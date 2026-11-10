package org.Tyche.src.core.producer.RealVal.zerodha;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

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

class ZerodhaAPITest {

    private static final String INSTRUMENTS_CSV = """
            instrument_token,tradingsymbol,exchange,instrument_type
            256265,RELIANCE,NSE,EQ
            738561,TCS,NSE,EQ
            123,RELIANCE,BSE,EQ
            999,RELIANCEFUT,NSE,FUT
            """;

    private static final String HISTORY_JSON = """
            {"status":"success","data":{"candles":[
              ["2026-11-01 09:15:00",100,105,99,104,1000],
              ["2026-11-01 09:30:00",104,106,103,105,1500],
              ["2026-11-01 09:45:00",0,0,0,0,0]
            ]}}
            """;

    private static final String QUOTES_JSON = """
            {"status":"success","data":{
              "NSE:RELIANCE":{"last_price":105.5,
                "ohlc":{"open":100,"high":106,"low":99,"close":104}}
            }}
            """;

    private HttpServer server;
    private ZerodhaAPI api;

    private final AtomicInteger instrumentsRequests = new AtomicInteger();
    private final AtomicInteger historicalRequests = new AtomicInteger();
    private final AtomicBoolean failQuotes = new AtomicBoolean(false);
    private final AtomicBoolean failProfile = new AtomicBoolean(false);
    private final AtomicReference<String> lastMethod = new AtomicReference<>("");
    private final AtomicReference<String> lastPath = new AtomicReference<>("");
    private final AtomicReference<String> lastQuery = new AtomicReference<>("");
    private final AtomicReference<String> lastBody = new AtomicReference<>("");
    private final AtomicReference<String> lastAuthorization = new AtomicReference<>("");
    private final AtomicReference<String> lastKiteVersion = new AtomicReference<>("");

    @BeforeEach
    void setUp() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::dispatch);
        server.start();
        api = new ZerodhaAPI("http://127.0.0.1:" + server.getAddress().getPort(),
                "testkey", "secret", "token", "NSE", 1, 0);
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    private void dispatch(HttpExchange exchange) throws IOException {
        lastMethod.set(exchange.getRequestMethod());
        lastPath.set(exchange.getRequestURI().getPath());
        lastQuery.set(String.valueOf(exchange.getRequestURI().getRawQuery()));
        lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        lastAuthorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
        lastKiteVersion.set(exchange.getRequestHeaders().getFirst("X-Kite-Version"));

        String path = exchange.getRequestURI().getPath();
        if ("/instruments".equals(path)) {
            instrumentsRequests.incrementAndGet();
            respond(exchange, 200, INSTRUMENTS_CSV, "text/csv");
        } else if (path.startsWith("/instruments/historical/")) {
            historicalRequests.incrementAndGet();
            respondJson(exchange, HISTORY_JSON);
        } else if ("/quote/ohlc".equals(path)) {
            if (failQuotes.get()) {
                respondJson(exchange, 400, "{\"status\":\"error\",\"message\":\"bad quote\"}");
            } else {
                respondJson(exchange, QUOTES_JSON);
            }
        } else if ("/session/token".equals(path)) {
            respondJson(exchange, 200, "{\"status\":\"success\",\"data\":{\"access_token\":\"newtoken\"}}");
        } else if (path.startsWith("/orders/")) {
            if ("DELETE".equals(exchange.getRequestMethod())) {
                respondJson(exchange, 200, "{\"status\":\"success\",\"data\":{\"order_id\":\"ABC123\"}}");
            } else {
                respondJson(exchange, 200, "{\"status\":\"success\",\"data\":{\"order_id\":\"XYZ789\"}}");
            }
        } else if ("/user/profile".equals(path)) {
            if (failProfile.get()) {
                respondJson(exchange, 403, "{\"status\":\"error\",\"message\":\"Token is invalid\"}");
            } else {
                respondJson(exchange, 200, "{\"status\":\"success\",\"data\":{\"user_id\":\"AB1234\"}}");
            }
        } else {
            respond(exchange, 404, "not found", "text/plain");
        }
    }

    private static void respondJson(HttpExchange exchange, String body) throws IOException {
        respondJson(exchange, 200, body);
    }

    private static void respondJson(HttpExchange exchange, int status, String body) throws IOException {
        respond(exchange, status, body, "application/json");
    }

    private static void respond(HttpExchange exchange, int status, String body, String contentType)
            throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private static Map<String, String> parseForm(String body) {
        Map<String, String> out = new LinkedHashMap<>();
        if (body == null || body.isBlank()) {
            return out;
        }
        for (String pair : body.split("&")) {
            int eq = pair.indexOf('=');
            String key = eq < 0 ? pair : pair.substring(0, eq);
            String value = eq < 0 ? "" : pair.substring(eq + 1);
            out.put(URLDecoder.decode(key, StandardCharsets.UTF_8),
                    URLDecoder.decode(value, StandardCharsets.UTF_8));
        }
        return out;
    }

    private static String sha256(String value) throws Exception {
        byte[] bytes = MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8));
        return bytesToHex(bytes);
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    @Test
    void parsesQuotedCsvFields() {
        assertEquals(List.of("a", "b", "c"), ZerodhaAPI.parseCsvLine("a,b,c"));
        assertEquals(List.of("a,b", "c"), ZerodhaAPI.parseCsvLine("\"a,b\",c"));
        assertEquals(List.of("he said \"hi\"", "x"),
                ZerodhaAPI.parseCsvLine("\"he said \"\"hi\"\"\",x"));
        assertEquals(List.of("a", ""), ZerodhaAPI.parseCsvLine("a,"));
        assertEquals(List.of(""), ZerodhaAPI.parseCsvLine(""));
    }

    @Test
    void resolvesInstrumentTokensAndCachesTheDump() throws Exception {
        assertEquals("256265", api.instrumentToken("RELIANCE"));
        assertEquals("256265", api.instrumentToken("reliance"));
        assertEquals("738561", api.instrumentToken("NSE:TCS"));
        assertEquals("123", api.instrumentToken("BSE:RELIANCE"));
        assertEquals(1, instrumentsRequests.get());

        IOException error = assertThrows(IOException.class, () -> api.instrumentToken("UNKNOWN"));
        assertTrue(error.getMessage().contains("Unknown Kite instrument"));
    }

    @Test
    void fetchesHistoricalCandlesWithAuthAndThrottleHeaders() throws Exception {
        List<Candle> candles = api.fetchHistorical("RELIANCE", CandleSize.min15, 10);

        assertEquals(2, candles.size());
        assertEquals(100, candles.get(0).open, 1e-9);
        assertEquals(105, candles.get(0).high, 1e-9);
        assertEquals(99, candles.get(0).low, 1e-9);
        assertEquals(104, candles.get(0).close, 1e-9);
        assertEquals(1000, candles.get(0).volume, 1e-9);
        assertEquals(105, candles.get(1).close, 1e-9);
        assertEquals("/instruments/historical/256265/15minute", lastPath.get());
        assertTrue(lastQuery.get().contains("continuous=0"));
        assertTrue(lastQuery.get().contains("oi=0"));
        assertTrue(lastQuery.get().contains("from="));
        assertTrue(lastQuery.get().contains("to="));
        assertEquals("token testkey:token", lastAuthorization.get());
        assertEquals("3", lastKiteVersion.get());
    }

    @Test
    void historicalLimitKeepsMostRecentCandles() throws Exception {
        List<Candle> candles = api.fetchHistorical("RELIANCE", CandleSize.min15, 1);

        assertEquals(1, candles.size());
        assertEquals(105, candles.get(0).close, 1e-9);
    }

    @Test
    void fetchesBatchedQuotes() throws Exception {
        Map<String, ZerodhaAPI.Quote> quotes = api.fetchQuotes(List.of("RELIANCE", "NSE:TCS"));

        assertEquals(1, quotes.size());
        ZerodhaAPI.Quote quote = quotes.get("NSE:RELIANCE");
        assertNotNull(quote);
        assertEquals(100, quote.open(), 1e-9);
        assertEquals(106, quote.high(), 1e-9);
        assertEquals(99, quote.low(), 1e-9);
        assertEquals(104, quote.close(), 1e-9);
        assertEquals(105.5, quote.last(), 1e-9);

        Candle candle = quote.toCandle();
        assertEquals(105.5, candle.close, 1e-9);
        assertEquals(106, candle.high, 1e-9);
        assertEquals(99, candle.low, 1e-9);
        assertTrue(lastQuery.get().contains("i=NSE%3ARELIANCE"));
        assertTrue(lastQuery.get().contains("i=NSE%3ATCS"));
    }

    @Test
    void generatesSessionWithCorrectChecksum() throws Exception {
        String token = api.generateSession("req123");

        assertEquals("newtoken", token);
        assertEquals("/session/token", lastPath.get());
        assertNull(lastAuthorization.get(), "session exchange must not send a bearer token");

        Map<String, String> form = parseForm(lastBody.get());
        assertEquals("testkey", form.get("api_key"));
        assertEquals("req123", form.get("request_token"));
        assertEquals(sha256("testkey" + "req123" + "secret"), form.get("checksum"));
    }

    @Test
    void placesAndCancelsOrders() throws Exception {
        String orderId = api.placeOrder(ZerodhaAPI.OrderRequest.marketBuy("NSE:RELIANCE", 7));

        assertEquals("XYZ789", orderId);
        assertEquals("/orders/regular", lastPath.get());
        Map<String, String> form = parseForm(lastBody.get());
        assertEquals("RELIANCE", form.get("tradingsymbol"));
        assertEquals("NSE", form.get("exchange"));
        assertEquals("BUY", form.get("transaction_type"));
        assertEquals("MARKET", form.get("order_type"));
        assertEquals("7", form.get("quantity"));
        assertEquals("CNC", form.get("product"));
        assertEquals("DAY", form.get("validity"));
        assertFalse(form.containsKey("price"));
        assertEquals("token testkey:token", lastAuthorization.get());

        api.cancelOrder("regular", "ABC123");
        assertEquals("DELETE", lastMethod.get());
        assertEquals("/orders/regular/ABC123", lastPath.get());
    }

    @Test
    void limitOrdersIncludePriceAndTriggerPrice() throws Exception {
        api.placeOrder(new ZerodhaAPI.OrderRequest(
                "TCS", null, "SELL", "SL", 3, "MIS", "DAY", 3500.5, 3499.0));

        Map<String, String> form = parseForm(lastBody.get());
        assertEquals("TCS", form.get("tradingsymbol"));
        assertEquals("NSE", form.get("exchange"));
        assertEquals("SELL", form.get("transaction_type"));
        assertEquals("SL", form.get("order_type"));
        assertEquals("MIS", form.get("product"));
        assertEquals("3500.5", form.get("price"));
        assertEquals("3499.0", form.get("trigger_price"));
    }

    @Test
    void nonRetryableErrorsSurfaceAsKiteException() {
        failProfile.set(true);

        ZerodhaAPI.KiteException error = assertThrows(ZerodhaAPI.KiteException.class,
                () -> api.profile());

        assertEquals(403, error.status());
        assertTrue(error.getMessage().contains("Token is invalid"));
    }

    @Test
    void loginUrlCarriesApiKey() {
        assertTrue(api.loginUrl().contains("api_key=testkey"));
        assertTrue(api.loginUrl().contains("v=3"));
    }

    @Test
    void historicalBootstrapBuildsSeriesPerSize() {
        ArrayList<PriorityBlock> blocks = new ArrayList<>(List.of(
                new PriorityBlock(CandleSize.min1, "RELIANCE"),
                new PriorityBlock(CandleSize.min15, "RELIANCE")));

        BootRequest request = api.GetHistoricalData(blocks);

        assertEquals(1, request.history.size());
        assertEquals(2, request.history.get(0).series.size());
        assertEquals(2, request.history.get(0).series.get(0).candles.size());
        assertEquals(2, request.history.get(0).series.get(1).candles.size());
        assertEquals(2, historicalRequests.get());
    }

    @Test
    void latestValuesFallBackToMinuteHistoryWhenQuotesFail() {
        failQuotes.set(true);
        ArrayList<PriorityBlock> blocks = new ArrayList<>(List.of(
                new PriorityBlock(CandleSize.min1, "RELIANCE")));

        RollRequest request = api.GetLatestVals(blocks);

        assertEquals(1, request.update.size());
        assertEquals(1, request.update.get(0).val.size());
        Candle candle = request.update.get(0).val.get(0).candle;
        assertEquals(105, candle.close, 1e-9);
        assertEquals(106, candle.high, 1e-9);
        assertEquals(103, candle.low, 1e-9);
    }

    @Test
    void intervalMappingMatchesKiteVocabulary() {
        assertEquals("minute", ZerodhaAPI.intervalFor(CandleSize.sec5));
        assertEquals("minute", ZerodhaAPI.intervalFor(CandleSize.sec30));
        assertEquals("minute", ZerodhaAPI.intervalFor(CandleSize.min1));
        assertEquals("15minute", ZerodhaAPI.intervalFor(CandleSize.min15));
        assertEquals("60minute", ZerodhaAPI.intervalFor(CandleSize.hour1));
    }
}
