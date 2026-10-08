package dev.miqui.messageservice.service;

import dev.miqui.messageservice.repo.AuthorRepository;
import dev.miqui.messageservice.v1.Author;
import dev.miqui.messageservice.v1.GetAuthorRequest;
import dev.miqui.messageservice.v1.MessageSummary;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.support.TransactionOperations;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class AuthorsPaginationTest {

    private final UUID id = UUID.randomUUID();
    private final Author author = Author.newBuilder().setId(id.toString()).build();
    private final AuthorRepository repository = mock(AuthorRepository.class);
    private final Authors authors = new Authors(repository, TransactionOperations.withoutTransaction());

    @Test
    void continuationOffsetsCanCrossTheInt32Boundary() {
        long offset = Integer.MAX_VALUE - 1L;
        var first = MessageSummary.newBuilder().setId("first").build();
        var second = MessageSummary.newBuilder().setId("second").build();
        var last = MessageSummary.newBuilder().setId("last").build();
        when(repository.find(id)).thenReturn(Optional.of(author));
        when(repository.messagesOf(id, 3, offset)).thenReturn(List.of(first, second, last));
        when(repository.messagesOf(id, 3, offset + 2)).thenReturn(List.of(last));

        var page = authors.get(id, true, 2, offset);
        assertThat(page.getMessagesList()).containsExactly(first, second);
        assertThat(page.hasNextMessagesOffset()).isTrue();
        assertThat(page.getNextMessagesOffset()).isEqualTo(Integer.MAX_VALUE + 1L);
        var nextRequest = GetAuthorRequest.newBuilder().setId(id.toString()).setIncludeMessages(true)
                .setMessagesLimit(2).setMessagesOffset(page.getNextMessagesOffset()).build();
        var finalPage = authors.get(id, nextRequest.getIncludeMessages(),
                nextRequest.getMessagesLimit(), nextRequest.getMessagesOffset());
        assertThat(finalPage.getMessagesList()).containsExactly(last);
        assertThat(finalPage.hasNextMessagesOffset()).isFalse();
        verify(repository).messagesOf(id, 3, offset);
        verify(repository).messagesOf(id, 3, offset + 2);
    }

    @Test
    void plainAuthorReadsNeverQueryTheMessageCollection() {
        when(repository.find(id)).thenReturn(Optional.of(author));

        var response = authors.get(id, false, 200, 0);

        assertThat(response.getAuthor()).isEqualTo(author);
        assertThat(response.getMessagesList()).isEmpty();
        assertThat(response.hasNextMessagesOffset()).isFalse();
        verify(repository).find(id);
        verifyNoMoreInteractions(repository);
    }
}
