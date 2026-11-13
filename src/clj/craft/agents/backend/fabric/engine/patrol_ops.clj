(ns craft.agents.backend.fabric.engine.patrol-ops
  "Passos de patrulha com animais (guia, contencao e critic empirico): :attach-lead,
   :detach-lead, :await-contained e :critic. Cada funcao consome o passo corrente de
   `(:steps nav-state)` e atualiza nav-state / engine-ctx."
  (:require [clojure.string]
            [craft.agents.backend.fabric.actions :refer [karlach-say!]]
            [craft.agents.backend.fabric.navigation :refer [compute-yaw generate-walk-steps]]
            [craft.agents.backend.fabric.sanctuary :refer [compute-enclosure-funnel
                                                           sanctuary-enclosures-by-id]]
            [craft.agents.backend.fabric.telemetry :refer [record-cognitive-event!]]
            [craft.agents.backend.fabric.transport :refer [send-bridge-cmd! uformat]]
            [craft.agents.cognition.critic :as critic]))

(defn attach-lead!
  "Op :attach-lead: aproxima do animal se longe e lassa com a guia."
  [{:keys [nav-state]} st cur-step]
  (let [[cx cy cz] (:pos st)]
    (let [species (or (:species cur-step) "animal")
          norm-sp (critic/normalize-species species)
          mob-resp (send-bridge-cmd! (str "LOCATE_MOB|Karlach|" norm-sp))
          mob-info (when (and mob-resp (clojure.string/starts-with? mob-resp "OK|"))
                     (let [parts (clojure.string/split mob-resp #"\|")]
                       {:pos [(Double/parseDouble (nth parts 1))
                              (Double/parseDouble (nth parts 2))
                              (Double/parseDouble (nth parts 3))]
                        :type (nth parts 4)}))
          mob-dist (when mob-info
                     (let [[mx my mz] (:pos mob-info)]
                       (Math/sqrt (+ (Math/pow (- mx cx) 2) (Math/pow (- my cy) 2) (Math/pow (- mz cz) 2)))))]
      (if (and mob-info mob-dist (> mob-dist 2.2))
        ;; O animal está além do alcance físico (2.2 blocos): aproxima-se antes de usar a corda
        (let [close-steps (generate-walk-steps [cx cy cz] (:pos mob-info) (str "Aproximando-se de " norm-sp " para laçar") {:radius 1.2})]
          (swap! nav-state update :steps #(vec (concat close-steps [%]))))
        (do
          (send-bridge-cmd! "CMD|item replace entity Karlach weapon.offhand with minecraft:lead")
          (when mob-info
            (let [[mx _ mz] (:pos mob-info)
                  look-yaw (compute-yaw (- mx cx) (- mz cz))]
              (send-bridge-cmd! (uformat "MOVE|Karlach|%.3f|%.3f|%.3f|%.1f|0.0|false|false" cx cy cz look-yaw))))
          (let [leash-res (send-bridge-cmd! (str "LEASH|Karlach|" norm-sp))]
            (if (clojure.string/starts-with? (or leash-res "") "ERR|TOO_FAR")
              (let [close-steps (generate-walk-steps [cx cy cz] (or (:pos mob-info) [cx cy cz]) (str "Fechando distancia de " norm-sp) {:radius 1.0})]
                (swap! nav-state update :steps #(vec (concat close-steps [%]))))
              (do
                (send-bridge-cmd! "CMD|execute at Karlach run playsound minecraft:entity.leash_knot.place master @a ~ ~ ~ 1.0 1.0")
                (swap! nav-state #(-> %
                                      (assoc :leashed? true)
                                      (update :steps (fn [s] (vec (rest s))))))))))))
))

(defn detach-lead!
  "Op :detach-lead: solta a guia."
  [{:keys [nav-state]}]
  (send-bridge-cmd! "UNLEASH|Karlach")
  (send-bridge-cmd! "CMD|item replace entity Karlach weapon.offhand with minecraft:air")
  (send-bridge-cmd! "CMD|execute at Karlach run playsound minecraft:entity.leash_knot.break master @a ~ ~ ~ 1.0 1.0")
  (swap! nav-state #(-> %
                        (assoc :leashed? false)
                        (update :steps (fn [s] (vec (rest s)))))))

(defn await-contained!
  "Op :await-contained: espera a confirmacao sensorial de que o animal entrou no cercado."
  [{:keys [nav-state engine-ctx-atom tick make-agents-map]} st cur-step]
  (let [[cx cy cz] (:pos st)]
    (let [species    (:species cur-step "naturalist:capybara")
          pen-id     (:pen-id cur-step :pen-south-west)
          init-c     (:initial-contained cur-step 0)
          target-q   (get critic/enclosure->quadrant pen-id :SW)
          elapsed    (:elapsed-ticks cur-step 0)
          timeout    (:timeout cur-step 80)
          raw-snap   (when (zero? (mod elapsed 5)) (send-bridge-cmd! "SANCTUARY"))
          snap       (when raw-snap (critic/parse-sanctuary-snapshot raw-snap))
          curr-c     (when snap (get-in snap [target-q (critic/normalize-species species)] 0))
          contained? (and curr-c (> curr-c init-c))]
      (if (or contained? (>= elapsed timeout))
        (do
          (if contained?
            (do
              (record-cognitive-event! engine-ctx-atom :perception "Ground Truth Sensor"
                                       (str "Animal confirmado dentro do cercado " (name target-q) " (" (critic/normalize-species species) "). Fechando portão."))
              (karlach-say! "Entrou! Fechando o portão pra ninguém escapar!"))
            (do
              (record-cognitive-event! engine-ctx-atom :perception-warn "Ground Truth Sensor"
                                       (str "Tempo limite de espera de entrada (" elapsed " ticks). Concluindo trancamento."))
              (karlach-say! "Atenção ao portão, trancando agora!")))
          (swap! nav-state update :steps #(vec (rest %))))
        (do
          (swap! engine-ctx-atom assoc
                 :tick tick
                 :leases {:legs {:owner :await-contained}
                          :hands nil
                          :gaze {:owner :portal-watch}}
                 :agents (make-agents-map (:label cur-step) [cx cy cz]))
          (swap! nav-state update-in [:steps 0 :elapsed-ticks] (fnil inc 0)))))
))

(defn critic!
  "Op :critic: verifica empiricamente a contencao; na falha monta a Tentativa 2."
  [{:keys [nav-state engine-ctx-atom]} _st cur-step]
    (let [raw-snap  (send-bridge-cmd! "SANCTUARY")
          snap      (critic/parse-sanctuary-snapshot raw-snap)
          species   (:species cur-step "naturalist:capybara")
          pen-id    (:pen-id cur-step :pen-south-west)
          init-c    (:initial-contained cur-step)
          eval-res  (critic/evaluate-goal-achievement
                     :animal-containment nil snap
                     {:species species :pen-id pen-id
                      :require-all? false :initial-contained init-c})
          entity    (:entity cur-step "Capivara Nadadora")
          attempt   (get cur-step :attempt 1)]
      (if (:success? eval-res)
        (do
          (record-cognitive-event! engine-ctx-atom :critic "Environmental Critic"
                                   (str "SUCESSO EMPÍRICO COMPROVADO: " (:diagnostic eval-res)))
          (let [enc-title (or (:enclosure/name (get sanctuary-enclosures-by-id pen-id))
                              (get critic/quadrant->name (get critic/enclosure->quadrant pen-id :SW))
                              "cercado")]
            (karlach-say! (str "Missão cumprida, soldado! " entity " sã e salva no " enc-title ", tudo verificado!")))
          (swap! nav-state update :steps #(vec (rest %))))
        (if (>= attempt 2)
          (do
            (record-cognitive-event! engine-ctx-atom :critic-fail "Environmental Critic"
                                     "Tentativas de resgate concluídas. Encerrando ciclo para evitar loop.")
            (karlach-say! (str "Soldado, portão trancado! O " entity " tá agitado lá fora, melhor a gente cuidar dele daqui a pouco."))
            (swap! nav-state assoc :mode :sanctuary-patrol :steps []))
          (let [reflection (critic/generate-reflection eval-res {:skill/id (:goal-id cur-step) :skill/version 1})
                next-v     2]
            (record-cognitive-event! engine-ctx-atom :critic-fail "Environmental Critic"
                                     (str "FALHA EMPÍRICA DETECTADA: " (:diagnostic eval-res)))
            (record-cognitive-event! engine-ctx-atom :reflexion "System 2 Reflexion"
                                     "Auto-correção disparada: Tentativa 2 com condução cuidadosa até o cercado.")
            (karlach-say! (:reflection/voice-line reflection))
            (let [enc-info (get sanctuary-enclosures-by-id pen-id)
                  portal-info (or (:enclosure/portal enc-info)
                                  {:blocks (:gates cur-step)
                                   :normal (if (<= (first (or (first (:gates cur-step)) [-179 65 189])) -175) [-1.0 0.0 0.0] [1.0 0.0 0.0])
                                   :inside-anchor (:inside-pos cur-step)
                                   :outside-anchor (:outside-pos cur-step)
                                   :gate-block (:block cur-step)
                                   :gate-facing (:facing cur-step)})
                  funnel (compute-enclosure-funnel portal-info)
                  funnel-entry (:funnel-entry funnel)
                  pre-gate (:pre-gate funnel)
                  in-anc (:inside-anchor funnel)
                  deep-in (:deep-inside funnel)
                  retry-outside (or funnel-entry (:outside-pos cur-step) [-177.0 65.0 189.0])
                  to-corr (generate-walk-steps (:pos @nav-state) retry-outside (str "Saindo para buscar " entity " junto ao portão") {:radius 1.0})
                  to-feed (generate-walk-steps in-anc (:feed-pos cur-step) (str "Aproximando do comedouro de " entity) {:radius 1.5})
                  v2-steps (concat
                            [{:op :gate :open? true :pos (or (first (:gates cur-step)) [-179 65 189])
                              :gates (:gates cur-step) :block (:block cur-step) :facing (:facing cur-step) :label "Abrindo portão para recuperar animal"}
                             {:op :wait :ticks 8 :label "Aguardando portão abrir"}]
                            to-corr
                            [{:op :attach-lead :species species :label (str "Prendendo laço no " entity)}
                             {:op :wait :ticks 15 :label "Fixando laço"}
                             {:op :walk :target pre-gate :label "Realinhando animal ortogonalmente ao portão" :species species}
                             {:op :walk :target in-anc :label "Atravessando vão do portão" :species species}
                             {:op :walk :target deep-in :label (str "Conduzindo " entity " para o fundo do cercado") :species species}
                             {:op :await-contained :species species :pen-id pen-id :initial-contained init-c :timeout 80
                              :label "Aguardando confirmação sensorial de entrada do animal (Tentativa 2)"}
                             {:op :detach-lead :species species :label (str "Soltando a guia do " entity)}
                             {:op :wait :ticks 10 :label "Removendo guia"}
                             {:op :walk :target in-anc :label "Retornando ao portão para trancar"}
                             {:op :gate :open? false :pos (or (first (:gates cur-step)) [-179 65 189])
                              :gates (:gates cur-step) :block (:block cur-step) :facing (:facing cur-step) :label "Trancando portão"}
                             {:op :wait :ticks 8 :label "Conferindo tranca"}]
                            to-feed
                            [{:op :feed :pos (:feed-pos cur-step) :food (:bait-item cur-step) :species [species] :label (str "Alimentando: " entity)}
                             {:op :wait :ticks 20 :label (str entity " seguro e alimentado")}
                             {:op :critic :attempt 2 :goal-id (:goal-id cur-step) :species species :pen-id pen-id :entity entity
                              :initial-contained init-c
                              :animal-pos retry-outside
                              :gates (:gates cur-step) :block (:block cur-step) :facing (:facing cur-step)
                              :outside-pos pre-gate :inside-pos in-anc :feed-pos (:feed-pos cur-step) :bait-item (:bait-item cur-step)
                              :label "Critic: Segunda e Última Verificação"}])]
              (swap! nav-state assoc :steps (vec v2-steps)))))))
)
