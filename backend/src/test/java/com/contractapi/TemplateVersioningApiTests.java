package com.contractapi;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 模板版本管理全流程接口测试（内存 H2，MySQL 兼容模式，配置见 src/test/resources/application.yml）。
 */
@SpringBootTest
@AutoConfigureMockMvc
class TemplateVersioningApiTests {
  @Autowired
  private MockMvc mvc;

  private long createTemplate(String content, String variablesJson) throws Exception {
    String body = """
        {"type":"LEASE","title":"租赁合同","content":"%s","variables":%s}
        """.formatted(content, variablesJson);
    MvcResult result = mvc.perform(post("/api/templates").contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").isNumber())
        .andReturn();
    return Long.parseLong(com.jayway.jsonpath.JsonPath.read(result.getResponse().getContentAsString(), "$.id").toString());
  }

  @Test
  void modifyCreatesNewVersion_andOnlyOnePublishedVersion() throws Exception {
    long templateId = createTemplate("甲方：${partyA}", "[{\"name\":\"partyA\",\"label\":\"甲方\",\"required\":true}]");

    // 初始版本 v1 为草稿
    mvc.perform(get("/api/templates/{id}/versions", templateId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].versionNo").value(1))
        .andExpect(jsonPath("$[0].status").value("DRAFT"));

    // 修改模板 → 生成 v2，v1 内容保持原样
    mvc.perform(post("/api/templates/{id}/versions", templateId)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"content\":\"甲方：${partyA} 乙方：${partyB}\",\"variables\":[{\"name\":\"partyA\",\"required\":true},{\"name\":\"partyB\",\"required\":true}]}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.versionNo").value(2))
        .andExpect(jsonPath("$.status").value("DRAFT"));
    mvc.perform(get("/api/templates/{id}/versions/1", templateId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.content").value("甲方：${partyA}"));

    // 发布 v1，再发布 v2：v1 自动归档，全模板始终只有一个已发布版本
    mvc.perform(post("/api/templates/{id}/versions/1/publish", templateId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("PUBLISHED"));
    mvc.perform(post("/api/templates/{id}/versions/2/publish", templateId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("PUBLISHED"));
    mvc.perform(get("/api/templates/{id}/versions", templateId))
        .andExpect(jsonPath("$[0].status").value("ARCHIVED"))
        .andExpect(jsonPath("$[1].status").value("PUBLISHED"));
    mvc.perform(get("/api/templates/{id}", templateId))
        .andExpect(jsonPath("$.publishedVersionNo").value(2))
        .andExpect(jsonPath("$.versionCount").value(2));

    // 已发布/已归档版本不可再发布或修改
    mvc.perform(post("/api/templates/{id}/versions/1/publish", templateId))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VERSION_IMMUTABLE"));
    mvc.perform(post("/api/templates/{id}/versions/2/publish", templateId))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VERSION_IMMUTABLE"));
  }

  @Test
  void generate_validatesVariablesStrictly() throws Exception {
    long templateId = createTemplate(
        "甲方：${partyA}\\n金额：${amount}\\n备注：${note}",
        "[{\"name\":\"partyA\",\"required\":true},{\"name\":\"amount\",\"required\":true},{\"name\":\"note\",\"required\":false}]");
    mvc.perform(post("/api/templates/{id}/versions/1/publish", templateId)).andExpect(status().isOk());

    // 缺必填变量 → 明确失败并指出缺哪个
    mvc.perform(post("/api/contracts/generate").contentType(MediaType.APPLICATION_JSON)
            .content("{\"userId\":7,\"templateId\":" + templateId + ",\"variables\":{\"partyA\":\"张三\",\"note\":\"无\"}}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("MISSING_REQUIRED_VARIABLES"))
        .andExpect(jsonPath("$.details.missing[0]").value("amount"));

    // 传入未声明变量 → 明确失败并指出是哪个
    mvc.perform(post("/api/contracts/generate").contentType(MediaType.APPLICATION_JSON)
            .content("{\"userId\":7,\"templateId\":" + templateId + ",\"variables\":{\"partyA\":\"张三\",\"amount\":\"5000\",\"note\":\"无\",\"hacker\":\"x\"}}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("UNDECLARED_VARIABLES"))
        .andExpect(jsonPath("$.details.undeclared[0]").value("hacker"));

    // 可选变量未填导致占位符残留 → 拒绝生成
    mvc.perform(post("/api/contracts/generate").contentType(MediaType.APPLICATION_JSON)
            .content("{\"userId\":7,\"templateId\":" + templateId + ",\"variables\":{\"partyA\":\"张三\",\"amount\":\"5000\"}}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("UNRESOLVED_PLACEHOLDERS"))
        .andExpect(jsonPath("$.details.placeholders[0]").value("note"));

    // 全部满足 → 生成成功，内容无占位符，且固化版本与变量快照
    MvcResult created = mvc.perform(post("/api/contracts/generate").contentType(MediaType.APPLICATION_JSON)
            .content("{\"userId\":7,\"templateId\":" + templateId + ",\"title\":\"测试合同\",\"variables\":{\"partyA\":\"张三\",\"amount\":\"5000\",\"note\":\"无\"}}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.versionNo").value(1))
        .andExpect(jsonPath("$.templateVersionId").isNumber())
        .andExpect(jsonPath("$.content", containsString("甲方：张三")))
        .andExpect(jsonPath("$.content", not(containsString("${"))))
        .andExpect(jsonPath("$.variables.partyA").value("张三"))
        .andReturn();
    long contractId = Long.parseLong(com.jayway.jsonpath.JsonPath.read(created.getResponse().getContentAsString(), "$.id").toString());

    // 快照可原样读回
    mvc.perform(get("/api/contracts/{id}", contractId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.versionNo").value(1))
        .andExpect(jsonPath("$.content", containsString("金额：5000")))
        .andExpect(jsonPath("$.variables.amount").value("5000"));
  }

  @Test
  void generate_usesSpecifiedVersion_andDefaultsToPublished() throws Exception {
    long templateId = createTemplate("V1 甲方：${partyA}", "[{\"name\":\"partyA\",\"required\":true}]");
    mvc.perform(post("/api/templates/{id}/versions", templateId).contentType(MediaType.APPLICATION_JSON)
            .content("{\"content\":\"V2 甲方：${partyA} 租金：${amount}\",\"variables\":[{\"name\":\"partyA\",\"required\":true},{\"name\":\"amount\",\"required\":true}]}"))
        .andExpect(status().isOk());
    mvc.perform(post("/api/templates/{id}/versions/1/publish", templateId)).andExpect(status().isOk());
    mvc.perform(post("/api/templates/{id}/versions/2/publish", templateId)).andExpect(status().isOk());

    // 指定历史版本（已归档）→ 仍按该版本当时的内容生成
    mvc.perform(post("/api/contracts/generate").contentType(MediaType.APPLICATION_JSON)
            .content("{\"userId\":7,\"templateId\":" + templateId + ",\"versionNo\":1,\"variables\":{\"partyA\":\"张三\"}}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.versionNo").value(1))
        .andExpect(jsonPath("$.content").value("V1 甲方：张三"));

    // 不指定版本 → 使用当前已发布版本 v2
    mvc.perform(post("/api/contracts/generate").contentType(MediaType.APPLICATION_JSON)
            .content("{\"userId\":7,\"templateId\":" + templateId + ",\"variables\":{\"partyA\":\"李四\",\"amount\":\"5000\"}}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.versionNo").value(2))
        .andExpect(jsonPath("$.content").value("V2 甲方：李四 租金：5000"));

    // 指定不存在的版本 → 明确失败
    mvc.perform(post("/api/contracts/generate").contentType(MediaType.APPLICATION_JSON)
            .content("{\"userId\":7,\"templateId\":" + templateId + ",\"versionNo\":99,\"variables\":{\"partyA\":\"张三\"}}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("VERSION_NOT_FOUND"));
  }

  @Test
  void templatePayload_rejectsUndeclaredPlaceholder() throws Exception {
    // 模板内容引用了未声明的占位符 → 创建失败
    mvc.perform(post("/api/templates").contentType(MediaType.APPLICATION_JSON)
            .content("{\"type\":\"LEASE\",\"title\":\"坏模板\",\"content\":\"甲方：${partyA} 幽灵：${ghost}\",\"variables\":[{\"name\":\"partyA\",\"required\":true}]}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("UNDECLARED_PLACEHOLDER"))
        .andExpect(jsonPath("$.details.placeholders[0]").value("ghost"));

    // 新版本同样校验
    long templateId = createTemplate("甲方：${partyA}", "[{\"name\":\"partyA\",\"required\":true}]");
    mvc.perform(post("/api/templates/{id}/versions", templateId).contentType(MediaType.APPLICATION_JSON)
            .content("{\"content\":\"甲方：${partyA} 幽灵：${ghost}\",\"variables\":[{\"name\":\"partyA\",\"required\":true}]}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("UNDECLARED_PLACEHOLDER"));
  }

  @Test
  void generate_withoutPublishedVersion_failsClearly() throws Exception {
    long templateId = createTemplate("甲方：${partyA}", "[{\"name\":\"partyA\",\"required\":true}]");
    mvc.perform(post("/api/contracts/generate").contentType(MediaType.APPLICATION_JSON)
            .content("{\"userId\":7,\"templateId\":" + templateId + ",\"variables\":{\"partyA\":\"张三\"}}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("NO_PUBLISHED_VERSION"));

    mvc.perform(get("/api/templates/{id}", 424242L))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("TEMPLATE_NOT_FOUND"));
  }
}
