package org.Tyche.src.internal.grpc;

import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;
import stockrec.v1.RustServiceGrpc;
import stockrec.v1.Stockrec.*;

public class GRPC {
    public static void start() throws Exception {
        io.grpc.Server server = ServerBuilder
                .forPort(50051)
                .addService(new RustServiceImpl())
                .build()
                .start();

        System.out.println("gRPC server started on port 50051");
        server.awaitTermination();
    }

    static class RustServiceImpl extends RustServiceGrpc.RustServiceImplBase {

        @Override
        public void sendRollData(SendRollDataRequest request,
                StreamObserver<SendRollDataResponse> responseObserver) {
            // request.getStockList() — list of StockUpdate
            SendRollDataResponse response = SendRollDataResponse.newBuilder()
                    .build();

            responseObserver.onNext(response);
            responseObserver.onCompleted();
        }

        @Override
        public void sendBootSignal(SendBootSignalRequest request,
                StreamObserver<SendBootSignalResponse> responseObserver) {
            // request.getHistList() — list of TickerHistory
            responseObserver.onNext(SendBootSignalResponse.newBuilder().build());
            responseObserver.onCompleted();
        }

        @Override
        public void sendKillSignal(SendKillSignalRequest request,
                StreamObserver<SendKillSignalResponse> responseObserver) {
            // request.getNameList() — list of strings
            responseObserver.onNext(SendKillSignalResponse.newBuilder().build());
            responseObserver.onCompleted();
        }
    }
}