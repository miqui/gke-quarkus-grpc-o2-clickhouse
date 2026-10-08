package dev.miqui.messageservice;

import com.google.protobuf.InvalidProtocolBufferException;
import com.google.rpc.BadRequest;
import com.google.rpc.ErrorInfo;
import dev.miqui.messageservice.v1.Author;
import dev.miqui.messageservice.v1.AuthorServiceGrpc;
import dev.miqui.messageservice.v1.CreateAuthorRequest;
import dev.miqui.messageservice.v1.CreateMessageRequest;
import dev.miqui.messageservice.v1.Message;
import dev.miqui.messageservice.v1.MessageServiceGrpc;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.protobuf.StatusProto;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.function.Executable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.grpc.test.autoconfigure.LocalGrpcServerPort;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Full application on random ports (real Netty gRPC server, so the 16 KiB inbound limit and all
 * interceptors apply) against Testcontainers Postgres, with the recording cache instead of
 * Hazelcast. One Spring context is shared by every subclass; tables are emptied before each test.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.grpc.server.port=0",
                "app.cache.type=memory",
                "management.otlp.metrics.export.enabled=false",
                "management.tracing.export.otlp.enabled=false",
        })
@Import(TestcontainersConfig.class)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public abstract class GrpcTestSupport {

    @LocalGrpcServerPort
    int grpcPort;

    @Autowired
    protected JdbcClient jdbc;

    @Autowired
    protected RecordingMessageCache cache;

    protected ManagedChannel channel;
    protected MessageServiceGrpc.MessageServiceBlockingStub messages;
    protected AuthorServiceGrpc.AuthorServiceBlockingStub authors;

    @BeforeAll
    void openChannel() {
        channel = ManagedChannelBuilder.forAddress("localhost", grpcPort).usePlaintext().build();
        messages = MessageServiceGrpc.newBlockingStub(channel);
        authors = AuthorServiceGrpc.newBlockingStub(channel);
    }

    @AfterAll
    void closeChannel() {
        channel.shutdownNow();
    }

    @BeforeEach
    void emptyTables() {
        jdbc.sql("TRUNCATE messages, authors").update();
        cache.reset();
    }

    protected Author createAuthor(String name, String email) {
        return authors.createAuthor(CreateAuthorRequest.newBuilder().setName(name).setEmail(email).build());
    }

    protected Author createAuthor() {
        return createAuthor("Alice", "alice-" + UUID.randomUUID() + "@example.com");
    }

    protected Message createMessage(String authorId, String title) {
        return messages.createMessage(CreateMessageRequest.newBuilder()
                .setTitle(title).setContent("Body of " + title).setAuthorId(authorId).build());
    }

    protected Message createMessage(String authorId) {
        return createMessage(authorId, "Hello");
    }

    /** What a client can read from a failed call: status, ErrorInfo reason, field violations. */
    protected record RpcError(Status.Code code, String description, String reason, Map<String, String> violations) {
    }

    protected static RpcError rpcError(Executable call) {
        StatusRuntimeException e = assertThrows(StatusRuntimeException.class, call);
        com.google.rpc.Status status = StatusProto.fromThrowable(e);
        assertThat(status).as("rich status details").isNotNull();
        String reason = null;
        Map<String, String> violations = new LinkedHashMap<>();
        try {
            for (var detail : status.getDetailsList()) {
                if (detail.is(ErrorInfo.class)) {
                    ErrorInfo info = detail.unpack(ErrorInfo.class);
                    assertThat(info.getDomain()).isEqualTo("message-service.miqui.dev");
                    reason = info.getReason();
                } else if (detail.is(BadRequest.class)) {
                    detail.unpack(BadRequest.class).getFieldViolationsList()
                            .forEach(v -> violations.put(v.getField(), v.getDescription()));
                }
            }
        } catch (InvalidProtocolBufferException ex) {
            throw new AssertionError(ex);
        }
        return new RpcError(e.getStatus().getCode(), e.getStatus().getDescription(), reason, violations);
    }
}
