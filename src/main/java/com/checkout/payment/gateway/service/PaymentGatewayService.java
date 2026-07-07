package com.checkout.payment.gateway.service;

import com.checkout.payment.gateway.client.BankClient;
import com.checkout.payment.gateway.enums.PaymentStatus;
import com.checkout.payment.gateway.exception.EventProcessingException;
import com.checkout.payment.gateway.exception.InvalidRequestException;
import com.checkout.payment.gateway.model.BankPaymentRequest;
import com.checkout.payment.gateway.model.BankPaymentResponse;
import com.checkout.payment.gateway.model.PostPaymentRequest;
import com.checkout.payment.gateway.model.PostPaymentResponse;
import com.checkout.payment.gateway.repository.PaymentsRepository;
import java.time.YearMonth;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class PaymentGatewayService {

  private static final Logger LOG = LoggerFactory.getLogger(PaymentGatewayService.class);

  private static final Set<String> SUPPORTED_CURRENCIES = Set.of("USD", "GBP", "EUR");

  private final PaymentsRepository paymentsRepository;
  private final BankClient bankClient;
  private final ConcurrentHashMap<String, PostPaymentResponse> idempotencyCache = new ConcurrentHashMap<>();

  public PaymentGatewayService(PaymentsRepository paymentsRepository, BankClient bankClient) {
    this.paymentsRepository = paymentsRepository;
    this.bankClient = bankClient;
  }

  public PostPaymentResponse getPaymentById(UUID id) {
    LOG.debug("Requesting access to to payment with ID {}", id);
    return paymentsRepository.get(id).orElseThrow(() -> new EventProcessingException("Invalid ID"));
  }

  public PostPaymentResponse processPayment(PostPaymentRequest request, String idempotencyKey) {
    if (idempotencyKey == null || idempotencyKey.isBlank()) {
      throw new InvalidRequestException("Idempotency-Key header is required");
    }

    PostPaymentResponse cached = idempotencyCache.get(idempotencyKey);
    if (cached != null) {
      return cached;
    }

    UUID paymentId = UUID.randomUUID();

    try {
      validate(request);
    } catch (InvalidRequestException e) {
      PostPaymentResponse rejected = buildResponse(request, paymentId, PaymentStatus.REJECTED);
      paymentsRepository.store(rejected);
      idempotencyCache.put(idempotencyKey, rejected);
      return rejected;
    }

    PostPaymentResponse response = buildResponse(request, paymentId, PaymentStatus.PENDING);
    paymentsRepository.store(response);

    BankPaymentRequest bankRequest = toBankRequest(request);
    BankPaymentResponse bankResponse = bankClient.processPayment(bankRequest);
    if (bankResponse == null) {
      LOG.warn("Bank unavailable for payment {}; PENDING...", paymentId);
      idempotencyCache.put(idempotencyKey, response);
      return response;
    }
    response.setStatus(bankResponse.isAuthorized()
        ? PaymentStatus.AUTHORIZED : PaymentStatus.DECLINED);
    paymentsRepository.store(response);

    idempotencyCache.put(idempotencyKey, response);
    return response;
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
    return response;
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
