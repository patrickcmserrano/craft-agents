(ns craft.agents.cognition.intent-grounding-test
  "Especificação comportamental TDD para o Router de Intenções (System 1) e Grounding no Mundo (CA-040)."
  (:require [clojure.test :refer [deftest is testing]]
            [craft.agents.cognition.intent :as intent]
            [craft.agents.cognition.semantic-router :as router]
            [craft.agents.cognition.grounding :as grounding]))

(deftest test-intent-classification-no-collisions
  (testing "Frases com 'de volta pra casa' NÃO colidem com patrulha e ativam emergência animal"
    (let [res (intent/classify-intent-semantic "karlach, a capivara nadadora fugiu! leva ela de volta pra casa" "pace")]
      (is (= :animal-emergency (:intent/type res)))
      (is (= "Capivara Nadadora" (get-in res [:intent/slots :target/entity])))
      (is (>= (:intent/confidence res) 0.95))))

  (testing "Detecção de cervo fugitivo"
    (let [res (intent/classify-intent-semantic "karlach, o cervo fugiu, bota ele de volta pra dentro" "pace")]
      (is (= :animal-emergency (:intent/type res)))
      (is (re-find #"Cervo" (get-in res [:intent/slots :target/entity])))))

  (testing "Detecção de capivarinha filhote fora da grade"
    (let [res (intent/classify-intent-semantic "karlach, a capivarinha filhote está fora da grade, conduz ela pra dentro da jaula apropriada" "pace")]
      (is (= :animal-emergency (:intent/type res)))
      (is (= "Capivarinha Filhote" (get-in res [:intent/slots :target/entity])))))

  (testing "Detecção de Atenção Espacial Compartilhada (Joint Gaze / Olhar e Identificar)"
    (let [r1 (intent/classify-intent-semantic "karlach, ta vendo este cervo real aqui?" "pace")
          r2 (intent/classify-intent-semantic "karlach, ta vendo esse servo real aqui?" "pace")]
      (is (= :spatial-attention (:intent/type r1)))
      (is (= "Cervo Real" (get-in r1 [:intent/slots :target/entity])))
      (is (= :spatial-attention (:intent/type r2)))
      (is (= "Cervo Real" (get-in r2 [:intent/slots :target/entity])))))

  (testing "Comando de condução pra casa dele detecta emergência animal"
    (let [res (intent/classify-intent-semantic "karlach, leva este cervo pra casa dele" "pace")]
      (is (= :animal-emergency (:intent/type res)))
      (is (re-find #"Cervo" (get-in res [:intent/slots :target/entity])))))

  (testing "Retorno explícito para patrulha"
    (let [res (intent/classify-intent-semantic "karlach, volta pra patrulha no Santuário!" "pace")]
      (is (= :patrol-sanctuary (:intent/type res)))))

  (testing "Detecção de abrigo de chuva"
    (let [res (intent/classify-intent-semantic "karlach, está chovendo, vá se abrigar!" "pace")]
      (is (= :weather-shelter (:intent/type res)))))

  (testing "Detecção de comando de seguir e dança"
    (let [r-follow (intent/classify-intent-semantic "karlach, vem comigo!" "pace")
          r-dance  (intent/classify-intent-semantic "karlach, dança o passinho de Avernus 🔥" "pace")]
      (is (= :follow-player (:intent/type r-follow)))
      (is (= :social-dance (:intent/type r-dance)))))

  (testing "Variações colóquias e imperativas de resgate, parada e descanso (Anti-Fragilidade)"
    (let [r1 (intent/classify-intent-semantic "karlach, resgata essa capivara aqui" "pace")
          r2 (intent/classify-intent-semantic "karlach, pega essa capivara" "pace")
          r3 (intent/classify-intent-semantic "karlach, ajuda a prender o cervo" "pace")
          r4 (intent/classify-intent-semantic "karlach, bota esse bicho pra dentro" "pace")
          r5 (intent/classify-intent-semantic "karlach, parar" "pace")
          r6 (intent/classify-intent-semantic "karlach, descansa" "pace")
          r7 (intent/classify-intent-semantic "karlach, valeu" "pace")
          r8 (intent/classify-intent-semantic "karlach, me acompanha" "pace")]
      (is (= :animal-emergency (:intent/type r1)))
      (is (= "Capivara" (get-in r1 [:intent/slots :target/entity])))
      (is (= :animal-emergency (:intent/type r2)))
      (is (= :animal-emergency (:intent/type r3)))
      (is (= :animal-emergency (:intent/type r4)))
      (is (= :stop-following (:intent/type r5)))
      (is (= :stop-following (:intent/type r6)))
      (is (= :stop-following (:intent/type r7)))
      (is (= :follow-player (:intent/type r8))))))

(deftest test-semantic-grounding-with-sanctuary
  (testing "Grounding de Capivara Nadadora ancora no Bosque Sudoeste com skill geral :rescue-animal"
    (let [intent-res (intent/classify-intent-semantic "karlach, a capivara nadadora fugiu! leva ela de volta pra casa" "pace")
          goal-spec  (grounding/ground-goal intent-res)]
      (is (= :rescue-animal (:goal/id goal-spec)))
      (is (= :animal-rescue (:goal/type goal-spec)))
      (is (= :pen-south-west (:goal/pen-id goal-spec)))
      (is (= "minecraft:apple" (:goal/bait-item goal-spec)))
      (is (= [-179 65 189] (:goal/gate-pos goal-spec)))))

  (testing "Grounding de Cervo ancora no Bosque Sudoeste com skill geral :rescue-animal"
    (let [intent-res (intent/classify-intent-semantic "karlach, o cervo fugiu, bota ele de volta pra dentro" "pace")
          goal-spec  (grounding/ground-goal intent-res)]
      (is (= :rescue-animal (:goal/id goal-spec)))
      (is (= :pen-south-west (:goal/pen-id goal-spec)))))

  (testing "Grounding de Elefante ancora na Savana Sudeste com feno e portão leste"
    (let [intent-res (intent/classify-intent-semantic "karlach, o elefante fugiu, traz ele pro cercado" "pace")
          goal-spec  (grounding/ground-goal intent-res)]
      (is (= :rescue-animal (:goal/id goal-spec)))
      (is (= :pen-south-east (:goal/pen-id goal-spec)))
      (is (= "minecraft:hay_block" (:goal/bait-item goal-spec)))
      (is (= [-171 65 189] (:goal/gate-pos goal-spec))))))

(deftest test-semantic-router-vector-mechanics
  (testing "Similaridade de cosseno e subword n-grams"
    (let [v1 (router/text->vector "capivara")
          v2 (router/text->vector "capivarinha")
          v3 (router/text->vector "computador")
          sim-relacionada (router/cosine-similarity v1 v2)
          sim-distante    (router/cosine-similarity v1 v3)]
      (is (> sim-relacionada 0.35) "Variação morfológica deve manter alta similaridade")
      (is (< sim-distante 0.05) "Palavra não relacionada deve ter similaridade nula ou mínima")
      (is (> sim-relacionada sim-distante))))

  (testing "Roteamento semântico vetorial puro para intenções coloquiais"
    (let [r1 (router/match-intent "socorro a bicha fugiu corre")
          r2 (router/match-intent "dá um descanso agora")
          r3 (router/match-intent "vamos dar uma volta juntos")
          r4 (router/match-intent "tá caindo um toró daqueles")
          r5 (router/match-intent "mostra como você ginga")]
      (is (= :animal-emergency (:intent/type r1)))
      (is (= :stop-following (:intent/type r2)))
      (is (= :follow-player (:intent/type r3)))
      (is (= :weather-shelter (:intent/type r4)))
      (is (= :social-dance (:intent/type r5)))
      (is (contains? #{:animal-emergency :stop-following :follow-player :weather-shelter :social-dance} (:intent/type r1))))))

(deftest test-dynamic-route-hot-reload-and-persistence
  (testing "Hot-reload de rota dinâmica aprendida em tempo de execução (P1.2)"
    (try
      ;; Registra nova rota inédita: :build-bridge
      (router/register-route! {:id :build-bridge
                               :description "Construção de ponte de madeira"
                               :anchors ["construir ponte de carvalho ligando os habitats"
                                         "faz uma ponte sobre o lago"
                                         "bora montar a ponte de madeira"]})
      (let [r1 (router/match-intent "karlach, vai construir ponte de carvalho ligando os habitats")
            r2 (intent/classify-intent-semantic "karlach, faz uma ponte sobre o lago agora" "pace")]
        (is (= :build-bridge (:intent/type r1)))
        (is (= :build-bridge (:intent/type r2)))
        (is (= :dynamic-skill-route (:intent/route-method r2)))
        (is (>= (:intent/confidence r2) 0.70)))

      ;; Confirma listagem de rotas
      (let [routes (router/list-routes)]
        (is (some #(= :build-bridge (:id %)) routes)))

      (finally
        (router/unregister-route! :build-bridge)
        (router/reset-routes-to-default!))))

  (testing "Geração automática de âncoras e auto-registro a partir de Skill (P1.1)"
    (let [skill {:skill/id :gather-wood
                 :skill/name "Coletar Madeira de Carvalho"}
          anchors (router/generate-skill-anchors skill)]
      (is (seq anchors))
      (is (some #(re-find #"coletar madeira" %) anchors))
      (is (some #(re-find #"vai coletar" %) anchors))

      (try
        (router/auto-register-skill-route! skill)
        (let [res (intent/classify-intent-semantic "karlach, vai coletar madeira de carvalho" "pace")]
          (is (= :gather-wood (:intent/type res))))
        (finally
          (router/unregister-route! :gather-wood)
          (router/reset-routes-to-default!)))))

  (testing "Persistência e recarga de rotas dinâmicas em disco (P1.3)"
    (let [tmp-dir (str "/tmp/craft-routes-test-" (System/currentTimeMillis))]
      (try
        (router/register-route! {:id :feed-pandas
                                 :description "Alimentar pandas com bambu"
                                 :anchors ["alimenta os pandas com bambu fresco"
                                           "leva bambu pros pandas"]}
                                {:persist? true :routes-dir tmp-dir})
        ;; Arquivo .edn deve ter sido criado
        (is (.exists (java.io.File. (str tmp-dir "/feed-pandas.edn"))))

        ;; Reseta o router para o padrão
        (router/reset-routes-to-default!)
        (is (not-any? #(= :feed-pandas (:id %)) (router/list-routes)))

        ;; Recarrega a partir da pasta persistida
        (let [loaded (router/load-persisted-routes! tmp-dir)]
          (is (= 1 (count loaded)))
          (is (some #(= :feed-pandas (:id %)) (router/list-routes)))
          (let [res (router/match-intent "karlach, alimenta os pandas com bambu fresco")]
            (is (= :feed-pandas (:intent/type res)))))

        (finally
          (router/reset-routes-to-default!)
          (let [d (java.io.File. tmp-dir)]
            (doseq [f (.listFiles d)] (.delete f))
            (.delete d)))))))

(deftest test-targeted-mining-intent-and-grounding
  (testing "Extração de slots e grounding para minérios específicos"
    (let [r-iron (intent/classify-intent-semantic "karlach, minera ferro aqui" "pace")
          g-iron (grounding/ground-goal r-iron)]
      (is (= :mining-cooperation (:intent/type r-iron)))
      (is (= "iron" (get-in r-iron [:intent/slots :target/filter])))
      (is (= "iron" (:goal/filter g-iron)))
      (is (re-find #"ferro" (:goal/voice-line g-iron))))

    (let [r-coal (intent/classify-intent-semantic "karlach, minera carvão" "pace")
          g-coal (grounding/ground-goal r-coal)]
      (is (= :mining-cooperation (:intent/type r-coal)))
      (is (= "coal" (get-in r-coal [:intent/slots :target/filter])))
      (is (= "coal" (:goal/filter g-coal)))
      (is (re-find #"carvão" (:goal/voice-line g-coal))))

    (let [r-dia (intent/classify-intent-semantic "karlach, minera diamante agora" "pace")
          g-dia (grounding/ground-goal r-dia)]
      (is (= :mining-cooperation (:intent/type r-dia)))
      (is (= "diamond" (get-in r-dia [:intent/slots :target/filter])))
      (is (= "diamond" (:goal/filter g-dia)))
      (is (re-find #"diamante" (:goal/voice-line g-dia)))))

  (testing "Extração de comando por mira/crosshair ('quebra esse bloco' / 'minera aqui')"
    (let [r-look (intent/classify-intent-semantic "karlach, quebra esse bloco" "pace")
          g-look (grounding/ground-goal r-look)]
      (is (= :mining-cooperation (:intent/type r-look)))
      (is (= "crosshair" (get-in r-look [:intent/slots :target/filter])))
      (is (= "crosshair" (:goal/filter g-look)))
      (is (or (re-find #"frente" (:goal/voice-line g-look))
              (re-find #"mira" (:goal/voice-line g-look)))))))
