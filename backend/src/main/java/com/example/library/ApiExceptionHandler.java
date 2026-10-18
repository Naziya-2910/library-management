package com.example.library;

import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;

import com.example.library.book.BookNotFoundException;
import com.example.library.book.DuplicateIsbnException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;

@RestControllerAdvice
public class ApiExceptionHandler {
    @ExceptionHandler(BookNotFoundException.class)
    public ResponseEntity<Map<String, Object>> notFound(BookNotFoundException exception, WebRequest request) {
        return error(HttpStatus.NOT_FOUND, exception.getMessage(), request);
    }

    @ExceptionHandler({DuplicateIsbnException.class, DataIntegrityViolationException.class})
    public ResponseEntity<Map<String, Object>> conflict(Exception exception, WebRequest request) {
        String message = exception instanceof DuplicateIsbnException
                ? exception.getMessage()
                : "A book with this ISBN already exists";
        return error(HttpStatus.CONFLICT, message, request);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> invalidInput(MethodArgumentNotValidException exception,
                                                              WebRequest request) {
        Map<String, String> validationErrors = new TreeMap<>();
        exception.getBindingResult().getFieldErrors().forEach(error ->
                validationErrors.putIfAbsent(error.getField(), error.getDefaultMessage()));
        Map<String, Object> body = body(HttpStatus.BAD_REQUEST, "Input validation failed", request);
        body.put("validationErrors", validationErrors);
        return ResponseEntity.badRequest().body(body);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, Object>> unreadableInput(HttpMessageNotReadableException exception,
                                                                 WebRequest request) {
        return error(HttpStatus.BAD_REQUEST, "Request body is invalid or contains an unsupported value", request);
    }

    private ResponseEntity<Map<String, Object>> error(HttpStatus status, String message, WebRequest request) {
        return ResponseEntity.status(status).body(body(status, message, request));
    }

    private Map<String, Object> body(HttpStatus status, String message, WebRequest request) {
        return new TreeMap<>(Map.of(
                "timestamp", Instant.now().toString(),
                "status", status.value(),
                "error", status.getReasonPhrase(),
                "message", message,
                "path", request.getDescription(false).replace("uri=", "")));
    }
}
