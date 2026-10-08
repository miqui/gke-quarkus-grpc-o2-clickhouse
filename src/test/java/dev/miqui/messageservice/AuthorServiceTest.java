package dev.miqui.messageservice;

import dev.miqui.messageservice.v1.Author;
import dev.miqui.messageservice.v1.DeleteAuthorRequest;
import dev.miqui.messageservice.v1.DeleteMessageRequest;
import dev.miqui.messageservice.v1.GetAuthorRequest;
import dev.miqui.messageservice.v1.GetAuthorResponse;
import dev.miqui.messageservice.v1.GetMessageRequest;
import dev.miqui.messageservice.v1.ListAuthorsRequest;
import dev.miqui.messageservice.v1.MessageSummary;
import dev.miqui.messageservice.v1.UpdateAuthorRequest;
import io.grpc.Status.Code;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

class AuthorServiceTest extends GrpcTestSupport {

    private DeleteAuthorRequest delete(String id) {
        return DeleteAuthorRequest.newBuilder().setId(id).build();
    }

    @Test
    void createTrims() {
        Author author = createAuthor(" Bob ", " bob@example.com ");
        assertThat(UUID.fromString(author.getId())).isNotNull();
        assertThat(author.getName()).isEqualTo("Bob");
        assertThat(author.getEmail()).isEqualTo("bob@example.com");
        assertThat(author.hasCreatedAt()).isTrue();
    }

    @Test
    void validationCollectsAllErrors() {
        var error = rpcError(() -> createAuthor("x".repeat(51), "not-an-email"));
        assertThat(error.code()).isEqualTo(Code.INVALID_ARGUMENT);
        assertThat(error.reason()).isEqualTo("BAD_USER_INPUT");
        assertThat(error.violations()).containsExactly(
                Map.entry("name", "name cannot exceed 50 characters"),
                Map.entry("email", "email must be a valid email address"));
    }

    @Test
    void createRejectsNulInNameAndEmailBeforeInserting() {
        var error = rpcError(() -> createAuthor("Bad\0name", "bad\0@example.com"));

        assertThat(error.code()).isEqualTo(Code.INVALID_ARGUMENT);
        assertThat(error.reason()).isEqualTo("BAD_USER_INPUT");
        assertThat(error.violations()).containsExactly(
                Map.entry("name", "name cannot contain NUL characters"),
                Map.entry("email", "email cannot contain NUL characters"));
        assertThat(jdbc.sql("SELECT count(*) FROM authors").query(Long.class).single()).isZero();
    }

    @Test
    void updateRejectsNulInNameAndEmailWithoutChangingTheAuthor() {
        Author author = createAuthor();
        var error = rpcError(() -> authors.updateAuthor(UpdateAuthorRequest.newBuilder()
                .setId(author.getId()).setName("Bad\0name").setEmail("bad\0@example.com").build()));

        assertThat(error.code()).isEqualTo(Code.INVALID_ARGUMENT);
        assertThat(error.reason()).isEqualTo("BAD_USER_INPUT");
        assertThat(error.violations()).containsExactly(
                Map.entry("name", "name cannot contain NUL characters"),
                Map.entry("email", "email cannot contain NUL characters"));
        assertThat(authors.getAuthor(GetAuthorRequest.newBuilder().setId(author.getId()).build()).getAuthor())
                .isEqualTo(author);
    }

    @Test
    void duplicateEmailIsAlreadyExists() {
        Author author = createAuthor();
        var dup = rpcError(() -> createAuthor("Other", author.getEmail()));
        assertThat(dup.code()).isEqualTo(Code.ALREADY_EXISTS);
        assertThat(dup.reason()).isEqualTo("CONFLICT");

        Author other = createAuthor("O", "o@example.com");
        var clash = rpcError(() -> authors.updateAuthor(UpdateAuthorRequest.newBuilder()
                .setId(other.getId()).setEmail(author.getEmail()).build()));
        assertThat(clash.code()).isEqualTo(Code.ALREADY_EXISTS);
    }

    @Test
    void deletingAnAuthorWithMessagesFailsUntilTheyAreGone() {
        Author author = createAuthor();
        String messageId = createMessage(author.getId()).getId();

        var blocked = rpcError(() -> authors.deleteAuthor(delete(author.getId()))); // FK RESTRICT
        assertThat(blocked.code()).isEqualTo(Code.FAILED_PRECONDITION);
        assertThat(blocked.reason()).isEqualTo("CONFLICT");

        messages.deleteMessage(DeleteMessageRequest.newBuilder().setId(messageId).build());
        authors.deleteAuthor(delete(author.getId()));
        assertThat(rpcError(() -> authors.deleteAuthor(delete(author.getId()))).code()).isEqualTo(Code.NOT_FOUND);
    }

    @Test
    void getAuthorAndIncludeMessages() {
        Author author = createAuthor();
        createMessage(author.getId(), "T");

        GetAuthorResponse plain = authors.getAuthor(GetAuthorRequest.newBuilder().setId(author.getId()).build());
        assertThat(plain.getAuthor()).isEqualTo(author);
        assertThat(plain.getMessagesList()).isEmpty();
        assertThat(plain.hasNextMessagesOffset()).isFalse();

        GetAuthorResponse detailed = authors.getAuthor(GetAuthorRequest.newBuilder()
                .setId(author.getId()).setIncludeMessages(true).build());
        // MessageSummary has no author field: no author -> messages -> author cycle.
        assertThat(detailed.getMessagesList()).extracting(MessageSummary::getTitle).containsExactly("T");
        assertThat(detailed.hasNextMessagesOffset()).isFalse();

        assertThat(rpcError(() -> authors.getAuthor(GetAuthorRequest.newBuilder()
                .setId(UUID.randomUUID().toString()).build())).code()).isEqualTo(Code.NOT_FOUND);
    }

    @Test
    void includedMessagesDefaultToFiftyAndContinueWithoutSkippingOrRepeating() {
        Author author = createAuthor();
        seedMessages(author.getId(), 205);
        seedMessages(createAuthor().getId(), 3);
        List<String> titles = new ArrayList<>();
        long offset = 0;

        while (true) {
            var page = authors.getAuthor(GetAuthorRequest.newBuilder()
                    .setId(author.getId()).setIncludeMessages(true).setMessagesOffset(offset).build());
            assertThat(page.getAuthor()).isEqualTo(author);
            assertThat(page.getMessagesCount()).isEqualTo(Math.min(50, 205 - offset));
            titles.addAll(page.getMessagesList().stream().map(MessageSummary::getTitle).toList());
            if (!page.hasNextMessagesOffset()) {
                break;
            }
            assertThat(page.getNextMessagesOffset()).isEqualTo(offset + 50);
            offset = page.getNextMessagesOffset();
        }

        assertThat(titles).containsExactlyElementsOf(
                IntStream.rangeClosed(1, 205).mapToObj(i -> "m" + (206 - i)).toList());
    }

    @Test
    void includedMessagesAreCappedAtTwoHundredWithAnExplicitFinalPage() {
        Author author = createAuthor();
        seedMessages(author.getId(), 205);
        var request = GetAuthorRequest.newBuilder()
                .setId(author.getId()).setIncludeMessages(true).setMessagesLimit(200);

        var first = authors.getAuthor(request.build());
        assertThat(first.getMessagesCount()).isEqualTo(200);
        assertThat(first.getNextMessagesOffset()).isEqualTo(200);
        assertThat(first.hasNextMessagesOffset()).isTrue();
        assertThat(first.getSerializedSize()).isLessThan(250_000);

        var last = authors.getAuthor(request.setMessagesOffset(first.getNextMessagesOffset()).build());
        assertThat(last.getMessagesList()).extracting(MessageSummary::getTitle)
                .containsExactly("m5", "m4", "m3", "m2", "m1");
        assertThat(last.hasNextMessagesOffset()).isFalse();
    }

    @Test
    void emptyExactAndOutOfRangePagesHaveNoContinuation() {
        Author author = createAuthor();
        var request = GetAuthorRequest.newBuilder()
                .setId(author.getId()).setIncludeMessages(true).setMessagesLimit(3);
        var empty = authors.getAuthor(request.build());
        assertThat(empty.getMessagesList()).isEmpty();
        assertThat(empty.hasNextMessagesOffset()).isFalse();

        seedMessages(author.getId(), 3);
        var exact = authors.getAuthor(request.build());
        assertThat(exact.getMessagesCount()).isEqualTo(3);
        assertThat(exact.hasNextMessagesOffset()).isFalse();

        var pastEnd = authors.getAuthor(request.setMessagesOffset(3).build());
        assertThat(pastEnd.getMessagesList()).isEmpty();
        assertThat(pastEnd.hasNextMessagesOffset()).isFalse();

        var plain = authors.getAuthor(request.setIncludeMessages(false).setMessagesOffset(0).build());
        assertThat(plain.getMessagesList()).isEmpty();
        assertThat(plain.hasNextMessagesOffset()).isFalse();
    }

    @Test
    void includedMessagePagesUseIdToBreakTimestampTies() {
        Author author = createAuthor();
        seedMessages(author.getId(), 5);
        jdbc.sql("UPDATE messages SET created_at = '2024-01-01T00:00:00Z'").update();
        List<String> expected = jdbc.sql("SELECT id FROM messages").query(UUID.class).list().stream()
                .map(UUID::toString).sorted(Comparator.reverseOrder()).toList();
        var request = GetAuthorRequest.newBuilder()
                .setId(author.getId()).setIncludeMessages(true).setMessagesLimit(2);
        List<String> ids = new ArrayList<>();

        for (int offset = 0; offset < 5; offset += 2) {
            ids.addAll(authors.getAuthor(request.setMessagesOffset(offset).build()).getMessagesList()
                    .stream().map(MessageSummary::getId).toList());
        }

        assertThat(ids).containsExactlyElementsOf(expected);
    }

    @Test
    void messagePaginationReportsItsOwnFieldNamesAndAcceptsLargeOffsets() {
        Author author = createAuthor();
        for (int limit : List.of(-1, 0, 201)) {
            var error = rpcError(() -> authors.getAuthor(GetAuthorRequest.newBuilder()
                    .setId(author.getId()).setIncludeMessages(true).setMessagesLimit(limit).build()));
            assertThat(error.code()).isEqualTo(Code.INVALID_ARGUMENT);
            assertThat(error.reason()).isEqualTo("BAD_USER_INPUT");
            assertThat(error.violations()).containsExactly(
                    Map.entry("messages_limit", "messages_limit must be between 1 and 200"));
        }

        var combined = rpcError(() -> authors.getAuthor(GetAuthorRequest.newBuilder()
                .setId("bad").setMessagesLimit(0).setMessagesOffset(-1).build()));
        assertThat(combined.violations()).containsExactly(
                Map.entry("id", "id must be a valid UUID"),
                Map.entry("messages_limit", "messages_limit must be between 1 and 200"),
                Map.entry("messages_offset", "messages_offset must be 0 or greater"));

        for (long offset : List.of((long) Integer.MAX_VALUE + 1, Long.MAX_VALUE)) {
            var pastEnd = authors.getAuthor(GetAuthorRequest.newBuilder()
                    .setId(author.getId()).setIncludeMessages(true)
                    .setMessagesLimit(200).setMessagesOffset(offset).build());
            assertThat(pastEnd.getMessagesList()).isEmpty();
            assertThat(pastEnd.hasNextMessagesOffset()).isFalse();
        }
    }

    private void seedMessages(String authorId, int count) {
        jdbc.sql("""
                        INSERT INTO messages (title, content, author_id, created_at)
                        SELECT 'm' || n, repeat('x', 1000), :authorId,
                               TIMESTAMPTZ '2024-01-01T00:00:00Z' + n * INTERVAL '1 second'
                        FROM generate_series(1, :count) AS n
                        """)
                .param("authorId", UUID.fromString(authorId))
                .param("count", count)
                .update();
    }

    @Test
    void updateAuthor() {
        Author author = createAuthor();
        Author renamed = authors.updateAuthor(UpdateAuthorRequest.newBuilder()
                .setId(author.getId()).setName("Renamed").build());
        assertThat(renamed.getName()).isEqualTo("Renamed");
        assertThat(renamed.getEmail()).isEqualTo(author.getEmail());

        Author noop = authors.updateAuthor(UpdateAuthorRequest.newBuilder().setId(author.getId()).build());
        assertThat(noop).isEqualTo(renamed);

        assertThat(rpcError(() -> authors.updateAuthor(UpdateAuthorRequest.newBuilder()
                .setId(UUID.randomUUID().toString()).setName("x").build())).code()).isEqualTo(Code.NOT_FOUND);
        assertThat(rpcError(() -> authors.updateAuthor(UpdateAuthorRequest.newBuilder()
                .setId(UUID.randomUUID().toString()).build())).code()).isEqualTo(Code.NOT_FOUND);
        assertThat(rpcError(() -> authors.updateAuthor(UpdateAuthorRequest.newBuilder()
                .setId(author.getId()).setName("  ").build())).code()).isEqualTo(Code.INVALID_ARGUMENT);
    }

    @Test
    void updatingAnAuthorRefreshesEveryCachedMessageWithoutChangingTheirVersions() {
        Author author = createAuthor();
        var first = createMessage(author.getId(), "First");
        var second = createMessage(author.getId(), "Second");
        var firstRequest = GetMessageRequest.newBuilder().setId(first.getId()).build();
        var secondRequest = GetMessageRequest.newBuilder().setId(second.getId()).build();
        messages.getMessage(firstRequest);
        messages.getMessage(secondRequest);

        Author updated = authors.updateAuthor(UpdateAuthorRequest.newBuilder()
                .setId(author.getId()).setName("Renamed").setEmail("renamed@example.com").build());
        cache.calls.clear();

        assertThat(messages.getMessage(firstRequest)).isEqualTo(first.toBuilder().setAuthor(updated).build());
        assertThat(messages.getMessage(secondRequest)).isEqualTo(second.toBuilder().setAuthor(updated).build());
        assertThat(cache.calls).containsExactly(
                new RecordingMessageCache.Call("get", UUID.fromString(first.getId())),
                new RecordingMessageCache.Call("get", UUID.fromString(second.getId())));
    }

    @Test
    void listAuthors() {
        Author author = createAuthor();
        var page = authors.listAuthors(ListAuthorsRequest.getDefaultInstance());
        assertThat(page.getTotalCount()).isEqualTo(1);
        assertThat(page.getItems(0).getId()).isEqualTo(author.getId());
        assertThat(rpcError(() -> authors.listAuthors(ListAuthorsRequest.newBuilder().setLimit(0).build())).code())
                .isEqualTo(Code.INVALID_ARGUMENT);
    }
}
