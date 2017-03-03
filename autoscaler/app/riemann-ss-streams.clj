(require 'riemann.common)

(logging/init {:file "/var/log/riemann/riemann.log"})

(let [host "0.0.0.0"]
  (tcp-server {:host host})
  (udp-server {:host host})
  (ws-server {:host host}))

; Scan indexes for expired events every N seconds.
(periodically-expire 20)

(require '[sixsq.slipstream.riemann.scale :as ss])

;; Load application elasticity constraints.
(ss/set-elasticity-constaints "/etc/riemann/scale-constraints.edn")

;; Set up Graphite publisher.
(ss/with-graphite)

;; Send out tagged service metrics to graphite.
(ss/all-tagged-to-graphite)

;; Multiplicity indexing stream. (Dashboard.)
(ss/index-comp-multiplicity)

;; Set state for the monitored services. (Dashboard.)
(ss/set-monitored-service-state)

;; Count number of instances per monitored component. (Dashboard.)
(ss/count-components)

(def cmp (first ss/*elasticity-constaints*))

;; Scaling streams.
(def mtw-sec 30)
(let [index (default :ttl 60 (index))]
  (streams

    index

    (where (and (tagged ss/*service-tags*) (service (:service-metric-re cmp)))
           (moving-time-window mtw-sec
                               (fn [events]
                                 (let [mean (:metric (riemann.folds/mean events))]
                                   (info "Average over sliding" mtw-sec "sec window:" (* 1000 mean) "ms")
                                   (ss/cond-scale mean cmp)))))

    (expired
      #(info "expired" %))))
