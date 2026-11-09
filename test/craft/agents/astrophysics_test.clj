(ns craft.agents.astrophysics-test
  "Suíte TDD de especificação para os Épicos CA-050, CA-051 e CA-052:
   - CA-050: Motor de Efemérides Determinístico f(world-time) & SceneDigest
   - CA-051: Geometria Fotométrica HR & Sombra Analítica dos Anéis
   - CA-052: Refino Canônico Coco & Richeh + Consciência Celeste nas BTs"
  (:require [clojure.test :refer [deftest is testing]]
            [craft.agents.astrophysics.ephemeris :as ephem]
            [craft.agents.perception :as perception]
            [craft.agents.bt.engine :as bt]
            [craft.agents.bt.builtins :as builtins]
            [craft.agents.backend.fabric.atelier :as atelier]
            [clojure.edn :as edn]
            [clojure.java.io :as io])
  (:import [com.craft.agents.client.sky AlienSkyGeometry]))

;; =============================================================================
;; CA-050: Efemérides Determinísticas & SceneDigest
;; =============================================================================

(deftest test-ephemeris-vector-normalization-and-determinism
  (testing "Vetores solar e do gigante gasoso são estritamente unitários e determinísticos"
    (let [s1 (ephem/compute-celestial-state 6000)
          s2 (ephem/compute-celestial-state 6000)
          s3 (ephem/compute-celestial-state 18000)]
      ;; Determinismo puro
      (is (= s1 s2) "Mesmo world-time deve produzir estado rigorosamente idêntico")
      (is (not= s1 s3) "Ticks diferentes devem produzir estados distintos")

      ;; Normalização vetorial
      (let [[sx sy sz] (:sun-vector s1)
            sun-mag (Math/sqrt (+ (* sx sx) (* sy sy) (* sz sz)))
            [gx gy gz] (get-in s1 [:gas-giant :vector])
            giant-mag (Math/sqrt (+ (* gx gx) (* gy gy) (* gz gz)))]
        (is (< (Math/abs (- sun-mag 1.0)) 1.0e-5) "Vetor solar deve ter magnitude 1.0")
        (is (< (Math/abs (- giant-mag 1.0)) 1.0e-5) "Vetor do gigante gasoso deve ter magnitude 1.0")))))

(deftest test-ephemeris-phase-classification
  (testing "Classificação de fases do Gigante Gasoso"
    (is (= :new-giant (ephem/classify-phase 0.95)))
    (is (= :crescent (ephem/classify-phase 0.50)))
    (is (= :quarter-illuminated (ephem/classify-phase 0.0)))
    (is (= :gibbous (ephem/classify-phase -0.50)))
    (is (= :full-illuminated (ephem/classify-phase -0.95)))))

(deftest test-ephemeris-eclipse-and-aurora-detection
  (testing "Detecção analítica de eclipse e intensidade de aurora"
    ;; Meio-dia (6000 ticks) -> Sol acima do horizonte
    (let [state-noon (ephem/compute-celestial-state 6000)]
      (is (> (:sun-elevation-deg state-noon) 0.0) "Meio-dia deve ter sol acima do horizonte")
      (is (= 0.0 (:aurora-intensity state-noon)) "Aurora deve ser zero durante o dia pleno"))

    ;; Noite (18000 ticks) -> Sol abaixo do horizonte, aurora elegível
    (let [state-midnight (ephem/compute-celestial-state 18000)]
      (is (< (:sun-elevation-deg state-midnight) -5.0) "Meia-noite deve ter sol abaixo do horizonte")
      (is (number? (:aurora-intensity state-midnight)))
      (is (>= (:aurora-intensity state-midnight) 0.0)))

    ;; Simulação direta de eclipse: quando o gigante e o sol se aproximam (< 8.5 graus)
    (let [eclipse-state (ephem/simulate-eclipse-state 6000 4.0)]
      (is (true? (:eclipse-active? eclipse-state)) "Deve sinalizar eclipse quando separação < 8.5°")
      (is (> (:eclipse-obscuration eclipse-state) 0.0) "Obscuração deve ser positiva durante o eclipse")
      (is (= :solar-eclipse (:phenomenon eclipse-state))))))

(deftest test-perception-scene-digest-celestial-coupling
  (testing "SceneDigest incorpora snapshot astrofísico na chave :celestial"
    (let [mock-backend (reify craft.agents.backend.protocol/IWorldBackend
                         (query-entities [_ _ _] [])
                         (spawn-agent! [_ _] nil)
                         (despawn-agent! [_ _] nil)
                         (active-agents [_] {})
                         (capture-voxel-snapshot [_ _ _] nil)
                         (has-line-of-sight? [_ _ _] true))
          mock-body (reify craft.agents.backend.protocol/IAgentBody
                      (body-state [_] {:name "Coco" :pos [-30.0 66.0 190.0]}))
          digest (perception/build-scene-digest mock-backend mock-body nil nil)]
      (is (contains? digest :celestial) "SceneDigest deve conter a chave :celestial")
      (let [cel (:celestial digest)]
        (is (contains? cel :sun-vector))
        (is (contains? cel :gas-giant))
        (is (contains? cel :phenomenon))
        (is (contains? cel :eclipse-active?))))))

;; =============================================================================
;; CA-051: Geometria Fotométrica HR & Sombra Analítica dos Anéis (Java 21)
;; =============================================================================

(deftest test-hr-blackbody-planck-temperatures
  (testing "Conversão Kelvin -> RGB do Diagrama HR (Corpo Negro)"
    ;; Classe M (Anã Vermelha 3000K): Vermelho dominante
    (let [rgb-m (AlienSkyGeometry/kelvinToRgb 3000.0)
          r (aget rgb-m 0) g (aget rgb-m 1) b (aget rgb-m 2)]
      (is (> r g) "Classe M deve ter R > G")
      (is (> g b) "Classe M deve ter G > B")
      (is (< b 0.4) "Classe M deve ter componente azul baixa"))

    ;; Classe G (Sol 5800K): Luz branca-amarelada equilibrada
    (let [rgb-g (AlienSkyGeometry/kelvinToRgb 5800.0)
          r (aget rgb-g 0) g (aget rgb-g 1) b (aget rgb-g 2)]
      (is (> r 0.85))
      (is (> g 0.75))
      (is (> b 0.65)))

    ;; Classe O/B (Supergigante Azul 25000K): Azul dominante
    (let [rgb-o (AlienSkyGeometry/kelvinToRgb 25000.0)
          r (aget rgb-o 0) g (aget rgb-o 1) b (aget rgb-o 2)]
      (is (> b r) "Classe O/B deve ter B > R")
      (is (> b 0.95) "Classe O/B deve ter B saturado"))))

(deftest test-starfield-catalog-generation
  (testing "Catálogo fotométrico estelar determinístico"
    (let [catalog (AlienSkyGeometry/generatePhotometricCatalog 200)]
      (is (= 200 (count catalog)) "Deve gerar exatamente 200 estrelas")
      (doseq [star (take 20 catalog)]
        (let [mag (Math/sqrt (+ (* (.x star) (.x star))
                                (* (.y star) (.y star))
                                (* (.z star) (.z star))))]
          (is (< (Math/abs (- mag 1.0)) 1.0e-4) "Estrela deve estar na esfera unitária")
          (is (<= 0.0 (.r star) 1.0))
          (is (<= 0.0 (.g star) 1.0))
          (is (<= 0.0 (.b star) 1.0))
          (is (> (.size star) 0.0)))))))

(deftest test-ring-shadow-ray-plane-intersection
  (testing "Cálculo analítico da sombra dos anéis sobre o planeta"
    (let [light-dir (float-array [0.0 1.0 0.0])
          ring-n (float-array [0.0 1.0 0.0])
          ;; Ponto no equador da esfera iluminado pelo sol
          surface-pos (float-array [1.5 -1.0 0.0])
          shadow (AlienSkyGeometry/computeRingShadowOnPlanet surface-pos light-dir ring-n 1.2 2.5)]
      (is (<= 0.25 shadow 1.0) "Fator de sombra deve atenuar a luz entre 0.25 e 1.0"))))

;; =============================================================================
;; CA-052: Refino Canônico Coco & Richeh + Consciência Celeste nas BTs
;; =============================================================================

(deftest test-canonical-coco-and-richeh-agent-definitions
  (testing "Fidelidade canônica dos arquivos agents/coco.edn e agents/richeh.edn"
    (let [coco-edn (edn/read-string (slurp "agents/coco.edn"))
          richeh-edn (edn/read-string (slurp "agents/richeh.edn"))]
      ;; Coco: pena + livro de escrita
      (is (= "minecraft:feather" (get-in coco-edn [:agent/loadout :mainhand])))
      (is (= "minecraft:writable_book" (get-in coco-edn [:agent/loadout :offhand])))
      (is (some #(re-find #"anéis|glifos|tinta" %) (get-in coco-edn [:agent/dialogues :castles])))

      ;; Richeh: fragmento de ametista + bolsa de tinta brilhante
      (is (= "minecraft:amethyst_shard" (get-in richeh-edn [:agent/loadout :mainhand])))
      (is (= "minecraft:glow_ink_sac" (get-in richeh-edn [:agent/loadout :offhand])))
      (is (re-find #"Cristal" (or (:agent/role richeh-edn) ""))))))

(deftest test-celestial-behavior-tree-guards-and-actions
  (testing "Guards e Ação de olhar para o Gigante Gasoso nas Behavior Trees"
    (let [celestial-ctx {:celestial {:eclipse-active? true
                                     :aurora-intensity 0.85
                                     :gas-giant {:vector [0.0 0.7071 0.7071]
                                                 :visible-above-horiz? true}}
                         :guards {:celestial-eclipse-active? (fn [ctx] (get-in ctx [:celestial :eclipse-active?]))
                                  :celestial-aurora-active?  (fn [ctx] (> (get-in ctx [:celestial :aurora-intensity] 0.0) 0.6))
                                  :gas-giant-visible?        (fn [ctx] (get-in ctx [:celestial :gas-giant :visible-above-horiz?]))}
                         :actions {:gaze-at-celestial-body (fn [ctx]
                                                             (let [body-vec (get-in ctx [:celestial :gas-giant :vector])
                                                                   [dx dy dz] body-vec
                                                                   pitch (* (Math/asin (- dy)) (/ 180.0 Math/PI))
                                                                   yaw (* (Math/atan2 (- dx) dz) (/ 180.0 Math/PI))]
                                                               {:status :success
                                                                :leases [:gaze]
                                                                :yaw (float yaw)
                                                                :pitch (float pitch)}))}}]

      ;; Teste do Guard de Eclipse
      (let [guard-res (bt/tick-tree [:guard :celestial-eclipse-active?] celestial-ctx)]
        (is (= :success (:status guard-res))))

      ;; Teste do Guard de Aurora
      (let [guard-res (bt/tick-tree [:guard :celestial-aurora-active?] celestial-ctx)]
        (is (= :success (:status guard-res))))

      ;; Teste da Ação de Olhar para o Gigante Gasoso
      (let [action-res (bt/tick-tree [:action :gaze-at-celestial-body {:body :gas-giant}] celestial-ctx)]
        (is (= :success (:status action-res)))
        (is (= [:gaze] (:leases action-res)))
        (is (< (:pitch action-res) 0.0) "Olhar para o céu requer pitch negativo (-Y)")
        (is (float? (:yaw action-res)))))))
