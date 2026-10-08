package dev.miqui.messageservice;

import dev.miqui.messageservice.config.WelcomeSeeder;
import dev.miqui.messageservice.v1.GetMessageRequest;
import io.grpc.health.v1.HealthCheckRequest;
import io.grpc.health.v1.HealthCheckResponse;
import io.grpc.health.v1.HealthGrpc;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.availability.AvailabilityChangeEvent;
import org.springframework.boot.availability.ReadinessState;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.ApplicationContext;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/** Probes, health/metrics plumbing and the startup seed. */
class PlatformTest extends GrpcTestSupport {

    @LocalServerPort
    int managementPort;

    @Autowired
    ApplicationContext context;

    @Autowired
    MeterRegistry registry;

    @Autowired
    WelcomeSeeder seeder;

    private final HttpClient http = HttpClient.newHttpClient();

    private HttpResponse<String> probe(String name) throws Exception {
        return http.send(HttpRequest.newBuilder(URI.create(
                "http://localhost:" + managementPort + "/actuator/health/" + name)).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void livenessIsUpWhileTheCacheClientRuns() throws Exception {
        var response = probe("liveness");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"status\":\"UP\"");
    }

    @Test
    void livenessIsDownIfTheCacheClientHasShutDown() throws Exception {
        // A Hazelcast client that exhausted its connect timeout shuts down for good; only a
        // container restart recovers, so liveness must fail (see HealthConfig).
        cache.connected = false;
        var response = probe("liveness");
        assertThat(response.statusCode()).isEqualTo(503);
        assertThat(response.body()).contains("\"status\":\"DOWN\"");
    }

    @Test
    void readinessIsUpOnceStartedAndDownWhileRefusingTraffic() throws Exception {
        assertThat(probe("readiness").statusCode()).isEqualTo(200);
        AvailabilityChangeEvent.publish(context, ReadinessState.REFUSING_TRAFFIC); // what SIGTERM does
        try {
            assertThat(probe("readiness").statusCode()).isEqualTo(503);
        } finally {
            AvailabilityChangeEvent.publish(context, ReadinessState.ACCEPTING_TRAFFIC);
        }
    }

    @Test
    void grpcHealthServiceIsServing() {
        var response = HealthGrpc.newBlockingStub(channel).check(HealthCheckRequest.getDefaultInstance());
        assertThat(response.getStatus()).isEqualTo(HealthCheckResponse.ServingStatus.SERVING);
    }

    @Test
    void failedCallsAreCountedByErrorCode() {
        String[] tags = {"rpc_service", "message.v1.MessageService", "rpc_method", "GetMessage",
                "grpc_status_code", "NOT_FOUND", "error_code", "NOT_FOUND"};
        Counter before = registry.find("grpc.errors").tags(tags).counter();
        double initial = before == null ? 0 : before.count();

        rpcError(() -> messages.getMessage(GetMessageRequest.newBuilder().setId(UUID.randomUUID().toString()).build()));

        Counter after = registry.get("grpc.errors").tags(tags).counter();
        assertThat(after.count()).isEqualTo(initial + 1);
    }

    @Test
    void seedIsIdempotentEvenWhenReplicasStartTogether() {
        CompletableFuture.allOf(IntStream.range(0, 3)
                .mapToObj(i -> CompletableFuture.runAsync(seeder::seed))
                .toArray(CompletableFuture[]::new)).join();
        seeder.seed();

        assertThat(jdbc.sql("SELECT count(*) FROM messages").query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM authors WHERE email = :e")
                .param("e", WelcomeSeeder.SYSTEM_EMAIL).query(Long.class).single()).isEqualTo(1);
    }
}
