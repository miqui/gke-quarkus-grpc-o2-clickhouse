package dev.miqui.messageservice.grpc;

import com.google.protobuf.Empty;
import dev.miqui.messageservice.service.Messages;
import dev.miqui.messageservice.v1.CreateMessageRequest;
import dev.miqui.messageservice.v1.DeleteMessageRequest;
import dev.miqui.messageservice.v1.GetMessageRequest;
import dev.miqui.messageservice.v1.ListMessagesRequest;
import dev.miqui.messageservice.v1.ListMessagesResponse;
import dev.miqui.messageservice.v1.Message;
import dev.miqui.messageservice.v1.MessageServiceGrpc;
import dev.miqui.messageservice.v1.UpdateMessageRequest;
import dev.miqui.messageservice.validation.Violations;
import io.grpc.stub.StreamObserver;
import org.springframework.grpc.server.service.GrpcService;

import java.util.UUID;

import static dev.miqui.messageservice.validation.Violations.CONTENT_MAX;
import static dev.miqui.messageservice.validation.Violations.TITLE_MAX;

/** Validation and proto plumbing only; exceptions are mapped by {@link Rpc#unary}. */
@GrpcService
public class MessageGrpcService extends MessageServiceGrpc.MessageServiceImplBase {

    private final Messages messages;

    public MessageGrpcService(Messages messages) {
        this.messages = messages;
    }

    @Override
    public void listMessages(ListMessagesRequest request, StreamObserver<ListMessagesResponse> response) {
        Rpc.unary(response, () -> {
            Violations v = new Violations();
            int limit = v.limit(request.hasLimit(), request.getLimit());
            int offset = v.offset(request.hasOffset(), request.getOffset());
            v.throwIfAny();
            return messages.list(limit, offset);
        });
    }

    @Override
    public void getMessage(GetMessageRequest request, StreamObserver<Message> response) {
        Rpc.unary(response, () -> {
            Violations v = new Violations();
            UUID id = v.uuid("id", request.getId());
            v.throwIfAny();
            return messages.get(id);
        });
    }

    @Override
    public void createMessage(CreateMessageRequest request, StreamObserver<Message> response) {
        Rpc.unary(response, () -> {
            Violations v = new Violations();
            String title = v.text("title", request.getTitle(), TITLE_MAX);
            String content = v.text("content", request.getContent(), CONTENT_MAX);
            UUID authorId = v.uuid("author_id", request.getAuthorId());
            v.throwIfAny();
            return messages.create(title, content, authorId);
        });
    }

    @Override
    public void updateMessage(UpdateMessageRequest request, StreamObserver<Message> response) {
        Rpc.unary(response, () -> {
            Violations v = new Violations();
            UUID id = v.uuid("id", request.getId());
            String title = request.hasTitle() ? v.text("title", request.getTitle(), TITLE_MAX) : null;
            String content = v.text("content", request.getContent(), CONTENT_MAX);
            if (!request.hasVersion()) {
                v.add("version", "version is required");
            } else if (request.getVersion() < 0) {
                v.add("version", "version must be 0 or greater");
            }
            v.throwIfAny();
            return messages.update(id, title, content, request.getVersion());
        });
    }

    @Override
    public void deleteMessage(DeleteMessageRequest request, StreamObserver<Empty> response) {
        Rpc.unary(response, () -> {
            Violations v = new Violations();
            UUID id = v.uuid("id", request.getId());
            v.throwIfAny();
            messages.delete(id);
            return Empty.getDefaultInstance();
        });
    }
}
