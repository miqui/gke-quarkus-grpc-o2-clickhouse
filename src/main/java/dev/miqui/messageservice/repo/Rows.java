package dev.miqui.messageservice.repo;

import com.google.protobuf.Timestamp;
import dev.miqui.messageservice.v1.Author;
import dev.miqui.messageservice.v1.Message;
import dev.miqui.messageservice.v1.MessageSummary;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.core.NestedExceptionUtils;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;

/** Row -> protobuf mapping shared by the repositories, and SQLSTATE inspection. */
public final class Rows {

    public static final String UNIQUE_VIOLATION = "23505";
    public static final String FK_VIOLATION = "23503";

    private Rows() {
    }

    public static Timestamp timestamp(ResultSet rs, String column) throws SQLException {
        OffsetDateTime t = rs.getObject(column, OffsetDateTime.class);
        return Timestamp.newBuilder().setSeconds(t.toEpochSecond()).setNanos(t.getNano()).build();
    }

    /** Columns id, name, email, created_at, each with the given prefix. */
    public static Author author(ResultSet rs, String prefix) throws SQLException {
        return Author.newBuilder()
                .setId(rs.getString(prefix + "id"))
                .setName(rs.getString(prefix + "name"))
                .setEmail(rs.getString(prefix + "email"))
                .setCreatedAt(timestamp(rs, prefix + "created_at"))
                .build();
    }

    public static MessageSummary summary(ResultSet rs) throws SQLException {
        return MessageSummary.newBuilder()
                .setId(rs.getString("id"))
                .setTitle(rs.getString("title"))
                .setContent(rs.getString("content"))
                .setCreatedAt(timestamp(rs, "created_at"))
                .setVersion(rs.getInt("version"))
                .build();
    }

    /** Message columns unprefixed, author columns prefixed with {@code a_}. */
    public static Message message(ResultSet rs) throws SQLException {
        return Message.newBuilder()
                .setId(rs.getString("id"))
                .setTitle(rs.getString("title"))
                .setContent(rs.getString("content"))
                .setCreatedAt(timestamp(rs, "created_at"))
                .setVersion(rs.getInt("version"))
                .setAuthor(author(rs, "a_"))
                .build();
    }

    public static boolean hasSqlState(DataIntegrityViolationException e, String sqlState) {
        return NestedExceptionUtils.getMostSpecificCause(e) instanceof SQLException sql
                && sqlState.equals(sql.getSQLState());
    }
}
