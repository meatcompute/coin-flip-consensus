(ns coin-flip-consensus.server
  "A web and backend server for a conensus-driven multiplayer coin flip game."
  (:require ; [coin-flip-consensus.event :as event]
   [coin-flip-consensus.http :as http]
   [coin-flip-consensus.channel :as channel]
   [com.stuartsierra.component :as component]
   [clojure.core.async
    :as async
    :refer [<! <!! >! >!! put! chan go go-loop close!]]
   [taoensso.timbre :as timbre]
   [taoensso.timbre.appenders.3rd-party.rotor :as rotor]))

;; Set the global logging behavior for timbre
(timbre/set-config! {:level :info
                     :appenders {:rotor (rotor/rotor-appender {:max-size (* 1024 1024)
                                                               :backlog 10
                                                               :path "./coin-flip-consensus.log"})}})

(defn client-spec [] java.util.UUID)
(defn vote-spec [] [java.util.UUID clojure.lang.Keyword])

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

(defrecord Db [clients log term]
  component/Lifecycle
  (start [this]
    (if term
      this
      (do
        (timbre/info {:component 'Db :state :started})
        (assoc this
               :clients (atom [])
               :log (atom [])
               :term (atom 0)
               :votes (atom [])))))

  (stop [this]
    (if term
      (do
        (timbre/info {:component 'Db :state :stopped})
        (assoc this
               :clients nil
               :log nil
               :term nil))
      this)))

(defn new-db []
  (map->Db {}))

;; TODO Test if adding and removing watchers is idempotent
(defrecord Watcher [db channel active]
  component/Lifecycle
  (start [this]
    (timbre/info {:component 'Watcher
                  :state :started})
    (add-watch (:term db) :term (partial push-client channel))
    (assoc this :active [:term]))

  (stop [this]
    (timbre/info {:component 'Watcher
                  :state :stopped})
    (remove-watch (:term db) :term)
    (assoc this :active [])))

(defn new-watcher []
  (map->Watcher {}))

(defn new-system
  [config]
  (let [{:keys [port]} config]
    (component/start-system {:db (new-db)
                             :channel (component/using
                                       (channel/new)
                                       [:db])
                             :http (component/using
                                    (http/new port)
                                    [:channel])
                             :watcher (component/using
                                       (new-watcher)
                                       [:channel :db])})))

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
