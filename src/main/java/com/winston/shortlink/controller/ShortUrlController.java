package com.winston.shortlink.controller;

import com.winston.shortlink.dto.ApiResponse;
import com.winston.shortlink.dto.CreateShortUrlRequest;
import com.winston.shortlink.dto.CreateShortUrlResponse;
import com.winston.shortlink.service.ShortUrlService;
import jakarta.validation.Valid;
import lombok.AllArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

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
}
