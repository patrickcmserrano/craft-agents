(ns craft.agents.backend.fabric.actions
  "Acoes motoras e consultas de estado fisico nativas sobre o Fabric Bridge."
  (:require [clojure.data.json :as json]
            [clojure.java.io :as io]
            [clojure.java.shell :as sh]
            [clojure.string :as str]
            [craft.agents.backend.fabric.locomotion :as loco]
            [craft.agents.backend.fabric.transport :as transport :refer [uformat]]
            [craft.agents.npc.registry :as registry]
            [craft.agents.npc.spawn :as spawn]
            [craft.agents.npc.speech :as speech]))

(defn karlach-say! [msg]
  (speech/say! :karlach msg))

(defn teleport-agent-to!
  "Teleporta um agente sintetico pelo op MOVE da bridge. NAO use /tp vanilla:
   o ServerPlayerEntity sintetico nunca confirma o teleporte, o servidor loga
   'Teleported' mas a posicao nao muda (loop infinito no follow > 48 m)."
  [agent-name [x y z]]
  (transport/send-bridge-cmd!
   (uformat "MOVE|%s|%.3f|%.3f|%.3f|0.0|0.0|false|false"
            agent-name (double x) (double y) (double z))))

(defn load-skin-meta [skin-filename]
  (let [f (io/file (str "/home/pace/dev/minecraft/server/skins/" skin-filename))]
    (if (.exists f)
      (try
        (let [m (json/read-str (slurp f) :key-fn keyword)]
          {:value (or (:value m) "")
           :signature (or (:signature m) "")})
        (catch Throwable _ {:value "" :signature ""}))
      {:value "" :signature ""})))

(defonce ^:private last-attach-attempt-ms (atom 0))

(defn ensure-live-bridge-and-agents!
  "Garante que a ponte in-server (25595) esta anexada ao servidor Fabric e que Karlach e Picard estao online com suas skins."
  []
  (when-not (transport/bridge-alive?)
    (let [now (System/currentTimeMillis)]
      (when (> (- now @last-attach-attempt-ms) 60000)
        (reset! last-attach-attempt-ms now)
        (println "[craft-agents] Anexando In-Server Bridge ao servidor Fabric (rate-limited)...")
        (try
          (sh/sh "make" "attach" :dir "/home/pace/dev/minecraft/craft-agents")
          (Thread/sleep 1000)
          (catch Throwable t
            (println "[craft-agents] Aviso ao anexar bridge:" (.getMessage t)))))))
  (when (transport/bridge-alive?)
    ;; Spawn, gamemode, efeitos e loadout vem do registro agents/*.edn (CA-088)
    (spawn/spawn-agents! transport/send-bridge-cmd! load-skin-meta)
    true))

(defn query-online-players []
  (let [resp (transport/send-bridge-cmd! "PLAYERS")]
    (if (and resp (str/starts-with? resp "OK|"))
      (let [payload (subs resp 3)]
        (if (str/blank? payload)
          []
          (vec
           (keep loco/parse-player-entry (str/split payload #";")))))
      [])))

(defn query-leash-status
  "Consulta sensorial em tempo real se o agente possui animal na corda/guia.
   Retorna {:leashed? boolean, :id int, :species string, :distance double, :pos [x y z]}."
  ([] (query-leash-status "Karlach"))
  ([player-name]
   (let [resp (transport/send-bridge-cmd! (str "LEASH_STATUS|" player-name))]
     (if (and resp (str/starts-with? resp "LEASHED|"))
       (let [parts (str/split (subs resp 8) #"\|")
             eid (try (Long/parseLong (nth parts 0 "0")) (catch Throwable _ 0))
             sp (nth parts 1 "mob")
             dist (try (Double/parseDouble (nth parts 2 "0.0")) (catch Throwable _ 0.0))
             pos-str (nth parts 3 "0,0,0")
             coords (mapv #(try (Double/parseDouble %) (catch Throwable _ 0.0)) (str/split pos-str #","))]
         {:leashed? true :id eid :species sp :distance dist :pos coords})
       {:leashed? false}))))

(defn query-health-status
  "Consulta estado fisiologico nativo (HP, foodLevel, saturation) via bridge (CA-E1.1)."
  ([] (query-health-status "Karlach"))
  ([player-name]
   (let [resp (transport/send-bridge-cmd! (str "HEALTH_STATUS|" player-name))]
     (if (and resp (str/starts-with? resp "OK|"))
       (let [parts (str/split (subs resp 3) #"\|")]
         {:hp (try (Double/parseDouble (nth parts 0 "20.0")) (catch Throwable _ 20.0))
          :food (try (Integer/parseInt (nth parts 1 "20")) (catch Throwable _ 20))
          :saturation (try (Double/parseDouble (nth parts 2 "5.0")) (catch Throwable _ 5.0))
          :alive? (try (Boolean/parseBoolean (nth parts 3 "true")) (catch Throwable _ true))})
       {:hp 20.0 :food 20 :saturation 5.0 :alive? true}))))

(defn query-sitting-status
  "Consulta se o agente ou jogador está montado em veículo/assento via opcode nativo IS_SITTING."
  ([] (query-sitting-status "Karlach"))
  ([player-name]
   (let [resp (transport/send-bridge-cmd! (str "IS_SITTING|" player-name))]
     (if (and resp (str/starts-with? resp "OK|"))
       (= (second (str/split resp #"\|")) "true")
       false))))

(defn sit-agent!
  "Senta o agente num assento nativo (CA-060). Com `seat-pos` [x y z] manda as
   coordenadas exatas do banco; sem elas, deixa a ponte procurar o assento mais
   proximo. Devolve true se a ponte respondeu OK."
  ([player-name] (sit-agent! player-name nil))
  ([player-name seat-pos]
   (let [cmd (if (and seat-pos (= 3 (count seat-pos)))
               (uformat "SIT|%s|%d|%d|%d" player-name
                        (int (Math/floor (double (nth seat-pos 0))))
                        (int (Math/floor (double (nth seat-pos 1))))
                        (int (Math/floor (double (nth seat-pos 2)))))
               (str "SIT|" player-name))
         resp (transport/send-bridge-cmd! cmd)]
     (boolean (and resp (str/starts-with? resp "OK"))))))

(defn stand-agent!
  "Levanta o agente do assento (CA-060). Devolve true se a ponte respondeu OK."
  [player-name]
  (let [resp (transport/send-bridge-cmd! (str "STAND|" player-name))]
    (boolean (and resp (str/starts-with? resp "OK")))))

(defn locate-dropped-items
  "Escaneia ItemEntity caidos no chao em um raio via bridge (CA-E1.4)."
  ([player-name] (locate-dropped-items player-name {}))
  ([player-name opts]
   (let [radius (or (:radius opts) 16.0)
         filter-item (or (:filter opts) "")
         cmd (uformat "LOCATE_ITEMS|%s|%.1f|%s" player-name (double radius) filter-item)
         resp (transport/send-bridge-cmd! cmd)]
     (if (and resp (str/starts-with? resp "OK|"))
       (let [parts (str/split (subs resp 3) #"\|")
             count-val (try (Integer/parseInt (nth parts 0 "0")) (catch Throwable _ 0))]
         (if (or (zero? count-val) (< (count parts) 2) (str/blank? (nth parts 1)))
           []
           (let [entries (str/split (nth parts 1) #";")]
             (mapv (fn [raw]
                     (let [[eid iid cnt x y z dist] (str/split raw #",")]
                       {:id (try (Integer/parseInt eid) (catch Throwable _ 0))
                        :item iid
                        :count (try (Integer/parseInt cnt) (catch Throwable _ 1))
                        :x (try (Double/parseDouble x) (catch Throwable _ 0.0))
                        :y (try (Double/parseDouble y) (catch Throwable _ 0.0))
                        :z (try (Double/parseDouble z) (catch Throwable _ 0.0))
                        :distance (try (Double/parseDouble dist) (catch Throwable _ 0.0))}))
                   entries))))
       []))))

(defn collect-nearby-drops!
  "Coleta drops no chão e insere no inventário real do jogador via bridge (CA-E1.4 / Looting)."
  ([player-name] (collect-nearby-drops! player-name 4.0))
  ([player-name radius]
   (let [cmd (uformat "COLLECT_DROPS|%s|%.1f" player-name (double radius))
         resp (transport/send-bridge-cmd! cmd)]
     (if (and resp (str/starts-with? resp "OK|"))
       (let [parts (str/split (subs resp 3) #"\|")
             cnt (try (Integer/parseInt (nth parts 0 "0")) (catch Throwable _ 0))]
         (if (or (zero? cnt) (< (count parts) 2) (str/blank? (nth parts 1)))
           {:total-count 0 :items {}}
           (let [entries (str/split (nth parts 1) #";")]
             {:total-count cnt
              :items (into {}
                           (map (fn [e]
                                  (let [[iid c] (str/split e #",")]
                                    [iid (try (Integer/parseInt c) (catch Throwable _ 1))]))
                                entries))})))
       {:total-count 0 :items {}}))))

(defn inspect-inventory
  "Consulta o inventário real do jogador via bridge, retornando mapa de itens e contagens."
  ([player-name]
   (let [cmd (str "INSPECT_INVENTORY|" player-name)
         resp (transport/send-bridge-cmd! cmd)]
     (if (and resp (str/starts-with? resp "OK|"))
       (let [parts (str/split (subs resp 3) #"\|")
             occupied (try (Integer/parseInt (nth parts 0 "0")) (catch Throwable _ 0))]
         (if (or (zero? occupied) (< (count parts) 2) (str/blank? (nth parts 1)))
           {:slots-used 0 :items {}}
           (let [entries (str/split (nth parts 1) #";")]
             {:slots-used occupied
              :items (into {}
                           (map (fn [e]
                                  (let [[iid c] (str/split e #",")]
                                    [iid (try (Integer/parseInt c) (catch Throwable _ 1))]))
                                entries))})))
       {:slots-used 0 :items {}}))))

(defonce last-drop-ms (atom 0))

(defn drop-inventory-items!
  "Ejeta itens do inventário no mundo (loot sharing com jogador humano)."
  ([player-name] (drop-inventory-items! player-name "all"))
  ([player-name filter-str]
   (reset! last-drop-ms (System/currentTimeMillis))
   (let [cmd (str "DROP_ITEMS|" player-name "|" (or filter-str ""))
         resp (transport/send-bridge-cmd! cmd)]
     (if (and resp (str/starts-with? resp "OK|"))
       (let [parts (str/split (subs resp 3) #"\|")
             dropped (try (Integer/parseInt (nth parts 0 "0")) (catch Throwable _ 0))]
         (if (or (zero? dropped) (< (count parts) 2) (str/blank? (nth parts 1)))
           {:dropped-count 0 :items {}}
           (let [entries (str/split (nth parts 1) #";")]
             {:dropped-count dropped
              :items (into {}
                           (map (fn [e]
                                  (let [[iid c] (str/split e #",")]
                                    [iid (try (Integer/parseInt c) (catch Throwable _ 1))]))
                                entries))})))
       {:dropped-count 0 :items {}}))))

(defn scan-nearby-hostiles
  "Escaneia monstros hostis no raio de percepcao do jogador via bridge (CA-E1.3)."
  ([player-name] (scan-nearby-hostiles player-name 16.0))
  ([player-name radius]
   (let [cmd (uformat "TARGET_HOSTILE|%s|%.1f" player-name (double radius))
         resp (transport/send-bridge-cmd! cmd)]
     (if (and resp (str/starts-with? resp "OK|"))
       (let [parts (str/split (subs resp 3) #"\|")
             count-val (try (Integer/parseInt (nth parts 0 "0")) (catch Throwable _ 0))]
         (if (or (zero? count-val) (< (count parts) 2) (str/blank? (nth parts 1)))
           []
           (let [entries (str/split (nth parts 1) #";")]
             (mapv (fn [raw]
                     (let [[eid type x y z dist hp] (str/split raw #",")]
                       {:id (try (Integer/parseInt eid) (catch Throwable _ 0))
                        :type type
                        :x (try (Double/parseDouble x) (catch Throwable _ 0.0))
                        :y (try (Double/parseDouble y) (catch Throwable _ 0.0))
                        :z (try (Double/parseDouble z) (catch Throwable _ 0.0))
                        :distance (try (Double/parseDouble dist) (catch Throwable _ 0.0))
                        :hp (try (Double/parseDouble hp) (catch Throwable _ 20.0))}))
                   entries))))
       []))))

(defn attack-hostile-target!
  "Executa ataque fisico com cooldown carregado via bridge (CA-E1.3)."
  [player-name target-id]
  (let [cmd (str "ATTACK_HOSTILE|" player-name "|" target-id)
        resp (transport/send-bridge-cmd! cmd)]
    (when (and resp (str/starts-with? resp "OK|"))
      (let [[_ tid cd] (str/split resp #"\|")]
        {:success? true
         :target-id (try (Integer/parseInt tid) (catch Throwable _ target-id))
         :cooldown (try (Double/parseDouble cd) (catch Throwable _ 1.0))}))))

(defn equip-item!
  "Equipa item na mão principal ou secundária via opcode nativo EQUIP_ITEM da bridge (Zero CMD - CA-049)."
  [player-name slot item-id]
  (let [cmd (str "EQUIP_ITEM|" player-name "|" slot "|" item-id)
        resp (transport/send-bridge-cmd! cmd)]
    (when (and resp (str/starts-with? resp "OK"))
      {:success? true :player player-name :slot slot :item item-id})))

(defn mine-block!
  "Executa quebra segura de bloco in-JVM respeitando regras de ouro (CA-E3.1).
   Retorna {:success? true/false :block-id ... :tool-used ...} ou erro."
  ([x y z] (mine-block! "Karlach" x y z))
  ([player-name x y z]
   (let [cmd (uformat "MINE_BLOCK|%s|%d|%d|%d" player-name (int x) (int y) (int z))
         resp (transport/send-bridge-cmd! cmd)]
     (if (and resp (str/starts-with? resp "OK|"))
       (let [[_ bid tid bx by bz] (str/split resp #"\|")]
         {:success? true
          :block-id bid
          :tool-used tid
          :x (try (Integer/parseInt bx) (catch Throwable _ (int x)))
          :y (try (Integer/parseInt by) (catch Throwable _ (int y)))
          :z (try (Integer/parseInt bz) (catch Throwable _ (int z)))})
       (let [parts (str/split (or resp "ERR|UNKNOWN") #"\|")
             code (nth parts 1 "UNKNOWN")
             msg (nth parts 2 "")]
         {:success? false
          :error-code code
          :message msg})))))

(def ore-pt-names
  {"iron" "ferro"
   "coal" "carvão"
   "diamond" "diamante"
   "copper" "cobre"
   "gold" "ouro"
   "redstone" "redstone"
   "lapis" "lápis-lazúli"
   "emerald" "esmeralda"
   "deepslate" "deepslate"
   "stone" "pedra"
   "dripstone" "espeleotema"})

(defn locate-nearby-ores
  "Escaneia veios de minerios proximos ao jogador via bridge (CA-E3.1).
   Retorna apenas minerios com faces expostas ao ar (Anti X-Ray) e coordenadas do ponto de ar (Anti No-Clip)."
  ([player-name] (locate-nearby-ores player-name 12.0 ""))
  ([player-name radius] (locate-nearby-ores player-name radius ""))
  ([player-name radius filter-str]
   (let [cmd (uformat "LOCATE_ORES|%s|%.1f|%s" player-name (double radius) (or filter-str ""))
         resp (transport/send-bridge-cmd! cmd)]
     (if (and resp (str/starts-with? resp "OK|"))
       (let [parts (str/split (subs resp 3) #"\|")
             count-val (try (Integer/parseInt (nth parts 0 "0")) (catch Throwable _ 0))]
         (if (or (zero? count-val) (< (count parts) 2) (str/blank? (nth parts 1)))
           []
           (let [entries (str/split (nth parts 1) #";")]
             (mapv (fn [raw]
                     (let [[x y z id dist ax ay az] (str/split raw #",")]
                       {:x (try (Integer/parseInt x) (catch Throwable _ 0))
                        :y (try (Integer/parseInt y) (catch Throwable _ 0))
                        :z (try (Integer/parseInt z) (catch Throwable _ 0))
                        :block-id id
                        :distance (try (Double/parseDouble dist) (catch Throwable _ 0.0))
                        :air-x (if ax (try (Integer/parseInt ax) (catch Throwable _ (Integer/parseInt x))) (try (Integer/parseInt x) (catch Throwable _ 0)))
                        :air-y (if ay (try (Integer/parseInt ay) (catch Throwable _ (Integer/parseInt y))) (try (Integer/parseInt y) (catch Throwable _ 0)))
                        :air-z (if az (try (Integer/parseInt az) (catch Throwable _ (Integer/parseInt z))) (try (Integer/parseInt z) (catch Throwable _ 0)))}))
                   entries))))
       []))))

(defn raycast-player-look
  "Detecta o bloco para o qual o jogador esta olhando (ate max-dist metros)."
  ([player-name] (raycast-player-look player-name 5.5))
  ([player-name max-dist]
   (let [cmd (uformat "RAYCAST_BLOCK|%s|%.1f" player-name (double max-dist))
         resp (transport/send-bridge-cmd! cmd)]
     (if (and resp (str/starts-with? resp "OK|"))
       (let [[_ bid bx by bz dist] (str/split resp #"\|")]
         {:found? true
          :block-id bid
          :x (try (Integer/parseInt bx) (catch Throwable _ 0))
          :y (try (Integer/parseInt by) (catch Throwable _ 0))
          :z (try (Integer/parseInt bz) (catch Throwable _ 0))
          :distance (try (Double/parseDouble dist) (catch Throwable _ 0.0))})
       {:found? false}))))

(defn auto-eat-food!
  "Restaura a vida e a barra de fome de um agente pela escrita direta (SET_VITALS).

  NAO usa `effect give`: medido em 2026-11-10, NENHUM efeito (`saturation`,
  `instant_health`, `regeneration`) move a barra destas entidades, e o `data merge`
  tambem nao cola — elas tem um ServerPlayNetworkHandler sobre um EmbeddedChannel
  vazio, SEM tick de jogador, entao o valor e re-derivado do estado de spawn. O
  unico caminho que pega e escrever no objeto vivo: `SET_VITALS` chama `setHealth`
  (method_6033) e os setters do HungerManager (method_7580/method_7583) direto.

  A versao anterior ainda equipava uma maca na mainhand e swingava, destruindo a
  arma do agente (Picard perdia o spyglass). Aqui nao se toca no inventario."
  ([player-name] (auto-eat-food! player-name nil))
  ([player-name _food-item]
   (let [resp (transport/send-bridge-cmd! (str "SET_VITALS|" player-name "|20.0|20|5.0"))]
     {:fed? (and resp (str/starts-with? resp "OK")) :player player-name :resp resp})))

(defn feed-hungry-agents!
  "Alimenta qualquer agente do registro (CA-093) cuja barra de fome tenha caido abaixo de `threshold`.

  Existe porque o boot so da `resistance` a todos e nunca alimenta ninguem: medido
  em 2026-11-10, o Picard chegou a hp=1 food=0 e nada o socorria (a funcao de
  comer existia mas nunca era chamada). Um agente faminto perde vida.

  Devolve a lista de nomes alimentados nesta passada (vazia se nenhum)."
  ([] (feed-hungry-agents! 6))
  ([threshold]
   (vec
    (keep (fn [nm]
            (let [{:keys [food alive?]} (query-health-status nm)]
              (when (and alive? (some? food) (< food threshold))
                (auto-eat-food! nm)
                nm)))
          (registry/agent-names)))))
