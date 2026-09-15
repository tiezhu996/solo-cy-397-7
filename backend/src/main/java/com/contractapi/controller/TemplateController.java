package com.contractapi.controller;

import java.util.List;
import com.contractapi.dto.CreateTemplateRequest;
import com.contractapi.dto.CreateVersionRequest;
import com.contractapi.entity.ContractTemplate;
import com.contractapi.entity.TemplateVersion;
import com.contractapi.service.TemplateService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/templates")
public class TemplateController {
  private final TemplateService service;
  public TemplateController(TemplateService service) { this.service = service; }

  @GetMapping
  public List<ContractTemplate> list() { return service.list(); }

  @PostMapping
  public ContractTemplate create(@RequestBody CreateTemplateRequest request) { return service.createTemplate(request); }

  @GetMapping("/{id}")
  public ContractTemplate get(@PathVariable("id") Long id) { return service.get(id); }

  /** 修改模板内容/变量定义：每次调用生成一个新版本，历史版本保持不变 */
  @PostMapping("/{id}/versions")
  public TemplateVersion createVersion(@PathVariable("id") Long id, @RequestBody CreateVersionRequest request) {
    return service.createVersion(id, request);
  }

  @GetMapping("/{id}/versions")
  public List<TemplateVersion> listVersions(@PathVariable("id") Long id) { return service.listVersions(id); }

  @GetMapping("/{id}/versions/{versionNo}")
  public TemplateVersion getVersion(@PathVariable("id") Long id, @PathVariable("versionNo") Integer versionNo) {
    return service.getVersion(id, versionNo);
  }

  /** 发布指定版本：同一模板同时只有一个已发布版本，旧的已发布版本自动归档 */
  @PostMapping("/{id}/versions/{versionNo}/publish")
  public TemplateVersion publish(@PathVariable("id") Long id, @PathVariable("versionNo") Integer versionNo) {
    return service.publish(id, versionNo);
  }

  @GetMapping("/{id}/published")
  public TemplateVersion published(@PathVariable("id") Long id) { return service.requirePublished(id); }
}
