package com.winston.shortlink.controller;

import com.winston.shortlink.dto.ApiResponse;
import com.winston.shortlink.dto.CreateShortUrlRequest;
import com.winston.shortlink.dto.CreateShortUrlResponse;
import com.winston.shortlink.service.ShortUrlService;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.Objects;

/**
 * @description: 短链核心控制器
 * @author: Winston
 * @date: 2026/2/2 21:56
 * @version: 1.0
 */
@RestController
@RequestMapping("/shortUrl")
@AllArgsConstructor
public class ShortUrlController {

    private final ShortUrlService shortUrlService;

    @PostMapping("/api/short-url")
    public ApiResponse<CreateShortUrlResponse> createShortUrl(
            @Valid @RequestBody CreateShortUrlRequest createShortUrlRequest
            ) {
        try {
            CreateShortUrlResponse shortUrlResponse = shortUrlService.createShortUrl(createShortUrlRequest);
            return ApiResponse.success("短链创建成功",  shortUrlResponse);
        } catch (RuntimeException e) {
            return ApiResponse.badRequest(e.getMessage());
        } catch (Exception e) {
            return ApiResponse.internalError("系统内部错误");
        }
    }

    /**
     * 短链跳转
     */
    @GetMapping("/{shortCode}")
    public void redirect(@PathVariable("shortCode") String shortCode, HttpServletResponse response)
            throws IOException {
        try {
            CreateShortUrlResponse shortUrlInfo = shortUrlService.getShortUrlInfo(shortCode);

            if (Objects.isNull(shortUrlInfo)) {
                response.sendError(HttpServletResponse.SC_NOT_FOUND, "短链不存在或已过期");
                return;
            }

            // 302重定向，优化缓存策略
            response.setStatus(HttpServletResponse.SC_FOUND);
            response.setHeader("Location", shortUrlInfo.getOriginUrl());
            response.setHeader("Cache-Control", "no-cache, no-store, must-revalidate");
            response.setHeader("Pragma", "no-cache");
            response.setHeader("Expires", "0");
        } catch (Exception e) {
            response.sendError(HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "系统内部错误");
        }
    }

    /**
     * 获取短链信息
     */
    @GetMapping("/api/short-url/{shortCode}")
    public ApiResponse<CreateShortUrlResponse> getShortUrlInfo(@PathVariable("shortCode") String shortCode) {
        try {
            CreateShortUrlResponse response = shortUrlService.getShortUrlInfo(shortCode);
            if (response == null) {
                return ApiResponse.notFound("短链不存在或已过期");
            }
            return ApiResponse.success(response);
        } catch (Exception e) {
            return ApiResponse.internalError("系统内部错误");
        }
    }

    /**
     * 健康检查
     */
    @GetMapping("/api/health")
    public ApiResponse<String> health() {
        return ApiResponse.success("系统运行正常", "OK");
    }
}
