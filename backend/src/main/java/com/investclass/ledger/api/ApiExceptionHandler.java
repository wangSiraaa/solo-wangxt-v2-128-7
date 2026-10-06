package com.investclass.ledger.api;

import com.investclass.ledger.eod.EodService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(EodService.PublishBlockedException.class)
    public ResponseEntity<Map<String, Object>> blocked(EodService.PublishBlockedException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of(
                "error", "PUBLISH_BLOCKED",
                "message", ex.getMessage(),
                "earliestMismatchEventId", ex.earliestMismatchEventId));
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<Map<String, Object>> illegal(IllegalStateException ex) {
        return ResponseEntity.badRequest().body(Map.of(
                "error", "ILLEGAL_STATE", "message", String.valueOf(ex.getMessage())));
    }
}
