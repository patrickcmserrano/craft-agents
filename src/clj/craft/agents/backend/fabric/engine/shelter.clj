(ns craft.agents.backend.fabric.engine.shelter
  "Abrigo autonomo da Karlach contra chuva (CA-060): decisoes puras de clima e
   comandos de sentar/levantar no quarto."
  (:require [clojure.string :as str]
            [craft.agents.backend.fabric.actions :refer [sit-agent! stand-agent!]]
            [craft.agents.backend.fabric.navigation :refer [expand-route-microsteps]]
            [craft.agents.backend.fabric.picard :refer [picard-broadcast-rain-alert!
                                                        query-minecraft-weather]]))

(defn rain-shelter-decision
  "Decide o que um agente faz diante do clima no tick atual (CA-060, CA-093).
   Entrada: {:raining? bool-or-nil :thundering? bool-or-nil :mode kw :sheltering? bool
             :agent spec-opcional}
   Saida:
     :shelter -> chovendo/trovejando e ainda na rua: aborta o que fazia e vai pro quarto
     :wait    -> chovendo/trovejando e ja no quarto: permanece abrigado
     :resume  -> tempo abriu com abrigo ativo: conclui o descanso e retoma a rotina
     :none    -> nada a fazer (ceu limpo, clima desconhecido ou agente sem quarto)
   Com `:agent`, quem decide e o `:agent/quarters`: sem quarters o agente nunca se abriga e
   continua o que fazia; com quarters se abriga em qualquer modo, exceto acompanhando ou
   guardando o lider e minerando. Sem `:agent` vale o contrato legado da Karlach (so abriga
   em :sanctuary-patrol).
   Fail-safe: leitura de clima ausente (ponte caida) NUNCA trava o agente no quarto —
   vale como ceu limpo, exceto para liberar quem ja estava abrigado."
  [{:keys [raining? thundering? mode sheltering? agent]}]
  (let [wet        (or (true? raining?) (true? thundering?))
        legacy?    (nil? agent)
        has-home?  (or legacy? (some? (:agent/quarters agent)))
        can-leave? (if legacy?
                     (= mode :sanctuary-patrol)
                     (not (contains? #{:follow :bodyguard :mining} mode)))]
    (cond
      (not has-home?)               {:action :none    :speak? false}
      (and sheltering? (not wet))   {:action :resume  :speak? true}
      (and sheltering? wet)         {:action :wait    :speak? false}
      (and wet can-leave?)          {:action :shelter :speak? true}
      :else                         {:action :none    :speak? false})))

(defn weather-transition
  "Detecta a BORDA do clima entre duas leituras (CA-060).
   Entrada: mapa anterior e mapa atual, cada um com :raining?/:thundering?.
   Saida: :started-raining | :stopped-raining | :none
   \"Molhado\" agrega chuva E trovoada, entao alternar entre as duas nao gera flap."
  [prev current]
  (let [wet? (fn [w] (or (true? (:raining? w)) (true? (:thundering? w))))
        was-wet (wet? prev)
        now-wet (wet? current)]
    (cond
      (and (not was-wet) now-wet) :started-raining
      (and was-wet (not now-wet)) :stopped-raining
      :else                       :none)))

(defn shelter-route-steps
  "Micro-etapas de caminhada da posicao atual ate o Quarto da Karlach (CA-060).
   `quarters-stop` e a parada :type :quarters da rota do santuario; se ausente,
   devolve nil (o loop degrada com seguranca, sem explodir).

   ATENCAO: expand-route-microsteps de :quarters produz ida (equip + portoes +
   caminhada + BARK) + DESCANSO (o :wait rotulado Descansando) + RETORNO a ronda.
   Para o abrigo so interessa a IDA: cortamos logo apos o descanso, para ela PARAR no
   quarto em vez de caminhar de volta e travar no santuario. Nao basta cortar no
   primeiro :wait — a ida ja contem :wait de portao/tranca antes do descanso."
  [quarters-stop from-pos]
  (when-let [qpos (:pos quarters-stop)]
    (let [all-steps (vec (expand-route-microsteps {:type :quarters :pos qpos} from-pos))
          rest-idx  (first (keep-indexed
                            (fn [i s]
                              (when (and (= (:op s) :wait)
                                         (some-> (:label s) (str/includes? "Descansando")))
                                i))
                            all-steps))]
      (if rest-idx
        (vec (take (inc rest-idx) all-steps))
        all-steps))))

(defn sit-karlach-at-quarters!
  "Senta a Karlach no banco do quarto enquanto ela espera a chuva passar (CA-060).
   `seat-pos` e [x y z] do banco; se ausente/nil, nao emite nada (degrada com
   seguranca - melhor ficar de pe do que sentar no ar)."
  [seat-pos]
  (when (and seat-pos (= 3 (count seat-pos)))
    (sit-agent! "Karlach" seat-pos)))

(defn stand-karlach!
  "Levanta a Karlach do banco antes de retomar a ronda (CA-060)."
  []
  (stand-agent! "Karlach"))

(defn check-weather!
  "CA-060: le o clima REAL do servidor. Na borda de ceu limpo -> chuva, o Picard
   anuncia globalmente no chat (tellraw @a sem filtro de distancia). Guarda a
   leitura em :last-weather do nav-state e publica a borda no engine-ctx."
  [engine-ctx-atom nav-state]
  (let [w    (query-minecraft-weather)
        prev (:last-weather @nav-state)
        edge (when prev (weather-transition prev w))]
    (when (= :started-raining edge)
      (picard-broadcast-rain-alert!))
    (swap! nav-state assoc :last-weather w)
    (swap! engine-ctx-atom assoc
           :weather-edge edge
           :weather-alerts-sent (if (= :started-raining edge)
                                  (inc (:weather-alerts-sent @engine-ctx-atom 0))
                                  (:weather-alerts-sent @engine-ctx-atom 0)))))
