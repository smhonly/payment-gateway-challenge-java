package com.checkout.payment.gateway.configuration;

import jakarta.servlet.http.HttpServletRequest;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

@Aspect
@Component
public class AuditAspect {

  private static final Logger AUDIT_LOG = LoggerFactory.getLogger("AUDIT");

  @Around("execution(* com.checkout.payment.gateway.controller.*.*(..))")
  public Object auditController(ProceedingJoinPoint pjp) throws Throwable {
    long start = System.currentTimeMillis();
    Object result = pjp.proceed();
    long elapsed = System.currentTimeMillis() - start;

    ServletRequestAttributes attrs = (ServletRequestAttributes) RequestContextHolder.getRequestAttributes();
    if (attrs != null) {
      HttpServletRequest request = attrs.getRequest();
      int status = result instanceof ResponseEntity ? ((ResponseEntity<?>) result).getStatusCode().value() : 200;
      AUDIT_LOG.info("request method={} path={} status={} elapsed_ms={}",
          request.getMethod(), request.getRequestURI(), status, elapsed);
    }
    return result;
  }
}
