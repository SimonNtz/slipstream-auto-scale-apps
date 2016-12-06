;; SlipStream autoscaling.
; Using core.async due to synchrnonous model of event processing in Riemann.
; http://riemann.io/howto.html#client-backpressure-latency-and-queues
(require '[clojure.core.async :refer [go timeout chan sliding-buffer <! >! alts!]])
(require '[sixsq.slipstream.runproxy.api :as ssrp])
(require '[clojure.set :as cs])
(require '[clojure.tools.logging :as log])

(require 'riemann.common)

(ssrp/set-ss-proxy "http://localhost:8008")

;;
;; Application elasticity constraints.
;;

(def constraint-template-required
  {:comp-name         "change-comp-name"
   :service-tags      []
   :service-metric    "change-service-metric"
   :metric-thold-up   0.0
   :metric-thold-down 0.0
   :vms-max           1})

(def constraint-template
  (merge constraint-template-required
         {:vms-min       1
          :scale-up-by   1
          :scale-down-by 1}))

(def required-constraint-keys
  (-> constraint-template-required
      keys
      set))

(def ^:dynamic *elasticity-constaints* [])

(defn constaint-valid?
  [c]
  (cs/subset? required-constraint-keys (set (keys c))))

(defn- assoc-sm-re
  [cs]
  (assoc cs
    :service-metric-re (re-pattern (str "^" (:service-metric cs)))))

(defn- throw-on-invalid-constraint
  [c]
  (@#'clojure.core/throw-if (not (constaint-valid? c))
    (format "Invalid constraint provided: %s" c)))

(defn- append-constraint
  [current new]
  (merge current (->> new
                      (merge constraint-template)
                      (assoc-sm-re))))

(defn- set-constraint
  [current-set new-constraint]
  (if (not (empty? new-constraint))
    (do
      (throw-on-invalid-constraint new-constraint)
      (append-constraint current-set new-constraint))
    (log/warn "Empty constraint provided.")))

(defn log-elasticity-constraints
  []
  (log/info "Elasiticity constraints:" *elasticity-constaints*))

(defn set-elasticity-constaints
  "The required keys are in required-constraint-keys.  The full set is
  a unity of required-constraint-keys and constraints-defaults."
  [cs]
  (doseq [c cs]
    (log/info "Setting elasticity constraint:" c)
    (alter-var-root #'*elasticity-constaints* set-constraint c))
  (log-elasticity-constraints))

(def service-tags
  (apply concat (map #(:service-tags %) *elasticity-constaints*)))

;;
;; Scaler logic.
;;

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
  (log/warn "Timed out waiting scaler to return:" (str-action action comp-name n) ". Elapsed:" elapsed))
(defn log-scaling-failure
  [action comp-name n elapsed scale-res]
  (log/error "Scaling failed: " (str-action action comp-name n) ". Result:" scale-res ". Elapsed:" elapsed))
(defn log-scaling-success
  [action comp-name n elapsed scale-res]
  (log/info "Scaling success:" (str-action action comp-name n) ". Result:" scale-res ". Elapsed:" elapsed))
(defn log-exception-scaling
  [action comp-name n e]
  (log/error "Exception when scaling:" (str-action action comp-name n) ". " (.getMessage e)))
(defn log-will-execute-scale
  [action comp-name n]
  (log/info "Will execute scale request:" (str-action action comp-name n)))
(defn log-place-scale-request
  [action comp-name n]
  (log/info "Placing scale request:" (str-action action comp-name n)))
(defn log-scaler-busy
  [action comp-name n]
  (log/warn "Scaler busy. Rejected scale request:" (str-action action comp-name n)))
(defn log-skip-scale-request
  []
  (log/warn "Scale request is not attempted."
            "Run is not in scalable state."
            "Request is not taken from the queue."))

;;
;; Async action execution.
;;

(def busy? (atom false))
(defn busy! [] (swap! busy? (constantly true)))
(defn free! [] (swap! busy? (constantly false)))

(defn scale!
  [action comp-name n]
  (let [ch (chan 1) start-ts (System/currentTimeMillis)]
    (go
      (let [[scale-res _] (alts! [ch (timeout timeout-scale-scaler-release)])
            elapsed (ms-to-sec (- (System/currentTimeMillis) start-ts))]
        (free!)
        (cond
          (nil? scale-res) (log-scaler-timout action comp-name n elapsed)
          (ssrp/scale-failure? scale-res) (log-scaling-failure action comp-name n elapsed scale-res)
          :else (log-scaling-success action comp-name n elapsed scale-res))))
    (ssrp/scale-action ch action comp-name n timeout-scale)))

(defn scalers
  [chan]
  (let [msg (str "Starting " number-of-scalers " scale request processor(s).")]
    (log/info msg)
    (log/warn msg)
    (log/error msg))
  (doseq [_ (range number-of-scalers)]
    (go
      (while true
        (if (ssrp/can-scale?)
          (let [[[action comp-name n] _] (alts! [chan (timeout timeout-processing-loop)])]
            (when (not-nil? action)
              (try
                (log-will-execute-scale action comp-name n)
                (scale! action comp-name n)
                (catch Exception e (log-exception-scaling action comp-name n e)))))
          (log-skip-scale-request))
        (log/info "Sleeping in scale request processor loop for 5 sec.")
        (sleep 5)))))

(defonce ^:dynamic *scalers-executor* (scalers scale-chan))

;;
;; Action helpers.
;;

(def scale-actions #{:up :down})

(defn- scale-by-kw
  [action]
  (keyword (format "scale-%s-by" (name action))))

(defn put-scale-request
  [action comp & _]
  (when (contains? scale-actions action)
    (let [comp-name (:comp-name comp)
          n         ((scale-by-kw action) comp)]
      (cond
        (= false @busy?) (do
                           (log-place-scale-request action comp-name n)
                           (go (>! scale-chan [action comp-name n]))
                           (busy!))
        (= true @busy?) (log-scaler-busy action comp-name n)))))

(defn- get-multiplicity
  [comp]
  ; TODO: look for the multiplicity in the index.  It should be put there by a separate stream.
  ; (riemann.index/lookup (:index @riemann.config/core) comp-name".mult" comp-name"-mult")
  (ssrp/get-multiplicity (:comp-name comp)))

(defn- event-mult
  [mult comp]
  (let [{:keys [comp-name vms-max]} comp]
    (event {
            :service     (str comp-name "-mult")
            :host        (str comp-name ".mult")
            :state       (condp < mult
                           vms-max "critical"
                           (- vms-max 2) "warning"
                           "ok")
            :description (str "Multiplicity of " comp-name " in SS run.")
            :ttl         30
            :metric      mult})))

(defn comp-mult-as-event
  [comp]
  (-> comp
      get-multiplicity
      (event-mult comp)))

(defn scale-up?
  [mean comp]
  (and (>= mean (:metric-thold-up comp))
       (< (get-multiplicity comp) (:vms-max comp))))

(defn scale-down?
  "Predicate checking whether the scale down action should be requested."
  [mean comp]
  (and (< mean (:metric-thold-down comp))
       (> (get-multiplicity comp) (:vms-min comp))))

(defn cond-scale-action
  [metric-val comp]
  (cond
    (scale-up? metric-val comp) :up
    (scale-down? metric-val comp) :down
    :else :none))

(defn cond-scale
  "Given component and service metric value, conditionally scale up/down the component."
  [metric-val comp]
  (-> (cond-scale-action metric-val comp)
      (put-scale-request comp)))

