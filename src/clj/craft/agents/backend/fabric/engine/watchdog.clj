(ns craft.agents.backend.fabric.engine.watchdog
  "Watchdog de progresso (CA-066) aplicado ao follow e a patrulha da Karlach.
   O estado puro vive em craft.agents.backend.fabric.watchdog; aqui ficam os
   efeitos (ponte, nav-state)."
  (:require [craft.agents.backend.fabric.actions :as actions]
            [craft.agents.backend.fabric.navigation :as nav]
            [craft.agents.backend.fabric.transport :refer [send-bridge-cmd! uformat]]
            [craft.agents.backend.fabric.watchdog :as wd]))

(defn teleport-karlach-to-leader!
  "Teleporta a Karlach ao lider (tp + som + particula) e ancora :pos nele."
  [nav-state _leader-name [tx ty tz]]
  (actions/teleport-agent-to! "Karlach" [tx ty tz])
  (send-bridge-cmd! "CMD|execute at Karlach run playsound minecraft:entity.enderman.teleport master @a ~ ~ ~ 1.0 1.0")
  (send-bridge-cmd! "CMD|execute at Karlach run particle minecraft:portal ~ ~1 ~ 0.5 0.8 0.5 0.1 20 normal")
  (swap! nav-state assoc :pos [tx ty tz] :follow-path []))

(defn- watchdog-hdist [[ax _ az] [bx _ bz]]
  (let [dx (- (double ax) (double bx)) dz (- (double az) (double bz))]
    (Math/sqrt (+ (* dx dx) (* dz dz)))))

(defn follow-watchdog-step!
  "Um tick do watchdog do follow. Devolve `st` atualizado (:pos/:follow-path).
   Age so na escalada (uma vez por nivel) para nao replanejar a cada tick."
  [nav-state st leader-name leader-pos tick]
  (let [w0 (:watchdog st)
        w1 (if (= :follow (:kind w0)) w0 (assoc (wd/arm leader-pos tick) :kind :follow))
        w2 (if (> (watchdog-hdist (:goal w1) leader-pos) 3.0) (wd/retarget w1 leader-pos) w1)
        w3 (wd/progress w2 (:pos st) tick)
        v  (wd/verdict w3 tick wd/follow-cfg)]
    (if (or (= v :ok) (= v (:fired w3)))
      (do (swap! nav-state assoc :watchdog (if (= v :ok) (dissoc w3 :fired) w3))
          st)
      (let [w4 (assoc w3 :fired v)
            [cx cy cz] (:pos st)]
        (println (format "[karlach-watchdog] follow %s tick=%d pos=%s lider=%s"
                         (name v) tick (pr-str (:pos st)) (pr-str leader-pos)))
        (case v
          :replan
          (do (swap! nav-state assoc :watchdog w4 :follow-path [])
              (assoc st :follow-path []))

          :unstick
          (let [gy (nav/query-ground-y [cx cy cz])]
            (swap! nav-state assoc :watchdog w4 :follow-path [])
            (if (and gy (> (Math/abs (- (double gy) (double cy))) 1.0e-6))
              (do (send-bridge-cmd!
                   (uformat "MOVE|Karlach|%.3f|%.3f|%.3f|%.1f|0.0|false|false"
                            cx gy cz (double (:yaw st 0.0))))
                  (swap! nav-state assoc :pos [cx gy cz])
                  (assoc st :pos [cx gy cz] :follow-path []))
              (assoc st :follow-path [])))

          :teleport
          (do (teleport-karlach-to-leader! nav-state leader-name leader-pos)
              (swap! nav-state assoc :watchdog (assoc (wd/arm leader-pos tick) :kind :follow))
              (assoc st :pos leader-pos :follow-path [])))))))

(defn patrol-watchdog-fired!
  "Watchdog do passo :walk da patrulha. Arma a cada novo alvo; devolve true (e
   limpa o watchdog) quando o passo estagnou e deve ser descartado."
  [nav-state st cur-step pos tick]
  (let [goal (:target cur-step)
        w0   (:watchdog st)
        w1   (if (and (= :patrol (:kind w0)) (= goal (:goal w0)))
               w0
               (assoc (wd/arm goal tick) :kind :patrol))
        w2   (wd/progress w1 pos tick)
        v    (wd/verdict w2 tick wd/patrol-cfg)]
    (if (#{:replan :unstick} v)
      (do (println (format "[karlach-watchdog] patrulha %s tick=%d pos=%s alvo=%s: descartando passo"
                           (name v) tick (pr-str pos) (pr-str goal)))
          (swap! nav-state assoc :watchdog nil)
          true)
      (do (swap! nav-state assoc :watchdog w2)
          false))))
