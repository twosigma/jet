# jet
[![Build Status](https://secure.travis-ci.org/mpenet/jet.png?branch=master)](http://travis-ci.org/mpenet/jet)

<img src="http://i.imgur.com/gs2v6d8.gif" title="Hosted by imgur.com" align="right"/>

Jet is a jetty9 server + client library for clojure.
It's a drop in adapter replacement for ring apps.

## What's in the box?

* **ring adapter** running on jetty9

* Ring extension where core.async channel as response toggle **Jetty9 Async**

* Ring extension where core.async channel as :body in response does
  **Chunked Transfers**

* **Websocket Server** with a simple yet powerful api based on core.async

* **WebSocket Client** sharing the same principles/api than the WebSocket
  server handlers

* **Asynchronous HTTP Client** with streaming support (clj-http ~compatible API)

The server part started from the code of the various
`ring-jetty9-adapters` out there.

The API is still subject to changes.

## Documentation

[codox generated documentation](http://mpenet.github.io/jet/).

## Installation

jet is [available on Clojars](https://clojars.org/cc.qbits/jet).

Add this to your dependencies:

```clojure
[cc.qbits/jet "0.3.0"]
```
## Examples

### Vanilla Ring handler

Same as any ring compliant adapter

```clojure
(use 'qbits.jet.server)

(run-jetty handler {:port ...})
```

### Ring Async

You can have fine control over Jetty9 Async mode using a core.async
channel as response:

```clojure
(require '[clojure.core.async :as async])

(defn async-handler [request]
  (let [ch (async/chan)]
    (async/go
      (async/<! (async/timeout 1000))
      (async/>! ch
                {:body "foo"
                 :headers {"Content-Type" "foo"}
                 :status 202}))
    ch))

(qbits.jet.server/run-jetty async-handler)

```

### Server Chunked Responses

If you return a core.async channel in a ring body jetty will go into
async mode and the channel values will be streamed as chunks. If the
channel is closed the connection ends. If an error occurs or the
client disconnects the channel closes as well.

```clojure
(require '[clojure.core.async :as async])

(defn handler
  [request]
  (let [ch (async/chan 1)]
    (async/go
     (dotimes [i 5]
       (async/<! (async/timeout 300))
       (async/>! ch (str i "\n")))
     (async/close! ch))
    {:body ch
     :headers {"Content-Type" "prout"}
     :status 201}))

(qbits.jet.server/run-jetty handler {:port ...})
```

### WebSocket

Here we have the equivalent of a call to run-jetty, with the first
param as your main app ring handler (coming from whatever routing lib
you might use).

In the options the `:websockets` value takes a map of path to
handlers.

The websocket handlers receive a map that hold 3 core.async channels
and the underlying WebSocketAdapter instance for potential advanced uses.

* `ctrl` will receive status messages such as `[::error e]` `[::close reason]`

* `in` will receive content sent by this connected client

* `out` will allow you to push content to this connected client and
  close the socket


An example with a little PING/PONG between client and server:

```clojure
(use 'qbits.jet.server)

;; Simple ping/pong server, will wait for PING, reply PONG and close connection
(run-jetty some-ring-handler
  {:port 8013
   :websockets {"/"
                (fn [{:keys [in out ctrl ws]
                      :as opts}]
                    (async/go
                      (when (= "PING" (async/<! in))
                        (async/>! out "PONG")
                        (async/close! out))))}})
```

The websocket client is used the same way

```clojure
(use 'qbits.jet.client.websocket)

;; Simple PING client to our server, sends PING, waits for PONG and
;; closes the connection
(connect! "ws://localhost:8013/"
          (fn [{:keys [in out ctrl ws]}]
            (async/go
              (async/>! out "PING")
              (when (= "PONG" (async/<! in))
                (async/close! out))))))
```

If you close the :out channel, the socket will be closed, this is true
for both client/server modes.


### HTTP Client

The API is nearly identical to clj-http and other clients for
clojure. One of the major difference is that calls to the client
return a channel that will receive the eventual response
asynchronously.  The response is then a fairly standard ring response
map, except the body, which is also a core.async channel (support for
chunked responses).

See the docs for details,
[HTTP client API docs](http://mpenet.github.io/jet/qbits.jet.client.http.html)
[`qbits.jet.client.http/request`](http://mpenet.github.io/jet/qbits.jet.client.http.html#var-request) &
[`qbits.jet.client.http/client`](http://mpenet.github.io/jet/qbits.jet.client.http.html#var-client) (the former builds on the later).



```clojure
(use 'qbits.jet.client.http)
(use 'clojure.core.async)

;; returns a chan
(http/get "http://graph.facebook.com/zuck")
user> #<ManyToManyChannel clojure.core.async.impl.channels.ManyToManyChannel@731db933>

;; block for the response
(<!! (http/get "http://graph.facebook.com/zuck"))

user> {:status 200,
       :headers
       {"content-type" "text/javascript; charset=UTF-8",
        "access-control-allow-origin" "*",
        "content-length" "173",
        "x-fb-debug"
        "jkc4w5S1VN3bLddmGEU+r3F/5ANxPZXrcqq3bUXJ3n2bwZq7WB0xy+mB/CziD56wHWd2us//p2dTmRQSIiW+Yg==",
        "facebook-api-version" "v1.0",
        "connection" "keep-alive",
        "pragma" "no-cache",
        "expires" "Sat, 01 Jan 2000 00:00:00 GMT",
        "x-fb-rev" "1358170",
        "etag" "\"3becf5f2bb7ec39daa6bb65345d40b9f4b1db483\"",
        "date" "Wed, 06 Aug 2014 15:51:02 GMT",
        "cache-control" "private, no-cache, no-store, must-revalidate"},
       :body
       #<ManyToManyChannel clojure.core.async.impl.channels.ManyToManyChannel@7ca698b0>}


;; get to the body
(-> (http/get "http://graph.facebook.com/zuck")
    <!!
    :body
    <!!)
user> "{\"id\":\"4\",\"first_name\":\"Mark\",\"gender\":\"male\",\"last_name\":\"Zuckerberg\",\"link\":\"https:\\/\\/www.facebook.com\\/zuck\",\"locale\":\"en_US\",\"name\":\"Mark Zuckerberg\",\"username\":\"zuck\"}"

;; autodecode the body
(-> (get "http://graph.facebook.com/zuck" {:as :json})
         async/<!!
         :body
         async/<!!)
user> {:id "4",
       :first_name "Mark",
       :gender "male",
       :last_name "Zuckerberg",
       :link "https://www.facebook.com/zuck",
       :locale "en_US",
       :name "Mark Zuckerberg",
       :username "zuck"}

;; POST
(post "http://foo.com" {:form-params {:foo "bar" :baz 1}})
```

And you can imagine (or read the api doc) how `post`, `put`, `delete`
and other methods work. It's fairly standard. All the "method"
functions are just api sugar around [`qbits.jet.client.http/request`](http://mpenet.github.io/jet/qbits.jet.client.http.html#var-request).

This is the easy way of using the Http Client, you can also go further
and take advantage of the fact that jetty9 HTTP client splits the client
and request part of the process. You can create/setup a single client
and re-use it for many requests.

To quote the Jetty9 documentation:

> HttpClient provides an efficient, asynchronous, non-blocking
> implementation to perform HTTP requests to a server through a simple
> API that offers also blocking semantic.

> HttpClient provides easy-to-use methods such as GET(String) that
> allow to perform HTTP requests in a one-liner, but also gives the
> ability to fine tune the configuration of requests via
> newRequest(URI).

> HttpClient acts as a central configuration point for network
> parameters (such as idle timeouts) and HTTP parameters (such as
> whether to follow redirects).

> HttpClient transparently pools connections to servers, but allows
> direct control of connections for cases where this is needed.

> HttpClient also acts as a central configuration point for cookies,
> via getCookieStore().


This is also accessible from clojure:
```clojure
(def c (client {...}))

(get "http://foo.com" {:client c ...})
(get "http://foo.com/bar" {:client c ...})

```

Please check the
[Changelog](https://github.com/mpenet/jet/blob/master/CHANGELOG.md)
if you are upgrading.

## License

Copyright © 2014 [Max Penet](http://twitter.com/mpenet)

Distributed under the
[Eclipse Public License](http://www.eclipse.org/legal/epl-v10.html),
the same as Clojure.
