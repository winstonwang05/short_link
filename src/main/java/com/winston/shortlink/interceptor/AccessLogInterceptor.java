package com.winston.shortlink.interceptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * @description: 登录拦截器
 * @author: Winston
 * @date: 2026/2/8 22:39
 * @version: 1.0
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AccessLogInterceptor implements HandlerInterceptor {


    private final ObjectMapper objectMapper;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        // 生成请求ID
        String requestId = UUID.randomUUID().toString().replace("-", "");
        MDC.put("requestId", requestId);
        MDC.put("userId", request.getHeader("X-User-Id"));

        // 记录请求开始时间
        request.setAttribute("startTime", System.currentTimeMillis());

        // 记录访问日志
        Map<String, Object> accessLog = new HashMap<>();
        accessLog.put("type", "access");
        accessLog.put("requestId", requestId);
        accessLog.put("method", request.getMethod());
        accessLog.put("uri", request.getRequestURI());
        accessLog.put("queryString", request.getQueryString());
        accessLog.put("userAgent", request.getHeader("User-Agent"));
        accessLog.put("clientIp", getClientIp(request));
        accessLog.put("referer", request.getHeader("Referer"));

        log.info("ACCESS_LOG: {}", objectMapper.writeValueAsString(accessLog));

        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        try {
            Long startTime = (Long) request.getAttribute("startTime");
            long duration = System.currentTimeMillis() - startTime;

            Map<String, Object> responseLog = new HashMap<>();
            responseLog.put("type", "response");
            responseLog.put("requestId", MDC.get("requestId"));
            responseLog.put("status", response.getStatus());
            responseLog.put("duration", duration);
            responseLog.put("success", response.getStatus() < 400);

            if (ex != null) {
                responseLog.put("exception", ex.getClass().getSimpleName());
                responseLog.put("errorMessage", ex.getMessage());
            }

            log.info("RESPONSE_LOG: {}", objectMapper.writeValueAsString(responseLog));
        } finally {
            // 清除用户的一切请求信息，不能存留在Tomcat中
            MDC.clear();
        }
    }

    private String getClientIp(HttpServletRequest request) {
        String xForwardedFor = request.getHeader("X-Forwarded-For");
        if (xForwardedFor != null && !xForwardedFor.isEmpty()) {
            return xForwardedFor.split(",")[0].trim();
        }
        String xRealIp = request.getHeader("X-Real-IP");
        if (xRealIp != null && !xRealIp.isEmpty()) {
            return xRealIp;
        }
        return request.getRemoteAddr();
    }
}
