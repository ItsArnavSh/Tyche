package org.Tyche.src.internal.grpc;

import java.util.ArrayList;
import java.util.List;

import org.Tyche.Config;
import org.Tyche.src.entity.CandleSize;
import org.Tyche.src.entity.CoreAPI;
import org.Tyche.src.core.producer.StockInterface;
import org.Tyche.src.entity.Blocks.Candle;
import org.Tyche.src.entity.CoreAPI.RollRequest;
import org.Tyche.src.server.Server;

import io.grpc.ServerBuilder;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import stockrec.v1.RustServiceGrpc;
import stockrec.v1.Stockrec.*;

public class GRPC {
    private static final Logger logger = LoggerFactory.getLogger(GRPC.class);

    StockInterface stockapi;

    public GRPC(StockInterface stockapi) {
        this.stockapi = stockapi;
    }

    public static void start(Server server) throws Exception {
        io.grpc.Server grpcServer = ServerBuilder
                .forPort(Config.GRPC_PORT)
                .addService(new RustServiceImpl(server))
                .maxInboundMessageSize(50 * 1024 * 1024)
                .build()
                .start();
        logger.info("gRPC server started on port {}", Config.GRPC_PORT);
        grpcServer.awaitTermination();
    }

    static class RustServiceImpl extends RustServiceGrpc.RustServiceImplBase {
        private final StockInterface stockapi;

        RustServiceImpl(Server server) {
            this(server.stockapi);
        }

        /** Visible for tests: allows injecting a fake producer boundary. */
        RustServiceImpl(StockInterface stockapi) {
            this.stockapi = stockapi;
        }

        @Override
        public void sendRollData(SendRollDataRequest request,
                StreamObserver<SendRollDataResponse> responseObserver) {
            try {
                RollRequest rreq = new RollRequest();

                for (var stock : request.getStockList()) {
                    CoreAPI.StockUpdate su = new CoreAPI.StockUpdate(stock.getName());

                    for (var ticker : stock.getValsList()) {
                        var size = CandleSize.ConvToEntity(ticker.getSize());
                        var val = ticker.getVal();

                        Candle candle = new Candle(val.getOpen(), val.getHigh(), val.getLow(), val.getClose(),
                                (long) val.getVolume());
                        su.val.add(new CoreAPI.LatestStockVal(candle, size));
                    }

                    rreq.update.add(su);
                }

                var missing = stockapi.PushLatestVals(rreq);
                ArrayList<StockSeries> ser = new ArrayList<>();
                for (var mis : missing) {
                    StockSeries stockInfo = StockSeries.newBuilder().setName(mis.name)
                            .setSize(CandleSize.ConvToProto(mis.size)).build();
                    ser.add(stockInfo);
                }
                SendRollDataResponse response = SendRollDataResponse.newBuilder()
                        .addAllMissing(ser)
                        .build();
                responseObserver.onNext(response);
                responseObserver.onCompleted();
            } catch (Exception e) {
                logger.error("sendRollData failed", e);
                responseObserver.onError(Status.INTERNAL.withDescription(e.getMessage()).withCause(e).asRuntimeException());
            }
        }

        @Override
        public void sendBootSignal(SendBootSignalRequest request,
                StreamObserver<SendBootSignalResponse> responseObserver) {
            try {
                CoreAPI.BootRequest breq = new CoreAPI.BootRequest();

                for (var hist : request.getHistList()) {
                    CoreAPI.TickerHistory th = new CoreAPI.TickerHistory(hist.getName());

                    for (var series : hist.getSeriesList()) {
                        CoreAPI.Series s = new CoreAPI.Series(CandleSize.ConvToEntity(series.getSize()));

                        for (var val : series.getValList()) {
                            Candle candle = new Candle(val.getOpen(), val.getHigh(), val.getLow(), val.getClose(),
                                    (long) val.getVolume());
                            s.candles.add(candle);
                        }

                        th.series.add(s);
                    }

                    breq.history.add(th);
                }

                stockapi.PushHistoricalVals(breq);
                responseObserver.onNext(SendBootSignalResponse.newBuilder().build());
                responseObserver.onCompleted();
            } catch (Exception e) {
                logger.error("sendBootSignal failed", e);
                responseObserver.onError(Status.INTERNAL.withDescription(e.getMessage()).withCause(e).asRuntimeException());
            }
        }

        @Override
        public void sendKillSignal(SendKillSignalRequest request,
                StreamObserver<SendKillSignalResponse> responseObserver) {
            try {
                List<String> names = request.getNameList();
                stockapi.PushKillSignal(names);
                responseObserver.onNext(SendKillSignalResponse.newBuilder().build());
                responseObserver.onCompleted();
            } catch (Exception e) {
                logger.error("sendKillSignal failed", e);
                responseObserver.onError(Status.INTERNAL.withDescription(e.getMessage()).withCause(e).asRuntimeException());
            }
        }
    }
}
