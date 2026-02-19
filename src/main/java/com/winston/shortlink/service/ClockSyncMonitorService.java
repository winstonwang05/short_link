package com.winston.shortlink.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RedissonClient;
import org.redisson.client.protocol.Time;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;

/**
 * @description: 时间同步监控服务
 * 监控服务器时钟偏移，防止始终回拨问题
 * @author: Winston
 * @date: 2026/2/14 20:04
 * @version: 1.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ClockSyncMonitorService {

    private final RedissonClient redissonClient;
    private final DistributedLockService lockService;

    private static final String CLOCK_SYNC_KEY = "shortlink:clock-sync";
    private static final String CLOCK_ALERT_KEY = "shortlink:clock-alert";
    // 严重时钟偏移阈值(ms)
    private static final long SEVERE_DRIFT_THRESHOLD = 100;
    /**
     * 每分钟检查一次时钟同步状态
     */
    @Scheduled(fixedRate = 60000)
    public void checkClockSync() {
        try {
            // 获取Redis服务器时间作为参考
            long redisTime = getRedisTime();
            long localTime = System.currentTimeMillis();
            long drift = localTime - redisTime; // 可能为负值
            long absDrift = Math.abs(drift);

            // 记录时钟偏移
            if (absDrift > 10) { // 超过10ms才记录，减少日志量
                log.info("时钟偏移检测: {}ms, 本地时间: {}, 参考时间: {}",
                        drift, formatTime(localTime), formatTime(redisTime));
            }

            // 处理严重时钟偏移
            if (absDrift > SEVERE_DRIFT_THRESHOLD) {
                handleSevereDrift(drift, localTime, redisTime);
            }

        } catch (Exception e) {
            log.error("时钟同步检查失败", e);
        }
    }
    /**
     * 处理严重时钟偏移
     */
    private void handleSevereDrift(long drift, long localTime, long redisTime) {
        // 使用分布式锁避免告警风暴
        lockService.executeWithLock(CLOCK_ALERT_KEY, 30, 60, TimeUnit.SECONDS, () -> {
            log.warn("检测到严重时钟偏移: {}ms, 本地时间: {}, 参考时间: {}",
                    drift, formatTime(localTime), formatTime(redisTime));

            // 这里可以添加告警逻辑，如发送钉钉通知等

            return null;
        });
    }
    /**
     * 获取Redis服务器时间
     * @return 返回Redis服务器时间
     */
    private long getRedisTime() {
        try {
            // 1.获取节点组
            var nodes = redissonClient.getNodesGroup().getNodes();
            if (nodes == null || !nodes.iterator().hasNext()) {
                log.warn("Redis节点列表为空，使用系统时间");
                return System.currentTimeMillis();
            }
            // 2.遍历节点组，并获取其中三个节点的时间
            int sampleCount = 0;
            long[] samples = new long[3];
            var it =  nodes.iterator();
            while (it.hasNext() && sampleCount < samples.length) {
                var node = it.next();
                try {
                    // 3.RTT补偿
                    long tStart = System.currentTimeMillis();
                    Time timeResponse = node.time();
                    long tEnd = System.currentTimeMillis();

                    if (timeResponse == null) {
                        continue;
                    }
                    // Redis TIME: 秒 + 微秒 → 毫秒
                    long serverMillis = timeResponse.getSeconds() * 1000L + (timeResponse.getMicroseconds() / 1000L);
                    long rtt = Math.max(0L, tEnd - tStart);
                    // 将服务器时间校正到本地时间轴的“响应时刻”，采用NTP的简化近似：serverTime + rtt/2
                    long adjusted = serverMillis + rtt / 2;
                    samples[sampleCount++] = adjusted;
                } catch (Exception e) {
                    // 单节点失败不影响整体结果，继续其他节点
                    log.debug("采样Redis节点时间失败，忽略该节点", e);
                }
            }
            if (sampleCount == 0) {
                log.warn("所有Redis时间采样失败，使用系统时间");
                return System.currentTimeMillis();
            }

            // 4.取三个时间的中位数
            Arrays.sort(samples,  0, sampleCount);
            long midTime = samples[(sampleCount - 1) / 2];
            return midTime;
        } catch (Exception e) {
            log.warn("获取Redis时间失败，使用系统时间", e);
            return System.currentTimeMillis();
        }
    }
    /**
     * 格式化时间戳
     */
    private String formatTime(long timestamp) {
        return Instant.ofEpochMilli(timestamp).toString();
    }

    /**
     * 获取当前参考时间
     * 可在时钟回拨情况下作为备用时间源
     */
    public long getReferenceTime() {
        try {
            return getRedisTime();
        } catch (Exception e) {
            log.error("获取参考时间失败，使用系统时间", e);
            return System.currentTimeMillis();
        }
    }

}
