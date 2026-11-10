(ns craft.agents.backend.fabric.sanctuary
  "Topologia espacial, cercados, portais e rotinas de alimentacao do Santuario de Animais."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [craft.agents.backend.fabric.transport :as transport :refer [uformat]]
            [craft.agents.regions :as regions]))

(def karlach-dialogues
  (try
    (:agent/dialogues (edn/read-string (slurp (io/file "agents/karlach.edn"))))
    (catch Throwable _ {})))

(defn current-enclosure-id
  "Identifica rigorosamente em qual cercado uma coordenada [x y z] se encontra.
   Usa os limites fisicos reais dos muros e portões do Santuário com margem de segurança
   para não considerar animais encostados na cerca como fugitivos."
  [[cx _ cz]]
  (cond
    (and (<= -171.8 cx -135.0) (<= 164.0 cz 178.0)) :pen-north-east
    (and (<= -171.8 cx -135.0) (<= 183.0 cz 197.5)) :pen-south-east
    (and (<= -215.5 cx -178.2) (<= 183.0 cz 197.5)) :pen-south-west
    (and (<= -215.5 cx -178.2) (<= 164.0 cz 178.0)) :pen-north-west
    :else nil))

(defn in-escaped-zone?
  "Determina se a coordenada [x y z] esta comprovadamente em zona de fuga externa (corredor central, lago ou praca)."
  [[cx _ cz]]
  (or
   (and (<= -178.0 cx -172.0) (<= 158.0 cz 202.0))
   (< cz 163.5)
   (> cz 198.0)
   (< cx -216.0)
   (> cx -134.0)))

(defn barks-for-current-position
  "Retorna exclusivamente as falas contextuais correspondentes aos animais do cercado onde a Karlach fisicamente está."
  [[cx cy cz]]
  (if-let [eid (current-enclosure-id [cx cy cz])]
    (get-in karlach-dialogues [:enclosures eid] [])
    []))

(defn build-sanctuary-route
  "Constrói a rota ampliada de patrulha perimetral, vigilância e alimentação do Santuário diretamente de sanctuary.edn + karlach.edn,
   cobrindo a Praça Central, Mirante Norte do Lago, Esplanada Leste, Pátio Sul e Alameda Oeste, intercalando os 4 cercados e o quarto."
  []
  (let [region   (regions/load-region-file "agents/regions/sanctuary.edn")
        karlach  (try (edn/read-string (slurp (io/file "agents/karlach.edn"))) (catch Throwable _ {}))
        cor-barks (get-in karlach [:agent/dialogues :corridors])
        enc-barks (get-in karlach [:agent/dialogues :enclosures])
        sp-barks  (get-in karlach [:agent/dialogues :species])
        enclosures-map (into {}
                             (map (fn [enc]
                                    (let [eid    (:enclosure/id enc)
                                          portal (:enclosure/portal enc)
                                          sp-vec (:enclosure/species enc [])
                                          barks  (vec (concat (get enc-barks eid [])
                                                              (keep #(get sp-barks %) sp-vec)))]
                                      [eid
                                       {:type        :enclosure
                                        :id          eid
                                        :name        (:enclosure/name enc (name eid))
                                        :outside     (:outside-anchor portal)
                                        :gates       (:blocks portal)
                                        :gate-block  (:gate-block portal "minecraft:dark_oak_fence_gate")
                                        :gate-facing (:gate-facing portal "west")
                                        :inside      (:inside-anchor portal)
                                        :feed-pos    (:enclosure/feed-pos enc (:inside-anchor portal))
                                        :food        (:enclosure/food enc "minecraft:apple")
                                        :species     sp-vec
                                        :residents   (:enclosure/residents enc [])
                                        :barks       barks
                                        :bark        (first barks)}]))
                                  (:region/enclosures region)))
        enc-nw (get enclosures-map :pen-north-west)
        enc-ne (get enclosures-map :pen-north-east)
        enc-se (get enclosures-map :pen-south-east)
        enc-sw (get enclosures-map :pen-south-west)

        extended-promenades
        [{:type :promenade :name "Praça Central"           :pos [-175.0 65.0 180.0] :hold-item "minecraft:apple"          :pause-ticks 40 :bark (:south cor-barks "Praça central inspecionada! Tudo em ordem.")}
         {:type :promenade :name "Posto Portão Tundra"     :pos [-177.0 65.0 171.0] :hold-item "minecraft:sweet_berries" :pause-ticks 30 :bark "Conferindo o cercado das raposas e mamutes da tundra."}
         enc-nw
         {:type :promenade :name "Mirante Norte do Lago"   :pos [-175.0 65.0 162.0] :hold-item "minecraft:golden_carrot" :pause-ticks 50 :bark (:north cor-barks "Bela vista do lago! As capivaras adoram nadar por aqui.")}
         {:type :promenade :name "Posto Portão dos Pandas" :pos [-173.0 65.0 171.0] :hold-item "minecraft:bamboo"        :pause-ticks 30 :bark "Passando pelo cercado dos pandas e camelos."}
         enc-ne
         {:type :promenade :name "Esplanada Leste"         :pos [-155.0 65.0 180.0] :hold-item "minecraft:wheat"         :pause-ticks 40 :bark (:east cor-barks "Ronda na esplanada leste. Perímetro leste seguro!")}
         {:type :promenade :name "Posto Portão da Savana"  :pos [-173.0 65.0 189.0] :hold-item "minecraft:hay_block"    :pause-ticks 30 :bark "Checando girafas, zebras e elefantes na savana."}
         enc-se
         {:type :promenade :name "Pátio Sul dos Bambus"    :pos [-175.0 65.0 195.0] :hold-item "minecraft:apple"          :pause-ticks 40 :bark "Pátio sul inspecionado. Nenhum intruso à vista!"}
         {:type :promenade :name "Posto Portão Capivaras"  :pos [-177.0 65.0 189.0] :hold-item "minecraft:apple"          :pause-ticks 30 :bark "Olho vivo no bosque das capivaras e cervos!"}
         enc-sw
         {:type :promenade :name "Alameda Oeste"           :pos [-195.0 65.0 180.0] :hold-item "minecraft:sweet_berries" :pause-ticks 40 :bark (:west cor-barks "Alameda oeste percorrida. Perímetro oeste 100% sob controle!")}]

        base-route (vec (filter some? extended-promenades))
        quarters   (when-let [q (:agent/quarters karlach)]
                     [{:type             :quarters
                       :id               (:id q :karlach-bedroom)
                       :name             (:name q "Quarto da Karlach")
                       :pos              (:pos q [-131.0 65.0 195.0])
                       :seat-pos         (:seat-pos q)
                       :seat-block       (:seat-block q)
                       :waypoints        (:waypoints q [[-136.0 65.0 183.0] [-133.0 65.0 194.0] (:pos q [-131.0 65.0 195.0])])
                       :return-waypoints (:return-waypoints q [[-133.0 65.0 194.0] [-136.0 65.0 183.0] [-173.0 65.0 180.0]])
                       :hold-item        (:hold-item q "minecraft:golden_apple")
                       :pause-ticks      (:pause-ticks q 140)
                       :barks            (:barks q ["Ah, finalmente um descanso pro meu motor infernal!"])
                       :bark             (first (:barks q))}])]
    (if (seq quarters)
      (into base-route quarters)
      base-route)))

(def sanctuary-route (build-sanctuary-route))

(defn build-patrol-route
  "Rota de PATRULHA: os postos do santuario SEM o Quarto da Karlach (CA-061).
   A patrulha nao deve visitar o quarto — ele so e usado em duas ocasiões, ambas
   explicitas: descanso pedido pelo jogador ou abrigo de chuva (CA-060)."
  [route]
  (vec (remove #(= (:type %) :quarters) route)))

(def patrol-route (build-patrol-route sanctuary-route))

(def sanctuary-portals-by-pos
  (try
    (let [reg (regions/load-region-file "agents/regions/sanctuary.edn")]
      (into {}
            (for [enc (:region/enclosures reg)
                  :let [portal (:enclosure/portal enc)
                        blocks (:blocks portal)]
                  b blocks]
              [(vec b) {:gates blocks
                        :block (:gate-block portal)
                        :facing (:gate-facing portal)}])))
    (catch Throwable _ {})))

(def sanctuary-enclosures-by-id
  (try
    (let [reg (regions/load-region-file "agents/regions/sanctuary.edn")]
      (into {} (map (fn [e] [(:enclosure/id e) e]) (:region/enclosures reg))))
    (catch Throwable _ {})))

(defn compute-enclosure-funnel
  "Calcula os waypoints do funil ortogonal de travessia do portão para evitar que animais
   na guia fiquem presos diagonalmente contra as quinas e batentes da cerca."
  [portal-info]
  (let [norm (:normal portal-info [-1.0 0.0 0.0])
        nx   (double (first norm))
        nz   (double (nth norm 2))
        out-anc (:outside-anchor portal-info [-177.0 65.0 189.0])
        in-anc  (:inside-anchor portal-info [-181.5 65.0 189.0])
        ox (double (first out-anc)) oy (double (second out-anc)) oz (double (nth out-anc 2))
        ix (double (first in-anc))  iy (double (second in-anc))  iz (double (nth in-anc 2))
        funnel-entry [(- ox (* nx 2.5)) oy (- oz (* nz 2.5))]
        pre-gate     [ox oy oz]
        deep-inside  [(+ ix (* nx 4.0)) iy (+ iz (* nz 4.0))]]
    {:funnel-entry funnel-entry
     :pre-gate pre-gate
     :inside-anchor in-anc
     :deep-inside deep-inside}))

(defn set-double-gate! [gates gate-block gate-facing open?]
  (let [open-str (if open? "true" "false")
        [[x1 y1 z1] [x2 y2 z2]] gates
        sound (if open? "minecraft:block.fence_gate.open" "minecraft:block.fence_gate.close")]
    (transport/send-bridge-cmd! "SWING|Karlach")
    (transport/send-bridge-cmd! (uformat "CMD|setblock %d %d %d %s[facing=%s,open=%s]" x1 y1 z1 gate-block gate-facing open-str))
    (transport/send-bridge-cmd! (uformat "CMD|setblock %d %d %d %s[facing=%s,open=%s]" x2 y2 z2 gate-block gate-facing open-str))
    (transport/send-bridge-cmd! (uformat "CMD|playsound %s block @a %d %d %d 1.0 1.0" sound x1 y1 z1))))

(defn open-portal-at-coord!
  "Abre o portão correspondente à coordenada [x y z] com animação e som."
  [[x y z]]
  (let [bpos [(int x) (int y) (int z)]]
    (if-let [portal-info (get sanctuary-portals-by-pos bpos)]
      (set-double-gate! (:gates portal-info) (:block portal-info) (:facing portal-info) true)
      (transport/send-bridge-cmd! (uformat "TOGGLE_PORTAL|%d|%d|%d|true" (int x) (int y) (int z))))))

(defn close-portal-at-coord!
  "Fecha o portão correspondente à coordenada [x y z] garantindo contenção dos animais."
  [[x y z]]
  (let [bpos [(int x) (int y) (int z)]]
    (if-let [portal-info (get sanctuary-portals-by-pos bpos)]
      (set-double-gate! (:gates portal-info) (:block portal-info) (:facing portal-info) false)
      (transport/send-bridge-cmd! (uformat "TOGGLE_PORTAL|%d|%d|%d|false" (int x) (int y) (int z))))))

(defn feed-enclosure-animals! [[fx fy fz] food species-list]
  (transport/send-bridge-cmd! (str "CMD|item replace entity Karlach weapon.mainhand with " food))
  (transport/send-bridge-cmd! "SWING|Karlach")
  (doseq [sp species-list]
    (let [is-cat? (or (= sp "minecraft:cat") (= sp "Cat") (= sp "cat"))]
      (when is-cat?
        (transport/send-bridge-cmd! "CMD|item replace entity Karlach weapon.mainhand with minecraft:cod")
        (transport/send-bridge-cmd! "SWING|Karlach"))
      ;; Cura e regenera os animais
      (transport/send-bridge-cmd!
       (uformat "CMD|execute positioned %.1f %.1f %.1f as @e[type=%s,distance=..22] run effect give @s minecraft:instant_health 1 1 true"
               fx fy fz sp))
      (transport/send-bridge-cmd!
       (uformat "CMD|execute positioned %.1f %.1f %.1f as @e[type=%s,distance=..22] run effect give @s minecraft:regeneration 20 1 true"
               fx fy fz sp))
      (if is-cat?
        (do
          ;; 1. Reprodução AFK para gatos adultos (Age: 0): apenas gatos em pé (Sitting:0b), preservando gatos sentados (Sitting:1b)
          (transport/send-bridge-cmd!
           (uformat "CMD|execute positioned %.1f %.1f %.1f if entity @e[type=cat,distance=..25,limit=18] as @e[type=cat,distance=..25,nbt={Age:0,Sitting:0b}] run data merge entity @s {InLove:600,PersistenceRequired:1b}"
                   fx fy fz))
          ;; 2. Aceleração de crescimento para gatinhos filhotes (Age < 0) com peixe fresco
          (transport/send-bridge-cmd!
           (uformat "CMD|execute positioned %.1f %.1f %.1f as @e[type=cat,distance=..25,nbt={Age:-1..}] run data modify entity @s Age set value -200"
                   fx fy fz)))
        (transport/send-bridge-cmd!
         (uformat "CMD|execute positioned %.1f %.1f %.1f as @e[type=%s,distance=..22] run data merge entity @s {InLove:0,PersistenceRequired:1b}"
                 fx fy fz sp)))
      (transport/send-bridge-cmd!
       (uformat "CMD|execute positioned %.1f %.1f %.1f at @e[type=%s,distance=..22,limit=4,sort=nearest] run particle minecraft:heart ~ ~1.2 ~ 0.4 0.4 0.4 0.02 6 normal"
               fx fy fz sp))
      (transport/send-bridge-cmd!
       (uformat "CMD|execute positioned %.1f %.1f %.1f at @e[type=%s,distance=..22,limit=4,sort=nearest] run particle minecraft:happy_villager ~ ~1.0 ~ 0.4 0.4 0.4 0.05 8 normal"
               fx fy fz sp))
      (transport/send-bridge-cmd!
       (uformat "CMD|execute positioned %.1f %.1f %.1f at @e[type=%s,distance=..22,limit=1,sort=nearest] run playsound minecraft:entity.generic.eat neutral @a ~ ~ ~ 1.0 1.0"
               fx fy fz sp))
      (when is-cat?
        (transport/send-bridge-cmd!
         (uformat "CMD|execute positioned %.1f %.1f %.1f at @e[type=cat,distance=..22,limit=2,sort=nearest] run playsound minecraft:entity.cat.purr neutral @a ~ ~ ~ 1.0 1.0"
                 fx fy fz))))))
