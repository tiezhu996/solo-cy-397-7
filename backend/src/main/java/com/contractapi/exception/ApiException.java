package com.contractapi.exception;

import java.util.Map;
import org.springframework.http.HttpStatus;

public class ApiException extends RuntimeException {
  private final String code;
  /** 结构化错误明细，例如 {"missing": ["partyA"]}，便于调用方定位问题 */
  private final Map<String, Object> details;
  /** 响应 HTTP 状态，默认 400；并发冲突类错误使用 409 */
  private final HttpStatus status;

  public ApiException(String code, String message) {
    this(code, message, null, HttpStatus.BAD_REQUEST);
  }

  public ApiException(String code, String message, Map<String, Object> details) {
    this(code, message, details, HttpStatus.BAD_REQUEST);
  }

  public ApiException(String code, String message, HttpStatus status) {
    this(code, message, null, status);
  }

  public ApiException(String code, String message, Map<String, Object> details, HttpStatus status) {
    super(message);
    this.code = code;
    this.details = details;
    this.status = status;
  }

  public String getCode() { return code; }
  public Map<String, Object> getDetails() { return details; }
  public HttpStatus getStatus() { return status; }
}
