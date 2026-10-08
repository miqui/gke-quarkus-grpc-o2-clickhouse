package dev.miqui.messageservice.grpc;

import io.grpc.stub.StreamObserver;

import java.util.function.Supplier;

/**
 * Runs a unary RPC body and answers with its result, or with the google.rpc.Status its exception
 * maps to ({@link GrpcErrorHandler#toStatusException}).
 *
 * <p>Errors are reported through {@code onError} here rather than thrown to Spring gRPC's
 * exception-handler interceptor on purpose: that interceptor closes the call outside the
 * observation and metrics interceptors, which then record the status as UNKNOWN. Answering from
 * inside the call makes the real status flow through every interceptor's {@code close()}.
 */
public final class Rpc {

    private Rpc() {
    }

    public static <T> void unary(StreamObserver<T> response, Supplier<T> body) {
        T value;
        try {
            value = body.get();
        } catch (RuntimeException e) {
            response.onError(GrpcErrorHandler.toStatusException(e));
            return;
        }
        response.onNext(value);
        response.onCompleted();
    }
}
