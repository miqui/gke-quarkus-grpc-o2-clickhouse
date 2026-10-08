package dev.miqui.messageservice.config;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionOperations;

/**
 * Pre-populates a welcome message (and its system author) on a fresh database. Runs before the
 * application reports ready. Idempotent, and safe with all replicas starting at once: a
 * transaction-scoped advisory lock serialises the check-then-insert, so exactly one welcome
 * message is created.
 */
@Component
@ConditionalOnProperty(name = "app.seed.enabled", havingValue = "true", matchIfMissing = true)
public class WelcomeSeeder implements ApplicationRunner {

    public static final String SYSTEM_EMAIL = "system@message-service.local";
    // Arbitrary fixed key; distinct from Flyway's own advisory lock.
    static final long SEED_LOCK_KEY = 7_265_490_001L;

    private final JdbcClient jdbc;
    private final TransactionOperations tx;

    public WelcomeSeeder(JdbcClient jdbc, TransactionOperations tx) {
        this.jdbc = jdbc;
        this.tx = tx;
    }

    @Override
    public void run(ApplicationArguments args) {
        seed();
    }

    public void seed() {
        tx.executeWithoutResult(s -> {
            jdbc.sql("SELECT pg_advisory_xact_lock(:key)").param("key", SEED_LOCK_KEY).query().singleRow();
            long messages = jdbc.sql("SELECT count(*) FROM messages").query(Long.class).single();
            if (messages > 0) {
                return;
            }
            jdbc.sql("INSERT INTO authors (name, email) VALUES ('system', :email) ON CONFLICT (email) DO NOTHING")
                    .param("email", SYSTEM_EMAIL)
                    .update();
            jdbc.sql("""
                            INSERT INTO messages (title, content, author_id)
                            SELECT :title, :content, id FROM authors WHERE email = :email
                            """)
                    .param("title", "Welcome to the Kubernetes gRPC API")
                    .param("content", "A sample message backed by Spring Boot gRPC and Cloud SQL for PostgreSQL on GKE.")
                    .param("email", SYSTEM_EMAIL)
                    .update();
        });
    }
}
