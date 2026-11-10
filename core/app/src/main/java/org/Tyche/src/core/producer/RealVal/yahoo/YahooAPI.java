package org.Tyche.src.core.producer.RealVal.yahoo;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
 * Yahoo Finance chart-API producer.
 *
 * <p>Uses the public {@code /v8/finance/chart} endpoint (the older
 * {@code /v7/quote} endpoint now requires a crumb/cookie and is avoided).
 * NSE tickers are automatically suffixed with {@code .NS}.</p>
 */
public class YahooAPI extends StockAPI {
    private static final Logger logger = LoggerFactory.getLogger(YahooAPI.class);

    private final String baseUrl;
    private final String suffix;
    private final int retries;
    private final HttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();

    public YahooAPI() {
        this(Config.YAHOO_BASE_URL, Config.YAHOO_TIMEOUT_MS, Config.HTTP_RETRIES, Config.YAHOO_SUFFIX);
    }

    public YahooAPI(String baseUrl, int timeoutMs, int retries, String suffix) {
        this.baseUrl = trimTrailingSlash(baseUrl);
        this.suffix = suffix == null ? "" : suffix.trim();
        this.retries = Math.max(1, retries);
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(Math.max(1000, timeoutMs)))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    private static String trimTrailingSlash(String value) {
        if (value == null || value.isBlank()) {
            return "https://query1.finance.yahoo.com";
        }
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    @Override
    public BootRequest GetHistoricalData(ArrayList<PriorityBlock> stocks) {
        BootRequest request = new BootRequest();
        Map<String, Set<CandleSize>> grouped = group(stocks);

        for (var entry : grouped.entrySet()) {
            String name = entry.getKey();
            Map<String, List<Candle>> byInterval = new HashMap<>();
            TickerHistory history = new TickerHistory(name);
            for (CandleSize size : entry.getValue()) {
                String interval = intervalFor(size);
                try {
                    List<Candle> candles = byInterval.get(interval);
                    if (candles == null) {
                        candles = fetchCandles(name, interval, rangeFor(size, Config.BOOT_CANDLE_COUNT));
                        byInterval.put(interval, candles);
                    }
                    if (candles.isEmpty()) {
                        logger.warn("Yahoo returned no historical candles for {} ({} / {})", name, size, interval);
                        continue;
                    }
                    List<Candle> tail = candles.size() > Config.BOOT_CANDLE_COUNT
                            ? candles.subList(candles.size() - Config.BOOT_CANDLE_COUNT, candles.size())
                            : candles;
                    Series series = new Series(size);
                    series.candles.addAll(tail);
                    history.series.add(series);
                } catch (Exception e) {
                    logger.warn("Yahoo historical fetch failed for {} {}: {}", name, size, e.getMessage());
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

        for (var entry : grouped.entrySet()) {
            String name = entry.getKey();
            Map<String, Latest> latestByInterval = new HashMap<>();
            StockUpdate update = new StockUpdate(name);
            for (CandleSize size : entry.getValue()) {
                String interval = intervalFor(size);
                try {
                    Latest latest = latestByInterval.get(interval);
                    if (latest == null && !latestByInterval.containsKey(interval)) {
                        latest = fetchLatest(name, interval);
                        latestByInterval.put(interval, latest);
                    }
                    if (latest == null) {
                        continue;
                    }
                    update.val.add(new LatestStockVal(latest.candle, size));
                } catch (Exception e) {
                    logger.warn("Yahoo latest fetch failed for {} {}: {}", name, size, e.getMessage());
                }
            }
            if (!update.val.isEmpty()) {
                request.update.add(update);
            }
        }
        return request;
    }

    private Map<String, Set<CandleSize>> group(ArrayList<PriorityBlock> stocks) {
        Map<String, Set<CandleSize>> grouped = new LinkedHashMap<>();
        if (stocks == null) {
            return grouped;
        }
        for (PriorityBlock block : stocks) {
            if (block == null) {
                continue;
            }
            grouped.computeIfAbsent(block.name, k -> new LinkedHashSet<>()).add(block.size);
        }
        return grouped;
    }

    static String intervalFor(CandleSize size) {
        return switch (size) {
            case sec5, sec30, min1 -> "1m";
            case min15 -> "15m";
            case hour1 -> "60m";
        };
    }

    static String rangeFor(CandleSize size, int candleCount) {
        return switch (size) {
            case sec5, sec30, min1 -> "7d";
            case min15 -> candleCount > 500 ? "3mo" : "1mo";
            case hour1 -> candleCount > 500 ? "2y" : "3mo";
        };
    }

    /** Visible for testing. */
    String yahooSymbol(String name) {
        if (name == null || name.isBlank()) {
            return "";
        }
        String symbol = name.trim();
        int colon = symbol.indexOf(':');
        if (colon >= 0) {
            symbol = symbol.substring(colon + 1);
        }
        if (symbol.startsWith("^") || symbol.contains(".") || suffix.isEmpty()) {
            return symbol;
        }
        return symbol + suffix;
    }

    private URI chartUri(String symbol, String interval, String range) {
        String encoded = URLEncoder.encode(yahooSymbol(symbol), StandardCharsets.UTF_8).replace("+", "%20");
        return URI.create(baseUrl + "/v8/finance/chart/" + encoded
                + "?interval=" + interval + "&range=" + range
                + "&includePrePost=false&events=div%2Csplits");
    }

    private JsonNode getJson(URI uri) throws IOException, InterruptedException {
        IOException last = null;
        for (int attempt = 0; attempt < retries; attempt++) {
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(Duration.ofMillis(Config.YAHOO_TIMEOUT_MS))
                    .header("User-Agent", "Mozilla/5.0 (X11; Linux x86_64) Tyche/1.0")
                    .header("Accept", "application/json")
                    .GET()
                    .build();
            try {
                HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
                int status = response.statusCode();
                if (status >= 200 && status < 300) {
                    JsonNode root = mapper.readTree(response.body());
                    JsonNode error = root.path("chart").path("error");
                    if (!error.isMissingNode() && !error.isNull()) {
                        throw new IOException("Yahoo API error: " + error.path("description").asText(error.toString()));
                    }
                    return root;
                }
                if (status == 429 || status >= 500) {
                    last = new IOException("Yahoo HTTP " + status);
                } else {
                    throw new IOException("Yahoo HTTP " + status + ": " + abbreviate(response.body()));
                }
            } catch (IOException e) {
                last = e;
            }
            if (attempt + 1 < retries) {
                try {
                    Thread.sleep(100L * (attempt + 1));
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw e;
                }
            }
        }
        throw last == null ? new IOException("Yahoo request failed") : last;
    }

    private static String abbreviate(String value) {
        if (value == null) {
            return "";
        }
        return value.length() > 200 ? value.substring(0, 200) : value;
    }

    List<Candle> fetchCandles(String symbol, String interval, String range)
            throws IOException, InterruptedException {
        JsonNode root = getJson(chartUri(symbol, interval, range));
        JsonNode result = root.path("chart").path("result");
        if (!result.isArray() || result.size() == 0) {
            return List.of();
        }
        JsonNode node = result.get(0);
        JsonNode timestamps = node.path("timestamp");
        JsonNode quote = node.path("indicators").path("quote");
        if (!timestamps.isArray() || !quote.isArray() || quote.size() == 0) {
            return List.of();
        }
        JsonNode q = quote.get(0);
        JsonNode opens = q.path("open");
        JsonNode highs = q.path("high");
        JsonNode lows = q.path("low");
        JsonNode closes = q.path("close");
        JsonNode volumes = q.path("volume");

        ArrayList<Candle> candles = new ArrayList<>(timestamps.size());
        int n = timestamps.size();
        for (int i = 0; i < n; i++) {
            JsonNode o = opens.path(i);
            JsonNode h = highs.path(i);
            JsonNode l = lows.path(i);
            JsonNode c = closes.path(i);
            if (o.isMissingNode() || h.isMissingNode() || l.isMissingNode() || c.isMissingNode()
                    || o.isNull() || h.isNull() || l.isNull() || c.isNull()) {
                continue;
            }
            double open = o.asDouble();
            double high = h.asDouble();
            double low = l.asDouble();
            double close = c.asDouble();
            if (!validOhlc(open, high, low, close)) {
                continue;
            }
            JsonNode v = volumes.path(i);
            double volume = v.isMissingNode() || v.isNull() ? 0 : v.asDouble();
            candles.add(new Candle(open, high, low, close, (long) Math.max(0, volume)));
        }
        return candles;
    }

    private static boolean validOhlc(double open, double high, double low, double close) {
        return Double.isFinite(open) && Double.isFinite(high) && Double.isFinite(low) && Double.isFinite(close)
                && open > 0 && close > 0 && high > 0 && low > 0
                && high >= Math.max(open, close) && low <= Math.min(open, close) && high >= low;
    }

    record Latest(Candle candle) {
    }

    Latest fetchLatest(String symbol, String interval) throws IOException, InterruptedException {
        JsonNode root = getJson(chartUri(symbol, interval, "1d"));
        JsonNode result = root.path("chart").path("result");
        if (!result.isArray() || result.isEmpty()) {
            return null;
        }
        JsonNode node = result.get(0);
        double marketPrice = node.path("meta").path("regularMarketPrice").asDouble(0);
        List<Candle> candles = parseCandles(node);
        if (candles.isEmpty()) {
            return null;
        }
        Candle last = candles.get(candles.size() - 1);
        double close = marketPrice > 0 ? marketPrice : last.close;
        Candle adjusted = new Candle(last.open, Math.max(last.high, close), Math.min(last.low, close),
                close, (long) Math.max(0, last.volume));
        return new Latest(adjusted);
    }

    private List<Candle> parseCandles(JsonNode node) {
        JsonNode timestamps = node.path("timestamp");
        JsonNode quote = node.path("indicators").path("quote");
        if (!timestamps.isArray() || !quote.isArray() || quote.size() == 0) {
            return List.of();
        }
        JsonNode q = quote.get(0);
        ArrayList<Candle> candles = new ArrayList<>();
        for (int i = 0; i < timestamps.size(); i++) {
            JsonNode o = q.path("open").path(i);
            JsonNode h = q.path("high").path(i);
            JsonNode l = q.path("low").path(i);
            JsonNode c = q.path("close").path(i);
            if (o.isNull() || h.isNull() || l.isNull() || c.isNull()) {
                continue;
            }
            double open = o.asDouble();
            double high = h.asDouble();
            double low = l.asDouble();
            double close = c.asDouble();
            if (!validOhlc(open, high, low, close)) {
                continue;
            }
            JsonNode v = q.path("volume").path(i);
            double volume = v.isNull() || v.isMissingNode() ? 0 : v.asDouble();
            candles.add(new Candle(open, high, low, close, (long) Math.max(0, volume)));
        }
        return candles;
    }
}
