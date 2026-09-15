package com.contractapi.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import java.time.LocalDateTime;

/**
 * 合同模板主表：只保存模板元信息。
 * 内容与变量定义全部放在 template_versions，每次修改产生一个新版本行。
 */
@TableName("contract_templates")
public class ContractTemplate {
  @TableId(type = IdType.AUTO)
  private Long id;
  private String type;
  private String title;
  private LocalDateTime createdAt;

  /** 非持久化字段：当前已发布版本号（无已发布版本时为 null） */
  @TableField(exist = false)
  private Integer publishedVersionNo;
  /** 非持久化字段：版本总数 */
  @TableField(exist = false)
  private Integer versionCount;

  public Long getId() { return id; }
  public void setId(Long id) { this.id = id; }
  public String getType() { return type; }
  public void setType(String type) { this.type = type; }
  public String getTitle() { return title; }
  public void setTitle(String title) { this.title = title; }
  public LocalDateTime getCreatedAt() { return createdAt; }
  public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
  public Integer getPublishedVersionNo() { return publishedVersionNo; }
  public void setPublishedVersionNo(Integer publishedVersionNo) { this.publishedVersionNo = publishedVersionNo; }
  public Integer getVersionCount() { return versionCount; }
  public void setVersionCount(Integer versionCount) { this.versionCount = versionCount; }
}
