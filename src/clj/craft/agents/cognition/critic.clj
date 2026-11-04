(ns craft.agents.cognition.critic
  "Environmental Critic e Reflexion Engine para Aprendizado Contínuo (Voyager Triad).
   Inspeciona o estado real do mundo físico no servidor (Ground Truth),
   elimina alucinações de sucesso em malha aberta e sintetiza hipóteses de correção (CA-041)."
  (:require [clojure.string :as str]))

(def enclosure->quadrant
  {:pen-north-east :NE
   :pen-south-east :SE
   :pen-south-west :SW
   :pen-north-west :NW})

(def quadrant->name
  {:NE "Reserva dos Pandas (NE)"
   :SE "Savana dos Elefantes (SE)"
   :SW "Bosque das Capivaras (SW)"
   :NW "Tundra do Ártico (NW)"
   :OUT "Fora do Cercado / Corredores (OUT)"})

(defn normalize-species [species-str]
  (cond
    (nil? species-str) "Capybara"
    (str/includes? (str/lower-case species-str) "capybara") "Capybara"
    (str/includes? (str/lower-case species-str) "capivara") "Capybara"
    (str/includes? (str/lower-case species-str) "fox") "Fox"
    (str/includes? (str/lower-case species-str) "raposa") "Fox"
    (str/includes? (str/lower-case species-str) "armadillo") "Armadillo"
    (str/includes? (str/lower-case species-str) "tatu") "Armadillo"
    (str/includes? (str/lower-case species-str) "deer") "Deer"
    (str/includes? (str/lower-case species-str) "cervo") "Deer"
    (str/includes? (str/lower-case species-str) "cerva") "Deer"
    (str/includes? (str/lower-case species-str) "veado") "Deer"
    (str/includes? (str/lower-case species-str) "servo") "Deer"
    (str/includes? (str/lower-case species-str) "elephant") "Elephant"
    (str/includes? (str/lower-case species-str) "elefante") "Elephant"
    (str/includes? (str/lower-case species-str) "giraffe") "Giraffe"
    (str/includes? (str/lower-case species-str) "girafa") "Giraffe"
    (str/includes? (str/lower-case species-str) "zebra") "Zebra"
    (str/includes? (str/lower-case species-str) "mammoth") "Mammoth"
    (str/includes? (str/lower-case species-str) "mamute") "Mammoth"
    (str/includes? (str/lower-case species-str) "panda") "Panda"
    (str/includes? (str/lower-case species-str) "duck") "Duck"
    (str/includes? (str/lower-case species-str) "pato") "Duck"
    (str/includes? (str/lower-case species-str) "tortoise") "Tortoise"
    (str/includes? (str/lower-case species-str) "tartaruga") "Tortoise"
    (str/includes? (str/lower-case species-str) "hedgehog") "Hedgehog"
    (str/includes? (str/lower-case species-str) "ourico") "Hedgehog"
    (str/includes? (str/lower-case species-str) "ouriço") "Hedgehog"
    :else species-str))

(defn parse-sanctuary-snapshot
  "Converte a resposta do comando SANCTUARY em um mapa estruturado por quadrante e espécie."
  [raw-str]
  (let [content (if (str/starts-with? (or raw-str "") "OK|")
                  (subs raw-str 3)
                  raw-str)
        matches (re-seq #"([A-Z_]+):([A-Za-z0-9_]+)=(\d+)" content)]
    (reduce (fn [acc [_ quad species count-str]]
              (let [q (keyword quad)
                    c (Integer/parseInt count-str)]
                (assoc-in acc [q species] c)))
            {}
            matches)))

(def species->pt
  {"Capybara"  {:singular "capivara"  :plural "capivaras"  :article "todas as capivaras"}
   "Deer"      {:singular "cervo"     :plural "cervos"     :article "todos os cervos"}
   "Elephant"  {:singular "elefante"  :plural "elefantes"  :article "todos os elefantes"}
   "Giraffe"   {:singular "girafa"    :plural "girafas"    :article "todas as girafas"}
   "Zebra"     {:singular "zebra"     :plural "zebras"     :article "todas as zebras"}
   "Mammoth"   {:singular "mamute"    :plural "mamutes"    :article "todos os mamutes"}
   "Fox"       {:singular "raposa"    :plural "raposas"    :article "todas as raposas"}
   "Armadillo" {:singular "tatu"      :plural "tatus"      :article "todos os tatus"}
   "Duck"      {:singular "pato"      :plural "patos"      :article "todos os patos"}
   "Tortoise"  {:singular "tartaruga" :plural "tartarugas" :article "todas as tartarugas"}
   "Hedgehog"  {:singular "ouriço"    :plural "ouriços"    :article "todos os ouriços"}
   "Panda"     {:singular "panda"     :plural "pandas"     :article "todos os pandas"}})

(defn evaluate-animal-containment
  "Avalia o estado empírico de contenção do animal após a tentativa de resgate."
  ([snapshot species-id target-enclosure-id]
   (evaluate-animal-containment snapshot species-id target-enclosure-id nil))
  ([snapshot species-id target-enclosure-id opts]
   (let [require-all?       (get opts :require-all? (nil? opts))
         initial-contained  (:initial-contained opts)
         min-contained      (get opts :min-contained 1)
         norm-species       (normalize-species species-id)
         target-quad        (get enclosure->quadrant target-enclosure-id :SW)
         enc-title          (get quadrant->name target-quad (str "Cercado (" (name target-quad) ")"))
         all-quads          #{:NE :NW :SE :SW :OUT}
         other-quads        (disj all-quads target-quad)
         escaped-list       (reduce (fn [acc q]
                                      (let [c (get-in snapshot [q norm-species] 0)]
                                        (if (pos? c)
                                          (conj acc {:quadrant q :count c})
                                          acc)))
                                    []
                                    other-quads)
         total-escaped      (reduce + 0 (map :count escaped-list))
         target-count       (get-in snapshot [target-quad norm-species] 0)
         rescued-individual? (and (not require-all?)
                                  (if (some? initial-contained)
                                    (> target-count initial-contained)
                                    (>= target-count min-contained)))
         success?           (or (zero? total-escaped) rescued-individual?)]
     (if success?
       {:success?          true
        :verdict           :goal-achieved
        :escaped-count     total-escaped
        :contained-count   target-count
        :target-quadrant   target-quad
        :diagnostic        (if (zero? total-escaped)
                             (str "Sucesso comprovado no mundo real: "
                                  (get-in species->pt [norm-species :article] (str "todos os " (str/lower-case norm-species) "s"))
                                  " (" target-count ") estão contidos no " enc-title ".")
                             (str "Sucesso comprovado no mundo real: "
                                  (get-in species->pt [norm-species :singular] (str/lower-case norm-species))
                                  " resgatado e seguro no " enc-title " (total contidos: " target-count ")."))}
       {:success?           false
        :verdict            :goal-failed
        :escaped-count      total-escaped
        :escaped-locations  escaped-list
        :target-quadrant    target-quad
        :diagnostic         (str "Falha empírica: " total-escaped " "
                                 (if (> total-escaped 1)
                                   (get-in species->pt [norm-species :plural] (str (str/lower-case norm-species) "s"))
                                   (get-in species->pt [norm-species :singular] (str/lower-case norm-species)))
                                 (if (> total-escaped 1) " continuam" " continua")
                                 " fora do " enc-title " (" (str/join ", " (map #(str (:count %) " em " (name (:quadrant %))) escaped-list)) ").")}))))

(defn generate-reflection
  "Gera o relatório de Reflexion para que o System 2 sintetize a próxima iteração da skill."
  [failed-eval previous-skill]
  (let [cur-ver  (get previous-skill :skill/version 1)
        next-ver (inc cur-ver)]
    {:reflection/status          :self-correction-required
     :reflection/attempt         cur-ver
     :reflection/next-version    next-ver
     :reflection/failure-reason  (:diagnostic failed-eval)
     :reflection/hypothesis      "A condução por isca em malha aberta falhou: o animal move-se mais devagar ou perdeu o foco. É necessário acoplamento físico com laço (minecraft:lead) e verificação do cercado antes de fechar o portão."
     :reflection/suggested-modifications [:use-lead :wait-for-animal-proximity :verify-before-gate-close]
     :reflection/voice-line      "Droga, soldado! Ela não me seguiu, continua solta no Santuário! Deixa eu tentar de novo usando o laço e checando o cercado antes de trancar o portão!"}))

;; ----------------------------------------------------------------------------
;; Environmental Critic Polimórfico Multimodal (P0.1 - Voyager Universal Ground Truth)
;; ----------------------------------------------------------------------------

(defmulti evaluate-goal-achievement
  "Avalia o sucesso empírico de uma meta física no servidor com base em seu tipo.
   Despacha por :goal/type (:animal-containment, :inventory-delta, :waypoint-reached, :voxel-blueprint)."
  (fn [goal-type _before _after & _opts] goal-type))

(defmethod evaluate-goal-achievement :animal-containment
  [_ _before snapshot & [opts]]
  (let [species  (:species opts "naturalist:capybara")
        pen-id   (:pen-id opts :pen-south-west)]
    (evaluate-animal-containment snapshot species pen-id opts)))

(defmethod evaluate-goal-achievement :animal-rescue
  [_ before after & [opts]]
  (evaluate-goal-achievement :animal-containment before after opts))

(defmethod evaluate-goal-achievement :inventory-delta
  [_ before after & [opts]]
  (let [target-item  (:target/item opts)
        target-count (:target/count opts 1)
        count-before (get-in before [:inventory target-item] 0)
        count-after  (get-in after [:inventory target-item] 0)
        delta        (- count-after count-before)
        success?     (>= delta target-count)]
    (if success?
      {:success?      true
       :verdict       :goal-achieved
       :item          target-item
       :delta         delta
       :target-count  target-count
       :diagnostic    (str "Sucesso empírico de inventário: coletado +" delta " de " target-item " (meta: " target-count ").")}
      {:success?      false
       :verdict       :goal-failed
       :item          target-item
       :delta         delta
       :missing-count (- target-count delta)
       :diagnostic    (str "Falha empírica de inventário: esperado +" target-count " de " target-item ", mas delta foi apenas +" (max 0 delta) ".")})))

(defmethod evaluate-goal-achievement :waypoint-reached
  [_ _before after & [opts]]
  (let [target-pos  (:target/pos opts [0.0 0.0 0.0])
        current-pos (:pos after [0.0 0.0 0.0])
        tolerance   (:tolerance opts 2.5)
        [tx ty tz]  target-pos
        [cx cy cz]  current-pos
        dx (- cx tx)
        dy (- cy ty)
        dz (- cz tz)
        dist (Math/sqrt (+ (* dx dx) (* dy dy) (* dz dz)))
        success? (<= dist tolerance)]
    (if success?
      {:success?    true
       :verdict     :goal-achieved
       :distance    dist
       :tolerance   tolerance
       :target-pos  target-pos
       :current-pos current-pos
       :diagnostic  (str "Sucesso empírico de navegação: agente atingiu proximidade (" (format "%.2f" dist) " blocos) do waypoint.")}
      {:success?    false
       :verdict     :goal-failed
       :distance    dist
       :tolerance   tolerance
       :target-pos  target-pos
       :current-pos current-pos
       :diagnostic  (str "Falha empírica de navegação: agente a " (format "%.2f" dist) " blocos do destino (tolerância máxima: " tolerance " blocos).")})))

(defmethod evaluate-goal-achievement :voxel-blueprint
  [_ _before _after & [opts]]
  (let [blueprint      (:blueprint opts [])
        voxel-fn       (:voxel-reader opts (constantly nil))
        results        (mapv (fn [{:keys [pos block]}]
                               (let [actual (voxel-fn pos)]
                                 {:pos pos :expected block :actual actual :match? (= block actual)}))
                             blueprint)
        matches        (filter :match? results)
        total          (count blueprint)
        match-count    (count matches)
        ratio          (if (pos? total) (/ (double match-count) total) 1.0)
        min-ratio      (:min-completion opts 1.0)
        success?       (>= ratio min-ratio)]
    (if success?
      {:success?       true
       :verdict        :goal-achieved
       :completion     ratio
       :total-voxels   total
       :matched-voxels  match-count
       :diagnostic     (str "Sucesso empírico de construção: " match-count "/" total " blocos (" (int (* 100 ratio)) "%) em conformidade.")}
      {:success?       false
       :verdict        :goal-failed
       :completion     ratio
       :total-voxels   total
       :matched-voxels  match-count
       :mismatches     (vec (remove :match? results))
       :diagnostic     (str "Falha empírica de construção: completude de " (int (* 100 ratio)) "% abaixo do limiar requerido (" (int (* 100 min-ratio)) "%).")})))

(defmethod evaluate-goal-achievement :cooperative-goal
  [_ before after & [opts]]
  (let [sub-goals  (:sub-goals opts [])
        sub-evals  (mapv (fn [sg]
                           (evaluate-goal-achievement (:goal/type sg) before after (:goal/opts sg)))
                         sub-goals)
        failures   (remove :success? sub-evals)
        success?   (and (seq sub-goals) (empty? failures))]
    (if success?
      {:success?     true
       :verdict      :cooperative-goal-achieved
       :sub-evals    sub-evals
       :diagnostic   (str "Sucesso cooperativo comprovado: todas as " (count sub-goals) " metas conjuntas foram satisfeitas simultaneamente.")}
      {:success?     false
       :verdict      :cooperative-goal-failed
       :failures     (vec failures)
       :sub-evals    sub-evals
       :diagnostic   (str "Falha cooperativa: " (count failures) " de " (count sub-goals) " metas conjuntas falharam ("
                          (str/join "; " (map :diagnostic failures)) ").")})))

(defmethod evaluate-goal-achievement :default
  [goal-type _before _after & _opts]
  {:success?   false
   :verdict    :unknown-goal-type
   :goal-type  goal-type
   :diagnostic (str "Tipo de meta desconhecido pelo Environmental Critic: " goal-type)})

;; ----------------------------------------------------------------------------
;; Sentinelas Contínuas de Invariância em Tempo de Voo (P0.2 - Early Abort)
;; ----------------------------------------------------------------------------

(defn check-runtime-invariants
  "Inspeciona restrições físicas contínuas durante a marcha do bot.
   Se violado, dispara Early Abort para evitar encerramento às cegas de rotinas quebradas."
  [invariants observation]
  (or (some
       (fn [inv]
         (case (:invariant inv)
           :max-target-distance
           (let [max-d   (:max-distance inv 8.0)
                 bot-pos (:bot-pos observation [0.0 0.0 0.0])
                 tgt-pos (:target-pos observation [0.0 0.0 0.0])
                 [bx by bz] bot-pos
                 [tx ty tz] tgt-pos
                 dx (- bx tx) dy (- by ty) dz (- bz tz)
                 dist (Math/sqrt (+ (* dx dx) (* dy dy) (* dz dz)))]
             (when (> dist max-d)
               {:violated? true
                :invariant :max-target-distance
                :distance dist
                :max-distance max-d
                :diagnostic (str "Invariante violada: alvo afastou-se (" (format "%.2f" dist) " blocos) além do limiar (" max-d " blocos).")}))

           :leash-attached
           (when-not (:leashed? observation false)
             {:violated? true
              :invariant :leash-attached
              :diagnostic "Invariante violada: acoplamento físico com laço foi rompido."})

           :health-above
           (let [min-hp (:min-health inv 6.0)
                 cur-hp (:health observation 20.0)]
             (when (< cur-hp min-hp)
               {:violated? true
                :invariant :health-above
                :health cur-hp
                :min-health min-hp
                :diagnostic (str "Invariante de sobrevivência violada: vida (" cur-hp ") abaixo do mínimo seguro (" min-hp ").")}))

           nil))
       invariants)
      {:violated? false}))

(defn generate-early-abort-reflection
  "Gera diagnóstico causal e hipótese de recuperação imediata para Early Abort em tempo de voo."
  [invariant-violation current-step]
  {:reflection/status          :early-abort-triggered
   :reflection/invariant       (:invariant invariant-violation)
   :reflection/step-label      (:label current-step "Passo em execução")
   :reflection/step-op         (:op current-step)
   :reflection/diagnostic      (:diagnostic invariant-violation)
   :reflection/cause           (case (:invariant invariant-violation)
                                 :max-target-distance :target-lost-tracking
                                 :leash-attached      :lead-snapped
                                 :health-above        :critical-damage-hazard
                                 :unknown-invariance-failure)
   :reflection/suggested-action (case (:invariant invariant-violation)
                                  :max-target-distance :halt-and-relure
                                  :leash-attached      :re-equip-and-releash
                                  :health-above        :retreat-to-shelter
                                  :abort-to-patrol)
   :reflection/voice-line      (case (:invariant invariant-violation)
                                 :max-target-distance "Atenção soldado! O bicho se afastou demais! Parando imediatamente antes de trancar o portão vazio!"
                                 :leash-attached      "O laço arrebentou, soldado! Parando a marcha pra prender de novo!"
                                 :health-above        "Dano crítico! Recuando para posição segura!"
                                 "Anomalia detectada em tempo de voo! Abortando sequência para evitar erro no mundo!")})
