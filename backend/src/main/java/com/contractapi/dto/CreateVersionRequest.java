package com.contractapi.dto;

import java.util.List;

/**
 * 修改模板请求：每次提交都生成一个新版本（version_no 递增），历史版本保持不变。
 */
public record CreateVersionRequest(String content, List<VariableDefinition> variables) {}
