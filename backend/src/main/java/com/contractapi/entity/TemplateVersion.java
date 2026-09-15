package com.contractapi.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonRawValue;
import java.time.LocalDateTime;

/**
 * 模板版本：内容 + 变量定义的不可变快照。
 * 每次修改模板都会插入一行新版本；已发布版本不允许再修改（服务层拒绝 + 无更新入口）。
 * publishedGuard：仅当状态为 PUBLISHED 时等于 templateId，其余为 NULL，
 * 配合 uk_published_guard 唯一索引保证同一模板最多一个已发布版本。
 */
@TableName("template_versions")
public class TemplateVersion {
  @TableId(type = IdType.AUTO)
  private Long id;
  private Long templateId;
  private Integer versionNo;
  private String content;
  /** 变量定义 JSON：[{name,label,required}] */
  private String variables;
  private String status;
  private Long publishedGuard;
  private LocalDateTime createdAt;
  private LocalDateTime publishedAt;

  public Long getId() { return id; }
  public void setId(Long id) { this.id = id; }
  public Long getTemplateId() { return templateId; }
  public void setTemplateId(Long templateId) { this.templateId = templateId; }
  public Integer getVersionNo() { return versionNo; }
  public void setVersionNo(Integer versionNo) { this.versionNo = versionNo; }
  public String getContent() { return content; }
  public void setContent(String content) { this.content = content; }
  @JsonRawValue
  public String getVariables() { return variables; }
  public void setVariables(String variables) { this.variables = variables; }
  public String getStatus() { return status; }
  public void setStatus(String status) { this.status = status; }
  public Long getPublishedGuard() { return publishedGuard; }
  public void setPublishedGuard(Long publishedGuard) { this.publishedGuard = publishedGuard; }
  public LocalDateTime getCreatedAt() { return createdAt; }
  public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
  public LocalDateTime getPublishedAt() { return publishedAt; }
  public void setPublishedAt(LocalDateTime publishedAt) { this.publishedAt = publishedAt; }
}
