(ns craft.agents.skills.actions
  "Módulo extensível de ações motoras e comandos do bot para o SWE-Runner e o Sistema 2.
   Permite adição dinâmica de novas capacidades físicas (como veículos, interações com blocos)
   sem necessidade de modificar o adaptador central do Fabric."
  (:require [clojure.string :as str]))

;; =============================================================================
;; Multimethod Extensível de Ações Motoras
;; =============================================================================

(defmulti execute-action
  "Despacha execução de ação motora pelo identificador da ação.
   Recebe [action-id action-args ctx] onde ctx contém {:send-cmd! :karlach-say! :speaker :nav-state ...}."
  (fn [action-id & _] action-id))

(defmethod execute-action :default
  [action-id _action-args _ctx]
  {:status :failure
   :reason :UNKNOWN_ACTION
   :action-id action-id})
