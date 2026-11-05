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
                   [:target/speaker {:optional true} :string]
                   [:target/filter {:optional true} :string]]]
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
   "gatinho oriental"    {:entity "Gatinho Oriental"    :species "minecraft:cat"}
   "gato siames"         {:entity "Gato Siamês"         :species "minecraft:cat"}
   "gato rajado"         {:entity "Gato Rajado"         :species "minecraft:cat"}
   "gatinho preto"       {:entity "Gatinho Preto"       :species "minecraft:cat"}
   "gato calico"         {:entity "Gato Cálido"         :species "minecraft:cat"}
   "gatinhos"            {:entity "Gatinhos"            :species "minecraft:cat"}
   "gatinhas"            {:entity "Gatinhas"            :species "minecraft:cat"}
   "gatinho"             {:entity "Gatinho"             :species "minecraft:cat"}
   "gatinha"             {:entity "Gatinha"             :species "minecraft:cat"}
   "gatos"               {:entity "Gatos"               :species "minecraft:cat"}
   "gatas"               {:entity "Gatos"               :species "minecraft:cat"}
   "gato"                {:entity "Gato"                :species "minecraft:cat"}
   "gata"                {:entity "Gato"                :species "minecraft:cat"}
   "bichano"             {:entity "Gato"                :species "minecraft:cat"}
   "bichanos"            {:entity "Gatos"               :species "minecraft:cat"}
   "cats"                {:entity "Gatos"               :species "minecraft:cat"}
   "cat"                 {:entity "Gato"                :species "minecraft:cat"}
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

;; Teleporte intencional direto
(def ^:private teleport-pattern
  #"(teleport\w*|tp|teletransporte|teletransport\w*)")

;; Seguir o jogador (vem cá, me segue, acompanha, junto)
(def ^:private follow-pattern
  #"\b(vem\w*|venh\w*|segu\w*|sig\w*|acompanh\w*|junt\w*|atras|atraz|comigo)\b")

;; Parar, pausar ou dispensar (para, pare, chega, fica aqui, espera, descansa, valeu, etc.)
(def ^:private stop-pattern
  #"\b(par\w*|cheg\w*|bast\w*|fic\w*|esper\w*|paus\w*|descans\w*|pode ir|dispens\w*|obrigad\w*|valeu|tchau|calm\w*|quiet\w*)\b")

;; Retomada de patrulha e trabalho do santuário
(def ^:private patrol-pattern
  #"\b(patrulh\w*|rond\w*|trabalh\w*|aliment\w*|cuid\w*|rotin\w*|posto|afazer\w*)\b")

;; Reprodução de animais e procriação em AFK
(def ^:private breed-pattern
  #"\b(reproduz\w*|procri\w*|cruz\w*|namor\w*|acasal\w*|breed\w*|filhote\w*|filhotinho\w*)\b")

;; Dança e expressões sociais
(def ^:private dance-pattern
  #"\b(danc\w*|bail\w*|ging\w*|avernus|passinho)\b")

;; Clima e chuva
(def ^:private weather-pattern
  #"\b(chuv\w*|chov\w*|molh\w*|abrig\w*|tempor\w*|tempest\w*|raio|trov\w*)\b")

;; Guarda-costas, combate e autodefesa
(def ^:private bodyguard-pattern
  #"\b(guarda[\s-]*costas?|defend\w*|proteg\w*|escolt\w*|ataqu\w*|combat\w*|lut\w*|seguranc\w*|mat\w*|zumbi|monstro|esqueleto|aranha)\b")

;; Mineração cooperativa e escavação
(def ^:private mining-pattern
  #"\b(miner\w*|cav\w*|escav\w*|quebr\w*|picaret\w*|minerio|ferro|carvao|carvoes|ouro|diamante|cobre|redstone|lapis|pedra|deepslate|dripstone)\b")

;; Consulta e inspeção de inventário
(def ^:private inventory-pattern
  #"(inventario\w*|mochila\w*|loot|saque|itens?|recursos?|o que voce pegou|quanto minerio|o que tem|que voce tem|carregando)")

;; Ejeção e entrega de itens
(def ^:private drop-pattern
  #"\b(dropa\w*|entreg\w*|me da\w*|passa\w*|larga\w*|descarreg\w*|despej\w*|joga fora|joga no chao|libera\w*)\b")

;; Sentar em assentos e mobilias
(def ^:private sit-pattern
  #"\b(sent\w*|assent\w*|sofa\w*|cadeira\w*|poltrona\w*|banco\w*)\b")

;; Levantar de assentos e ficar de pe
(def ^:private stand-pattern
  #"\b(levant\w*|desmont\w*|em pe|de pe|sai do sofa)\b")

(defn extract-mining-target
  "Extrai o alvo específico de mineração ou bloco a partir do texto normalizado (NFD)."
  [norm-text]
  (cond
    (re-find #"\b(carvao|carvoes|coal)\b" norm-text) "coal"
    (re-find #"\b(ferro|iron)\b" norm-text) "iron"
    (re-find #"\b(diamante\w*|diamond\w*)\b" norm-text) "diamond"
    (re-find #"\b(cobre|copper)\b" norm-text) "copper"
    (re-find #"\b(ouro|gold)\b" norm-text) "gold"
    (re-find #"\b(redstone)\b" norm-text) "redstone"
    (re-find #"\b(lapis|lazuli)\b" norm-text) "lapis"
    (re-find #"\b(esmeralda\w*|emerald\w*)\b" norm-text) "emerald"
    (re-find #"\b(deepslate)\b" norm-text) "deepslate"
    (re-find #"\b(pedra|pedregulho|stone|cobble\w*)\b" norm-text) "stone"
    (re-find #"\b(dripstone|espeleotema|estalactite|estalagmite)\b" norm-text) "dripstone"
    (re-find #"\b(quartzo|quartz)\b" norm-text) "quartz"
    (re-find #"\b(netherite|debris)\b" norm-text) "ancient_debris"
    (re-find #"\b(esse|este|aqui|na minha frente|na frente|onde eu to olhando|na mira|apont\w*)\b" norm-text) "crosshair"
    :else nil))

;; Ensinar e sintetizar novas skills via chat (aprenda, ensina, nova skill, crie a skill)
(def ^:private teach-pattern
  #"\b(aprend\w*|ensin\w*|nova skill|novo comando|criar skill|crie a skill)\b")

(defn extract-teach-instruction
  "Extrai o payload da instrução de aprendizado de skill removendo menções e prefixos."
  [raw-text]
  (let [stripped (str/replace raw-text #"(?i)^[!/]?(karlach|picard)[,\s:]*" "")]
    (if-let [m (re-find #"(?i)\b(?:aprend\w*|ensin\w*|nova skill|novo comando|criar skill|crie a skill)[,\s:]*(.*)$" stripped)]
      (str/trim (second m))
      (str/trim stripped))))

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
        has-teach    (boolean (re-find teach-pattern norm))
        has-rescue   (boolean (re-find rescue-pattern norm))
        has-escape   (boolean (re-find escape-pattern norm))
        has-return   (boolean (re-find return-pattern norm))
        has-look     (boolean (re-find look-pattern norm))
        has-follow   (boolean (re-find follow-pattern norm))
        has-stop     (boolean (re-find stop-pattern norm))
        has-patrol   (boolean (re-find patrol-pattern norm))
        has-breed    (boolean (re-find breed-pattern norm))
        has-dance    (boolean (re-find dance-pattern norm))
        has-weather  (boolean (re-find weather-pattern norm))
        has-bodyguard (boolean (re-find bodyguard-pattern norm))
        has-mining   (boolean (re-find mining-pattern norm))
        has-inventory (boolean (re-find inventory-pattern norm))
        has-drop     (boolean (re-find drop-pattern norm))
        has-teleport (boolean (re-find teleport-pattern norm))
        has-sit      (boolean (re-find sit-pattern norm))
        has-stand    (boolean (re-find stand-pattern norm))]
    (cond
      ;; 0.0 Ensinar Nova Skill Dinâmica (Voyager Sistema 2)
      has-teach
      {:intent/type         :teach-skill
       :intent/confidence   0.99
       :intent/route-method :chat-synthesizer
       :intent/anchor       "aprenda nova skill"
       :intent/slots        {:skill/instruction (extract-teach-instruction raw-text)
                             :target/action     "teach-skill"
                             :target/speaker    speaker}
       :intent/raw-text     raw-text}

      ;; 0. Teleporte Intencional Direto ("karlach, teleportar pra ca", "da tp aqui")
      (or (= :teleport-to-player routed-type) has-teleport)
      {:intent/type         :teleport-to-player
       :intent/confidence   (max 0.98 (double router-conf))
       :intent/route-method :semantic-vector
       :intent/anchor       (:intent/anchor router-match)
       :intent/slots        {:target/action  "teleport-to-player"
                             :target/speaker speaker}
       :intent/raw-text     raw-text}

      ;; 0.1 Levantar do Assento / Ficar de Pe
      (and (not has-stop)
           (or (= :stand-up routed-type) has-stand))
      {:intent/type         :stand-up
       :intent/confidence   (max 0.95 (double router-conf))
       :intent/route-method :semantic-vector
       :intent/anchor       (:intent/anchor router-match)
       :intent/slots        {:target/action  "stand-up"
                             :target/speaker speaker}
       :intent/raw-text     raw-text}

      ;; 0.2 Sentar em Mobilia / Sofa / Cadeira
      (and (not has-stop)
           (or (= :sit-furniture routed-type)
               (and has-sit (not has-stand))))
      {:intent/type         :sit-furniture
       :intent/confidence   (max 0.95 (double router-conf))
       :intent/route-method :semantic-vector
       :intent/anchor       (:intent/anchor router-match)
       :intent/slots        {:target/action  "sit-furniture"
                             :target/speaker speaker}
       :intent/raw-text     raw-text}

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

      ;; 2.1 Reprodução de Animais / Gatos em AFK ("reproduz os gatos em afk", "cruza os gatos")
      (or (= :breed-animals routed-type)
          (and has-breed (or animal (re-find #"\b(gato|gatos|gatinho|gatinhos|bicho|animal|afk)\b" norm))))
      {:intent/type         :breed-animals
       :intent/confidence   (max 0.95 (double router-conf))
       :intent/route-method :semantic-vector
       :intent/anchor       (:intent/anchor router-match)
       :intent/slots        {:target/entity   (:entity animal "Gatos")
                             :target/species  (:species animal "minecraft:cat")
                             :target/action   "breed-afk"
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

      ;; 5.1 Modo Guarda-Costas / Combate e Autodefesa
      (or (= :bodyguard routed-type) has-bodyguard)
      {:intent/type         :bodyguard
       :intent/confidence   (max 0.95 (double router-conf))
       :intent/route-method :semantic-vector
       :intent/anchor       (:intent/anchor router-match)
       :intent/slots        {:target/action  "bodyguard"
                             :target/speaker speaker}
       :intent/raw-text     raw-text}

      ;; 5.2.1 Ejeção de Saque / Dropar Minérios (Prioridade sobre mineração se contiver verbo de drop/entrega)
      (or (= :drop-inventory routed-type)
          has-drop
          (and has-drop (or has-inventory has-mining (re-find #"(minerios?|itens?|loot|tudo|saque|ferro|ouro|carvao|diamante)" norm))))
      {:intent/type         :drop-inventory
       :intent/confidence   (max 0.95 (double router-conf))
       :intent/route-method :semantic-vector
       :intent/anchor       (:intent/anchor router-match)
       :intent/slots        {:target/action  "drop-inventory"
                             :target/filter  (or (extract-mining-target norm) "all")
                             :target/speaker speaker}
       :intent/raw-text     raw-text}

      ;; 5.2.2 Inspeção de Inventário e Mochila
      (or (= :inventory-inspection routed-type)
          (and has-inventory (re-find #"(mostr\w*|ve\w*|olh\w*|o que|quant\w*|tem|carreg\w*|abre)" norm)))
      {:intent/type         :inventory-inspection
       :intent/confidence   (max 0.95 (double router-conf))
       :intent/route-method :semantic-vector
       :intent/anchor       (:intent/anchor router-match)
       :intent/slots        {:target/action  "inspect-inventory"
                             :target/speaker speaker}
       :intent/raw-text     raw-text}

      ;; 5.2.3 Mineração Cooperativa & Escavação (apenas quando não é pedido de entrega/drop)
      (and (not has-drop)
           (not= :drop-inventory routed-type)
           (or (and (= :mining-cooperation routed-type) (not has-follow)) has-mining))
      (let [target-filter (extract-mining-target norm)]
        {:intent/type         :mining-cooperation
         :intent/confidence   (max 0.95 (double router-conf))
         :intent/route-method :semantic-vector
         :intent/anchor       (:intent/anchor router-match)
         :intent/slots        (cond-> {:target/action  "mining"
                                       :target/speaker speaker}
                                target-filter (assoc :target/filter target-filter))
         :intent/raw-text     raw-text})

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
       :intent/slots        (cond-> {:target/action  "resume-patrol"
                                     :target/speaker speaker}
                              animal (assoc :target/entity (:entity animal)
                                            :target/species (:species animal)))
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
