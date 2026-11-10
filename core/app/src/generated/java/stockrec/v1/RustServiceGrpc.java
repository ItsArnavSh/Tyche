package stockrec.v1;

import static io.grpc.MethodDescriptor.generateFullMethodName;

/**
 */
@javax.annotation.Generated(
    value = "by gRPC proto compiler (version 1.68.1)",
    comments = "Source: stockrec/stockrec.proto")
@io.grpc.stub.annotations.GrpcGenerated
public final class RustServiceGrpc {

  private RustServiceGrpc() {}

  public static final java.lang.String SERVICE_NAME = "stockrec.v1.RustService";

  // Static method descriptors that strictly reflect the proto.
  private static volatile io.grpc.MethodDescriptor<stockrec.v1.Stockrec.SendRollDataRequest,
      stockrec.v1.Stockrec.SendRollDataResponse> getSendRollDataMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "SendRollData",
      requestType = stockrec.v1.Stockrec.SendRollDataRequest.class,
      responseType = stockrec.v1.Stockrec.SendRollDataResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<stockrec.v1.Stockrec.SendRollDataRequest,
      stockrec.v1.Stockrec.SendRollDataResponse> getSendRollDataMethod() {
    io.grpc.MethodDescriptor<stockrec.v1.Stockrec.SendRollDataRequest, stockrec.v1.Stockrec.SendRollDataResponse> getSendRollDataMethod;
    if ((getSendRollDataMethod = RustServiceGrpc.getSendRollDataMethod) == null) {
      synchronized (RustServiceGrpc.class) {
        if ((getSendRollDataMethod = RustServiceGrpc.getSendRollDataMethod) == null) {
          RustServiceGrpc.getSendRollDataMethod = getSendRollDataMethod =
              io.grpc.MethodDescriptor.<stockrec.v1.Stockrec.SendRollDataRequest, stockrec.v1.Stockrec.SendRollDataResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "SendRollData"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  stockrec.v1.Stockrec.SendRollDataRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  stockrec.v1.Stockrec.SendRollDataResponse.getDefaultInstance()))
              .setSchemaDescriptor(new RustServiceMethodDescriptorSupplier("SendRollData"))
              .build();
        }
      }
    }
    return getSendRollDataMethod;
  }

  private static volatile io.grpc.MethodDescriptor<stockrec.v1.Stockrec.SendBootSignalRequest,
      stockrec.v1.Stockrec.SendBootSignalResponse> getSendBootSignalMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "SendBootSignal",
      requestType = stockrec.v1.Stockrec.SendBootSignalRequest.class,
      responseType = stockrec.v1.Stockrec.SendBootSignalResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<stockrec.v1.Stockrec.SendBootSignalRequest,
      stockrec.v1.Stockrec.SendBootSignalResponse> getSendBootSignalMethod() {
    io.grpc.MethodDescriptor<stockrec.v1.Stockrec.SendBootSignalRequest, stockrec.v1.Stockrec.SendBootSignalResponse> getSendBootSignalMethod;
    if ((getSendBootSignalMethod = RustServiceGrpc.getSendBootSignalMethod) == null) {
      synchronized (RustServiceGrpc.class) {
        if ((getSendBootSignalMethod = RustServiceGrpc.getSendBootSignalMethod) == null) {
          RustServiceGrpc.getSendBootSignalMethod = getSendBootSignalMethod =
              io.grpc.MethodDescriptor.<stockrec.v1.Stockrec.SendBootSignalRequest, stockrec.v1.Stockrec.SendBootSignalResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "SendBootSignal"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  stockrec.v1.Stockrec.SendBootSignalRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  stockrec.v1.Stockrec.SendBootSignalResponse.getDefaultInstance()))
              .setSchemaDescriptor(new RustServiceMethodDescriptorSupplier("SendBootSignal"))
              .build();
        }
      }
    }
    return getSendBootSignalMethod;
  }

  private static volatile io.grpc.MethodDescriptor<stockrec.v1.Stockrec.SendKillSignalRequest,
      stockrec.v1.Stockrec.SendKillSignalResponse> getSendKillSignalMethod;

  @io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "SendKillSignal",
      requestType = stockrec.v1.Stockrec.SendKillSignalRequest.class,
      responseType = stockrec.v1.Stockrec.SendKillSignalResponse.class,
      methodType = io.grpc.MethodDescriptor.MethodType.UNARY)
  public static io.grpc.MethodDescriptor<stockrec.v1.Stockrec.SendKillSignalRequest,
      stockrec.v1.Stockrec.SendKillSignalResponse> getSendKillSignalMethod() {
    io.grpc.MethodDescriptor<stockrec.v1.Stockrec.SendKillSignalRequest, stockrec.v1.Stockrec.SendKillSignalResponse> getSendKillSignalMethod;
    if ((getSendKillSignalMethod = RustServiceGrpc.getSendKillSignalMethod) == null) {
      synchronized (RustServiceGrpc.class) {
        if ((getSendKillSignalMethod = RustServiceGrpc.getSendKillSignalMethod) == null) {
          RustServiceGrpc.getSendKillSignalMethod = getSendKillSignalMethod =
              io.grpc.MethodDescriptor.<stockrec.v1.Stockrec.SendKillSignalRequest, stockrec.v1.Stockrec.SendKillSignalResponse>newBuilder()
              .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "SendKillSignal"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  stockrec.v1.Stockrec.SendKillSignalRequest.getDefaultInstance()))
              .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                  stockrec.v1.Stockrec.SendKillSignalResponse.getDefaultInstance()))
              .setSchemaDescriptor(new RustServiceMethodDescriptorSupplier("SendKillSignal"))
              .build();
        }
      }
    }
    return getSendKillSignalMethod;
  }

  /**
   * Creates a new async stub that supports all call types for the service
   */
  public static RustServiceStub newStub(io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<RustServiceStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<RustServiceStub>() {
        @java.lang.Override
        public RustServiceStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new RustServiceStub(channel, callOptions);
        }
      };
    return RustServiceStub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports unary and streaming output calls on the service
   */
  public static RustServiceBlockingStub newBlockingStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<RustServiceBlockingStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<RustServiceBlockingStub>() {
        @java.lang.Override
        public RustServiceBlockingStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new RustServiceBlockingStub(channel, callOptions);
        }
      };
    return RustServiceBlockingStub.newStub(factory, channel);
  }

  /**
   * Creates a new ListenableFuture-style stub that supports unary calls on the service
   */
  public static RustServiceFutureStub newFutureStub(
      io.grpc.Channel channel) {
    io.grpc.stub.AbstractStub.StubFactory<RustServiceFutureStub> factory =
      new io.grpc.stub.AbstractStub.StubFactory<RustServiceFutureStub>() {
        @java.lang.Override
        public RustServiceFutureStub newStub(io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
          return new RustServiceFutureStub(channel, callOptions);
        }
      };
    return RustServiceFutureStub.newStub(factory, channel);
  }

  /**
   */
  public interface AsyncService {

    /**
     */
    default void sendRollData(stockrec.v1.Stockrec.SendRollDataRequest request,
        io.grpc.stub.StreamObserver<stockrec.v1.Stockrec.SendRollDataResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getSendRollDataMethod(), responseObserver);
    }

    /**
     */
    default void sendBootSignal(stockrec.v1.Stockrec.SendBootSignalRequest request,
        io.grpc.stub.StreamObserver<stockrec.v1.Stockrec.SendBootSignalResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getSendBootSignalMethod(), responseObserver);
    }

    /**
     */
    default void sendKillSignal(stockrec.v1.Stockrec.SendKillSignalRequest request,
        io.grpc.stub.StreamObserver<stockrec.v1.Stockrec.SendKillSignalResponse> responseObserver) {
      io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getSendKillSignalMethod(), responseObserver);
    }
  }

  /**
   * Base class for the server implementation of the service RustService.
   */
  public static abstract class RustServiceImplBase
      implements io.grpc.BindableService, AsyncService {

    @java.lang.Override public final io.grpc.ServerServiceDefinition bindService() {
      return RustServiceGrpc.bindService(this);
    }
  }

  /**
   * A stub to allow clients to do asynchronous rpc calls to service RustService.
   */
  public static final class RustServiceStub
      extends io.grpc.stub.AbstractAsyncStub<RustServiceStub> {
    private RustServiceStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected RustServiceStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new RustServiceStub(channel, callOptions);
    }

    /**
     */
    public void sendRollData(stockrec.v1.Stockrec.SendRollDataRequest request,
        io.grpc.stub.StreamObserver<stockrec.v1.Stockrec.SendRollDataResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getSendRollDataMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void sendBootSignal(stockrec.v1.Stockrec.SendBootSignalRequest request,
        io.grpc.stub.StreamObserver<stockrec.v1.Stockrec.SendBootSignalResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getSendBootSignalMethod(), getCallOptions()), request, responseObserver);
    }

    /**
     */
    public void sendKillSignal(stockrec.v1.Stockrec.SendKillSignalRequest request,
        io.grpc.stub.StreamObserver<stockrec.v1.Stockrec.SendKillSignalResponse> responseObserver) {
      io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getSendKillSignalMethod(), getCallOptions()), request, responseObserver);
    }
  }

  /**
   * A stub to allow clients to do synchronous rpc calls to service RustService.
   */
  public static final class RustServiceBlockingStub
      extends io.grpc.stub.AbstractBlockingStub<RustServiceBlockingStub> {
    private RustServiceBlockingStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected RustServiceBlockingStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new RustServiceBlockingStub(channel, callOptions);
    }

    /**
     */
    public stockrec.v1.Stockrec.SendRollDataResponse sendRollData(stockrec.v1.Stockrec.SendRollDataRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getSendRollDataMethod(), getCallOptions(), request);
    }

    /**
     */
    public stockrec.v1.Stockrec.SendBootSignalResponse sendBootSignal(stockrec.v1.Stockrec.SendBootSignalRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getSendBootSignalMethod(), getCallOptions(), request);
    }

    /**
     */
    public stockrec.v1.Stockrec.SendKillSignalResponse sendKillSignal(stockrec.v1.Stockrec.SendKillSignalRequest request) {
      return io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getSendKillSignalMethod(), getCallOptions(), request);
    }
  }

  /**
   * A stub to allow clients to do ListenableFuture-style rpc calls to service RustService.
   */
  public static final class RustServiceFutureStub
      extends io.grpc.stub.AbstractFutureStub<RustServiceFutureStub> {
    private RustServiceFutureStub(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected RustServiceFutureStub build(
        io.grpc.Channel channel, io.grpc.CallOptions callOptions) {
      return new RustServiceFutureStub(channel, callOptions);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<stockrec.v1.Stockrec.SendRollDataResponse> sendRollData(
        stockrec.v1.Stockrec.SendRollDataRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getSendRollDataMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<stockrec.v1.Stockrec.SendBootSignalResponse> sendBootSignal(
        stockrec.v1.Stockrec.SendBootSignalRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getSendBootSignalMethod(), getCallOptions()), request);
    }

    /**
     */
    public com.google.common.util.concurrent.ListenableFuture<stockrec.v1.Stockrec.SendKillSignalResponse> sendKillSignal(
        stockrec.v1.Stockrec.SendKillSignalRequest request) {
      return io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getSendKillSignalMethod(), getCallOptions()), request);
    }
  }

  private static final int METHODID_SEND_ROLL_DATA = 0;
  private static final int METHODID_SEND_BOOT_SIGNAL = 1;
  private static final int METHODID_SEND_KILL_SIGNAL = 2;

  private static final class MethodHandlers<Req, Resp> implements
      io.grpc.stub.ServerCalls.UnaryMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.ServerStreamingMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.ClientStreamingMethod<Req, Resp>,
      io.grpc.stub.ServerCalls.BidiStreamingMethod<Req, Resp> {
    private final AsyncService serviceImpl;
    private final int methodId;

    MethodHandlers(AsyncService serviceImpl, int methodId) {
      this.serviceImpl = serviceImpl;
      this.methodId = methodId;
    }

    @java.lang.Override
    @java.lang.SuppressWarnings("unchecked")
    public void invoke(Req request, io.grpc.stub.StreamObserver<Resp> responseObserver) {
      switch (methodId) {
        case METHODID_SEND_ROLL_DATA:
          serviceImpl.sendRollData((stockrec.v1.Stockrec.SendRollDataRequest) request,
              (io.grpc.stub.StreamObserver<stockrec.v1.Stockrec.SendRollDataResponse>) responseObserver);
          break;
        case METHODID_SEND_BOOT_SIGNAL:
          serviceImpl.sendBootSignal((stockrec.v1.Stockrec.SendBootSignalRequest) request,
              (io.grpc.stub.StreamObserver<stockrec.v1.Stockrec.SendBootSignalResponse>) responseObserver);
          break;
        case METHODID_SEND_KILL_SIGNAL:
          serviceImpl.sendKillSignal((stockrec.v1.Stockrec.SendKillSignalRequest) request,
              (io.grpc.stub.StreamObserver<stockrec.v1.Stockrec.SendKillSignalResponse>) responseObserver);
          break;
        default:
          throw new AssertionError();
      }
    }

    @java.lang.Override
    @java.lang.SuppressWarnings("unchecked")
    public io.grpc.stub.StreamObserver<Req> invoke(
        io.grpc.stub.StreamObserver<Resp> responseObserver) {
      switch (methodId) {
        default:
          throw new AssertionError();
      }
    }
  }

  public static final io.grpc.ServerServiceDefinition bindService(AsyncService service) {
    return io.grpc.ServerServiceDefinition.builder(getServiceDescriptor())
        .addMethod(
          getSendRollDataMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              stockrec.v1.Stockrec.SendRollDataRequest,
              stockrec.v1.Stockrec.SendRollDataResponse>(
                service, METHODID_SEND_ROLL_DATA)))
        .addMethod(
          getSendBootSignalMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              stockrec.v1.Stockrec.SendBootSignalRequest,
              stockrec.v1.Stockrec.SendBootSignalResponse>(
                service, METHODID_SEND_BOOT_SIGNAL)))
        .addMethod(
          getSendKillSignalMethod(),
          io.grpc.stub.ServerCalls.asyncUnaryCall(
            new MethodHandlers<
              stockrec.v1.Stockrec.SendKillSignalRequest,
              stockrec.v1.Stockrec.SendKillSignalResponse>(
                service, METHODID_SEND_KILL_SIGNAL)))
        .build();
  }

  private static abstract class RustServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoFileDescriptorSupplier, io.grpc.protobuf.ProtoServiceDescriptorSupplier {
    RustServiceBaseDescriptorSupplier() {}

    @java.lang.Override
    public com.google.protobuf.Descriptors.FileDescriptor getFileDescriptor() {
      return stockrec.v1.Stockrec.getDescriptor();
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.ServiceDescriptor getServiceDescriptor() {
      return getFileDescriptor().findServiceByName("RustService");
    }
  }

  private static final class RustServiceFileDescriptorSupplier
      extends RustServiceBaseDescriptorSupplier {
    RustServiceFileDescriptorSupplier() {}
  }

  private static final class RustServiceMethodDescriptorSupplier
      extends RustServiceBaseDescriptorSupplier
      implements io.grpc.protobuf.ProtoMethodDescriptorSupplier {
    private final java.lang.String methodName;

    RustServiceMethodDescriptorSupplier(java.lang.String methodName) {
      this.methodName = methodName;
    }

    @java.lang.Override
    public com.google.protobuf.Descriptors.MethodDescriptor getMethodDescriptor() {
      return getServiceDescriptor().findMethodByName(methodName);
    }
  }

  private static volatile io.grpc.ServiceDescriptor serviceDescriptor;

  public static io.grpc.ServiceDescriptor getServiceDescriptor() {
    io.grpc.ServiceDescriptor result = serviceDescriptor;
    if (result == null) {
      synchronized (RustServiceGrpc.class) {
        result = serviceDescriptor;
        if (result == null) {
          serviceDescriptor = result = io.grpc.ServiceDescriptor.newBuilder(SERVICE_NAME)
              .setSchemaDescriptor(new RustServiceFileDescriptorSupplier())
              .addMethod(getSendRollDataMethod())
              .addMethod(getSendBootSignalMethod())
              .addMethod(getSendKillSignalMethod())
              .build();
        }
      }
    }
    return result;
  }
}
