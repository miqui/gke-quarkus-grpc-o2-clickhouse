package dev.miqui.messageservice.grpc;

import com.google.rpc.ErrorInfo;
import io.grpc.ForwardingServerCall;
import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import io.grpc.protobuf.StatusProto;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.grpc.server.GlobalServerInterceptor;
import org.springframework.stereotype.Component;

/**
 * Sees the final status of every RPC (wherever it was produced) and records:
 * <ul>
 *   <li>{@code grpc_errors_total{rpc_service, rpc_method, grpc_status_code, error_code}} - error_code is the ErrorInfo reason
 *       (BAD_USER_INPUT, NOT_FOUND, CONFLICT, INTERNAL_SERVER_ERROR), or the bare gRPC code for
 *       statuses the service didn't produce (e.g. RESOURCE_EXHAUSTED for an oversized message);</li>
 *   <li>one structured log line per RPC.</li>
 * </ul>
 * Latency/throughput come from Spring gRPC's observation ({@code grpc_server_*}). Health checks and
 * reflection are skipped here and in {@code ObservationConfig}.
 */
@Component
@GlobalServerInterceptor
public class RpcMetricsInterceptor implements ServerInterceptor {

    private static final Logger log = LoggerFactory.getLogger("dev.miqui.messageservice.access");

    private final MeterRegistry registry;

    public RpcMetricsInterceptor(MeterRegistry registry) {
        this.registry = registry;
    }

    public static boolean isInfrastructure(String fullMethodName) {
        return fullMethodName.startsWith("grpc.health.") || fullMethodName.startsWith("grpc.reflection.");
    }

    @Override
    public <Q, R> ServerCall.Listener<Q> interceptCall(ServerCall<Q, R> call, Metadata headers,
                                                       ServerCallHandler<Q, R> next) {
        var descriptor = call.getMethodDescriptor();
        String method = descriptor.getFullMethodName();
        if (isInfrastructure(method)) {
            return next.startCall(call, headers);
        }
        long start = System.nanoTime();
        return next.startCall(new ForwardingServerCall.SimpleForwardingServerCall<>(call) {
            @Override
            public void close(Status status, Metadata trailers) {
                double ms = (System.nanoTime() - start) / 1_000_000.0;
                if (!status.isOk()) {
                    Counter.builder("grpc.errors")
                            .description("gRPC calls that ended with a non-OK status, by method and error code")
                            // Same labels and values as grpc_server_*, so the two can be divided.
                            .tag("rpc_service", descriptor.getServiceName())
                            .tag("rpc_method", descriptor.getBareMethodName())
                            .tag("grpc_status_code", status.getCode().name())
                            .tag("error_code", errorCode(status, trailers))
                            .register(registry)
                            .increment();
                }
                log.atInfo()
                        .addKeyValue("rpc_method", method)
                        .addKeyValue("grpc_status", status.getCode().name())
                        .addKeyValue("duration_ms", Math.round(ms * 100) / 100.0)
                        .log("rpc");
                super.close(status, trailers);
            }
        }, headers);
    }

    static String errorCode(Status status, Metadata trailers) {
        com.google.rpc.Status details = StatusProto.fromStatusAndTrailers(status, trailers);
        for (var any : details.getDetailsList()) {
            if (any.is(ErrorInfo.class)) {
                try {
                    return any.unpack(ErrorInfo.class).getReason();
                } catch (com.google.protobuf.InvalidProtocolBufferException e) {
                    break;
                }
            }
        }
        return status.getCode().name();
    }
}
