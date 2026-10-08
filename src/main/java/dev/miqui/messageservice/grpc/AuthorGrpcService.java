package dev.miqui.messageservice.grpc;

import com.google.protobuf.Empty;
import dev.miqui.messageservice.service.Authors;
import dev.miqui.messageservice.v1.Author;
import dev.miqui.messageservice.v1.AuthorServiceGrpc;
import dev.miqui.messageservice.v1.CreateAuthorRequest;
import dev.miqui.messageservice.v1.DeleteAuthorRequest;
import dev.miqui.messageservice.v1.GetAuthorRequest;
import dev.miqui.messageservice.v1.GetAuthorResponse;
import dev.miqui.messageservice.v1.ListAuthorsRequest;
import dev.miqui.messageservice.v1.ListAuthorsResponse;
import dev.miqui.messageservice.v1.UpdateAuthorRequest;
import dev.miqui.messageservice.validation.Violations;
import io.grpc.stub.StreamObserver;
import org.springframework.grpc.server.service.GrpcService;

import java.util.UUID;

import static dev.miqui.messageservice.validation.Violations.NAME_MAX;

/** Validation and proto plumbing only; exceptions are mapped by {@link Rpc#unary}. */
@GrpcService
public class AuthorGrpcService extends AuthorServiceGrpc.AuthorServiceImplBase {

    private final Authors authors;

    public AuthorGrpcService(Authors authors) {
        this.authors = authors;
    }

    @Override
    public void listAuthors(ListAuthorsRequest request, StreamObserver<ListAuthorsResponse> response) {
        Rpc.unary(response, () -> {
            Violations v = new Violations();
            int limit = v.limit(request.hasLimit(), request.getLimit());
            int offset = v.offset(request.hasOffset(), request.getOffset());
            v.throwIfAny();
            return authors.list(limit, offset);
        });
    }

    @Override
    public void getAuthor(GetAuthorRequest request, StreamObserver<GetAuthorResponse> response) {
        Rpc.unary(response, () -> {
            Violations v = new Violations();
            UUID id = v.uuid("id", request.getId());
            int limit = v.limit("messages_limit", request.hasMessagesLimit(), request.getMessagesLimit());
            long offset = v.offset("messages_offset", request.hasMessagesOffset(), request.getMessagesOffset());
            v.throwIfAny();
            return authors.get(id, request.getIncludeMessages(), limit, offset);
        });
    }

    @Override
    public void createAuthor(CreateAuthorRequest request, StreamObserver<Author> response) {
        Rpc.unary(response, () -> {
            Violations v = new Violations();
            String name = v.text("name", request.getName(), NAME_MAX);
            String email = v.email("email", request.getEmail());
            v.throwIfAny();
            return authors.create(name, email);
        });
    }

    @Override
    public void updateAuthor(UpdateAuthorRequest request, StreamObserver<Author> response) {
        Rpc.unary(response, () -> {
            Violations v = new Violations();
            UUID id = v.uuid("id", request.getId());
            String name = request.hasName() ? v.text("name", request.getName(), NAME_MAX) : null;
            String email = request.hasEmail() ? v.email("email", request.getEmail()) : null;
            v.throwIfAny();
            return authors.update(id, name, email);
        });
    }

    @Override
    public void deleteAuthor(DeleteAuthorRequest request, StreamObserver<Empty> response) {
        Rpc.unary(response, () -> {
            Violations v = new Violations();
            UUID id = v.uuid("id", request.getId());
            v.throwIfAny();
            authors.delete(id);
            return Empty.getDefaultInstance();
        });
    }
}
