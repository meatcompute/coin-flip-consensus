(ns coin-flip-consensus.http-test
  (:require [clojure.test :refer :all]
            [coin-flip-consensus.http :as http]
            [org.httpkit.client :as hc]))

;; FIXME Create an http/new and check for 200 response on the URI
;; This is going to require waiting for the server to start...
(deftest http-component-test
  (testing "Can create an Http component"
    (let [port 15864
          http (http/new port)
          response (hc/get (str "http://localhost:" port))
          status @response]
      (is (= status 200)))))
