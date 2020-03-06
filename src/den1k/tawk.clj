(ns den1k.tawk
  (:require [cognitect.transit :as transit])
  (:import (java.io InputStream ByteArrayOutputStream)))

(defn transit-encode
  "Resolve and apply Transit's JSON/MessagePack encoding."
  [out type & [opts]]
  (let [output (ByteArrayOutputStream.)]
    (transit/write (transit/writer output type opts) out)
    (.toByteArray output)))

(defn parse-transit
  "Resolve and apply Transit's JSON/MessagePack decoding."
  [^InputStream in type & [opts]]
  (transit/read (transit/reader in type opts)))

(defn transit-encode-json-with-meta [out & [opts]]
  (transit-encode out :json (merge {:transform transit/write-meta} opts)))

(def multi-handler-req-dispatch-fn first)

(defmulti multi-handler-response-fn
  (fn [body _handled] (multi-handler-req-dispatch-fn body)))

(defmethod multi-handler-response-fn :default
  [req handled]
  {:status  200
   :headers {"content-type" "application/transit+json"}
   :body    (transit-encode handled
                            :json
                            {:transform transit/write-meta})})

(defn transit-wrap-multi-handler
  ([handler] (fn [req] (transit-wrap-multi-handler handler req)))
  ([handler req]
   (let [body    (parse-transit (:body req) :json)
         handled (handler body)]
     ;(merge (select-keys req [:session]))
     (multi-handler-response-fn body handled))))
