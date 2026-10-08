package dev.miqui.messageservice.config;

import dev.miqui.messageservice.grpc.RpcMetricsInterceptor;
import io.micrometer.core.instrument.binder.grpc.GrpcServerObservationContext;
import io.micrometer.observation.ObservationPredicate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.server.observation.ServerRequestObservationContext;

@Configuration(proxyBeanMethods = false)
public class ObservationConfig {

    /**
     * No spans or grpc_server_* samples for grpc.health.v1 and reflection calls: health clients
     * poll every few seconds and would dominate the trace list (and OpenObserve's small PVC).
     */
    @Bean
    ObservationPredicate skipInfrastructureRpcs() {
        return (name, context) -> !(context instanceof GrpcServerObservationContext grpc
                && grpc.getServiceName() != null
                && RpcMetricsInterceptor.isInfrastructure(grpc.getServiceName() + "/"));
    }

    /**
     * The HTTP server (management port) only answers kubelet probes and LB health checks, so
     * none of its requests are worth a span.
     */
    @Bean
    ObservationPredicate skipHttpServerRequests() {
        return (name, context) -> !(context instanceof ServerRequestObservationContext);
    }
}
