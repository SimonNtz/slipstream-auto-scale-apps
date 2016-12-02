(require 'riemann.common)

(logging/init {:file "/var/log/riemann/riemann.log"})

; Listen on the local interface over TCP (5555), UDP (5555), and websockets
; (5556)
(let [host "0.0.0.0"]
  (tcp-server {:host host})
  (udp-server {:host host})
  (ws-server {:host host}))

; Scan indexes for expired events every N seconds.
(periodically-expire 20)

(require '[sixsq.slipstream.riemann.scale :as ss])

(ss/with-graphite)

;; Application elasticity constraints.
(ss/set-elasticity-constaints "/etc/riemann/scale-constraints.edn")

(def cmp (first ss/*elasticity-constaints*))

;; Send out tagged service metrics to graphite.
(ss/all-tagged-to-graphite)

;; Multiplicity indexing stream.
(ss/index-comp-multiplicity)

;; Scaling streams.
(def mtw-sec 30)
(let [index (default :ttl 60 (index))]
  (streams
    (where (and (tagged ss/*service-tags*) (service (:service-metric-re cmp)))
           (fn [event]
             (assoc event :state
                          (condp < (:metric_f event)
                            (:metric-thold-up cmp) "critical"
                            (:metric-thold-down cmp) "warning" ;; 75%
                            "ok"))
             (index event)))
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
