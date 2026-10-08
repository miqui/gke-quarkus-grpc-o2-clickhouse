package dev.miqui.messageservice.service;

import dev.miqui.messageservice.error.ApiException;
import dev.miqui.messageservice.repo.AuthorRepository;
import dev.miqui.messageservice.repo.Rows;
import dev.miqui.messageservice.v1.Author;
import dev.miqui.messageservice.v1.GetAuthorResponse;
import dev.miqui.messageservice.v1.ListAuthorsResponse;
import io.grpc.Status;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

import java.util.UUID;

@Service
public class Authors {

    private final AuthorRepository authors;
    private final TransactionOperations tx;

    public Authors(AuthorRepository authors, TransactionOperations tx) {
        this.authors = authors;
        this.tx = tx;
    }

    public ListAuthorsResponse list(int limit, int offset) {
        return tx.execute(s -> ListAuthorsResponse.newBuilder()
                .addAllItems(authors.page(limit, offset))
                .setTotalCount(authors.count())
                .build());
    }

    public GetAuthorResponse get(UUID id, boolean includeMessages, int limit, long offset) {
        return tx.execute(s -> {
            Author author = authors.find(id).orElseThrow(() -> notFound(id));
            GetAuthorResponse.Builder response = GetAuthorResponse.newBuilder().setAuthor(author);
            if (includeMessages) {
                var messages = authors.messagesOf(id, limit + 1, offset);
                response.addAllMessages(messages.subList(0, Math.min(limit, messages.size())));
                if (messages.size() > limit) {
                    response.setNextMessagesOffset(Math.addExact(offset, limit));
                }
            }
            return response.build();
        });
    }

    public Author create(String name, String email) {
        try {
            return authors.insert(name, email);
        } catch (DuplicateKeyException e) {
            throw duplicateEmail(email);
        }
    }

    /** Null name/email mean unchanged; with nothing to change it still 404s for an unknown id. */
    public Author update(UUID id, String name, String email) {
        try {
            if (name == null && email == null) {
                return authors.find(id).orElseThrow(() -> notFound(id));
            }
            return authors.update(id, name, email).orElseThrow(() -> notFound(id));
        } catch (DuplicateKeyException e) {
            throw duplicateEmail(email);
        }
    }

    public void delete(UUID id) {
        boolean deleted;
        try {
            deleted = authors.delete(id);
        } catch (DataIntegrityViolationException e) {
            if (Rows.hasSqlState(e, Rows.FK_VIOLATION)) {
                throw new ApiException.Conflict(Status.Code.FAILED_PRECONDITION,
                        "Author with ID '" + id + "' still has messages and cannot be deleted.");
            }
            throw e;
        }
        if (!deleted) {
            throw notFound(id);
        }
    }

    private static ApiException notFound(UUID id) {
        return new ApiException.NotFound("Author with ID '" + id + "' was not found.");
    }

    private static ApiException duplicateEmail(String email) {
        return new ApiException.Conflict(Status.Code.ALREADY_EXISTS,
                "Author with email '" + email + "' already exists.");
    }
}
