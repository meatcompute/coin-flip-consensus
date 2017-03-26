(ns coin-flip-consensus.event
  (:require [taoensso.timbre :as timbre]))

(def db (atom {:clients []
               :terms {:log []}}))

(defn count-terms [db]
  (count (:terms db)))

;; Event handlers
(defmulti -event-msg-handler :id)

(defn event-msg-handler
  "Wraps `-event-msg-handler` with logging."
  [{:as ev-msg :keys [event ring-req client-id]}]
  (let [session (:session ring-req)]
    (timbre/info {:uid client-id :event event})
    (-event-msg-handler ev-msg)))

(defmethod -event-msg-handler :cli/prev [_]
  (swap! db (fn [state] (update-in state [:index] dec))))

(defmethod -event-msg-handler :cli/next [_]
  (swap! db (fn [state] (update-in state [:index] inc))))

(defmethod -event-msg-handler :chsk/ws-ping [_] (comment "Noop"))

; Default/fallback case (no other matching handler)
(defmethod -event-msg-handler :default
  [{:as ev-msg :keys [event id ?data ring-req ?reply-fn send-fn]}]
  (let [session (:session ring-req)
        uid     (:uid     session)]
    (timbre/debugf "Unhandled event: %s" event)
    (when ?reply-fn
      (?reply-fn {:umatched-event-as-echoed-from-from-server event}))))

(defn sync-client
  [{:keys [send-fn connected-uids]} term]
  (timbre/info {:event :sync-clients
                :message "Sending sync to all clients."})
  (let [db @db
        uids @connected-uids]
    (when uids
      (doseq [uid (:any uids)]
        (timbre/debug {:uid uid
                       :db db
                       :event :sync-client
                       :message (str "Syncing: " db " to UID: " uid)})
        (send-fn uid [:srv/sync (assoc db
                                       :term term)])))))

(defn push-client
  [{:keys [send-fn connected-uids]} _ _ _ new-state]
  (timbre/info {:event :push-clients
                :message "Pushing state to all clients."})
  (let [uids @connected-uids]
    (doseq [uid (:any uids)]
      (timbre/debug {:uid uid
                     :new-state new-state
                     :event :push-client
                     :message (str "Pushing: " new-state " to UID: " uid)})
      (send-fn uid [:srv/push new-state]))))
