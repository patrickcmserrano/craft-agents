(ns craft.agents.blackboard
  "Memoria de trabalho imutavel por agente com TTL de diretivas e historico circular (CA-030).")

(defrecord Blackboard [max-history state-atom])

(defn create-blackboard
  "Cria blackboard com capacidade maxima de historico circular (CA-030)."
  ([opts]
   (->Blackboard (:max-history opts 64)
                 (atom {:history [] :directive nil})))
  ([] (create-blackboard {:max-history 64})))

(defn put-directive!
  "Escreve nova diretiva tatica (com :ttl-ms opcional) registrando transicao no historico (CA-030)."
  [bb directive now-ms]
  (let [ttl-ms     (or (:ttl-ms directive) 10000)
        expires-at (+ (long now-ms) (long ttl-ms))
        entry      (assoc directive :created-at now-ms :expires-at expires-at)]
    (swap! (:state-atom bb)
           (fn [s]
             (let [hist (conj (:history s) entry)
                   trimmed (if (> (count hist) (:max-history bb))
                             (subvec hist (- (count hist) (:max-history bb)))
                             hist)]
               (assoc s :directive entry :history trimmed))))
    entry))

(defn active-directive
  "Retorna diretiva ativa (expirando automaticamente caso :expires-at <= now-ms) (CA-030)."
  [bb now-ms]
  (let [dir (:directive @(:state-atom bb))]
    (when (and dir (< (long now-ms) (long (:expires-at dir 0))))
      dir)))

(defn recent-history
  "Retorna vetor imutavel das ultimas transicoes do blackboard (CA-030)."
  [bb]
  (:history @(:state-atom bb)))
