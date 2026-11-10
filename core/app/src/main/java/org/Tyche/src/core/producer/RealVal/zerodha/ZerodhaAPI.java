package org.Tyche.src.core.producer.RealVal.zerodha;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.Tyche.Config;
import org.Tyche.src.core.producer.RealVal.StockAPI;
import org.Tyche.src.entity.Blocks.Candle;
import org.Tyche.src.entity.CandleSize;
import org.Tyche.src.entity.CoreAPI.BootRequest;
import org.Tyche.src.entity.CoreAPI.LatestStockVal;
import org.Tyche.src.entity.CoreAPI.RollRequest;
import org.Tyche.src.entity.CoreAPI.Series;
import org.Tyche.src.entity.CoreAPI.StockUpdate;
import org.Tyche.src.entity.CoreAPI.TickerHistory;
import org.Tyche.src.entity.Scheduler_Entity.PriorityBlock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Zerodha Kite Connect v3 producer.
 *
 * <p>Implemented endpoints:</p>
 * <ul>
 *   <li>{@code GET  /instruments} – instrument-token resolution (public)</li>
 *   <li>{@code GET  /instruments/historical/:token/:interval} – OHLC history</li>
 *   <li>{@code GET  /quote/ohlc} – batched latest prices/OHLC</li>
 *   <li>{@code GET  /quote/ltp} – batched last traded price</li>
 *   <li>{@code POST /session/token} – request-token → access-token exchange</li>
 *   <li>{@code POST /orders/:variety} – order placement</li>
 *   <li>{@code DELETE /orders/:variety/:order_id} – order cancellation</li>
 *   <li>{@code GET  /user/profile, /user/margins, /portfolio/positions, /orders}</li>
 * </ul>
 *
 * <p>Authentication uses {@code Authorization: token api_key:access_token} and
 * {@code X-Kite-Version: 3}.  Requests are throttled to respect Kite's
 * historical-data rate limit.</p>
 */
public class ZerodhaAPI extends StockAPI {
    private static final Logger logger = LoggerFactory.getLogger(ZerodhaAPI.class);
    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final DateTimeFormatter KITE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
    private static final long INSTRUMENT_TTL_MS = 12L * 60 * 60 * 1000;

    private final String baseUrl;
    private final String apiKey;
    private final String apiSecret;
    private final String accessToken;
    private final String defaultExchange;
    private final int retries;
    private final long minRequestIntervalMs;
    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();

    private final Map<String, String> instrumentTokens = new ConcurrentHashMap<>();
    private volatile long instrumentsLoadedAt = 0;
    private long lastRequestAt = 0;

    public ZerodhaAPI() {
        this(Config.KITE_BASE_URL, Config.KITE_API_KEY, Config.KITE_API_SECRET, Config.KITE_ACCESS_TOKEN,
                Config.KITE_DEFAULT_EXCHANGE, Config.HTTP_RETRIES, 350L);
        if (apiKey.isBlank() || accessToken.isBlank()) {
            throw new IllegalStateException(
                    "TYCHE_DATA_SOURCE=zerodha requires TYCHE_KITE_API_KEY and TYCHE_KITE_ACCESS_TOKEN");
        }
    }

    public ZerodhaAPI(String baseUrl, String apiKey, String apiSecret, String accessToken,
            String defaultExchange, int retries, long minRequestIntervalMs) {
        this.baseUrl = baseUrl == null || baseUrl.isBlank() ? "https://api.kite.trade"
                : baseUrl.replaceAll("/+$", "");
        this.apiKey = apiKey == null ? "" : apiKey.trim();
        this.apiSecret = apiSecret == null ? "" : apiSecret.trim();
        this.accessToken = accessToken == null ? "" : accessToken.trim();
        this.defaultExchange = defaultExchange == null || defaultExchange.isBlank() ? "NSE"
                : defaultExchange.trim().toUpperCase();
        this.retries = Math.max(1, retries);
        this.minRequestIntervalMs = Math.max(0, minRequestIntervalMs);
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    // ── Producer API ────────────────────────────────────────────────────

    @Override
    public BootRequest GetHistoricalData(ArrayList<PriorityBlock> stocks) {
        BootRequest request = new BootRequest();
        Map<String, Set<CandleSize>> grouped = group(stocks);
        for (var entry : grouped.entrySet()) {
            TickerHistory history = new TickerHistory(entry.getKey());
            for (CandleSize size : entry.getValue()) {
                try {
                    List<Candle> candles = fetchHistorical(entry.getKey(), size, Config.BOOT_CANDLE_COUNT);
                    if (candles.isEmpty()) {
                        logger.warn("Kite returned no history for {} {}", entry.getKey(), size);
                        continue;
                    }
                    Series series = new Series(size);
                    series.candles.addAll(candles);
                    history.series.add(series);
                } catch (Exception e) {
                    logger.warn("Kite history failed for {} {}: {}", entry.getKey(), size, e.getMessage());
                }
            }
            if (!history.series.isEmpty()) {
                request.history.add(history);
            }
        }
        return request;
    }

    @Override
    public RollRequest GetLatestVals(ArrayList<PriorityBlock> stocks) {
        RollRequest request = new RollRequest();
        Map<String, Set<CandleSize>> grouped = group(stocks);
        if (grouped.isEmpty()) {
            return request;
        }

        Map<String, Quote> quotes;
        try {
            quotes = fetchQuotes(new ArrayList<>(grouped.keySet()));
        } catch (Exception e) {
            logger.warn("Kite quote request failed: {}", e.getMessage());
            quotes = Map.of();
        }

        for (var entry : grouped.entrySet()) {
            String name = entry.getKey();
            Quote quote = quotes.get(instrumentKey(name));
            if (quote == null) {
                // Fall back to the most recent minute candle; this also keeps the
                // core from repeatedly requesting a boot for a transient quote error.
                try {
                    List<Candle> candles = fetchHistorical(name, CandleSize.min1, 2);
                    if (!candles.isEmpty()) {
                        Candle c = candles.get(candles.size() - 1);
                        quote = new Quote(c.open, c.high, c.low, c.close, c.close, c.volume);
                    }
                } catch (Exception e) {
                    logger.debug("Kite fallback history failed for {}: {}", name, e.getMessage());
                }
            }
            if (quote == null) {
                continue;
            }
            StockUpdate update = new StockUpdate(name);
            Candle candle = quote.toCandle();
            for (CandleSize size : entry.getValue()) {
                update.val.add(new LatestStockVal(candle, size));
            }
            request.update.add(update);
        }
        return request;
    }

    // ── Auth / account / orders ────────────────────────────────────────

    /** Login URL the user opens once to obtain a request token. */
    public String loginUrl() {
        return "https://kite.trade/connect/login?api_key=" + urlEncode(apiKey) + "&v=3";
    }

    /**
     * Exchanges a one-time request token for an access token.
     *
     * @return the access token (also usable with a new {@link ZerodhaAPI}).
     */
    public String generateSession(String requestToken) throws IOException, InterruptedException {
        if (requestToken == null || requestToken.isBlank()) {
            throw new IllegalArgumentException("requestToken is required");
        }
        if (apiSecret.isBlank()) {
            throw new IllegalStateException("TYCHE_KITE_API_SECRET is required for session generation");
        }
        String checksum = sha256(apiKey + requestToken + apiSecret);
        Map<String, String> form = new LinkedHashMap<>();
        form.put("api_key", apiKey);
        form.put("request_token", requestToken);
        form.put("checksum", checksum);
        JsonNode root = send("POST", "/session/token", form, false, false);
        JsonNode token = root.path("data").path("access_token");
        if (!token.isTextual() || token.asText().isBlank()) {
            throw new IOException("Kite session response did not contain an access token: " + root);
        }
        return token.asText();
    }

    public JsonNode profile() throws IOException, InterruptedException {
        return send("GET", "/user/profile", null, true, true).path("data");
    }

    public JsonNode margins() throws IOException, InterruptedException {
        return send("GET", "/user/margins", null, true, true).path("data");
    }

    public JsonNode positions() throws IOException, InterruptedException {
        return send("GET", "/portfolio/positions", null, true, true).path("data");
    }

    public JsonNode orders() throws IOException, InterruptedException {
        return send("GET", "/orders", null, true, true).path("data");
    }

    public record OrderRequest(
            String ticker,
            String exchange,
            String transactionType,
            String orderType,
            int quantity,
            String product,
            String validity,
            Double price,
            Double triggerPrice) {

        public static OrderRequest marketBuy(String ticker, int quantity) {
            return new OrderRequest(ticker, null, "BUY", "MARKET", quantity, "CNC", "DAY", null, null);
        }

        public static OrderRequest marketSell(String ticker, int quantity) {
            return new OrderRequest(ticker, null, "SELL", "MARKET", quantity, "CNC", "DAY", null, null);
        }
    }

    public String placeOrder(OrderRequest order) throws IOException, InterruptedException {
        return placeOrder("regular", order);
    }

    public String placeOrder(String variety, OrderRequest order) throws IOException, InterruptedException {
        if (order == null || order.ticker() == null || order.ticker().isBlank()) {
            throw new IllegalArgumentException("ticker is required");
        }
        if (order.quantity() <= 0) {
            throw new IllegalArgumentException("quantity must be positive");
        }
        Map<String, String> form = new LinkedHashMap<>();
        form.put("tradingsymbol", stripExchange(order.ticker()));
        form.put("exchange", order.exchange() == null ? defaultExchange : order.exchange());
        form.put("transaction_type", order.transactionType());
        form.put("order_type", order.orderType());
        form.put("quantity", Integer.toString(order.quantity()));
        form.put("product", order.product() == null ? "CNC" : order.product());
        form.put("validity", order.validity() == null ? "DAY" : order.validity());
        if (order.price() != null) {
            form.put("price", order.price().toString());
        }
        if (order.triggerPrice() != null) {
            form.put("trigger_price", order.triggerPrice().toString());
        }
        JsonNode root = send("POST", "/orders/" + urlEncode(variety), form, true, false);
        String orderId = root.path("data").path("order_id").asText("");
        if (orderId.isBlank()) {
            throw new IOException("Kite order response did not contain an order_id: " + root);
        }
        return orderId;
    }

    public void cancelOrder(String orderId) throws IOException, InterruptedException {
        cancelOrder("regular", orderId);
    }

    public void cancelOrder(String variety, String orderId) throws IOException, InterruptedException {
        if (orderId == null || orderId.isBlank()) {
            throw new IllegalArgumentException("orderId is required");
        }
        send("DELETE", "/orders/" + urlEncode(variety) + "/" + urlEncode(orderId), null, true, false);
    }

    // ── Historical / quote ─────────────────────────────────────────────

    public static String intervalFor(CandleSize size) {
        return switch (size) {
            case sec5, sec30, min1 -> "minute";
            case min15 -> "15minute";
            case hour1 -> "60minute";
        };
    }

    public List<Candle> fetchHistorical(String ticker, CandleSize size, int count)
            throws IOException, InterruptedException {
        String token = instrumentToken(ticker);
        String interval = intervalFor(size);
        int limit = Math.max(1, Math.min(count, Config.KITE_HISTORY_CANDLE_LIMIT));
        long stepSeconds = switch (size) {
            case sec5, sec30, min1 -> 60;
            case min15 -> 15 * 60;
            case hour1 -> 60 * 60;
        };
        ZonedDateTime to = ZonedDateTime.now(IST);
        ZonedDateTime from = to.minusSeconds(stepSeconds * (limit + 5L));
        // Kite returns at most 2000 candles per call and only allows recent
        // minute data for a limited window.
        String query = "?from=" + urlEncode(from.format(KITE_TIME))
                + "&to=" + urlEncode(to.format(KITE_TIME))
                + "&continuous=0&oi=0";
        JsonNode root = send("GET", "/instruments/historical/" + urlEncode(token) + "/" + interval + query,
                null, true, true);
        JsonNode candlesNode = root.path("data").path("candles");
        ArrayList<Candle> candles = new ArrayList<>();
        if (candlesNode.isArray()) {
            for (JsonNode row : candlesNode) {
                if (!row.isArray() || row.size() < 5) {
                    continue;
                }
                double open = row.get(1).asDouble();
                double high = row.get(2).asDouble();
                double low = row.get(3).asDouble();
                double close = row.get(4).asDouble();
                double volume = row.size() > 5 ? row.get(5).asDouble() : 0;
                if (validOhlc(open, high, low, close)) {
                    candles.add(new Candle(open, high, low, close, (long) Math.max(0, volume)));
                }
            }
        }
        if (candles.size() > limit) {
            return new ArrayList<>(candles.subList(candles.size() - limit, candles.size()));
        }
        return candles;
    }

    public Map<String, Quote> fetchQuotes(List<String> tickers) throws IOException, InterruptedException {
        StringBuilder query = new StringBuilder();
        for (String ticker : new LinkedHashSet<>(tickers)) {
            if (query.length() > 0) {
                query.append('&');
            }
            query.append("i=").append(urlEncode(instrumentKey(ticker)));
        }
        JsonNode root = send("GET", "/quote/ohlc?" + query, null, true, true);
        JsonNode data = root.path("data");
        Map<String, Quote> out = new HashMap<>();
        if (data.isObject()) {
            data.fields().forEachRemaining(entry -> {
                JsonNode node = entry.getValue();
                JsonNode ohlc = node.path("ohlc");
                double last = node.path("last_price").asDouble(0);
                double open = ohlc.path("open").asDouble(0);
                double high = ohlc.path("high").asDouble(0);
                double low = ohlc.path("low").asDouble(0);
                double close = ohlc.path("close").asDouble(0);
                double price = last > 0 ? last : close;
                if (price > 0) {
                    out.put(entry.getKey(), new Quote(open > 0 ? open : price, high > 0 ? high : price,
                            low > 0 ? low : price, close > 0 ? close : price, price, 0));
                }
            });
        }
        return out;
    }

    public record Quote(double open, double high, double low, double close, double last, double volume) {
        Candle toCandle() {
            double adjustedHigh = Math.max(Math.max(high, open), Math.max(close, last));
            double adjustedLow = Math.min(Math.min(low, open), Math.min(close, last));
            return new Candle(open, adjustedHigh, adjustedLow, last, (long) Math.max(0, volume));
        }
    }

    // ── Instrument resolution ──────────────────────────────────────────

    public String instrumentToken(String ticker) throws IOException, InterruptedException {
        ensureInstruments();
        String key = instrumentKey(ticker);
        String token = instrumentTokens.get(key);
        if (token == null) {
            throw new IOException("Unknown Kite instrument: " + key);
        }
        return token;
    }

    private String instrumentKey(String ticker) {
        String cleaned = ticker == null ? "" : ticker.trim();
        int colon = cleaned.indexOf(':');
        if (colon >= 0) {
            return cleaned.substring(0, colon).toUpperCase() + ":" + cleaned.substring(colon + 1).toUpperCase();
        }
        return defaultExchange + ":" + cleaned.toUpperCase();
    }

    private String stripExchange(String ticker) {
        String cleaned = ticker == null ? "" : ticker.trim();
        int colon = cleaned.indexOf(':');
        return colon >= 0 ? cleaned.substring(colon + 1) : cleaned;
    }

    private synchronized void ensureInstruments() throws IOException, InterruptedException {
        long now = System.currentTimeMillis();
        if (!instrumentTokens.isEmpty() && now - instrumentsLoadedAt < INSTRUMENT_TTL_MS) {
            return;
        }
        try {
            // /instruments is a CSV endpoint, not JSON; use the raw sender.
            String csv = sendRaw("GET", "/instruments", null, false, true);
            parseInstruments(csv);
            instrumentsLoadedAt = now;
            logger.info("Loaded {} Kite instruments", instrumentTokens.size());
        } catch (Exception e) {
            if (instrumentTokens.isEmpty()) {
                throw e instanceof IOException io ? io : new IOException(e);
            }
            logger.warn("Could not refresh Kite instruments; using cached set: {}", e.getMessage());
        }
    }

    private void parseInstruments(String csv) throws IOException {
        if (csv == null || csv.isBlank()) {
            throw new IOException("empty instrument dump");
        }
        try (BufferedReader reader = new BufferedReader(new StringReader(csv))) {
            String header = reader.readLine();
            if (header == null) {
                return;
            }
            List<String> columns = parseCsvLine(header);
            int tokenIdx = columns.indexOf("instrument_token");
            int symbolIdx = columns.indexOf("tradingsymbol");
            int exchangeIdx = columns.indexOf("exchange");
            int typeIdx = columns.indexOf("instrument_type");
            if (tokenIdx < 0 || symbolIdx < 0 || exchangeIdx < 0) {
                throw new IOException("unexpected instrument CSV header: " + header);
            }
            String line;
            while ((line = reader.readLine()) != null) {
                List<String> row = parseCsvLine(line);
                if (row.size() <= Math.max(tokenIdx, Math.max(symbolIdx, exchangeIdx))) {
                    continue;
                }
                String type = typeIdx >= 0 && typeIdx < row.size() ? row.get(typeIdx) : "EQ";
                if (!"EQ".equalsIgnoreCase(type)) {
                    continue;
                }
                instrumentTokens.put(row.get(exchangeIdx).toUpperCase() + ":" + row.get(symbolIdx).toUpperCase(),
                        row.get(tokenIdx));
            }
        }
    }

    /** Minimal RFC-4180-ish CSV parser (quoted fields, doubled quotes). */
    static List<String> parseCsvLine(String line) {
        List<String> values = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        current.append('"');
                        i++;
                    } else {
                        quoted = false;
                    }
                } else {
                    current.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                values.add(current.toString());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        values.add(current.toString());
        return values;
    }

    // ── HTTP plumbing ──────────────────────────────────────────────────

    private JsonNode send(String method, String path, Map<String, String> form, boolean auth, boolean retry)
            throws IOException, InterruptedException {
        String body = sendRaw(method, path, form, auth, retry);
        try {
            return mapper.readTree(body == null || body.isBlank() ? "{}" : body);
        } catch (IOException e) {
            throw new IOException("Kite returned non-JSON response: " + abbreviate(body), e);
        }
    }

    private String sendRaw(String method, String path, Map<String, String> form, boolean auth, boolean retry)
            throws IOException, InterruptedException {
        IOException last = null;
        int attempts = retry ? retries : 1;
        for (int attempt = 0; attempt < attempts; attempt++) {
            throttle();
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(baseUrl + path))
                    .timeout(Duration.ofSeconds(20))
                    .header("User-Agent", "Tyche/1.0")
                    .header("Accept", "application/json")
                    .header("X-Kite-Version", "3");
            if (auth) {
                builder.header("Authorization", "token " + apiKey + ":" + accessToken);
            }
            if (form != null) {
                builder.header("Content-Type", "application/x-www-form-urlencoded");
            }
            if ("POST".equals(method)) {
                builder.POST(HttpRequest.BodyPublishers.ofString(encodeForm(form)));
            } else if ("DELETE".equals(method)) {
                builder.DELETE();
            } else if ("PUT".equals(method)) {
                builder.PUT(HttpRequest.BodyPublishers.ofString(encodeForm(form)));
            } else {
                builder.GET();
            }
            try {
                HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
                int status = response.statusCode();
                String body = response.body();
                if (status >= 200 && status < 300) {
                    return body;
                }
                if (status == 429 || status >= 500) {
                    last = new IOException("Kite HTTP " + status + ": " + abbreviate(body));
                } else {
                    throw new KiteException(status, abbreviate(body));
                }
            } catch (IOException e) {
                last = e;
            }
            if (attempt + 1 < attempts) {
                Thread.sleep(150L * (attempt + 1));
            }
        }
        throw last == null ? new IOException("Kite request failed") : last;
    }

    private synchronized void throttle() throws InterruptedException {
        if (minRequestIntervalMs <= 0) {
            return;
        }
        long now = System.currentTimeMillis();
        long wait = lastRequestAt + minRequestIntervalMs - now;
        if (wait > 0) {
            Thread.sleep(wait);
        }
        lastRequestAt = System.currentTimeMillis();
    }

    private static String encodeForm(Map<String, String> form) {
        if (form == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (var entry : form.entrySet()) {
            if (sb.length() > 0) {
                sb.append('&');
            }
            sb.append(urlEncode(entry.getKey())).append('=').append(urlEncode(entry.getValue()));
        }
        return sb.toString();
    }

    private static String urlEncode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(value.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(bytes.length * 2);
            for (byte b : bytes) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static boolean validOhlc(double open, double high, double low, double close) {
        return Double.isFinite(open) && Double.isFinite(high) && Double.isFinite(low) && Double.isFinite(close)
                && open > 0 && close > 0 && high > 0 && low > 0
                && high >= Math.max(open, close) && low <= Math.min(open, close) && high >= low;
    }

    private static String abbreviate(String value) {
        if (value == null) {
            return "";
        }
        return value.length() > 300 ? value.substring(0, 300) : value;
    }

    private static Map<String, Set<CandleSize>> group(ArrayList<PriorityBlock> stocks) {
        Map<String, Set<CandleSize>> grouped = new LinkedHashMap<>();
        if (stocks == null) {
            return grouped;
        }
        for (PriorityBlock block : stocks) {
            if (block != null) {
                grouped.computeIfAbsent(block.name, k -> new LinkedHashSet<>()).add(block.size);
            }
        }
        return grouped;
    }

    /** Raised for non-retryable Kite 4xx responses. */
    public static class KiteException extends IOException {
        private final int status;

        public KiteException(int status, String body) {
            super("Kite HTTP " + status + ": " + body);
            this.status = status;
        }

        public int status() {
            return status;
        }
    }
}
