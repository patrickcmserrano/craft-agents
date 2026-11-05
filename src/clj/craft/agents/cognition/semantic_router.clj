(ns craft.agents.cognition.semantic-router
  "Roteador Semântico Vetorial de Alta Performance (Semantic Router).
   Baseado na arquitetura Aurelio AI / RouteLLM adaptada para Sistema 1 em Clojure/JVM.
   Opera via representação vetorial densa/esparsa (subword n-grams + termos) e similaridade de cosseno,
   garantindo classificação em < 1ms por inferência, sem quebrar com variações morfológicas ou gírias."
  (:require [clojure.string :as str]
            [clojure.java.io :as io]
            [clojure.edn :as edn]))

;; ----------------------------------------------------------------------------
;; Pré-processamento e Vetorização de Subpalavras (N-Grams)
;; ----------------------------------------------------------------------------

(defn strip-accents
  "Normaliza texto removendo acentuação e diacríticos em conformidade NFD."
  [s]
  (-> (java.text.Normalizer/normalize (or s "") java.text.Normalizer$Form/NFD)
      (str/replace #"\p{M}" "")
      (str/lower-case)
      (str/trim)))

(defn- extract-char-ngrams
  "Gera n-grams de caracteres de tamanho n a partir de uma palavra delimitada."
  [word n]
  (let [padded (str "^" word "$")
        len    (count padded)]
    (if (<= len n)
      [padded]
      (mapv #(subs padded % (+ % n)) (range (- len (dec n)))))))

(defn text->vector
  "Converte texto em vetor de frequências de termos e subpalavras (3-grams e 4-grams).
   Isso permite similaridade semântica resistente a conjugações verbais, prefixos e erros de digitação."
  [text]
  (let [clean  (strip-accents text)
        words  (re-seq #"\w+" clean)]
    (reduce (fn [acc word]
              (let [acc (update acc (str "w:" word) (fnil inc 0))
                    ng3 (extract-char-ngrams word 3)
                    ng4 (extract-char-ngrams word 4)]
                (-> (reduce (fn [m g] (update m (str "c3:" g) (fnil #(+ % 0.8) 0.0))) acc ng3)
                    (as-> m' (reduce (fn [m g] (update m (str "c4:" g) (fnil #(+ % 1.2) 0.0))) m' ng4)))))
            {}
            words)))

(defn vector-magnitude
  "Calcula a norma euclidiana L2 do vetor."
  [v]
  (Math/sqrt (reduce (fn [sum [_ val]] (+ sum (* val val))) 0.0 v)))

(defn cosine-similarity
  "Calcula a similaridade de cosseno entre dois vetores de frequência."
  [v1 v2]
  (if (or (empty? v1) (empty? v2))
    0.0
    (let [mag1 (vector-magnitude v1)
          mag2 (vector-magnitude v2)]
      (if (or (zero? mag1) (zero? mag2))
        0.0
        (let [dot (reduce (fn [sum [k val1]]
                            (if-let [val2 (get v2 k)]
                              (+ sum (* val1 val2))
                              sum))
                          0.0
                          v1)]
          (/ dot (* mag1 mag2)))))))

;; ----------------------------------------------------------------------------
;; Definição das Rotas Semânticas e Âncoras (Exemplars do Santuário)
;; ----------------------------------------------------------------------------

(def default-routes
  [{:id :animal-emergency
    :description "Resgate, captura, condução emergencial de animal fugitivo ou solto"
    :anchors ["a capivara nadadora fugiu leva ela de volta pra casa"
              "resgata essa capivara aqui"
              "pega essa capivara"
              "o cervo fugiu bota ele de volta pra dentro"
              "ajuda a prender o cervo que escapou"
              "a capivarinha filhote esta fora da grade conduz ela pra dentro da jaula"
              "leva este cervo pra casa dele"
              "bota esse bicho pra dentro"
              "resgata o bicho solto no lago"
              "salva o animal que escapou do cercado"
              "captura a capivara fujona"
              "prende o mob no cercado"
              "conduz ele de volta pro habitat"
              "traz o filhote com a coleira"
              "busca o bicho que fugiu"
              "recolhe esse animal agora"
              "socorro a bicha fugiu corre"
              "coloca essa capivara no cercado dela"]}

   {:id :spatial-attention
    :description "Atenção visual compartilhada e identificação de entidades próximas"
    :anchors ["ta vendo este cervo real aqui"
              "ta vendo esse servo real aqui"
              "olha essa capivara aqui perto de mim"
              "repara naquele bicho ali na frente"
              "enxerga aquele mob acolá"
              "da uma olhada nessa capivara"
              "ve aquele animal ali"
              "olha aqui comigo"
              "repara nessa criatura"]}

   {:id :weather-shelter
    :description "Proteger-se do clima hostil, chuva, raios e tempestade"
    :anchors ["esta chovendo va se abrigar"
              "comecou a chover forte corre pro abrigo"
              "temporal chegando vai pros seus aposentos"
              "vai se proteger da chuva e dos raios"
              "tempestade caindo entra no abrigo"
              "ta caindo chuva torrencial abriga"
              "ta caindo um toro daqueles"]}

   {:id :social-dance
    :description "Comemorações, danças e expressões sociais lúdicas"
    :anchors ["danca o passinho de avernus"
              "vamos dancar um pouco"
              "mostra seu passinho de danca"
              "dancinha pra comemorar"
              "baila comigo agora"
              "solta o som e danca"
              "mostra como voce ginga"
              "solta a ginga"
              "manda aquele passinho"]}

   {:id :stop-following
    :description "Parar de seguir, descansar, pausar ações ou dispensar o bot"
    :anchors ["parar"
              "pode parar por aqui"
              "chega de me seguir fica aqui"
              "descansa agora valeu"
              "espera ai quietinha"
              "para de me acompanhar"
              "dispensada por hoje obrigado"
              "pode relaxar e ficar parada"
              "valeu pelo apoio para aqui"
              "fica de boa descansando"
              "calma fica aqui"
              "da um descanso agora"]}

   {:id :follow-player
    :description "Seguir o jogador pelo mundo ou caminhar junto"
    :anchors ["vem comigo"
              "me segue aqui"
              "vem ca me acompanha"
              "vamos juntos ate ali"
              "cola aqui em mim"
              "vem atras de mim"
              "me acompanha pelo santuario"
              "anda junto comigo"
              "vamos dar uma volta juntos"]}

   {:id :teleport-to-player
    :description "Teleporte intencional imediato para junto do jogador"
    :anchors ["teleportar pra ca"
              "teleporta pra ca"
              "teleporta ate mim"
              "teleporta aqui"
              "da tp aqui"
              "vem de tp"
              "teleporta agora"
              "da teleporte"
              "teleporte pra ca"
              "tp pra ca"]}

   {:id :patrol-sanctuary
    :description "Retomar rotina de ronda, patrulha e cuidados com os habitats"
    :anchors ["volta pra patrulha no santuario"
              "pode continuar sua ronda de rotina"
              "vai cuidar dos animais nos cercados"
              "retoma seus afazeres e posto"
              "vai patrulhar a area agora"
              "alimenta os animais nas baias"]}

   {:id :bodyguard
    :description "Modo guarda-costas, proteção pessoal, escolta armada e combate a hostis"
    :anchors ["ativa o modo guarda costas"
              "me protege contra os monstros"
              "fica de guarda costas comigo"
              "mata esse zumbi"
              "defende o time contra hostis"
              "escolta armada"
              "seguranca pessoal"]}

   {:id :mining-cooperation
    :description "Mineração cooperativa, escavação de veios de minério e quebra de blocos"
    :anchors ["karlach minera comigo"
              "minera ferro"
              "minera carvao"
              "minera diamante"
              "minera cobre"
              "minera ouro"
              "minera redstone"
              "quebra esse bloco"
              "minera aqui"
              "escava esse minerio"
              "pega esses minerios"
              "quebra essa pedra"
              "vamos minerar"]}

   {:id :inventory-inspection
    :description "Consulta do inventário e inspeção de recursos guardados na mochila"
    :anchors ["o que tem no seu inventario"
              "mostra seu inventario"
              "o que voce pegou"
              "quanto minerio voce tem"
              "ver inventario"
              "abre a mochila"
              "o que voce ta carregando"
              "quanto ferro voce pegou"
              "mostra o loot"]}

   {:id :drop-inventory
    :description "Ejetar saque, dropar minérios e entregar itens ao jogador"
    :anchors ["me da os minerios"
              "dropa o loot"
              "me entrega os itens"
              "passa o que voce minerou"
              "larga o minerio aqui"
              "dropa tudo"
              "me da os itens"
              "passa os minerios"
              "descarrega o inventario"
              "joga o loot no chao"]}

   {:id :dialogue
    :description "Diálogo aberto, saudações gerais e perguntas de conversa"
    :anchors ["ola tudo bem com voce"
              "como voce esta hoje"
              "o que voce acha deste santuario"
              "qual a sua funcao aqui"
              "bom dia karlach"
              "me conta uma historia"]}])

;; ----------------------------------------------------------------------------
;; Índice de Rotas Vetorizadas (Pré-compilado na inicialização)
;; ----------------------------------------------------------------------------

(defn compile-routes
  "Compila as rotas gerando os vetores normalizados para cada âncora."
  [routes]
  (mapv (fn [route]
          (let [vec-anchors (mapv (fn [anchor]
                                    {:raw anchor
                                     :vec (text->vector anchor)})
                                  (:anchors route))]
            (assoc route :compiled-anchors vec-anchors)))
        routes))

(def ^:private compiled-default-routes
  (compile-routes default-routes))

(defonce active-routes-atom
  (atom compiled-default-routes))

(defn get-active-routes
  "Retorna o vetor de rotas compiladas ativas no momento."
  []
  @active-routes-atom)

(defn register-route!
  "Adiciona ou atualiza dinamicamente uma rota semântica no router em tempo de execução (Hot-Reload P1.2).
   Opcionalmente persiste a definição em EDN na pasta especificada (P1.3)."
  [route-def & [{:keys [persist? routes-dir] :or {persist? false routes-dir "agents/routes"}}]]
  (let [compiled (first (compile-routes [route-def]))
        route-id (:id route-def)]
    (swap! active-routes-atom (fn [routes]
                                (conj (vec (remove #(= (:id %) route-id) routes))
                                      compiled)))
    (when (and persist? routes-dir)
      (try
        (let [dir (io/file routes-dir)]
          (.mkdirs dir)
          (spit (io/file dir (str (name route-id) ".edn"))
                (pr-str (select-keys route-def [:id :description :anchors]))))
        (catch Throwable _ nil)))
    compiled))

(defn unregister-route!
  "Remove uma rota semântica registrada e opcionalmente remove o arquivo de persistência."
  [route-id & [{:keys [routes-dir]}]]
  (swap! active-routes-atom (fn [routes]
                              (vec (remove #(= (:id %) route-id) routes))))
  (when routes-dir
    (let [f (io/file routes-dir (str (name route-id) ".edn"))]
      (when (.exists f) (.delete f))))
  true)

(defn list-routes
  "Retorna a lista resumida de todas as rotas atualmente ativas no router."
  []
  (mapv (fn [r]
          {:id           (:id r)
           :description  (:description r)
           :anchor-count (count (:anchors r))})
        @active-routes-atom))

(defn reset-routes-to-default!
  "Restaura as rotas padrão de fábrica."
  []
  (reset! active-routes-atom compiled-default-routes))

(defn load-persisted-routes!
  "Carrega rotas customizadas persistidas em arquivos .edn no diretório informado."
  [routes-dir]
  (let [dir (io/file routes-dir)]
    (if (and (.exists dir) (.isDirectory dir))
      (let [files (filter #(str/ends-with? (.getName %) ".edn") (.listFiles dir))
            loaded (keep (fn [f]
                           (try
                             (let [data (edn/read-string (slurp f))]
                               (when (and (:id data) (seq (:anchors data)))
                                 (register-route! data {:persist? false})))
                             (catch Throwable _ nil)))
                         files)]
        (vec loaded))
      [])))

(defn generate-skill-anchors
  "Gera variações coloquiais em português (afirmativas, imperativos, pedidos)
   a partir da definição de uma nova Skill (P1.1)."
  [skill-def]
  (let [name-clean (strip-accents (or (:skill/name skill-def) (name (:skill/id skill-def))))
        id-str     (name (:skill/id skill-def))
        base-terms (re-seq #"\w+" name-clean)
        phrase     (str/join " " base-terms)]
    (vec
     (distinct
      [phrase
       (str "executar " phrase)
       (str "vai " phrase)
       (str "ajuda a " phrase)
       (str "pode " phrase " por favor")
       (str "preciso que voce " phrase)
       (str "bora " phrase)
       (str "iniciar " phrase)
       (str "hora de " phrase)
       (str "favor " phrase)
       (str "faz o " phrase)
       (str "vamos " phrase)
       id-str]))))

(defn auto-register-skill-route!
  "Gera automaticamente âncoras para uma nova skill e registra dinamicamente
   no Semantic Router (P1.1 + P1.2 + P1.3)."
  [skill-def & [{:keys [persist? routes-dir custom-anchors] :or {persist? false routes-dir "agents/routes"}}]]
  (let [skill-id (:skill/id skill-def)
        anchors  (into (generate-skill-anchors skill-def)
                       (or custom-anchors []))
        route-def {:id          skill-id
                   :description (or (:skill/name skill-def) (str "Habilidade " skill-id))
                   :anchors     (vec (distinct anchors))}]
    (register-route! route-def {:persist? persist? :routes-dir routes-dir})))

;; ----------------------------------------------------------------------------
;; Mecanismo de Roteamento Semântico
;; ----------------------------------------------------------------------------

(defn score-route
  "Calcula o score de similaridade semântica entre o vetor de entrada e as âncoras da rota."
  [input-vec compiled-route]
  (let [anchors (:compiled-anchors compiled-route)]
    (reduce (fn [best {:keys [raw vec]}]
              (let [sim (cosine-similarity input-vec vec)]
                (if (> sim (:score best))
                  {:score sim :anchor raw}
                  best)))
            {:score 0.0 :anchor nil}
            anchors)))

(defn match-intent
  "Avalia o texto de entrada contra todas as rotas e retorna a melhor correspondência.
   threshold: limite mínimo para ativação da rota (default 0.28 em espaço esparso de subwords)."
  ([text]
   (match-intent text @active-routes-atom 0.26))
  ([text routes threshold]
   (let [input-vec (text->vector text)
         scores    (mapv (fn [r]
                           (let [{:keys [score anchor]} (score-route input-vec r)]
                             {:route  (:id r)
                              :score  score
                              :anchor anchor}))
                         routes)
         sorted    (vec (sort-by (comp - :score) scores))
         best      (first sorted)]
     (if (and best (>= (:score best) threshold))
       {:intent/type       (:route best)
        :intent/confidence (min 0.99 (+ 0.60 (* 0.40 (double (:score best)))))
        :intent/score      (:score best)
        :intent/anchor     (:anchor best)
        :intent/candidates (take 3 sorted)}
       {:intent/type       :dialogue
        :intent/confidence 0.65
        :intent/score      (get best :score 0.0)
        :intent/anchor     (get best :anchor)
        :intent/candidates (take 3 sorted)}))))
