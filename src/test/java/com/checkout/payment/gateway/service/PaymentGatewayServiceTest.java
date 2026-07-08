package com.checkout.payment.gateway.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.checkout.payment.gateway.client.BankClient;
import com.checkout.payment.gateway.enums.PaymentStatus;
import com.checkout.payment.gateway.exception.BankUnavailableException;
import com.checkout.payment.gateway.exception.EventProcessingException;
import com.checkout.payment.gateway.exception.InvalidRequestException;
import com.checkout.payment.gateway.model.BankPaymentRequest;
import com.checkout.payment.gateway.model.BankPaymentResponse;
import com.checkout.payment.gateway.model.PostPaymentRequest;
import com.checkout.payment.gateway.model.PostPaymentResponse;
import com.checkout.payment.gateway.repository.PaymentsRepository;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class PaymentGatewayServiceTest {

  @Mock
  PaymentsRepository paymentsRepository;
  @Mock
  BankClient bankClient;
  @InjectMocks
  PaymentGatewayService service;

  private static final String VALID_CARD = "2222405343248877";
  private static final String KEY = "idem-key";

  // ---------- getPaymentById ----------

  @Test
  void getPaymentById_whenExists_returnsPayment() {
    UUID id = UUID.randomUUID();
    PostPaymentResponse stored = new PostPaymentResponse();
    stored.setId(id);
    when(paymentsRepository.get(id)).thenReturn(Optional.of(stored));

    PostPaymentResponse result = service.getPaymentById(id);

    assertThat(result).isSameAs(stored);
    assertThat(result.getId()).isEqualTo(id);
  }

  @Test
  void getPaymentById_whenMissing_throwsEventProcessingException() {
    UUID id = UUID.randomUUID();
    when(paymentsRepository.get(id)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.getPaymentById(id))
        .isInstanceOf(EventProcessingException.class);
  }

  // ---------- processPayment: idempotency-key guard ----------

  @Test
  void processPayment_whenIdempotencyKeyNull_throwsInvalidRequest() {
    assertThatThrownBy(() -> service.processPayment(validRequest(), null))
        .isInstanceOf(InvalidRequestException.class)
        .hasMessageContaining("Idempotency-Key");
  }

  @Test
  void processPayment_whenIdempotencyKeyBlank_throwsInvalidRequest() {
    assertThatThrownBy(() -> service.processPayment(validRequest(), "   "))
        .isInstanceOf(InvalidRequestException.class);
  }

  // ---------- processPayment: validation rejections ----------

  @Test
  void processPayment_whenCardNumberNull_returnsRejected() {
    PostPaymentRequest req = validRequest();
    req.setCardNumber(null);

    PostPaymentResponse result = service.processPayment(req, KEY);

    assertThat(result.getStatus()).isEqualTo(PaymentStatus.REJECTED);
    verify(bankClient, never()).processPayment(any());
  }

  @Test
  void processPayment_whenCardNumberTooShort_returnsRejected() {
    PostPaymentRequest req = validRequest();
    req.setCardNumber("123");

    PostPaymentResponse result = service.processPayment(req, KEY);

    assertThat(result.getStatus()).isEqualTo(PaymentStatus.REJECTED);
  }

  @Test
  void processPayment_whenCardNumberContainsLetters_returnsRejected() {
    PostPaymentRequest req = validRequest();
    req.setCardNumber("2222abcd00008877");

    PostPaymentResponse result = service.processPayment(req, KEY);

    assertThat(result.getStatus()).isEqualTo(PaymentStatus.REJECTED);
  }

  @Test
  void processPayment_whenExpiryMonthTooLarge_returnsRejected() {
    PostPaymentRequest req = validRequest();
    req.setExpiryMonth(13);

    assertThat(service.processPayment(req, KEY).getStatus())
        .isEqualTo(PaymentStatus.REJECTED);
  }

  @Test
  void processPayment_whenExpiryMonthZero_returnsRejected() {
    PostPaymentRequest req = validRequest();
    req.setExpiryMonth(0);

    assertThat(service.processPayment(req, KEY).getStatus())
        .isEqualTo(PaymentStatus.REJECTED);
  }

  @Test
  void processPayment_whenExpiryInPast_returnsRejected() {
    PostPaymentRequest req = validRequest();
    req.setExpiryMonth(1);
    req.setExpiryYear(2020);

    assertThat(service.processPayment(req, KEY).getStatus())
        .isEqualTo(PaymentStatus.REJECTED);
  }

  @Test
  void processPayment_whenCurrencyUnsupported_returnsRejected() {
    PostPaymentRequest req = validRequest();
    req.setCurrency("XYZ");

    assertThat(service.processPayment(req, KEY).getStatus())
        .isEqualTo(PaymentStatus.REJECTED);
  }

  @Test
  void processPayment_whenAmountZero_returnsRejected() {
    PostPaymentRequest req = validRequest();
    req.setAmount(0);

    assertThat(service.processPayment(req, KEY).getStatus())
        .isEqualTo(PaymentStatus.REJECTED);
  }

  @Test
  void processPayment_whenAmountNegative_returnsRejected() {
    PostPaymentRequest req = validRequest();
    req.setAmount(-1);

    assertThat(service.processPayment(req, KEY).getStatus())
        .isEqualTo(PaymentStatus.REJECTED);
  }

  @Test
  void processPayment_whenCvvInvalid_returnsRejected() {
    PostPaymentRequest req = validRequest();
    req.setCvv("ab");

    assertThat(service.processPayment(req, KEY).getStatus())
        .isEqualTo(PaymentStatus.REJECTED);
  }

  @Test
  void processPayment_whenCvvTooLong_returnsRejected() {
    PostPaymentRequest req = validRequest();
    req.setCvv("12345");

    assertThat(service.processPayment(req, KEY).getStatus())
        .isEqualTo(PaymentStatus.REJECTED);
  }

  // ---------- processPayment: bank flows ----------

  @Test
  void processPayment_bankAuthorizes_returnsAuthorized() {
    BankPaymentResponse bankResponse = new BankPaymentResponse();
    bankResponse.setAuthorized(true);
    bankResponse.setAuthorizationCode("auth-code");
    when(bankClient.processPayment(any(BankPaymentRequest.class))).thenReturn(bankResponse);

    PostPaymentResponse result = service.processPayment(validRequest(), KEY);

    assertThat(result.getStatus()).isEqualTo(PaymentStatus.AUTHORIZED);
    assertThat(result.getId()).isNotNull();
    verify(bankClient, times(1)).processPayment(any(BankPaymentRequest.class));
  }

  @Test
  void processPayment_bankDeclines_returnsDeclined() {
    BankPaymentResponse bankResponse = new BankPaymentResponse();
    bankResponse.setAuthorized(false);
    when(bankClient.processPayment(any(BankPaymentRequest.class))).thenReturn(bankResponse);

    PostPaymentResponse result = service.processPayment(validRequest(), KEY);

    assertThat(result.getStatus()).isEqualTo(PaymentStatus.DECLINED);
  }

  @Test
  void processPayment_bankUnavailable_returnsPending() {
    when(bankClient.processPayment(any(BankPaymentRequest.class)))
        .thenThrow(new BankUnavailableException("Bank unavailable"));

    PostPaymentResponse result = service.processPayment(validRequest(), KEY);

    assertThat(result.getStatus()).isEqualTo(PaymentStatus.PENDING);
  }

  @Test
  void processPayment_bankOtherException_returnsPending() {
    when(bankClient.processPayment(any(BankPaymentRequest.class)))
        .thenThrow(new RuntimeException("network failure"));

    PostPaymentResponse result = service.processPayment(validRequest(), KEY);

    assertThat(result.getStatus()).isEqualTo(PaymentStatus.PENDING);
  }

  // ---------- processPayment: replay path ----------

  @Test
  void processPayment_replayHit_returnsCachedAndSkipsBank() {
    UUID existingId = UUID.randomUUID();
    PostPaymentResponse cached = new PostPaymentResponse();
    cached.setId(existingId);
    cached.setStatus(PaymentStatus.AUTHORIZED);
    cached.setRequestHash("b1e4662d7a923d0c47fa2c6014415101a67391126d5929a5fae917342a068d84");
    when(paymentsRepository.get(KEY)).thenReturn(cached);

    PostPaymentResponse result = service.processPayment(validRequest(), KEY);

    assertThat(result).isSameAs(cached);
    assertThat(result.getId()).isEqualTo(existingId);
    verify(bankClient, never()).processPayment(any(BankPaymentRequest.class));
    verify(paymentsRepository, never()).save(eq(KEY), any());
  }

  // ---------- helpers ----------

  private PostPaymentRequest validRequest() {
    PostPaymentRequest req = new PostPaymentRequest();
    req.setCardNumber(VALID_CARD);
    req.setExpiryMonth(4);
    req.setExpiryYear(2030);
    req.setCurrency("GBP");
    req.setAmount(100);
    req.setCvv("123");
    return req;
  }
}
