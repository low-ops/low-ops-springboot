package com.example.springbootapp.users;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

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
        Map<String, Object> body = new LinkedHashMap<>();
        String message = ex.getMessage() == null ? "" : ex.getMessage().toLowerCase();
        if (message.contains("email")) {
            body.put("email", List.of("A user with this email already exists."));
        } else {
            body.put("detail", "Could not save user due to a conflict.");
        }
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<?> handleIllegalState(IllegalStateException ex) {
        String message = ex.getMessage() == null ? "" : ex.getMessage();
        String lower = message.toLowerCase();
        if (lower.contains("duplicate key") || lower.contains("unique constraint") || lower.contains("already exists")) {
            Map<String, Object> body = new LinkedHashMap<>();
            if (lower.contains("email")) {
                body.put("email", List.of("A user with this email already exists."));
            } else {
                body.put("detail", "Could not save user due to a conflict.");
            }
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
        }
        Map<String, String> body = new LinkedHashMap<>();
        body.put("detail", message.isBlank() ? "Something went wrong" : message);
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }
}
