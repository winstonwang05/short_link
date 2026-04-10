package com.winston.shortlink.controller;


import com.alibaba.csp.sentinel.annotation.SentinelResource;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.winston.shortlink.config.MonitoringConfig;
import com.winston.shortlink.service.ShortUrlService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.AllArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;

import static org.bouncycastle.asn1.x500.style.RFC4519Style.cn;

/**
 * 301 重定向
 */
@AllArgsConstructor
@RestController
public class RedirectController {

    private static final Logger logger = LoggerFactory.getLogger(RedirectController.class);
    private final ShortUrlService shortUrlService;
    private final MonitoringConfig monitoringConfig;

    /**
     * 短链跳转
     */
    @GetMapping("/{shortCode}")
    @SentinelResource(
            value = "redirectShortUrl",
            blockHandler = "redirectBlockHandler",
            fallback = "redirectFallback"
    )
    public void redirect(@PathVariable("shortCode") String shortCode,
                         HttpServletResponse response) throws IOException {

        try {
            // 获取短链信息
            var shortUrlInfo = shortUrlService.getShortUrlInfo(shortCode);

            if (shortUrlInfo == null) {
                response.setStatus(HttpStatus.NOT_FOUND.value());
                response.getWriter().write("短链不存在或已过期");
                return;
            }

            // 先执行重定向，不让计数逻辑阻塞用户响应
            response.setStatus(HttpStatus.MOVED_PERMANENTLY.value());
            response.setHeader("Location", shortUrlInfo.getOriginUrl());
            response.setHeader("Cache-Control", "public, max-age=3600");

            // 真正的异步更新访问次数，不阻塞跳转主流程
            CompletableFuture.runAsync(() -> {
                try {
                    shortUrlService.updateAccessCountAsync(shortCode);
                    monitoringConfig.getAccessCounter().increment();
                } catch (Exception e) {
                    logger.warn("异步更新访问次数失败: {}", e.getMessage());
                }
            });

        } catch (Exception e) {
            logger.error("短链跳转异常: {}", e.getMessage(), e);
            response.setStatus(HttpStatus.INTERNAL_SERVER_ERROR.value());
            response.getWriter().write("系统内部错误");
        }
    }

    // Sentinel 限流处理
    public void redirectBlockHandler(String shortCode,
                                     HttpServletRequest request,
                                     HttpServletResponse response,
                                     BlockException ex) throws IOException {
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.getWriter().write("访问过于频繁，请稍后重试");
    }

    // Sentinel 降级处理
    public void redirectFallback(String shortCode,
                                 HttpServletRequest request,
                                 HttpServletResponse response,
                                 Throwable ex) throws IOException {
        response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value());
        response.getWriter().write("服务暂时不可用，请稍后重试");
    }
}