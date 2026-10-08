package dev.miqui.messageservice.cache;

import com.hazelcast.core.HazelcastInstance;
import com.hazelcast.map.IMap;
import io.micrometer.observation.ObservationRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class HazelcastMessageCacheTest {

    private final UUID id = UUID.randomUUID();
    private final IMap<String, byte[]> map = mock();
    private HazelcastMessageCache cache;

    @BeforeEach
    void setUp() {
        HazelcastInstance client = mock(HazelcastInstance.class);
        when(client.<String, byte[]>getMap(HazelcastMessageCache.MAP_NAME)).thenReturn(map);
        cache = new HazelcastMessageCache(client, ObservationRegistry.NOOP);
    }

    @Test
    void locksBeforeTheOperationAndUnlocksAfterwards() throws Exception {
        when(map.tryLock(id.toString(), 5, TimeUnit.SECONDS)).thenReturn(true);
        Supplier<String> operation = mock();
        when(operation.get()).thenReturn("result");

        assertThat(cache.withLock(id, operation)).isEqualTo("result");

        var order = inOrder(map, operation);
        order.verify(map).tryLock(id.toString(), 5, TimeUnit.SECONDS);
        order.verify(operation).get();
        order.verify(map).unlock(id.toString());
    }

    @Test
    void releasesTheLockWhenTheOperationFails() throws Exception {
        when(map.tryLock(id.toString(), 5, TimeUnit.SECONDS)).thenReturn(true);
        var failure = new IllegalStateException("operation failed");

        assertThat(assertThrows(IllegalStateException.class, () -> cache.withLock(id, () -> {
            throw failure;
        }))).isSameAs(failure);
        verify(map).unlock(id.toString());
    }

    @Test
    void timeoutDoesNotRunTheOperationOrUnlockAnUnownedLock() {
        Supplier<String> operation = mock();

        assertThrows(IllegalStateException.class, () -> cache.withLock(id, operation));
        verify(operation, never()).get();
        verify(map, never()).unlock(id.toString());
    }

    @Test
    void interruptionIsPreservedAndDoesNotRunTheOperation() throws Exception {
        when(map.tryLock(id.toString(), 5, TimeUnit.SECONDS)).thenThrow(new InterruptedException());
        Supplier<String> operation = mock();

        try {
            assertThrows(IllegalStateException.class, () -> cache.withLock(id, operation));
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            verify(operation, never()).get();
            verify(map, never()).unlock(id.toString());
        } finally {
            Thread.interrupted();
        }
    }
}
