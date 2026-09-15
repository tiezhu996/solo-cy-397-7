package com.contractapi.utils;

import com.contractapi.constants.ErrorCode;
import com.contractapi.dto.VariableDefinition;
import com.contractapi.exception.ApiException;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * 模板变量校验器：负责变量定义的解析/合法性检查，
 * 以及生成合同时「提交值 vs 版本变量定义」的严格校验。
 * 任何不满足都抛出带结构化 details 的 ApiException，绝不静默放行。
 */
@Component
public class VariableValidator {
  private final ObjectMapper objectMapper;

  public VariableValidator(ObjectMapper objectMapper) {
    this.objectMapper = objectMapper;
  }

  /** 解析版本行中存储的变量定义 JSON */
  public List<VariableDefinition> parseDefinitions(String variablesJson) {
    try {
      List<VariableDefinition> definitions = objectMapper.readValue(variablesJson, new TypeReference<>() {});
      return definitions == null ? List.of() : definitions;
    } catch (JsonProcessingException e) {
      throw new ApiException(ErrorCode.VALIDATION_FAILED, "变量定义 JSON 无法解析: " + e.getOriginalMessage());
    }
  }

  /** 序列化变量定义为 JSON 存储 */
  public String toJson(List<VariableDefinition> definitions) {
    try {
      return objectMapper.writeValueAsString(definitions == null ? List.of() : definitions);
    } catch (JsonProcessingException e) {
      throw new ApiException(ErrorCode.VALIDATION_FAILED, "变量定义无法序列化: " + e.getOriginalMessage());
    }
  }

  /** 校验变量定义本身合法：名称非空、符合占位符变量名规则、不重复 */
  public void validateDefinitions(List<VariableDefinition> definitions) {
    if (definitions == null) {
      return;
    }
    Set<String> seen = new LinkedHashSet<>();
    List<String> duplicated = new ArrayList<>();
    for (VariableDefinition definition : definitions) {
      if (definition == null || definition.name() == null || definition.name().isBlank()) {
        throw new ApiException(ErrorCode.VALIDATION_FAILED, "变量定义中存在空名称");
      }
      if (!TemplateRenderer.VARIABLE_NAME.matcher(definition.name()).matches()) {
        throw new ApiException(ErrorCode.VALIDATION_FAILED,
            "变量名不能包含空白或花括号: " + definition.name(),
            Map.of("invalid", definition.name()));
      }
      if (!seen.add(definition.name())) {
        duplicated.add(definition.name());
      }
    }
    if (!duplicated.isEmpty()) {
      throw new ApiException(ErrorCode.VALIDATION_FAILED, "变量定义重复: " + duplicated,
          Map.of("duplicated", duplicated));
    }
  }

  /**
   * 生成合同前的严格校验：
   * 1. 提交的变量名必须全部在版本变量定义中声明过；
   * 2. 所有必填变量必须提交且非空白。
   */
  public void validateProvided(List<VariableDefinition> definitions, Map<String, String> provided) {
    Map<String, VariableDefinition> declared = new LinkedHashMap<>();
    for (VariableDefinition definition : definitions) {
      declared.put(definition.name(), definition);
    }

    List<String> undeclared = provided.keySet().stream()
        .filter(name -> !declared.containsKey(name))
        .sorted()
        .toList();
    if (!undeclared.isEmpty()) {
      throw new ApiException(ErrorCode.UNDECLARED_VARIABLES,
          "传入未声明的变量: " + undeclared, Map.of("undeclared", undeclared));
    }

    List<String> missing = new ArrayList<>();
    for (VariableDefinition definition : definitions) {
      if (!definition.isRequired()) {
        continue;
      }
      String value = provided.get(definition.name());
      if (value == null || value.isBlank()) {
        missing.add(definition.name());
      }
    }
    if (!missing.isEmpty()) {
      throw new ApiException(ErrorCode.MISSING_REQUIRED_VARIABLES,
          "缺少必填变量: " + missing, Map.of("missing", missing));
    }
  }
}
