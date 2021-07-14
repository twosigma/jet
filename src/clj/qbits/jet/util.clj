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

(defn trailers-fn->supplier
  [trailers-fn]
  (when trailers-fn
    (reify Supplier
      (get [_]
        (when-let [trailers (trailers-fn)]
          (map->http-fields trailers))))))

(defn trailers-ch->supplier
  [trailers-ch]
  (when trailers-ch
    (reify Supplier
      (get [_]
        (when-let [trailers-map (async/<!! trailers-ch)]
          (map->http-fields trailers-map))))))