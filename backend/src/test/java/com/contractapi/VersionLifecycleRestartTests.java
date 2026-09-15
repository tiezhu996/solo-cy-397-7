package com.contractapi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.contractapi.constants.ErrorCode;
import com.contractapi.constants.TemplateVersionStatus;
import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * 版本生命周期 + 变量边界 + 重启读回 的端到端测试。
 *
 * 全程真实链路：独立 Tomcat 端口 → Controller → Service → MyBatis-Plus →
 * 文件型 H2（真实落盘，target/version-lifecycle-it.mv.db），不使用内存替身或
 * 序列化替身。第一次启动完成生命周期/边界/生成场景，关闭上下文（模拟服务重启）
 * 后第二次启动核对读回。每条断言都带【创建版本】/【发布】/【生成】/【重启读回】
 * 阶段标签，失败时可直接定位出问题的阶段。
 *
 * 可重复运行：每次运行前删除数据库文件，连续两次运行结果一致。
 */
class VersionLifecycleRestartTests {
  private static final String DB_URL = "jdbc:h2:file:./target/version-lifecycle-it;MODE=MySQL";

  @Test
  void lifecycleBoundaryAndRestartReadBack() throws Exception {
    deleteDbFiles();
    String[] args = {
        "--spring.datasource.url=" + DB_URL,
        "--spring.datasource.driver-class-name=org.h2.Driver",
        "--spring.datasource.username=sa",
        "--spring.datasource.password=",
        "--server.port=0",
        "--logging.level.root=WARN"};

    long templateA;
    long contractFromV2;
    long contractFromPublished;

    try {
      // ================= 第一次启动：生命周期 + 边界 + 生成 =================
      try (ConfigurableApplicationContext ctx =
          new SpringApplicationBuilder(ContractApiApplication.class).run(args)) {
        TestRestTemplate http = new TestRestTemplate();
        String base = base(ctx);

        // ---------- 版本生命周期 ----------

        // 创建模板 A → v1 草稿
        ResponseEntity<JsonNode> r1 = http.postForEntity(base + "/api/templates",
            Map.of("type", "LEASE", "title", "生命周期模板A",
                "content", "A-V1 甲方：${partyA}",
                "variables", List.of(Map.of("name", "partyA", "label", "甲方", "required", true))),
            JsonNode.class);
        assertEquals(HttpStatus.OK, r1.getStatusCode(), "【创建版本】创建模板A应成功");
        templateA = r1.getBody().get("id").asLong();

        // 修改模板 → v2
        ResponseEntity<JsonNode> r2 = http.postForEntity(base + "/api/templates/" + templateA + "/versions",
            Map.of("content", "A-V2 甲方：${partyA} 租金：${amount}",
                "variables", List.of(
                    Map.of("name", "partyA", "required", true),
                    Map.of("name", "amount", "required", true))),
            JsonNode.class);
        assertEquals(HttpStatus.OK, r2.getStatusCode(), "【创建版本】第一次修改应成功生成新版本");
        assertEquals(2, r2.getBody().get("versionNo").asInt(), "【创建版本】第二次修改应得到版本号 2");
        assertEquals(TemplateVersionStatus.DRAFT.name(), r2.getBody().get("status").asText(),
            "【创建版本】新版本应为草稿");

        // 再修改 → v3
        ResponseEntity<JsonNode> r3 = http.postForEntity(base + "/api/templates/" + templateA + "/versions",
            Map.of("content", "A-V3 甲方：${partyA} 租金：${amount} 期限：${term}",
                "variables", List.of(
                    Map.of("name", "partyA", "required", true),
                    Map.of("name", "amount", "required", true),
                    Map.of("name", "term", "required", true))),
            JsonNode.class);
        assertEquals(3, r3.getBody().get("versionNo").asInt(), "【创建版本】第三次修改应得到版本号 3");

        // 发布 v2，再发布 v3 → v2 应自动归档
        ResponseEntity<JsonNode> p2 = http.postForEntity(
            base + "/api/templates/" + templateA + "/versions/2/publish", null, JsonNode.class);
        assertEquals(HttpStatus.OK, p2.getStatusCode(), "【发布】发布 v2 应成功");
        ResponseEntity<JsonNode> p3 = http.postForEntity(
            base + "/api/templates/" + templateA + "/versions/3/publish", null, JsonNode.class);
        assertEquals(HttpStatus.OK, p3.getStatusCode(), "【发布】发布 v3 应成功");

        JsonNode versions = http.getForEntity(base + "/api/templates/" + templateA + "/versions", JsonNode.class).getBody();
        assertEquals(TemplateVersionStatus.DRAFT.name(), versions.get(0).get("status").asText(),
            "【发布】v1 从未发布应保持草稿");
        assertEquals(TemplateVersionStatus.ARCHIVED.name(), versions.get(1).get("status").asText(),
            "【发布】v3 发布后，旧的已发布版本 v2 应自动归档");
        assertEquals(TemplateVersionStatus.PUBLISHED.name(), versions.get(2).get("status").asText(),
            "【发布】v3 应为当前唯一已发布版本");

        // 归档版本不能再发布
        ResponseEntity<JsonNode> republish = http.postForEntity(
            base + "/api/templates/" + templateA + "/versions/2/publish", null, JsonNode.class);
        assertEquals(HttpStatus.BAD_REQUEST, republish.getStatusCode(), "【发布】归档版本再次发布应失败");
        assertEquals(ErrorCode.VERSION_IMMUTABLE, republish.getBody().get("code").asText(),
            "【发布】归档版本再次发布应返回 VERSION_IMMUTABLE");

        // 发布不存在的版本
        ResponseEntity<JsonNode> notFound = http.postForEntity(
            base + "/api/templates/" + templateA + "/versions/99/publish", null, JsonNode.class);
        assertEquals(ErrorCode.VERSION_NOT_FOUND, notFound.getBody().get("code").asText(),
            "【发布】发布不存在的版本应返回 VERSION_NOT_FOUND");

        // 拿别的模板去取版本：模板 B 只有自己的 v1
        ResponseEntity<JsonNode> rb = http.postForEntity(base + "/api/templates",
            Map.of("type", "NDA", "title", "模板B",
                "content", "B-V1 保密方：${partyA}",
                "variables", List.of(Map.of("name", "partyA", "required", true))),
            JsonNode.class);
        long templateB = rb.getBody().get("id").asLong();
        JsonNode bV1 = http.getForEntity(base + "/api/templates/" + templateB + "/versions/1", JsonNode.class).getBody();
        assertEquals("B-V1 保密方：${partyA}", bV1.get("content").asText(),
            "【创建版本】模板B的 v1 应是 B 自己的内容，版本按模板隔离");
        ResponseEntity<JsonNode> crossGet = http.getForEntity(
            base + "/api/templates/" + templateB + "/versions/3", JsonNode.class);
        assertEquals(ErrorCode.VERSION_NOT_FOUND, crossGet.getBody().get("code").asText(),
            "【创建版本】用模板B取只存在于模板A的 v3 应返回 VERSION_NOT_FOUND");
        ResponseEntity<JsonNode> crossPublish = http.postForEntity(
            base + "/api/templates/" + templateB + "/versions/2/publish", null, JsonNode.class);
        assertEquals(ErrorCode.VERSION_NOT_FOUND, crossPublish.getBody().get("code").asText(),
            "【发布】用模板B发布不属于自己的 v2 应返回 VERSION_NOT_FOUND");
        ResponseEntity<JsonNode> crossGenerate = http.postForEntity(base + "/api/contracts/generate",
            Map.of("userId", 7, "templateId", templateB, "versionNo", 3,
                "variables", Map.of("partyA", "张三")),
            JsonNode.class);
        assertEquals(ErrorCode.VERSION_NOT_FOUND, crossGenerate.getBody().get("code").asText(),
            "【生成】用模板B指定只存在于模板A的版本生成合同应返回 VERSION_NOT_FOUND");

        // ---------- 变量定义边界 ----------

        // 模板内容里出现未声明占位符
        ResponseEntity<JsonNode> ghost = http.postForEntity(base + "/api/templates",
            Map.of("type", "LEASE", "title", "坏模板",
                "content", "甲方：${partyA} 幽灵：${ghost}",
                "variables", List.of(Map.of("name", "partyA", "required", true))),
            JsonNode.class);
        assertEquals(ErrorCode.UNDECLARED_PLACEHOLDER, ghost.getBody().get("code").asText(),
            "【创建版本】内容含未声明占位符应返回 UNDECLARED_PLACEHOLDER");
        assertEquals("ghost", ghost.getBody().at("/details/placeholders/0").asText(),
            "【创建版本】应指出未声明的占位符名");

        // 变量定义重复
        ResponseEntity<JsonNode> duplicated = http.postForEntity(base + "/api/templates",
            Map.of("type", "LEASE", "title", "重复变量模板",
                "content", "甲方：${partyA}",
                "variables", List.of(
                    Map.of("name", "partyA", "required", true),
                    Map.of("name", "partyA", "required", false))),
            JsonNode.class);
        assertEquals(ErrorCode.VALIDATION_FAILED, duplicated.getBody().get("code").asText(),
            "【创建版本】变量定义重复应返回 VALIDATION_FAILED");
        assertEquals("partyA", duplicated.getBody().at("/details/duplicated/0").asText(),
            "【创建版本】应指出重复的变量名");

        // 非必填变量没传值：内容引用了它 → 残留占位符，拒绝生成
        ResponseEntity<JsonNode> rc = http.postForEntity(base + "/api/templates",
            Map.of("type", "LEASE", "title", "可选变量模板C",
                "content", "甲方：${partyA} 备注：${note}",
                "variables", List.of(
                    Map.of("name", "partyA", "required", true),
                    Map.of("name", "note", "required", false))),
            JsonNode.class);
        long templateC = rc.getBody().get("id").asLong();
        http.postForEntity(base + "/api/templates/" + templateC + "/versions/1/publish", null, JsonNode.class);
        ResponseEntity<JsonNode> noNote = http.postForEntity(base + "/api/contracts/generate",
            Map.of("userId", 7, "templateId", templateC,
                "variables", Map.of("partyA", "张三")),
            JsonNode.class);
        assertEquals(ErrorCode.UNRESOLVED_PLACEHOLDERS, noNote.getBody().get("code").asText(),
            "【生成】非必填变量未传值但内容引用，应拒绝生成带占位符的合同");
        assertEquals("note", noNote.getBody().at("/details/placeholders/0").asText(),
            "【生成】应指出残留的占位符名");

        // 非必填变量没传值：内容没引用它 → 正常生成
        ResponseEntity<JsonNode> rd = http.postForEntity(base + "/api/templates",
            Map.of("type", "LEASE", "title", "可选变量模板D",
                "content", "甲方：${partyA}",
                "variables", List.of(
                    Map.of("name", "partyA", "required", true),
                    Map.of("name", "note", "required", false))),
            JsonNode.class);
        long templateD = rd.getBody().get("id").asLong();
        http.postForEntity(base + "/api/templates/" + templateD + "/versions/1/publish", null, JsonNode.class);
        ResponseEntity<JsonNode> optionalOk = http.postForEntity(base + "/api/contracts/generate",
            Map.of("userId", 7, "templateId", templateD,
                "variables", Map.of("partyA", "王五")),
            JsonNode.class);
        assertEquals(HttpStatus.OK, optionalOk.getStatusCode(),
            "【生成】非必填变量未传值且内容未引用，应正常生成");
        assertEquals("甲方：王五", optionalOk.getBody().get("content").asText(),
            "【生成】生成内容应完整且无占位符残留");

        // ---------- 生成合同（供重启后核对快照） ----------

        // 按已归档的 v2 生成：历史版本内容固化
        ResponseEntity<JsonNode> c1 = http.postForEntity(base + "/api/contracts/generate",
            Map.of("userId", 7, "templateId", templateA, "versionNo", 2, "title", "历史合同",
                "variables", Map.of("partyA", "张三", "amount", "5000")),
            JsonNode.class);
        assertEquals(HttpStatus.OK, c1.getStatusCode(), "【生成】按已归档版本生成应成功");
        assertEquals(2, c1.getBody().get("versionNo").asInt(), "【生成】合同应固化版本号 2");
        assertEquals("A-V2 甲方：张三 租金：5000", c1.getBody().get("content").asText(),
            "【生成】合同内容应按 v2 当时的内容渲染");
        contractFromV2 = c1.getBody().get("id").asLong();

        // 缺省版本 → 使用当前已发布的 v3
        ResponseEntity<JsonNode> c2 = http.postForEntity(base + "/api/contracts/generate",
            Map.of("userId", 7, "templateId", templateA, "title", "现行合同",
                "variables", Map.of("partyA", "李四", "amount", "6000", "term", "两年")),
            JsonNode.class);
        assertEquals(HttpStatus.OK, c2.getStatusCode(), "【生成】缺省版本生成应成功");
        assertEquals(3, c2.getBody().get("versionNo").asInt(), "【生成】缺省版本应使用已发布的 v3");
        contractFromPublished = c2.getBody().get("id").asLong();
      }

      // ================= 重启：第二次启动核对读回 =================
      try (ConfigurableApplicationContext ctx =
          new SpringApplicationBuilder(ContractApiApplication.class).run(args)) {
        TestRestTemplate http = new TestRestTemplate();
        String base = base(ctx);

        // 版本读回：三个版本状态与内容原样保留
        JsonNode versions = http.getForEntity(base + "/api/templates/" + templateA + "/versions", JsonNode.class).getBody();
        assertEquals(3, versions.size(), "【重启读回】模板A应有 3 个版本");
        assertEquals(TemplateVersionStatus.DRAFT.name(), versions.get(0).get("status").asText(),
            "【重启读回】v1 应仍为草稿");
        assertEquals(TemplateVersionStatus.ARCHIVED.name(), versions.get(1).get("status").asText(),
            "【重启读回】v2 应仍为已归档");
        assertEquals(TemplateVersionStatus.PUBLISHED.name(), versions.get(2).get("status").asText(),
            "【重启读回】v3 应仍为已发布");
        assertEquals("A-V2 甲方：${partyA} 租金：${amount}", versions.get(1).get("content").asText(),
            "【重启读回】v2 的模板内容应原样读回");

        JsonNode published = http.getForEntity(base + "/api/templates/" + templateA + "/published", JsonNode.class).getBody();
        assertEquals(3, published.get("versionNo").asInt(), "【重启读回】当前已发布版本应仍为 v3");

        // 合同快照读回：版本号、内容、提交变量全部固化
        JsonNode c1 = http.getForEntity(base + "/api/contracts/" + contractFromV2, JsonNode.class).getBody();
        assertEquals(2, c1.get("versionNo").asInt(), "【重启读回】合同固化的版本号应为 2");
        assertEquals("A-V2 甲方：张三 租金：5000", c1.get("content").asText(),
            "【重启读回】历史合同内容应原样读回");
        assertEquals("张三", c1.at("/variables/partyA").asText(), "【重启读回】合同变量快照应原样读回");

        JsonNode c2 = http.getForEntity(base + "/api/contracts/" + contractFromPublished, JsonNode.class).getBody();
        assertEquals(3, c2.get("versionNo").asInt(), "【重启读回】合同固化的版本号应为 3");
        assertEquals("A-V3 甲方：李四 租金：6000 期限：两年", c2.get("content").asText(),
            "【重启读回】现行合同内容应原样读回");
        assertFalse(c2.get("content").asText().contains("${"), "【重启读回】合同内容不应含占位符");

        // 重启后版本不变量仍生效：归档版本依旧不可再发布
        ResponseEntity<JsonNode> republish = http.postForEntity(
            base + "/api/templates/" + templateA + "/versions/2/publish", null, JsonNode.class);
        assertEquals(ErrorCode.VERSION_IMMUTABLE, republish.getBody().get("code").asText(),
            "【重启读回】归档版本在重启后仍不可再发布");
      }
    } finally {
      deleteDbFiles();
    }
  }

  private static String base(ConfigurableApplicationContext ctx) {
    return "http://localhost:" + ctx.getEnvironment().getProperty("local.server.port");
  }

  private static void deleteDbFiles() {
    for (String suffix : new String[] {".mv.db", ".trace.db", ".lock.db"}) {
      try {
        Files.deleteIfExists(Path.of("target", "version-lifecycle-it" + suffix));
      } catch (Exception ignored) {
        // 清理失败不影响测试主流程
      }
    }
  }
}
