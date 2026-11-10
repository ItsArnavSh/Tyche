package org.Tyche.src.internal.grpc;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.Tyche.src.core.producer.StockInterface;
import org.Tyche.src.entity.Blocks.Candle;
import org.Tyche.src.entity.CoreAPI.BootRequest;
import org.Tyche.src.entity.CoreAPI.RollRequest;
import org.Tyche.src.entity.Scheduler_Entity.PriorityBlock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import stockrec.v1.Stockrec.CandleSize;
import stockrec.v1.Stockrec.LatestStockVals;
import stockrec.v1.Stockrec.SendBootSignalRequest;
import stockrec.v1.Stockrec.SendBootSignalResponse;
import stockrec.v1.Stockrec.SendKillSignalRequest;
import stockrec.v1.Stockrec.SendKillSignalResponse;
import stockrec.v1.Stockrec.SendRollDataRequest;
import stockrec.v1.Stockrec.SendRollDataResponse;
import stockrec.v1.Stockrec.Series;
import stockrec.v1.Stockrec.StockUpdate;
import stockrec.v1.Stockrec.StockValue;
import stockrec.v1.Stockrec.TickerHistory;

class GRPCTest {

    private static final class CapturingObserver<T> implements StreamObserver<T> {
        T value;
        Throwable error;
        boolean completed;

        @Override
        public void onNext(T value) {
            this.value = value;
        }

        @Override
        public void onError(Throwable error) {
            this.error = error;
        }

        @Override
        public void onCompleted() {
            this.completed = true;
        }
    }

    private static final class FakeStockInterface extends StockInterface {
        BootRequest bootRequest;
        RollRequest rollRequest;
        List<String> killed;
        final ArrayList<PriorityBlock> missing = new ArrayList<>();
        RuntimeException failure;

        FakeStockInterface() {
            super(null, null);
        }

        private void maybeFail() {
            if (failure != null) {
                throw failure;
            }
        }

        @Override
        public void PushHistoricalVals(BootRequest breq) {
            maybeFail();
            this.bootRequest = breq;
        }

        @Override
        public ArrayList<PriorityBlock> PushLatestVals(RollRequest rreq) {
            maybeFail();
            this.rollRequest = rreq;
            return missing;
        }

        @Override
        public void PushKillSignal(List<String> tickers) {
            maybeFail();
            this.killed = tickers;
        }
    }

    private FakeStockInterface stockapi;
    private GRPC.RustServiceImpl service;

    @BeforeEach
    void setUp() {
        stockapi = new FakeStockInterface();
        service = new GRPC.RustServiceImpl(stockapi);
    }

    private static StockValue value(float open, float high, float low, float close, double volume) {
        return StockValue.newBuilder()
                .setOpen(open).setHigh(high).setLow(low).setClose(close).setVolume(volume)
                .build();
    }

    @Test
    void bootSignalConvertsProtoCandlesIntoEntityHistory() {
        TickerHistory history = TickerHistory.newBuilder()
                .setName("TCS")
                .addSeries(Series.newBuilder()
                        .setSize(CandleSize.MIN15)
                        .addVal(value(100.5f, 105.5f, 99.5f, 104.5f, 1234))
                        .addVal(value(104.5f, 106.5f, 103.5f, 105.5f, 2222)))
                .build();
        SendBootSignalRequest request = SendBootSignalRequest.newBuilder().addHist(history).build();
        CapturingObserver<SendBootSignalResponse> observer = new CapturingObserver<>();

        service.sendBootSignal(request, observer);

        assertNull(observer.error);
        assertTrue(observer.completed);
        assertNotNull(stockapi.bootRequest);
        assertEquals(1, stockapi.bootRequest.history.size());
        var ticker = stockapi.bootRequest.history.get(0);
        assertEquals("TCS", ticker.name);
        assertEquals(1, ticker.series.size());
        var series = ticker.series.get(0);
        assertEquals(org.Tyche.src.entity.CandleSize.min15, series.size);
        assertEquals(2, series.candles.size());
        Candle first = series.candles.peekFirst();
        assertEquals(100.5, first.open, 1e-4);
        assertEquals(105.5, first.high, 1e-4);
        assertEquals(99.5, first.low, 1e-4);
        assertEquals(104.5, first.close, 1e-4);
        assertEquals(1234, first.volume, 1e-9);
        assertEquals(2222, series.candles.peekLast().volume, 1e-9);
    }

    @Test
    void rollSignalConvertsValuesAndReturnsMissingTickers() {
        stockapi.missing.add(new PriorityBlock(org.Tyche.src.entity.CandleSize.min15, "INFY"));
        StockUpdate update = StockUpdate.newBuilder()
                .setName("TCS")
                .addVals(LatestStockVals.newBuilder()
                        .setSize(CandleSize.MIN1)
                        .setVal(value(200f, 205f, 199f, 204f, 5000)))
                .build();
        SendRollDataRequest request = SendRollDataRequest.newBuilder().addStock(update).build();
        CapturingObserver<SendRollDataResponse> observer = new CapturingObserver<>();

        service.sendRollData(request, observer);

        assertNull(observer.error);
        assertTrue(observer.completed);
        assertEquals(1, stockapi.rollRequest.update.size());
        var stock = stockapi.rollRequest.update.get(0);
        assertEquals("TCS", stock.name);
        assertEquals(1, stock.val.size());
        assertEquals(org.Tyche.src.entity.CandleSize.min1, stock.val.get(0).size);
        assertEquals(204, stock.val.get(0).candle.close, 1e-4);
        assertEquals(5000, stock.val.get(0).candle.volume, 1e-9);

        assertEquals(1, observer.value.getMissingCount());
        assertEquals("INFY", observer.value.getMissing(0).getName());
        assertEquals(CandleSize.MIN15, observer.value.getMissing(0).getSize());
    }

    @Test
    void killSignalForwardsNames() {
        SendKillSignalRequest request = SendKillSignalRequest.newBuilder()
                .addName("TCS").addName("INFY").build();
        CapturingObserver<SendKillSignalResponse> observer = new CapturingObserver<>();

        service.sendKillSignal(request, observer);

        assertNull(observer.error);
        assertTrue(observer.completed);
        assertNotNull(observer.value);
        assertArrayEquals(new String[] { "TCS", "INFY" }, stockapi.killed.toArray());
    }

    @Test
    void failuresAreReportedAsInternalGrpcErrors() {
        stockapi.failure = new IllegalStateException("boom");
        SendRollDataRequest request = SendRollDataRequest.newBuilder().build();
        CapturingObserver<SendRollDataResponse> observer = new CapturingObserver<>();

        service.sendRollData(request, observer);

        assertNotNull(observer.error);
        assertEquals(Status.Code.INTERNAL, Status.fromThrowable(observer.error).getCode());
        assertTrue(Status.fromThrowable(observer.error).getDescription().contains("boom"));
        assertTrue(!observer.completed);
    }

    @Test
    void bootFailuresAreAlsoReported() {
        stockapi.failure = new IllegalArgumentException("bad history");
        SendBootSignalRequest request = SendBootSignalRequest.newBuilder().build();
        CapturingObserver<SendBootSignalResponse> observer = new CapturingObserver<>();

        service.sendBootSignal(request, observer);

        assertNotNull(observer.error);
        assertEquals(Status.Code.INTERNAL, Status.fromThrowable(observer.error).getCode());
    }

    @Test
    void killFailuresAreAlsoReported() {
        stockapi.failure = new IllegalStateException("cannot kill");
        SendKillSignalRequest request = SendKillSignalRequest.newBuilder().addName("TCS").build();
        CapturingObserver<SendKillSignalResponse> observer = new CapturingObserver<>();

        service.sendKillSignal(request, observer);

        assertNotNull(observer.error);
        assertEquals(Status.Code.INTERNAL, Status.fromThrowable(observer.error).getCode());
    }
}
