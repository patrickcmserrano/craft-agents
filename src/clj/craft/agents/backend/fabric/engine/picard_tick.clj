(ns craft.agents.backend.fabric.engine.picard-tick
  "Tick (20Hz) do Capitao Picard: missao de acompanhamento ou rotina de estacoes
   na Ponte da Enterprise-D."
  (:require [clojure.string :as str]
            [craft.agents.backend.fabric.actions :refer [query-online-players]]
            [craft.agents.backend.fabric.locomotion :as loco]
            [craft.agents.backend.fabric.navigation :refer [compute-yaw]]
            [craft.agents.backend.fabric.picard :refer [picard-stations]]
            [craft.agents.backend.fabric.transport :refer [send-bridge-cmd! uformat]]))

(defn picard-walk-tick
  "Passo puro :walk do Picard rumo a `station` (CA-083): usa loco/walk-step com
   :speed 0.16. Devolve {:pos :yaw :arrived?}."
  [pst station]
  (let [[tx ty tz] (:pos station)]
    (loco/walk-step {:pos (:pos pst)
                     :target [(double tx) (double ty) (double tz)]
                     :speed 0.16
                     :yaw (:yaw pst)})))

(defn picard-follow-decision
  "Decisao pura do Picard em missao de acompanhamento. `target` e o jogador
   {:x :y :z}. Devolve {:action :snap|:approach|:hold :pos :yaw}: :snap (> 28m)
   teleporta sobre o jogador; :approach (> 2.8m) anda ate 0.21 por tick; :hold
   fica parado."
  [[px _ pz] target]
  (let [dx (- (:x target) px)
        dz (- (:z target) pz)
        dist (Math/sqrt (+ (* dx dx) (* dz dz)))
        yaw (compute-yaw dx dz)]
    (cond
      (> dist 28.0)
      {:action :snap :pos [(:x target) (:y target) (:z target)] :yaw yaw}

      (> dist 2.8)
      (let [step-d (min 0.21 dist)]
        {:action :approach
         :pos [(+ px (* (/ dx dist) step-d)) (:y target) (+ pz (* (/ dz dist) step-d))]
         :yaw yaw})

      :else {:action :hold :yaw yaw})))

(defn- tick-follow! [picard-state pst]
  (let [players (query-online-players)
        target-p (first (filter #(= (str/lower-case (:name %))
                                    (str/lower-case (or (:follow-target pst) "pace")))
                                players))]
    (if target-p
      (let [{:keys [action pos yaw]} (picard-follow-decision (:pos pst) target-p)
            [nx ny nz] pos]
        (case action
          :snap
          (do (send-bridge-cmd! (uformat "MOVE|Picard|%.3f|%.3f|%.3f|%.1f|0.0|false|false" nx ny nz yaw))
              (swap! picard-state assoc :pos pos :yaw yaw
                     :status (str "Missão com " (:name target-p))))
          :approach
          (do (send-bridge-cmd! (uformat "MOVE|Picard|%.3f|%.3f|%.3f|%.1f|0.0|false|false" nx ny nz yaw))
              (swap! picard-state assoc :pos pos :yaw yaw
                     :status (str "Acompanhando " (:name target-p))))
          nil))
      (swap! picard-state assoc :mode :bridge-command))))

(defn- tick-station! [picard-state pst]
  (let [st-idx  (:station-idx pst 0)
        station (nth picard-stations st-idx (first picard-stations))
        [tx ty tz] (:pos station)]
    (if (= (:phase pst) :walk)
      (let [{[nx ny nz] :pos yaw :yaw arrived? :arrived?} (picard-walk-tick pst station)]
        (if arrived?
          (do
            (send-bridge-cmd!
             (uformat "MOVE|Picard|%.3f|%.3f|%.3f|%.1f|%.1f|false|false"
                      (double tx) (double ty) (double tz)
                      (double (:yaw station 0.0)) (double (:pitch station 0.0))))
            ;; Picard em serviço silencioso na ponte (fala apenas quando consultado no chat pelo jogador)
            (swap! picard-state assoc
                   :pos [(double tx) (double ty) (double tz)]
                   :yaw (:yaw station 0.0)
                   :pitch (:pitch station 0.0)
                   :phase :wait
                   :wait-left (:wait-ticks station 120)
                   :status (str "Na estação: " (:name station))))
          (do
            (send-bridge-cmd! (uformat "MOVE|Picard|%.3f|%.3f|%.3f|%.1f|0.0|false|false" nx ny nz yaw))
            (swap! picard-state assoc :pos [nx ny nz] :yaw yaw
                   :status (str "Ponte -> " (:name station))))))
      ;; Fase :wait na estação da ponte
      (let [rem (dec (:wait-left pst 0))]
        (if (<= rem 0)
          (swap! picard-state assoc
                 :station-idx (mod (inc st-idx) (count picard-stations))
                 :phase :walk)
          (swap! picard-state assoc :wait-left rem))))))

(defn tick-picard!
  "Um tick do Capitao Picard. `env` precisa de :picard-state (atom)."
  [{:keys [picard-state]}]
  (let [pst @picard-state]
    (if (= (:mode pst) :follow)
      (tick-follow! picard-state pst)
      (tick-station! picard-state pst))))
