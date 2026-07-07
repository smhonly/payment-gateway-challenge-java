package com.checkout.payment.gateway.repository;

import com.checkout.payment.gateway.enums.PaymentStatus;
import com.checkout.payment.gateway.model.PostPaymentResponse;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class PaymentsRepository {

  private final ConcurrentHashMap<UUID, PostPaymentResponse> payments = new ConcurrentHashMap<>();
  private final ConcurrentHashMap<String, UUID> idempotencyDb = new ConcurrentHashMap<>();

  @Transactional
  public void save(PostPaymentResponse payment) {
    payments.put(payment.getId(), payment);
  }

  public Optional<PostPaymentResponse> get(UUID id) {
    return Optional.ofNullable(payments.get(id));
  }

  public PostPaymentResponse get(String idempotencyKey) {
    UUID paymentId = idempotencyDb.get(idempotencyKey);
    return paymentId == null ? null : payments.get(paymentId);
  }

  @Transactional
  public void save(String idempotencyKey, PostPaymentResponse response) {
    idempotencyDb.put(idempotencyKey, response.getId());
    payments.put(response.getId(), response);
  }

  public List<PostPaymentResponse> findPending() {
    return payments.values().stream()
        .filter(p -> p.getStatus() == PaymentStatus.PENDING)
        .collect(Collectors.toList());
  }

}
