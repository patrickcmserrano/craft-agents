(ns craft.agents.backend.fabric.navigation
  "Calculo de orientacao espacial, pathfinding A* 3D e expansao de rotas em micro-passos."
  (:require [clojure.string :as str]
            [craft.agents.backend.fabric.transport :as transport :refer [uformat]]))

(defn compute-yaw [dx dz]
  (float (* (/ 180.0 Math/PI) (Math/atan2 (- (double dx)) (double dz)))))

(defn compute-pitch [dy hdist]
  (float (- (* (/ 180.0 Math/PI) (Math/atan2 (double dy) (max 0.001 (double hdist)))))))

(defn raw-request-path3d
  "Requisita rota A* 3D com percepcao real de voxels e portais via ponte in-server."
  [[fx fy fz] [tx ty tz] & [radius]]
  (let [r (or radius 1.2)
        cmd (uformat "PATH|%.1f,%.1f,%.1f|%.1f,%.1f,%.1f|%.1f"
                    (double fx) (double fy) (double fz)
                    (double tx) (double ty) (double tz)
                    (double r))
        raw (transport/send-bridge-cmd! cmd)]
    (when (str/starts-with? (or raw "") "OK|")
      (let [steps-str (subs raw 3)
            parts (str/split steps-str #";")]
        (mapv (fn [p]
                (let [[x y z act] (str/split p #",")]
                  {:x (Double/parseDouble x)
                   :y (Double/parseDouble y)
                   :z (Double/parseDouble z)
                   :action (keyword act)}))
              parts)))))

(defn query-ground-y
  "Pergunta a ponte pelo y em pe da coluna de [x y z] (op GROUND, CA-063).
   Devolve o y como double, ou nil em erro, ERR ou resposta malformada."
  [[x y z]]
  (let [cmd (str "GROUND|" (long (Math/floor (double x)))
                 "|" (long (Math/floor (double y)))
                 "|" (long (Math/floor (double z))))
        raw (try (transport/send-bridge-cmd! cmd) (catch Throwable _ nil))]
    (when (and (string? raw) (str/starts-with? raw "OK|"))
      (try (Double/parseDouble (str/trim (subs raw 3)))
           (catch Throwable _ nil)))))

(defn request-path3d
  "Ponto de entrada unificado para busca de caminhos 3D.
   Delega dinamicamente se craft.agents.backend.fabric/request-path3d tiver sido redefinido (ex: em suites de teste)."
  [from to & [radius]]
  (if-let [v (resolve 'craft.agents.backend.fabric/request-path3d)]
    (let [f (var-get v)]
      (if (and f (not (identical? f request-path3d)))
        (f from to radius)
        (raw-request-path3d from to radius)))
    (raw-request-path3d from to radius)))

(defn generate-walk-steps
  "Gera passos de caminhada detalhados usando A* 3D (request-path3d) com percepção
   em tempo real de voxels e detecção dinâmica de portais (:TRAVERSE_PORTAL),
   evitando colisões com cercas e paredes de alvenaria."
  [from-pos to-pos label & [opts]]
  (let [invariants   (:invariants opts)
        species      (:species opts)
        pull-leash?  (or (:pull-leashed? opts) (some #(= (:invariant %) :leash-attached) invariants))
        radius       (or (:radius opts) 1.5)
        safe-from    (or from-pos to-pos [0.0 65.0 0.0])
        safe-to      (or to-pos from-pos [0.0 65.0 0.0])
        raw-path     (or (try (request-path3d safe-from safe-to radius) (catch Throwable _ nil))
                         (try (request-path3d safe-from safe-to 2.2) (catch Throwable _ nil)))]
    (if (seq raw-path)
      (let [n (count raw-path)]
        (loop [idx 0
               acc []]
          (if (>= idx n)
            acc
            (let [node (nth raw-path idx)
                  act  (:action node)
                  nx   (+ (double (:x node)) 0.5)
                  ny   (double (:y node))
                  nz   (+ (double (:z node)) 0.5)
                  target-pt [nx ny nz]]
              (if (= act :TRAVERSE_PORTAL)
                ;; Percepção de Terreno em Tempo Real: Portal/Gate detectado!
                (let [portal-pos [(:x node) (:y node) (:z node)]
                      prev-node  (when (pos? idx) (nth raw-path (dec idx)))
                      next-node  (when (< (inc idx) n) (nth raw-path (inc idx)))
                      ;; Vetor de avanço do portal
                      pdx (if next-node (- (:x next-node) (:x node)) (if prev-node (- (:x node) (:x prev-node)) 1.0))
                      pdz (if next-node (- (:z next-node) (:z node)) (if prev-node (- (:z node) (:z prev-node)) 0.0))
                      deep-in-pt [(+ nx (* (double pdx) 3.5)) ny (+ nz (* (double pdz) 3.5))]
                      next-pt    (if next-node
                                   [(+ (double (:x next-node)) 0.5) (double (:y next-node)) (+ (double (:z next-node)) 0.5)]
                                   [(+ nx (double pdx)) ny (+ nz (double pdz))])
                      portal-microsteps
                      (if pull-leash?
                        ;; Com animal na guia: abre, entra, avança fundo para puxar mob, espera colisão, volta e tranca
                        [{:op :gate :open? true :pos portal-pos :label "Abrindo portão do cercado"}
                         {:op :wait :ticks 10 :label "Aguardando portão abrir"}
                         (cond-> {:op :walk :target target-pt :label "Atravessando vão do portão"}
                           invariants (assoc :invariants invariants)
                           species (assoc :species species))
                         (cond-> {:op :walk :target deep-in-pt :label "Conduzindo animal para o interior do cercado"}
                           invariants (assoc :invariants invariants)
                           species (assoc :species species))
                         {:op :wait :ticks 25 :label "Aguardando física de colisão do animal"}
                         {:op :detach-lead :species species :label "Soltando o laço dentro do cercado"}
                         {:op :wait :ticks 10 :label "Removendo guia"}
                         {:op :walk :target next-pt :label "Retornando ao portão para trancar"}
                         {:op :gate :open? false :pos portal-pos :label "Trancando portão"}
                         {:op :wait :ticks 8 :label "Conferindo tranca"}]
                        ;; Sem animal: abre, cruza vão, avança e fecha
                        [{:op :gate :open? true :pos portal-pos :label "Abrindo portão"}
                         {:op :wait :ticks 8 :label "Aguardando portão abrir"}
                         {:op :walk :target target-pt :label "Cruzando vão do portão"}
                         {:op :walk :target next-pt :label "Passando pelo portão"}
                         {:op :gate :open? false :pos portal-pos :label "Fechando portão atrás de si"}
                         {:op :wait :ticks 6 :label "Conferindo tranca"}])]
                  (recur (if next-node (+ idx 2) (inc idx))
                         (into acc portal-microsteps)))
                ;; Passo normal de caminhada
                (let [step (cond-> {:op :walk :target target-pt :label label}
                             invariants (assoc :invariants invariants)
                             species (assoc :species species))]
                  (recur (inc idx) (conj acc step))))))))
      ;; Fallback se path solver não encontrou caminho
      [(cond-> {:op :walk :target safe-to :label label}
         invariants (assoc :invariants invariants)
         species (assoc :species species))])))

(defn expand-route-microsteps
  "Converte um item da rota do santuario em micro-etapas atomicas para execucao deterministica a 20Hz,
   utilizando PathSolver3D com voxels reais para evitar travessia fantasma através de paredes."
  ([stop] (expand-route-microsteps stop nil))
  ([stop from-pos]
   (cond
     (= (:type stop) :promenade)
     (let [target-pos (:pos stop)
           walk-steps (if from-pos
                        (generate-walk-steps from-pos target-pos (str "Caminhando -> " (:name stop)) {:radius 0.8})
                        [{:op :walk :target target-pos :label (str "Caminhando -> " (:name stop))}])]
       (vec
        (concat
         [{:op :equip :item (:hold-item stop) :label (:name stop)}]
         walk-steps
         [{:op :BARK :text (:bark stop) :label (:name stop)}
          {:op :wait :ticks (:pause-ticks stop 40) :label (str "Vigiando " (:name stop))}])))

     (= (:type stop) :quarters)
     (let [barks-vec (seq (:barks stop))
           chosen-bark (if barks-vec (rand-nth (vec barks-vec)) (:bark stop))
           bed-pos (:pos stop [-131.0 65.0 195.0])
           ret-pos (or (last (:return-waypoints stop)) [-173.0 65.0 180.0])
           to-bed-steps (if from-pos
                          (generate-walk-steps from-pos bed-pos (str "Indo descansar no " (:name stop)) {:radius 0.8})
                          (let [wps (or (:waypoints stop) [bed-pos])]
                            (mapv (fn [wp] {:op :walk :target wp :label (str "Indo ao " (:name stop))}) wps)))
           ret-steps (if from-pos
                       (generate-walk-steps bed-pos ret-pos "Retornando à ronda de patrulha" {:radius 0.8})
                       (let [ret (or (:return-waypoints stop) [ret-pos])]
                         (mapv (fn [wp] {:op :walk :target wp :label "Retornando à ronda"}) ret)))]
       (vec
        (concat
         [{:op :equip :item (:hold-item stop "minecraft:golden_apple") :label (:name stop)}]
         to-bed-steps
         [{:op :BARK :text chosen-bark :label (:name stop)}
          {:op :wait :ticks (:pause-ticks stop 140) :label (str "Descansando no " (:name stop))}]
         ret-steps)))

     :else
     ;; Enclosure care sequence with guaranteed gate close
     (let [outside-pos (:outside stop)
           approach-steps (if from-pos
                            (generate-walk-steps from-pos outside-pos (str "Indo ao portão -> " (:name stop)) {:radius 0.8})
                            [{:op :walk :target outside-pos :label (str "Indo ao portão -> " (:name stop))}])]
       (vec
        (concat
         [{:op :equip :item (:food stop) :label (:name stop)}]
         approach-steps
         [{:op :gate :open? true :gates (:gates stop) :block (:gate-block stop) :facing (:gate-facing stop)
           :label (str "Abrindo portão -> " (:name stop))}
          {:op :walk :target (:inside stop) :label (str "Entrando -> " (:name stop))}
          {:op :gate :open? false :gates (:gates stop) :block (:gate-block stop) :facing (:gate-facing stop)
           :label (str "Fechando portão interno -> " (:name stop))}
          {:op :feed :pos (:feed-pos stop) :food (:food stop) :species (:species stop)
           :barks (:barks stop) :bark (:bark stop)
           :label (str "Alimentando -> " (:name stop))}
          {:op :wait :ticks 45 :label (str "Cuidando -> " (:name stop))}
          {:op :walk :target (:inside stop) :label (str "Saindo -> " (:name stop))}
          {:op :gate :open? true :gates (:gates stop) :block (:gate-block stop) :facing (:gate-facing stop)
           :label (str "Abrindo portão de saída -> " (:name stop))}
          {:op :walk :target (:outside stop) :label (str "Cruzando saída -> " (:name stop))}
          {:op :gate :open? false :gates (:gates stop) :block (:gate-block stop) :facing (:gate-facing stop)
           :label (str "Trancando cercado -> " (:name stop))}
          {:op :wait :ticks 25 :label (str "Cercado trancado: " (:name stop))}]))))))
