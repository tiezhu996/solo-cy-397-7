package com.contractapi;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.contractapi.constants.ErrorCode;
import com.contractapi.constants.TemplateVersionStatus;
import com.contractapi.dto.CreateTemplateRequest;
import com.contractapi.dto.CreateVersionRequest;
import com.contractapi.dto.VariableDefinition;
import com.contractapi.entity.ContractTemplate;
import com.contractapi.entity.TemplateVersion;
import com.contractapi.exception.ApiException;
import com.contractapi.service.TemplateService;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 并发写入测试：多人同时修改/发布同一模板时，
 * 每个请求都必须拿到明确结果（成功或 409 冲突失败），
 * 不允许静默丢请求，也不允许返回内部错误。
 */
@SpringBootTest
class ConcurrentVersioningTests {
  @Autowired
  private TemplateService templateService;

  private ContractTemplate newTemplate() {
    return templateService.createTemplate(new CreateTemplateRequest(
        "LEASE", "并发测试模板", "甲方：${partyA}",
        List.of(new VariableDefinition("partyA", "甲方", true))));
  }

  /** 并发修改同一模板：版本号唯一约束只放行部分请求，其余必须收到明确的 VERSION_CONFLICT */
  @Test
  void concurrentModify_conflictsReturnClearFailure() throws Exception {
    ContractTemplate template = newTemplate();

    int threads = 16;
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    CountDownLatch ready = new CountDownLatch(threads);
    CountDownLatch start = new CountDownLatch(1);
    List<Future<Object>> futures = new ArrayList<>();
    for (int i = 0; i < threads; i++) {
      int batch = i;
      futures.add(pool.submit(() -> {
        ready.countDown();
        start.await();
        try {
          return templateService.createVersion(template.getId(), new CreateVersionRequest(
              "甲方：${partyA} 批次" + batch,
              List.of(new VariableDefinition("partyA", "甲方", true))));
        } catch (Throwable t) {
          return t;
        }
      }));
    }
    ready.await();
    start.countDown();
    List<Object> results = new ArrayList<>();
    for (Future<Object> future : futures) {
      results.add(future.get(30, TimeUnit.SECONDS));
    }
    pool.shutdown();

    List<TemplateVersion> successes = results.stream()
        .filter(r -> r instanceof TemplateVersion).map(r -> (TemplateVersion) r).toList();
    List<ApiException> conflicts = results.stream()
        .filter(r -> r instanceof ApiException).map(r -> (ApiException) r).toList();
    List<Object> unexpected = results.stream()
        .filter(r -> !(r instanceof TemplateVersion) && !(r instanceof ApiException)).toList();

    assertTrue(unexpected.isEmpty(), "并发请求不允许返回内部错误: " + unexpected);
    assertEquals(threads, successes.size() + conflicts.size(), "每个请求都必须有明确结果，不能静默丢失");
    assertTrue(successes.size() >= 1, "至少一个请求成功");
    assertTrue(conflicts.size() >= 1, "并发冲突必须产生明确失败");
    assertTrue(conflicts.stream().allMatch(e -> ErrorCode.VERSION_CONFLICT.equals(e.getCode())),
        "冲突失败必须是 VERSION_CONFLICT");

    // 落库版本 = v1 + 成功数，版本号连续无空洞（没有半个请求写进来的脏数据）
    List<TemplateVersion> versions = templateService.listVersions(template.getId());
    assertEquals(1 + successes.size(), versions.size());
    assertEquals(IntStream.rangeClosed(1, versions.size()).boxed().sorted().toList(),
        versions.stream().map(TemplateVersion::getVersionNo).sorted().toList());
  }

  /** 并发发布：重叠事务中只有一个能抢到唯一已发布槽位，落败方收到明确的
   *  PUBLISH_CONFLICT；无论竞争结果如何，终态必须恰好一个已发布版本。 */
  @Test
  void concurrentPublish_singleWinner_noIntermediateState() throws Exception {
    ContractTemplate template = newTemplate();
    int racers = 8;
    for (int i = 2; i <= racers + 1; i++) {
      templateService.createVersion(template.getId(), new CreateVersionRequest(
          "V" + i + " 甲方：${partyA}", List.of(new VariableDefinition("partyA", "甲方", true))));
    }
    templateService.publish(template.getId(), 1); // v1 已发布，v2..v(racers+1) 为草稿

    ExecutorService pool = Executors.newFixedThreadPool(racers);
    CountDownLatch ready = new CountDownLatch(racers);
    CountDownLatch start = new CountDownLatch(1);
    List<Future<Object>> futures = new ArrayList<>();
    for (int i = 0; i < racers; i++) {
      int versionNo = i + 2;
      futures.add(pool.submit(() -> {
        ready.countDown();
        start.await();
        try {
          return templateService.publish(template.getId(), versionNo);
        } catch (Throwable t) {
          return t;
        }
      }));
    }
    ready.await();
    start.countDown();
    List<Object> results = new ArrayList<>();
    for (Future<Object> future : futures) {
      results.add(future.get(30, TimeUnit.SECONDS));
    }
    pool.shutdown();

    List<TemplateVersion> successes = results.stream()
        .filter(r -> r instanceof TemplateVersion).map(r -> (TemplateVersion) r).toList();
    List<ApiException> conflicts = results.stream()
        .filter(r -> r instanceof ApiException).map(r -> (ApiException) r).toList();
    List<Object> unexpected = results.stream()
        .filter(r -> !(r instanceof TemplateVersion) && !(r instanceof ApiException)).toList();

    assertTrue(unexpected.isEmpty(), "并发发布不允许返回内部错误: " + unexpected);
    assertEquals(racers, successes.size() + conflicts.size(), "每个请求都必须有明确结果，不能静默丢失");
    assertTrue(successes.size() >= 1, "至少一个发布成功");
    assertTrue(conflicts.size() >= 1, "并发发布必须产生明确的落败失败");
    assertTrue(conflicts.stream().allMatch(e -> ErrorCode.PUBLISH_CONFLICT.equals(e.getCode())),
        "落败失败必须是 PUBLISH_CONFLICT");

    // 终态：恰好一个 PUBLISHED——不存在"旧版本已归档、新版本没发布上"的中间状态
    List<TemplateVersion> versions = templateService.listVersions(template.getId());
    long publishedCount = versions.stream()
        .filter(v -> TemplateVersionStatus.PUBLISHED.name().equals(v.getStatus())).count();
    assertEquals(1, publishedCount, "任何时刻必须恰好一个已发布版本");
    assertEquals(TemplateVersionStatus.ARCHIVED.name(), versions.get(0).getStatus(), "v1 应被归档");
  }
}
