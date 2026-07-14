package com.checkout.payment.gateway.controller;


import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.checkout.payment.gateway.client.BankClient;
import com.checkout.payment.gateway.enums.PaymentStatus;
import com.checkout.payment.gateway.exception.BankUnavailableException;
import com.checkout.payment.gateway.model.BankPaymentRequest;
import com.checkout.payment.gateway.model.BankPaymentResponse;
import com.checkout.payment.gateway.model.PostPaymentResponse;
import com.checkout.payment.gateway.repository.PaymentsRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

@SpringBootTest
@AutoConfigureMockMvc
class PaymentGatewayControllerTest {

  @Autowired
  private MockMvc mvc;
  @Autowired
  PaymentsRepository paymentsRepository;
  @MockBean
  private BankClient bankClient;

  @Test
  void whenPaymentWithIdExistThenCorrectPaymentIsReturned() throws Exception {
    PostPaymentResponse payment = new PostPaymentResponse();
    payment.setId(UUID.randomUUID());
    payment.setAmount(10);
    payment.setCurrency("USD");
    payment.setStatus(PaymentStatus.AUTHORIZED);
    payment.setExpiryMonth(12);
    payment.setExpiryYear(2024);
    payment.setCardNumberLastFour(4321);

    paymentsRepository.save("test-key-" + payment.getId(), payment);

    mvc.perform(MockMvcRequestBuilders.get("/payment/" + payment.getId()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value(payment.getStatus().getName()))
        .andExpect(jsonPath("$.cardNumberLastFour").value(payment.getCardNumberLastFour()))
        .andExpect(jsonPath("$.expiryMonth").value(payment.getExpiryMonth()))
        .andExpect(jsonPath("$.expiryYear").value(payment.getExpiryYear()))
        .andExpect(jsonPath("$.currency").value(payment.getCurrency()))
        .andExpect(jsonPath("$.amount").value(payment.getAmount()));
  }

  @Test
  void whenPaymentWithIdDoesNotExistThen404IsReturned() throws Exception {
    mvc.perform(MockMvcRequestBuilders.get("/payment/" + UUID.randomUUID()))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.message").value("Page not found"));
  }

  @Test
  void whenValidPaymentAndBankAuthorizesThen201WithAuthorizedStatus() throws Exception {
    BankPaymentResponse bankResponse = new BankPaymentResponse();
    bankResponse.setAuthorized(true);
    bankResponse.setAuthorizationCode("code-123");
    when(bankClient.processPayment(any(BankPaymentRequest.class))).thenReturn(bankResponse);

    String body = "{\"card_number\":\"2222405343248877\","
        + "\"expiry_month\":4,\"expiry_year\":2027,"
        + "\"currency\":\"GBP\",\"amount\":100,\"cvv\":\"123\"}";

    mvc.perform(MockMvcRequestBuilders.post("/payment")
            .contentType(MediaType.APPLICATION_JSON)
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .content(body))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.status").value("Authorized"))
        .andExpect(jsonPath("$.cardNumberLastFour").value(8877))
        .andExpect(jsonPath("$.currency").value("GBP"))
        .andExpect(jsonPath("$.amount").value(100));
  }

  @Test
  void whenBankDeclinesThen201WithDeclinedStatus() throws Exception {
    BankPaymentResponse bankResponse = new BankPaymentResponse();
    bankResponse.setAuthorized(false);
    when(bankClient.processPayment(any(BankPaymentRequest.class))).thenReturn(bankResponse);

    String body = "{\"card_number\":\"2222405343248874\","
        + "\"expiry_month\":4,\"expiry_year\":2027,"
        + "\"currency\":\"GBP\",\"amount\":100,\"cvv\":\"123\"}";

    mvc.perform(MockMvcRequestBuilders.post("/payment")
            .contentType(MediaType.APPLICATION_JSON)
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .content(body))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.status").value("Declined"));
  }

  @Test
  void whenCardNumberTooShortThen201WithRejectedStatus() throws Exception {
    String body = "{\"card_number\":\"123\","
        + "\"expiry_month\":4,\"expiry_year\":2027,"
        + "\"currency\":\"GBP\",\"amount\":100,\"cvv\":\"123\"}";

    mvc.perform(MockMvcRequestBuilders.post("/payment")
            .contentType(MediaType.APPLICATION_JSON)
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .content(body))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.status").value("Rejected"));
  }

  @Test
  void whenRequestBodyMalformedThen400() throws Exception {
    mvc.perform(MockMvcRequestBuilders.post("/payment")
            .contentType(MediaType.APPLICATION_JSON)
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .content("{not json"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value("Malformed request body"));
  }

  @Test
  void whenIdempotencyKeyReplayedWithDifferentBodyThen400() throws Exception {
    String body1 = "{\"card_number\":\"2222405343248877\","
        + "\"expiry_month\":4,\"expiry_year\":2027,"
        + "\"currency\":\"GBP\",\"amount\":100,\"cvv\":\"123\"}";
    String body2 = "{\"card_number\":\"2222405343248877\","
        + "\"expiry_month\":4,\"expiry_year\":2027,"
        + "\"currency\":\"GBP\",\"amount\":999,\"cvv\":\"123\"}";

    BankPaymentResponse bankResponse = new BankPaymentResponse();
    bankResponse.setAuthorized(true);
    when(bankClient.processPayment(any(BankPaymentRequest.class))).thenReturn(bankResponse);

    String idemKey = "hash-mismatch-key";

    mvc.perform(MockMvcRequestBuilders.post("/payment")
            .contentType(MediaType.APPLICATION_JSON)
            .header("Idempotency-Key", idemKey)
            .content(body1))
        .andExpect(status().isCreated());

    mvc.perform(MockMvcRequestBuilders.post("/payment")
            .contentType(MediaType.APPLICATION_JSON)
            .header("Idempotency-Key", idemKey)
            .content(body2))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value(containsString("IdempotencyKey")));

    verify(bankClient, times(1)).processPayment(any(BankPaymentRequest.class));
  }

  @Test
  void whenSameIdempotencyKeyReplayedThenBankCalledOnceAndSameId() throws Exception {
    BankPaymentResponse bankResponse = new BankPaymentResponse();
    bankResponse.setAuthorized(true);
    bankResponse.setAuthorizationCode("code-123");
    when(bankClient.processPayment(any(BankPaymentRequest.class))).thenReturn(bankResponse);

    String body = "{\"card_number\":\"2222405343248877\","
        + "\"expiry_month\":4,\"expiry_year\":2027,"
        + "\"currency\":\"GBP\",\"amount\":100,\"cvv\":\"123\"}";
    String idempotencyKey = "replay-key-123";

    String firstBody = mvc.perform(MockMvcRequestBuilders.post("/payment")
            .contentType(MediaType.APPLICATION_JSON)
            .header("Idempotency-Key", idempotencyKey)
            .content(body))
        .andExpect(status().isCreated())
        .andReturn().getResponse().getContentAsString();

    String secondBody = mvc.perform(MockMvcRequestBuilders.post("/payment")
            .contentType(MediaType.APPLICATION_JSON)
            .header("Idempotency-Key", idempotencyKey)
            .content(body))
        .andExpect(status().isCreated())
        .andReturn().getResponse().getContentAsString();

    ObjectMapper mapper = new ObjectMapper();
    String firstId = mapper.readTree(firstBody).get("id").asText();
    String secondId = mapper.readTree(secondBody).get("id").asText();
    assertEquals(firstId, secondId);
    verify(bankClient, times(1)).processPayment(any(BankPaymentRequest.class));
  }

  @Test
  void whenIdempotencyKeyMissingThen400() throws Exception {
    String body = "{\"card_number\":\"2222405343248877\","
        + "\"expiry_month\":4,\"expiry_year\":2027,"
        + "\"currency\":\"GBP\",\"amount\":100,\"cvv\":\"123\"}";

    mvc.perform(MockMvcRequestBuilders.post("/payment")
            .contentType(MediaType.APPLICATION_JSON)
            .content(body))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.message").value(containsString("Idempotency-Key")));
  }

  @Test
  void whenBankUnavailableThen201WithPendingStatus() throws Exception {
    when(bankClient.processPayment(any(BankPaymentRequest.class)))
        .thenThrow(new BankUnavailableException("Bank unavailable"));

    String body = "{\"card_number\":\"2222405343248877\","
        + "\"expiry_month\":4,\"expiry_year\":2027,"
        + "\"currency\":\"GBP\",\"amount\":100,\"cvv\":\"123\"}";

    mvc.perform(MockMvcRequestBuilders.post("/payment")
            .contentType(MediaType.APPLICATION_JSON)
            .header("Idempotency-Key", UUID.randomUUID().toString())
            .content(body))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.status").value("Pending"));
  }

  @Test
  void whenBankUnavailableThenReplayReturnsSamePendingAndBankNotCalledAgain() throws Exception {
    when(bankClient.processPayment(any(BankPaymentRequest.class)))
        .thenThrow(new BankUnavailableException("Bank unavailable"));

    String body = "{\"card_number\":\"2222405343248877\","
        + "\"expiry_month\":4,\"expiry_year\":2027,"
        + "\"currency\":\"GBP\",\"amount\":100,\"cvv\":\"123\"}";
    String idempotencyKey = "pending-replay-key";

    String firstBody = mvc.perform(MockMvcRequestBuilders.post("/payment")
            .contentType(MediaType.APPLICATION_JSON)
            .header("Idempotency-Key", idempotencyKey)
            .content(body))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.status").value("Pending"))
        .andReturn().getResponse().getContentAsString();

    String secondBody = mvc.perform(MockMvcRequestBuilders.post("/payment")
            .contentType(MediaType.APPLICATION_JSON)
            .header("Idempotency-Key", idempotencyKey)
            .content(body))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.message").value(containsString("in progress")))
        .andReturn().getResponse().getContentAsString();

    verify(bankClient, times(1)).processPayment(any(BankPaymentRequest.class));
  }
}
