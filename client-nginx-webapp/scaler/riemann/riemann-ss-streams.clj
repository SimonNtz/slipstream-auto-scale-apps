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

(include "ss-scale-support.clj")

;; Application elasticity constraints.
;; TODO: Read from .edn and reload service to pickup new values.
(set-elasticity-constaints
  {:comp-name         "webapp"
   :service-tags      ["webapp"]
   :service-metric    "avg_response_time"
   :metric-thold-up   7000.0
   :metric-thold-down 4000.0
   :vms-max           4})

(def compnt (first *elasticity-constaints*))

;; Send service metrics to graphite.
(let [index (default :ttl 60 (index))]
  (streams
    (where (tagged service-tags)  ;; service-tags var is defined in ss-scale-support.clj
           to-graphite)))

;; Multiplicity indexing stream.
;; Get multiplicity of the component instances, index it and send to graphite.
(let [index (default :ttl 20 (index))]
  (riemann.time/every! 10 (fn [] (let [e (comp-mult-as-event comp)]
                                   (index e)
                                   (to-graphite e)))))

;; Scaling streams.
(def mtw-sec 30)
(let [index (default :ttl 60 (index))]
  (streams
    index
    (where (and (tagged service-tags) (service (:service-metric-re compnt)))
           (moving-time-window mtw-sec
                               (fn [events]
                                 (let [mean (:metric (riemann.folds/mean events))]
                                   (info "Average over sliding" mtw-sec "sec window:" mean)
                                   (cond-scale mean compnt)))))

    (where (and (= (:node-name event) (:comp-name compnt))
                (service (re-pattern "^load/load/shortterm")))
           (coalesce 5
                     (smap folds/count
                           (with {:host nil :instance-id nil :service (str (:comp-name compnt) "-count")}
                                 index))))

    (expired
      #(info "expired" %))))
