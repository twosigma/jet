(ns qbits.jet.util
  (:require [clojure.core.async :as async]
            [clojure.string :as string])
  (:import (java.util.function Supplier)
           (org.eclipse.jetty.http HttpField HttpFields HttpVersion)))

(defn http-fields->map
  [^HttpFields hfs]
  (when hfs
    (reduce (fn [m ^HttpField hf]
              (let [k (string/lower-case (.getName hf))
                    v (.getValue hf)]
                (if (contains? m k)
                  (if (coll? (m k))
                    (assoc m k (conj (m k) v))
                    (assoc m k [(m k) v]))
                  (assoc m k v))))
            {}
            hfs)))

(defn map->http-fields
  [m]
  (let [hfs (HttpFields.)]
    (doseq [[k v] m]
      (if (coll? v)
        (doseq [v' v]
          (.add hfs (name k) (str v')))
        (.add hfs (name k) (str v))))
    hfs))

(defn http2-request?
  [^String request-protocol]
  (= (.asString HttpVersion/HTTP_2) request-protocol))

(defn trailers-supported?
  [^String request-protocol {:strs [content-length transfer-encoding]}]
  (or (http2-request? request-protocol)
      (and (nil? content-length) (= transfer-encoding "chunked"))))

(defn trailers->supplier
  [trailers]
  (when trailers
    (reify Supplier
      (get [_] (map->http-fields trailers)))))

(defn trailers-fn->supplier
  [trailers-fn]
  (when trailers-fn
    (reify Supplier
      (get [_]
        (when-let [trailers (trailers-fn)]
          (map->http-fields trailers))))))

(defn- map->non-empty-http-fields
  "Ensures that we return on-empty http fields as trailers in responses.
   This is to prevent issues while using clients who do not accept empty trailers in responses."
  ;; TODO remove when https://github.com/eclipse/jetty.project/issues/3829 is fixed and empty trailer frames are not sent.
  [trailers-map]
  (if (seq trailers-map)
    (map->http-fields trailers-map)
    (map->http-fields {"x-waiter-trailer-reason" "ensures-non-empty-trailers"})))

(defn trailers-ch->supplier
  [trailers-ch]
  (when trailers-ch
    (reify Supplier
      (get [_]
        (map->non-empty-http-fields (async/<!! trailers-ch))))))

