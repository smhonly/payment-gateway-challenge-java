package com.checkout.payment.gateway.service;

import com.checkout.payment.gateway.enums.PaymentStatus;
import com.checkout.payment.gateway.model.PostPaymentResponse;
import com.checkout.payment.gateway.repository.PaymentsRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class PendingPaymentHandler {

  private static final Logger LOG = LoggerFactory.getLogger(PendingPaymentHandler.class);

  private final PaymentsRepository paymentsRepository;
  private final MeterRegistry meterRegistry;

  public PendingPaymentHandler(PaymentsRepository paymentsRepository,
      MeterRegistry meterRegistry) {
    this.paymentsRepository = paymentsRepository;
    this.meterRegistry = meterRegistry;
  }

  @Scheduled(fixedRate = 30000)
  public void handle() {
    long start = System.currentTimeMillis();
    List<PostPaymentResponse> pendings = paymentsRepository.findPending();
    if (!pendings.isEmpty()) {
      LOG.warn("pending_sweep count={}", pendings.size());
    }
    for (PostPaymentResponse p : pendings) {
      //option 1. todo: if bank client support inquiry, need query status and update in paymentsRepository.
      //option 2. todo: retry bank call(Need to store PAN+CVV in DB, they are PCI DSS data).

      //option 3. for simple, just set to failed
      p.setStatus(PaymentStatus.FAILED);
      paymentsRepository.save(p);
      LOG.warn("Reconciled old PENDING payment {} to FAILED", p.getId());
    }

    long elapsed = System.currentTimeMillis() - start;
    meterRegistry.counter("payments.pending.sweep.count",
        "status", pendings.isEmpty() ? "empty" : "reconciled").increment();
    meterRegistry.timer("payments.pending.sweep.duration").record(elapsed, TimeUnit.MILLISECONDS);
  }
}
