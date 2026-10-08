package dev.miqui.messageservice.grpc;

import com.google.rpc.ErrorInfo;
import io.grpc.Status;
import io.grpc.StatusException;
import io.grpc.protobuf.StatusProto;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GrpcErrorHandlerTest {

    @Test
    void unexpectedExceptionsBecomeAGenericInternalErrorWithoutDetails() throws Exception {
        StatusException e = GrpcErrorHandler.toStatusException(new IllegalStateException("secret internals"));

        assertThat(e.getStatus().getCode()).isEqualTo(Status.Code.INTERNAL);
        assertThat(e.getStatus().getDescription()).doesNotContain("secret");
        assertThat(e.getStatus().getCause()).isNull();

        com.google.rpc.Status status = StatusProto.fromThrowable(e);
        assertThat(status).isNotNull();
        assertThat(status.toString()).doesNotContain("secret");
        assertThat(status.getDetails(0).unpack(ErrorInfo.class).getReason()).isEqualTo("INTERNAL_SERVER_ERROR");
    }

    @Test
    void statusExceptionsPassThrough() {
        StatusException original = Status.UNAVAILABLE.asException();
        assertThat(GrpcErrorHandler.toStatusException(original)).isSameAs(original);
    }
}
