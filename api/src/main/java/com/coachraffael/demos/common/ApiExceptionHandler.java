package com.coachraffael.demos.common;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.sql.SQLException;
import java.util.Map;
import java.util.stream.Collectors;

/** Einheitliche JSON-Fehler: {@code {"error": "...", "message": "..."}}. */
@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(Errors.NotFound.class)
    ResponseEntity<Map<String, String>> notFound(Errors.NotFound e) {
        return body(HttpStatus.NOT_FOUND, "not_found", e.getMessage());
    }

    @ExceptionHandler(Errors.Conflict.class)
    ResponseEntity<Map<String, String>> conflict(Errors.Conflict e) {
        return body(HttpStatus.CONFLICT, "conflict", e.getMessage());
    }

    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    ResponseEntity<Map<String, String>> tooLarge(org.springframework.web.multipart.MaxUploadSizeExceededException e) {
        return body(HttpStatus.PAYLOAD_TOO_LARGE, "too_large", "Anfrage ist zu groß");
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<Map<String, String>> badRequest(IllegalArgumentException e) {
        return body(HttpStatus.BAD_REQUEST, "bad_request", e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<Map<String, String>> invalid(MethodArgumentNotValidException e) {
        String msg = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + ": " + f.getDefaultMessage())
                .collect(Collectors.joining("; "));
        return body(HttpStatus.BAD_REQUEST, "invalid", msg);
    }

    /** Verstöße gegen DB-Regeln (Trigger der Unveränderlichkeit, Check-Constraints, Fremdschlüssel). */
    @ExceptionHandler(DataIntegrityViolationException.class)
    ResponseEntity<Map<String, String>> integrity(DataIntegrityViolationException e) {
        return body(HttpStatus.CONFLICT, "integrity", databaseMessage(e));
    }

    /** Die eigentliche Postgres-Meldung (z. B. aus RAISE EXCEPTION) ohne JDBC-Präfixe. */
    static String databaseMessage(Throwable e) {
        Throwable t = e;
        while (t != null) {
            if (t instanceof SQLException sql && sql.getMessage() != null) {
                String m = sql.getMessage();
                int nl = m.indexOf('\n');
                m = nl > 0 ? m.substring(0, nl) : m;
                return m.replaceFirst("^ERROR: ", "");
            }
            t = t.getCause();
        }
        return "Datenbankregel verletzt";
    }

    private static ResponseEntity<Map<String, String>> body(HttpStatus status, String error, String message) {
        return ResponseEntity.status(status).body(Map.of("error", error, "message", message == null ? "" : message));
    }
}
