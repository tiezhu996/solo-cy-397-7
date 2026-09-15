package com.contractapi.utils;

import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class TemplateRenderer {
  private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([^{}]+)\\}");

  /** 提取文本中全部 ${name} 占位符名（按出现顺序去重） */
  public Set<String> extractPlaceholders(String template) {
    Set<String> names = new LinkedHashSet<>();
    if (template == null) {
      return names;
    }
    Matcher matcher = PLACEHOLDER.matcher(template);
    while (matcher.find()) {
      names.add(matcher.group(1).trim());
    }
    return names;
  }

  /** 仅做变量替换，不做任何校验；校验由 VariableValidator 负责 */
  public String render(String template, Map<String, String> variables) {
    String result = template;
    for (Map.Entry<String, String> entry : variables.entrySet()) {
      result = result.replace("${" + entry.getKey() + "}", entry.getValue());
    }
    return result;
  }
}
