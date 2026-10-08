package dev.miqui.messageservice.service;

import dev.miqui.messageservice.cache.MessageCache;
import dev.miqui.messageservice.error.ApiException;
import dev.miqui.messageservice.repo.AuthorRepository;
import dev.miqui.messageservice.repo.MessageRepository;
import dev.miqui.messageservice.repo.Rows;
import dev.miqui.messageservice.v1.ListMessagesResponse;
import dev.miqui.messageservice.v1.Message;
import io.grpc.Status;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

import java.util.Optional;
import java.util.UUID;

/**
 * Message use cases. Transactions are explicit (TransactionOperations) rather than
 * {@code @Transactional}, so cache evictions visibly happen after the commit.
 */
@Service
public class Messages {

    private final MessageRepository messages;
    private final AuthorRepository authors;
    private final MessageCache cache;
    private final TransactionOperations tx;

    public Messages(MessageRepository messages, AuthorRepository authors, MessageCache cache,
                    TransactionOperations tx) {
        this.messages = messages;
        this.authors = authors;
        this.cache = cache;
        this.tx = tx;
    }

    public ListMessagesResponse list(int limit, int offset) {
        return tx.execute(s -> ListMessagesResponse.newBuilder()
                .addAllItems(messages.page(limit, offset))
                .setTotalCount(messages.count())
                .build());
    }

    /** Cache-aside: read the cache first; on a miss load from the DB and populate the cache. */
    public Message get(UUID id) {
        return cache.withLock(id, () -> {
            Optional<Message> cached = cache.get(id);
            if (cached.isPresent()) {
                Message message = cached.get();
                UUID authorId = UUID.fromString(message.getAuthor().getId());
                return message.toBuilder()
                        .setAuthor(authors.find(authorId).orElseThrow(() -> authorNotFound(authorId)))
                        .build();
            }
            Message message = messages.find(id).orElseThrow(() -> notFound(id));
            cache.put(message);
            return message;
        });
    }

    public Message create(String title, String content, UUID authorId) {
        try {
            return tx.execute(s -> {
                if (!authors.exists(authorId)) {
                    throw authorNotFound(authorId);
                }
                UUID id = messages.insert(title, content, authorId);
                return messages.find(id).orElseThrow();
            });
        } catch (DataIntegrityViolationException e) {
            // The author was deleted between the check and the insert.
            if (Rows.hasSqlState(e, Rows.FK_VIOLATION)) {
                throw authorNotFound(authorId);
            }
            throw e;
        }
    }

    /**
     * Applies only if the row is still at the version the caller read. When nothing matched: 404 if
     * the id doesn't exist, otherwise ABORTED (the row moved on).
     */
    public Message update(UUID id, String title, String content, int version) {
        return cache.withLock(id, () -> {
            Optional<Message> updated = tx.execute(s -> messages.updateIfVersion(id, title, content, version)
                    .map(updatedId -> messages.find(updatedId).orElseThrow()));
            cache.evict(id);
            if (updated.isEmpty()) {
                if (!messages.exists(id)) {
                    throw notFound(id);
                }
                throw new ApiException.Conflict(Status.Code.ABORTED, "Message with ID '" + id
                        + "' has changed since version " + version + " was read; refetch and retry.");
            }
            return updated.get();
        });
    }

    public void delete(UUID id) {
        cache.withLock(id, () -> {
            boolean deleted = messages.delete(id);
            cache.evict(id);
            if (!deleted) {
                throw notFound(id);
            }
            return null;
        });
    }

    private static ApiException notFound(UUID id) {
        return new ApiException.NotFound("Message with ID '" + id + "' was not found.");
    }

    private static ApiException authorNotFound(UUID id) {
        return new ApiException.NotFound("Author with ID '" + id + "' was not found.");
    }
}
