package com.winston.shortlink.service;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @description: Redis时间片布隆过滤器服务 -- 集群缓存
 * @author: Winston
 * @date: 2026/2/13 16:33
 * @version: 1.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RedisTimeBasedBloomFilterService {

    private final RedissonClient redissonClient;

    private final LocalBloomFilterService localBloomFilterService;

    private final BloomFilterStreamService bloomFilterStreamService;

    @Value("${shortlink.bloom.time-slice.hours:6}")
    private int timeSliceHours;

    // Redis保留的时间片数量（8天）
    @Value("${shortlink.bloom.time-slice.redis-keep-count:32}")
    private int redisKeepSliceCount;

    /**
     * Redis时间分片布隆过滤器映射
     */
    private final ConcurrentHashMap<String, RBloomFilter<String>> redisTimeSlices = new ConcurrentHashMap<>();
    private volatile String currentTimeSlice;


    /**
     * 每一个时间片的配置
     */
    private static final long EXPECTED_INSERTIONS = 216_000_000L;
    private static final double FALSE_PROBABILITY = 0.01;

    // 节点ID缓存
    private volatile String nodeId;

    /**
     * 时间片活跃，集群感知获取过去8天内的时间片放入当前节点中
     */
    @PostConstruct
    public void init() {
        // 初始化Redis当前时间片
        currentTimeSlice = getCurrentTimeSliceKey();
        // 初始化所有活跃的Redis时间分片
        initializeActiveTimeSlices();

        log.info("时间分片布隆过滤器初始化完成 - Redis当前片: {}, Redis活跃片数: {}",
                currentTimeSlice, redisTimeSlices.size());
    }

    /**
     * 初始化时间片，获取过去8天内的时间片并放入本地Map集合中
     */
    private void initializeActiveTimeSlices() {
        LocalDateTime now = LocalDateTime.now();
        // 1.获取时间分片,计算需要加载的时间片范围
        for (int i = 0; i < redisKeepSliceCount; i++) {
            LocalDateTime localDateTime = now.minusHours(i * timeSliceHours);
            String sliceKey = getTimeSliceKey(localDateTime);
            try {
                // 2.得到对应的布隆过滤器对象
                RBloomFilter<String> bloomFilter = redissonClient.getBloomFilter(sliceKey);
                if (bloomFilter.isExists()) {
                    // 3.将存在的时间分片放入本地Map集合
                    redisTimeSlices.putIfAbsent(sliceKey, bloomFilter);
                    log.debug("加载已存在的时间分片: {}", sliceKey);
                } else if (currentTimeSlice.equals(sliceKey)) {
                    // 如果是当前时间片不存在就创建它
                    createRedisTimeSlice(currentTimeSlice);
                }
            } catch (Exception e) {
                log.warn("初始化时间分片失败: {}, 错误: {}", sliceKey, e.getMessage());
            }
        }
        log.info("活跃时间分片初始化完成，加载了 {} 个时间分片", redisTimeSlices.size());

    }

    /**
     * 获取指定时间处于的时间分片key
     * @param localDateTime 指定时间
     * @return 返回对应的时间分片
     */
    public String getTimeSliceKey(LocalDateTime localDateTime) {
        LocalDateTime sliceTime = localDateTime.withMinute(0).withMinute(0)
                .withSecond(0).withNano(0)
                .withHour((localDateTime.getHour() / timeSliceHours) * timeSliceHours);
        return "redis_bloom_" + sliceTime.format(DateTimeFormatter.ofPattern("yyyyMMdd_HH"));
    }

    public String getCurrentTimeSliceKey() {
        return getTimeSliceKey(LocalDateTime.now());
    }

    /**
     * 创建Redis时间分片
     * @param sliceKey key
     */
    public void createRedisTimeSlice(String sliceKey) {
        try {
            // 1.根据时间片标识获取对应的布隆过滤器对象
            RBloomFilter<String> bloomFilter = redissonClient.getBloomFilter(sliceKey);
            if (!bloomFilter.isExists()) {
                bloomFilter.tryInit(EXPECTED_INSERTIONS, FALSE_PROBABILITY);
            }
            redisTimeSlices.put(sliceKey, bloomFilter);
            log.info("创建Redis时间分片: {}", sliceKey);
        } catch (Exception e) {
            log.error("创建Redis时间分片失败: {}", sliceKey, e);
        }
    }

    /**
     * 判断当前分片是否过期
     * @param sliceKey 时间分片key
     * @return 返回是否过期
     */
    public boolean isSliceExpired(String sliceKey) {
        try {
            String redisBloom = sliceKey.replace("redis_bloom_", "");
            LocalDateTime sliceTime = LocalDateTime.parse(redisBloom,
                    DateTimeFormatter.ofPattern("yyyyMMdd_HH"));
            return LocalDateTime.now().isAfter(sliceTime.plusHours(timeSliceHours * redisKeepSliceCount));
        } catch (Exception e) {
            log.warn("解析Redis时间片key失败: {}", sliceKey);
            // 解析失败认为已过期
            return true;
        }
    }

    /**
     * 检查时间片是否存在该数据
     * 先检查本地，再检查集群
     * @param sliceKey key
     */
    public boolean mightContain(String sliceKey) {
        // 1.先检查本地
        if (localBloomFilterService.mightContain(sliceKey)) {
            return true;
        }
        // 2.再检查Redis时间分片
        for (RBloomFilter<String> bloomFilter : redisTimeSlices.values()) {
            try {
                if (bloomFilter.contains(sliceKey)) {
                    return true;
                }
            } catch (Exception e) {
                log.warn("检查Redis时间分片失败: {}", e.getMessage());
            }
        }
        return false;
    }

    /**
     * 添加到Redis时间片
     * @param shortCode 数据
     */
    public void add(String shortCode) {
        // 1.获取当前段的时间片
        String currentTimeSliceKey = getCurrentTimeSliceKey();
        if (!currentTimeSliceKey.equals(currentTimeSlice)) {
            synchronized (this) {
                createRedisTimeSlice(currentTimeSliceKey);
                currentTimeSlice = currentTimeSliceKey;
                log.info("创建新的Redis时间片: {}", currentTimeSliceKey);
            }
        }
        // 2.获取当前片key的布隆过滤器对象并存储数据
        RBloomFilter<String> bloomFilter = redissonClient.getBloomFilter(currentTimeSlice);
        if (bloomFilter.isExists()) {
            try {
                bloomFilter.add(shortCode);
            } catch (Exception e) {
                log.error("添加到Redis时间分片失败: slice={}, shortCode={}", currentTimeSlice, shortCode, e);
            }
        }
        // 3.同步到本节点本地布隆过滤器
        localBloomFilterService.addLocal(shortCode);
        // 4.广播到其他节点
        try {
            bloomFilterStreamService.publishNewShortCode(shortCode);
        } catch (Exception e) {
            log.warn("发布Stream事件失败: {}", e.getMessage());
        }
    }
    /**
     * 定时清理过期的Redis时间片
     */
    /**
     * 简化的清理方法：所有节点都执行，无需分布式锁
     */
    @Scheduled(fixedRate = 300000) // 每5分钟检查一次
    public void cleanupExpiredSlices() {
        /**
         * 清理过期的Redis时间片（幂等安全）
         * 输入: 无
         * 输出: 无；副作用为删除过期Redis分片并释放内存映射
         */
        try {
            log.debug("开始清理过期时间片 - 节点: {}", getNodeId());
            doCleanupExpiredSlices();
        } catch (Exception e) {
            log.error("清理过期时间片失败 - 节点: {}", getNodeId(), e);
        }
    }

    /**
     * 将过期的Redis时间片清除
     * Map集合和Redis时间片都清除
     */
    private void doCleanupExpiredSlices() {
        try {
            // 1.获取过期的时间片
            List<String> expiredSlices = new ArrayList<>();
            for (String sliceKey : redisTimeSlices.keySet()) {
                if (isSliceExpired(sliceKey)) {
                    expiredSlices.add(sliceKey);
                }
            }
            // 2.将过期时间片清除
            int memoryCleanedCount = 0;
            int redisCleanedCount = 0;
            for (String sliceKey : expiredSlices) {
                // 清除内存中的时间片
                RBloomFilter<String> removed = redisTimeSlices.remove(sliceKey);
                if (removed != null) {
                    memoryCleanedCount++;
                    // 清除Redis中的时间片
                    try {
                        if (removed.isExists()) {
                            removed.delete();
                            redisCleanedCount++;
                            log.info("✅ 清理时间片成功: {} - 节点: {}", sliceKey, getNodeId());
                        } else {
                            log.debug("时间片已被清理: {} - 节点: {}", sliceKey, getNodeId());
                        }
                    } catch (Exception e) {
                        log.warn("Redis清理失败: {} - 节点: {}, 错误: {}",
                                sliceKey, getNodeId(), e.getMessage());
                        // 注意：即使Redis删除失败，内存已经清理，不会影响应用运行
                    }
                }

            }
            if (memoryCleanedCount > 0 || redisCleanedCount > 0) {
                log.info("🧹 清理完成 - 节点: {}, 内存清理: {}, Redis清理: {}, 剩余片数: {}",
                        getNodeId(), memoryCleanedCount, redisCleanedCount, redisTimeSlices.size());
            }
        } catch (Exception e) {
            log.error("清理过期时间片失败 - 节点: {}", getNodeId(), e);
        }
    }


    /**
     * 获取当前节点ID
     * 格式: hostname-pid-timestamp
     */
    public String getNodeId() {
        /**
         * 获取当前节点ID
         * 输入: 无
         * 输出: 节点标识字符串（hostname-pid-timestamp）
         */
        if (nodeId == null) {
            synchronized (this) {
                if (nodeId == null) {
                    try {
                        String hostname = InetAddress.getLocalHost().getHostName();
                        String pid = ManagementFactory.getRuntimeMXBean().getName().split("@")[0];
                        // 取后5位避免太长
                        long timestamp = System.currentTimeMillis() % 100000;
                        nodeId = String.format("%s-%s-%d", hostname, pid, timestamp);
                    } catch (Exception e) {
                        // 如果获取失败，使用备用方案
                        nodeId = "node-" + System.currentTimeMillis() % 10000;
                        log.warn("获取节点信息失败，使用备用节点ID: {}", nodeId);
                    }
                    log.info("节点ID初始化: {}", nodeId);
                }
            }
        }
        return nodeId;
    }

    public String getRedisStats() {
        /**
         * 获取Redis时间分片统计信息
         * 输入: 无
         * 输出: 统计字符串（活跃片数/当前片/保留策略）
         */
        return String.format("Redis时间分片统计 - 活跃片数: %d, 当前片: %s, 保留策略: %d天",
                redisTimeSlices.size(), currentTimeSlice,
                (timeSliceHours * redisKeepSliceCount) / 24);
    }

    public String getLocalStats() {
        return localBloomFilterService.getLocalStats();
    }
    /**
     * 兼容旧接口：返回合并统计
     */
    public String getStats() {
        return String.format("时间分片统计 - 本地: [%s], Redis: [%s]",
                getLocalStats(), getRedisStats());
    }

}
