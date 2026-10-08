package dev.miqui.messageservice.config;

import dev.miqui.messageservice.cache.MessageCache;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
public class HealthConfig {

    /**
     * Contributor {@code hazelcastClient}, part of the liveness group. A client that gave up
     * reconnecting (the member was gone longer than the connect timeout, e.g. rescheduled by a
     * node scale-down) shuts itself down for good, and every cache call then fails - restarting
     * the container is the only recovery. A local lifecycle flag, not a network call, so a slow
     * member can't fail the probe.
     */
    @Bean
    HealthIndicator hazelcastClientHealthIndicator(MessageCache messageCache) {
        return () -> messageCache.connected()
                ? Health.up().build()
                : Health.down().withDetail("reason", "cache client shut down").build();
    }
}
