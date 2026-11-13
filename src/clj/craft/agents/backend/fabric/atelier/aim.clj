(ns craft.agents.backend.fabric.atelier.aim
  "Leitura de hostis e mira do jogador pela ponte, selecao de alvo e chao seguro."
  (:require [clojure.string :as str]
            [craft.agents.backend.fabric.transport :refer [uformat]]))

(defn- parse-hostile-segment
  "Um segmento `<id>,<tipo>,<x>,<y>,<z>,<dist>,<hp>` do scanner -> mapa, ou nil
   se tem menos de 7 campos ou algum numero invalido."
  [seg]
  (let [fields (str/split seg #",")]
    (when (>= (count fields) 7)
      (try
        {:id (Integer/parseInt (first fields))
         :type (nth fields 1)
         :x (Double/parseDouble (nth fields 2))
         :y (Double/parseDouble (nth fields 3))
         :z (Double/parseDouble (nth fields 4))
         :dist (Double/parseDouble (nth fields 5))
         :hp (Double/parseDouble (nth fields 6))}
        (catch Throwable _ nil)))))

(defn- hostile-segments
  "Segmentos do payload de `OK|<count>|<seg>;<seg>...`, ou nil se a resposta
   nao e OK ou o payload esta vazio."
  [resp]
  (when (and resp (str/starts-with? resp "OK|"))
    (let [payload (nth (str/split resp #"\|" 3) 2 "")]
      (when-not (str/blank? payload)
        (str/split payload #";")))))

(defn parse-hostile-response
  "Extrai o alvo mais proximo da resposta do SyntheticHostileScanner.
   Formato: OK|<count>|<id>,<tipo>,<x>,<y>,<z>,<dist>,<hp>;<id>,... (ja ordenado).
   Devolve {:id int :type string :x :y :z :dist :hp} ou nil."
  [resp]
  (some-> (hostile-segments resp) first parse-hostile-segment))



;; =============================================================================
;; Mira por Direcao (o jogador aponta, a magia acerta o que ele mira)
;; =============================================================================
;; O jogador NAO precisa de um op novo: a ponte ja sabe ler a direcao do olhar
;; (RAYCAST_BLOCK devolve o primeiro bloco solido na linha do olhar). Nao existe
;; um op de raycast de ENTIDADES, entao a mira cruza a LINHA do olhar com a
;; varredura de hostis (TARGET_HOSTILE) e escolhe o hostil mais proximo DESSA
;; linha — os hostis que nao estao no cone de mira sao ignorados.

(defn- player-pes
  "Le a posicao dos pes do jogador (op PLAYERS -> nome,x,y,z). Devolve [x y z] ou nil."
  [send-cmd! player-name]
  (let [pos-resp (send-cmd! "PLAYERS")]
    (some (fn [entry]
            (let [f (str/split entry #",")]
              (when (= player-name (first f))
                [(Double/parseDouble (nth f 1))
                 (Double/parseDouble (nth f 2))
                 (Double/parseDouble (nth f 3))])))
          (-> pos-resp (str/replace #"^OK\|" "") (str/split #";")))))

(defn- look-direction-op
  "Le a DIRECAO REAL do olhar pelo op LOOK (vetor unitario + yaw/pitch).
   `LOOK|<jogador>` -> OK|dx|dy|dz|yaw|pitch. Devolve [dx dy dz] normalizado
   ou nil se o op nao existe (JVM antiga) ou o jogador nao esta online."
  [send-cmd! player-name]
  (let [resp (send-cmd! (uformat "LOOK|%s" player-name))]
    (when (and resp (str/starts-with? resp "OK|"))
      (let [parts (str/split resp #"\|")]
        (when (>= (count parts) 5)
          (let [dx (try (Double/parseDouble (nth parts 1)) (catch Throwable _ 0.0))
                dy (try (Double/parseDouble (nth parts 2)) (catch Throwable _ 0.0))
                dz (try (Double/parseDouble (nth parts 3)) (catch Throwable _ 0.0))
                len (max 0.001 (Math/sqrt (+ (* dx dx) (* dy dy) (* dz dz))))]
            [(/ dx len) (/ dy len) (/ dz len)]))))))

(defn player-aim-snapshot
  "Le a mira do jogador pela ponte. A ORIGEM sao os pes do jogador (op PLAYERS,
   que devolve nome,x,y,z) e a DIRECAO e a direcao REAL do olhar pelo op LOOK
   (vetor do olhar, nao depende de haver bloco a frente).
   FALLBACK: se o op LOOK nao existir (JVM antiga, sem reinicio), a direcao cai
   no antigo vetor origem->bloco avistado (RAYCAST_BLOCK) — que e fragil (acerta
   o chao), mas mantem o caminho vivo.
   `send-cmd!` e injetado para os testes; devolve
   {:look-direction [dx dy dz] :ray-origin [x y z] :ray-max-dist d}
   ou nil se o jogador nao existe."
  [send-cmd! player-name max-dist]
  (let [look (look-direction-op send-cmd! player-name)
        pes  (player-pes send-cmd! player-name)]
    (when pes
      (if look
        {:look-direction look
         :ray-origin pes
         :ray-max-dist (double max-dist)}
        ;; Fallback: derivar a direcao do bloco avistado (caminho antigo).
        (let [resp (send-cmd! (uformat "RAYCAST_BLOCK|%s|%.1f" player-name (double max-dist)))]
          (when (and resp (str/starts-with? resp "OK|"))
            (let [parts (str/split resp #"\|")
                  bx (try (Double/parseDouble (nth parts 2 "0")) (catch Throwable _ 0.0))
                  by (try (Double/parseDouble (nth parts 3 "0")) (catch Throwable _ 0.0))
                  bz (try (Double/parseDouble (nth parts 4 "0")) (catch Throwable _ 0.0))
                  [ox oy oz] pes
                  ey (+ oy 1.62)
                  dx (- bx ox)
                  dy (- by ey)
                  dz (- bz oz)
                  len (max 0.001 (Math/sqrt (+ (* dx dx) (* dy dy) (* dz dz))))]
              {:look-direction [(/ dx len) (/ dy len) (/ dz len)]
               :ray-origin [ox oy oz]
               :ray-max-dist (double max-dist)})))))))

(defn- best-aligned
  "Entre os `hostiles`, o de MAIOR cosseno com a linha do olhar de `snapshot`
   que satisfaz `(accept? cos)`; nil se nenhum. Cosseno de hostil->jogador contra
   o vetor do olhar a partir da origem do raio."
  [snapshot hostiles accept?]
  (when (and snapshot (seq hostiles))
    (let [[dx dy dz] (:look-direction snapshot)
          [ox oy oz] (:ray-origin snapshot)
          scored (keep (fn [{:keys [x y z] :as h}]
                         (let [vx (- (double x) (double ox))
                               vy (- (double y) (double oy))
                               vz (- (double z) (double oz))
                               vlen (max 0.001 (Math/sqrt (+ (* vx vx) (* vy vy) (* vz vz))))
                               cosang (/ (+ (* dx vx) (* dy vy) (* dz vz)) vlen)]
                           (when (accept? cosang) [cosang h])))
                       hostiles)]
      (second (apply max-key first (cons [-1.0 nil] scored))))))

(defn select-aim-target
  "Escolhe, entre os `hostiles`, o que esta na mira do jogador: aquele cuja
   direcao o jogador->hostil forma o MENOR angulo com a linha do olhar. Ja NAO
   ha cone maximo: apontar para qualquer hostil a frente conta (o mais alinhado
   ganha); quem nao esta a frente (cos <= 0) e ignorado — nao se acerta pelas
   costas. Devolve o hostil escolhido ou nil."
  [snapshot hostiles]
  (best-aligned snapshot hostiles pos?))

(defn select-aim-target-cone
  "Escolhe o hostil na mira dentro de um cone restrito (min-cos, ex: 0.94 ~ 20°).
   Evita que mobs laterais roubem a mira quando o jogador esta apontando para outro ponto."
  [snapshot hostiles min-cos]
  (best-aligned snapshot hostiles #(>= % min-cos)))

(defn find-safe-ground-y
  "Garante que o destino [tx ty tz] nao deixe o jogador enterrado em blocos
   solidos. Se o ponto estiver dentro de terra/pedra/bloco solido, sobe y ate
   encontrar espaco livre de 2 blocos (ar nos pes e ar na cabeca).
   Se ja estiver no ar (dash aereo), mantem ty.
   Se nao encontrar ar seguro ate 24 blocos, recua para fallback-oy (altura dos pes)."
  [send-cmd! tx ty tz fallback-oy]
  (let [bx (int (Math/floor tx))
        bz (int (Math/floor tz))
        init-by (int (Math/floor ty))]
    (loop [y init-by
           climbed 0]
      (if (>= climbed 24)
        (double fallback-oy)
        (let [resp (send-cmd! (uformat "BLOCK|%d|%d|%d" bx y bz))
              block-id (when (and resp (str/starts-with? resp "OK|"))
                         (second (str/split resp #"\|")))
              solid? (and block-id (not (str/includes? block-id ":air")))]
          (if solid?
            (recur (inc y) (inc climbed))
            ;; Bloco y e passavel (ar). Verifica se ha espaco para a cabeca em y+1:
            (let [resp-head (send-cmd! (uformat "BLOCK|%d|%d|%d" bx (inc y) bz))
                  head-id (when (and resp-head (str/starts-with? resp-head "OK|"))
                            (second (str/split resp-head #"\|")))
                  head-solid? (and head-id (not (str/includes? head-id ":air")))]
              (if head-solid?
                (recur (+ y 2) (+ climbed 2))
                (double y)))))))))

(defn parse-all-hostiles
  "Extrai TODOS os hostis da resposta do SyntheticHostileScanner
   (OK|<count>|<id>,<tipo>,<x>,<y>,<z>,<dist>,<hp>;...) para uma lista de mapas.
   Usado pela mira por direcao, que precisa comparar todos contra a linha do olhar."
  [resp]
  (when (and resp (str/starts-with? resp "OK|"))
    (let [payload (nth (str/split resp #"\|" 3) 2 "")]
      (when-not (str/blank? payload)
        (keep (fn [seg]
                (let [fields (str/split seg #",")]
                  (when (>= (count fields) 7)
                    (try
                      {:id (Integer/parseInt (first fields))
                       :type (nth fields 1)
                       :x (Double/parseDouble (nth fields 2))
                       :y (Double/parseDouble (nth fields 3))
                       :z (Double/parseDouble (nth fields 4))
                       :dist (Double/parseDouble (nth fields 5))
                       :hp (Double/parseDouble (nth fields 6))}
                      (catch Throwable _ nil)))))
              (str/split payload #";"))))))
