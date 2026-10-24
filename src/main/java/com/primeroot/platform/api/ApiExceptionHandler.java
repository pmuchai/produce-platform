package com.primeroot.platform.api;

import java.util.Map;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.http.ResponseEntity;

@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {
	@Override
	protected ResponseEntity<Object> handleMethodArgumentNotValid(MethodArgumentNotValidException exception,
			HttpHeaders headers, HttpStatusCode status, WebRequest request) {
		var problem = ProblemDetail.forStatusAndDetail(status, "Request validation failed");
		problem.setProperty("errors", exception.getBindingResult().getFieldErrors().stream()
			.map(error -> Map.of("field", error.getField(),
				"message", error.getDefaultMessage() == null ? "Invalid value" : error.getDefaultMessage()))
			.toList());
		return handleExceptionInternal(exception, problem, headers, status, request);
	}

	@ExceptionHandler(DataIntegrityViolationException.class)
	public ProblemDetail conflict(DataIntegrityViolationException exception) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
			"The operation conflicts with an existing record or a database constraint");
	}

	@ExceptionHandler(ObjectOptimisticLockingFailureException.class)
	public ProblemDetail concurrentUpdate(ObjectOptimisticLockingFailureException exception) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT,
			"The record changed concurrently; reload it and try again");
	}

	@ExceptionHandler(AccessDeniedException.class)
	public ProblemDetail forbidden(AccessDeniedException exception) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, exception.getMessage());
	}

	@ExceptionHandler(AuthenticationException.class)
	public ProblemDetail unauthenticated(AuthenticationException exception) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.UNAUTHORIZED, "Authentication is required");
	}
}
