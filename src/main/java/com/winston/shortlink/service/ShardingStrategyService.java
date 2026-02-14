package com.winston.shortlink.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;

import static com.winston.shortlink.constant.RedisKeyConstants.REDIS_CLUSTER_SLOTS;

/**
 * @description: Redis集群分片策略服务
 * @author: Winston
 * @date: 2026/2/10 16:21
 * @version: 1.0
 */
@Slf4j
@Service
public class ShardingStrategyService {


    /**
     * 从key中提取Hash Tag键
     * @param key key
     * @return 返回Hash Tag键
     */
    private String extractHashTag(String key) {
        // 提取{}中的字符串
        int start = key.indexOf('{');
        if (start != -1) {
            int end = key.indexOf('}', start + 1);
            while (end != -1 && end != start + 1) {
                return key.substring(start + 1, end);
            }
        }
        return key;
    }

    /**
     * CRC16算法实现（与Redis集群一致）
     */
    private int crc16(byte[] bytes) {
        int crc = 0x0000;
        int polynomial = 0x1021;

        for (byte b : bytes) {
            for (int i = 0; i < 8; i++) {
                boolean bit = ((b >> (7 - i) & 1) == 1);
                boolean c15 = ((crc >> 15 & 1) == 1);
                crc <<= 1;
                if (c15 ^ bit) {
                    crc ^= polynomial;
                }
            }
        }

        return crc & 0xffff;
    }

    /**
     * 计算Redis集群插槽
     * @param key key
     * @return 返回存储的插槽位置
     */
    public int calculateSlot(String key) {
        // 1.非空判断
        if (key == null ||  key.isEmpty()) {
            return 0;
        }
        // 2.提取Hash Tag
        String hashKey = extractHashTag(key);
        // 3.对总插槽取模
        return crc16(hashKey.getBytes(StandardCharsets.UTF_8)) % REDIS_CLUSTER_SLOTS;
    }

    /**
     * 生成Hash Tag Key
     * @param shortCode 短码
     * @param prefix 前缀
     * @return 返回Key
     */
    public String generateHashTagKey(String shortCode, String prefix) {
        return prefix + "{" + shortCode + "}";
    }

    /**
     * 计算分片分布统计
     */
    public ShardDistribution calculateShardDistribution(java.util.List<String> keys) {
        java.util.Map<Integer, Integer> distribution = new java.util.HashMap<>();

        for (String key : keys) {
            int slot = calculateSlot(key);
            distribution.merge(slot, 1, Integer::sum);
        }

        return new ShardDistribution(distribution);
    }

    /**
     * 分片分布统计结果
     */
    public static class ShardDistribution {
        private final java.util.Map<Integer, Integer> distribution;

        public ShardDistribution(java.util.Map<Integer, Integer> distribution) {
            this.distribution = distribution;
        }

        public java.util.Map<Integer, Integer> getDistribution() {
            return distribution;
        }

        public int getTotalKeys() {
            return distribution.values().stream().mapToInt(Integer::intValue).sum();
        }

        public double getAverageKeysPerShard() {
            return distribution.isEmpty() ? 0 : (double) getTotalKeys() / distribution.size();
        }

        public int getMaxKeysInShard() {
            return distribution.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        }

        public int getMinKeysInShard() {
            return distribution.values().stream().mapToInt(Integer::intValue).min().orElse(0);
        }
    }



}
