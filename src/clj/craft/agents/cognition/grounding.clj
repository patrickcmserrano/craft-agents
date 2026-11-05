(ns craft.agents.cognition.grounding
  "Grounding Semântico no Grafo de Conhecimento do Mundo (Padrão Rohana/EQL).
   Cruza as intenções e slots com sanctuary.edn e perfis de agentes,
   derivando coordenadas exatas, atuadores espaciais e metas estruturadas (CA-040)."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(defonce ^:private sanctuary-cache
  (delay
    (try
      (edn/read-string (slurp (io/file "agents/regions/sanctuary.edn")))
      (catch Throwable _
        {:region/enclosures []}))))

(defn get-sanctuary-enclosures []
  (:region/enclosures @sanctuary-cache []))

(defn find-enclosure-for-entity
  "Localiza o cercado correspondente à entidade ou espécie no grafo de conhecimento."
  [entity-name species]
  (let [enclosures (get-sanctuary-enclosures)
        norm-name  (when entity-name (str/lower-case entity-name))]
    (or
     ;; 1. Correspondência exata por nome de residente cadastrado
     (some (fn [enc]
             (when (some #(and (:name %) (str/includes? (str/lower-case (:name %)) (or norm-name "")))
                         (:enclosure/residents enc))
               enc))
           enclosures)
     ;; 2. Correspondência por espécie cadastrada no cercado
     (some (fn [enc]
             (when (some #(and species (= % species)) (:enclosure/species enc))
               enc))
           enclosures)
     ;; 3. Fallback: Bosque das Capivaras & Cervos (:pen-south-west)
     (some #(when (= (:enclosure/id %) :pen-south-west) %) enclosures))))

(defn ground-goal
  "Deriva uma especificação formal de meta (GoalSpecification) a partir do resultado da intenção.
   Ancora o raciocínio em coordenadas 3D físicas, portões e itens de atração cadastrados no mundo."
  [intent-result]
  (let [itype   (:intent/type intent-result)
        slots   (:intent/slots intent-result)
        entity  (:target/entity slots "Capivara")
        species (:target/species slots "naturalist:capybara")
        speaker (:target/speaker slots "pace")]
    (case itype
      :animal-emergency
      (let [enc       (find-enclosure-for-entity entity species)
            pen-id    (:enclosure/id enc :pen-south-west)
            enc-name  (or (:enclosure/name enc) "Bosque")
            portal    (:enclosure/portal enc)
            gate-pos  (or (first (:blocks portal)) [-179 65 189])
            inside    (or (:inside-anchor portal) [-181.5 65.0 189.0])
            outside   (or (:outside-anchor portal) [-177.0 65.0 189.0])
            feed-pos  (or (:enclosure/feed-pos enc) [-192.0 65.0 189.0])
            food      (cond
                        (or (= species "minecraft:cat") (= species "Cat")) "minecraft:cod"
                        :else (or (:enclosure/food enc) "minecraft:apple"))
            gate-bl   (or (:gate-block portal) "minecraft:dark_oak_fence_gate")
            facing    (or (:gate-facing portal) "east")
            food-name (case food
                        "minecraft:apple" "uma maçã"
                        "minecraft:hay_block" "um fardo de feno"
                        "minecraft:bamboo" "um bambu"
                        "minecraft:sweet_berries" "bagas doces"
                        "minecraft:cod" "um peixe fresco"
                        "minecraft:salmon" "um salmão apetitoso"
                        "uma comidinha")
            voice-msg (str "O " entity " fugiu?! Deixa comigo, soldado! Pegando " food-name " e um laço pra trazer de volta ao " enc-name "!")]
        {:goal/id          :rescue-animal
         :goal/type        :animal-rescue
         :goal/name        (str "Condução de " entity " ao " enc-name)
         :goal/entity      entity
         :goal/species     species
         :goal/pen-id      pen-id
         :goal/portal      portal
         :goal/gate-pos    gate-pos
         :goal/gate-block  gate-bl
         :goal/gate-facing facing
         :goal/inside-pos  inside
         :goal/outside-pos outside
         :goal/feed-pos    feed-pos
         :goal/bait-item   food
         :goal/voice-line  voice-msg
         :goal/speaker     speaker})

      :spatial-attention
      {:goal/id         :spatial-attention
       :goal/type       :spatial-attention
       :goal/entity     entity
       :goal/species    species
       :goal/speaker    speaker
       :goal/voice-line (str "Tô vendo sim, " speaker "! Um belo de um " entity " bem aí perto de você!")}

      :breed-animals
      (let [enc       (find-enclosure-for-entity entity species)
            pen-id    (:enclosure/id enc :pen-north-east)
            food      (if (or (= species "minecraft:cat") (= species "Cat")) "minecraft:cod" (or (:enclosure/food enc) "minecraft:apple"))
            voice-msg (str "Pode deixar, " speaker "! Ativando modo de reprodução de gatos em AFK! 🔥 🐱 Vou manter nossos bichanos sempre com peixe fresco, soltos pra namorar e cheios de corações pra lotar o Santuário de filhotinhos enquanto você estiver AFK! 🐟 💕")]
        {:goal/id         :breed-animals
         :goal/type       :breed-animals
         :goal/name       (str "Reprodução de " entity " em AFK")
         :goal/entity     entity
         :goal/species    species
         :goal/pen-id     pen-id
         :goal/bait-item  food
         :goal/voice-line voice-msg
         :goal/speaker    speaker})

      :weather-shelter
      {:goal/id         :shelter-from-rain
       :goal/type       :weather-shelter
       :goal/name       "Abrigo de Emergência contra Chuva"
       :goal/voice-line "Chuva detectada! O motor infernal chia com água, soldado! Sintetizando nova Skill de abrigo emergencial..."
       :goal/speaker    speaker}

      :stop-following
      {:goal/id         :patrol-sanctuary
       :goal/type       :patrol-sanctuary
       :goal/voice-line (str "Entendido, " speaker "! Parando de te seguir e voltando pros meus afazeres no Santuário.")
       :goal/speaker    speaker}

      :teleport-to-player
      {:goal/id         :teleport-to-player
       :goal/type       :teleport-to-player
       :goal/target     speaker
       :goal/voice-line (str "Abrindo fenda infernal! Teleportando direto pra você, " speaker "! 🔥 🌀")
       :goal/speaker    speaker}

      :follow-player
      {:goal/id         :follow-player
       :goal/type       :follow-player
       :goal/target     speaker
       :goal/voice-line (str "Tô indo até você, " speaker "! Meu motor infernal já tá aquecido!")
       :goal/speaker    speaker}

      :bodyguard
      {:goal/id         :bodyguard
       :goal/type       :bodyguard
       :goal/target     speaker
       :goal/voice-line (str "Modo Guarda-Costas ativado, soldado! Macho nenhum e monstro nenhum encosta em você enquanto meu motor infernal estiver rugindo! 🔥")
       :goal/speaker    speaker}

      :mining-cooperation
      (let [target-filter (get-in intent-result [:intent/slots :target/filter])
            ore-label (case target-filter
                        "diamond" "diamante"
                        "iron" "ferro"
                        "coal" "carvão"
                        "copper" "cobre"
                        "gold" "ouro"
                        "redstone" "redstone"
                        "lapis" "lápis-lazúli"
                        "emerald" "esmeralda"
                        "deepslate" "deepslate"
                        "stone" "pedra"
                        "dripstone" "espeleotema"
                        "crosshair" "esse bloco na sua mira"
                        nil)
            voice (cond
                    (= target-filter "crosshair")
                    (str "Entendido, " speaker "! Quebrando esse bloco bem na sua frente! ⛏️ 🔥")

                    ore-label
                    (str "Picareta em punho, " speaker "! Deixa comigo que vou escavar veios de " ore-label " pra gente! 🔥 ⛏️")

                    :else
                    (str "Picareta em punho, " speaker "! Deixa que eu quebro as pedras e minérios pra gente! ⛏️"))]
        {:goal/id         :mining-cooperation
         :goal/type       :mining-cooperation
         :goal/target     speaker
         :goal/filter     target-filter
         :goal/voice-line voice
         :goal/speaker    speaker})

      :social-dance
      {:goal/id         :dance-avernus
       :goal/type       :social-dance
       :goal/voice-line "Haha! Olha o passinho de Avernus, soldado! 🔥"
       :goal/speaker    speaker}

      :patrol-sanctuary
      {:goal/id         :patrol-sanctuary
       :goal/type       :patrol-sanctuary
       :goal/voice-line (cond
                          (and entity (re-find #"(?i)gat" entity))
                          (str "Com certeza, " speaker "! Deixa comigo que vou dar atenção e peixinho fresco pros nossos gatinhos e rondar o Santuário! 🐟 🐱")
                          (seq entity)
                          (str "Entendido, " speaker "! Vou dar atenção especial para " entity " e patrulhar o Santuário!")
                          :else "De volta à ronda do Santuário! Vou cuidar de todos os bichinhos!")
       :goal/speaker    speaker}

      :inventory-inspection
      {:goal/id         :inventory-inspection
       :goal/type       :inventory-inspection
       :goal/voice-line (str "Deixa eu checar o que eu tô carregando aqui, " speaker "!")
       :goal/speaker    speaker}

      :drop-inventory
      {:goal/id         :drop-inventory
       :goal/type       :drop-inventory
       :goal/filter     (get-in intent-result [:intent/slots :target/filter] "all")
       :goal/voice-line (str "Loot saindo do forno, " speaker "! Pegando o saque pra você!")
       :goal/speaker    speaker}

      :sit-furniture
      {:goal/id         :sit-furniture
       :goal/type       :sit-furniture
       :goal/voice-line (str "Ah, uma pausa bem merecida! Sentando aqui do seu lado no sofá, " speaker "! 🔥")
       :goal/speaker    speaker}

      :stand-up
      {:goal/id         :stand-up
       :goal/type       :stand-up
       :goal/voice-line (str "De pé e pronta pro combate, " speaker "! Qual é o plano agora? 🔥")
       :goal/speaker    speaker}

      :teach-skill
      {:goal/id          :teach-skill
       :goal/type        :teach-skill
       :goal/raw-text    (:intent/raw-text intent-result)
       :goal/instruction (get-in intent-result [:intent/slots :skill/instruction])
       :goal/speaker     speaker}

      ;; Fallback / Dynamic Skill Route
      (if (= (:intent/route-method intent-result) :dynamic-skill-route)
        {:goal/id       itype
         :goal/type     :execute-dynamic-skill
         :goal/skill-id itype
         :goal/raw-text (:intent/raw-text intent-result)
         :goal/speaker  speaker}
        {:goal/id         :natural-dialogue
         :goal/type       :dialogue
         :goal/raw-text   (:intent/raw-text intent-result)
         :goal/voice-line (str "Tô na escuta, " speaker "! Pode falar que eu tô atenta.")
         :goal/speaker    speaker}))))
