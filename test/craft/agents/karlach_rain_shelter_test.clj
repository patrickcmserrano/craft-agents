(ns craft.agents.karlach-rain-shelter-test
  "CA-060: Comportamento autonomo de abrigo contra chuva da Karlach.
   Enquanto chove ela nao patrulha o santuario: interrompe a rota e recolhe-se
   ao Quarto da Karlach ate o tempo abrir."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [craft.agents.backend.fabric :as fabric]
            [craft.agents.backend.fabric.picard :as picard]
            [craft.agents.backend.fabric.transport :as transport]))

(deftest ca-060a-rain-gate-interrupts-patrol-test
  (testing "CA-060a: Com chuva, a patrulha e interrompida e o destino passa a ser o quarto"
    (let [decision (fabric/rain-shelter-decision
                    {:raining? true :mode :sanctuary-patrol :sheltering? false})]
      (is (= :shelter (:action decision))
          "Chovendo e patrulhando -> aborta a patrulha e vai para o quarto")
      (is (true? (:speak? decision)) "Deve avisar em voz alta que esta se abrigando")))

  (testing "CA-060b: Sem chuva e sem abrigo, nada acontece (a patrulha segue)"
    (let [decision (fabric/rain-shelter-decision
                    {:raining? false :mode :sanctuary-patrol :sheltering? false})]
      (is (= :none (:action decision))
          "Ceu limpo nao interfere na rotina normal")))

  (testing "CA-060c: Chovendo, mas ja abrigada, ela permanece no quarto (nao re-emite rota)"
    (let [decision (fabric/rain-shelter-decision
                    {:raining? true :mode :sanctuary-patrol :sheltering? true})]
      (is (= :wait (:action decision))
          "Enquanto chove e ja esta no quarto, apenas aguarda a chuva passar")))

  (testing "CA-060d: Ao parar a chuva, ela conclui o descanso e retoma a patrulha"
    (let [decision (fabric/rain-shelter-decision
                    {:raining? false :mode :sanctuary-patrol :sheltering? true})]
      (is (= :resume (:action decision))
          "Chuva cessou com abrigo ativo -> sai do quarto e volta a ronda")
      (is (true? (:speak? decision)) "Deve avisar que esta voltando a patrulhar")))

  (testing "CA-060e: Fail-safe — ponte caida (raining? ausente) nunca trava a Karlach no quarto"
    (let [decision (fabric/rain-shelter-decision
                    {:mode :sanctuary-patrol :sheltering? false})]
      (is (= :none (:action decision))
          "Sem leitura de clima, assume ceu limpo e mantem a patrulha (nao se abriga por engano)"))

    (let [decision (fabric/rain-shelter-decision
                    {:raining? nil :mode :sanctuary-patrol :sheltering? true})]
      (is (= :resume (:action decision))
          "Leitura ausente tambem libera quem ja estava abrigado"))))

(deftest ca-060h-thunder-counts-as-rain-test
  (testing "CA-060h: Trovoada tambem obriga a Karlach a se abrigar"
    (let [decision (fabric/rain-shelter-decision
                    {:raining? false :thundering? true :mode :sanctuary-patrol :sheltering? false})]
      (is (= :shelter (:action decision))
          "Thundering (mesmo sem raining) recolhe a Karlach ao quarto")))

  (testing "CA-060i: Trovoada mantem quem ja esta abrigada no quarto"
    (let [decision (fabric/rain-shelter-decision
                    {:raining? false :thundering? true :mode :sanctuary-patrol :sheltering? true})]
      (is (= :wait (:action decision))
          "Nao libera a Karlach enquanto trovejar")))

  (testing "CA-060j: Sem chuva E sem trovao, o abrigo e liberado"
    (let [decision (fabric/rain-shelter-decision
                    {:raining? false :thundering? false :mode :sanctuary-patrol :sheltering? true})]
      (is (= :resume (:action decision))))))

(deftest ca-060k-weather-transition-edge-test
  (testing "CA-060k: O aviso de chuva do Picard dispara na TRANSICAO, nao a cada tick"
    (is (= :started-raining
           (fabric/weather-transition {:raining? false :thundering? false}
                                      {:raining? true :thundering? false}))
        "Ceu limpo -> chuva: anuncia uma vez")
    (is (= :none
           (fabric/weather-transition {:raining? true :thundering? false}
                                      {:raining? true :thundering? false}))
        "Continuando a chover: sem repetir o aviso")
    (is (= :stopped-raining
           (fabric/weather-transition {:raining? true :thundering? false}
                                      {:raining? false :thundering? false}))
        "Chuva -> limpo: avisa o fim")
    (is (= :none
           (fabric/weather-transition {:raining? false :thundering? false}
                                      {:raining? false :thundering? false}))
        "Estavel limpo: nada a dizer"))

  (testing "CA-060l: Passar de chuva para trovao continua contando como 'chovendo' (sem flap)"
    (is (= :none
           (fabric/weather-transition {:raining? true :thundering? false}
                                      {:raining? true :thundering? true}))
        "Ainda molhado: nao re-anuncia nem diz que parou")
    (is (= :none
           (fabric/weather-transition {:raining? false :thundering? true}
                                      {:raining? true :thundering? false}))
        "Trovao -> chuva: ainda molhado, sem flap")))

(deftest ca-060m-picard-rain-alert-is-global-test
  (testing "CA-060m: O aviso de chuva do Picard alcanca TODOS os jogadores (sem filtro de distancia)"
    (let [sent (atom [])]
      (with-redefs [transport/send-bridge-cmd!
                    (fn [c] (swap! sent conj c))]
        (picard/picard-broadcast-rain-alert!)
        (let [tellraw (first (filter #(str/includes? % "tellraw") @sent))]
          (is (some? tellraw) "Deve emitir um tellraw")
          (is (str/includes? tellraw "tellraw @a")
              "O alvo tem de ser @a sem seletor de distancia")
          (is (not (str/includes? tellraw "distance="))
              "Nenhum filtro de distancia — a mensagem precisa ser global"))))))

(deftest ca-060p-shelter-sits-on-bench-test
  (testing "CA-060p: Chegando ao quarto, ela SENTA no banco"
    (let [sent (atom [])]
      (with-redefs [transport/send-bridge-cmd! (fn [c] (swap! sent conj c))]
        (fabric/sit-karlach-at-quarters! [-131.0 65.0 198.0])
        (let [sit (first (filter #(str/starts-with? % "SIT|") @sent))]
          (is (some? sit) "Deve emitir um op SIT pela ponte")
          (is (= "SIT|Karlach|-131|65|198" sit)
              "O SIT leva as coordenadas do banco declarado no EDN")))))

  (testing "CA-060q: Sem cadeira declarada, o assento degrada com seguranca"
    (let [sent (atom [])]
      (with-redefs [transport/send-bridge-cmd! (fn [c] (swap! sent conj c))]
        (fabric/sit-karlach-at-quarters! nil)
        (is (empty? (filter #(str/starts-with? % "SIT|") @sent))
            "Sem cadeira configurada, nao emite SIT (nao explode, nao senta no ar)")))))

(deftest ca-060r-shelter-stands-on-exit-test
  (testing "CA-060r: Ao retomar a patrulha, ela LEVANTA do banco"
    (let [sent (atom [])]
      (with-redefs [transport/send-bridge-cmd! (fn [c] (swap! sent conj c))]
        (fabric/stand-karlach!)
        (is (some #(= % "STAND|Karlach") @sent)
            "Deve emitir STAND|Karlach antes de sair")))))

(deftest ca-060s-seat-comes-from-edeclared-quarters-test
  (testing "CA-060s: O banco do quarto vem do EDN (karlach.edn :agent/quarters :seat-pos)"
    (let [route    (fabric/build-sanctuary-route)
          quarters (some #(when (= (:type %) :quarters) %) route)]
      (is (= [-131.0 65.0 198.0] (:seat-pos quarters))
          "O banco declarado e o another_furniture:acacia_bench medido em [-131,65,198]"))))

(deftest ca-061-patrol-never-visits-quarters-test
  (testing "CA-061a: A patrulha NAO passa pelo Quarto da Karlach"
    (let [patrol fabric/patrol-route]
      (is (seq patrol) "A rota de patrulha nao pode ficar vazia")
      (is (not-any? #(= (:type %) :quarters) patrol)
          "Nenhuma parada :quarters no ciclo de patrulha — ela nao visita o quarto a toa")))

  (testing "CA-061b: O Quarto continua no catalogo (o abrigo precisa dele)"
    (let [route (fabric/build-sanctuary-route)
          q (some #(when (= (:type %) :quarters) %) route)]
      (is (some? q) "O quarto segue na rota completa, com :pos e :seat-pos para o abrigo/descanso")
      (is (= [-131.0 65.0 198.0] (:seat-pos q)))))

  (testing "CA-061c: A patrulha e a rota completa menos o quarto"
    (let [full   (fabric/build-sanctuary-route)
          patrol (fabric/build-patrol-route full)]
      (is (= (dec (count full)) (count patrol))
          "So o quarto sai da rota de patrulha")
      (is (= (vec (remove #(= (:type %) :quarters) full)) patrol)))))

(deftest ca-060f-shelter-goal-and-quarters-wiring-test
  (testing "CA-060f: A decisao de abrigo leva ao Quarto da Karlach da rota real"
    (let [route    (fabric/build-sanctuary-route)
          quarters (some #(when (= (:type %) :quarters) %) route)
          steps    (fabric/shelter-route-steps quarters [-175.0 65.0 180.0])]
      (is (some? quarters) "A rota do santuario deve conter o quarto")
      (is (= [-131.0 65.0 195.0] (:pos quarters)))
      (is (vector? steps))
      (is (seq steps) "Deve haver micro-etapas de caminhada ate o quarto")
      (is (some #(= (:op %) :walk) steps) "O trajeto ate o quarto caminha de fato")))

  (testing "CA-060n: O trajeto de abrigo PARА no descanso — nao inclui a volta a ronda"
    ;; O expand-route-microsteps de :quarters embute ida + descanso + retorno ao
    ;; santuario. Para o abrigo queremos SO a ida: parar no descanso e ficar no quarto.
    ;; Atencao: a ida ja contem :wait de portao/tranca — o corte e no DESCANSO.
    (let [route    (fabric/build-sanctuary-route)
          quarters (some #(when (= (:type %) :quarters) %) route)
          steps    (fabric/shelter-route-steps quarters [-179.5 65.0 171.5])
          ops      (map :op steps)
          labels   (map :label steps)]
      (is (some #(= % :walk) ops)
          "Tem de haver caminhada — o trajeto nao pode ser so equip/portao/descanso")
      (is (some (fn [l] (and l (str/includes? (str l) "Descansando"))) labels)
          "O descanso tem de estar presente — e onde ela fica no quarto")
      (is (not-any? (fn [l] (and l (str/includes? (str l) "Retornando"))) labels)
          "Nenhuma micro-etapa de retorno a ronda — senao ela volta e trava no santuario")
      (is (str/includes? (str (:label (last steps))) "Descansando")
          "O trajeto tem de TERMINAR no descanso")))

  (testing "CA-060o: A ida inclui os portoes do trajeto (nao corta no primeiro :wait)"
    (let [route    (fabric/build-sanctuary-route)
          quarters (some #(when (= (:type %) :quarters) %) route)
          steps    (fabric/shelter-route-steps quarters [-179.5 65.0 171.5])
          n-waits  (count (filter #(= (:op %) :wait) steps))]
      (is (> n-waits 1)
          "A ida tem varios :wait (portao, tranca, descanso) — cortar no 1o perderia a caminhada")))

  (testing "CA-060g: Sem parada de quarto configurada, o abrigo degrada com seguranca"
    (is (nil? (fabric/shelter-route-steps nil [-175.0 65.0 180.0]))
        "Ausencia do quarto devolve nil em vez de explodir")))
