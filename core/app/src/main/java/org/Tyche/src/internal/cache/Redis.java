package org.Tyche.src.internal.cache;

import java.util.concurrent.Exchanger;

import org.Tyche.src.entity.Signal;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

public class Redis {

    private final JedisPool pool;
    private final String namespace;

    public Redis(String host, int port, String namespace) {
        this.pool = new JedisPool(new JedisPoolConfig(), host, port);
        this.namespace = namespace;
    }

    private String key(String key) {
        return namespace + ":" + key;
    }

    public void set(String key, String value) {
        try (Jedis jedis = pool.getResource()) {
            jedis.set(key(key), value);
        }
    }

    public String get(String key) {
        try (Jedis jedis = pool.getResource()) {
            return jedis.get(key(key));
        }
    }

    public void del(String key) {
        try (Jedis jedis = pool.getResource()) {
            jedis.del(key(key));
        }
    }

    public boolean exists(String key) {
        try (Jedis jedis = pool.getResource()) {
            return jedis.exists(key(key));
        }
    }

    public void close() {
        pool.close();
    }

    public long add(String key, long amount) {
        try (Jedis jedis = pool.getResource()) {
            return jedis.incrBy(key(key), amount);
        }
    }

    public void send_signal(Signal sig) {
        try (Jedis jedis = pool.getResource()) {
            try {

                jedis.rpush("signal", sig.toJson());
            } catch (Exception e) {
            }
        }
    }
}