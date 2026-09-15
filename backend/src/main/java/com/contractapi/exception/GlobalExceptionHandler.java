package com.contractapi.exception;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

@ControllerAdvice
public class GlobalExceptionHandler {
  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  @ExceptionHandler(ApiException.class)
  public ResponseEntity<Map<String, Object>> handleApi(ApiException ex) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("success", false);
    body.put("code", ex.getCode());
    body.put("message", ex.getMessage());
    if (ex.getDetails() != null) {
      body.put("details", ex.getDetails());
    }
    body.put("timestamp", Instant.now().toString());
    return ResponseEntity.status(ex.getStatus()).body(body);
  }

  @ExceptionHandler(Exception.class)
  public ResponseEntity<Map<String, Object>> handleUnknown(Exception ex) {
    log.error("Unhandled exception", ex);
    return ResponseEntity.internalServerError().body(Map.of("success", false, "code", "INTERNAL_ERROR", "message", "服务器内部错误", "timestamp", Instant.now().toString()));
  }
}
