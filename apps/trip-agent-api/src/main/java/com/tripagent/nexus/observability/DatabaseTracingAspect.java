package com.tripagent.nexus.observability;

import java.util.Map;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

@Aspect
@Component
public class DatabaseTracingAspect {

  private final TraceOperations traces;

  public DatabaseTracingAspect(TraceOperations traces) {
    this.traces = traces;
  }

  @Around("execution(* com.tripagent.nexus.infrastructure.persistence.repository..*(..))")
  public Object traceRepositoryCall(ProceedingJoinPoint joinPoint) {
    String repository = joinPoint.getSignature().getDeclaringType().getSimpleName();
    String operation = joinPoint.getSignature().getName();
    return traces.trace(
        "db.repository." + operation,
        "tripagent.db.operation",
        Map.of(
            "db.system", "postgresql",
            "db.repository", repository,
            "db.operation.name", operation),
        () -> proceed(joinPoint));
  }

  private static Object proceed(ProceedingJoinPoint joinPoint) {
    try {
      return joinPoint.proceed();
    } catch (RuntimeException exception) {
      throw exception;
    } catch (Throwable throwable) {
      throw new IllegalStateException("Repository invocation failed.", throwable);
    }
  }
}
