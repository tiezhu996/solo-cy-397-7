package com.contractapi.utils;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

/**
 * 模板渲染器：负责占位符的识别与替换。
 * 合法占位符严格定义为 ${name}——name 非空且不含空白（含全角空格）与花括号；
 * 未闭合（${name）、带空格（${ name }、$ {name}）、空名（${}）等写法都属于
 * 非法片段，由 findMalformedPlaceholders 检出，录入与生成两个环节都会拦截。
 */
@Component
public class TemplateRenderer {
  /** 变量名规则：非空，不含空白字符与花括号（允许中文名） */
  public static final Pattern VARIABLE_NAME = Pattern.compile("[^{}\\s\\p{Z}]+");
  /** 合法占位符：${name}，name 规则同 VARIABLE_NAME */
  private static final Pattern VALID_PLACEHOLDER = Pattern.compile("\\$\\{([^{}\\s\\p{Z}]+)\\}");
  /** 占位符意图：$ 后紧跟（可含空白）{ —— 用于检出写法非法的占位符片段 */
  private static final Pattern PLACEHOLDER_START = Pattern.compile("\\$[\\s\\p{Z}]*\\{");

  /** 提取文本中全部合法 ${name} 占位符名（按出现顺序去重） */
  public Set<String> extractPlaceholders(String template) {
    Set<String> names = new LinkedHashSet<>();
    if (template == null) {
      return names;
    }
    Matcher matcher = VALID_PLACEHOLDER.matcher(template);
    while (matcher.find()) {
      names.add(matcher.group(1));
    }
    return names;
  }

  /**
   * 检出非法占位符片段：任何"$ + 可选空白 + {"的位置，若不能构成合法 ${name}，
   * 即为非法片段（未闭合、带空格、空名等），返回原始片段用于错误提示。
   */
  public List<String> findMalformedPlaceholders(String content) {
    List<String> malformed = new ArrayList<>();
    if (content == null) {
      return malformed;
    }
    Matcher starts = PLACEHOLDER_START.matcher(content);
    while (starts.find()) {
      int start = starts.start();
      Matcher valid = VALID_PLACEHOLDER.matcher(content).region(start, content.length());
      if (valid.lookingAt()) {
        continue; // 合法占位符，跳过
      }
      malformed.add(snippet(content, start));
    }
    return malformed;
  }

  /** 截取非法片段：到最近的 } 为止；没有 } 则截取前 20 个字符 */
  private static String snippet(String content, int start) {
    int closing = content.indexOf('}', start);
    if (closing >= 0 && closing - start <= 60) {
      return content.substring(start, closing + 1);
    }
    int end = Math.min(content.length(), start + 20);
    String snippet = content.substring(start, end);
    return end < content.length() ? snippet + "…" : snippet;
  }

  /** 仅做合法占位符的精确替换，不做任何校验；校验由 VariableValidator 与残留检查负责 */
  public String render(String template, java.util.Map<String, String> variables) {
    String result = template;
    for (java.util.Map.Entry<String, String> entry : variables.entrySet()) {
      result = result.replace("${" + entry.getKey() + "}", entry.getValue());
    }
    return result;
  }
}
