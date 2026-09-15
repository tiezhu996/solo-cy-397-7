package com.contractapi.constants;

/**
 * 模板版本状态：DRAFT 草稿（不可用于正式生成，可继续基于它出新版本）、
 * PUBLISHED 已发布（唯一、不可变）、ARCHIVED 已归档（历史版本，只读）。
 * 版本行一旦创建，内容与变量定义不再修改；任何修改都产生新版本。
 */
public enum TemplateVersionStatus {
  DRAFT,
  PUBLISHED,
  ARCHIVED
}
