(ns den1k.tawk
  "CLJC namespace for CLJS and CLJ SSR support."
  #?(:cljs
     (:require
      [cognitect.transit :as t]
      [cljs.core.async :as a :refer [go <!]]
      [cljs-http.client :as http]
      [kitchen-async.promise :as p]
      [applied-science.js-interop :as j]))
  (:refer-clojure :exclude [send]))

(def read-transit
  #?(:cljs
     (let [reader (t/reader :json)]
       (fn [transit]
         (t/read reader transit)))))

(defn send-url []
  #?(:cljs
     (str (j/get-in js/window [:location :origin])
          "/dispatch")))

(defn send [dispatch-vec cb]
  #?(:cljs
     (go
      (when-let [body (:body (<! (http/post
                                  (send-url)
                                  {:transit-params dispatch-vec})))]
        (cb body)))))

(defn send-promise [dispatch-vec]
  #?(:cljs
     (p/promise [resolve reject]
       (go
        (when-let [body (:body (<! (http/post
                                    (send-url)
                                    {:transit-params dispatch-vec})))]
          (resolve body))))))

