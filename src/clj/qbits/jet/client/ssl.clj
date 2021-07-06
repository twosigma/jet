(ns qbits.jet.client.ssl
  (:import (org.eclipse.jetty.util.ssl
             SslContextFactory
             SslContextFactory$Client)))

(defn ^SslContextFactory insecure-ssl-context-factory
  []
  (SslContextFactory$Client. true))
