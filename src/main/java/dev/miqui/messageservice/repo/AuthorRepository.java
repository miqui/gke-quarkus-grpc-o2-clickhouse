package dev.miqui.messageservice.repo;

import dev.miqui.messageservice.v1.Author;
import dev.miqui.messageservice.v1.MessageSummary;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

import java.sql.Types;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public class AuthorRepository {

    private final JdbcClient jdbc;

    public AuthorRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<Author> page(int limit, int offset) {
        return jdbc.sql("SELECT id, name, email, created_at FROM authors ORDER BY created_at ASC, id ASC LIMIT :limit OFFSET :offset")
                .param("limit", limit)
                .param("offset", offset)
                .query((rs, n) -> Rows.author(rs, ""))
                .list();
    }

    public long count() {
        return jdbc.sql("SELECT count(*) FROM authors").query(Long.class).single();
    }

    public Optional<Author> find(UUID id) {
        return jdbc.sql("SELECT id, name, email, created_at FROM authors WHERE id = :id")
                .param("id", id)
                .query((rs, n) -> Rows.author(rs, ""))
                .optional();
    }

    public boolean exists(UUID id) {
        return jdbc.sql("SELECT EXISTS (SELECT 1 FROM authors WHERE id = :id)")
                .param("id", id)
                .query(Boolean.class)
                .single();
    }

    /** Newest first, without the author (no author -> messages -> author cycle). */
    public List<MessageSummary> messagesOf(UUID authorId, int limit, long offset) {
        return jdbc.sql("""
                        SELECT id, title, content, created_at, version FROM messages
                        WHERE author_id = :id ORDER BY created_at DESC, id DESC
                        LIMIT :limit OFFSET :offset
                        """)
                .param("id", authorId)
                .param("limit", limit)
                .param("offset", offset)
                .query((rs, n) -> Rows.summary(rs))
                .list();
    }

    /** Throws DuplicateKeyException for an email that is taken. */
    public Author insert(String name, String email) {
        return jdbc.sql("INSERT INTO authors (name, email) VALUES (:name, :email) RETURNING id, name, email, created_at")
                .param("name", name)
                .param("email", email)
                .query((rs, n) -> Rows.author(rs, ""))
                .single();
    }

    /** Null arguments leave the column unchanged. Empty when the id doesn't exist. */
    public Optional<Author> update(UUID id, String name, String email) {
        return jdbc.sql("""
                        UPDATE authors SET name = COALESCE(:name, name), email = COALESCE(:email, email)
                        WHERE id = :id
                        RETURNING id, name, email, created_at
                        """)
                .param("id", id)
                .param("name", name, Types.VARCHAR)
                .param("email", email, Types.VARCHAR)
                .query((rs, n) -> Rows.author(rs, ""))
                .optional();
    }

    /** Throws DataIntegrityViolationException (FK) while the author still has messages. */
    public boolean delete(UUID id) {
        return jdbc.sql("DELETE FROM authors WHERE id = :id").param("id", id).update() > 0;
    }
}
