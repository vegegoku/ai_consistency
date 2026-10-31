package com.example.payments.application;

import com.example.payments.persistence.ReconciliationRepository;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ReconciliationService {
  private final ReconciliationRepository repository;

  public ReconciliationService(ReconciliationRepository repository) {
    this.repository = repository;
  }

  @Transactional(
      readOnly = true,
      isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ)
  public List<Map<String, Object>> report() {
    return repository.report();
  }
}
