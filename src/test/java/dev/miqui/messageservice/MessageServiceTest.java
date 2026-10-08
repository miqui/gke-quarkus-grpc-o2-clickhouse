package dev.miqui.messageservice;

import dev.miqui.messageservice.RecordingMessageCache.Call;
import dev.miqui.messageservice.v1.CreateMessageRequest;
import dev.miqui.messageservice.v1.DeleteMessageRequest;
import dev.miqui.messageservice.v1.GetMessageRequest;
import dev.miqui.messageservice.v1.ListMessagesRequest;
import dev.miqui.messageservice.v1.ListMessagesResponse;
import dev.miqui.messageservice.v1.Message;
import dev.miqui.messageservice.v1.UpdateMessageRequest;
import io.grpc.Status.Code;
import io.grpc.StatusRuntimeException;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

class MessageServiceTest extends GrpcTestSupport {

    private GetMessageRequest get(String id) {
        return GetMessageRequest.newBuilder().setId(id).build();
    }

    private UpdateMessageRequest.Builder update(String id, String content, int version) {
        return UpdateMessageRequest.newBuilder().setId(id).setContent(content).setVersion(version);
    }

    @Test
    void createTrimsAndEmbedsTheAuthor() {
        var author = createAuthor();
        Message m = messages.createMessage(CreateMessageRequest.newBuilder()
                .setTitle("  Hi  ").setContent(" there ").setAuthorId(author.getId()).build());

        assertThat(UUID.fromString(m.getId())).isNotNull();
        assertThat(m.getTitle()).isEqualTo("Hi");
        assertThat(m.getContent()).isEqualTo("there");
        assertThat(m.getVersion()).isZero();
        assertThat(m.hasCreatedAt()).isTrue();
        assertThat(m.getAuthor()).isEqualTo(author);
    }

    @Test
    void validationCollectsAllErrors() {
        var error = rpcError(() -> messages.createMessage(CreateMessageRequest.newBuilder()
                .setTitle(" ").setContent("x".repeat(1001)).setAuthorId("nope").build()));

        assertThat(error.code()).isEqualTo(Code.INVALID_ARGUMENT);
        assertThat(error.reason()).isEqualTo("BAD_USER_INPUT");
        assertThat(error.violations()).containsExactly(
                java.util.Map.entry("title", "title is required and cannot be blank"),
                java.util.Map.entry("content", "content cannot exceed 1000 characters"),
                java.util.Map.entry("author_id", "author_id must be a valid UUID"));
    }

    @Test
    void createRejectsNulInTitleAndContentBeforeInserting() {
        var author = createAuthor();
        var error = rpcError(() -> messages.createMessage(CreateMessageRequest.newBuilder()
                .setTitle("Bad\0title").setContent("Bad\0content").setAuthorId(author.getId()).build()));

        assertThat(error.code()).isEqualTo(Code.INVALID_ARGUMENT);
        assertThat(error.reason()).isEqualTo("BAD_USER_INPUT");
        assertThat(error.violations()).containsExactly(
                java.util.Map.entry("title", "title cannot contain NUL characters"),
                java.util.Map.entry("content", "content cannot contain NUL characters"));
        assertThat(jdbc.sql("SELECT count(*) FROM messages").query(Long.class).single()).isZero();
    }

    @Test
    void updateRejectsNulInTitleAndContentWithoutChangingTheMessage() {
        Message original = createMessage(createAuthor().getId());
        var error = rpcError(() -> messages.updateMessage(update(original.getId(), "Bad\0content", 0)
                .setTitle("Bad\0title").build()));

        assertThat(error.code()).isEqualTo(Code.INVALID_ARGUMENT);
        assertThat(error.reason()).isEqualTo("BAD_USER_INPUT");
        assertThat(error.violations()).containsExactly(
                java.util.Map.entry("title", "title cannot contain NUL characters"),
                java.util.Map.entry("content", "content cannot contain NUL characters"));
        assertThat(messages.getMessage(get(original.getId()))).isEqualTo(original);
    }

    @Test
    void createWithUnknownAuthorIsNotFound() {
        var error = rpcError(() -> createMessage(UUID.randomUUID().toString()));
        assertThat(error.code()).isEqualTo(Code.NOT_FOUND);
        assertThat(error.reason()).isEqualTo("NOT_FOUND");
    }

    @Test
    void getIsCacheAside() {
        String id = createMessage(createAuthor().getId()).getId();
        UUID uuid = UUID.fromString(id);

        Message first = messages.getMessage(get(id)); // miss: loads from the DB, populates the cache
        assertThat(cache.calls).containsExactly(new Call("get", uuid), new Call("set", uuid));

        cache.calls.clear();
        Message second = messages.getMessage(get(id)); // hit: served from the cache
        assertThat(second).isEqualTo(first);
        assertThat(cache.calls).containsExactly(new Call("get", uuid));
    }

    @Test
    void getUnknownOrMalformedId() {
        var missing = rpcError(() -> messages.getMessage(get(UUID.randomUUID().toString())));
        assertThat(missing.code()).isEqualTo(Code.NOT_FOUND);
        assertThat(missing.reason()).isEqualTo("NOT_FOUND");

        var bad = rpcError(() -> messages.getMessage(get("not-a-uuid")));
        assertThat(bad.code()).isEqualTo(Code.INVALID_ARGUMENT);
        assertThat(bad.violations()).containsExactly(java.util.Map.entry("id", "id must be a valid UUID"));

        // Only the canonical 8-4-4-4-12 form is accepted (UUID.fromString alone is lenient).
        assertThat(rpcError(() -> messages.getMessage(get("1-1-1-1-1"))).code()).isEqualTo(Code.INVALID_ARGUMENT);
    }

    @Test
    void updateBumpsVersionAndEvictsTheCache() {
        String id = createMessage(createAuthor().getId(), "Old").getId();
        UUID uuid = UUID.fromString(id);
        messages.getMessage(get(id));
        assertThat(cache.data).containsKey(uuid);

        Message updated = messages.updateMessage(update(id, "New body", 0).setTitle("New").build());
        assertThat(updated.getTitle()).isEqualTo("New");
        assertThat(updated.getContent()).isEqualTo("New body");
        assertThat(updated.getVersion()).isEqualTo(1);
        assertThat(cache.calls).contains(new Call("evict", uuid));
        assertThat(cache.data).doesNotContainKey(uuid);

        // An absent title leaves it unchanged.
        Message again = messages.updateMessage(update(id, "Again", 1).build());
        assertThat(again.getTitle()).isEqualTo("New");
        assertThat(again.getVersion()).isEqualTo(2);
    }

    @Test
    void staleVersionIsAbortedAndDoesNotOverwrite() {
        String id = createMessage(createAuthor().getId()).getId();
        messages.updateMessage(update(id, "first", 0).build());

        var stale = rpcError(() -> messages.updateMessage(update(id, "stale write", 0).build()));
        assertThat(stale.code()).isEqualTo(Code.ABORTED);
        assertThat(stale.reason()).isEqualTo("CONFLICT");

        Message current = messages.getMessage(get(id));
        assertThat(current.getContent()).isEqualTo("first");
        assertThat(current.getVersion()).isEqualTo(1);
    }

    @Test
    void staleVersionEvictsAPossiblyStaleCacheEntry() {
        String id = createMessage(createAuthor().getId()).getId();
        UUID uuid = UUID.fromString(id);
        messages.updateMessage(update(id, "v1", 0).build());
        // A slow reader repopulated the cache with old data.
        cache.data.put(uuid, Message.newBuilder().setId(id).setContent("stale").build());

        assertThat(rpcError(() -> messages.updateMessage(update(id, "x", 0).build())).code())
                .isEqualTo(Code.ABORTED);
        assertThat(cache.data).doesNotContainKey(uuid); // healed: the next read reloads from the DB
    }

    @Test
    void updateUnknownMessageIsNotFoundNotAborted() {
        var error = rpcError(() -> messages.updateMessage(update(UUID.randomUUID().toString(), "x", 0).build()));
        assertThat(error.code()).isEqualTo(Code.NOT_FOUND);
    }

    @Test
    void concurrentUpdatesNeverLoseAWrite() throws Exception {
        String id = createMessage(createAuthor().getId()).getId();
        int writers = 10;
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<Code>> tasks = new ArrayList<>();
        for (int i = 0; i < writers; i++) {
            String content = "w" + i;
            tasks.add(() -> {
                start.await();
                try {
                    messages.updateMessage(update(id, content, 0).build());
                    return Code.OK;
                } catch (StatusRuntimeException e) {
                    return e.getStatus().getCode();
                }
            });
        }
        List<Code> codes = new ArrayList<>();
        try (ExecutorService pool = Executors.newFixedThreadPool(writers)) {
            List<Future<Code>> futures = tasks.stream().map(pool::submit).toList();
            start.countDown();
            for (Future<Code> f : futures) {
                codes.add(f.get());
            }
        }
        // All read version 0: exactly one wins, every other write is rejected, none is lost.
        assertThat(Collections.frequency(codes, Code.OK)).isEqualTo(1);
        assertThat(Collections.frequency(codes, Code.ABORTED)).isEqualTo(writers - 1);
        assertThat(messages.getMessage(get(id)).getVersion()).isEqualTo(1);
    }

    @Test
    void updateValidation() {
        String id = createMessage(createAuthor().getId()).getId();
        var error = rpcError(() -> messages.updateMessage(UpdateMessageRequest.newBuilder()
                .setId(id).setTitle("  ").setContent("").setVersion(-1).build()));
        assertThat(error.code()).isEqualTo(Code.INVALID_ARGUMENT);
        assertThat(error.violations()).containsOnlyKeys("title", "content", "version");

        var noVersion = rpcError(() -> messages.updateMessage(UpdateMessageRequest.newBuilder()
                .setId(id).setContent("x").build()));
        assertThat(noVersion.violations()).containsExactly(java.util.Map.entry("version", "version is required"));
    }

    @Test
    void deleteEvictsTheCacheThenNotFound() {
        String id = createMessage(createAuthor().getId()).getId();
        UUID uuid = UUID.fromString(id);
        messages.getMessage(get(id));

        messages.deleteMessage(DeleteMessageRequest.newBuilder().setId(id).build());
        assertThat(cache.calls).contains(new Call("evict", uuid));
        assertThat(cache.data).doesNotContainKey(uuid);
        assertThat(rpcError(() -> messages.getMessage(get(id))).code()).isEqualTo(Code.NOT_FOUND);
        assertThat(rpcError(() -> messages.deleteMessage(DeleteMessageRequest.newBuilder().setId(id).build())).code())
                .isEqualTo(Code.NOT_FOUND);
    }

    @Test
    void listPaginatesNewestFirstAndRejectsBadBounds() {
        String authorId = createAuthor().getId();
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            ids.add(createMessage(authorId, "m" + i).getId());
        }
        List<String> newestFirst = new ArrayList<>(ids);
        Collections.reverse(newestFirst);

        ListMessagesResponse page = messages.listMessages(ListMessagesRequest.newBuilder().setLimit(2).setOffset(1).build());
        assertThat(page.getTotalCount()).isEqualTo(5);
        assertThat(page.getItemsList()).extracting(Message::getId).containsExactlyElementsOf(newestFirst.subList(1, 3));

        assertThat(messages.listMessages(ListMessagesRequest.getDefaultInstance()).getItemsCount()).isEqualTo(5);

        // Rejected, never silently clamped.
        for (var bad : List.of(
                ListMessagesRequest.newBuilder().setLimit(0),
                ListMessagesRequest.newBuilder().setLimit(201),
                ListMessagesRequest.newBuilder().setOffset(-1))) {
            var error = rpcError(() -> messages.listMessages(bad.build()));
            assertThat(error.code()).as(bad.toString()).isEqualTo(Code.INVALID_ARGUMENT);
            assertThat(error.reason()).isEqualTo("BAD_USER_INPUT");
        }
    }

    @Test
    void oversizedRequestIsResourceExhausted() {
        String authorId = createAuthor().getId();
        StatusRuntimeException e = org.junit.jupiter.api.Assertions.assertThrows(StatusRuntimeException.class,
                () -> messages.createMessage(CreateMessageRequest.newBuilder()
                        .setTitle("a").setContent("a".repeat(20_000)).setAuthorId(authorId).build()));
        assertThat(e.getStatus().getCode()).isEqualTo(Code.RESOURCE_EXHAUSTED);
    }
}
