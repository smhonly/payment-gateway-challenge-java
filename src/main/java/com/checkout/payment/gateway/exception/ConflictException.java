package com.checkout.payment.gateway.exception;

public class ConflictException extends RuntimeException {

  public ConflictException(String message) {
    super(message);
  }
}
