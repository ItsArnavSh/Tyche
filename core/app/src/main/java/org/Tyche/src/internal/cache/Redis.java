package org.Tyche.src.internal.cache;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.Tyche.Config;
import org.Tyche.src.entity.Signal;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

/**
 * Thin, resilient Redis facade.
 *
 * <p>Redis is an observability/signal transport concern: a temporary outage
 * must never take the trading engine down.  Every operation is therefore
 * best-effort and failures are logged once (at warn level) rather than thrown.</p>
 */
public class Redis {

    private static final Logger logger = LoggerFactory.getLogger(Redis.class);

    private final JedisPool pool;
    private final String namespace;
    private final String signalQueue;
    private final boolean enabled;
    private volatile boolean warned = false;

    public Redis(String host, int port, String namespace) {
        this(host, port, namespace, Config.SIGNAL_QUEUE, Config.REDIS_ENABLED);
    }

    public Redis(String host, int port, String namespace, String signalQueue, boolean enabled) {
        this.namespace = namespace;
        this.signalQueue = signalQueue;
        this.enabled = enabled;
        JedisPoolConfig config = new JedisPoolConfig();
        config.setMaxTotal(16);
        config.setMaxIdle(8);
        config.setMinIdle(1);
        config.setTestOnBorrow(true);
        this.pool = new JedisPool(config, host, port);
    }

    private String key(String key) {
        return namespace + ":" + key;
    }

    private void warnOnce(String operation, Exception e) {
        if (!warned) {
            warned = true;
            logger.warn("Redis {} failed ({}). Continuing without Redis; further errors suppressed.",
                    operation, e.getMessage());
        } else {
            logger.debug("Redis {} failed: {}", operation, e.getMessage());
        }
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void set(String key, String value) {
        if (!enabled) {
            return;
        }
        try (Jedis jedis = pool.getResource()) {
            jedis.set(key(key), value);
        } catch (RuntimeException e) {
            warnOnce("SET " + key, e);
        }
    }

    public String get(String key) {
        if (!enabled) {
            return null;
        }
        try (Jedis jedis = pool.getResource()) {
            return jedis.get(key(key));
        } catch (RuntimeException e) {
            warnOnce("GET " + key, e);
            return null;
        }
    }

    public void del(String key) {
        if (!enabled) {
            return;
        }
        try (Jedis jedis = pool.getResource()) {
            jedis.del(key(key));
        } catch (RuntimeException e) {
            warnOnce("DEL " + key, e);
        }
    }

    public boolean exists(String key) {
        if (!enabled) {
            return false;
        }
        try (Jedis jedis = pool.getResource()) {
            return jedis.exists(key(key));
        } catch (RuntimeException e) {
            warnOnce("EXISTS " + key, e);
            return false;
        }
    }

    public long add(String key, long amount) {
        if (!enabled) {
            return -1;
        }
        try (Jedis jedis = pool.getResource()) {
            return jedis.incrBy(key(key), amount);
        } catch (RuntimeException e) {
            warnOnce("INCRBY " + key, e);
            return -1;
        }
    }

    /**
     * Publishes a trading signal to the raw queue consumed by the Go gateway.
     * The queue name is intentionally not namespaced (the gateway uses
     * {@code signal}); it is configurable through {@code TYCHE_SIGNAL_QUEUE}.
     */
    public boolean send_signal(Signal sig) {
        if (!enabled || sig == null) {
            return false;
        }
        try (Jedis jedis = pool.getResource()) {
            jedis.rpush(signalQueue, sig.toJson());
            return true;
        } catch (Exception e) {
            warnOnce("RPUSH " + signalQueue, e instanceof RuntimeException re ? re : new RuntimeException(e));
            return false;
        }
    }

    /** Returns true when the server answers PING. */
    public boolean ping() {
        if (!enabled) {
            return false;
        }
        try (Jedis jedis = pool.getResource()) {
            return "PONG".equalsIgnoreCase(jedis.ping());
        } catch (RuntimeException e) {
            return false;
        }
    }

    public void close() {
        try {
            pool.close();
        } catch (RuntimeException e) {
            logger.debug("Error while closing Redis pool", e);
        }
    }
}
