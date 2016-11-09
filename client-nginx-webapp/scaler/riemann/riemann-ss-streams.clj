(require 'riemann.common)

(logging/init {:file "/var/log/riemann/riemann.log"})

; Listen on the local interface over TCP (5555), UDP (5555), and websockets
; (5556)
(let [host "0.0.0.0"]
  (tcp-server {:host host})
  (udp-server {:host host})
  (ws-server {:host host}))

(def to-graphite (graphite {:host "127.0.0.1"}))

; Scan indexes for expired events every N seconds.
(periodically-expire 20)

(require '[sixsq.slipstream.riemann.scale :as ss])

;; Application elasticity constraints.
(ss/set-elasticity-constaints "/etc/riemann/scale-constraints.edn")

(def cmp (first ss/*elasticity-constaints*))

;; Send service metrics to graphite.
(let [index (default :ttl 60 (index))]
  (streams
    (where (tagged ss/*service-tags*)
           to-graphite)))

;; Multiplicity indexing stream.
;; Get multiplicity of the component instances, index it and send to graphite.
(let [index (default :ttl 20 (index))]
  (riemann.time/every! 10 (fn [] (let [e (ss/comp-mult-as-event cmp)]
                                   (index e)
                                   (to-graphite e)))))

;; Scaling streams.
(def mtw-sec 30)
(let [index (default :ttl 60 (index))]
  (streams
    index
    (where (and (tagged ss/*service-tags*) (service (:service-metric-re cmp)))
           (moving-time-window mtw-sec
                               (fn [events]
                                 (let [mean (:metric (riemann.folds/mean events))]
                                   (info "Average over sliding" mtw-sec "sec window:" mean)
                                   (ss/cond-scale mean cmp)))))

    (ss/count-components index)

    (expired
      #(info "expired" %))))
