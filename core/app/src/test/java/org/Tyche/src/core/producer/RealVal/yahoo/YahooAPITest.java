package org.Tyche.src.core.producer.RealVal.yahoo;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.Tyche.src.entity.Blocks.Candle;
import org.Tyche.src.entity.CandleSize;
import org.Tyche.src.entity.CoreAPI.BootRequest;
import org.Tyche.src.entity.CoreAPI.RollRequest;
import org.Tyche.src.entity.Scheduler_Entity.PriorityBlock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

class YahooAPITest {

    private static final String CHART_JSON = """
            {
              "chart": {
                "result": [
                  {
                    "meta": { "regularMarketPrice": 123.5 },
                    "timestamp": [1, 2, 3, 4, 5],
                    "indicators": {
                      "quote": [
                        {
                          "open":   [100, 101, 102, null, 103],
                          "high":   [105, 106, 107, 108, 0],
                          "low":    [99, 100, 101, 0, 98],
                          "close":  [104, 105, 106, 107, 99],
                          "volume": [1000, 2000, 3000, 4000, 5000]
                        }
                      ]
                    }
                  }
                ],
                "error": null
              }
            }
            """;

    private HttpServer server;
    private final AtomicReference<HttpHandler> handler = new AtomicReference<>();
    private final AtomicInteger requests = new AtomicInteger();
    private final AtomicReference<String> lastQuery = new AtomicReference<>("");
    private YahooAPI api;

    @FunctionalInterface
    private interface HttpHandler {
        void handle(HttpExchange exchange) throws IOException;
    }

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            requests.incrementAndGet();
            lastQuery.set(String.valueOf(exchange.getRequestURI().getQuery()));
            HttpHandler current = handler.get();
            if (current == null) {
                respond(exchange, 500, "no handler");
            } else {
                current.handle(exchange);
            }
        });
        server.start();
        api = new YahooAPI("http://127.0.0.1:" + server.getAddress().getPort(), 2_000, 1, ".NS");
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static void respondJson(HttpExchange exchange, String body) throws IOException {
        respond(exchange, 200, body);
    }

    @Test
    void yahooSymbolSuffixesOnlyBareNseTickers() {
        assertEquals("RELIANCE.NS", api.yahooSymbol("RELIANCE"));
        assertEquals("RELIANCE.NS", api.yahooSymbol("RELIANCE.NS"));
        assertEquals("TCS.NS", api.yahooSymbol("NSE:TCS"));
        assertEquals("^NSEI", api.yahooSymbol("^NSEI"));
        assertEquals("", api.yahooSymbol(null));
        assertEquals("", api.yahooSymbol("   "));

        YahooAPI noSuffix = new YahooAPI("http://localhost", 1_000, 1, "");
        assertEquals("TCS", noSuffix.yahooSymbol("TCS"));
    }

    @Test
    void intervalAndRangeMappingsAreStable() {
        assertEquals("1m", YahooAPI.intervalFor(CandleSize.sec5));
        assertEquals("1m", YahooAPI.intervalFor(CandleSize.sec30));
        assertEquals("1m", YahooAPI.intervalFor(CandleSize.min1));
        assertEquals("15m", YahooAPI.intervalFor(CandleSize.min15));
        assertEquals("60m", YahooAPI.intervalFor(CandleSize.hour1));

        assertEquals("7d", YahooAPI.rangeFor(CandleSize.min1, 300));
        assertEquals("1mo", YahooAPI.rangeFor(CandleSize.min15, 300));
        assertEquals("3mo", YahooAPI.rangeFor(CandleSize.min15, 1000));
        assertEquals("3mo", YahooAPI.rangeFor(CandleSize.hour1, 300));
        assertEquals("2y", YahooAPI.rangeFor(CandleSize.hour1, 1000));
    }

    @Test
    void fetchCandlesParsesValidRowsAndSkipsGaps() throws Exception {
        handler.set(exchange -> respondJson(exchange, CHART_JSON));

        List<Candle> candles = api.fetchCandles("RELIANCE", "1m", "7d");

        assertEquals(3, candles.size());
        assertEquals(100, candles.get(0).open, 1e-9);
        assertEquals(104, candles.get(0).close, 1e-9);
        assertEquals(1000, candles.get(0).volume, 1e-9);
        assertEquals(106, candles.get(2).close, 1e-9);
        assertEquals(107, candles.get(2).high, 1e-9);
        assertEquals(101, candles.get(2).low, 1e-9);
        assertTrue(lastQuery.get().contains("interval=1m"));
        assertTrue(lastQuery.get().contains("range=7d"));
    }

    @Test
    void fetchLatestUsesRegularMarketPrice() throws Exception {
        handler.set(exchange -> respondJson(exchange, CHART_JSON));

        var latest = api.fetchLatest("RELIANCE", "1m");

        assertNotNull(latest);
        Candle candle = latest.candle();
        assertEquals(123.5, candle.close, 1e-9);
        assertTrue(candle.high >= 123.5);
        assertTrue(candle.low <= 123.5);
        assertEquals(3000, candle.volume, 1e-9);
    }

    @Test
    void historicalBootstrapGroupsSizesSharingAnInterval() {
        handler.set(exchange -> respondJson(exchange, CHART_JSON));
        ArrayList<PriorityBlock> blocks = new ArrayList<>(List.of(
                new PriorityBlock(CandleSize.min1, "RELIANCE"),
                new PriorityBlock(CandleSize.sec5, "RELIANCE")));

        BootRequest request = api.GetHistoricalData(blocks);

        assertEquals(1, requests.get(), "sizes mapping to the same interval must share a request");
        assertEquals(1, request.history.size());
        assertEquals("RELIANCE", request.history.get(0).name);
        assertEquals(2, request.history.get(0).series.size());
        assertEquals(3, request.history.get(0).series.get(0).candles.size());
        assertEquals(3, request.history.get(0).series.get(1).candles.size());
    }

    @Test
    void latestBootstrapProducesOneUpdatePerSize() {
        handler.set(exchange -> respondJson(exchange, CHART_JSON));
        ArrayList<PriorityBlock> blocks = new ArrayList<>(List.of(
                new PriorityBlock(CandleSize.min1, "RELIANCE"),
                new PriorityBlock(CandleSize.min15, "RELIANCE")));

        RollRequest request = api.GetLatestVals(blocks);

        assertEquals(1, request.update.size());
        assertEquals(2, request.update.get(0).val.size());
        assertEquals(CandleSize.min1, request.update.get(0).val.get(0).size);
        assertEquals(CandleSize.min15, request.update.get(0).val.get(1).size);
        assertEquals(123.5, request.update.get(0).val.get(1).candle.close, 1e-9);
    }

    @Test
    void retriesTransientServerErrors() throws Exception {
        AtomicInteger calls = new AtomicInteger();
        handler.set(exchange -> {
            if (calls.incrementAndGet() == 1) {
                respond(exchange, 503, "busy");
            } else {
                respondJson(exchange, CHART_JSON);
            }
        });
        YahooAPI retrying = new YahooAPI(
                "http://127.0.0.1:" + server.getAddress().getPort(), 2_000, 2, ".NS");

        List<Candle> candles = retrying.fetchCandles("RELIANCE", "1m", "7d");

        assertEquals(2, calls.get());
        assertEquals(3, candles.size());
    }

    @Test
    void chartErrorsAndEmptyResultsAreSurfaced() throws Exception {
        handler.set(exchange -> respondJson(exchange, """
                {"chart":{"result":null,"error":{"code":"Not Found","description":"No data found"}}}
                """));
        assertThrows(IOException.class, () -> api.fetchCandles("BAD", "1m", "7d"));

        handler.set(exchange -> respondJson(exchange, """
                {"chart":{"result":[],"error":null}}
                """));
        assertTrue(api.fetchCandles("EMPTY", "1m", "7d").isEmpty());
    }

    @Test
    void failedFetchesAreIsolatedPerTicker() {
        handler.set(exchange -> respond(exchange, 404, "not found"));
        ArrayList<PriorityBlock> blocks = new ArrayList<>(List.of(
                new PriorityBlock(CandleSize.min1, "BAD")));

        BootRequest request = api.GetHistoricalData(blocks);
        RollRequest roll = api.GetLatestVals(blocks);

        assertTrue(request.history.isEmpty());
        assertTrue(roll.update.isEmpty());
    }
}
