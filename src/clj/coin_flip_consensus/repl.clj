(ns coin-flip-consensus.repl
  (:require [coin-flip-consensus.server :as s]
            [figwheel-sidecar.repl-api :as fig]))

(defn server-stop! []
  (s/stop))

(defn server-start! []
  (s/init)
  (s/start))

(defn client-start! []
  (fig/start-figwheel!))

(defn start-dev! []
  (server-start!)
  (client-start!))
