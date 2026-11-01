package gov.mot.netmon.common;

import java.time.Instant;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Structured error body returned by every failing API call. */
@JsonInclude(JsonInclude.Include.NON_EMPTY)
public record ApiError(
        Instant timestamp,
        int status,
        String error,
        String code,
        String message,
        String path,
        String requestId,
        List<FieldViolation> fieldErrors) {

    public record FieldViolation(String field, String message) {
    }
}
