package com.example.springbootapp.users;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import com.example.springbootapp.users.UserController.ValidationException;

@RestControllerAdvice(annotations = RestController.class)
public class ApiExceptionHandler {

    private static final Logger logger = LoggerFactory.getLogger("lowops.api");

    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String, String>> handleStatus(ResponseStatusException ex) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("detail", ex.getReason() != null ? ex.getReason() : "Error");
        return ResponseEntity.status(ex.getStatusCode()).body(body);
    }

    @ExceptionHandler(ValidationException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(ValidationException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(ex.getErrors());
    }

    @ExceptionHandler({DuplicateKeyException.class, DataIntegrityViolationException.class})
    public ResponseEntity<Map<String, Object>> handleDuplicate(RuntimeException ex) {
        String message = fullMessage(ex);
        logger.warn("Data integrity violation while saving user: {}", message);
        Map<String, Object> body = new LinkedHashMap<>();
        String lower = message.toLowerCase();
        if (lower.contains("email") || lower.contains("users_email")) {
            body.put("email", List.of("A user with this email already exists."));
        } else {
            body.put("detail", "Could not save user due to a conflict.");
        }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<?> handleIllegalState(IllegalStateException ex) {
        String message = fullMessage(ex);
        logger.warn("Request failed: {}", message);
        String lower = message.toLowerCase();
        if (lower.contains("duplicate key") || lower.contains("unique constraint") || lower.contains("already exists")) {
            Map<String, Object> body = new LinkedHashMap<>();
            if (lower.contains("email") || lower.contains("users_email")) {
                body.put("email", List.of("A user with this email already exists."));
            } else {
                body.put("detail", "Could not save user due to a conflict.");
            }
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
        }
        Map<String, String> body = new LinkedHashMap<>();
        body.put("detail", rootMessage(ex));
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    private static String fullMessage(Throwable ex) {
        StringBuilder builder = new StringBuilder();
        Throwable current = ex;
        while (current != null) {
            if (current.getMessage() != null && !current.getMessage().isBlank()) {
                if (!builder.isEmpty()) {
                    builder.append(" | ");
                }
                builder.append(current.getMessage());
            }
            current = current.getCause();
        }
        return builder.toString();
    }

    private static String rootMessage(Throwable ex) {
        Throwable current = ex;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        if (current.getMessage() != null && !current.getMessage().isBlank()) {
            return current.getMessage();
        }
        return ex.getMessage() != null && !ex.getMessage().isBlank()
                ? ex.getMessage()
                : "Something went wrong";
    }
}
