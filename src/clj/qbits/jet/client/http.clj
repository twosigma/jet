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
      AsyncContentProvider
      HttpClient
      HttpRequest
      HttpResponse
      Synchronizable)
    (org.eclipse.jetty.util
      Callback
      Fields)
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
    (clojure.lang Sequential)
    (java.util Iterator)))

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
   [request ^Response response abort-ch body-ch error-chan trailers-ch]
   (let [headers (util/http-fields->map (.getHeaders response))
         status (.getStatus response)]
     {:abort-ch abort-ch
      :body body-ch
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
            client-name
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

     (when client-name
       (.setName client client-name))

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

(defn- wrap-iterator
  [on-last-chunk ^Iterator iterator]
  (let [default-lock-object (Object.)]
    (reify
      Callback
      (succeeded [_]
        (when (instance? Callback iterator)
          (.succeeded ^Callback iterator)))
      (failed [_ throwable]
        (when (instance? Callback iterator)
          (.failed ^Callback iterator throwable)))
      Iterator
      (hasNext [_]
        (let [more? (.hasNext iterator)]
          (when-not more?
            (on-last-chunk))
          more?))
      (next [_]
        ;; We rely on the caller (and that is currently true in Jetty) always checking
        ;; more data is available by having called hasNext() previously.
        (.next iterator))
      Synchronizable
      ;; Mimic DeferredContentProviderIterator which implements Synchronizable
      (getLock [_]
        (if (instance? Synchronizable iterator)
          (.getLock ^Synchronizable iterator)
          default-lock-object)))))

(defn- untyped-content-provider
  [on-last-chunk ^ContentProvider content-provider]
  (if (instance? AsyncContentProvider content-provider)
    (reify
      AsyncContentProvider
      ;; interface AsyncContentProvider methods
      (setListener [_ listener]
        (.setListener ^AsyncContentProvider content-provider listener))
      ;; interface ContentProvider methods
      (getLength [_] (.getLength content-provider))
      (isReproducible [_] (.isReproducible content-provider))
      ;; interface Iterable<ByteBuffer> methods
      (iterator [_]
        (cond->> (.iterator content-provider)
          ;; we wrap the iterator since Jetty doesn't currently provide a listener for
          ;; when the request body has been read.
          on-last-chunk (wrap-iterator on-last-chunk)))
      (forEach [_ action] (.forEach content-provider action))
      (spliterator [_] (.spliterator content-provider)))
    (reify
      ContentProvider
      ;; interface ContentProvider methods
      (getLength [_] (.getLength content-provider))
      (isReproducible [_] (.isReproducible content-provider))
      ;; interface Iterable<ByteBuffer> methods
      (iterator [_]
        (cond->> (.iterator content-provider)
          ;; we wrap the iterator since Jetty doesn't currently provide a listener for
          ;; when the request body has been read.
          on-last-chunk (wrap-iterator on-last-chunk)))
      (forEach [_ action] (.forEach content-provider action))
      (spliterator [_] (.spliterator content-provider)))))

(defn- track-response-trailers
  "Asynchronously loops and looks for presence of trailers in the response.
   When available, they are propagated to the trailers channel and other request channels closed.
   Please see https://github.com/eclipse/jetty.project/issues/3842 for details on why we need this."
  [response-trailers-ch response-body-ch close-request-channels! response]
  (async/go
    (loop []
      ;; iterate every second and check if trailers are available
      (async/<! (async/timeout 1000))
      (let [trailers (.getTrailers ^HttpResponse response)]
        (if trailers
          (do
            (async/>! response-trailers-ch (util/http-fields->map trailers))
            (close-request-channels!))
          (when-not (protocols/closed? response-body-ch)
            (recur)))))))

(defn request
  [^HttpClient client
   {:keys [url method query-string form-params headers body trailers-fn
           content-type
           abort-ch
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
        ^Request request (.newRequest client ^String url)
        trailers-supported? (and trailers-fn (instance? HttpRequest request))
        close-request-channels! (fn close-request-channels! []
                                 (when abort-ch
                                   (async/close! abort-ch))
                                 (async/close! body-ch)
                                 (async/close! trailers-ch)
                                 (async/close! ch)
                                 (async/close! error-ch))]

    (some->> version
      HttpVersion/fromString
      (.version request))

    (when abort-ch
      (async/go
        (loop []
          ;; continuously process any abort requests
          (when-let [message (async/<! abort-ch)]
            (let [[cause response-chan] message
                  abort-result (.abort request cause)]
              (async/put! response-chan [abort-result (.getAbortCause request)])
              (recur))))))

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

    (if (and body (util/http2-request? version))
      ;; HTTP/2 requests with a body need the trailer to set be set as late as possible
      ;; This allows us to avoid sending an empty terminating trailer frame if there are no trailers to send.
      (->> (encode-body body)
           (untyped-content-provider
             (fn on-request-body-content-read []
               (when trailers-supported?
                 (when-let [trailers (trailers-fn)]
                   (.trailers ^HttpRequest request (util/trailers->supplier trailers))))))
           (.content request))
      ;; For HTTP/1.1 requests or requests without a body, we need the trailer set eagerly
      ;; For HTTP/2 requests, we may end up sending an empty trailer frame if the body is empty
      (do
        (when body
          (->> (encode-body body)
               (untyped-content-provider nil)
               (.content request)))
        (when trailers-supported?
          (.trailers ^HttpRequest request (util/trailers-fn->supplier trailers-fn)))))

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
                               (onContent [_ _ byte-buffer callback]
                                 (if (protocols/closed? body-ch)
                                   (let [ex (IOException. "Body channel closed unexpectedly")]
                                     (.failed callback ex)
                                     (.abort request ex))
                                   (async/put! body-ch (byte-buffer->bytes byte-buffer)
                                               (fn [_] (.succeeded callback)))))))

    (.onResponseHeaders request
                        (reify Response$HeadersListener
                          (onHeaders [_ response]
                            (async/put! ch (make-response request response abort-ch body-ch error-ch trailers-ch))
                            (track-response-trailers trailers-ch body-ch close-request-channels! response))))

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
               (close-request-channels!))))
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
