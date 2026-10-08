package dev.miqui.messageservice.service;

import dev.miqui.messageservice.RecordingMessageCache;
import dev.miqui.messageservice.error.ApiException;
import dev.miqui.messageservice.repo.AuthorRepository;
import dev.miqui.messageservice.repo.MessageRepository;
import dev.miqui.messageservice.v1.Author;
import dev.miqui.messageservice.v1.Message;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MessagesCacheTest {

    private final UUID id = UUID.randomUUID();
    private final UUID authorId = UUID.randomUUID();
    private final Author author = Author.newBuilder().setId(authorId.toString()).setName("Original").build();
    private final Message original = Message.newBuilder().setId(id.toString())
            .setTitle("Title").setContent("Original").setAuthor(author).build();
    private final MessageRepository repository = mock(MessageRepository.class);
    private final AuthorRepository authors = mock(AuthorRepository.class);

    private Messages service(RecordingMessageCache cache) {
        return new Messages(repository, authors, cache, TransactionOperations.withoutTransaction());
    }

    @Test
    void cacheHitsReloadTheAuthorWithoutReloadingTheMessage() {
        var cache = new RecordingMessageCache();
        cache.put(original);
        Author updated = author.toBuilder().setName("Updated").setEmail("updated@example.com").build();
        when(authors.find(authorId)).thenReturn(Optional.of(updated));

        assertThat(service(cache).get(id)).isEqualTo(original.toBuilder().setAuthor(updated).build());
        org.mockito.Mockito.verifyNoInteractions(repository);
    }

    @Test
    void deleteWaitsForAnInFlightCacheFillAcrossServiceInstances() throws Exception {
        assertMutationWaitsForCacheFill(true);
    }

    @Test
    void updateWaitsForAnInFlightCacheFillAcrossServiceInstances() throws Exception {
        assertMutationWaitsForCacheFill(false);
    }

    private void assertMutationWaitsForCacheFill(boolean delete) throws Exception {
        var row = new AtomicReference<>(original);
        when(repository.find(id)).thenAnswer(call -> Optional.ofNullable(row.get()));
        when(repository.delete(id)).thenAnswer(call -> row.getAndSet(null) != null);
        Message updated = original.toBuilder().setContent("Updated").setVersion(1).build();
        when(repository.updateIfVersion(id, null, "Updated", 0)).thenAnswer(call -> {
            assertThat(row.compareAndSet(original, updated)).isTrue();
            return Optional.of(id);
        });
        when(authors.find(authorId)).thenReturn(Optional.of(author));

        var cache = new PausingCache();
        Messages reader = service(cache);
        Messages writer = service(cache);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var read = pool.submit(() -> reader.get(id));
            try {
                assertThat(cache.filling.await(5, TimeUnit.SECONDS)).isTrue();
                var mutation = pool.submit(() -> {
                    if (delete) {
                        writer.delete(id);
                        return null;
                    }
                    return writer.update(id, null, "Updated", 0);
                });
                assertThat(cache.mutationAttempted.await(5, TimeUnit.SECONDS)).isTrue();
                assertThrows(TimeoutException.class, () -> mutation.get(200, TimeUnit.MILLISECONDS));
                assertThat(row.get()).isEqualTo(original);

                cache.resume.countDown();
                assertThat(read.get(5, TimeUnit.SECONDS)).isEqualTo(original);
                assertThat(mutation.get(5, TimeUnit.SECONDS)).isEqualTo(delete ? null : updated);
                assertThat(cache.data).doesNotContainKey(id);
                if (delete) {
                    assertThrows(ApiException.NotFound.class, () -> reader.get(id));
                } else {
                    assertThat(reader.get(id)).isEqualTo(updated);
                }
            } finally {
                cache.resume.countDown();
            }
        }
    }

    private static class PausingCache extends RecordingMessageCache {
        private final CountDownLatch filling = new CountDownLatch(1);
        private final CountDownLatch resume = new CountDownLatch(1);
        private final CountDownLatch mutationAttempted = new CountDownLatch(1);
        private final AtomicInteger lockAttempts = new AtomicInteger();

        @Override
        public <T> T withLock(UUID id, Supplier<T> operation) {
            if (lockAttempts.incrementAndGet() == 2) {
                mutationAttempted.countDown();
            }
            return super.withLock(id, operation);
        }

        @Override
        public void put(Message message) {
            filling.countDown();
            try {
                if (!resume.await(5, TimeUnit.SECONDS)) {
                    throw new IllegalStateException("Timed out waiting to resume cache fill");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Interrupted cache fill", e);
            }
            super.put(message);
        }
    }
}
