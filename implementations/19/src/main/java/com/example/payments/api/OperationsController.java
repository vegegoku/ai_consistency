package com.example.payments.api;

import com.example.payments.application.Reconciliation;
import java.util.*;
import org.springframework.web.bind.annotation.*;

@RestController
public class OperationsController {
  private final Reconciliation report;

  public OperationsController(Reconciliation report) {
    this.report = report;
  }

  @GetMapping("/api/v1/operations/reconciliation")
  public Map<String, Object> reconcile() {
    var issues = report.report();
    return Map.of("healthy", issues.isEmpty(), "issues", issues);
  }
}
