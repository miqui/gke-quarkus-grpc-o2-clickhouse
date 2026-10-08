package dev.miqui.messageservice.cache;

import dev.miqui.messageservice.v1.Message;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/** Cache-aside store for GetMessage (key: message id). */
public interface MessageCache {

    /** Serializes cache fills and mutations for an id across all application replicas. */
    <T> T withLock(UUID id, Supplier<T> operation);

    Optional<Message> get(UUID id);

    void put(Message message);

    void evict(UUID id);

    /** False once the client has given up for good; a restart is the only recovery. */
    boolean connected();
}
