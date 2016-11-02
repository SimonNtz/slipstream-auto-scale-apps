(ns sixsq.slipstream.runproxy.api
  "This namespace requires a very limited set of dependencies and is inteded to
   be used to talk to SlipStream run via `sixsq.slipstream.runproxy.server`
   simple proxy server.
  "
  (:require
    [clojure.core.async :refer [go >!]]
    [clj-http.client :as http]
    [clojure.tools.logging :as log]))

(def ^:dynamic *ss-proxy* "http://localhost:8008")
(defn set-ss-proxy
  [ss-proxy]
  (alter-var-root #'*ss-proxy* (constantly ss-proxy)))

(defn r-can-scale []
  (str *ss-proxy* "/can-scale"))
(defn r-mult []
  (str *ss-proxy* "/multiplicity"))
(defn r-scale-action
  [action]
  (format "%s/scale-%s" *ss-proxy* (name action)))


(defn scale-failure?
  [scale-res]
  (false? (= "success" (:state scale-res))))

(defn get-multiplicity
  [comp]
  (when comp
    (->> {:query-params {"cname" comp}}
         (http/get (r-mult))
         :body
         read-string)))

(defn can-scale?
  []
  (try
    (-> (r-can-scale)
        http/get
        :body
        read-string
        true?)
    (catch Exception e
      (do
        (log/error "Failed to check if can scale with:" (.getMessage e))
        false))))

(defn- scale-request!
  [action comp-name n timeout]
  (http/post (r-scale-action action)
             {:form-params {:comp    [comp-name n]
                            :timeout timeout}}))

(defn scale-up
  [comp-name n timeout]
  (scale-request! :up comp-name n timeout))

(defn scale-down
  [comp-name n timeout]
  (scale-request! :down comp-name n timeout))

(defn scale-action
  [chan action comp-name n timeout]
  (cond
    (= :up action) (go (>! chan (scale-up comp-name n timeout)))
    (= :down action) (go (>! chan (scale-down comp-name n timeout)))))

