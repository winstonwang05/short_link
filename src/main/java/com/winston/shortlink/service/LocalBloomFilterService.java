package com.winston.shortlink.service;

import com.winston.shortlink.filter.TimeSliceBloomFilter;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * @description: 本地时间片布隆过滤器服务
 * @author: Winston
 * @date: 2026/2/13 10:30
 * @version: 1.0
 */

@Slf4j
@Service
@RequiredArgsConstructor
public class LocalBloomFilterService {

    private final RedissonClient redissonClient;

    @Value("${shortlink.bloom.time-slice.hours:6}")
    private int timeSliceHours;

    @Value("${shortlink.bloom.time-slice.local-keep-count:8}")
    private int localKeepSliceCount;

    private final ConcurrentHashMap<String, TimeSliceBloomFilter> localBloomSlices = new ConcurrentHashMap<>();
    /**
     * 当前正在执行的时间片key
     */
    private volatile String currentLocalTimeSlice;

    // 预热进度的控制
    /**
     * 预热基准
     */
    private volatile String prewarmBaselineHeadKey;
    /**
     * 已经预热了时间片个数
     */
    private final AtomicInteger prewarmInitializedCount = new AtomicInteger(0);
    /**
     * 是否完成预热
     */
    private volatile boolean prewarmAllDone = false;

    @PostConstruct
    public void init() {
        currentLocalTimeSlice = getCurrentLocalSliceKey();
        createLocalTimeSlice(currentLocalTimeSlice);
        prewarmBaselineHeadKey = currentLocalTimeSlice;
        prewarmInitializedCount.set(1);
        prewarmAllDone = false;
        log.info("本地时间片初始化完成，当前片: {}", currentLocalTimeSlice);
    }

    public boolean mightContain (String shortCode) {
        for  (TimeSliceBloomFilter bloomFilterSlice : localBloomSlices.values()) {
            if (bloomFilterSlice.mightContain(shortCode)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 添加到本地时间片
     * @param shortCode 添加的数据
     */
    public void addLocal(String shortCode) {
        // 检测时间片是否需要切换
        String expectedSliceKey = getCurrentLocalSliceKey();
        if (!expectedSliceKey.equals(currentLocalTimeSlice)) {
            synchronized (this) {
                if (!expectedSliceKey.equals(currentLocalTimeSlice)) {
                    createLocalTimeSlice(expectedSliceKey);
                    currentLocalTimeSlice = expectedSliceKey;
                    log.info("本地时间片切换: {}", expectedSliceKey);
                }
            }
        }
        String sliceKey = currentLocalTimeSlice;
        TimeSliceBloomFilter timeSliceBloomFilter = localBloomSlices.get(sliceKey);
        if (timeSliceBloomFilter != null) {
            timeSliceBloomFilter.add(shortCode);
        }
        log.debug("本地添加短链到时间片布隆过滤器: {} (片: {})", shortCode, sliceKey);
    }
    @Scheduled(fixedRate = 300000)
    public void cleanupLocalSlices() {
        try {
            doCleanupLocalSlices();
        } catch (Exception e) {
            log.error("清理本地过期时间片失败", e);
        }
    }


    public void doCleanupLocalSlices() {
        // 收集过期了的时间片并删除
        List<String> expiredSlices = new ArrayList<>();
        for (String sliceKey : localBloomSlices.keySet()) {
            if (isLocalSliceExpired(sliceKey)) {
                expiredSlices.add(sliceKey);
            }
        }
        for (String sliceKey : expiredSlices) {
            TimeSliceBloomFilter removed = localBloomSlices.remove(sliceKey);
            if (removed != null) {
                log.info("清理本地过期时间片: {}, 估计元素数: {}", sliceKey, removed.getApproximateElementCount());
            }
        }
        log.info("本地时间片清理完成，当前活跃片数: {}", localBloomSlices.size());
    }

    /**
     * 增量预热，每次仅预热一个缺失的时间片 （按照Redis时间片由新到旧）
     * 当目标范围内的全部本地时间片已初始化，则本次预热跳过
     */
    @Scheduled(fixedRateString = "${shortlink.bloom.local.prewarm.fixed-rate-ms:300000}")
    public void initLocalSlicesFromRedis () {
        try {
            // 1.判断是否需要增量预热，预热基准是否是当前时间段的时间片
            String expectedHead = getCurrentLocalSliceKey();
            if (prewarmBaselineHeadKey == null || !prewarmBaselineHeadKey.equals(expectedHead)) {
                prewarmBaselineHeadKey = expectedHead;
                createLocalTimeSlice(expectedHead);
                prewarmInitializedCount.set(localBloomSlices.containsKey(expectedHead) ? 1 : 0);
                prewarmAllDone = false;
                log.debug("预热基准头片更新为: {}，已初始化计数: {}", expectedHead, prewarmInitializedCount.get());
            }
            if (prewarmAllDone) {
                log.debug("预热跳过：预热已完成，计数: {}/{}"
                        , prewarmInitializedCount.get(), Math.max(localKeepSliceCount, 1));
                return;
            }
            // 2.从Redis时间片获取最新的时间片，上限是8个
            List<String> redisSliceKeysSorted = listExistingRedisSliceKeysSorted();
            int target = Math.max(localKeepSliceCount, 1);
            List<String> targetKeys = new ArrayList<>();
            for  (String sliceKey : redisSliceKeysSorted) {
                targetKeys.add(sliceKey);
                if (targetKeys.size() >= target) {
                    break;
                }
            }
            // 3.如果本地时间片已经含有了的跳过
            int currentCount = 0;
            String firstMissingLocalKey = null;
            for(String sliceKey : targetKeys) {
                if (localBloomSlices.containsKey(sliceKey)) {
                    currentCount++;
                } else if (firstMissingLocalKey == null) {
                    firstMissingLocalKey = sliceKey;
                }
            }
            prewarmInitializedCount.set(currentCount);
            // 如果目标范围超过，则跳过
            if (currentCount >= target || firstMissingLocalKey == null) {
                prewarmAllDone = true;
                log.debug("预热跳过：目标范围已全部存在，计数: {}/{}", currentCount, target);
                return;
            }
            // 4.预热，每次近预热一个缺失的时间片
            createLocalTimeSlice(firstMissingLocalKey);
            int after = prewarmInitializedCount.incrementAndGet();
            log.info("预热本地时间片: {}，进度: {}/{}", firstMissingLocalKey, after, target);
            if (after >= target) {
                prewarmAllDone = true;
                log.info("本地时间片预热全部完成: {}/{}", after, target);
            }
        } catch (Exception e) {
            log.error("本地时间片预热失败", e);
        }
    }


    /**
     * 获取当前时间段的时间片
     * @return 返回时间片key
     */
    private String getCurrentLocalSliceKey() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime sliceTime = now.withMinute(0).withSecond(0).withNano(0)
                .withHour((now.getHour() / timeSliceHours) * timeSliceHours);
        return sliceTime.format(DateTimeFormatter.ofPattern("yyyyMMdd_HH"));
    }

    /**
     * 判断当前时间片是否过期
     * @param sliceKey 时间片key
     * @return 返回是否过期
     */
    private boolean isLocalSliceExpired(String sliceKey) {
        try {
            // 解析为当前时间
            LocalDateTime sliceTime = LocalDateTime.parse(sliceKey,
                    DateTimeFormatter.ofPattern("yyyyMMdd_HH"));
            LocalDateTime expireTime = sliceTime.plusHours(timeSliceHours * localKeepSliceCount);
            return LocalDateTime.now().isAfter(expireTime);
        } catch (Exception e) {
            log.warn("解析本地时间片key失败: {}", sliceKey);
            return true;
        }
    }

    /**
     * 创建当前时间片，添加到Map集合中
     * @param sliceKey 需要添加的时间片
     */
    private void createLocalTimeSlice(String sliceKey) {
        localBloomSlices.put(sliceKey, new TimeSliceBloomFilter(sliceKey));
    }




    /**
     * 从redis 的时间片获取由 新到旧的顺序的时间片
     * 获取上限是8个时间片，并不是必须获取8个，可能小于8个
     * @return 返回有序的时间片
     */
    private List<String> listExistingRedisSliceKeysSorted() {
        List<String> existingKeys = new ArrayList<>();
        LocalDateTime now = LocalDateTime.now();
        // 1.创建更大的窗口范围，因为可能会存在离散的时间片
        int window = Math.max(localKeepSliceCount, 1) * 4;
        for (int i =  0; i < window; i++) {
            LocalDateTime time = now.minusHours(i * timeSliceHours);
            String redisSliceKey = getRedisSliceKey(time);
            try {
                // 2.将存在于Redis的时间片放入结果集
                RBloomFilter<Object> slice = redissonClient.getBloomFilter(redisSliceKey);
                if (slice.isExists()) {
                    existingKeys.add(redisSliceKey);
                }
            } catch (Exception e) {
                log.warn("探测Redis时间片失败: {}", redisSliceKey);
            }
        }
        // 3.排序
        existingKeys.sort((a, b) -> parseRedisSliceTime(b).compareTo(parseRedisSliceTime(a)));
        return existingKeys;
    }


    public String getLocalStats() {
        long totalElements = localBloomSlices.values().stream()
                .mapToLong(TimeSliceBloomFilter::getApproximateElementCount).sum();
        return String.format("本地时间分片统计 - 活跃片数: %d, 当前片: %s, 总元素数: %d, 保留策略: %d天",
                localBloomSlices.size(), currentLocalTimeSlice,
                totalElements, (timeSliceHours * localKeepSliceCount) / 24);
    }


    /**
     * 获取Redis时间片的key
     * @param dateTime 某个时间段
     * @return 返回某个时间段的key
     */
    private String getRedisSliceKey(LocalDateTime dateTime) {
        LocalDateTime sliceTime = dateTime.withMinute(0).withSecond(0).withNano(0)
                .withHour((dateTime.getHour() / timeSliceHours) * timeSliceHours);
        return "redis_bloom_" + sliceTime.format(DateTimeFormatter.ofPattern("yyyyMMdd_HH"));
    }

    /**
     * 将Redis的key转化为本地时间片的key
     * @param redisSliceKey Redis时间片key
     * @return 返回转化后的本地时间片key
     */
    private String toLocalSliceKey (String redisSliceKey) {
        return redisSliceKey.replace("redis_bloom_", "");
    }

    /**
     * 将Redis的时间片转化为时间，用来排序，新 -> 旧的顺序
     * @param redisSliceKey 时间片key
     * @return 返回时间
     */
    private LocalDateTime parseRedisSliceTime (String redisSliceKey) {
        try {
            String localSliceKey = toLocalSliceKey(redisSliceKey);
            return LocalDateTime.parse(localSliceKey, DateTimeFormatter.ofPattern("yyyyMMdd_HH"));
        }  catch (Exception e) {
            log.warn("解析Redis时间片失败: {}", redisSliceKey);
            return LocalDateTime.MIN;
        }
    }

}
