package com.winston.shortlink.service;

import com.alibaba.csp.sentinel.annotation.SentinelResource;
import com.winston.shortlink.dto.CreateShortUrlRequest;
import com.winston.shortlink.dto.CreateShortUrlResponse;
import jakarta.transaction.Transactional;

/**
 * @description: 短链url服务
 * @author: Winston
 * @date: 2026/1/28 15:15
 * @version: 1.0
 */
public class ShortUrlService {

    /**
     * 创建短链--支持分库分表
     * @param request 创建短链的参数
     * @return 返回给Controller层的短链信息
     */
    @SentinelResource(
            value = "createShortUrl",
            blockHandler = "createShortUrlBlockHandler",
            fallback = "createShortUrlFallback"
    )
    @Transactional(rollbackFor = Exception.class) // 添加明确的回滚策略
    public CreateShortUrlResponse createShortUrl(CreateShortUrlRequest request) {
       
    }

    /**
     * 数据库访问次数更新--支持分库分表
     */
    @Transactional(rollbackFor = Exception.class)
    public void updateAccessCountInDatabase(String shortCode, Long accessCount)




}
