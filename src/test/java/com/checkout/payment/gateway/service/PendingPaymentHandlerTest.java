package com.checkout.payment.gateway.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.checkout.payment.gateway.enums.PaymentStatus;
import com.checkout.payment.gateway.model.PostPaymentResponse;
import com.checkout.payment.gateway.repository.PaymentsRepository;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PendingPaymentHandlerTest {

  @Mock
  PaymentsRepository paymentsRepository;
  @InjectMocks
  PendingPaymentHandler handler;

  @Test
  void handle_whenNoPending_doesNothing() {
    when(paymentsRepository.findPending()).thenReturn(Collections.emptyList());

    handler.handle();

    verify(paymentsRepository, never()).save(anyPayment());
  }

  @Test
  void handle_whenSinglePending_marksAsFailed() {
    UUID id = UUID.randomUUID();
    PostPaymentResponse pending = newPending(id);
    when(paymentsRepository.findPending()).thenReturn(List.of(pending));

    handler.handle();

    assertThat(pending.getStatus()).isEqualTo(PaymentStatus.FAILED);
    verify(paymentsRepository, times(1)).save(pending);
  }

  @Test
  void handle_whenMultiplePending_marksAllAsFailed() {
    PostPaymentResponse a = newPending(UUID.randomUUID());
    PostPaymentResponse b = newPending(UUID.randomUUID());
    PostPaymentResponse c = newPending(UUID.randomUUID());
    when(paymentsRepository.findPending()).thenReturn(List.of(a, b, c));

    handler.handle();

    assertThat(a.getStatus()).isEqualTo(PaymentStatus.FAILED);
    assertThat(b.getStatus()).isEqualTo(PaymentStatus.FAILED);
    assertThat(c.getStatus()).isEqualTo(PaymentStatus.FAILED);
    verify(paymentsRepository, times(3)).save(anyPayment());
  }

  private PostPaymentResponse newPending(UUID id) {
    PostPaymentResponse p = new PostPaymentResponse();
    p.setId(id);
    p.setStatus(PaymentStatus.PENDING);
    return p;
  }

  private static PostPaymentResponse anyPayment() {
    return argThat(p -> true);
  }
}
