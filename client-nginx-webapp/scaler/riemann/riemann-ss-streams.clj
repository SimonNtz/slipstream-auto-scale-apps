; -*- mode: clojure; -*-
; vim: filetype=clojure


(require 'riemann.common)

;; (logging/init {:console true})
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

;;
;; SlipStream autoscaling section.
; Using core.async due to synchrnonous model of event processing in Riemann.
; http://riemann.io/howto.html#client-backpressure-latency-and-queues
(require '[clojure.core.async :refer [go timeout chan sliding-buffer <! >! alts!]])
(require '[com.sixsq.slipstream.clj-client.run :as ss-r])

;; Application elasticity constraints.
;; TODO: Read from .edn
(def comp-name "webapp")
(def service-tags [comp-name])
(def service-metric-name "avg_response_time")
(def scale-up-by 1)
(def scale-down-by 1)
(def metric-thold-up 7000.0)
(def metric-thold-down 4000.0)
(def vms-min 1)
(def vms-max 4)                                             ; "price" constraint

(def service-metric-re (re-pattern (str "^" service-metric-name)))

;; Send service metrics to graphite.
(let [index (default :ttl 60 (index))]
  (streams
    (where (tagged service-tags)
           to-graphite)))


;; Scaler logic.
(def busy? (atom false))
(defn busy! [] (swap! busy? (constantly true)))
(defn free! [] (swap! busy? (constantly false)))

(defn ms-to-sec
  [ms]
  (/ (float ms) 1000))
(defn sec-to-ms
  [sec]
  (* 1000 sec))
(def number-of-scalers 1)
(def scale-chan (chan (sliding-buffer 1)))
(def timeout-scale 600)
(def timeout-scale-scaler-release (sec-to-ms (+ timeout-scale 2)))
(def timeout-processing-loop (sec-to-ms 600))

(def not-nil? (complement nil?))

(defn sleep
  [sec]
  (Thread/sleep (sec-to-ms sec)))

(defn str-action
  [action comp-name n]
  (let [act (cond
              (= action :down) "-"
              (= action :up) "+"
              :else "?")]
    (format "%s %s%s" comp-name act n)))
(defn log-scaler-timout
  [action comp-name n elapsed]
  (warn "Timed out waiting scaler to return:" (str-action action comp-name n) ". Elapsed:" elapsed))
(defn log-scaling-failure
  [action comp-name n elapsed scale-res]
  (error "Scaling failed: " (str-action action comp-name n) ". Result:" scale-res ". Elapsed:" elapsed))
(defn log-scaling-success
  [action comp-name n elapsed scale-res]
  (info "Scaling success:" (str-action action comp-name n) ". Result:" scale-res ". Elapsed:" elapsed))
(defn log-exception-scaling
  [action comp-name n e]
  (error "Exception when scaling:" (str-action action comp-name n) ". " (.getMessage e)))
(defn log-will-execute-scale
  [action comp-name n]
  (info "Will execute scale request:" (str-action action comp-name n)))
(defn log-place-scale-request
  [action comp-name n]
  (info "Placing scale request:" (str-action action comp-name n)))
(defn log-scaler-busy
  [action comp-name n]
  (warn "Scaler busy. Rejected scale request:" (str-action action comp-name n)))
(defn log-skip-scale-request
  []
  (warn "Scale request is not attempted."
        "Run is not in scalable state."
        "Request is not taken from the queue."))

(defn scale-failure?
  [scale-res]
  (not= (:state scale-res) ss-r/action-success))

(defn can-scale?
  []
  (ss-r/can-scale?))

(defn scale-action
  [chan action comp-name n timeout]
  (cond
    (= :up action) (go (>! chan (ss-r/action-scale-up comp-name n :timeout timeout)))
    (= :down action) (go (>! chan (ss-r/action-scale-down-by comp-name n :timeout timeout)))))

(defn scale!
  [action comp-name n]
  (let [ch (chan 1) start-ts (System/currentTimeMillis)]
    (go
      (let [[scale-res _] (alts! [ch (timeout timeout-scale-scaler-release)])
            elapsed (ms-to-sec (- (System/currentTimeMillis) start-ts))]
        (free!)
        (cond
          (nil? scale-res) (log-scaler-timout action comp-name n elapsed)
          (scale-failure? scale-res) (log-scaling-failure action comp-name n elapsed scale-res)
          :else (log-scaling-success action comp-name n elapsed scale-res))))
    (scale-action ch action comp-name n timeout-scale)))

(defn scalers
  [chan]
  (let [msg (str "Starting " number-of-scalers " scale request processor(s).")]
    (info msg)
    (warn msg)
    (error msg))
  (doseq [_ (range number-of-scalers)]
    (go
      (while true
        (if (can-scale?)
          (let [[[action comp-name n] _] (alts! [chan (timeout timeout-processing-loop)])]
            (when (not-nil? action)
              (try
                (log-will-execute-scale action comp-name n)
                (scale! action comp-name n)
                (catch Exception e (log-exception-scaling action comp-name n e)))))
          (log-skip-scale-request))
        (info "Sleeping in scale request processor loop for 5 sec.")
        (sleep 5)))))

(defonce ^:dynamic *scalers-executor* (scalers scale-chan))

(defn put-scale-request
  [action comp-name n & _]
  (cond
    (= false @busy?) (do
                       (log-place-scale-request action comp-name n)
                       (go (>! scale-chan [action comp-name n]))
                       (busy!))
    (= true @busy?) (log-scaler-busy action comp-name n)))

(defn event-mult
  [mult]
  (event {
          :service     (str comp-name "-mult")
          :host        (str comp-name ".mult")
          :state       (condp < mult
                         vms-max "critical"
                         (- vms-max 2) "warning"
                         "ok")
          :description (str "Multiplicity of " comp-name " in SS run.")
          :ttl         30
          :metric      mult}))

;; Get multiplicity of the component instances, index it and send to graphite.
(let [index (default :ttl 20 (index))]
  (riemann.time/every! 10 (fn [] (let [mult (ss-r/get-multiplicity comp-name)
                                       e    (event-mult mult)]
                                   (index e)
                                   (to-graphite e)))))

;; Scaling streams.
(def mtw-sec 30)
(let [index (default :ttl 60 (index))]
  (streams

    index

    (where (and (tagged service-tags)
                (service service-metric-re))
           (moving-time-window mtw-sec
                               (fn [events]
                                 (let [mean (:metric (riemann.folds/mean events))]
                                   (info "Average over sliding" mtw-sec "sec window:" mean)
                                   (cond
                                     ; TODO: look for the multiplicity in the index
                                     ; (riemann.index/lookup (:index @riemann.config/core) comp-name".mult" comp-name"-mult")
                                     (and (>= mean metric-thold-up) (< (ss-r/get-multiplicity comp-name) vms-max))
                                       (put-scale-request :up comp-name scale-up-by)
                                     (and (< mean metric-thold-down) (> (ss-r/get-multiplicity comp-name) vms-min))
                                       (put-scale-request :down comp-name scale-down-by))))))

    (where (and (= (:node-name event) comp-name)
                (service (re-pattern "^load/load/shortterm")))
           (coalesce 5
                     (smap folds/count
                           (with {:host nil :instance-id nil :service (str comp-name "-count")}
                                 index))))

    (expired
      #(info "expired" %))))
