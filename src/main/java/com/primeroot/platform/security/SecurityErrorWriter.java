package com.primeroot.platform.security;

import java.io.IOException;
import java.net.URI;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
public class SecurityErrorWriter {
	private final ObjectMapper mapper;

	public SecurityErrorWriter(ObjectMapper mapper) {
		this.mapper = mapper;
	}

	public void write(HttpServletRequest request, HttpServletResponse response,
			HttpStatus status, String detail) throws IOException {
		var problem = ProblemDetail.forStatusAndDetail(status, detail);
		problem.setInstance(URI.create(request.getRequestURI()));
		response.setStatus(status.value());
		response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
		mapper.writeValue(response.getOutputStream(), problem);
	}
}
