(ns obiwan.core
  (:require [clojure.tools.logging :as log])
  (:import [redis.clients.jedis Jedis
                                Protocol
                                JedisPool
                                JedisPoolConfig
                                ScanParams
                                ScanResult]
           [org.apache.commons.pool2.impl GenericObjectPool]))

(defn new-conn [^JedisPool pool]
  (.getResource pool))

(defn op [pool f]
  (with-open [^Jedis r (new-conn pool)]
    (f r)))

(defn create-pool
  ([]
   (create-pool {}))
  ([{:keys [host port pool timeout password]
     :or {host "127.0.0.1"
          port 6379
          timeout Protocol/DEFAULT_TIMEOUT
          pool {:size 42
                :max-wait 30000}}}]
   (let [conf (doto (JedisPoolConfig.)
                (.setMaxTotal (pool :size))
                (.setMaxWaitMillis (pool :max-wait)))]
     (log/info (str "connecting to Redis " host ":" port ", timeout: " timeout ", pool: " pool))
     (JedisPool. conf ^String host ^int port ^int timeout ^String password))))

(defn close-pool [pool]
  (log/info "disconnecting from Redis:" pool)
  (.destroy pool))

(defn pool-stats [pool]
  {:active-resources (.getNumActive pool)
   ; :max-total (.getMaxTotal pool)
   ; :max-wait-ms (.getMaxWaitMillis pool)
   ; :created-count (.getCreatedCount pool)
   ; :returned-count (.getReturnedCount pool)
   :number-of-waiters (.getNumWaiters pool)
   :idle-resources (.getNumIdle pool)})

;; wrap Java methods to make them composable

;; hash

(defn ^{:doc {:obiwan-doc
              "takes in a jedis connection pool, a hash name and a field name if present, returns a field name value"}}
      hget [^JedisPool redis h f]
  (op redis #(.hget % h f)))

(defn hmget [^JedisPool redis h fs]
  (into [] (op redis #(.hmget % h (into-array String fs)))))

(defn hgetall [^JedisPool redis h]
  (into {} (op redis #(.hgetAll % h))))

(defn hset [redis h f v]
  (op redis #(.hset % h f v)))

(defn hmset [redis h m]
  (op redis #(.hmset % h m)))

(defn hdel [redis h & v]
  (op redis #(.hdel % h (into-array String v))))

;; sorted set

(defn zadd [redis s m]
  (op redis #(.zadd % s m)))

(defn zrange [redis s zmin zmax]
  (op redis #(.zrange % s zmin zmax)))

;; set

(defn smembers [redis s]
  (op redis #(.smembers % s)))

(defn scard [redis s]
  (op redis #(.scard % s)))

(defn sismember [redis s v]
  (op redis #(.sismember % s v)))

(defn sadd [redis s v]
  (op redis #(.sadd % s (into-array
                          String [v]))))

(defn srem [redis s v]
  (op redis #(.srem % s (into-array
                         String [v]))))

;; scaning things

(defn new-scan-params [{:keys [fetch-size pattern]
                        :or {fetch-size 10}}]
  (doto (ScanParams.)
        (.count (int fetch-size))))

(defn sscan
  ([redis s cur]
   (sscan redis s cur {}))
  ([redis s cur params]
   (let [^ScanResult rs (op redis #(.sscan % s cur (new-scan-params params)))
         batch  (.getResult rs)
         cursor (.getCursor rs)]
     {:batch batch :cursor cursor :done? (.isCompleteIteration rs)})))


;; TODO: if/when needed generalize to scan, hscan, zscan
(defn scan-all
  "scan a whole set 's' and apply a function 'f' on each 'batch'"
  ([redis s f]
   (scan-all redis s f {}))
  ([redis s f params]
   (loop [cursor "0"
          rs (sscan redis s "0" params)]
     (let [{:keys [batch cursor done?]} rs]
       (f batch)
       (when-not done?
         (recur cursor (sscan redis s cursor params)))))))
