#!/usr/bin/env clojure
;;; kit_pace.clj — entrega o kit de itens ao player por /give, pela ponte in-server.
;;;
;;; POR QUE A PONTE E NAO O PLAYERDATA: o servidor esta no ar e o save em memoria
;;; sobrescreve qualquer .dat editado por fora. /give e seguro com ele rodando.
;;;
;;; A ponte (25596/25595) expoe o op `CMD|<comando>`, que executa na server thread
;;; via CommandManager. `send-bridge-cmd!` reconecta sozinho e devolve a resposta
;;; (`OK` / `ERR|...`).
;;;
;;; Origem do conteudo: o inventario real do pace salvo em
;;;   server/world/playerdata/bfb04b46-...-17602935058979343386.dat
;;; (31/out/2026, 30 stacks), limpo dos duplicados (3 elytra -> 1) e dos drops de
;;; mob (spider_eye, rotten_flesh, bone, wheat) e organizado por categoria.
;;;
;;; Uso:
;;;   clojure -M:scripts -m kit-pace                     ; modo seco
;;;   clojure -M:scripts -m kit-pace --apply             ; envia
;;;   clojure -M:scripts -m kit-pace --apply --replace   ; limpa os itens do kit antes
;;;   clojure -M:scripts -m kit-pace --apply --target Fulano
;;;   clojure -Sdeps '{:paths ["src/clj" "scripts" "resources" "target/classes"]}' -M -m kit-pace
(ns kit-pace
  (:require [clojure.string :as str]
            [craft.agents.backend.fabric.transport :as t]))

;; rotulo + id + quantidade. O arquivo e o "backlog" do kit: editar aqui e o
;; unico lugar onde o conteudo muda.
(def kit
  [{:grupo "arma e municao"
    :itens [["Rifle"                 "jeg:assault_rifle"            1]
            ["Municao rifle"         "jeg:rifle_ammo"             128]
            ["Carregador"            "jeg:rifle_magazine"           1]
            ["Carregador estendido"  "jeg:rifle_extended_magazine"  1]
            ["Magazine loader"       "jeg:magazine_loader"          1]]}
   {:grupo "ferramentas"
    :itens [["Picareta de diamante"  "minecraft:diamond_pickaxe"    1]
            ["Machado de diamante"   "minecraft:diamond_axe"        1]
            ["Pa de ferro"           "minecraft:iron_shovel"        1]
            ["Isqueiro"              "minecraft:flint_and_steel"    1]
            ["Balde de agua"         "minecraft:water_bucket"       1]]}
   {:grupo "mobilidade"
    :itens [["Elytra"                "minecraft:elytra"             1]
            ["Foguetes"              "minecraft:firework_rocket"  128]]}
   {:grupo "combate e protecao"
    :itens [["Escudo"                "minecraft:shield"             1]
            ["Peitoral de ferro"     "minecraft:iron_chestplate"    1]
            ["Botas de diamante"     "minecraft:diamond_boots"      1]
            ["Calcas de diamante"    "minecraft:diamond_leggings"   1]]}
   {:grupo "utilidade"
    :itens [["Luneta"                "minecraft:spyglass"           1]
            ["Lanterna"              "minecraft:lantern"            2]
            ["Maca"                  "minecraft:apple"              8]
            ["Corda"                 "minecraft:lead"               1]]}
   {:grupo "materiais"
    :itens [["Deepslate polido"      "minecraft:polished_deepslate"        32]
            ["Laje de deepslate"     "minecraft:polished_deepslate_slab"   16]
            ["Blackstone polido"     "minecraft:polished_blackstone"       32]]}])

(defn- todos-itens [k]
  (mapcat :itens k))

(defn- cmd [target [rotulo id n]]
  {:rotulo rotulo :id id :n n
   :cmd (str "give " target " " id " " n)})

(defn- ids-do-kit
  "IDs distintos do kit. O --replace limpa SÓ estes — o inventário pode ter
   coisas fora do kit e elas não são tocadas."
  [k]
  (->> (todos-itens k) (map second) distinct vec))

(defn- parse-args [args]
  (loop [m {:target "pace" :apply false :replace false} [a & more] args]
    (cond
      (nil? a) m
      (= a "--apply")   (recur (assoc m :apply true) more)
      (= a "--replace") (recur (assoc m :replace true) more)
      (= a "--target")  (recur (assoc m :target (first more)) (rest more))
      :else (recur m more))))

(defn- enviar!
  "Envia uma lista de comandos pela ponte e devolve [enviados falhas]."
  [cs]
  (let [respostas (mapv (fn [c] [(t/send-bridge-cmd! (str "CMD|" c)) c]) cs)
        erros (remove (fn [[r _]] (= "OK" r)) respostas)]
    (doseq [[r c] erros]
      (println (format "  FALHOU %s -> %s" c r)))
    [(- (count cs) (count erros)) (count erros)]))

(defn -main [& args]
  (let [{:keys [target apply replace]} (parse-args args)
        cmds (mapv (partial cmd target) (todos-itens kit))
        contar (count cmds)]
    (doseq [{:keys [grupo itens]} kit]
      (println (str "— " grupo))
      (doseq [[rotulo id n] itens]
        (printf "  %-22s %-32s x%s%n" rotulo id n)))
    (println (format "%n%d itens para '%s'" contar target))

    (if-not apply
      (println "\nmodo seco. rode com --apply para enviar.")
      (do
        (when replace
          (let [limpar (mapv #(str "clear " target " " %) (ids-do-kit kit))]
            (println (format "\nlimpando %d IDs do kit (--replace)..." (count limpar)))
            (let [[ok err] (enviar! limpar)]
              (println (format "  limpos: %d, falhas: %d" ok err)))))
        (println "\nenviando pela ponte...")
        (let [[ok err] (enviar! (mapv :cmd cmds))]
          (println (format "enviados: %d, falhas: %d" ok err))
          (when (pos? err) (System/exit 2)))))))

(comment
  ;; conferir o inventario depois de aplicar:
  ;; (t/send-bridge-cmd! "INSPECT_INVENTORY|pace")
  )
