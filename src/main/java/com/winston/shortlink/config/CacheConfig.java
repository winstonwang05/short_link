package com.winston.shortlink.config;

import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.KeyGenerator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * @description: 自定义本地缓存的key
 * @author: Winston
 * @date: 2026/2/9 22:56
 * @version: 1.0
 */
@Configuration
@EnableCaching // 开启本地缓存
public class CacheConfig {

    /**
     * 自定义Key生成器 - 解决Null key问题
     */
    @Bean("safeKeyGenerator")
    public KeyGenerator safeKeyGenerator() {
        return (target, method, params) -> {
            if (params == null || params.length == 0) {
                return "default";
            }

            return Arrays.stream(params)
                    .map(param -> {
                        if (param == null) {
                            return "null";
                        }
                        if (param instanceof String && !StringUtils.hasText((String) param)) {
                            return "empty";
                        }
                        return param.toString();
                    })
                    .collect(Collectors.joining(":"));
        };
    }

    /**
     * 专门用于shortCode的Key生成器
     */
    @Bean("shortCodeKeyGenerator")
    public KeyGenerator shortCodeKeyGenerator() {
        return (target, method, params) -> {
            if (params == null || params.length == 0) {
                throw new IllegalArgumentException("shortCode参数不能为空");
            }

            Object shortCodeParam = params[0];
            if (shortCodeParam == null) {
                throw new IllegalArgumentException("shortCode不能为null");
            }

            String shortCode = shortCodeParam.toString();
            if (!StringUtils.hasText(shortCode)) {
                throw new IllegalArgumentException("shortCode不能为空字符串");
            }

            // 添加前缀避免key冲突
            return "shortUrl:" + shortCode;
        };
    }
}
