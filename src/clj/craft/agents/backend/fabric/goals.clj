(ns craft.agents.backend.fabric.goals
  "Fachada do despacho de metas cognitivas da Karlach e do interpretador de Behavior Tree.
   CA-085: a logica vive em craft.agents.backend.fabric.goals.*:
     dispatch   : multimethod execute-karlach-goal! e o metodo :default
     rescue     : resgate de animal e procriar (papel da Karlach no Santuario)
     companion  : teleporte, follow, bodyguard, atencao espacial, danca
     work       : mineracao, inventario, drop, patrulha
     furniture  : sentar/levantar e abrigo de chuva
     skills     : ensinar skill e executar skill dinamica
     bt         : interpretador de Behavior Tree (execute-tree-node!)
   Os requires abaixo sao obrigatorios: sem eles os defmethod nao registram."
  (:require [craft.agents.backend.fabric.goals.bt :as bt]
            [craft.agents.backend.fabric.goals.companion]
            [craft.agents.backend.fabric.goals.dispatch :as dispatch]
            [craft.agents.backend.fabric.goals.furniture]
            [craft.agents.backend.fabric.goals.rescue]
            [craft.agents.backend.fabric.goals.skills]
            [craft.agents.backend.fabric.goals.work]))

;; Reexportacoes: a API publica de antes da quebra em sub-namespaces.
(def execute-karlach-goal! dispatch/execute-karlach-goal!)
(def execute-tree-node! bt/execute-tree-node!)
