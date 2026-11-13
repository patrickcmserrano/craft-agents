(ns craft.agents.npc.spawn
  "Spawn e loadout de NPCs guiados pelo registro `agents/*.edn` (CA-088).

   `spawn-cmds` e puro: dado o spec de um agente (e o meta da skin) devolve os
   comandos da bridge na ordem SPAWN, gamemode, efeitos (`:agent/effects`,
   opcional) e `item replace` de cada slot de `:agent/loadout`.

   O loadout do spawn e `:agent/spawn-loadout` quando existe (itens de runtime do
   perfil, como a maca de alimentacao da Karlach, ficam em `:agent/loadout`);
   senao `:agent/loadout`.

   Slots do loadout: `:hotbar.N`, `:head :chest :legs :feet` (armor.*),
   `:mainhand` e `:offhand`. Ordem de emissao: hotbar, armadura, maos.

   Agentes com `:agent/boot-spawn? false` (spawn feito em outro lugar, ex.
   Antigravity) ficam fora do spawn de boot."
  (:require [craft.agents.npc.registry :as registry]))

(def ^:private armor-slots
  {:head "armor.head" :chest "armor.chest" :legs "armor.legs" :feet "armor.feet"})

(def ^:private hand-slots
  {:mainhand "weapon.mainhand" :offhand "weapon.offhand"})

(defn- hotbar-index [slot]
  (when-let [[_ n] (re-matches #"hotbar\.(\d+)" (name slot))]
    (Long/parseLong n)))

(defn- slot-rank
  "Chave de ordenacao: hotbar por indice, depois armadura, depois maos."
  [slot]
  (cond
    (hotbar-index slot) [0 (hotbar-index slot)]
    (armor-slots slot) [1 (.indexOf [:head :chest :legs :feet] slot)]
    (hand-slots slot) [2 (.indexOf [:mainhand :offhand] slot)]
    :else [3 0]))

(defn- slot-name [slot]
  (or (when (hotbar-index slot) (name slot))
      (armor-slots slot)
      (hand-slots slot)))

(defn skin-file
  "Nome do arquivo de meta da skin (`<skin>_meta.json`) ou nil se o agente nao tem skin."
  [spec]
  (when-let [s (:agent/skin spec)]
    (str s "_meta.json")))

(defn spawn-cmds
  "Comandos da bridge para colocar o agente no mundo com efeitos e loadout."
  ([spec] (spawn-cmds spec nil))
  ([spec {:keys [value signature]}]
   (let [nm (:agent/name spec)
         [x y z] (get-in spec [:agent/spawn :pos])
         effects (for [{:keys [effect amplifier]} (:agent/effects spec)]
                   (str "CMD|effect give " nm " " effect " infinite " (or amplifier 0) " true"))
         items (for [[slot item] (sort-by (comp slot-rank key) (or (:agent/spawn-loadout spec) (:agent/loadout spec)))
                     :when (slot-name slot)]
                 (str "CMD|item replace entity " nm " " (slot-name slot) " with " item))]
     (vec (concat [(str "SPAWN|" nm "|" x "|" y "|" z "|" (or value "") "|" (or signature ""))
                   (str "CMD|gamemode survival " nm)]
                  effects
                  items)))))

(defn boot-agents
  "Agentes do registro que o spawn de boot cobre."
  []
  (->> (registry/all-agents)
       (remove #(false? (:agent/boot-spawn? %)))))

(defn spawn-agents!
  "Itera o registro: para cada agente envia `spawn-cmds` por `send-fn`.
   `load-skin-fn` recebe o nome do arquivo de meta e devolve {:value :signature}.
   Devolve os nomes dos agentes processados."
  [send-fn load-skin-fn]
  (vec
   (for [spec (boot-agents)
         :let [nm (:agent/name spec)
               skin (some-> (skin-file spec) load-skin-fn)
               cmds (spawn-cmds spec skin)
               resp (send-fn (first cmds))]]
     (do (doseq [c (rest cmds)] (send-fn c))
         (println "[craft-agents] Agente Server-Native conectado:" nm resp)
         nm))))
