(ns coin-flip-consensus.channel
  (:require [com.stuartsierra.component :as component]
            [taoensso.timbre :as timbre]
            [taoensso.sente :as sente]
            [taoensso.sente.server-adapters.http-kit :refer [sente-web-server-adapter]]))

(defn user-id-fn
  "Each client provides a UUID on connect. We get it from the request and call it the uid on our end."
  [ring-req]
  (:client-id ring-req))

;; Event handlers
(defmulti -event-msg-handler :id)

(defn event-msg-handler
  "Wraps `-event-msg-handler` with logging."
  [db {:as ev-msg :keys [event ring-req client-id]}]
  (let [session (:session ring-req)]
    (timbre/info {:uid client-id :event event})
    #_(-event-msg-handler db ev-msg)))

;; FIXME Orange
(defmethod -event-msg-handler
  :cli/prev
  [{:keys [term]} ev-msg]
  (swap! term dec))

;; FIXME Blue
(defmethod -event-msg-handler
  :cli/next
  [{:keys [term]} ev-msg]
  (swap! term inc))

;; FIXME Update client state when it gets pinged? This should
;;       help address missed updates.
(defmethod -event-msg-handler
  :chsk/ws-ping
  [db ev-msg]
  (comment "Noop"))

; Default/fallback case (no other matching handler)
(defmethod -event-msg-handler
  :default
  [{:as ev-msg :keys [event id ?data ring-req ?reply-fn send-fn]}]
  (let [session (:session ring-req)
        uid     (:uid     session)]
    (when ?reply-fn
      (?reply-fn {:umatched-event-as-echoed-from-server event}))))

#_(defn update-client
    "Sends db state to all clients, which clients always accept and overwrite their local state."
    [db {:keys [send-fn connected-uids]} term]
    (timbre/info {:event :update-clients})
    (let [db @db
          uids @connected-uids]
      (when uids
        (doseq [uid (:any uids)]
          (timbre/debug {:uid uid
                         :db db
                         :event :update-client})
          (send-fn uid [:srv/update (assoc db :term term)])))))

(defrecord Channel [db
                    ch-recv
                    send-fn
                    ajax-post-fn
                    ajax-get-or-ws-handshake-fn
                    connected-uids
                    stop-fn]
  component/Lifecycle
  (start [this]
    (timbre/info {:component 'Channel
                  :state :started})

    (let [server (sente/make-channel-socket-server! sente-web-server-adapter
                                                    {:packer :edn
                                                     :user-id-fn user-id-fn
                                                     :handshake-data-fn (fn [_] db)})
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
        (timbre/info {:component 'Channel
                      :state :stopped})
        (stop-fn)
        (assoc this
               :ch-recv nil
               :send-fn nil
               :ajax-post-fn nil
               :ajax-get-or-ws-handshake-fn nil
               :connected-uids nil))
      this)))

(defn new [] (map->Channel {}))
