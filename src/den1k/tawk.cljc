(ns den1k.tawk
  "CLJC namespace for CLJS and CLJ SSR support."
  (:require
    [cognitect.transit :as t]
    #?@(:cljs
        [[cljs.core.async :as a :refer [go <!]]
         [cljs-http.client :as http]
         ;[kitchen-async.promise :as p]
         [applied-science.js-interop :as j]]))
  (:refer-clojure :exclude [send])
  #?(:clj (:import (java.io InputStream ByteArrayOutputStream)
                   (com.cognitect.transit ReadHandler WriteHandler)
                   (clojure.lang PersistentTreeMap PersistentArrayMap MapEntry))))

;; CLJS

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

#_(defn send-promise [dispatch-vec]
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

;; CLJ

#?(:clj
   (def default-writer-options
     {:handlers
      {PersistentArrayMap
       (reify
         WriteHandler
         (tag [_ _] "array-map")
         (rep [_ x] (into [] cat x)))
       PersistentTreeMap
       (reify
         WriteHandler
         (tag [_ _] "sorted-map")
         (rep [_ x] (into {} x)))}}))

#?(:clj
   (defn transit-encode
     "Resolve and apply Transit's JSON/MessagePack encoding."
     [out type & [opts]]
     (let [output (ByteArrayOutputStream.)]
       (t/write (t/writer output type (merge opts default-writer-options)) out)
       (.toByteArray output))))

#?(:clj
   (def default-reader-options
     {:handlers
      {"array-map"
       (reify
         ReadHandler
         (fromRep [_ x] (apply array-map x)))
       "sorted-map"
       (reify
         ReadHandler
         (fromRep [_ x] (into (sorted-map) x)))}}))

#?(:clj
   (defn parse-transit
     "Resolve and apply Transit's JSON/MessagePack decoding."
     [^InputStream in type & [opts]]
     (t/read (t/reader in type (merge default-reader-options opts)))))

(comment

  (defn roundtrip [x]
    (-> x
        (transit-encode :json)
        (clojure.java.io/input-stream)
        (parse-transit :json)))

  (let [am (apply array-map (range 100))]
    (= (vec (roundtrip am)) (vec am)))

  (let [sm (sorted-map 5 5 6 6 1 1 2 2 3 3 4 4)]
    (= (vec (roundtrip sm)) (vec sm)))

  )

#?(:clj
   (defn transit-encode-json-with-meta [out & [opts]]
     (transit-encode out :json (merge {:transform t/write-meta} opts))))

#?(:clj
   (def multi-handler-req-dispatch-fn first))

#?(:clj
   (defmulti multi-handler-response-fn
     (fn [body _handled] (multi-handler-req-dispatch-fn body))))

#?(:clj
   (defmethod multi-handler-response-fn :default
     [req handled]
     {:status  200
      :headers {"content-type" "application/transit+json"}
      :body    (transit-encode handled
                               :json
                               {:transform t/write-meta})}))

#?(:clj
   (defn transit-wrap-multi-handler
     ([handler] (fn [req] (transit-wrap-multi-handler handler req)))
     ([handler req]
      (let [body    (parse-transit (:body req) :json)
            handled (handler body)]
        ;(merge (select-keys req [:session]))
        (multi-handler-response-fn body handled)))))

