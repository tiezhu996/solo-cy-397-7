package com.contractapi.constants;

public final class ErrorCode {
  public static final String NOT_FOUND = "NOT_FOUND";
  public static final String VALIDATION_FAILED = "VALIDATION_FAILED";
  public static final String PDF_EXPORT_FAILED = "PDF_EXPORT_FAILED";
  public static final String TEMPLATE_NOT_FOUND = "TEMPLATE_NOT_FOUND";
  public static final String VERSION_NOT_FOUND = "VERSION_NOT_FOUND";
  public static final String NO_PUBLISHED_VERSION = "NO_PUBLISHED_VERSION";
  /** 已发布/已归档版本不可再修改或重复发布 */
  public static final String VERSION_IMMUTABLE = "VERSION_IMMUTABLE";
  /** 模板内容中出现了未在变量定义里声明的占位符 */
  public static final String UNDECLARED_PLACEHOLDER = "UNDECLARED_PLACEHOLDER";
  /** 生成合同时缺少必填变量 */
  public static final String MISSING_REQUIRED_VARIABLES = "MISSING_REQUIRED_VARIABLES";
  /** 生成合同时传入了版本未声明的变量 */
  public static final String UNDECLARED_VARIABLES = "UNDECLARED_VARIABLES";
  /** 渲染后合同仍残留占位符，禁止生成 */
  public static final String UNRESOLVED_PLACEHOLDERS = "UNRESOLVED_PLACEHOLDERS";
  /** 并发修改同一模板导致版本号冲突，请求方应重试 */
  public static final String VERSION_CONFLICT = "VERSION_CONFLICT";
  /** 并发发布同一模板：仅一个请求成功，落败方收到此错误 */
  public static final String PUBLISH_CONFLICT = "PUBLISH_CONFLICT";
  private ErrorCode() {}
}
