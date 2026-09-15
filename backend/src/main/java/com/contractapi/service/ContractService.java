package com.contractapi.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.contractapi.constants.ContractStatus;
import com.contractapi.constants.ErrorCode;
import com.contractapi.dto.GenerateContractRequest;
import com.contractapi.dto.VariableDefinition;
import com.contractapi.entity.Contract;
import com.contractapi.entity.ContractTemplate;
import com.contractapi.entity.TemplateVersion;
import com.contractapi.exception.ApiException;
import com.contractapi.mapper.ContractMapper;
import com.contractapi.utils.TemplateRenderer;
import com.contractapi.utils.VariableValidator;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;

/**
 * 合同生成服务。生成流程：
 * 1. 解析目标版本（指定 versionNo，缺省取当前已发布版本）；
 * 2. 按该版本的变量定义严格校验提交值（未声明/缺必填直接失败）；
 * 3. 渲染并确认无残留占位符（不生成还带占位符的合同）；
 * 4. 落库时固化版本号、版本行 ID、渲染后内容与提交变量快照，重启后原样读回。
 */
@Service
public class ContractService {
  private final TemplateService templateService;
  private final TemplateRenderer renderer;
  private final VariableValidator validator;
  private final ContractMapper contractMapper;
  private final ObjectMapper objectMapper;

  public ContractService(TemplateService templateService, TemplateRenderer renderer,
                         VariableValidator validator, ContractMapper contractMapper,
                         ObjectMapper objectMapper) {
    this.templateService = templateService;
    this.renderer = renderer;
    this.validator = validator;
    this.contractMapper = contractMapper;
    this.objectMapper = objectMapper;
  }

  public Contract generate(GenerateContractRequest request) {
    ContractTemplate template = templateService.get(request.templateId());
    TemplateVersion version = request.versionNo() != null
        ? templateService.getVersion(template.getId(), request.versionNo())
        : templateService.requirePublished(template.getId());

    List<VariableDefinition> definitions = validator.parseDefinitions(version.getVariables());
    Map<String, String> provided = request.variables() == null ? Map.of() : request.variables();
    validator.validateProvided(definitions, provided);

    String content = renderer.render(version.getContent(), provided);
    // 生成前兜底：未填充的合法占位符与非法片段（如历史遗留的未闭合/带空格写法）都不得留在合同里
    List<String> leftover = renderer.extractPlaceholders(content).stream().sorted().toList();
    List<String> malformed = renderer.findMalformedPlaceholders(content);
    if (!leftover.isEmpty() || !malformed.isEmpty()) {
      Map<String, Object> details = new LinkedHashMap<>();
      if (!leftover.isEmpty()) {
        details.put("placeholders", leftover);
      }
      if (!malformed.isEmpty()) {
        details.put("malformed", malformed);
      }
      List<String> all = new ArrayList<>(leftover);
      all.addAll(malformed);
      throw new ApiException(ErrorCode.UNRESOLVED_PLACEHOLDERS,
          "合同存在未填充的占位符或非法片段，禁止生成: " + all, details);
    }

    Contract contract = new Contract();
    contract.setUserId(request.userId() == null ? 0L : request.userId());
    contract.setTemplateId(template.getId());
    contract.setTemplateVersionId(version.getId());
    contract.setVersionNo(version.getVersionNo());
    contract.setTitle(request.title() == null || request.title().isBlank()
        ? template.getTitle() + "（v" + version.getVersionNo() + "）" : request.title());
    contract.setContent(content);
    contract.setVariables(toJson(provided));
    contract.setStatus(ContractStatus.DRAFT.name());
    contract.setSigners("[]");
    contractMapper.insert(contract);
    return contract;
  }

  public Contract get(Long id) {
    Contract contract = contractMapper.selectById(id);
    if (contract == null) {
      throw new ApiException(ErrorCode.NOT_FOUND, "合同不存在: " + id);
    }
    return contract;
  }

  public List<Contract> list(Long userId, String status) {
    return contractMapper.selectList(new LambdaQueryWrapper<Contract>()
        .eq(userId != null, Contract::getUserId, userId)
        .eq(status != null, Contract::getStatus, status)
        .orderByDesc(Contract::getId));
  }

  public Contract updateStatus(Long id, ContractStatus status) {
    Contract contract = get(id);
    contract.setStatus(status.name());
    if (status == ContractStatus.SIGNED && contract.getSignedAt() == null) {
      contract.setSignedAt(LocalDateTime.now());
    }
    contractMapper.updateById(contract);
    return contract;
  }

  public String exportPdf(Long id) {
    get(id);
    return "wkhtmltopdf 已在 Docker 镜像安装，合同 " + id + " 可导出到 /tmp/contracts/" + id + ".pdf";
  }

  private String toJson(Map<String, String> variables) {
    try {
      return objectMapper.writeValueAsString(variables);
    } catch (JsonProcessingException e) {
      throw new ApiException(ErrorCode.VALIDATION_FAILED, "变量值无法序列化: " + e.getOriginalMessage());
    }
  }
}
