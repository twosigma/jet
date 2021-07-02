(ns qbits.jet.server
  "Adapter for the Jetty 9 server, with websocket + core.async support.
Derived from ring.adapter.jetty"
  (:require
   [clojure.core.async :as async]
   [qbits.jet.servlet :as servlet]
   [qbits.jet.websocket :refer :all])
  (:import
    (org.eclipse.jetty.alpn.server
      ALPNServerConnectionFactory)
   (org.eclipse.jetty.server
    Connector
    Handler
    Server
    Request
    ServerConnector
    HttpConfiguration
    HttpConnectionFactory
    SslConnectionFactory
    ConnectionFactory)
   (org.eclipse.jetty.http
    HttpCompliance)
   (org.eclipse.jetty.http2
     HTTP2Cipher)
   (org.eclipse.jetty.http2.server
    HTTP2ServerConnectionFactory
    HTTP2CServerConnectionFactory)
   (org.eclipse.jetty.server.handler
    HandlerCollection
    AbstractHandler
    ContextHandler
    HandlerList)
   (org.eclipse.jetty.util.thread
    QueuedThreadPool
    ScheduledExecutorScheduler)
    (org.eclipse.jetty.util.ssl
      KeyStoreScanner
      SslContextFactory
      SslContextFactory$Server)
   (org.eclipse.jetty.websocket.server WebSocketHandler)
   (org.eclipse.jetty.websocket.servlet
    WebSocketServletFactory
    WebSocketCreator
    ServletUpgradeRequest
    ServletUpgradeResponse)
   (javax.servlet.http
    HttpServletRequest
    HttpServletResponse)
   (org.eclipse.jetty.websocket.api
    WebSocketListener
    RemoteEndpoint
    Session
    UpgradeRequest)
   (qbits.jet.websocket WebSocket)))

(defn- make-ws-creator
  [handler {:keys [in out ctrl websocket-acceptor]
            :or {in async/chan
                 out async/chan
                 ctrl async/chan
                 websocket-acceptor (constantly true)}
            :as options}]
  (reify WebSocketCreator
    (createWebSocket [this request response]
      (if (websocket-acceptor request response)
        (make-websocket (in) (out) (ctrl) handler)))))

(defn- make-ws-handler
  "Returns a Jetty websocket handler"
  [handlers {:as options
             :keys [ws-input-buffer-size
                    ws-max-binary-message-buffer-size
                    ws-max-binary-message-size
                    ws-max-idle-time
                    ws-max-text-message-buffer-size
                    ws-max-text-message-size]
             :or {ws-max-idle-time 500000}}]
  (proxy [WebSocketHandler] []
    (configure [^WebSocketServletFactory factory]
      (let [ws-policy (.getPolicy factory)]
        (.setIdleTimeout ws-policy ws-max-idle-time)
        (when ws-max-binary-message-buffer-size
          (.setMaxBinaryMessageBufferSize ws-policy ws-max-binary-message-buffer-size))
        (when ws-max-binary-message-size
          (.setMaxBinaryMessageSize ws-policy ws-max-binary-message-size))
        (when ws-max-text-message-buffer-size
          (.setMaxTextMessageBufferSize ws-policy ws-max-text-message-buffer-size))
        (when ws-max-text-message-size
          (.setMaxTextMessageSize ws-policy ws-max-text-message-size))
        (when ws-input-buffer-size
          (.setInputBufferSize ws-policy ws-input-buffer-size)))
      (.setCreator factory (make-ws-creator handlers options)))))

(defn- make-handler
  "Returns an Jetty Handler implementation for the given Ring handler."
  [handler options]
  (proxy [AbstractHandler] []
    (handle [_ ^Request base-request request response]
      (let [request-map (servlet/build-request-map request)
            response' (handler request-map)]
        (when response'
          (servlet/update-response response' request-map)
          (.setHandled base-request true))))))

(defn- http-config
  [{:as options
    :keys [blocking-timeout ssl-port secure-scheme output-buffer-size request-header-size
           response-header-size send-server-version? send-date-header?
           header-cache-size]
    :or {blocking-timeout 3600
         ssl-port 443
         secure-scheme "https"
         output-buffer-size 32768
         request-header-size 8192
         response-header-size 8192
         send-server-version? true
         send-date-header? false
         header-cache-size 512}}]
  "Creates jetty http configurator"
  (doto (HttpConfiguration.)
    (.setBlockingTimeout blocking-timeout)
    (.setSecureScheme secure-scheme)
    (.setSecurePort ssl-port)
    (.setOutputBufferSize output-buffer-size)
    (.setRequestHeaderSize request-header-size)
    (.setResponseHeaderSize response-header-size)
    (.setSendServerVersion send-server-version?)
    (.setSendDateHeader send-date-header?)
    (.setHeaderCacheSize header-cache-size)))

(defn- ^SslContextFactory ssl-context-factory
  "Creates a new SslContextFactory instance from a map of options."
  [{:as options
    :keys [client-auth http2?
           keystore keystore-type key-password
           truststore trust-password truststore-type]}]
  (let [context (SslContextFactory$Server.)]
    (if (string? keystore)
      (.setKeyStorePath context keystore)
      (.setKeyStore context ^java.security.KeyStore keystore))
    (.setKeyStorePassword context key-password)
    (when keystore-type
      (.setKeyStoreType context keystore-type))
    (when truststore
      (if (string? truststore)
        (.setTrustStorePath context truststore)
        (.setTrustStore context ^java.security.KeyStore truststore)))
    (when trust-password
      (.setTrustStorePassword context trust-password))
    (when truststore-type
      (.setTrustStoreType context truststore-type))
    (case client-auth
      :need (.setNeedClientAuth context true)
      :want (.setWantClientAuth context true)
      nil)
    (when http2?
      (.setCipherComparator context HTTP2Cipher/COMPARATOR))
    context))

(defn ^HttpCompliance any->parser-compliance
  [pc]
  (cond
    (instance? HttpCompliance pc) pc
    (= pc :legacy)                HttpCompliance/LEGACY
    (= pc :rfc2616)               HttpCompliance/RFC2616
    (= pc :rfc7230)               HttpCompliance/RFC7230
    (nil? pc)                     HttpCompliance/RFC7230
    :else                         (throw (ex-info "Illegal Http Parser" {}))))

(defn ^Server run-jetty
  "Start a Jetty webserver to serve the given handler according to the
supplied options:


* `:port` - the port to listen on (defaults to 80), can be a sequence
* `:host` - the hostname to listen on
* `:join?` - blocks the thread until server ends (defaults to true)
* `:http2?` - enable HTTP2 transport
* `:http2c?` - enable HTTP2C transport (cleartext)
* `:configurator` - fn that will be passed the server instance before server.start()
* `:daemon?` - use daemon threads (defaults to false)
* `:ssl?` - allow connections over HTTPS
* `:ssl-port` - the SSL port to listen on (defaults to 443, implies :ssl?)
* `:keystore` - the keystore to use for SSL connections
* `:keystore-type` - the format of keystore
* `:keystore-scan-interval-secs` - the scanning interval to detect changes to and reload the keystore
* `:key-password` - the password to the keystore
* `:truststore` - a truststore to use for SSL connections
* `:truststore-type` - the format of trust store
* `:trust-password` - the password to the truststore
* `:accept-queue-size` - the accept queue size (also known as accept backlog)
* `:max-threads` - the maximum number of threads to use (default 50)
* `:min-threads` - the minimum number of threads to use (default 8)
* `:max-idle-time`  - the maximum idle time in milliseconds for a connection (default 200000)
* `:ws-max-idle-time`  - the maximum idle time in milliseconds for a websocket connection (default 500000)
* `:client-auth` - SSL client certificate authenticate, may be set to :need, :want or :none (defaults to :none)
* `:output-buffer-size` - (default 32768)
* `:input-buffer-size` - (default 8192)
* `:request-header-size` - (default 8192)
* `:response-header-size` - (default 8192)
* `:send-server-version?` - (default true)
* `:send-date-header?` - (default false)
* `:header-cache-size` - (default 512)
* `:websocket-handler` - a handler function that will receive a RING request map with the following keys added:
    * `:in`: core.async chan that receives data sent by the client
    * `:out`: core async chan you can use to send data to client
    * `:ctrl`: core.async chan that received control messages such as: `[::error e]`, `[::close code reason]`
* `:websocket-acceptor`: a function called with (request response) that returns a boolean whether to further process the
                         websocket request. If the function returns false, it is responsible for committing a response.
                         If the function return true, a websocket is created and the `websocket-handler` is eventually
                         called."
  [{:as options
    :keys [websocket-handler ring-handler host port accept-queue-size max-threads min-threads
           input-buffer-size max-idle-time ssl-port configurator parser-compliance
           daemon? ssl? join? http2? http2c? keystore-scan-interval-secs]
    :or {accept-queue-size 0
         max-threads 50
         min-threads 8
         daemon? false
         max-idle-time 200000
         ssl? false
         join? true
         port 80
         parser-compliance HttpCompliance/LEGACY
         input-buffer-size 8192
         keystore-scan-interval-secs (* 60 60 24)}}]
  (let [pool (doto (QueuedThreadPool. (int max-threads)
                                      (int min-threads))
               (.setDaemon daemon?))
        server (doto (Server. pool)
                 (.addBean (ScheduledExecutorScheduler.)))
        http-conf (http-config options)
        http-connection-factory (doto (HttpConnectionFactory. http-conf)
                                  (.setHttpCompliance (any->parser-compliance parser-compliance))
                                  (.setInputBufferSize (int input-buffer-size)))
        ssl-enabled? (some? (or ssl? ssl-port))
        port-seq (if (number? port)
                   [port]
                   port)
        ;; use HTTP if ssl is disabled or
        ;;             ssl is explicitly enabled and ssl-port is explicitly provided
        http-connectors (if (or (not ssl-enabled?)
                                (and ssl? ssl-port))
                          (map (fn make-http-connector [port]
                                 (doto (ServerConnector.
                                         ^Server server
                                         ^"[Lorg.eclipse.jetty.server.ConnectionFactory;"
                                         (into-array ConnectionFactory
                                                     (cond-> [http-connection-factory]
                                                       http2c? (conj (HTTP2CServerConnectionFactory. http-conf)))))
                                   (.setPort port)
                                   (.setHost host)
                                   (.setName (str "http-connector-" port))
                                   (.setAcceptQueueSize accept-queue-size)
                                   (.setIdleTimeout max-idle-time)))
                               port-seq)
                          [])
        connectors (cond-> http-connectors
                     ssl-enabled?
                     (conj (doto (ServerConnector.
                                   ^Server server
                                   (let [context-factory (ssl-context-factory options)]
                                     (when keystore-scan-interval-secs
                                       (let [keystore-scanner (KeyStoreScanner. context-factory)]
                                         (.setScanInterval keystore-scanner keystore-scan-interval-secs)
                                         (.addBean server keystore-scanner)))
                                     context-factory)
                                   ^"[Lorg.eclipse.jetty.server.ConnectionFactory;"
                                   (into-array ConnectionFactory
                                               (cond-> [(ALPNServerConnectionFactory. ^String (cond->> "http/1.1" http2? (str "h2,")))
                                                        http-connection-factory]
                                                 http2? (conj (HTTP2ServerConnectionFactory. http-conf)))))
                             (.setPort (or ssl-port port 443))
                             (.setHost host)
                             (.setName (str "https-connector-" (or ssl-port port 443)))
                             (.setAcceptQueueSize accept-queue-size)
                             (.setIdleTimeout max-idle-time))))]
    (when (empty? connectors)
      (throw (IllegalStateException. "No connectors found! HTTP port or SSL must be configured!")))
    (.setConnectors server (into-array Connector connectors))
    (when (or websocket-handler ring-handler)
      (let [hs (HandlerList.)]
        (when websocket-handler
          (.addHandler hs (make-ws-handler websocket-handler options)))
        (when ring-handler
          (.addHandler hs (make-handler ring-handler options)))
        (.setHandler server hs)))
    (when configurator
      (configurator server))
    (.start server)
    (when join?
      (.join server))
    server))
