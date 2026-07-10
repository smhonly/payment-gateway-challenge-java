package com.checkout.payment.gateway.client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.checkout.payment.gateway.model.BankPaymentRequest;
import com.checkout.payment.gateway.model.BankPaymentResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpEntity;
import org.springframework.web.client.RestTemplate;

@ExtendWith(MockitoExtension.class)
class BankClientTest {

  @Mock
  RestTemplate restTemplate;

  BankClient bankClient;

  @BeforeEach
  void setUp() {
    bankClient = new BankClient(restTemplate, "http://localhost:8080");
  }

  @Test
  void processPayment_whenBankResponds_returnsResponse() {
    BankPaymentRequest request = new BankPaymentRequest();
    BankPaymentResponse expected = new BankPaymentResponse();
    expected.setAuthorized(true);
    when(restTemplate.postForObject(eq("http://localhost:8080/payments"),
        any(HttpEntity.class), eq(BankPaymentResponse.class)))
        .thenReturn(expected);

    BankPaymentResponse result = bankClient.processPayment(request);

    assertThat(result.isAuthorized()).isTrue();
  }

  @Test
  void processPayment_whenBankFails_throwsBankUnavailableException() {
    BankPaymentRequest request = new BankPaymentRequest();
    when(restTemplate.postForObject(eq("http://localhost:8080/payments"),
        any(HttpEntity.class), eq(BankPaymentResponse.class)))
        .thenThrow(new RuntimeException("Connection refused"));

    assertThatThrownBy(() -> bankClient.processPayment(request))
        .isInstanceOf(RuntimeException.class);
  }
}
