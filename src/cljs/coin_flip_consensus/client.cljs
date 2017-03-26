(ns coin-flip-consensus.client
  (:require [clojure.string :as str]
            [goog.events :as events]
            [taoensso.encore :as encore]
            [taoensso.timbre :as timbre :refer-macros [tracef debugf infof warnf errorf]]
            [taoensso.sente  :as sente]
            [reagent.core :as r :refer [atom]]))

;; TODO Add a socket-closed state.
;; TODO Add a latency-tracking event

(defn now [] (.getTime (js/Date.)))

;; Dev tooling
(enable-console-print!)
(timbre/debugf "Client is running at %s" (now))

;; Database, init with some scratch vals.
(defonce db (atom {:peers []
                   :log []}))

;; Channel socket setup
(defn make-chsk-client
  "Creates a socket connection with server at /chsk"
  []
  (sente/make-channel-socket-client! "/chsk"
                                     {:type :auto
                                      :packer :edn}))

;; Batch of assignments here for the client - server connection and assign them to the global namespace.
;; FIXME Split this up into function calls, this is janky af
(defonce state_
  (let [{:keys [chsk ch-recv send-fn state]} (make-chsk-client)]

    (def chsk       chsk)
    (def ch-chsk    ch-recv) ; ChannelSocket's receive channel
    (def chsk-send! send-fn) ; ChannelSocket's send API fn
    (def chsk-state state)   ; Watchable, read-only atom
    ))

(defn update-db
  [state {:keys [term] :as new-state}]
  (assoc state :term (or term 0)))

;;;; Sente event handlers
(defmulti -event-msg-handler :id)

(defn event-msg-handler
  "Wraps `-event-msg-handler` with logging, error catching, etc."
  [{:as ev-msg :keys [id ?data event]}]
  (timbre/debugf "%s" event)
  (-event-msg-handler ev-msg))

(defmethod -event-msg-handler :chsk/state
  [{:as ev-msg :keys [?data]}]
  (if (= ?data {:first-open? true})
    (timbre/debugf "Channel socket successfully established!")))

(defmethod -event-msg-handler :chsk/handshake
  [{:as ev-msg :keys [?data]}]
  (swap! db update-db (nth ?data 2)))

(defmethod -event-msg-handler :chsk/recv
  [{:as ev-msg :keys [event ?data]}]
  (let [[id body] ?data]
    (case id
      :srv/update (swap! db update-db body)
      :srv/push (swap! db update-db body))))

(defmethod -event-msg-handler
  :default ; Default/fallback case (no other matching handler)
  [{:as ev-msg :keys [event]}]
  (timbre/debugf "Unhandled event: %s" event))

(defonce router_ (atom nil))

(defn stop-router! []
  (when-let [stop-f @router_] (stop-f)))

(defn start-router! []
  (stop-router!)
  (reset! router_
          (sente/start-client-chsk-router! ch-chsk event-msg-handler)))

(defn send-event
  "Wraps event submissions with logging."
  ([id]
   (send-event id {}))
  ([id body]
   (chsk-send! [id body])
   (timbre/debugf "[%s %s] event sent" id body)))

;; TODO Make these events for blue or orange
(defn slide-prev
  "Send next event to server. Either commit change locally on correct response, or sync w/ heartbeat."
  []
  (let [{:keys [term]} @db]
    (send-event :cli/prev {:term term :timestamp (now)})))

;; TODO Make these events for blue or orange
(defn slide-next
  "Send next event to server. Either commit change locally on correct response, or sync w/ heartbeat."
  []
  (let [{:keys [term max]} @db]
    (send-event :cli/next {:term term :timestamp (now)})))

(defn start! [] (start-router!))

(defn get-db []
  (let [db @db]
    [:p (str db)]))

(defn layout []
  [:div.app-container
   [:div.click-container
    [:div.click-left {:on-click slide-prev}]
    [:div.click-right {:on-click slide-next}]]
   [:div.log
    [get-db]]])

;; Input handling -- move this to its own namespace
(def keyboard-prev #{40 37})
(def keyboard-next #{38 39})

(defn keyboard-handle
  "Maps keyCode propery to correct handler."
  [e]
  (let [key-code (.-keyCode e)]
    (cond
      (keyboard-prev key-code) (slide-prev)
      (keyboard-next key-code) (slide-next))))

(defn keyboard-listen []
  (events/listen js/window "keydown" keyboard-handle))

;; Run these functions once on startup.
(defonce _start-once
  (do
    (keyboard-listen)
    (start!)))

(r/render-component [layout]
                    (.getElementById js/document "app"))
