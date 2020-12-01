(ns den1k.tawk
  "CLJC namespace for CLJS and CLJ SSR support."
  (:require
    [cognitect.transit :as t]
    #?@(:cljs
        [[kitchen-async.promise :as p]
         [applied-science.js-interop :as j]
         [lambdaisland.fetch :as fetch]]))
  (:refer-clojure :exclude [get])
  #?(:clj (:import (java.io InputStream ByteArrayOutputStream)
                   (com.cognitect.transit ReadHandler WriteHandler)
                   (clojure.lang PersistentTreeMap PersistentArrayMap MapEntry))))

;; CLJS

(def dispatch-url
  #?(:cljs
     (delay
       (str (j/get-in js/window [:location :origin])
            "/dispatch"))))

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

#?(:cljs
   (defn reader [] (t/reader :json default-decoding-opts)))

#?(:cljs
   (defn writer [] (t/writer :json default-encoding-opts)))

#?(:cljs
   (def fetch-transit-opts
     (delay
       {:transit-json-writer (writer)
        :transit-json-reader (reader)})))

(defn get [url & [opts]]
  #?(:cljs
     (fetch/get url (into opts @fetch-transit-opts))))

(defn post [url & [opts]]
  #?(:cljs
     (fetch/request
       url
       (-> opts
           (assoc :method post)
           (into @fetch-transit-opts)))))

(defn dispatch [dispatch-vec cb]
  #?(:cljs
     (p/then
       (fetch/post @dispatch-url
                   (assoc @fetch-transit-opts :body dispatch-vec))
       (fn [{:as resp :keys [status body]}]
         (if (= 200 status)
           (cb body)
           (js/console.error "den1k.tawk/dispatch error: " resp))))))

(comment

  (def read-transit
    #?(:cljs
       (fn [transit]
         (t/read (reader) transit))))

  (def write-transit
    #?(:cljs
       (fn [transit]
         (t/write (writer) transit))))

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
       (t/write (t/writer output type (into opts default-writer-options)) out)
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
     (t/read (t/reader in type (into default-reader-options opts)))))

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

