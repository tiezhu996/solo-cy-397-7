package com.contractapi.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.contractapi.constants.ErrorCode;
import com.contractapi.constants.TemplateVersionStatus;
import com.contractapi.dto.CreateTemplateRequest;
import com.contractapi.dto.CreateVersionRequest;
import com.contractapi.dto.VariableDefinition;
import com.contractapi.entity.ContractTemplate;
import com.contractapi.entity.TemplateVersion;
import com.contractapi.exception.ApiException;
import com.contractapi.mapper.ContractTemplateMapper;
import com.contractapi.mapper.TemplateVersionMapper;
import com.contractapi.utils.TemplateRenderer;
import com.contractapi.utils.VariableValidator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 模板版本管理服务。核心不变量：
 * 1. 模板内容/变量定义的每一次修改都生成一个新版本行，历史版本永不更新；
 * 2. 同一模板任意时刻最多一个 PUBLISHED 版本（事务内归档旧版本 + published_guard 唯一索引兜底）；
 * 3. 已发布/已归档版本不可再修改或重复发布。
 */
@Service
public class TemplateService {
  private final ContractTemplateMapper templateMapper;
  private final TemplateVersionMapper versionMapper;
  private final TemplateRenderer renderer;
  private final VariableValidator validator;

  public TemplateService(ContractTemplateMapper templateMapper, TemplateVersionMapper versionMapper,
                         TemplateRenderer renderer, VariableValidator validator) {
    this.templateMapper = templateMapper;
    this.versionMapper = versionMapper;
    this.renderer = renderer;
    this.validator = validator;
  }

  /** 创建模板，同时以提交的内容与变量定义生成第 1 个版本（草稿） */
  @Transactional
  public ContractTemplate createTemplate(CreateTemplateRequest request) {
    if (request.title() == null || request.title().isBlank()) {
      throw new ApiException(ErrorCode.VALIDATION_FAILED, "模板标题不能为空");
    }
    validateVersionPayload(request.content(), request.variables());

    ContractTemplate template = new ContractTemplate();
    template.setType(request.type());
    template.setTitle(request.title());
    templateMapper.insert(template);

    TemplateVersion v1 = buildVersion(template.getId(), 1, request.content(), request.variables());
    versionMapper.insert(v1);
    return fillVersionInfo(template);
  }

  /** 修改模板：不改动任何历史版本，追加一个 version_no 递增的新草稿版本。
   *  并发修改同一模板时，uk_template_version 唯一约束只放行一个请求，
   *  其余请求收到明确的 VERSION_CONFLICT(409)，而不是内部错误。 */
  public TemplateVersion createVersion(Long templateId, CreateVersionRequest request) {
    requireTemplate(templateId);
    validateVersionPayload(request.content(), request.variables());

    Integer maxVersionNo = versionMapper.selectObjs(new LambdaQueryWrapper<TemplateVersion>()
            .select(TemplateVersion::getVersionNo)
            .eq(TemplateVersion::getTemplateId, templateId)
            .orderByDesc(TemplateVersion::getVersionNo)
            .last("LIMIT 1"))
        .stream().findFirst().map(o -> (Integer) o).orElse(0);

    TemplateVersion version = buildVersion(templateId, maxVersionNo + 1, request.content(), request.variables());
    try {
      versionMapper.insert(version);
    } catch (DataIntegrityViolationException | ConcurrencyFailureException e) {
      throw new ApiException(ErrorCode.VERSION_CONFLICT,
          "模板 " + templateId + " 存在并发修改，版本号冲突，请重试", HttpStatus.CONFLICT);
    }
    return version;
  }

  /**
   * 发布指定版本：同一事务内先把当前已发布版本归档，再发布目标版本。
   * 仅草稿可发布；已发布/已归档版本再次发布会以 VERSION_IMMUTABLE 失败。
   * 并发发布时 uk_published_guard 唯一约束只放行一个请求；落败方收到明确的
   * PUBLISH_CONFLICT(409)，且整个事务回滚——不会留下"旧版本已归档、
   * 新版本未发布"的中间状态。
   */
  @Transactional
  public TemplateVersion publish(Long templateId, Integer versionNo) {
    requireTemplate(templateId);
    TemplateVersion target = getVersion(templateId, versionNo);
    if (!TemplateVersionStatus.DRAFT.name().equals(target.getStatus())) {
      throw new ApiException(ErrorCode.VERSION_IMMUTABLE,
          "版本 " + versionNo + " 已发布，不可再修改或重复发布");
    }

    TemplateVersion currentPublished = findPublished(templateId);
    try {
      if (currentPublished != null) {
        versionMapper.update(null, new LambdaUpdateWrapper<TemplateVersion>()
            .eq(TemplateVersion::getId, currentPublished.getId())
            .set(TemplateVersion::getStatus, TemplateVersionStatus.ARCHIVED.name())
            .set(TemplateVersion::getPublishedGuard, null));
      }

      target.setStatus(TemplateVersionStatus.PUBLISHED.name());
      target.setPublishedGuard(templateId);
      target.setPublishedAt(java.time.LocalDateTime.now());
      versionMapper.updateById(target);
    } catch (DataIntegrityViolationException | ConcurrencyFailureException e) {
      throw new ApiException(ErrorCode.PUBLISH_CONFLICT,
          "模板 " + templateId + " 存在并发发布，本次发布已被拒绝，请刷新后重试", HttpStatus.CONFLICT);
    }
    return target;
  }

  public List<ContractTemplate> list() {
    return templateMapper.selectList(null).stream().map(this::fillVersionInfo).toList();
  }

  public ContractTemplate get(Long templateId) {
    return fillVersionInfo(requireTemplate(templateId));
  }

  public List<TemplateVersion> listVersions(Long templateId) {
    requireTemplate(templateId);
    return versionMapper.selectList(new LambdaQueryWrapper<TemplateVersion>()
        .eq(TemplateVersion::getTemplateId, templateId)
        .orderByAsc(TemplateVersion::getVersionNo));
  }

  public TemplateVersion getVersion(Long templateId, Integer versionNo) {
    TemplateVersion version = versionMapper.selectOne(new LambdaQueryWrapper<TemplateVersion>()
        .eq(TemplateVersion::getTemplateId, templateId)
        .eq(TemplateVersion::getVersionNo, versionNo));
    if (version == null) {
      throw new ApiException(ErrorCode.VERSION_NOT_FOUND,
          "模板 " + templateId + " 不存在版本 " + versionNo);
    }
    return version;
  }

  /** 当前已发布版本；无则抛 NO_PUBLISHED_VERSION */
  public TemplateVersion requirePublished(Long templateId) {
    TemplateVersion published = findPublished(templateId);
    if (published == null) {
      throw new ApiException(ErrorCode.NO_PUBLISHED_VERSION,
          "模板 " + templateId + " 当前没有已发布版本");
    }
    return published;
  }

  public TemplateVersion findPublished(Long templateId) {
    return versionMapper.selectOne(new LambdaQueryWrapper<TemplateVersion>()
        .eq(TemplateVersion::getTemplateId, templateId)
        .eq(TemplateVersion::getStatus, TemplateVersionStatus.PUBLISHED.name()));
  }

  private ContractTemplate requireTemplate(Long templateId) {
    ContractTemplate template = templateId == null ? null : templateMapper.selectById(templateId);
    if (template == null) {
      throw new ApiException(ErrorCode.TEMPLATE_NOT_FOUND, "模板不存在: " + templateId);
    }
    return template;
  }

  /** 校验一次版本提交：内容非空、变量定义合法、无非法占位符片段、内容中的占位符必须全部已声明 */
  private void validateVersionPayload(String content, List<VariableDefinition> variables) {
    if (content == null || content.isBlank()) {
      throw new ApiException(ErrorCode.VALIDATION_FAILED, "模板内容不能为空");
    }
    validator.validateDefinitions(variables);

    // 未闭合、带空格等非法占位符片段：录入即拒绝，不让"永远填不上"的模板入库
    List<String> malformed = renderer.findMalformedPlaceholders(content);
    if (!malformed.isEmpty()) {
      throw new ApiException(ErrorCode.MALFORMED_PLACEHOLDER,
          "模板内容包含未闭合或格式非法的占位符: " + malformed,
          Map.of("fragments", malformed));
    }

    Set<String> placeholders = renderer.extractPlaceholders(content);
    List<String> declared = (variables == null ? List.<VariableDefinition>of() : variables)
        .stream().map(VariableDefinition::name).toList();
    List<String> undeclaredPlaceholders = placeholders.stream()
        .filter(name -> !declared.contains(name))
        .toList();
    if (!undeclaredPlaceholders.isEmpty()) {
      throw new ApiException(ErrorCode.UNDECLARED_PLACEHOLDER,
          "模板内容包含未声明的占位符: " + undeclaredPlaceholders,
          Map.of("placeholders", undeclaredPlaceholders));
    }
  }

  private TemplateVersion buildVersion(Long templateId, int versionNo, String content,
                                       List<VariableDefinition> variables) {
    TemplateVersion version = new TemplateVersion();
    version.setTemplateId(templateId);
    version.setVersionNo(versionNo);
    version.setContent(content);
    version.setVariables(validator.toJson(variables));
    version.setStatus(TemplateVersionStatus.DRAFT.name());
    return version;
  }

  private ContractTemplate fillVersionInfo(ContractTemplate template) {
    List<TemplateVersion> versions = versionMapper.selectList(new LambdaQueryWrapper<TemplateVersion>()
        .eq(TemplateVersion::getTemplateId, template.getId()));
    template.setVersionCount(versions.size());
    versions.stream()
        .filter(v -> TemplateVersionStatus.PUBLISHED.name().equals(v.getStatus()))
        .findFirst()
        .ifPresent(v -> template.setPublishedVersionNo(v.getVersionNo()));
    return template;
  }
}
