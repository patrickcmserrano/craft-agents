(ns craft.agents.npc-persona-structural-test
  "CA-112 (SUG-11 parte 2) — guardas automaticos de persona:
   1. duas personas distintas (`:coco` vs `:richeh`, mais duas sinteticas) produzem falas, destinos
      (`:agent/places`) e acoes distintos pelo MESMO codigo, so por causa do `.edn`;
   2. varredura estrutural: nenhum namespace de nucleo referencia `:coco`, `:richeh`, \"Coco\" ou
      \"Richeh\" (fora de `legacy-facade-allowlist`, fachadas legadas anotadas)."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]
            [craft.agents.backend.fabric.goals.bt-actions :as bta]
            [craft.agents.backend.fabric.goals.dispatch :as dispatch]
            [craft.agents.backend.fabric.transport :as transport]
            [craft.agents.npc.body :as body]
            [craft.agents.npc.registry :as registry]))

;; ----------------------------------------------------------------------------
;; 1. Distincao de persona
;; ----------------------------------------------------------------------------

(def ^:private alpha
  {:agent/id :alpha :agent/name "Alpha"
   :agent/lines {:greeting ["Oi, Beta!"]}
   :agent/relations {:beta {:kind :companion :affinity 0.9}}
   :agent/places {:lab {:name "Lab" :pos [10.0 65.0 0.0]}}
   :agent/routine [{:from 6 :to 18 :activity :study :place :lab}]})

(def ^:private beta
  {:agent/id :beta :agent/name "Beta"
   :agent/lines {:greeting ["Salve, Alpha."]}
   :agent/relations {:alpha {:kind :friend :affinity 0.3}}
   :agent/places {:gate {:name "Portao" :pos [0.0 65.0 -20.0]}}
   :agent/routine [{:from 6 :to 18 :activity :patrol :place :gate}]})

(defn- run
  "Executa a acao `id` como `agent` com ponte e fala stubadas."
  [agent id params agents]
  (let [cmds (atom []) said (atom [])
        ectx (atom {:world-time 0 :agents agents})
        ctx  {:agent agent :state (atom {:pos [0.0 65.0 0.0]}) :engine-ctx ectx :speaker "Pace"}]
    (body/reset-buffer!)
    (body/flush!)
    (let [res (with-redefs [transport/send-bridge-cmd! (fn [c] (swap! cmds conj c) "OK")
                            dispatch/say! (fn [_ msg] (swap! said conj msg))]
                (bta/bt-action id params ctx))]
      {:res res :cmds @cmds :said @said})))

(defn- persona-outputs [agent]
  (let [near   {:pos [1.0 65.0 0.0]}
        others {:alpha near :beta near :coco near :richeh near}
        slot   (run agent :follow-routine-slot {} others)
        greet  (run agent :greet-companion {} others)]
    {:activity (:activity (:res slot))
     :move     (first (:cmds slot))
     :said     (:said greet)
     :target   (:target (:res greet))}))

(deftest synthetic-personas-diverge-with-same-code-test
  (let [a (persona-outputs alpha) b (persona-outputs beta)]
    (is (not= (:activity a) (:activity b)))
    (is (not= (:move a) (:move b)) "destinos diferentes (:places)")
    (is (not= (:said a) (:said b)) "falas diferentes (:lines)")
    (is (not= (:target a) (:target b)))))

(deftest coco-and-richeh-diverge-with-same-code-test
  (let [coco (registry/agent-by-id :coco) richeh (registry/agent-by-id :richeh)
        c (persona-outputs coco) r (persona-outputs richeh)]
    (is (some? coco))
    (is (some? richeh))
    (is (not= (:activity c) (:activity r)) "acoes escolhidas diferem pelo EDN")
    (is (not= (:move c) (:move r)) "destinos diferem pelo EDN")
    (is (not= (:said c) (:said r)) "falas diferem pelo EDN")
    (is (not= (:agent/places coco) (:agent/places richeh)))
    (is (not= (:agent/lines coco) (:agent/lines richeh)))))

;; ----------------------------------------------------------------------------
;; 2. Anti-hardcoding
;; ----------------------------------------------------------------------------

(def ^:private legacy-facade-allowlist
  "Arquivo -> motivo. Fachadas legadas Karlach/Picard documentadas; vazio por padrao."
  {})

(def ^:private fabric "src/clj/craft/agents/backend/fabric")

(defn- clj-files-in [dir pred]
  (->> (.listFiles (io/file dir))
       (filter #(and (.isFile ^java.io.File %)
                     (str/ends-with? (.getName ^java.io.File %) ".clj")
                     (pred (.getName ^java.io.File %))))
       (map #(.getPath ^java.io.File %))
       sort))

(defn- core-files []
  (concat (clj-files-in "src/clj/craft/agents/npc" any?)
          (clj-files-in "src/clj/craft/agents/bt" any?)
          (clj-files-in (str fabric "/goals") #(str/starts-with? % "bt"))
          [(str fabric "/engine/bt_tick.clj")
           (str fabric "/engine/atelier_tick.clj")
           (str fabric "/atelier/behavior.clj")]))

(def ^:private persona-re #"(?i):(coco|richeh)(?![\w\-])|\"(coco|richeh)\"")

(defn- violations [path]
  (->> (str/split-lines (slurp path))
       (map-indexed (fn [i line] [(inc i) (str/replace line #"\s*;.*$" "")]))
       (filter (fn [[_ l]] (re-find persona-re l)))
       (map (fn [[n l]] (str path ":" n " " (str/trim l))))))

(deftest core-namespaces-have-no-hardcoded-persona-test
  (let [files (remove legacy-facade-allowlist (core-files))
        found (vec (mapcat violations files))]
    (is (every? #(.exists (io/file %)) (core-files)) "todos os arquivos varridos existem")
    (is (> (count (core-files)) 8))
    (testing "nenhuma referencia a :coco/:richeh/\"Coco\"/\"Richeh\" fora da allowlist legada"
      (is (empty? found) (str "persona hardcoded: " found)))))
