package com.contractapi.dto;

import java.util.Map;

/**
 * 生成合同请求。
 * versionNo 指定使用的模板版本；为 null 时使用当前已发布版本。
 * variables 必须与该版本的变量定义严格匹配：缺必填、传未声明变量都会失败。
 */
public record GenerateContractRequest(Long userId, Long templateId, Integer versionNo, String title,
                                      Map<String, String> variables, String format) {}
