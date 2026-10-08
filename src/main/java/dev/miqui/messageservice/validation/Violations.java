package dev.miqui.messageservice.validation;

import com.google.rpc.BadRequest.FieldViolation;
import dev.miqui.messageservice.error.ApiException;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Collects every field violation of one request, then throws them together
 * ({@link #throwIfAny()}). Required strings are trimmed, must be non-blank and within their max
 * length; optional strings (partial updates) are only checked when present.
 */
public final class Violations {

    public static final int TITLE_MAX = 100;
    public static final int CONTENT_MAX = 1000;
    public static final int NAME_MAX = 50;
    public static final int EMAIL_MAX = 100;
    public static final int LIMIT_DEFAULT = 50;
    public static final int LIMIT_MAX = 200;

    private static final Pattern EMAIL = Pattern.compile("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$");
    // Canonical 8-4-4-4-12 form only: UUID.fromString alone also accepts "1-2-3-4-5".
    private static final Pattern UUID_FORMAT =
            Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private final List<FieldViolation> violations = new ArrayList<>();

    public void add(String field, String reason) {
        violations.add(FieldViolation.newBuilder().setField(field).setDescription(reason).build());
    }

    public String text(String field, String value, int maxLength) {
        String trimmed = value == null ? "" : value.strip();
        if (trimmed.indexOf('\0') >= 0) {
            add(field, field + " cannot contain NUL characters");
        } else if (trimmed.isEmpty()) {
            add(field, field + " is required and cannot be blank");
        } else if (trimmed.length() > maxLength) {
            add(field, field + " cannot exceed " + maxLength + " characters");
        }
        return trimmed;
    }

    public String email(String field, String value) {
        int before = violations.size();
        String trimmed = text(field, value, EMAIL_MAX);
        if (violations.size() == before && !EMAIL.matcher(trimmed).matches()) {
            add(field, field + " must be a valid email address");
        }
        return trimmed;
    }

    public UUID uuid(String field, String value) {
        if (value == null || !UUID_FORMAT.matcher(value).matches()) {
            add(field, field + " must be a valid UUID");
            return null;
        }
        return UUID.fromString(value);
    }

    public int limit(boolean present, int value) {
        return limit("limit", present, value);
    }

    public int limit(String field, boolean present, int value) {
        if (!present) {
            return LIMIT_DEFAULT;
        }
        if (value < 1 || value > LIMIT_MAX) {
            add(field, field + " must be between 1 and " + LIMIT_MAX);
        }
        return value;
    }

    public int offset(boolean present, int value) {
        return Math.toIntExact(offset("offset", present, value));
    }

    public long offset(String field, boolean present, long value) {
        if (present && value < 0) {
            add(field, field + " must be 0 or greater");
        }
        return present ? value : 0;
    }

    public void throwIfAny() {
        if (!violations.isEmpty()) {
            throw new ApiException.BadInput(violations);
        }
    }
}
