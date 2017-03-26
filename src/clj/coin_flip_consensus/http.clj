(ns coin-flip-consensus.http
  (:require
   [com.stuartsierra.component :as component]
   [compojure.core :refer [routes GET POST]]
   [compojure.route :as route]
   [hiccup.core :as hiccup]
   [org.httpkit.server :as http-kit]
   [ring.middleware.defaults :as defaults]
   [taoensso.timbre :as timbre]))

(defn landing-pg-handler
  "Template for the index web page.
  FIXME: move this into its own namespace"
  [ring-req]
  (hiccup/html
   [:head
    [:meta {:charset "UTF-8"}]
    [:meta {:name "viewport"
            :content "width=device-width, initial-scale=1"}]
    [:link {:href "css/style.css"
            :rel "stylesheet"
            :type "text/css"}]]
   [:body
    [:h1 "Twitch Plays Coin Flip"]
    [:p "Hey there this is a landing page!"]
    [:div#app]
    [:script {:src "js/compiled/coin_flip_consensus.js"}]]))

(defn ring-routes
  "Takes a client request and routes it to a handler."
  [ring-ajax-get-or-ws-handshake]
  (routes
   (GET  "/"      ring-req (landing-pg-handler            ring-req))
   (GET  "/chsk"  ring-req (ring-ajax-get-or-ws-handshake ring-req))
   (route/resources "/") ; Static files, notably public/main.js (our cljs target)
   (route/not-found "<h1>Route not found, 404 :C</h1>")))

;; FIXME Server is redundant with the namespace
(defrecord Http [port chsk-server stop-fn]
  component/Lifecycle
  (start [this]
    (if-not stop-fn
      (let [chsk-handshake (:ajax-get-or-ws-handshake-fn chsk-server)
            ring-handler (defaults/wrap-defaults (ring-routes chsk-handshake)
                                                      defaults/site-defaults)

            server-map (let [stop-fn (http-kit/run-server ring-handler {:port port})]
                         {:port    (:local-port (meta stop-fn))
                          :stop-fn (fn [] (stop-fn :timeout 100))})]
        (timbre/info {:component 'Http
                      :state :started
                      :uri (format "http://localhost:%s/" port)
                      :port port})

        (assoc this :stop-fn (:stop-fn server-map)))
      this))

  (stop [this]
    (if stop-fn
      (do
        (stop-fn)
        (timbre/info {:component 'Http
                      :state :stopped}))
      this)))

(defn new [port]
  (map->Http {:port port}))
