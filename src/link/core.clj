(ns link.core
  (:refer-clojure :exclude [send])
  (:use [link.util :only [make-handler-macro]])
  (:import [java.net InetSocketAddress InetAddress])
  (:import [io.netty.channel
            Channel
            ChannelId
            ChannelFuture
            ChannelHandlerContext
            ChannelOption
            SimpleChannelInboundHandler])
  (:import [io.netty.channel.socket.nio NioSocketChannel])
  (:import [io.netty.util.concurrent GenericFutureListener]))

(defprotocol LinkMessageChannel
  (id [this])
  (send! [this msg])
  (send!* [this msg cb])
  (valid? [this])
  (channel-addr [this])
  (remote-addr [this])
  (close! [this]))

(defn- client-channel-valid? [^Channel ch]
  (and ch (.isActive ch)))

(defn- addr-str [^InetSocketAddress addr]
  (str (.. addr (getAddress) (getHostAddress))
       ":" (.getPort addr)))

(defn channel-id [^Channel ch]
  (.asShortText ^ChannelId (.id ch)))

(deftype ClientSocketChannel [ch-agent factory-fn stopped]
  LinkMessageChannel
  (id [this]
    (channel-id @ch-agent))
  (send! [this msg]
    (send!* this msg nil))
  (send!* [this msg cb]
    (clojure.core/send-off ch-agent
                           (fn [ch]
                             (let [ch- (if (client-channel-valid? ch)
                                         ch
                                         (do
                                           (when ch
                                             (.close ^Channel ch))
                                           (factory-fn)))
                                   cf (if (client-channel-valid? ch-)
                                        (.writeAndFlush ^Channel ch- msg))]
                               (when (and cf cb)
                                 (.addListener ^ChannelFuture cf
                                               (reify GenericFutureListener
                                                 (operationComplete [this f]
                                                   (cb f)))))
                               ch-))))
  (channel-addr [this]
    (.localAddress ^Channel @ch-agent))
  (remote-addr [this]
    (.remoteAddress ^Channel @ch-agent))
  (close! [this]
    (reset! stopped true)
    (when @ch-agent
      (.close ^Channel @ch-agent)))
  (valid? [this]
    (client-channel-valid? @ch-agent)))

(extend-protocol LinkMessageChannel
  NioSocketChannel
  (id [this]
    (channel-id this))
  (send! [this msg]
    (send!* this msg nil))
  (send!* [this msg cb]
    (let [cf (.writeAndFlush this msg)]
      (when cb
        (.addListener ^ChannelFuture cf (reify GenericFutureListener
                                          (operationComplete [this f] (cb f)))))))
  (channel-addr [this]
    (.localAddress this))
  (remote-addr [this]
    (.remoteAddress this))
  (close! [this]
    (.close this))
  (valid? [this]
    (.isActive this)))

(make-handler-macro message)
(make-handler-macro error)
(make-handler-macro active)
(make-handler-macro inactive)
(make-handler-macro event)

(defmacro create-handler0 [sharable & body]
  `(let [handlers# (merge ~@body)]
     (proxy [SimpleChannelInboundHandler] []
       (isSharable [] ~sharable)
       (channelActive [^ChannelHandlerContext ctx#]
         (when-let [handler# (:on-active handlers#)]
           (handler# (.channel ctx#)))
         (.fireChannelActive ctx#))

       (channelInactive [^ChannelHandlerContext ctx#]
         (when-let [handler# (:on-inactive handlers#)]
           (handler# (.channel ctx#)))
         (.fireChannelInactive ctx#))

       (exceptionCaught [^ChannelHandlerContext ctx#
                         ^Throwable e#]
         (if-let [handler# (:on-error handlers#)]
           (handler# (.channel ctx#) e#)
           (.fireExceptionCaught ctx# e#)))

       (channelRead0 [^ChannelHandlerContext ctx# msg#]
         (when-let [handler# (:on-message handlers#)]
           (handler# (.channel ctx#) msg#)))

       (userEventTriggered [^ChannelHandlerContext ctx# evt#]
         (if-let [handler# (:on-event handlers#)]
           (handler# (.channel ctx#) evt#)
           (.fireUserEventTriggered ctx# evt#))))))

(defmacro create-handler [& body]
  `(create-handler0 true ~@body))

(defmacro create-stateful-handler [& body]
  `(fn [_] (create-handler0 false ~@body)))
