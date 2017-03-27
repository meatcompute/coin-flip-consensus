(ns coin-flip-consensus.server
  "A web and backend server for a conensus-driven multiplayer coin flip game."
  (:require ; [coin-flip-consensus.event :as event]
   [coin-flip-consensus.http :as http]
   [com.stuartsierra.component :as component]
   [clojure.core.async
    :as async
    :refer [<! <!! >! >!! put! chan go go-loop close!]]
   [taoensso.timbre :as timbre]
   [taoensso.timbre.appenders.3rd-party.rotor :as rotor]
   [taoensso.sente.server-adapters.http-kit :refer [sente-web-server-adapter]]
   [taoensso.sente :as sente]))

;; Set the global logging behavior for timbre
(timbre/set-config! {:level :info
                     :appenders {:rotor (rotor/rotor-appender {:max-size (* 1024 1024)
                                                               :backlog 10
                                                               :path "./coin-flip-consensus.log"})}})
(defn user-id-fn
  "Each client provides a UUID on connect. We get it from the request and call it the uid on our end."
  [ring-req]
  (:client-id ring-req))

(def db (atom {:clients []
               :log []
               :term 0}))

;; Event handlers
(defmulti -event-msg-handler :id)

(defn event-msg-handler
  "Wraps `-event-msg-handler` with logging."
  [{:as ev-msg :keys [event ring-req client-id]}]
  (let [session (:session ring-req)]
    (timbre/info {:uid client-id :event event})
    (-event-msg-handler ev-msg)))

;; FIXME Orange
(defmethod -event-msg-handler :cli/prev [_]
  (swap! db (fn [state] (update-in state [:term] dec))))

;; FIXME blue
(defmethod -event-msg-handler :cli/next [_]
  (swap! db (fn [state] (update-in state [:term] inc))))

(defmethod -event-msg-handler :chsk/ws-ping [_] (comment "Noop"))

; Default/fallback case (no other matching handler)
(defmethod -event-msg-handler :default
  [{:as ev-msg :keys [event id ?data ring-req ?reply-fn send-fn]}]
  (let [session (:session ring-req)
        uid     (:uid     session)]
    (timbre/debugf "Unhandled event: %s" event)
    (when ?reply-fn
      (?reply-fn {:umatched-event-as-echoed-from-server event}))))

(defn update-client
  "Sends db state to all clients, which clients always accept and overwrite their local state."
  [{:keys [send-fn connected-uids]} term]
  (timbre/info {:event :update-clients})
  (let [db @db
        uids @connected-uids]
    (when uids
      (doseq [uid (:any uids)]
        (timbre/debug {:uid uid
                       :db db
                       :event :update-client})
        (send-fn uid [:srv/update (assoc db :term term)])))))

(defn push-client
  "When the server updates its db, it sends out this db to all clients."
  [{:keys [send-fn connected-uids]} _ _ _ new-state]
  (timbre/info {:event :push-clients})
  (let [uids @connected-uids]
    (doseq [uid (:any uids)]
      (timbre/debug {:uid uid
                     :new-state new-state
                     :event :push-client})
      (send-fn uid [:srv/push new-state]))))

;; FIXME Server is redundant with the namespace
(defrecord ChskServer [ch-recv
                       send-fn
                       ajax-post-fn
                       ajax-get-or-ws-handshake-fn
                       connected-uids
                       stop-fn]
  component/Lifecycle
  (start [this]
    (timbre/info {:component 'ChskServer
                  :state :started})

    (let [server (sente/make-channel-socket-server! sente-web-server-adapter
                                                    {:packer :edn
                                                     :user-id-fn user-id-fn
                                                     :handshake-data-fn (fn [_] @db)})
          router (sente/start-server-chsk-router! (:ch-recv server) event-msg-handler)]
      (assoc this
             :ch-recv (:ch-recv server)
             :send-fn (:send-fn server)
             :ajax-post-fn (:ajax-post-fn server)
             :ajax-get-or-ws-handshake-fn (:ajax-get-or-ws-handshake-fn server)
             :connected-uids (:connected-uids server)
             :stop-fn router)))

  (stop [this]
    (if stop-fn
      (do
        (timbre/info {:component 'ChskServer
                      :state :stopped})
        (stop-fn)
        (assoc this
               :ch-recv nil
               :send-fn nil
               :ajax-post-fn nil
               :ajax-get-or-ws-handshake-fn nil
               :connected-uids nil))
      this)))

;; FIXME Server is redundant with the namespace
(defn new-chsk-server [] (map->ChskServer {}))

;; TODO Make idempotent
(defrecord Watcher [chsk-server active]
  component/Lifecycle
  (start [this]
    (timbre/info {:component 'Watcher
                  :state :started})
    (add-watch db :term (partial push-client chsk-server))
    (assoc this :active [:term]))

  (stop [this]
    (timbre/info {:component 'Watcher
                  :state :stopped})
    (remove-watch db :term)
    (assoc this :active [])))

(defn new-watcher []
  (map->Watcher {}))

(defn new-system
  [config]
  (let [{:keys [port]} config]
    (component/start-system
     {:chsk-server (new-chsk-server)
      :http (component/using
             (http/new port)
             [:chsk-server])

      :watcher (component/using
                (new-watcher)
                [:chsk-server])})))

(def system-state nil)

(defn init []
  (alter-var-root
   #'system-state
   (constantly
    (new-system {:port 10002}))))

(defn start []
  (alter-var-root
   #'system-state
   component/start))

(defn stop []
  (alter-var-root
   #'system-state
   component/stop-system))

(defn -main "For `lein run`, etc." [] (component/start (new-system {:port 10002})))
