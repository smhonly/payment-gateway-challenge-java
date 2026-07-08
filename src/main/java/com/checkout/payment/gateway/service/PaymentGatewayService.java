package com.checkout.payment.gateway.service;

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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.YearMonth;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class PaymentGatewayService {

  private static final Logger LOG = LoggerFactory.getLogger(PaymentGatewayService.class);

  private static final Set<String> SUPPORTED_CURRENCIES = Set.of("USD", "GBP", "EUR");

  private final PaymentsRepository paymentsRepository;
  private final BankClient bankClient;

  public PaymentGatewayService(PaymentsRepository paymentsRepository,
      BankClient bankClient) {
    this.paymentsRepository = paymentsRepository;
    this.bankClient = bankClient;
  }

  public PostPaymentResponse getPaymentById(UUID id) {
    LOG.debug("Requesting access to to payment with ID {}", id);
    return paymentsRepository.get(id).orElseThrow(() -> new EventProcessingException("Invalid ID"));
  }

  public PostPaymentResponse processPayment(PostPaymentRequest request,
      String idempotencyKey) {
    if (idempotencyKey == null || idempotencyKey.isBlank()) {
      throw new InvalidRequestException("Idempotency-Key header is required");
    }

    long startMs = System.currentTimeMillis();
    String requestHash = hashRequest(request);

    PostPaymentResponse cached = paymentsRepository.get(idempotencyKey);
    if (cached != null) {
      if (!requestHash.equals(cached.getRequestHash())) {
        throw new InvalidRequestException("IdempotencyKey with a invalid request body");
      }
      long elapsedMs = System.currentTimeMillis() - startMs;
      LOG.info("idempotency_hit id={} idem={} status={} elapsed_ms={}",
          cached.getId(), idempotencyKey, cached.getStatus(), elapsedMs);
      return cached;
    }

    UUID paymentId = UUID.randomUUID();
    PostPaymentResponse response;

    try {
      validate(request);
    } catch (InvalidRequestException e) {
      PostPaymentResponse rejected = buildResponse(request, paymentId, PaymentStatus.REJECTED);
      paymentsRepository.save(idempotencyKey, rejected);
      logOutcome(paymentId, idempotencyKey, rejected, startMs);
      return rejected;
    }

    response = buildResponse(request, paymentId, PaymentStatus.PENDING);
    paymentsRepository.save(idempotencyKey, response);

    BankPaymentRequest bankRequest = toBankRequest(request);
    long bankStartMs = System.currentTimeMillis();
    BankPaymentResponse bankResponse;
    try {
      bankResponse = bankClient.processPayment(bankRequest);
    } catch (BankUnavailableException e) {
      long bankMs = System.currentTimeMillis() - bankStartMs;
      LOG.error("bank_response id={} idem={} bank_ms={} status=UNAVAILABLE",
          paymentId, idempotencyKey, bankMs);
      LOG.error("Bank unavailable for payment {}; PENDING...", paymentId);
      logOutcome(paymentId, idempotencyKey, response, startMs);
      return response;
    } catch (Exception e) {
      long bankMs = System.currentTimeMillis() - bankStartMs;
      LOG.error("bank_response id={} idem={} bank_ms={} status=ERROR",
          paymentId, idempotencyKey, bankMs);
      LOG.error("Bank call error for payment {}.", paymentId, e);
      logOutcome(paymentId, idempotencyKey, response, startMs);
      return response;
    }

    long bankMs = System.currentTimeMillis() - bankStartMs;
    LOG.info("bank_response id={} idem={} bank_ms={} status={}",
        paymentId, idempotencyKey, bankMs,
        bankResponse.isAuthorized() ? "AUTHORIZED" : "DECLINED");

    response.setStatus(bankResponse.isAuthorized()
        ? PaymentStatus.AUTHORIZED : PaymentStatus.DECLINED);
    paymentsRepository.save(idempotencyKey, response);
    logOutcome(paymentId, idempotencyKey, response, startMs);
    return response;
  }

  private void logOutcome(UUID paymentId, String idemKey,
      PostPaymentResponse response, long startMs) {
    long elapsedMs = System.currentTimeMillis() - startMs;
    LOG.info("payment_outcome id={} idem={} status={} amount={} currency={} elapsed_ms={}",
        paymentId, idemKey, response.getStatus(),
        response.getAmount(), response.getCurrency(), elapsedMs);
  }

  private void validate(PostPaymentRequest request) {
    String cardNumber = request.getCardNumber();
    if (cardNumber == null || !cardNumber.matches("\\d{14,19}")) {
      throw new InvalidRequestException("card_number must be 14-19 numeric characters");
    }
    if (request.getExpiryMonth() < 1 || request.getExpiryMonth() > 12) {
      throw new InvalidRequestException("expiry_month must be between 1 and 12");
    }
    if (YearMonth.of(request.getExpiryYear(), request.getExpiryMonth()).isBefore(YearMonth.now())) {
      throw new InvalidRequestException("expiry_date must be in the future");
    }
    String currency = request.getCurrency();
    if (currency == null || !SUPPORTED_CURRENCIES.contains(currency)) {
      throw new InvalidRequestException("currency must be one of " + SUPPORTED_CURRENCIES);
    }
    if (request.getAmount() <= 0) {
      throw new InvalidRequestException("amount must be a positive integer");
    }
    String cvv = request.getCvv();
    if (cvv == null || !cvv.matches("\\d{3,4}")) {
      throw new InvalidRequestException("cvv must be 3-4 numeric characters");
    }
  }

  private BankPaymentRequest toBankRequest(PostPaymentRequest request) {
    //todo: need to add some Idempotency-Key for bank request,
    //such as merchantRefId, to avoid duplicated payment.
    //But currently simulator bank request is hardcoded here.
    BankPaymentRequest bankRequest = new BankPaymentRequest();
    bankRequest.setCardNumber(request.getCardNumber());
    bankRequest.setExpiryDate(request.getExpiryDate());
    bankRequest.setCurrency(request.getCurrency());
    bankRequest.setAmount(request.getAmount());
    bankRequest.setCvv(request.getCvv());
    return bankRequest;
  }

  private PostPaymentResponse buildResponse(PostPaymentRequest request, UUID paymentId,
      PaymentStatus status) {
    PostPaymentResponse response = new PostPaymentResponse();
    response.setId(paymentId);
    response.setStatus(status);
    response.setCardNumberLastFour(safeLastFour(request.getCardNumber()));
    response.setExpiryMonth(request.getExpiryMonth());
    response.setExpiryYear(request.getExpiryYear());
    response.setCurrency(request.getCurrency());
    response.setAmount(request.getAmount());
    response.setRequestHash(hashRequest(request));
    return response;
  }

  private String hashRequest(PostPaymentRequest request) {
    String canonical = String.join("|",
        request.getCardNumber(),
        String.valueOf(request.getExpiryMonth()),
        String.valueOf(request.getExpiryYear()),
        request.getCurrency(),
        String.valueOf(request.getAmount()),
        request.getCvv());
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      byte[] digest = md.digest(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException e) {
      throw new RuntimeException("SHA-256 not available", e);
    }
  }

  private int safeLastFour(String cardNumber) {
    if (cardNumber == null || cardNumber.length() < 4) {
      return 0;
    }
    try {
      return Integer.parseInt(cardNumber.substring(cardNumber.length() - 4));
    } catch (NumberFormatException e) {
      return 0;
    }
  }
}
