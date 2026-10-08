package dev.miqui.messageservice.config;

import com.hazelcast.client.HazelcastClient;
import com.hazelcast.client.config.ClientConfig;
import com.hazelcast.client.impl.connection.tcp.RoutingMode;
import com.hazelcast.core.HazelcastInstance;
import dev.miqui.messageservice.cache.HazelcastMessageCache;
import dev.miqui.messageservice.cache.MessageCache;
import io.micrometer.observation.ObservationRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * The Hazelcast client. The cache is mandatory: if the member is unreachable, startup fails
 * rather than running without it. The connect timeout is bounded (the client default retries
 * forever) so a dead member fails well inside the startup-probe budget.
 *
 * <p>Tests set {@code app.cache.type=memory} and provide their own {@link MessageCache}.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "app.cache.type", havingValue = "hazelcast", matchIfMissing = true)
public class CacheConfig {

    public static final String CLUSTER_NAME = "message-service-cache";

    @ConfigurationProperties("app.hazelcast")
    public record HazelcastProperties(String host, int port, Duration connectTimeout) {
        public HazelcastProperties {
            host = host == null ? "localhost" : host;
            port = port == 0 ? 5701 : port;
            connectTimeout = connectTimeout == null ? Duration.ofSeconds(20) : connectTimeout;
        }
    }

    @Bean(destroyMethod = "shutdown")
    HazelcastInstance hazelcastClient(HazelcastProperties props) {
        ClientConfig config = new ClientConfig();
        config.setClusterName(CLUSTER_NAME);
        config.getNetworkConfig().addAddress(props.host() + ":" + props.port());
        // One member behind a ClusterIP Service: talk to the address given, not to every member.
        config.getNetworkConfig().getClusterRoutingConfig().setRoutingMode(RoutingMode.SINGLE_MEMBER);
        config.getConnectionStrategyConfig().getConnectionRetryConfig()
                .setClusterConnectTimeoutMillis(props.connectTimeout().toMillis());
        config.setProperty("hazelcast.logging.type", "slf4j");
        return HazelcastClient.newHazelcastClient(config);
    }

    @Bean
    MessageCache messageCache(HazelcastInstance hazelcastClient, ObservationRegistry observations) {
        return new HazelcastMessageCache(hazelcastClient, observations);
    }
}
