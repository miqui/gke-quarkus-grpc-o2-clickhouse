package dev.miqui.messageservice.repo;

import dev.miqui.messageservice.v1.Message;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Types;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class MessageRepository {

    private static final String SELECT_WITH_AUTHOR = """
            SELECT m.id, m.title, m.content, m.created_at, m.version,
                   a.id AS a_id, a.name AS a_name, a.email AS a_email, a.created_at AS a_created_at
            FROM messages m JOIN authors a ON a.id = m.author_id
            """;

    private final JdbcClient jdbc;

    public MessageRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<Message> page(int limit, int offset) {
        return jdbc.sql(SELECT_WITH_AUTHOR + " ORDER BY m.created_at DESC, m.id DESC LIMIT :limit OFFSET :offset")
                .param("limit", limit)
                .param("offset", offset)
                .query((rs, n) -> Rows.message(rs))
                .list();
    }

    public long count() {
        return jdbc.sql("SELECT count(*) FROM messages").query(Long.class).single();
    }

    public Optional<Message> find(UUID id) {
        return jdbc.sql(SELECT_WITH_AUTHOR + " WHERE m.id = :id")
                .param("id", id)
                .query((rs, n) -> Rows.message(rs))
                .optional();
    }

    public boolean exists(UUID id) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM messages WHERE id = :id)")
                .param("id", id)
                .query(Boolean.class)
                .single();
    }

    /** Returns the new message's id. Throws DataIntegrityViolationException (FK) for a gone author. */
    public UUID insert(String title, String content, UUID authorId) {
        return jdbc.sql("INSERT INTO messages (title, content, author_id) VALUES (:title, :content, :authorId) RETURNING id")
                .param("title", title)
                .param("content", content)
                .param("authorId", authorId)
                .query(UUID.class)
                .single();
    }

    /**
     * Optimistic locking in a single statement: the row only changes if it is still at the version
     * the caller read. Comparing against a server-side re-read instead would compare the row to
     * itself and never catch a client acting on stale data. Empty when no row matched.
     */
    public Optional<UUID> updateIfVersion(UUID id, String title, String content, int version) {
        return jdbc.sql("""
                        UPDATE messages
                        SET title = COALESCE(:title, title), content = :content, version = version + 1
                        WHERE id = :id AND version = :version
                        RETURNING id
                        """)
                .param("id", id)
                .param("title", title, Types.VARCHAR)
                .param("content", content)
                .param("version", version)
                .query(UUID.class)
                .optional();
    }

    public boolean delete(UUID id) {
        return jdbc.sql("DELETE FROM messages WHERE id = :id").param("id", id).update() > 0;
    }
}
