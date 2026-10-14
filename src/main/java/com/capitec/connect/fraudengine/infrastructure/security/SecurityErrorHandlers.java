package com.capitec.connect.fraudengine.infrastructure.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class SecurityErrorHandlers implements AuthenticationEntryPoint, AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    @Override
    public void commence(
            HttpServletRequest request,
            HttpServletResponse response,
            org.springframework.security.core.AuthenticationException exception
    ) throws IOException, ServletException {
        response.setHeader(HttpHeaders.WWW_AUTHENTICATE, "Bearer");
        writeProblem(response, HttpServletResponse.SC_UNAUTHORIZED, "Unauthorized",
            "Authentication is required to access this resource.", request.getRequestURI());
    }

    @Override
    public void handle(
            HttpServletRequest request,
            HttpServletResponse response,
            AccessDeniedException exception
    ) throws IOException, ServletException {
        writeProblem(response, HttpServletResponse.SC_FORBIDDEN, "Forbidden",
            "You do not have permission to access this resource.", request.getRequestURI());
    }

    private void writeProblem(
            HttpServletResponse response,
            int status,
            String title,
            String detail,
            String instance
    ) throws IOException {
        Map<String, Object> problem = new LinkedHashMap<>();
        problem.put("type", "about:blank");
        problem.put("title", title);
        problem.put("status", status);
        problem.put("detail", detail);
        problem.put("instance", instance);

        response.setStatus(status);
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding("UTF-8");
        objectMapper.writeValue(response.getOutputStream(), problem);
    }
}
