package com.checkout.payment.gateway.client;

import com.checkout.payment.gateway.exception.BankUnavailableException;
import com.checkout.payment.gateway.model.BankPaymentRequest;
import com.checkout.payment.gateway.model.BankPaymentResponse;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import io.github.resilience4j.ratelimiter.annotation.RateLimiter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

@Component
public class BankClient {

  private static final Logger LOG = LoggerFactory.getLogger(BankClient.class);

  private final RestTemplate restTemplate;
  private final String bankBaseUrl;

  public BankClient(RestTemplate restTemplate,
      @Value("${bank.simulator.url:http://localhost:8080}")
      String bankBaseUrl) {
    this.restTemplate = restTemplate;
    this.bankBaseUrl = bankBaseUrl;
  }

  @CircuitBreaker(name = "bank", fallbackMethod = "fallback")
  @RateLimiter(name = "bank", fallbackMethod = "fallback")
  public BankPaymentResponse processPayment(BankPaymentRequest request) {
    HttpHeaders headers = new HttpHeaders();
    headers.setContentType(MediaType.APPLICATION_JSON);
    //todo: add credential headers if bank needed for prod
    HttpEntity<BankPaymentRequest> entity = new HttpEntity<>(request, headers);
    return restTemplate.postForObject(bankBaseUrl + "/payments", entity,
        BankPaymentResponse.class);
  }

  private BankPaymentResponse fallback(BankPaymentRequest request, Throwable t) {
    LOG.error("Fallback, Bank unavailable!");
    throw new BankUnavailableException("Fallback, Bank unavailable!");
  }
}
