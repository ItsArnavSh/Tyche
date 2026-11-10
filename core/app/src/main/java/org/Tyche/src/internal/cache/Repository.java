package org.Tyche.src.internal.cache;

import org.Tyche.Config;

public class Repository {

    public final Cache cache;
    public final Redis redis;

    public Repository() {
        this(new Cache(Config.CACHE_MAX_CANDLES),
                new Redis(Config.REDIS_HOST, Config.REDIS_PORT, Config.REDIS_NAMESPACE));
    }

    public Repository(Cache cache, Redis redis) {
        this.cache = cache;
        this.redis = redis;
    }

    /** Convenience constructor for tests / offline runs. */
    public Repository(boolean redisEnabled) {
        this(new Cache(Config.CACHE_MAX_CANDLES),
                new Redis(Config.REDIS_HOST, Config.REDIS_PORT, Config.REDIS_NAMESPACE,
                        Config.SIGNAL_QUEUE, redisEnabled));
    }
}
