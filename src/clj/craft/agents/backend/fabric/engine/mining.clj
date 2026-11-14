(ns craft.agents.backend.fabric.engine.mining
  "Modo Mineracao Continua Autonoma da Karlach (A* 3D estrito, sem no-clip e sem
   teleporte exceto resgate a > 48m)."
  (:require [clojure.string :as str]
            [craft.agents.backend.fabric.actions :as actions
             :refer [collect-nearby-drops! inspect-inventory karlach-say!
                     locate-nearby-ores mine-block! ore-pt-names query-online-players]]
            [craft.agents.backend.fabric.locomotion :as loco]
            [craft.agents.backend.fabric.navigation :refer [compute-pitch compute-yaw request-path3d]]
            [craft.agents.backend.fabric.transport :refer [send-bridge-cmd! uformat]]
            [craft.agents.npc.body :as body]))

(defn tick-mining!
  "Um tick do modo :mining. `st` e o snapshot de nav-state; `tick` vem de `env`."
  [{:keys [nav-state tick]} st]
  (let [[cx cy cz] (:pos st)]
    (let [players (query-online-players)
          target-p (first (clojure.core/filter #(= (str/lower-case (:name %))
                                                  (str/lower-case (or (:mining-target st) "pace")))
                                              players))]
      (if target-p
        (let [tx (:x target-p)
              ty (:y target-p)
              tz (:z target-p)
              dx (- tx cx)
              dz (- tz cz)
              dist-to-player (Math/sqrt (+ (* dx dx) (* (- (double ty) (double cy)) (- (double ty) (double cy))) (* dz dz)))]
          (cond
            ;; Teleporte de resgate apenas em distancia extrema (> 48m / outro bioma)
            (> dist-to-player 48.0)
            (do
              (actions/teleport-agent-to! "Karlach" [tx ty tz])
              (karlach-say! "Você avançou muito na mina, soldado! Acompanhando seus passos!")
              (swap! nav-state assoc :mode :bodyguard :pos [tx ty tz] :active-mining nil))
    
            ;; Se o jogador se afastar mais de 16m, suspende a picareta e caminha ate ele a pe
            (> dist-to-player 16.0)
            (do
              (karlach-say! "Avançando com você pela mina, soldado!")
              (swap! nav-state assoc :mode :bodyguard :active-mining nil :follow-path (or (request-path3d [cx cy cz] [tx ty tz] 2.2) [])))
    
            ;; Execução motora realista
            :else
            (if-let [am (:active-mining st)]
              ;; Bloco ativo sendo minerado com progresso e sons reais
              (let [bx (:x am)
                    by (:y am)
                    bz (:z am)
                    bid (:block-id am)
                    ax (:air-x am bx)
                    ay (:air-y am by)
                    az (:air-z am bz)
                    stand-x (+ (double ax) 0.5)
                    stand-y (double ay)
                    stand-z (+ (double az) 0.5)
                    odx (- (+ (double bx) 0.5) cx)
                    ody (- (+ (double by) 0.5) (+ (double cy) 1.62))
                    odz (- (+ (double bz) 0.5) cz)
                    hdist (Math/sqrt (+ (* odx odx) (* odz odz)))
                    total-ore-d (Math/sqrt (+ (* odx odx) (* (+ ody 1.62) (+ ody 1.62)) (* odz odz)))
                    yaw (compute-yaw odx odz)
                    pitch (compute-pitch ody hdist)]
                ;; Se estiver a mais de 2.4m do bloco de minério, navega via A* 3D pelos túneis livres (NUNCA no-clip!)
                (if (> total-ore-d 2.4)
                  (let [path-queue (:path am [])
                        active-path (if (empty? path-queue)
                                      (request-path3d [cx cy cz] [stand-x stand-y stand-z] 1.0)
                                      path-queue)]
                    (if-let [next-node (first active-path)]
                      (let [{[step-x step-y step-z] :pos
                             nyaw :yaw npitch :pitch rpath :path}
                            (loco/approach-step {:pos [cx cy cz] :path active-path
                                                 :speed 0.24 :yaw (:yaw st)})]
                        (body/move! {:agent :karlach :agent-name "Karlach"
                                     :x step-x :y step-y :z step-z :yaw nyaw :pitch npitch
                                     :sprint true :priority :bt})
                        (swap! nav-state #(-> %
                                              (assoc :pos [step-x step-y step-z] :yaw nyaw :pitch npitch)
                                              (assoc-in [:active-mining :path] rpath))))
                      ;; Sem caminho A* alcançável: minério inacessível sem quebrar outras rochas.
                      ;; Descarta sem atravessar parede!
                      (swap! nav-state #(-> %
                                            (assoc :active-mining nil)
                                            (update :unreachable-ores (fnil conj #{}) [bx by bz])))))
                  ;; Posicionada com firmeza ao lado do bloco: martela a picareta!
                  (let [cur-ticks (inc (:ticks am 0))
                        max-ticks (:max-ticks am 14)]
                    (body/move! {:agent :karlach :agent-name "Karlach" :x cx :y cy :z cz
                                 :yaw yaw :pitch pitch :priority :bt})
                    ;; Garante a picareta de netherite visível na mão principal durante toda a animação
                    (when (or (= cur-ticks 1) (zero? (mod cur-ticks 3)))
                      (send-bridge-cmd! "CMD|item replace entity Karlach weapon.mainhand with minecraft:netherite_pickaxe")
                      (send-bridge-cmd! "CMD|item replace entity Karlach hotbar.0 with minecraft:netherite_pickaxe"))
                    ;; Batidas rítmicas a cada 3 ticks
                    (when (zero? (mod cur-ticks 3))
                      (send-bridge-cmd! "SWING|Karlach")
                      (send-bridge-cmd!
                       (uformat "CMD|playsound minecraft:block.deepslate.hit master @a %.1f %.1f %.1f 0.8 1.0"
                               (+ (double bx) 0.5) (+ (double by) 0.5) (+ (double bz) 0.5)))
                      (send-bridge-cmd!
                       (uformat "CMD|particle minecraft:block %s %.2f %.2f %.2f 0.2 0.2 0.2 0.02 3 normal"
                               bid (+ (double bx) 0.5) (+ (double by) 0.5) (+ (double bz) 0.5))))
                    (if (>= cur-ticks max-ticks)
                      ;; Tempo de quebra concluído: estoura o bloco!
                      (do
                        (send-bridge-cmd! "SWING|Karlach")
                        (let [mres (mine-block! "Karlach" bx by bz)]
                          (when (:success? mres)
                            (send-bridge-cmd!
                             (uformat "CMD|particle minecraft:block %s %.2f %.2f %.2f 0.35 0.35 0.35 0.08 16 normal"
                                     bid (+ (double bx) 0.5) (+ (double by) 0.5) (+ (double bz) 0.5)))
                            (send-bridge-cmd!
                             (uformat "CMD|playsound minecraft:block.deepslate.break master @a %.1f %.1f %.1f 1.0 1.0"
                                     (double bx) (double by) (double bz)))
                            (collect-nearby-drops! "Karlach" 6.0)))
                        (swap! nav-state #(-> %
                                              (assoc :active-mining nil)
                                              (update :mining-blocks-broken (fnil inc 0)))))
                      ;; Continua quebrando
                      (swap! nav-state assoc-in [:active-mining :ticks] cur-ticks)))))
              ;; Se não tiver bloco ativo, busca o próximo minério exposto a cada 5 ticks
              (when (zero? (mod tick 5))
                (collect-nearby-drops! "Karlach" 6.0)
                (let [filter-str (or (:mining-filter st) "")
                      ores (locate-nearby-ores "Karlach" 12.0 filter-str)
                      unreach (:unreachable-ores st #{})
                      reachable-ores (filter #(not (contains? unreach [(:x %) (:y %) (:z %)])) ores)]
                  (if-let [target-ore (first reachable-ores)]
                    (do
                      (send-bridge-cmd! "CMD|item replace entity Karlach weapon.mainhand with minecraft:netherite_pickaxe")
                      (send-bridge-cmd! "CMD|item replace entity Karlach hotbar.0 with minecraft:netherite_pickaxe")
                      (swap! nav-state assoc :active-mining
                             {:x (:x target-ore)
                              :y (:y target-ore)
                              :z (:z target-ore)
                              :block-id (or (:block-id target-ore) "minecraft:deepslate")
                              :air-x (:air-x target-ore)
                              :air-y (:air-y target-ore)
                              :air-z (:air-z target-ore)
                              :ticks 0
                              :max-ticks 14}))
                    ;; Sem mais minérios expostos acessíveis!
                    (let [loot-res (collect-nearby-drops! "Karlach" 6.0)
                          broken-cnt (:mining-blocks-broken @nav-state 0)
                          ore-label (get ore-pt-names filter-str (if (str/blank? filter-str) "minérios" filter-str))
                          inv (inspect-inventory "Karlach")
                          loot-summary (if (empty? (:items inv))
                                         "nada"
                                         (str/join ", " (map (fn [[iid c]] (str c "x " (str/replace iid #"minecraft:" ""))) (:items inv))))]
                      (if (> broken-cnt 0)
                        (karlach-say! (str "Veio de " ore-label " extraído por completo (" broken-cnt " blocos)! Já looteei pro meu inventário: [" loot-summary "]! Se quiser o saque, só me pedir! 🔥 ⛏️"))
                        (karlach-say! (str "Não encontrei mais veios expostos de " ore-label " acessíveis por aqui, soldado!")))
                      (swap! nav-state assoc :mode :bodyguard :mining-blocks-broken 0 :active-mining nil :unreachable-ores #{}))))))))
        (swap! nav-state assoc :mode :bodyguard :active-mining nil :unreachable-ores #{})))
))
