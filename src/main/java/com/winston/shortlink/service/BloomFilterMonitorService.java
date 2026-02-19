package com.winston.shortlink.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/**
 * @description: 布隆过滤器监控服务
 * @author: Winston
 * @date: 2026/2/19 10:10
 * @version: 1.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BloomFilterMonitorService {


    private final RedisTimeBasedBloomFilterService redisTimeBasedBloomFilter;
    private final DingTalkNotificationService notificationService;

    @Value("${shortlink.bloom.alert.false-probability-threshold:0.05}")
    private double falseProbabilityThreshold;

    @Value("${shortlink.bloom.alert.memory-usage-threshold:0.8}")
    private double memoryUsageThreshold;

    @Value("${shortlink.bloom.time-slice.local-keep-count:8}")
    private int localKeepSliceCount;

    @Value("${shortlink.bloom.time-slice.redis-keep-count:32}")
    private int redisKeepSliceCount;

    @Value("${shortlink.bloom.time-slice.hours:6}")
    private int timeSliceHours; // 时间分片粒度（小时）

    @Scheduled(fixedRate = 60000) // 每分钟监控一次
    public void monitorBloomFilters() {
        try {
            // 监控本地时间分片（统一服务内部）
            String localStats = redisTimeBasedBloomFilter.getLocalStats();
            log.info("本地时间分片状态: {}", localStats);

            // 监控Redis时间分片
            String redisStats = redisTimeBasedBloomFilter.getRedisStats();
            log.info("Redis时间分片状态: {}", redisStats);

            // 检查告警条件
            checkAndAlert();

        } catch (Exception e) {
            log.error("布隆过滤器监控失败", e);
        }
    }

    private void checkAndAlert() {
        // 检查内存使用率
        Runtime runtime = Runtime.getRuntime();
        double memoryUsage = (double) (runtime.totalMemory() - runtime.freeMemory()) / runtime.maxMemory();

        if (memoryUsage > memoryUsageThreshold) {
            String alertMsg = String.format("布隆过滤器内存使用率过高: %.2f%% > %.2f%%",
                    memoryUsage * 100, memoryUsageThreshold * 100);
            log.warn(alertMsg);
        }
    }

    @Scheduled(fixedRate = 300000) // 每5分钟输出详细统计
    public void detailedStats() {
        log.info("=== 布隆过滤器详细统计 ===");
        log.info("本地时间分片: {}", redisTimeBasedBloomFilter.getLocalStats());
        log.info("Redis时间分片: {}", redisTimeBasedBloomFilter.getRedisStats());

        // 配置验证
        int localRetentionDays = (timeSliceHours * localKeepSliceCount) / 24;
        int redisRetentionDays = (timeSliceHours * redisKeepSliceCount) / 24;

        log.info("保留策略配置 - 本地: {}天, Redis: {}天", localRetentionDays, redisRetentionDays);

        // 安全性检查
        if (localRetentionDays > 7) {
            log.warn("本地布隆过滤器保留时间({})天超过短链最大过期时间(7天)，可能存在内存浪费", localRetentionDays);
        }

        if (redisRetentionDays < 7) {
            log.warn("Redis布隆过滤器保留时间({})天小于短链最大过期时间(7天)，可能导致误判", redisRetentionDays);
        }

        Runtime runtime = Runtime.getRuntime();
        long usedMemory = runtime.totalMemory() - runtime.freeMemory();
        log.info("内存使用: {}MB / {}MB", usedMemory / 1024 / 1024, runtime.maxMemory() / 1024 / 1024);
    }
}



