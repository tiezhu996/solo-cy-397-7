package com.contractapi.dto;

import java.util.List;

/**
 * 创建模板请求：同时以 content + variables 生成第 1 个版本（草稿）。
 */
public record CreateTemplateRequest(String type, String title, String content, List<VariableDefinition> variables) {}
