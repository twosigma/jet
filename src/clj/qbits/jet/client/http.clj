(ns qbits.jet.client.http
  (:refer-clojure :exclude [get])
  (:require
    [cheshire.core :as json]
    [clojure.core.async :as async]
    [clojure.core.async.impl.protocols :as protocols]
    [clojure.xml :as xml]
    [qbits.jet.client.ssl :as ssl]
    [qbits.jet.client.auth :as auth]
    [qbits.jet.client.cookies :as cookies]
    [qbits.jet.util :as util])
  (:import
    (org.eclipse.jetty.client
      HttpClient
      HttpRequest
      HttpResponse)
    (org.eclipse.jetty.util Fields)
    (org.eclipse.jetty.client.util
      StringContentProvider
      BytesContentProvider
      ByteBufferContentProvider
      DeferredContentProvider
      InputStreamContentProvider
      PathContentProvider
      FormContentProvider
      MultiPartContentProvider)
    (org.eclipse.jetty.http
      HttpField
      HttpVersion)
    (org.eclipse.jetty.client.api
      Authentication$Result
      ContentProvider
      Request
      Request$FailureListener
      Response
      Response$CompleteListener
      Response$HeadersListener
      Response$AsyncContentListener)
    (org.eclipse.jetty.client.http
      HttpClientTransportOverHTTP)
    (java.util.concurrent TimeUnit)
    java.net.HttpCookie
    (java.nio ByteBuffer)
    (java.io
      ByteArrayInputStream
      ByteArrayOutputStream
      IOException)
    (clojure.lang Sequential)))

(def ^:const array-class (class (clojure.core/byte-array 0)))
(def default-buffer-size (* 1024 1024 4))
(def default-insecure-ssl-context-factory
  (delay (ssl/insecure-ssl-context-factory)))

(defn ^:no-doc ^array-class
  byte-buffer->bytes
  [^ByteBuffer bb]
  (let [ba (byte-array (.remaining bb))]
    (.get bb ba)
    ba))

(defn ^:no-doc byte-array->string
  [^bytes bb]
  (String. bb "UTF-8"))

(defn ^:no-doc decode-body [bb as]
  (case as
    :bytes bb
    :input-stream (ByteArrayInputStream. bb)
    :string (byte-array->string bb)
    :json (json/parse-string (byte-array->string bb) true)
    :json-str (json/parse-string (byte-array->string bb) false)
    :xml (xml/parse (ByteArrayInputStream. bb))
    (ByteBuffer/wrap bb)))

(defn fold-chunks+decode-xform [as buffer-size]
  (fn [reduction-function]
    (let [ba (ByteArrayOutputStream.)
          state (atom ::open)
          retrieve-data (fn [] (decode-body (.toByteArray ba) as))]
      (fn
        ([] (reduction-function))
        ([result]
         (when (compare-and-set! state ::open ::closed)
           (reduction-function result (retrieve-data))))
        ([result chunk]
         (.write ba chunk)
         (if (>= (.size ba) buffer-size)
           (let [data (retrieve-data)]
             (.reset ba)
             (reduction-function result data))
           (reduction-function result)))))))

(defn decode-chunk-xform
  [as]
  (fn [reduction-function]
    (fn
      ([] (reduction-function))
      ([result]
       (reduction-function result))
      ([result chunk]
       (reduction-function result (decode-body chunk as))))))

(defn- make-response
   [request ^Response response body-ch error-chan trailers-ch]
   (let [headers (util/http-fields->map (.getHeaders response))
         status (.getStatus response)]
     {:body body-ch
      :error-chan error-chan
      :headers headers
      :request request
      :status status
      :trailers (when (and (instance? HttpRequest request)
                           (util/trailers-supported? (some-> request .getVersion .asString) headers))
                  trailers-ch)}))

(defprotocol PRequest
  (encode-chunk [x])
  (encode-body [x])
  (encode-content-type [x]))

(extend-protocol PRequest
  (Class/forName "[B")
  (encode-chunk [x]
    (ByteBuffer/wrap x))
  (encode-body [ba]
    (BytesContentProvider.
      (into-array array-class [ba])))

  ByteBuffer
  (encode-chunk [x] x)
  (encode-body [bb]
    (ByteBufferContentProvider. (into-array ByteBuffer [bb])))

  java.nio.file.Path
  (encode-body [p]
    (PathContentProvider. p))

  java.io.InputStream
  (encode-body [s]
    (InputStreamContentProvider. s))

  String
  (encode-chunk [x]
    (ByteBuffer/wrap (.getBytes x "UTF-8")))
  (encode-body [x]
    (StringContentProvider. x "UTF-8"))
  (encode-content-type [x]
    (str "Content-Type: " x))

  Number
  (encode-chunk [x] (encode-chunk (str x)))
  (encode-body [x] (encode-body (str x)))

  Sequential
  (encode-content-type [[content-type charset]]
    (str (encode-content-type content-type) "; charset=" (name charset)))

  clojure.core.async.impl.channels.ManyToManyChannel
  (encode-body [ch]
    (let [cp (DeferredContentProvider. (into-array ByteBuffer nil))]
      (async/go
        (loop []
          (if-let [chunk (async/<! ch)]
            (do (.offer ^DeferredContentProvider cp
                        (encode-chunk chunk))
                (recur))
            (.close ^DeferredContentProvider cp))))
      cp))

  Object
  (encode-chunk [x]
    (throw (ex-info "Chunk type no supported by encoder" {})))
  (encode-body [x]
    (throw (ex-info "Body type no supported by encoder" {})))
  (encode-content-type [content-type]
    (encode-content-type (subs (str content-type) 1))))

(defn ^:no-doc add-cookies!
  [^HttpClient client url cookies]
  (cookies/add-cookies! (.getCookieStore client)
                        url cookies))

(defn ^:no-doc add-auth!
  [^HttpClient client url {:keys [type user password realm]}]
  (.addAuthentication
   (.getAuthenticationStore client)
   (case type
     :digest (auth/digest-auth url realm user password)
     :basic (auth/basic-auth url realm user password))))

(defn ^HttpClient client
  ([{:keys [address-resolution-timeout
            connect-timeout
            executor
            follow-redirects?
            max-redirects
            idle-timeout
            stop-timeout
            max-connections-per-destination
            max-requests-queued-per-destination
            request-buffer-size
            response-buffer-size
            scheduler
            user-agent
            cookie-store
            remove-idle-destinations?
            dispatch-io?
            tcp-no-delay?
            strict-event-ordering?
            ssl-context-factory
            transport]
     :or {remove-idle-destinations? true
          dispatch-io? true
          follow-redirects? true
          tcp-no-delay? true
          strict-event-ordering? false
          ssl-context-factory @default-insecure-ssl-context-factory
          transport (HttpClientTransportOverHTTP.)
          request-buffer-size default-buffer-size
          response-buffer-size default-buffer-size}
     :as r}]
   (let [client (HttpClient. transport ssl-context-factory)]

     (when address-resolution-timeout
       (.setAddressResolutionTimeout client (long address-resolution-timeout)))

     (when connect-timeout
       (.setConnectTimeout client (long connect-timeout)))

     (when max-redirects
       (.setMaxRedirects client (int max-redirects)))

     (when idle-timeout
       (.setIdleTimeout client (long idle-timeout)))

     (when max-connections-per-destination
       (.setMaxConnectionsPerDestination client (int max-connections-per-destination)))

     (when max-requests-queued-per-destination
       (.setMaxRequestsQueuedPerDestination client (int max-requests-queued-per-destination)))

     (when request-buffer-size
       (.setRequestBufferSize client (int request-buffer-size)))

     (when response-buffer-size
       (.setResponseBufferSize client (int response-buffer-size)))

     (when scheduler
       (.setScheduler client scheduler))

     (when user-agent
       (.setUserAgentField client (HttpField. "User-Agent" ^String user-agent)))

     (when stop-timeout
       (.setStopTimeout client (long stop-timeout)))

     (when cookie-store
       (.setCookieStore client cookie-store))

     (when executor
       (.setExecutor client executor))

     (.setRemoveIdleDestinations client remove-idle-destinations?)
     (.setDispatchIO client dispatch-io?)
     (.setFollowRedirects client follow-redirects?)
     (.setStrictEventOrdering client strict-event-ordering?)
     (.setTCPNoDelay client tcp-no-delay?)
     (.start client)
     client))
  ([] (client {})))

(defn stop-client!
  [^HttpClient cl]
  (.stop cl))

(defn- set-query-string!
  "Configures the query string in the request object directly."
  [^HttpRequest request query-string]
  (doto (.getDeclaredField HttpRequest "query")
    (.setAccessible true)
    (.set request query-string)))

(defn- untyped-content-provider
  [^ContentProvider content-provider]
  (reify
    ContentProvider
    ;; interface ContentProvider methods
    (getLength [_] (.getLength content-provider))
    (isReproducible [_] (.isReproducible content-provider))
    ;; interface Iterable<ByteBuffer> methods
    (iterator [_] (.iterator content-provider))
    (forEach [_ action] (.forEach content-provider action))
    (spliterator [_] (.spliterator content-provider))))

(defn request
  [^HttpClient client
   {:keys [url method query-string form-params headers body trailers-fn
           content-type
           accept
           as
           idle-timeout
           timeout
           agent
           follow-redirects?
           fold-chunked-response?
           fold-chunked-response-buffer-size
           ^Authentication$Result auth
           cookies
           multipart
           version]
    :or {method :get
         as :string
         follow-redirects? true
         fold-chunked-response? true
         fold-chunked-response-buffer-size Integer/MAX_VALUE}
    :as request-map}]
  (let [ch (async/promise-chan)
        error-ch (async/promise-chan)
        body-ch (async/chan 1
                            (if fold-chunked-response?
                              (fold-chunks+decode-xform as fold-chunked-response-buffer-size)
                              (decode-chunk-xform as)))
        trailers-ch (async/promise-chan)
        ^Request request (.newRequest client ^String url)]

    (some->> version
      HttpVersion/fromString
      (.version request))

    (.followRedirects request follow-redirects?)

    (when timeout
      (.timeout request (long timeout) TimeUnit/MILLISECONDS))

    (when idle-timeout
      (.idleTimeout request (long idle-timeout) TimeUnit/MILLISECONDS))

    (when accept
      (.accept request (into-array String [(name accept)])))

    (when agent
      (.agent request agent))

    (.method request (name method))

    (when (seq form-params)
      (.content request
                (FormContentProvider. (let [f (Fields.)]
                                        (doseq [[k v] form-params]
                                          (.add f (name k) (str v)))
                                        f))))

       (when (seq multipart)
             (.content request
                       (let [provider (MultiPartContentProvider.)]
                            (doseq [[k v] multipart]
                                   (let [content (cond
                                                   (string? v) (StringContentProvider. v)
                                                   (instance? ContentProvider v) v
                                                   :else (throw (ex-info "Invalid multipart value" {k v})))]
                                        (.addFieldPart provider k content nil)))
                            (.close provider)
                            provider)))

    (when body
      (->> (encode-body body)
           untyped-content-provider
           (.content request)))

    (when (and trailers-fn (instance? HttpRequest request))
      (.trailers ^HttpRequest request (util/trailers-fn->supplier trailers-fn)))

    (doseq [[k v] headers]
      (if (coll? v)
        (doseq [v' v]
          (.header request (name k) (str v')))
        (.header request (name k) (str v))))

    (when content-type
      (.header request "Content-Type" (name content-type)))

    (when auth
      (.apply auth request))

    (if (string? query-string)
      (set-query-string! request query-string)
      (doseq [[k v] query-string]
        (if (coll? v)
          (doseq [v' v]
            (.param request (name k) (str v')))
          (.param request (name k) (str v)))))

    (when cookies
      (doseq [[name value] cookies]
        (.cookie request (HttpCookie. name value))))

    (.onResponseContentAsync request
                             (reify Response$AsyncContentListener
                               (onContent [_ _ bytebuffer callback]
                                 (if (protocols/closed? body-ch)
                                   (let [ex (IOException. "Body channel closed unexpectedly")]
                                     (.failed callback ex)
                                     (.abort request ex))
                                   (async/put! body-ch (byte-buffer->bytes bytebuffer)
                                               (fn [_] (.succeeded callback)))))))

    (.onResponseHeaders request
                        (reify Response$HeadersListener
                          (onHeaders [_ response]
                            (async/put! ch (make-response request response body-ch error-ch trailers-ch)))))

    (.onRequestFailure request
                       (reify Request$FailureListener
                         (onFailure [_ _ throwable]
                           (async/put! ch {:error throwable}))))

    (.send request
           (reify Response$CompleteListener
             (onComplete [_ result]
               (when (not (.isSucceeded result))
                   (async/put! ch {:error (.getFailure result)})
                   (async/put! error-ch {:error (.getFailure result)}))
               (when-let [response (.getResponse result)]
                 (when (instance? HttpResponse response)
                   (when-let [trailers (.getTrailers ^HttpResponse response)]
                     (async/>!! trailers-ch (util/http-fields->map trailers)))))
               (async/close! body-ch)
               (async/close! trailers-ch)
               (async/close! ch)
               (async/close! error-ch))))
    ch))

(defn get
  ([client url request-map]
   (request client
            (into {:method :get :url url}
                  request-map)))
  ([client url]
   (get client url {})))

(defn post
  ([client url request-map]
   (request client
            (into {:method :post :url url}
                  request-map)))
  ([client url]
   (post client url {})))

(defn put
  ([client url request-map]
   (request client
            (into {:method :put :url url}
                  request-map)))
  ([client url]
   (put client url {})))

(defn delete
  ([client url request-map]
   (request client
            (into {:method :delete :url url}
                  request-map)))
  ([client url]
   (delete client url {})))

(defn head
  ([client url request-map]
   (request client
            (into {:method :head :url url}
                  request-map)))
  ([client url]
   (head client url {})))

(defn trace
  ([client url request-map]
   (request client
            (into {:method :trace :url url}
                  request-map)))
  ([client url]
   (trace client url {})))
