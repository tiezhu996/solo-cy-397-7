package com.contractapi.controller;

import java.util.List;
import com.contractapi.constants.ContractStatus;
import com.contractapi.dto.GenerateContractRequest;
import com.contractapi.entity.Contract;
import com.contractapi.service.ContractService;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/contracts")
public class ContractController {
  private final ContractService service;
  public ContractController(ContractService service) { this.service = service; }

  /** 生成合同：versionNo 指定模板版本（缺省用当前已发布版本），按该版本变量定义严格校验 */
  @PostMapping("/generate")
  public Contract generate(@RequestBody GenerateContractRequest request) { return service.generate(request); }

  @GetMapping("/{id}")
  public Contract get(@PathVariable("id") Long id) { return service.get(id); }

  @PatchMapping("/{id}/status")
  public Contract updateStatus(@PathVariable("id") Long id, @RequestParam("status") ContractStatus status) { return service.updateStatus(id, status); }

  @GetMapping
  public List<Contract> list(@RequestParam(name = "userId", required = false) Long userId, @RequestParam(name = "status", required = false) String status) { return service.list(userId, status); }

  @PostMapping("/{id}/pdf")
  public String exportPdf(@PathVariable("id") Long id) { return service.exportPdf(id); }
}
