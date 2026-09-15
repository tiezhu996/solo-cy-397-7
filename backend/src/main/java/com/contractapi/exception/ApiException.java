package com.contractapi.exception;

import java.util.Map;

public class ApiException extends RuntimeException {
  private final String code;
  /** 结构化错误明细，例如 {"missing": ["partyA"]}，便于调用方定位问题 */
  private final Map<String, Object> details;

  public ApiException(String code, String message) {
    this(code, message, null);
  }

  public ApiException(String code, String message, Map<String, Object> details) {
    super(message);
    this.code = code;
    this.details = details;
  }

  public String getCode() { return code; }
  public Map<String, Object> getDetails() { return details; }
}
