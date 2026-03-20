package org.Tyche.src.internal.Redis;

import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.JedisPoolConfig;

import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

public class RedisUtil {

    private static final JedisPool pool = new JedisPool(
            buildPoolConfig(), "localhost", 6379);

    private static JedisPoolConfig buildPoolConfig() {
        JedisPoolConfig config = new JedisPoolConfig();
        config.setMaxTotal(16);
        config.setMaxIdle(8);
        config.setMinIdle(2);
        config.setTestOnBorrow(true);
        return config;
    }

    // ── Key helper ────────────────────────────────────────────────
    // Produces keys like "Monitoring:Thread1"
    private static String key(String namespace, String field) {
        return namespace + ":" + field;
    }

    // ── Write ─────────────────────────────────────────────────────

    /** Set a single key in a namespace. */
    public static void set(String namespace, String field, String value) {
        try (Jedis jedis = pool.getResource()) {
            jedis.set(key(namespace, field), value);
        }
    }

    /** Set a single key with a TTL (in seconds). */
    public static void set(String namespace, String field, String value, int ttlSeconds) {
        try (Jedis jedis = pool.getResource()) {
            jedis.setex(key(namespace, field), ttlSeconds, value);
        }
    }

    /** Set multiple fields in a namespace at once. */
    public static void setAll(String namespace, Map<String, String> entries) {
        try (Jedis jedis = pool.getResource()) {
            for (Map.Entry<String, String> e : entries.entrySet()) {
                jedis.set(key(namespace, e.getKey()), e.getValue());
            }
        }
    }

    // ── Read ──────────────────────────────────────────────────────

    /** Get a single value, empty if missing. */
    public static Optional<String> get(String namespace, String field) {
        try (Jedis jedis = pool.getResource()) {
            return Optional.ofNullable(jedis.get(key(namespace, field)));
        }
    }

    /**
     * Get all field→value pairs in a namespace (via SCAN, safe for large datasets).
     */
    public static Map<String, String> getAll(String namespace) {
        try (Jedis jedis = pool.getResource()) {
            String pattern = namespace + ":*";
            int prefixLen = namespace.length() + 1; // strip "Namespace:"

            return jedis.keys(pattern).stream()
                    .collect(Collectors.toMap(
                            k -> k.substring(prefixLen), // field name only
                            k -> {
                                String v = jedis.get(k);
                                return v != null ? v : "";
                            }));
        }
    }

    // ── Delete ────────────────────────────────────────────────────

    /** Delete a single field from a namespace. */
    public static void delete(String namespace, String field) {
        try (Jedis jedis = pool.getResource()) {
            jedis.del(key(namespace, field));
        }
    }

    /** Delete ALL keys in a namespace. */
    public static void deleteAll(String namespace) {
        try (Jedis jedis = pool.getResource()) {
            String pattern = namespace + ":*";
            jedis.keys(pattern).forEach(jedis::del);
        }
    }

    // ── Existence ─────────────────────────────────────────────────

    public static boolean exists(String namespace, String field) {
        try (Jedis jedis = pool.getResource()) {
            return jedis.exists(key(namespace, field));
        }
    }

    // ── Lifecycle ─────────────────────────────────────────────────

    /** Call once on app shutdown. */
    public static void shutdown() {
        pool.close();
    }
}