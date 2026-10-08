package dev.miqui.messageservice.grpc;

import com.google.protobuf.Any;
import com.google.rpc.BadRequest;
import com.google.rpc.ErrorInfo;
import dev.miqui.messageservice.error.ApiException;
import io.grpc.StatusException;
import io.grpc.protobuf.StatusProto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.grpc.server.exception.GrpcExceptionHandler;
import org.springframework.stereotype.Component;

/**
 * Maps every exception a service throws to a google.rpc.Status: the gRPC code, a human message,
 * an ErrorInfo whose {@code reason} is the stable code clients switch on, and - for bad input - a
 * BadRequest with one FieldViolation per invalid field.
 *
 * <p>Anything unexpected is logged (with the trace id, via MDC) and answered with a generic
 * INTERNAL: no exception text or stack trace ever reaches the client.
 */
@Component
public class GrpcErrorHandler implements GrpcExceptionHandler {

    public static final String DOMAIN = "message-service.miqui.dev";
    public static final String INTERNAL_REASON = "INTERNAL_SERVER_ERROR";

    private static final Logger log = LoggerFactory.getLogger(GrpcErrorHandler.class);

    /** Fallback for anything that escapes {@link Rpc#unary}. */
    @Override
    public StatusException handleException(Throwable exception) {
        return toStatusException(exception);
    }

    public static StatusException toStatusException(Throwable exception) {
        if (exception instanceof StatusException se) {
            return se;
        }
        if (exception instanceof ApiException api) {
            com.google.rpc.Status.Builder status = com.google.rpc.Status.newBuilder()
                    .setCode(api.code().value())
                    .setMessage(api.getMessage())
                    .addDetails(Any.pack(errorInfo(api.reason())));
            if (api instanceof ApiException.BadInput bad) {
                status.addDetails(Any.pack(BadRequest.newBuilder().addAllFieldViolations(bad.violations()).build()));
            }
            return StatusProto.toStatusException(status.build());
        }
        log.error("unhandled exception", exception);
        return StatusProto.toStatusException(com.google.rpc.Status.newBuilder()
                .setCode(io.grpc.Status.Code.INTERNAL.value())
                .setMessage("An unexpected error occurred.")
                .addDetails(Any.pack(errorInfo(INTERNAL_REASON)))
                .build());
    }

    private static ErrorInfo errorInfo(String reason) {
        return ErrorInfo.newBuilder().setReason(reason).setDomain(DOMAIN).build();
    }
}
