package com.winston.shortlink.service;

import com.alibaba.nacos.common.utils.StringUtils;
import com.winston.shortlink.entity.ShortUrlMapping;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

/**
 * @description: 本地缓存服务
 * @author: Winston
 * @date: 2026/2/9 21:56
 * @version: 1.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LocalCacheService {
    /**
     * 自注入，@Lazy防止循环依赖，自注入可以是调用本类方法注解不会失效
     */
    @Lazy
    @Autowired
    private LocalCacheService localCacheService;

    private final LocalCacheStreamService localCacheStreamService;

    /**
     * 从本地缓存中获取数据，无需广播，如果本地缓存查询得到，不会执行方法体内容
     * @param shortCode 短码
     * @return 返回缓存信息
     */
    @Cacheable(value = "shortUrls", keyGenerator = "shortCodeKeyGenerator")
    public ShortUrlMapping getFromLocalCache(String shortCode) {
        if (!StringUtils.hasText(shortCode)) {
            return null;
        }
        log.debug("尝试从本地缓存获取: {}", shortCode);
        return null;
    }

    /**
     * 安全从本地缓存获取，包括了异常处理
     * @param shortCode 短码
     * @return 返回缓存信息
     */
    public ShortUrlMapping safeGetFromLocalCache(String shortCode) {
        try {
            log.debug("safeGetFromLocalCache开始: shortCode='{}'", shortCode);

            if (!org.springframework.util.StringUtils.hasText(shortCode)) {
                log.warn("safeGetFromLocalCache: shortCode无效, value='{}'", shortCode);
                return null;
            }
            return localCacheService.getFromLocalCache(shortCode);
        } catch (Exception e) {
            log.error("safeGetFromLocalCache失败: shortCode='{}', error={}",
                    shortCode, e.getMessage(), e);
            return null;
        }
    }

    /**
     * 数据放入本地缓存,无广播
     * @param shortCode 短码
     * @param shortUrlMapping 需要存储到本地缓存的数据，可能是从Redis缓存或者数据库而来
     */
    @CachePut(value = "shortUrls", keyGenerator = "shortCodeKeyGenerator")
    public ShortUrlMapping putToLocalCacheOnly(String shortCode, ShortUrlMapping shortUrlMapping) {
        if (!StringUtils.hasText(shortCode)) {
            log.error("putToLocalCacheOnly: shortCode为空或null, shortCode={}", shortCode);
            throw new IllegalArgumentException("shortCode不能为空");
        }
        if (shortUrlMapping == null) {
            log.warn("putToLocalCache: shortUrlMapping为空，shortCode: {}", shortCode);
            return null;
        }
        log.debug("放入本地缓存: shortCode={}, originalUrl={}", shortCode, shortUrlMapping.getOriginUrl());

        return shortUrlMapping;
    }

    /**
     * 放入本地缓存 + 广播
     * @param shortCode 短码
     * @param shortUrlMapping 需要缓存的数据
     * @return 返回缓存的数据
     */
    public ShortUrlMapping safePutToLocalCache(String shortCode, ShortUrlMapping shortUrlMapping) {
        try {
            if (!StringUtils.hasText(shortCode) || shortUrlMapping == null) {
                log.warn("safePutToLocalCache: shortCode无效, value='{}'", shortCode);
                return null;
            }
            // 广播
            if (localCacheStreamService != null) {
                localCacheStreamService.publishCachePut(shortCode);
            }
            return localCacheService.putToLocalCacheOnly(shortCode, shortUrlMapping);
        } catch (Exception e) {
            log.error("safePutToLocalCache失败: shortCode='{}', error={}",
                    shortCode, e.getMessage(), e);
            return shortUrlMapping;
        }

    }

    /**
     * 从本地缓存中删除,无广播
     * @param shortCode 短码
     */
    @CacheEvict(value = "shortUrls", keyGenerator = "shortCodeKeyGenerator")
    public void evictFromLocalCacheOnly(String shortCode) {
        // 参数校验
        if (!org.springframework.util.StringUtils.hasText(shortCode)) {
            log.warn("evictFromLocalCache: shortCode为空或null, shortCode={}", shortCode);
            return;
        }

        log.debug("从本地缓存移除: shortCode={}", shortCode);
    }

    /**
     * 安全从本地缓存中删除数据，有广播
     * @param shortCode 短码
     */
    public void safeEvictFromLocalCache(String shortCode) {
        try {
            log.debug("safeEvictFromLocalCache开始: shortCode='{}'", shortCode);

            if (!org.springframework.util.StringUtils.hasText(shortCode)) {
                log.warn("safeEvictFromLocalCache: shortCode无效, value='{}'", shortCode);
                return;
            }
            // 广播
            if  (localCacheStreamService != null) {
                localCacheStreamService.publishCacheEvict(shortCode);
            }
            localCacheService.evictFromLocalCacheOnly(shortCode);
        }  catch (Exception e) {
            log.error("safeEvictFromLocalCache失败: shortCode='{}', error={}",
                    shortCode, e.getMessage(), e);
        }
    }

}
