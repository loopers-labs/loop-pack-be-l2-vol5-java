package com.loopers.testpg.interfaces;

import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingRequestValueException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;

@RestControllerAdvice
public class PgControllerAdvice {
    @ExceptionHandler({IllegalArgumentException.class, HttpMessageNotReadableException.class, MissingRequestValueException.class})
    ResponseEntity<Map<String, String>> invalidRequest(Exception ignored) {
        return ResponseEntity.badRequest().body(Map.of("code", "INVALID_REQUEST"));
    }

    @ExceptionHandler(ResponseStatusException.class)
    ResponseEntity<Map<String, String>> rejected(ResponseStatusException exception) {
        return ResponseEntity.status(exception.getStatusCode()).body(Map.of("code", exception.getReason()));
    }
}
