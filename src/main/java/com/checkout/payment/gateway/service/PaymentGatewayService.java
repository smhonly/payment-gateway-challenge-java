package com.checkout.payment.gateway.service;

import com.checkout.payment.gateway.client.BankClient;
import com.checkout.payment.gateway.enums.PaymentStatus;
import com.checkout.payment.gateway.exception.BankUnavailableException;
import com.checkout.payment.gateway.exception.ConflictException;
import com.checkout.payment.gateway.exception.EventProcessingException;
import com.checkout.payment.gateway.exception.InvalidRequestException;
import com.checkout.payment.gateway.model.BankPaymentRequest;
import com.checkout.payment.gateway.model.BankPaymentResponse;
import com.checkout.payment.gateway.model.PostPaymentRequest;
import com.checkout.payment.gateway.model.PostPaymentResponse;
import com.checkout.payment.gateway.repository.PaymentsRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.YearMonth;
import java.util.HexFormat;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class PaymentGatewayService {

  private static final Logger LOG = LoggerFactory.getLogger(PaymentGatewayService.class);

  private static final Set<String> SUPPORTED_CURRENCIES = Set.of("USD", "GBP", "EUR");

  private final PaymentsRepository paymentsRepository;
  private final BankClient bankClient;
  private final MeterRegistry meterRegistry;

  public PaymentGatewayService(PaymentsRepository paymentsRepository,
      BankClient bankClient, MeterRegistry meterRegistry) {
    this.paymentsRepository = paymentsRepository;
    this.bankClient = bankClient;
    this.meterRegistry = meterRegistry;
  }

  public PostPaymentResponse getPaymentById(UUID id) {
    LOG.debug("Requesting access to to payment with ID {}", id);
    return paymentsRepository.get(id).orElseThrow(() -> new EventProcessingException("Invalid ID"));
  }

  public PostPaymentResponse processPayment(PostPaymentRequest request, String idempotencyKey) {
    if (idempotencyKey == null || idempotencyKey.isBlank()) {
      throw new InvalidRequestException("Idempotency-Key header is required");
    }

    long startMs = System.currentTimeMillis();
    String requestHash = hashRequest(request);

    PostPaymentResponse cached = findCached(idempotencyKey, requestHash, startMs);
    if (cached != null) {
      return cached;
    }

    UUID paymentId = UUID.randomUUID();
    try {
      validate(request);
    } catch (InvalidRequestException e) {
      PostPaymentResponse rejected = buildResponse(request, paymentId, PaymentStatus.REJECTED);
      recordOutcome(paymentId, idempotencyKey, rejected, startMs);
      return rejected;
    }

    PostPaymentResponse response = buildResponse(request, paymentId, PaymentStatus.PENDING);
    paymentsRepository.save(idempotencyKey, response);
    return invokeBank(request, idempotencyKey, paymentId, response, startMs);
  }

  private PostPaymentResponse findCached(String idempotencyKey, String requestHash, long startMs) {
    PostPaymentResponse cached = paymentsRepository.get(idempotencyKey);
    if (cached == null) {
      return null;
    }
    if (cached.getStatus() == PaymentStatus.PENDING) {
      throw new ConflictException("Request already in progress");
    }
    if (!requestHash.equals(cached.getRequestHash())) {
      throw new InvalidRequestException("IdempotencyKey with a invalid request body");
    }
    LOG.info("idempotency_hit id={} idem={} status={} elapsed_ms={}",
        cached.getId(), idempotencyKey, cached.getStatus(),
        System.currentTimeMillis() - startMs);
    meterRegistry.counter("payments.idempotency.hits",
        "status", cached.getStatus().name()).increment();
    return cached;
  }

  private PostPaymentResponse invokeBank(PostPaymentRequest request, String idempotencyKey,
      UUID paymentId, PostPaymentResponse response, long startMs) {
    BankPaymentRequest bankRequest = toBankRequest(request);
    long bankStartMs = System.currentTimeMillis();
    Timer.Sample bankTimer = Timer.start(meterRegistry);
    BankPaymentResponse bankResponse;
    try {
      bankResponse = bankClient.processPayment(bankRequest);
    } catch (BankUnavailableException e) {
      return recordBankFailure(paymentId, idempotencyKey, response, bankTimer, bankStartMs,
          startMs, "UNAVAILABLE");
    } catch (Exception e) {
      return recordBankFailure(paymentId, idempotencyKey, response, bankTimer, bankStartMs,
          startMs, "ERROR", e);
    }
    long bankMs = System.currentTimeMillis() - bankStartMs;
    String bankStatus = bankResponse.isAuthorized() ? "AUTHORIZED" : "DECLINED";
    bankTimer.stop(Timer.builder("payments.bank.duration")
        .tag("status", bankStatus).register(meterRegistry));
    LOG.info("bank_response id={} idem={} bank_ms={} status={}",
        paymentId, idempotencyKey, bankMs, bankStatus);
    response.setStatus(bankResponse.isAuthorized()
        ? PaymentStatus.AUTHORIZED : PaymentStatus.DECLINED);
    paymentsRepository.save(idempotencyKey, response);
    recordOutcome(paymentId, idempotencyKey, response, startMs);
    return response;
  }

  private PostPaymentResponse recordBankFailure(UUID paymentId, String idempotencyKey,
      PostPaymentResponse response, Timer.Sample bankTimer, long bankStartMs, long startMs,
      String status, Exception... cause) {
    long bankMs = System.currentTimeMillis() - bankStartMs;
    bankTimer.stop(Timer.builder("payments.bank.duration")
        .tag("status", status).register(meterRegistry));
    LOG.error("bank_response id={} idem={} bank_ms={} status={}",
        paymentId, idempotencyKey, bankMs, status);
    if (cause.length > 0) {
      LOG.error("Bank call error for payment {}.", paymentId, cause[0]);
    } else {
      LOG.error("Bank unavailable for payment {}; PENDING...", paymentId);
    }
    recordOutcome(paymentId, idempotencyKey, response, startMs);
    return response;
  }

  private void recordOutcome(UUID paymentId, String idemKey,
      PostPaymentResponse response, long startMs) {
    long elapsedMs = System.currentTimeMillis() - startMs;
    LOG.info("payment_outcome id={} idem={} status={} amount={} currency={} elapsed_ms={}",
        paymentId, idemKey, response.getStatus(),
        response.getAmount(), response.getCurrency(), elapsedMs);

    meterRegistry.counter("payments.processed.total",
        "status", response.getStatus().name(),
        "currency", response.getCurrency()).increment();

    Timer.builder("payments.processing.duration")
        .tag("status", response.getStatus().name())
        .tag("currency", response.getCurrency())
        .register(meterRegistry)
        .record(elapsedMs, TimeUnit.MILLISECONDS);
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
    //todo: need to add Idempotency-Key for bank request,
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

  int safeLastFour(String cardNumber) {
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
