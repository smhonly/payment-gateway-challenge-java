package com.checkout.payment.gateway.service;

import com.checkout.payment.gateway.enums.PaymentStatus;
import com.checkout.payment.gateway.model.PostPaymentResponse;
import com.checkout.payment.gateway.repository.PaymentsRepository;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class PendingPaymentHandler {

  private static final Logger LOG = LoggerFactory.getLogger(PendingPaymentHandler.class);

  private final PaymentsRepository paymentsRepository;

  public PendingPaymentHandler(PaymentsRepository paymentsRepository) {
    this.paymentsRepository = paymentsRepository;
  }

  @Scheduled(fixedRate = 30000)
  public void handle() {
    List<PostPaymentResponse> pendings = paymentsRepository.findPending();
    for (PostPaymentResponse p : pendings) {
      //todo: if bank client support inquiry, need query status and update in paymentsRepository.

      //for simple, just set to failed
      p.setStatus(PaymentStatus.FAILED);
      paymentsRepository.store(p);
      LOG.warn("Reconciled old PENDING payment {} to FAILED", p.getId());
    }
  }
}
