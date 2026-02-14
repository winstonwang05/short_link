package com.winston.shortlink.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * @description: 分层布隆过滤器--本地 + Redis + 广播
 * @author: Winston
 * @date: 2026/2/13 22:19
 * @version: 1.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class TieredBloomFilterService {

    private final RedisTimeBasedBloomFilterService redisTimeBasedBloomFilterService;

    public boolean mightContain(String shortCode) {
        return redisTimeBasedBloomFilterService.mightContain(shortCode);
    }

    public void put(String shortCode) {
        if (shortCode == null || shortCode.trim().isEmpty()) {
            log.warn("shortCode为空或null");
            return;
        }

        try {
            // 统一由RedisTimeBasedBloomFilterService处理（内部包含：本地分片+Redis分片+发布Stream）
            redisTimeBasedBloomFilterService.add(shortCode);

            log.debug("时间分片布隆过滤器添加成功: {}", shortCode);
        } catch (Exception e) {
            log.error("时间分片布隆过滤器添加失败: shortCode={}", shortCode, e);
        }
    }

    public void addBatch(List<String> shortCodes) {
        for (String shortCode : shortCodes) {
            put(shortCode);
        }
    }
    /**
     * 获取统计信息
     */
    public String getStats() {
        return String.format("时间分片架构统计 - 本地: [%s], Redis: [%s]",
                redisTimeBasedBloomFilterService.getLocalStats(),
                redisTimeBasedBloomFilterService.getRedisStats());
    }
    /**
     * 获取节点信息
     * 包含节点ID、服务状态、统计信息等
     */
    public NodeInfo getNodeInfo() {
        try {
            // 获取节点ID（通过RedisTimeBasedBloomFilterService）
            String nodeId = redisTimeBasedBloomFilterService.getNodeId();

            // 获取服务状态
            boolean localServiceActive = redisTimeBasedBloomFilterService != null;
            boolean redisServiceActive = redisTimeBasedBloomFilterService != null;
            boolean streamServiceActive = true; // 由统一服务内部发布

            // 获取统计信息
            String localStats = localServiceActive ? redisTimeBasedBloomFilterService.getLocalStats() : "服务未激活";
            String redisStats = redisServiceActive ? redisTimeBasedBloomFilterService.getRedisStats() : "服务未激活";

            // 获取系统信息
            Runtime runtime = Runtime.getRuntime();
            long totalMemory = runtime.totalMemory();
            long freeMemory = runtime.freeMemory();
            long usedMemory = totalMemory - freeMemory;
            long maxMemory = runtime.maxMemory();

            return NodeInfo.builder()
                    .nodeId(nodeId)
                    .timestamp(System.currentTimeMillis())
                    .localServiceActive(localServiceActive)
                    .redisServiceActive(redisServiceActive)
                    .streamServiceActive(streamServiceActive)
                    .localStats(localStats)
                    .redisStats(redisStats)
                    .memoryUsedMB(usedMemory / 1024 / 1024)
                    .memoryTotalMB(totalMemory / 1024 / 1024)
                    .memoryMaxMB(maxMemory / 1024 / 1024)
                    .memoryUsagePercent((double) usedMemory / maxMemory * 100)
                    .build();

        } catch (Exception e) {
            log.error("获取节点信息失败", e);
            return NodeInfo.builder()
                    .nodeId("unknown")
                    .timestamp(System.currentTimeMillis())
                    .localServiceActive(false)
                    .redisServiceActive(false)
                    .streamServiceActive(false)
                    .localStats("获取失败: " + e.getMessage())
                    .redisStats("获取失败: " + e.getMessage())
                    .memoryUsedMB(0L)
                    .memoryTotalMB(0L)
                    .memoryMaxMB(0L)
                    .memoryUsagePercent(0.0)
                    .build();
        }
    }

    public static class NodeInfo {
        private String nodeId;
        private long timestamp;
        private boolean localServiceActive;
        private boolean redisServiceActive;
        private boolean streamServiceActive;
        private String localStats;
        private String redisStats;
        private long memoryUsedMB;
        private long memoryTotalMB;
        private long memoryMaxMB;
        private double memoryUsagePercent;

        /**
         * 私有构造方法
         */
        public NodeInfo(){};
        /**
         * Build模式对属性赋值
         */
        public static Builder builder() {return new Builder();}
        public static class Builder {
            private NodeInfo nodeInfo = new NodeInfo();

            public Builder nodeId(String nodeId) {
                nodeInfo.nodeId = nodeId;
                return this;
            }

            public Builder timestamp(long timestamp) {
                nodeInfo.timestamp = timestamp;
                return this;
            }

            public Builder localServiceActive(boolean localServiceActive) {
                nodeInfo.localServiceActive = localServiceActive;
                return this;
            }

            public Builder redisServiceActive(boolean redisServiceActive) {
                nodeInfo.redisServiceActive = redisServiceActive;
                return this;
            }

            public Builder streamServiceActive(boolean streamServiceActive) {
                nodeInfo.streamServiceActive = streamServiceActive;
                return this;
            }

            public Builder localStats(String localStats) {
                nodeInfo.localStats = localStats;
                return this;
            }

            public Builder redisStats(String redisStats) {
                nodeInfo.redisStats = redisStats;
                return this;
            }

            public Builder memoryUsedMB(long memoryUsedMB) {
                nodeInfo.memoryUsedMB = memoryUsedMB;
                return this;
            }

            public Builder memoryTotalMB(long memoryTotalMB) {
                nodeInfo.memoryTotalMB = memoryTotalMB;
                return this;
            }

            public Builder memoryMaxMB(long memoryMaxMB) {
                nodeInfo.memoryMaxMB = memoryMaxMB;
                return this;
            }

            public Builder memoryUsagePercent(double memoryUsagePercent) {
                nodeInfo.memoryUsagePercent = memoryUsagePercent;
                return this;
            }

            public NodeInfo build() {return nodeInfo;}

        }
        // Getter方法
        public String getNodeId() { return nodeId; }
        public long getTimestamp() { return timestamp; }
        public boolean isLocalServiceActive() { return localServiceActive; }
        public boolean isRedisServiceActive() { return redisServiceActive; }
        public boolean isStreamServiceActive() { return streamServiceActive; }
        public String getLocalStats() { return localStats; }
        public String getRedisStats() { return redisStats; }
        public long getMemoryUsedMB() { return memoryUsedMB; }
        public long getMemoryTotalMB() { return memoryTotalMB; }
        public long getMemoryMaxMB() { return memoryMaxMB; }
        public double getMemoryUsagePercent() { return memoryUsagePercent; }

        @Override
        public String toString() {
            return String.format(
                    "NodeInfo{nodeId='%s', timestamp=%d, services=[local=%s, redis=%s, stream=%s], " +
                            "memory=[used=%dMB, total=%dMB, max=%dMB, usage=%.2f%%], " +
                            "localStats='%s', redisStats='%s'}",
                    nodeId, timestamp, localServiceActive, redisServiceActive, streamServiceActive,
                    memoryUsedMB, memoryTotalMB, memoryMaxMB, memoryUsagePercent,
                    localStats, redisStats
            );
        }
    }
}
