(ns sixsq.slipstream.runproxy.server
  "Simple proxy server to SlipStream run.
  "
  (:require
    [clojure.string :as s]
    [compojure.core :refer :all]
    [compojure.route :as route]
    [clojure.data.json :as json]
    [clojure.tools.logging :as log]

    [ring.middleware.defaults :refer [wrap-defaults site-defaults]]
    [ring.middleware.json :refer [wrap-json-params]]
    [aleph.http :as http]

    [sixsq.slipstream.client.api.run :as ssr]))

; silence kvlt logs.
(require '[taoensso.timbre :as tlog])
(tlog/merge-config! {:ns-blacklist ["kvlt.*"]})

(ssr/contextualize!)

(def status200 {:status 200 :body "success"})
(def status400 {:status 400 :body "Bad request."})

(defn- status-200
  [& [body]]
  (if body
    (assoc status200 :body (str body))
    status200))

(defn- status-400
  [& [body]]
  (if body
    (assoc status400 :body body)
    status400))

(defn- scale-func
  [action]
  (cond
    (= action :up) ssr/action-scale-up
    (= action :down) ssr/action-scale-down-by))

(defn- scale!
  [action request]
  (log/info "Processing scale request:" action (:params request))
  (if-let [comp (-> request :params :comp)]
    (let [comp-name (first (s/split comp #"="))
          n         (try (read-string (second (s/split comp #"="))) (catch Exception e "1"))
          timeout   (try (read-string (-> request :params :timeout)) (catch Exception e "600"))]
      (when (not (and comp-name n))
        status400)
      (if (ssr/can-scale?)
        (let [_ (log/info "Proxying scale request:" action comp-name n timeout)
              res ((scale-func action) comp-name n :timeout timeout)]
          (if (ssr/action-success? res)
            status200
            (status-400 res)))
        (status-400 (format "Can not scale %s the deployment." (name action)))))
    (do
      (let [msg "Bad request. No component to scale provided."]
        (log/error msg)
        (status400 msg)))))

(defn scale-up
  [request]
  (scale! :up request))

(defn scale-down
  [request]
  (scale! :down request))

(defn get-multiplicity
  [request]
  (if-let [comp (:cname request)]
    (do
      (log/info "Getting multiplicity for component:" comp)
      (status-200 (ssr/get-multiplicity comp)))
    status400))

(defn get-multiplicity-mult
  [request]
  (if-let [req-comps (:comps request)]
    (let [comps (s/split req-comps #",")]
      (log/info "Getting multiplicity for components:" comps)
      (status-200 (->> comps
                       (map (fn [c] [c (ssr/get-multiplicity c)]))
                       (into {})
                       json/write-str)))
    status400))

(defn can-scale?
  []
  (log/info "Asked if can scale.")
  (if (ssr/can-scale?)
    (status-200 "true")
    (status-200 "false")))

(defroutes app-routes

           (wrap-json-params
             (POST "/scale-up" request (scale-up request)))

           (wrap-json-params
             (POST "/scale-down" request (scale-down request)))

           (wrap-json-params
             (GET "/multiplicity" {params :params} (get-multiplicity params)))

           (wrap-json-params
             (GET "/can-scale" {} (can-scale?)))

           (route/not-found {:status 404 :body "Not found"}))

(defn wrap-cross-origin [handler]
  (fn [request]
    (handler request)))

(def app
  (-> app-routes
      (wrap-defaults (assoc site-defaults :security (assoc (:security site-defaults) :anti-forgery false)))
      (wrap-cross-origin)
      ))

(defn start
  [port]
  (let [s (http/start-server app {:port port})
        _ (log/info "Started server on" port)]
    (fn [] (.close s))))

(defn stop
  "Stops the application server by calling the function that was
   created when the application server was started."
  [stop-fn]
  (try
    (and stop-fn (stop-fn))
    (catch Exception e (log/error (.getMessage e)))))
