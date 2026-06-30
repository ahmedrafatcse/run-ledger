package com.runledger.exception;

// global exception handler (catches errors from anywhere in controller layer)
// MERN equivalent = Express error-handling middleware (app.use((err, req, res, next) => {...}))

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException; // triggered when @Valid fails
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

@RestControllerAdvice
// @RestControllerAdvice = applies to ALL controllers globally
// and automatically returns JSON responses (like @RestController)
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    // This handles cases where YOU throw IllegalArgumentException manually
    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, Object>> handleBadArguments(IllegalArgumentException ex) {

        Map<String, Object> body = new HashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("status", 400);
        body.put("error", "Bad Request");
        body.put("message", ex.getMessage());

        return ResponseEntity.badRequest().body(body);
    }

    // This handles validation errors triggered by @Valid in your controller.
    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidationErrors(MethodArgumentNotValidException ex) {

        // Extract the first validation error message (simple approach)
        // Example message: "payload must not be null"
        String message = ex.getBindingResult()
                .getFieldErrors()
                .stream()
                .findFirst()
                .map(err -> err.getField() + " " + err.getDefaultMessage())
                .orElse("Validation failed");

        Map<String, Object> body = new HashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("status", 400);
        body.put("error", "Validation Error");
        body.put("message", message);

        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    // Catch-all fallback handler
    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleGenericError(Exception ex) {

        Map<String, Object> body = new HashMap<>();
        body.put("timestamp", Instant.now().toString());
        body.put("status", 500);
        body.put("error", "Internal Server Error");
        body.put("message", ex.getMessage()); // for dev (remove/replace in production)

        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(body);
    }

    // You can add more handlers later, like:
    //
    // @ExceptionHandler(AccessDeniedException.class) -> return 403 Forbidden
    // @ExceptionHandler(EntityNotFoundException.class) -> return 404 Not Found
    // @ExceptionHandler(DataIntegrityViolationException.class) -> return 409 Conflict
}