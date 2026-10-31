(ns craft.agents.regions
  "Regioes semanticas, cercados AABB e custos por zona (CA-028, CA-043)."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [craft.agents.schemas :as schemas]))

(defn load-region-file
  "Carrega e valida arquivo EDN de regiao semantica como sanctuary.edn (CA-028, CA-043)."
  [path]
  (let [f (io/file path)]
    (when-not (.exists f)
      (throw (ex-info (str "Arquivo de regiao nao encontrado: " path) {:path path})))
    (let [data (edn/read-string (slurp f))]
      (when-not (schemas/valid-region-profile? data)
        (throw (ex-info "Perfil de regiao semantica invalido pelo schema Malli"
                        {:data data
                         :errors (schemas/explain-validation schemas/RegionProfile data)})))
      data)))

(defn- in-bounds?
  [[x y z] {:keys [min max]}]
  (let [[x1 y1 z1] min
        [x2 y2 z2] max
        min-x (java.lang.Math/min (long x1) (long x2))
        max-x (java.lang.Math/max (long x1) (long x2))
        min-y (java.lang.Math/min (long y1) (long y2))
        max-y (java.lang.Math/max (long y1) (long y2))
        min-z (java.lang.Math/min (long z1) (long z2))
        max-z (java.lang.Math/max (long z1) (long z2))]
    (and (<= min-x x max-x)
         (<= min-y y max-y)
         (<= min-z z max-z))))

(defn enclosure-at
  "Retorna o cercado/zona contendo a coordenada [x y z] ou nil (CA-028)."
  [region-profile [x y z]]
  (some (fn [enc]
          (when (in-bounds? [x y z] (:enclosure/bounds enc))
            enc))
        (:region/enclosures region-profile)))
