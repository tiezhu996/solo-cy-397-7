package com.contractapi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.contractapi.constants.ErrorCode;
import com.contractapi.constants.TemplateVersionStatus;
import com.contractapi.dto.CreateTemplateRequest;
import com.contractapi.dto.CreateVersionRequest;
import com.contractapi.dto.GenerateContractRequest;
import com.contractapi.dto.VariableDefinition;
import com.contractapi.entity.Contract;
import com.contractapi.entity.ContractTemplate;
import com.contractapi.entity.TemplateVersion;
import com.contractapi.exception.ApiException;
import com.contractapi.service.ContractService;
import com.contractapi.service.TemplateService;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;

/**
 * 重启持久化测试：用文件型 H2，先起一个完整的 Spring 上下文写入
 * 模板版本与合同，关闭后（模拟服务重启）再起第二个上下文读回，
 * 验证版本、合同与快照全部持久保存，且版本不变量重启后仍然生效。
 */
class PersistenceRestartTests {
  private static final String DB_URL = "jdbc:h2:file:./target/restart-persistence-test;MODE=MySQL";

  @Test
  void versionsContractsAndSnapshotsSurviveRestart() throws Exception {
    deleteDbFiles();

    // 命令行参数优先级高于 application.yml，确保指向独立的文件型 H2
    String[] args = {
        "--spring.datasource.url=" + DB_URL,
        "--spring.datasource.driver-class-name=org.h2.Driver",
        "--spring.datasource.username=sa",
        "--spring.datasource.password=",
        "--logging.level.root=WARN"};

    long templateId;
    long contractFromV1;
    long contractFromPublished;

    // 第一次启动：建模板、改出新版本、发布、生成合同
    try (var ctx = new SpringApplicationBuilder(ContractApiApplication.class)
        .web(WebApplicationType.NONE).run(args)) {
      TemplateService templates = ctx.getBean(TemplateService.class);
      ContractService contracts = ctx.getBean(ContractService.class);

      ContractTemplate template = templates.createTemplate(new CreateTemplateRequest(
          "LEASE", "租赁合同", "V1 甲方：${partyA}",
          List.of(new VariableDefinition("partyA", "甲方", true))));
      templateId = template.getId();

      templates.createVersion(templateId, new CreateVersionRequest(
          "V2 甲方：${partyA} 租金：${amount}",
          List.of(new VariableDefinition("partyA", "甲方", true),
              new VariableDefinition("amount", "租金", true))));

      templates.publish(templateId, 1);
      templates.publish(templateId, 2); // v1 归档，v2 成为唯一已发布版本

      contractFromV1 = contracts.generate(new GenerateContractRequest(
          7L, templateId, 1, "历史合同", Map.of("partyA", "张三"), null)).getId();
      contractFromPublished = contracts.generate(new GenerateContractRequest(
          7L, templateId, null, "现行合同", Map.of("partyA", "李四", "amount", "5000"), null)).getId();
    }

    // 第二次启动（模拟重启）：全部数据原样读回
    try (var ctx = new SpringApplicationBuilder(ContractApiApplication.class)
        .web(WebApplicationType.NONE).run(args)) {
      TemplateService templates = ctx.getBean(TemplateService.class);
      ContractService contracts = ctx.getBean(ContractService.class);

      List<TemplateVersion> versions = templates.listVersions(templateId);
      assertEquals(2, versions.size());
      assertEquals(TemplateVersionStatus.ARCHIVED.name(), versions.get(0).getStatus());
      assertEquals(TemplateVersionStatus.PUBLISHED.name(), versions.get(1).getStatus());
      assertEquals("V1 甲方：${partyA}", versions.get(0).getContent());
      assertEquals(2, templates.requirePublished(templateId).getVersionNo());

      Contract historical = contracts.get(contractFromV1);
      assertEquals(1, historical.getVersionNo());
      assertEquals("V1 甲方：张三", historical.getContent());
      assertTrue(historical.getVariables().contains("张三"));

      Contract current = contracts.get(contractFromPublished);
      assertEquals(2, current.getVersionNo());
      assertEquals("V2 甲方：李四 租金：5000", current.getContent());
      assertTrue(current.getVariables().contains("5000"));

      // 重启后版本不变量仍生效：已归档版本不能再次发布
      ApiException ex = assertThrows(ApiException.class, () -> templates.publish(templateId, 1));
      assertEquals(ErrorCode.VERSION_IMMUTABLE, ex.getCode());
    } finally {
      deleteDbFiles();
    }
  }

  private static void deleteDbFiles() {
    for (String suffix : new String[] {".mv.db", ".trace.db", ".lock.db"}) {
      try {
        Files.deleteIfExists(Path.of("target", "restart-persistence-test" + suffix));
      } catch (Exception ignored) {
        // 清理失败不影响测试主流程
      }
    }
  }
}
