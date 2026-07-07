package com.checkout.payment.gateway.exception;

import com.checkout.payment.gateway.model.ErrorResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.bind.annotation.ExceptionHandler;

@ControllerAdvice
public class CommonExceptionHandler {

  private static final Logger LOG = LoggerFactory.getLogger(CommonExceptionHandler.class);

  @ExceptionHandler(InvalidRequestException.class)
  public ResponseEntity<ErrorResponse> handleInvalidRequest(InvalidRequestException ex) {
    LOG.warn("Invalid payment request: {}", ex.getMessage());
    return new ResponseEntity<>(new ErrorResponse(ex.getMessage()), HttpStatus.BAD_REQUEST);
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<ErrorResponse> handleMalformedBody(HttpMessageNotReadableException ex) {
    LOG.warn("Malformed request body: {}", ex.getMessage());
    return new ResponseEntity<>(new ErrorResponse("Malformed request body"), HttpStatus.BAD_REQUEST);
  }

  @ExceptionHandler(MissingRequestHeaderException.class)
  public ResponseEntity<ErrorResponse> handleMissingHeader(MissingRequestHeaderException ex) {
    String name = ex.getHeaderName();
    String message = name + " header is required";
    LOG.warn("Missing required header: {}", name);
    return new ResponseEntity<>(new ErrorResponse(message), HttpStatus.BAD_REQUEST);
  }

  @ExceptionHandler(EventProcessingException.class)
  public ResponseEntity<ErrorResponse> handleException(EventProcessingException ex) {
    LOG.error("Exception happened", ex);
    return new ResponseEntity<>(new ErrorResponse("Page not found"), HttpStatus.NOT_FOUND);
  }
}
