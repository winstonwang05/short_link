package com.winston.shortlink.service;

import com.alibaba.csp.sentinel.annotation.SentinelResource;
import com.alibaba.csp.sentinel.slots.block.BlockException;

import com.winston.shortlink.config.ShortCodeConfig;
import com.winston.shortlink.constant.CommonConstants;
import com.winston.shortlink.dao.ShortUrlDao;
import com.winston.shortlink.dto.CreateShortUrlRequest;
import com.winston.shortlink.dto.CreateShortUrlResponse;
import com.winston.shortlink.entity.ShortUrlMapping;
import com.winston.shortlink.util.DigestUtils;
import jakarta.persistence.EntityManager;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import static com.winston.shortlink.constant.CommonConstants.NEW_SHARDING_DATABASE_COUNT;
import static com.winston.shortlink.constant.CommonConstants.NEW_SHARDING_TABLE_COUNT;

/**
 * @description: 短链url服务
 * @author: Winston
 * @date: 2026/1/28 15:15
 * @version: 1.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShortUrlService {

    private final ShortUrlDao shortUrlDao;
    // 使用集群感知缓存服务替代原有的CacheService
    private final ClusterAwareCacheService clusterAwareCacheService;
    private final ShortCodeService shortCodeService;
    private final EntityManager entityManager;
    private final TransactionTemplate transactionTemplate;
    private final DistributedLockService distributedLockService;
    private final ShortCodeConfig shortCodeConfig;
    private final ShardingStrategyService shardingStrategyService;

    @Value("${shortlink.expansion.dual-write-enabled:false}")
    private boolean dualWriteEnabled;

    @Value("${shortlink.shorturl.domain:http://localhost:8001}")
    private String domain;

    @Value("${shortlink.shorturl.default-expire-days:0}")
    private Integer defaultExpireDays;

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
    public CreateShortUrlResponse createShortUrl(CreateShortUrlRequest request) {
        // 1.获取原始URL的多重哈希值
        List<String> urlHashes = generateMultipleUrlHash(request.getOriginUrl());
        // 2.第一层防护：智能缓存检查（支持多重哈希），增加hash冲突处理逻辑
        CacheCheckResult cacheCheckResult = smartCacheCheck(request.getOriginUrl(), urlHashes);
        if (cacheCheckResult.getCreateShortUrlResponse() != null) {
            return cacheCheckResult.getCreateShortUrlResponse();
        }
        String primaryUrlHash = cacheCheckResult.getCurrentHash();
        // 3.第二层防护：使用分布式锁保证同一URL的串行处理
        String lockKey = "create_url:" + primaryUrlHash;
        return distributedLockService.executeWithLock(lockKey, () -> {
            // 获取哈希映射
            String cachedShortCode = clusterAwareCacheService.getShortCodeByUrlHash(primaryUrlHash);
            if (StringUtils.isNotBlank(cachedShortCode)) {
                // 直接从缓存获取完整信息，避免额外的getShortUrlInfo调用
                ShortUrlMapping cachedMapping = clusterAwareCacheService.getFromCache(cachedShortCode);
                if (cachedMapping != null && request.getOriginUrl().equals(cachedMapping.getOriginUrl())) {
                    log.info("分布式锁内缓存命中: shortCode={}, originUrl={}", cachedShortCode, request.getOriginUrl());
                    return buildResponse(cachedMapping);
                }
            }
            // 使用分布式ID生成策略 生成短链code
            final String initialShortCode = shortCodeService.generateByStrategy(request.getOriginUrl());
            // 根据shortCode计算精确的分片位置
            int dbIndex = Math.abs(initialShortCode.hashCode()) % NEW_SHARDING_DATABASE_COUNT;
            int tableIndex = Math.abs(initialShortCode.hashCode()) % NEW_SHARDING_TABLE_COUNT;

            // 第一重检查
            ShortUrlMapping mapping = shortUrlDao.preCheckByShortCode(dbIndex, tableIndex, initialShortCode, primaryUrlHash)
                    .orElse(null);
            if (mapping != null) {
                // 缓存查询结果
                clusterAwareCacheService.putToCache(mapping.getShortCode(), mapping);
                clusterAwareCacheService.putUrlHashMapping(primaryUrlHash, mapping.getShortCode());
                log.info("数据库智能查询命中，返回已存在短链: shortCode={}, originUrl={}",
                        mapping.getShortCode(), request.getOriginUrl());
                return buildResponse(mapping);
            }

            try {
                // 4.第三层防护：数据库事务 + 唯一索引
                return transactionTemplate.execute(status -> {
                    try {
                        String currentShortCode = initialShortCode;
                        // 检查生成的短链code是否已存在（避免哈希冲突）
                        Optional<ShortUrlMapping> existingByCode = shortUrlDao.findByShortCode(currentShortCode);
                        if  (existingByCode.isPresent()) {
                            ShortUrlMapping existing = existingByCode.get();

                            if (request.getOriginUrl().equals(existing.getOriginUrl())) {
                                clusterAwareCacheService.putToCache(currentShortCode, existing);
                                clusterAwareCacheService.putUrlHashMapping(primaryUrlHash, currentShortCode);
                                log.info("发现相同URL的短链: shortCode={}, originUrl={}, 数据库分片: db={}, table={}, Redis分片槽位={}",
                                        currentShortCode, request.getOriginUrl(),
                                        calculateDatabaseIndex(currentShortCode), calculateTableIndex(currentShortCode),
                                        shardingStrategyService.calculateSlot(currentShortCode));
                                return buildResponse(existing);
                            } else {
                                // 哈希冲突，重新生成
                                int retryCount = 0;
                                int maxRetries = shortCodeConfig.getMaxRetries();
                                while (existingByCode.isPresent() && retryCount < maxRetries) {
                                    currentShortCode = shortCodeService.generateByStrategy(request.getOriginUrl());
                                    existingByCode = shortUrlDao.findByShortCode(currentShortCode);
                                    retryCount++;
                                }
                                if (retryCount >= maxRetries) {
                                    throw new RuntimeException("生成短链失败，请重试");
                                }
                            }
                        }

                        // 5.保存到数据库并写入布隆过滤器及缓存
                        // 创建新的短链记录
                        ShortUrlMapping shortUrlMapping = new ShortUrlMapping();
                        shortUrlMapping.setShortCode(currentShortCode);
                        shortUrlMapping.setOriginUrl(request.getOriginUrl());
                        shortUrlMapping.setOriginUrlHash(primaryUrlHash);
                        shortUrlMapping.setExpireDays(request.getExpireDays() != null ? request.getExpireDays() : defaultExpireDays);
                        shortUrlMapping.setCreator(request.getCreator());
                        shortUrlMapping.setStatus(1);
                        shortUrlMapping.setCreateTime(LocalDateTime.now());

                        // 在保存到数据库之前添加日志
                        log.info("准备保存短链: shortCode={}, originUrl={}, originUrlHash={},Redis分片槽位={}",
                                currentShortCode, request.getOriginUrl(), primaryUrlHash,
                                shardingStrategyService.calculateSlot(currentShortCode));

                        shortUrlMapping = shortUrlDao.save(shortUrlMapping, dbIndex, tableIndex);

                        entityManager.flush();

                        log.info("短链保存完成: shortCode={}, 实际shortCode={}", currentShortCode, shortUrlMapping.getShortCode());

                        // 添加到布隆过滤器
                        clusterAwareCacheService.addToBloomFilter(shortUrlMapping.getShortCode());

                        // 缓存短链信息和URL哈希映射到Redis集群
                        clusterAwareCacheService.putToCache(shortUrlMapping.getShortCode(), shortUrlMapping);
                        clusterAwareCacheService.putUrlHashMapping(primaryUrlHash, shortUrlMapping.getShortCode());

                        log.info("创建短链成功: shortCode={}, originUrl={}, Redis分片槽位={}",
                                shortUrlMapping.getShortCode(), request.getOriginUrl(),
                                shardingStrategyService.calculateSlot(shortUrlMapping.getShortCode()));
                        return buildResponse(shortUrlMapping);
                    } catch (Exception e) {
                        status.setRollbackOnly();
                        throw e;
                    }
                });
            } catch (Exception e) {
                log.error("创建短链失败: originUrl={}, error={}", request.getOriginUrl(), e.getMessage(), e);
                throw new RuntimeException("创建短链失败: " + e.getMessage(), e);
            }
        });
    }

    /**
     * 获取原始URL -- 用于重定向，链接的跳转
     */
    @SentinelResource(
            value = "getOriginUrl",
            blockHandler = "getOriginUrlBlockHandler",
            fallback = "getOriginUrlFallback"
    )
    public String getOriginUrl(String shortCode) {
        CreateShortUrlResponse shortUrlInfo = getShortUrlInfo(shortCode);
        if (Objects.isNull(shortUrlInfo)) {
            recordNotExistLog(shortCode);
            return null;
        }
        // 异步更新访问次数（不阻塞主流程）- 使用集群分片
        updateAccessCountAsync(shortCode);

        log.debug("短链重定向: shortCode={}, originUrl={}, 数据库分片: db={}, table={}, Redis分片槽位={}",
                shortCode, shortUrlInfo.getOriginUrl(),
                calculateDatabaseIndex(shortCode), calculateTableIndex(shortCode),
                shardingStrategyService.calculateSlot(shortCode));

        return shortUrlInfo.getOriginUrl();

    }

    /**
     * 异步更新访问次数，查询时候增加次数
     */
    @SentinelResource(value = "updateAccessCount")
    public void updateAccessCountAsync(String shortCode) {
        try {
            // 先尝试从Redis集群增加计数
            Long count = clusterAwareCacheService.incrementAccessCount(shortCode);

            // 异步更新数据库（可以考虑批量更新）
            if (count != null && count % 100 == 0) {
                // 每100次访问同步一次数据库（ShardingSphere会自动路由）
                updateAccessCountInDatabase(shortCode, count);
            }
        } catch (Exception e) {
            // 访问计数失败不影响主流程
            log.warn("更新访问次数失败: shortCode={}, error={}", shortCode, e.getMessage());
        }
    }

    /**
     * 数据库访问次数更新--支持分库分表
     */
    @Transactional(rollbackFor = Exception.class)
    public void updateAccessCountInDatabase(String shortCode, Long accessCount) {
        try {
            int updated = shortUrlDao.updateAccessCount(shortCode, accessCount);
            if (updated > 0) {
                log.debug("访问次数更新成功: shortCode={}, accessCount={}, 数据库分片: db={}, table={}",
                        shortCode, accessCount,
                        calculateDatabaseIndex(shortCode), calculateTableIndex(shortCode));
            } else {
                log.warn("访问次数更新失败，记录不存在: shortCode={}", shortCode);
            }
        } catch (Exception e) {
            log.error("数据库访问次数更新失败: shortCode={}, accessCount={}, error={}",
                    shortCode, accessCount, e.getMessage(), e);
            throw e;
        }
    }


    /**
     * 智能缓存验证 - 批量查询多个哈希值
     * 寻找可用的哈希值，返回第一个匹配的缓存结果，如果都不匹配则返回null
     */
    private CacheCheckResult smartCacheCheck(String originalUrl, List<String> urlHashes) {
        // 1.遍历多重哈希值
        for (String urlHash : urlHashes) {
            // 2.获取哈希映射
            String cachedShortCode = clusterAwareCacheService.getShortCodeByUrlHash(urlHash);
            if (StringUtils.isNotBlank(cachedShortCode)) {
                // 3.判断是否存在实体类，如果存在
                CreateShortUrlResponse cachedResponse = getShortUrlInfo(cachedShortCode);
                // 3.1 判断原始URL是否一致
                if (cachedResponse != null && originalUrl.equals(cachedResponse.getOriginUrl())) {
                    log.info("智能缓存命中: urlHash={}, shortCode={}, originUrl={}",
                            urlHash, cachedShortCode, originalUrl);
                    return new CacheCheckResult(cachedResponse, urlHash);
                } else {
                    // 发现哈希冲突，记录日志但不删除映射（因为这可能是其他URL的正确映射）
                    log.warn("检测到哈希冲突，跳过此哈希值: urlHash={}, 缓存URL={}, 请求URL={}",
                            urlHash, cachedResponse != null ? cachedResponse.getOriginUrl() : "null", originalUrl);
                    // 不删除映射，继续尝试下一个哈希值
                }
            } else {
                // 4.如果不存在，直接利用该哈希值
                // 找到第一个可用的哈希值，直接返回
                log.debug("找到可用哈希值: urlHash={}", urlHash);
                return new CacheCheckResult(null, urlHash);
            }
        }
        // 如果所有哈希值都冲突，抛出异常
        throw new RuntimeException("创建短链hash值失败，请稍后重试");
    }
    @AllArgsConstructor
    @Data
    static class CacheCheckResult {

        private CreateShortUrlResponse createShortUrlResponse;

        private String currentHash;
    }

    /**
     * 查询短链信息
     */
    @SentinelResource(
            value = "getShortUrlInfo",
            blockHandler = "getShortUrlInfoBlockHandler",
            fallback = "getShortUrlInfoFallback"
    )
    public CreateShortUrlResponse getShortUrlInfo(String shortCode) {
        // 1.参数检验
        if (shortCode == null) {
            return null;
        }
        // 2.查询布隆过滤器
        if (!clusterAwareCacheService.existsInBloomFilter(shortCode)) {
            log.debug("布隆过滤器检查失败: shortCode={}", shortCode);
            return null;
        }
        // 3.查询本地缓存和数据库
        ShortUrlMapping shortUrlMapping = getShortUrlWithSentinel(shortCode);
        if (shortUrlMapping == null) {
            log.debug("短链不存在: shortCode={}", shortCode);
            return null;
        }
        // 4.检验是否过期
        if (shortUrlMapping.isExpired()) {
            log.debug("短链已过期: shortCode={}, createTime={}, expireDays={}",
                    shortCode, shortUrlMapping.getCreateTime(), shortUrlMapping.getExpireDays());
            return null;
        }
        // 5.检查状态
        if (shortUrlMapping.getStatus() == null || shortUrlMapping.getStatus() != 1) {
            log.debug("短链状态异常: shortCode={}, status={}", shortCode, shortUrlMapping.getStatus());
            return null;
        }
        return buildResponse(shortUrlMapping);
    }


    /**
     * 高性能哈希冲突解决方案 - 多重哈希策略
     * 使用多种哈希算法生成候选URL哈希值， 减少哈希冲突
     */
    private List<String> generateMultipleUrlHash (String url) {
        List<String> urlHashes = new ArrayList<>();

        // 主哈希：MD5
        urlHashes.add(DigestUtils.md5(url));

        // 备用哈希1： SHA-256的前32位
        String sha256 = DigestUtils.sha256(url);
        urlHashes.add(sha256);

        // 备用哈希2：带时间戳盐值得MD5 // 秒级时间戳
        String saltedUrl = url + "_" + System.currentTimeMillis() / 1000;
        urlHashes.add(DigestUtils.md5(saltedUrl));

        return urlHashes;
    }
    /**
     * 带Sentinel保护的短链查询查询多层缓存再查询数据库
     */
    @SentinelResource(
            value = "databaseQuery",
            blockHandler = "databaseQueryBlockHandler",
            fallback = "databaseQueryFallback"
    )
    private ShortUrlMapping getShortUrlWithSentinel(String shortCode) {
        // 1.先查询缓存（包括本地+ Redis集群）
        ShortUrlMapping shortUrlMapping = clusterAwareCacheService.getFromCache(shortCode);
        if (shortUrlMapping != null) {
            return shortUrlMapping;
        }
        // 缓存未命中，查询数据库（ShardingSphere会自动路由到正确的分片）
        try {
            // 2.查询数据库，并保持到本地和集群
            Optional<ShortUrlMapping> optional = shortUrlDao.findByShortCode(shortCode);
            if  (optional.isPresent()) {
                shortUrlMapping = optional.get();
                clusterAwareCacheService.putToCache(shortCode, shortUrlMapping);
                log.debug("数据库查询成功: shortCode={}, 数据库分片: db={}, table={}, Redis分片槽位={}",
                        shortCode, calculateDatabaseIndex(shortCode), calculateTableIndex(shortCode),
                        shardingStrategyService.calculateSlot(shortCode));
                return shortUrlMapping;
            }
        } catch (Exception e) {
            log.error("数据库查询失败: shortCode={}, error={}", shortCode, e.getMessage(), e);
            throw e;
        }

        recordNotExistLog(shortCode);
        return null;
    }
    private void recordNotExistLog(String shortCode) {
        log.debug("短链不存在: shortCode={}", shortCode);
    }

    /**
     * 计算数据库索引（更新为32个数据库）
     */
    private int calculateDatabaseIndex(String shortCode) {
        if (dualWriteEnabled) {
            return Math.abs(shortCode.hashCode()) % NEW_SHARDING_DATABASE_COUNT;
        }
        return Math.abs(shortCode.hashCode()) % CommonConstants.SHARDING_DATABASE_COUNT;
    }

    /**
     * 计算表索引（更新为256张表）
     */
    private int calculateTableIndex(String shortCode) {
        if (dualWriteEnabled) {
            return Math.abs(shortCode.hashCode()) % NEW_SHARDING_TABLE_COUNT;
        }
        return Math.abs(shortCode.hashCode()) % CommonConstants.SHARDING_TABLE_COUNT;
    }

    /**
     * 构建响应对象
     */
    private CreateShortUrlResponse buildResponse(ShortUrlMapping shortUrlMapping) {
        CreateShortUrlResponse response = new CreateShortUrlResponse();
        response.setShortCode(shortUrlMapping.getShortCode());
        response.setShortUrl(domain + "/" + shortUrlMapping.getShortCode());
        response.setOriginUrl(shortUrlMapping.getOriginUrl());
        response.setCreateTime(shortUrlMapping.getCreateTime());
        response.setExpireDays(shortUrlMapping.getExpireDays());
        response.setAccessCount(shortUrlMapping.getAccessCount());
        return response;
    }
    // ==================== Sentinel 处理方法 ====================

    public CreateShortUrlResponse createShortUrlBlockHandler(CreateShortUrlRequest request, BlockException ex) {
        log.warn("创建短链被限流: originUrl={}", request != null ? request.getOriginUrl() : "null");
        CreateShortUrlResponse response = new CreateShortUrlResponse();
        response.setShortCode("RATE_LIMITED");
        response.setShortUrl("系统繁忙，请稍后重试");
        return response;
    }

    public CreateShortUrlResponse createShortUrlFallback(CreateShortUrlRequest request, Throwable ex) {
        log.error("创建短链降级: originUrl={}, error={}",
                request != null ? request.getOriginUrl() : "null", ex.getMessage());
        CreateShortUrlResponse response = new CreateShortUrlResponse();
        response.setShortCode("SERVICE_DEGRADED");
        response.setShortUrl("创建短链失败，请稍后重试");
        return response;
    }

    public CreateShortUrlResponse getShortUrlInfoBlockHandler(String shortCode, BlockException ex) {
        log.warn("查询短链信息被限流: shortCode={}", shortCode);
        return null;
    }

    public CreateShortUrlResponse getShortUrlInfoFallback(String shortCode, Throwable ex) {
        log.error("查询短链信息降级: shortCode={}, error={}", shortCode, ex.getMessage());
        return null;
    }

    public String getOriginUrlBlockHandler(String shortCode, BlockException ex) {
        log.warn("获取原始URL被限流: shortCode={}", shortCode);
        return null;
    }

    public String getOriginUrlFallback(String shortCode, Throwable ex) {
        log.error("获取原始URL降级: shortCode={}, error={}", shortCode, ex.getMessage());
        return null;
    }

    public ShortUrlMapping databaseQueryBlockHandler(String shortCode, BlockException ex) {
        log.warn("数据库查询被限流: shortCode={}", shortCode);
        return null;
    }

    public ShortUrlMapping databaseQueryFallback(String shortCode, Throwable ex) {
        log.error("数据库查询降级: shortCode={}, error={}", shortCode, ex.getMessage());
        return null;
    }



}
