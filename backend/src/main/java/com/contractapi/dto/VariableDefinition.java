package com.contractapi.dto;

/**
 * 模板变量定义。required 缺省视为 true（必填）。
 */
public record VariableDefinition(String name, String label, Boolean required) {
  public boolean isRequired() {
    return required == null || required;
  }
}
