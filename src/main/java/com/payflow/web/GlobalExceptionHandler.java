package com.payflow.web;

import com.payflow.db.DatabaseGuard;
import com.payflow.error.ErrorMapper;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/** Renders every error in the A7.2 format via {@link ErrorMapper}. */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    private final DatabaseGuard dbGuard;

    public GlobalExceptionHandler(DatabaseGuard dbGuard) {
        this.dbGuard = dbGuard;
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<Map<String, Object>> notFound(NoResourceFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                com.payflow.error.ErrorResponse.body("NOT_FOUND", "no such endpoint", Map.of()));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handle(Exception e) {
        ErrorMapper.Mapped mapped = ErrorMapper.map(e);
        HttpHeaders headers = new HttpHeaders();
        if (mapped.status() == HttpStatus.SERVICE_UNAVAILABLE) {
            dbGuard.onConnectionFailure();
            headers.add(HttpHeaders.RETRY_AFTER, "2");
        }
        if (mapped.status().is5xxServerError()) {
            log.error("request failed: {}", mapped.code(), e);
        }
        return ResponseEntity.status(mapped.status()).headers(headers).body(mapped.body());
    }
}
