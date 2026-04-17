package org.Tyche.src.internal.cache;

public class Repository {
    public Cache cache;
    public Redis redis;

    public Repository() {
        this.cache = new Cache();
        this.redis = new Redis("localhost", 6379, "tyche");
    }
}
