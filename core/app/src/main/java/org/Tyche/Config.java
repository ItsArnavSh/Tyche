package org.Tyche;

/**
 * Runtime configuration for the core engine.
 *
 * <p>Every value can be overridden with an environment variable so that the
 * same image works for local development, Docker Compose and production.</p>
 *
 * <p>The Java core does not own a market-data or broker integration. The Go
 * gateway owns that data plane and pushes everything the engine needs over
 * gRPC. This config therefore only covers engine scheduling, the cache, Redis
 * signal transport, and the gRPC listener.</p>
 */
public final class Config {

    private Config() {
    }

    // ── Redis ────────────────────────────────────────────────────────────
    public static final String REDIS_HOST = env("TYCHE_REDIS_HOST", env("REDIS_HOST", "localhost"));
    public static final int REDIS_PORT = envInt("TYCHE_REDIS_PORT", 6379);
    public static final String REDIS_NAMESPACE = env("TYCHE_REDIS_NAMESPACE", "tyche");
    public static final String SIGNAL_QUEUE = env("TYCHE_SIGNAL_QUEUE", "signal");
    public static final boolean REDIS_ENABLED = envBool("TYCHE_REDIS_ENABLED", true);

    // ── Engine ──────────────────────────────────────────────────────────
    public static final int GRPC_PORT = envInt("TYCHE_GRPC_PORT", 50051);
    public static final int CACHE_MAX_CANDLES = envInt("TYCHE_CACHE_MAX_CANDLES", 1500);
    public static final int WORKER_THREADS = envInt("TYCHE_WORKER_THREADS",
            Runtime.getRuntime().availableProcessors());
    public static final double STALE_MULTIPLIER = envDouble("TYCHE_STALE_MULTIPLIER", 2.0);

    // ── Signals ─────────────────────────────────────────────────────────
    /** Minimum time between two signals for the same strategy/ticker. */
    public static final long SIGNAL_COOLDOWN_MILLIS = envLong("TYCHE_SIGNAL_COOLDOWN_MS", 60_000L);

    // ── Helpers ─────────────────────────────────────────────────────────

    private static String env(String key, String fallback) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return value;
    }

    private static boolean envBool(String key, boolean fallback) {
        String value = System.getenv(key);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        return switch (value.trim().toLowerCase()) {
            case "1", "true", "yes", "on" -> true;
            case "0", "false", "no", "off" -> false;
            default -> fallback;
        };
    }

    private static int envInt(String key, int fallback) {
        try {
            String value = System.getenv(key);
            return value == null || value.isBlank() ? fallback : Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static long envLong(String key, long fallback) {
        try {
            String value = System.getenv(key);
            return value == null || value.isBlank() ? fallback : Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    private static double envDouble(String key, double fallback) {
        try {
            String value = System.getenv(key);
            return value == null || value.isBlank() ? fallback : Double.parseDouble(value.trim());
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
