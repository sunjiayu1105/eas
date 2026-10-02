package apache.rocketmq.v2;

import static org.apache.rocketmq.shaded.io.grpc.MethodDescriptor.generateFullMethodName;

/**
 */
@javax.annotation.Generated(
    value = "by gRPC proto compiler (version 1.50.0)",
    comments = "Source: apache/rocketmq/v2/admin.proto")
@org.apache.rocketmq.shaded.io.grpc.stub.annotations.GrpcGenerated
public final class AdminGrpc {

  private AdminGrpc() {}

  public static final String SERVICE_NAME = "apache.rocketmq.v2.Admin";

  // Static method descriptors that strictly reflect the proto.
  private static volatile org.apache.rocketmq.shaded.io.grpc.MethodDescriptor<apache.rocketmq.v2.ChangeLogLevelRequest,
      apache.rocketmq.v2.ChangeLogLevelResponse> getChangeLogLevelMethod;

  @org.apache.rocketmq.shaded.io.grpc.stub.annotations.RpcMethod(
      fullMethodName = SERVICE_NAME + '/' + "ChangeLogLevel",
      requestType = apache.rocketmq.v2.ChangeLogLevelRequest.class,
      responseType = apache.rocketmq.v2.ChangeLogLevelResponse.class,
      methodType = org.apache.rocketmq.shaded.io.grpc.MethodDescriptor.MethodType.UNARY)
  public static org.apache.rocketmq.shaded.io.grpc.MethodDescriptor<apache.rocketmq.v2.ChangeLogLevelRequest,
      apache.rocketmq.v2.ChangeLogLevelResponse> getChangeLogLevelMethod() {
    org.apache.rocketmq.shaded.io.grpc.MethodDescriptor<apache.rocketmq.v2.ChangeLogLevelRequest, apache.rocketmq.v2.ChangeLogLevelResponse> getChangeLogLevelMethod;
    if ((getChangeLogLevelMethod = AdminGrpc.getChangeLogLevelMethod) == null) {
      synchronized (AdminGrpc.class) {
        if ((getChangeLogLevelMethod = AdminGrpc.getChangeLogLevelMethod) == null) {
          AdminGrpc.getChangeLogLevelMethod = getChangeLogLevelMethod =
              org.apache.rocketmq.shaded.io.grpc.MethodDescriptor.<apache.rocketmq.v2.ChangeLogLevelRequest, apache.rocketmq.v2.ChangeLogLevelResponse>newBuilder()
              .setType(org.apache.rocketmq.shaded.io.grpc.MethodDescriptor.MethodType.UNARY)
              .setFullMethodName(generateFullMethodName(SERVICE_NAME, "ChangeLogLevel"))
              .setSampledToLocalTracing(true)
              .setRequestMarshaller(org.apache.rocketmq.shaded.io.grpc.protobuf.ProtoUtils.marshaller(
                  apache.rocketmq.v2.ChangeLogLevelRequest.getDefaultInstance()))
              .setResponseMarshaller(org.apache.rocketmq.shaded.io.grpc.protobuf.ProtoUtils.marshaller(
                  apache.rocketmq.v2.ChangeLogLevelResponse.getDefaultInstance()))
              .setSchemaDescriptor(new AdminMethodDescriptorSupplier("ChangeLogLevel"))
              .build();
        }
      }
    }
    return getChangeLogLevelMethod;
  }

  /**
   * Creates a new async stub that supports all call types for the service
   */
  public static AdminStub newStub(org.apache.rocketmq.shaded.io.grpc.Channel channel) {
    org.apache.rocketmq.shaded.io.grpc.stub.AbstractStub.StubFactory<AdminStub> factory =
      new org.apache.rocketmq.shaded.io.grpc.stub.AbstractStub.StubFactory<AdminStub>() {
        @java.lang.Override
        public AdminStub newStub(org.apache.rocketmq.shaded.io.grpc.Channel channel, org.apache.rocketmq.shaded.io.grpc.CallOptions callOptions) {
          return new AdminStub(channel, callOptions);
        }
      };
    return AdminStub.newStub(factory, channel);
  }

  /**
   * Creates a new blocking-style stub that supports unary and streaming output calls on the service
   */
  public static AdminBlockingStub newBlockingStub(
      org.apache.rocketmq.shaded.io.grpc.Channel channel) {
    org.apache.rocketmq.shaded.io.grpc.stub.AbstractStub.StubFactory<AdminBlockingStub> factory =
      new org.apache.rocketmq.shaded.io.grpc.stub.AbstractStub.StubFactory<AdminBlockingStub>() {
        @java.lang.Override
        public AdminBlockingStub newStub(org.apache.rocketmq.shaded.io.grpc.Channel channel, org.apache.rocketmq.shaded.io.grpc.CallOptions callOptions) {
          return new AdminBlockingStub(channel, callOptions);
        }
      };
    return AdminBlockingStub.newStub(factory, channel);
  }

  /**
   * Creates a new ListenableFuture-style stub that supports unary calls on the service
   */
  public static AdminFutureStub newFutureStub(
      org.apache.rocketmq.shaded.io.grpc.Channel channel) {
    org.apache.rocketmq.shaded.io.grpc.stub.AbstractStub.StubFactory<AdminFutureStub> factory =
      new org.apache.rocketmq.shaded.io.grpc.stub.AbstractStub.StubFactory<AdminFutureStub>() {
        @java.lang.Override
        public AdminFutureStub newStub(org.apache.rocketmq.shaded.io.grpc.Channel channel, org.apache.rocketmq.shaded.io.grpc.CallOptions callOptions) {
          return new AdminFutureStub(channel, callOptions);
        }
      };
    return AdminFutureStub.newStub(factory, channel);
  }

  /**
   */
  public static abstract class AdminImplBase implements org.apache.rocketmq.shaded.io.grpc.BindableService {

    /**
     */
    public void changeLogLevel(apache.rocketmq.v2.ChangeLogLevelRequest request,
        org.apache.rocketmq.shaded.io.grpc.stub.StreamObserver<apache.rocketmq.v2.ChangeLogLevelResponse> responseObserver) {
      org.apache.rocketmq.shaded.io.grpc.stub.ServerCalls.asyncUnimplementedUnaryCall(getChangeLogLevelMethod(), responseObserver);
    }

    @java.lang.Override public final org.apache.rocketmq.shaded.io.grpc.ServerServiceDefinition bindService() {
      return org.apache.rocketmq.shaded.io.grpc.ServerServiceDefinition.builder(getServiceDescriptor())
          .addMethod(
            getChangeLogLevelMethod(),
            org.apache.rocketmq.shaded.io.grpc.stub.ServerCalls.asyncUnaryCall(
              new MethodHandlers<
                apache.rocketmq.v2.ChangeLogLevelRequest,
                apache.rocketmq.v2.ChangeLogLevelResponse>(
                  this, METHODID_CHANGE_LOG_LEVEL)))
          .build();
    }
  }

  /**
   */
  public static final class AdminStub extends org.apache.rocketmq.shaded.io.grpc.stub.AbstractAsyncStub<AdminStub> {
    private AdminStub(
        org.apache.rocketmq.shaded.io.grpc.Channel channel, org.apache.rocketmq.shaded.io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected AdminStub build(
        org.apache.rocketmq.shaded.io.grpc.Channel channel, org.apache.rocketmq.shaded.io.grpc.CallOptions callOptions) {
      return new AdminStub(channel, callOptions);
    }

    /**
     */
    public void changeLogLevel(apache.rocketmq.v2.ChangeLogLevelRequest request,
        org.apache.rocketmq.shaded.io.grpc.stub.StreamObserver<apache.rocketmq.v2.ChangeLogLevelResponse> responseObserver) {
      org.apache.rocketmq.shaded.io.grpc.stub.ClientCalls.asyncUnaryCall(
          getChannel().newCall(getChangeLogLevelMethod(), getCallOptions()), request, responseObserver);
    }
  }

  /**
   */
  public static final class AdminBlockingStub extends org.apache.rocketmq.shaded.io.grpc.stub.AbstractBlockingStub<AdminBlockingStub> {
    private AdminBlockingStub(
        org.apache.rocketmq.shaded.io.grpc.Channel channel, org.apache.rocketmq.shaded.io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected AdminBlockingStub build(
        org.apache.rocketmq.shaded.io.grpc.Channel channel, org.apache.rocketmq.shaded.io.grpc.CallOptions callOptions) {
      return new AdminBlockingStub(channel, callOptions);
    }

    /**
     */
    public apache.rocketmq.v2.ChangeLogLevelResponse changeLogLevel(apache.rocketmq.v2.ChangeLogLevelRequest request) {
      return org.apache.rocketmq.shaded.io.grpc.stub.ClientCalls.blockingUnaryCall(
          getChannel(), getChangeLogLevelMethod(), getCallOptions(), request);
    }
  }

  /**
   */
  public static final class AdminFutureStub extends org.apache.rocketmq.shaded.io.grpc.stub.AbstractFutureStub<AdminFutureStub> {
    private AdminFutureStub(
        org.apache.rocketmq.shaded.io.grpc.Channel channel, org.apache.rocketmq.shaded.io.grpc.CallOptions callOptions) {
      super(channel, callOptions);
    }

    @java.lang.Override
    protected AdminFutureStub build(
        org.apache.rocketmq.shaded.io.grpc.Channel channel, org.apache.rocketmq.shaded.io.grpc.CallOptions callOptions) {
      return new AdminFutureStub(channel, callOptions);
    }

    /**
     */
    public org.apache.rocketmq.shaded.com.google.common.util.concurrent.ListenableFuture<apache.rocketmq.v2.ChangeLogLevelResponse> changeLogLevel(
        apache.rocketmq.v2.ChangeLogLevelRequest request) {
      return org.apache.rocketmq.shaded.io.grpc.stub.ClientCalls.futureUnaryCall(
          getChannel().newCall(getChangeLogLevelMethod(), getCallOptions()), request);
    }
  }

  private static final int METHODID_CHANGE_LOG_LEVEL = 0;

  private static final class MethodHandlers<Req, Resp> implements
      org.apache.rocketmq.shaded.io.grpc.stub.ServerCalls.UnaryMethod<Req, Resp>,
      org.apache.rocketmq.shaded.io.grpc.stub.ServerCalls.ServerStreamingMethod<Req, Resp>,
      org.apache.rocketmq.shaded.io.grpc.stub.ServerCalls.ClientStreamingMethod<Req, Resp>,
      org.apache.rocketmq.shaded.io.grpc.stub.ServerCalls.BidiStreamingMethod<Req, Resp> {
    private final AdminImplBase serviceImpl;
    private final int methodId;

    MethodHandlers(AdminImplBase serviceImpl, int methodId) {
      this.serviceImpl = serviceImpl;
      this.methodId = methodId;
    }

    @java.lang.Override
    @java.lang.SuppressWarnings("unchecked")
    public void invoke(Req request, org.apache.rocketmq.shaded.io.grpc.stub.StreamObserver<Resp> responseObserver) {
      switch (methodId) {
        case METHODID_CHANGE_LOG_LEVEL:
          serviceImpl.changeLogLevel((apache.rocketmq.v2.ChangeLogLevelRequest) request,
              (org.apache.rocketmq.shaded.io.grpc.stub.StreamObserver<apache.rocketmq.v2.ChangeLogLevelResponse>) responseObserver);
          break;
        default:
          throw new AssertionError();
      }
    }

    @java.lang.Override
    @java.lang.SuppressWarnings("unchecked")
    public org.apache.rocketmq.shaded.io.grpc.stub.StreamObserver<Req> invoke(
        org.apache.rocketmq.shaded.io.grpc.stub.StreamObserver<Resp> responseObserver) {
      switch (methodId) {
        default:
          throw new AssertionError();
      }
    }
  }

  private static abstract class AdminBaseDescriptorSupplier
      implements org.apache.rocketmq.shaded.io.grpc.protobuf.ProtoFileDescriptorSupplier, org.apache.rocketmq.shaded.io.grpc.protobuf.ProtoServiceDescriptorSupplier {
    AdminBaseDescriptorSupplier() {}

    @java.lang.Override
    public org.apache.rocketmq.shaded.com.google.protobuf.Descriptors.FileDescriptor getFileDescriptor() {
      return apache.rocketmq.v2.MQAdmin.getDescriptor();
    }

    @java.lang.Override
    public org.apache.rocketmq.shaded.com.google.protobuf.Descriptors.ServiceDescriptor getServiceDescriptor() {
      return getFileDescriptor().findServiceByName("Admin");
    }
  }

  private static final class AdminFileDescriptorSupplier
      extends AdminBaseDescriptorSupplier {
    AdminFileDescriptorSupplier() {}
  }

  private static final class AdminMethodDescriptorSupplier
      extends AdminBaseDescriptorSupplier
      implements org.apache.rocketmq.shaded.io.grpc.protobuf.ProtoMethodDescriptorSupplier {
    private final String methodName;

    AdminMethodDescriptorSupplier(String methodName) {
      this.methodName = methodName;
    }

    @java.lang.Override
    public org.apache.rocketmq.shaded.com.google.protobuf.Descriptors.MethodDescriptor getMethodDescriptor() {
      return getServiceDescriptor().findMethodByName(methodName);
    }
  }

  private static volatile org.apache.rocketmq.shaded.io.grpc.ServiceDescriptor serviceDescriptor;

  public static org.apache.rocketmq.shaded.io.grpc.ServiceDescriptor getServiceDescriptor() {
    org.apache.rocketmq.shaded.io.grpc.ServiceDescriptor result = serviceDescriptor;
    if (result == null) {
      synchronized (AdminGrpc.class) {
        result = serviceDescriptor;
        if (result == null) {
          serviceDescriptor = result = org.apache.rocketmq.shaded.io.grpc.ServiceDescriptor.newBuilder(SERVICE_NAME)
              .setSchemaDescriptor(new AdminFileDescriptorSupplier())
              .addMethod(getChangeLogLevelMethod())
              .build();
        }
      }
    }
    return result;
  }
}
