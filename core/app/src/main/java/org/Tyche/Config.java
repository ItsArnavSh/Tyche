package org.Tyche;

/**
 * Runtime configuration for the core engine.
 *
 * <p>Every value can be overridden with an environment variable so that the
 * same image works for local development, Docker Compose and production.</p>
 */
public final class Config {

    private Config() {
    }

    /** When true the engine adds artificial boot latency (useful for demos). */
    public static final boolean SIMULATION_MODE = envBool("TYCHE_SIMULATION_MODE", false);

    /** fake | yahoo | zerodha */
    public static final String DATA_SOURCE = env("TYCHE_DATA_SOURCE", "yahoo").trim().toLowerCase();

    // ── Redis ────────────────────────────────────────────────────────────
    public static final String REDIS_HOST = env("TYCHE_REDIS_HOST", env("REDIS_HOST", "localhost"));
    public static final int REDIS_PORT = envInt("TYCHE_REDIS_PORT", 6379);
    public static final String REDIS_NAMESPACE = env("TYCHE_REDIS_NAMESPACE", "tyche");
    public static final String SIGNAL_QUEUE = env("TYCHE_SIGNAL_QUEUE", "signal");
    public static final boolean REDIS_ENABLED = envBool("TYCHE_REDIS_ENABLED", true);

    // ── Engine ──────────────────────────────────────────────────────────
    public static final int GRPC_PORT = envInt("TYCHE_GRPC_PORT", 50051);
    public static final int CACHE_MAX_CANDLES = envInt("TYCHE_CACHE_MAX_CANDLES", 1500);
    public static final int BOOT_CANDLE_COUNT = envInt("TYCHE_BOOT_CANDLE_COUNT",
            envInt("TYCHE_BOOT_CANDLES", 300));
    public static final int WORKER_THREADS = envInt("TYCHE_WORKER_THREADS",
            Runtime.getRuntime().availableProcessors());
    public static final double STALE_MULTIPLIER = envDouble("TYCHE_STALE_MULTIPLIER", 2.0);

    // ── Yahoo Finance ───────────────────────────────────────────────────
    public static final String YAHOO_BASE_URL = env("TYCHE_YAHOO_BASE_URL",
            "https://query1.finance.yahoo.com");
    public static final String YAHOO_SUFFIX = env("TYCHE_YAHOO_SUFFIX", ".NS");
    public static final int YAHOO_TIMEOUT_MS = envInt("TYCHE_YAHOO_TIMEOUT_MS", 15_000);
    public static final int HTTP_RETRIES = envInt("TYCHE_HTTP_RETRIES", 3);

    // ── Zerodha Kite Connect ────────────────────────────────────────────
    public static final String KITE_BASE_URL = env("TYCHE_KITE_BASE_URL", "https://api.kite.trade");
    public static final String KITE_API_KEY = env("TYCHE_KITE_API_KEY", env("KITE_API_KEY", ""));
    public static final String KITE_API_SECRET = env("TYCHE_KITE_API_SECRET", env("KITE_API_SECRET", ""));
    public static final String KITE_ACCESS_TOKEN = env("TYCHE_KITE_ACCESS_TOKEN", env("KITE_ACCESS_TOKEN", ""));
    /** Exchange prefix used when a bare symbol is supplied (NSE, BSE, ...). */
    public static final String KITE_DEFAULT_EXCHANGE = env("TYCHE_KITE_DEFAULT_EXCHANGE", "NSE");
    public static final int KITE_HISTORY_CANDLE_LIMIT = envInt("TYCHE_KITE_HISTORY_LIMIT", 2000);

    // ── Instrument universe ─────────────────────────────────────────────
    public static final String DEFAULT_SYMBOLS = env("TYCHE_SYMBOLS",
            "RELIANCE.NS,TCS.NS,HDFCBANK.NS,INFY.NS,ICICIBANK.NS,SBIN.NS,ITC.NS,LT.NS,"
                    + "BHARTIARTL.NS,HINDUNILVR.NS");

    // ── Signals ─────────────────────────────────────────────────────────
    /** Minimum time between two signals for the same strategy/ticker. */
    public static final long SIGNAL_COOLDOWN_MILLIS = envLong("TYCHE_SIGNAL_COOLDOWN_MS", 60_000L);

    public static String[] symbols() {
        String[] raw = DEFAULT_SYMBOLS.split(",");
        java.util.ArrayList<String> out = new java.util.ArrayList<>();
        for (String s : raw) {
            String trimmed = s.trim();
            if (!trimmed.isEmpty()) {
                out.add(trimmed);
            }
        }
        return out.toArray(String[]::new);
    }

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
