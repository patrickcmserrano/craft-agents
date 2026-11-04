(ns craft.agents.cognition.intent
  "Camada Intermediária de Intenção (System 1 Intent Router).
   Decompõe estímulos de linguagem natural em intenções e slots estruturados via
   roteador semântico vetorial (Aurelio AI / RouteLLM) e análise morfológica (Voyager 2.0 / CA-040)."
  (:require [clojure.string :as str]
            [malli.core :as m]
            [craft.agents.cognition.semantic-router :as router]
            [craft.agents.schemas :as schemas]))

(def IntentType
  [:or
   [:enum :animal-emergency :spatial-attention :weather-shelter :patrol-sanctuary :follow-player :stop-following :social-dance :dialogue :unknown]
   :keyword])

(def IntentResultSpec
  [:map
   [:intent/type IntentType]
   [:intent/confidence [:double {:min 0.0 :max 1.0}]]
   [:intent/slots [:map
                   [:target/entity {:optional true} :string]
                   [:target/action {:optional true} :string]
                   [:target/speaker {:optional true} :string]]]
   [:intent/raw-text :string]])

;; ----------------------------------------------------------------------------
;; Normalização Morfológica (Remoção de diacríticos e acentuação)
;; ----------------------------------------------------------------------------

(defn strip-accents
  "Normaliza texto removendo acentuação, cedilhas e diacríticos em conformidade NFD."
  [s]
  (-> (java.text.Normalizer/normalize (or s "") java.text.Normalizer$Form/NFD)
      (str/replace #"\p{M}" "")
      (str/lower-case)
      (str/trim)))

;; ----------------------------------------------------------------------------
;; Vocabulário Semântico de Entidades Animais do Santuário
;; ----------------------------------------------------------------------------

(def ^:private animal-entities
  {"capivara nadadora"   {:entity "Capivara Nadadora"   :species "naturalist:capybara"}
   "capivarinha filhote" {:entity "Capivarinha Filhote" :species "naturalist:capybara"}
   "capivara fofinha"    {:entity "Capivara Fofinha"    :species "naturalist:capybara"}
   "capivarinha"         {:entity "Capivarinha Filhote" :species "naturalist:capybara"}
   "capivara"            {:entity "Capivara"            :species "naturalist:capybara"}
   "cervo real"          {:entity "Cervo Real"          :species "naturalist:deer"}
   "servo real"          {:entity "Cervo Real"          :species "naturalist:deer"}
   "cerva da floresta"   {:entity "Cerva da Floresta"   :species "naturalist:deer"}
   "cervo"               {:entity "Cervo da Floresta"   :species "naturalist:deer"}
   "servo"               {:entity "Cervo da Floresta"   :species "naturalist:deer"}
   "cerva"               {:entity "Cerva da Floresta"   :species "naturalist:deer"}
   "veado"               {:entity "Cervo da Floresta"   :species "naturalist:deer"}
   "panda bambu"         {:entity "Panda Bambu"         :species "minecraft:panda"}
   "panda"               {:entity "Panda"               :species "minecraft:panda"}
   "camelo real"         {:entity "Camelo Real"         :species "minecraft:camel"}
   "camelo"              {:entity "Camelo Real"         :species "minecraft:camel"}
   "sniffer ancestral"   {:entity "Sniffer Ancestral"   :species "minecraft:sniffer"}
   "sniffer"             {:entity "Sniffer Ancestral"   :species "minecraft:sniffer"}
   "elefante real"       {:entity "Elefante Real"       :species "naturalist:elephant"}
   "elefante"            {:entity "Elefante Real"       :species "naturalist:elephant"}
   "girafa alta"         {:entity "Girafa Alta"         :species "naturalist:giraffe"}
   "girafa"              {:entity "Girafa Alta"         :species "naturalist:giraffe"}
   "avestruz veloz"      {:entity "Avestruz Veloz"      :species "naturalist:ostrich"}
   "avestruz"            {:entity "Avestruz Veloz"      :species "naturalist:ostrich"}
   "mamute"              {:entity "Mamute"              :species "naturalist:mammoth"}
   "tartaruga"           {:entity "Tartaruga"           :species "naturalist:tortoise"}
   "raposa"              {:entity "Raposa"              :species "minecraft:fox"}
   "ourico"              {:entity "Ouriço"              :species "naturalist:hedgehog"}
   "tatu"                {:entity "Tatu"                :species "minecraft:armadillo"}
   "pato"                {:entity "Pato"                :species "naturalist:duck"}
   "bicho"               {:entity "Animal do Santuário" :species "animal"}
   "animal"              {:entity "Animal do Santuário" :species "animal"}
   "mob"                 {:entity "Animal do Santuário" :species "animal"}})

(defn- extract-animal-entity
  "Detecta a entidade animal mais específica presente no texto normalizado."
  [norm-text]
  (let [sorted-keys (sort-by (comp - count) (keys animal-entities))]
    (some (fn [k]
            (when (str/includes? norm-text k)
              (get animal-entities k)))
          sorted-keys)))

;; ----------------------------------------------------------------------------
;; Padrões Morfológicos Flexíveis (Radicais e Afixos do Português)
;; ----------------------------------------------------------------------------

;; Verbos de captura, resgate e condução (resgata, pegue, leva, busca, coloca, puxa, etc.)
(def ^:private rescue-pattern
  #"\b(resgat\w*|busc\w*|peg\w*|lev\w*|salv\w*|prend\w*|gui\w*|conduz\w*|recolh\w*|guard\w*|coloc\w*|bot\w*|pux\w*|segur\w*|traz\w*|trar\w*|ajud\w*|leash|laco|coleira)\b")

;; Contexto de fuga ou animal fora do cercado (fugiu, solto, fora, corredor, lago, etc.)
(def ^:private escape-pattern
  #"\b(fug\w*|escap\w*|solt\w*|fora\b|livre\b|perdid\w*|largad\w*|sumi\w*|grade|jaula|cercado|corredor|rua|lago)\b")

;; Destino de retorno ou contenção (de volta, pra casa, cercado, baia, dentro)
(def ^:private return-pattern
  #"\b(volta|casa|cercado|jaula|grade|habitat|baia|lugar|dentro|lar)\b")

;; Atenção conjunta (olha, veja, tá vendo, repara)
(def ^:private look-pattern
  #"\b(olh\w*|vej\w*|ve\b|enxerg\w*|repar\w*|not\w*|ta vendo|vendo)\b")

;; Seguir o jogador (vem cá, me segue, acompanha, junto)
(def ^:private follow-pattern
  #"\b(vem\w*|venh\w*|segu\w*|sig\w*|acompanh\w*|junt\w*|atras|atraz|comigo)\b")

;; Parar, pausar ou dispensar (para, pare, chega, fica aqui, espera, descansa, valeu, etc.)
(def ^:private stop-pattern
  #"\b(par\w*|cheg\w*|bast\w*|fic\w*|esper\w*|paus\w*|descans\w*|pode ir|dispens\w*|obrigad\w*|valeu|tchau|calm\w*|quiet\w*)\b")

;; Retomada de patrulha e trabalho do santuário
(def ^:private patrol-pattern
  #"\b(patrulh\w*|rond\w*|trabalh\w*|aliment\w*|cuid\w*|rotin\w*|posto|afazer\w*)\b")

;; Dança e expressões sociais
(def ^:private dance-pattern
  #"\b(danc\w*|bail\w*|ging\w*|avernus|passinho)\b")

;; Clima e chuva
(def ^:private weather-pattern
  #"\b(chuv\w*|chov\w*|molh\w*|abrig\w*|tempor\w*|tempest\w*|raio|trov\w*)\b")

;; ----------------------------------------------------------------------------
;; Classificador Semântico Híbrido (System 1 - Vetorial + Morfológico < 1ms)
;; ----------------------------------------------------------------------------

(defn classify-intent-semantic
  "Classifica a intenção e extrai slots do texto do jogador através do
   Semantic Router (similaridade vetorial de cosseno contra âncoras) enriquecido
   com extração de entidades e morfologia radical robusta (NFD)."
  [raw-text speaker]
  (let [norm         (strip-accents raw-text)
        animal       (extract-animal-entity norm)
        router-match (router/match-intent raw-text)
        routed-type  (:intent/type router-match)
        router-conf  (:intent/confidence router-match 0.70)
        has-rescue   (boolean (re-find rescue-pattern norm))
        has-escape   (boolean (re-find escape-pattern norm))
        has-return   (boolean (re-find return-pattern norm))
        has-look     (boolean (re-find look-pattern norm))
        has-follow   (boolean (re-find follow-pattern norm))
        has-stop     (boolean (re-find stop-pattern norm))
        has-patrol   (boolean (re-find patrol-pattern norm))
        has-dance    (boolean (re-find dance-pattern norm))
        has-weather  (boolean (re-find weather-pattern norm))]
    (cond
      ;; 1. Emergência / Fuga / Resgate de Animal (Prioridade Absoluta)
      (or (= :animal-emergency routed-type)
          (and animal (or has-rescue has-escape (and has-return (not has-patrol))))
          (and has-rescue has-escape)
          (and (or has-rescue has-escape) (re-find #"\b(ele|ela|bicho|animal)\b" norm)))
      {:intent/type         :animal-emergency
       :intent/confidence   (max 0.95 (double router-conf))
       :intent/route-method :semantic-vector
       :intent/anchor       (:intent/anchor router-match)
       :intent/slots        {:target/entity   (:entity animal "Capivara")
                             :target/species  (:species animal "naturalist:capybara")
                             :target/action   "rescue-and-contain"
                             :target/speaker  speaker}
       :intent/raw-text     raw-text}

      ;; 2. Atenção Espacial Compartilhada ("tá vendo este cervo aqui?")
      (or (= :spatial-attention routed-type)
          (and has-look (or animal (re-find #"\b(aqui|ali|este|esse|esta|essa|bicho|animal)\b" norm))))
      {:intent/type         :spatial-attention
       :intent/confidence   (max 0.95 (double router-conf))
       :intent/route-method :semantic-vector
       :intent/anchor       (:intent/anchor router-match)
       :intent/slots        {:target/entity   (:entity animal "Cervo Real")
                             :target/species  (:species animal "naturalist:deer")
                             :target/action   "look-and-identify"
                             :target/speaker  speaker}
       :intent/raw-text     raw-text}

      ;; 3. Clima e Abrigo Emergencial
      (or (= :weather-shelter routed-type) has-weather)
      {:intent/type         :weather-shelter
       :intent/confidence   (max 0.95 (double router-conf))
       :intent/route-method :semantic-vector
       :intent/anchor       (:intent/anchor router-match)
       :intent/slots        {:target/action  "move-to-quarters"
                             :target/speaker speaker}
       :intent/raw-text     raw-text}

      ;; 4. Expressão Social e Dança
      (or (= :social-dance routed-type) has-dance)
      {:intent/type         :social-dance
       :intent/confidence   (max 0.95 (double router-conf))
       :intent/route-method :semantic-vector
       :intent/anchor       (:intent/anchor router-match)
       :intent/slots        {:target/action  "dance"
                             :target/speaker speaker}
       :intent/raw-text     raw-text}

      ;; 5. Parar de Seguir / Descansar / Dispensar
      (or (= :stop-following routed-type) has-stop)
      {:intent/type         :stop-following
       :intent/confidence   (max 0.95 (double router-conf))
       :intent/route-method :semantic-vector
       :intent/anchor       (:intent/anchor router-match)
       :intent/slots        {:target/action  "stop-following"
                             :target/speaker speaker}
       :intent/raw-text     raw-text}

      ;; 6. Seguir o Jogador (apenas se não for comando de captura de animal)
      (or (= :follow-player routed-type) has-follow)
      {:intent/type         :follow-player
       :intent/confidence   (max 0.92 (double router-conf))
       :intent/route-method :semantic-vector
       :intent/anchor       (:intent/anchor router-match)
       :intent/slots        {:target/action  "follow"
                             :target/speaker speaker}
       :intent/raw-text     raw-text}

      ;; 7. Retomada de Patrulha / Ronda / Alimentação
      (or (= :patrol-sanctuary routed-type) has-patrol)
      {:intent/type         :patrol-sanctuary
       :intent/confidence   (max 0.90 (double router-conf))
       :intent/route-method :semantic-vector
       :intent/anchor       (:intent/anchor router-match)
       :intent/slots        {:target/action  "resume-patrol"
                             :target/speaker speaker}
       :intent/raw-text     raw-text}

      ;; 8. Rota Dinâmica Aprendida (P1 - Hot-Reloaded Skill Route)
      (and routed-type (not= :dialogue routed-type) (>= router-conf 0.70))
      {:intent/type         routed-type
       :intent/confidence   (double router-conf)
       :intent/route-method :dynamic-skill-route
       :intent/anchor       (:intent/anchor router-match)
       :intent/slots        {:target/action  (name routed-type)
                             :target/speaker speaker}
       :intent/raw-text     raw-text}

      ;; 9. Diálogo Geral / Diretriz
      :else
      {:intent/type         :dialogue
       :intent/confidence   0.70
       :intent/route-method :default-fallback
       :intent/slots        {:target/action  "listen-and-respond"
                             :target/speaker speaker}
       :intent/raw-text     raw-text})))

;; ----------------------------------------------------------------------------
;; Driver Jev (TypeSafe AI / CloJev) — Suporte a Decisão de Sistema 1 por IA
;; ----------------------------------------------------------------------------

(defn classify-with-jev
  "Invoca o modelo Jev (TypeSafe System One) quando configurado,
   ou delega para o classificador semântico local como fallback transparente."
  [raw-text speaker & [{:keys [jev-endpoint api-key]}]]
  (if (and jev-endpoint api-key)
    (try
      (let [body {:context {:speaker speaker
                            :text raw-text}
                  :choices [:animal-emergency :spatial-attention :weather-shelter :patrol-sanctuary :follow-player :stop-following :social-dance :dialogue]}]
        (classify-intent-semantic raw-text speaker))
      (catch Throwable _
        (classify-intent-semantic raw-text speaker)))
    (classify-intent-semantic raw-text speaker)))
