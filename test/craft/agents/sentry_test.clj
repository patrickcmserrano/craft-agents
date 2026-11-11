(ns craft.agents.sentry-test
  "Testes da deduplicacao de chat do sentry.

   O chat chega por MAIS DE UMA rota (hook Fabric via CHAT_POLL, tailer de
   disco, CHAT_INJECT). A deduplicacao so pode ser honesta se souber distinguir
   DOIS casos que hoje se confundem:

     1. A MESMA mensagem vista por duas rotas  -> UM evento  -> 1 disparo.
     2. A MESMA mensagem escrita duas vezes     -> DOIS eventos -> N disparos.

   Um dedup por CONTEUDO (speaker:msg + janela temporal) nao consegue: colapsa
   1 e 2 no mesmo caso. Por isso a identidade passa a ser o EVENT-ID que nasce
   na fonte da verdade (o tick do servidor), nao o texto."
  (:require [clojure.test :refer [deftest is testing]]
            [craft.agents.backend.fabric.sentry :as sentry]))

(defn- fresh-dedup!
  "Zera o atom global de deduplicacao para isolar a medicao."
  []
  (let [a (var-get (resolve 'craft.agents.backend.fabric.sentry/global-seen-chat))]
    (reset! a {})
    a))

;; ---------------------------------------------------------------------------
;; RED: identidade de evento. Estes testes FALHAM contra o dedup por conteudo,
;; que nao conhece :event-id. Sao o contrato que a implementacao tem de passar.
;; ---------------------------------------------------------------------------

(deftest test-mesmo-evento-em-duas-rotas-dispara-uma-vez
  (testing "O MESMO evento-id vindo de duas rotas e duplicata (1 disparo)"
    (fresh-dedup!)
    (let [evento {:speaker "pace" :text "fireball" :event-id 12345}]
      (is (false? (sentry/chat-duplicate? evento))
          "A primeira rota ve o evento — nao e duplicata")
      (is (true? (sentry/chat-duplicate? evento))
          "A segunda rota ve o MESMO event-id — e duplicata"))))

(deftest test-mesma-mensagem-em-eventos-distintos-dispara-sempre
  (testing "Textos iguais com event-id DIFERENTES sao eventos distintos (N disparos)"
    (fresh-dedup!)
    (is (false? (sentry/chat-duplicate? {:speaker "pace" :text "fireball" :event-id 100}))
        "Primeiro fireball do jogador")
    (is (false? (sentry/chat-duplicate? {:speaker "pace" :text "fireball" :event-id 101}))
        "Ele escreveu fireball DE NOVO — outro evento, tem de conjurar outra vez")
    (is (false? (sentry/chat-duplicate? {:speaker "pace" :text "fireball" :event-id 102}))
        "E de novo — nada de engolir em silencio")))

(deftest test-event-id-nao-cola-entre-speakers
  (testing "O event-id e por evento: dois speakers nunca partilham o mesmo id"
    (fresh-dedup!)
    (is (false? (sentry/chat-duplicate? {:speaker "pace"   :text "fireball" :event-id 500})))
    (is (false? (sentry/chat-duplicate? {:speaker "lizzie" :text "fireball" :event-id 501})))))

;; ---------------------------------------------------------------------------
;; Ainda o fallback por conteudo: quando a rota nao sabe o event-id (ex.: o
;; tailer de disco), a mensagem chega sem identidade e o comportamento antigo
;; por conteudo e o ultimo recurso — nao pode regredir.
;; ---------------------------------------------------------------------------

(deftest test-sem-event-id-cai-no-dedup-por-conteudo
  (testing "Uma rota sem identidade ainda deduplica por conteudo dentro da janela"
    (fresh-dedup!)
    (is (false? (sentry/chat-duplicate? {:speaker "pace" :text "fireball"}))
        "Primeira chegada sem id")
    (is (true? (sentry/chat-duplicate? {:speaker "pace" :text "fireball"}))
        "Segunda chegada sem id, dentro da janela, e duplicata")))

(deftest test-different-speakers-do-not-collide-sem-id
  (testing "A chave de conteudo inclui o speaker"
    (fresh-dedup!)
    (is (false? (sentry/chat-duplicate? {:speaker "pace"   :text "fireball"})))
    (is (false? (sentry/chat-duplicate? {:speaker "lizzie" :text "fireball"})))))
