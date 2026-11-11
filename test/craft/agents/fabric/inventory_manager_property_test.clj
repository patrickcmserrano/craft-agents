(ns craft.agents.fabric.inventory-manager-property-test
  "PROPRIEDADE do op HELD_ITEM (SyntheticInventoryManager.heldItem) — nao o
   parsing Clojure, que ja tem teste.

   O defeito medido em 2026-11-11: o heldItem lia `playerEntity.method_31549()`
   como se devolvesse int, mas em 1.21.1 esse metodo devolve `class_1656`
   (PlayerAbilities). A ClassCastException morria no `catch (Throwable ignored)`,
   `selected` ficava 0, e o op respondia SEMPRE o slot 0 — ignorando a mao real.
   Medido ao vivo: o slot 1 tinha a varinha e o HELD_ITEM respondia air.

   Como o defeito e reflexivo (nomes obfuscados), este teste le o FONTE: e a
   unica forma de prender a invariante sem a JVM do servidor. Ele NAO aprova
   comportamento em runtime; aprova que o op resolve o slot pela PlayerInventory."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(def ^:private src
  (slurp (io/file "src/java/com/craft/agents/fabric/SyntheticInventoryManager.java")))

(def ^:private held-item-fn
  "Recorta o corpo de `heldItem` ate o proximo metodo de topo."
  (let [start (str/index-of src "public static String heldItem(")]
    (when start
      (let [end (str/index-of src "\n    /**" start)]
        (subs src start (if end end (count src)))))))

(deftest test-held-item-resolves-selected-slot-from-player-inventory
  (testing "o corpo do heldItem existe (o recorte nao devolveu vazio)"
    (is (some? held-item-fn) "heldItem encontrado no fonte")
    (is (< 100 (count held-item-fn)) "o recorte tem corpo, nao so o cabecalho"))

  (testing "o stack da mao principal vem do getter oficial da PlayerInventory (method_7391 = getStackInHand)"
    (is (str/includes? held-item-fn "inventory.getClass().getMethod(\"method_7391\")")
        "Le o item segurado pelo getter oficial da PlayerInventory"))

  (testing "o getter ERRADO (PlayerEntity.method_31549 -> PlayerAbilities) nao e usado"
    ;; Em 1.21.1 method_31549 devolve class_1656 (nao int); o cast Number
    ;; estoura dentro de um catch vazio e o slot congela em 0.
    (is (not (str/includes? held-item-fn "playerEntity.getClass().getMethod(\"method_31549\")"))
        "Nao consulta o method_31549 do PlayerEntity para o slot")))
