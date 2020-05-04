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


(defn send-url []
  #?(:cljs
     (str (j/get-in js/window [:location :origin])
          "/dispatch")))

(def default-encoding-opts
  #?(:cljs
     {:handlers
      {cljs.core/PersistentArrayMap
       (t/write-handler (constantly "array-map") (fn [x] (into [] cat x)))
       cljs.core/PersistentTreeMap
       (t/write-handler (constantly "sorted-map") (fn [x] (into {} x)))}}))

(def default-decoding-opts
  #?(:cljs
     {:handlers
      {"sorted-map" (t/read-handler (fn [x] (into (sorted-map) x)))
       "array-map"  (t/read-handler (fn [x] (apply array-map x)))}}))

(def ^:private transit-encode-decode-opts
  {:encoding-opts default-encoding-opts
   :decoding-opts default-decoding-opts})

(def read-transit
  #?(:cljs
     (let [reader (t/reader :json default-decoding-opts)]
       (fn [transit]
         (t/read reader transit)))))

(def write-transit
  #?(:cljs
     (let [writer (t/writer :json default-encoding-opts)]
       (fn [transit]
         (t/write writer transit)))))

(defn send [dispatch-vec cb]
  #?(:cljs
     (go
      (when-let
       [body (:body
              (<! (http/post
                   (send-url)
                   {:transit-params dispatch-vec
                    :transit-opts   transit-encode-decode-opts})))]
        (cb body)))))

(defn send-promise [dispatch-vec]
  #?(:cljs
     (p/promise [resolve reject]
       (go
        (when-let [body (:body (<! (http/post
                                    (send-url)
                                    {:transit-params dispatch-vec
                                     :transit-opts   transit-encode-decode-opts})))]
          (resolve body))))))

(comment

 (defn roundtrip [x]
   (-> x
       (write-transit)
       (read-transit)))
 (let [am (apply array-map (range 100))]
   (= (vec (roundtrip am)) (vec am)))
 )

