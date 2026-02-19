package com.winston.shortlink.service;

import org.springframework.util.StringUtils;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.winston.shortlink.entity.ShortUrlMapping;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static com.winston.shortlink.constant.RedisKeyConstants.*;

/**
 * @description: Redis集群感知服务
 * @author: Winston
 * @date: 2026/2/9 22:29
 * @version: 1.0
 */

@Slf4j
@Service
@RequiredArgsConstructor
public class ClusterAwareCacheService {

    private final RedissonClient redissonClient;
    private final ObjectMapper objectMapper;
    private final LocalCacheService localCacheService;
    private final ShardingStrategyService shardingStrategyService;
    private final TieredBloomFilterService tieredBloomFilterService;

    @Value("${shortlink.cluster.batch-size:50}")
    private int batchSize;

    @Value("${shortlink.cluster.enable-hash-tag:true}")
    private boolean enableHashTag;

    /**
     * 获取缓存数据
     * @param shortCode 作为key查询
     * @return 返回缓存数据
     */
    public ShortUrlMapping getFromCache(String shortCode) {
        if (!StringUtils.hasText(shortCode)) {
            log.warn("shortCode 为空，无法获取缓存");
            return null;
        }
        // 1.先从本地缓存获取
        ShortUrlMapping shortUrlMapping = localCacheService.getFromLocalCache(shortCode);
        if (shortUrlMapping != null) {
            log.debug("本地缓存命中： {}", shortUrlMapping);
            return shortUrlMapping;
        }
        // 2.本地缓存未命中，从Redis集群获取
        shortUrlMapping = getFromRedisCluster(shortCode);
        // 3.写入本地缓存
        if (shortUrlMapping != null) {
            log.debug("Redis集群缓存命中: {}, 分片槽位: {}",
                    shortCode, shardingStrategyService.calculateSlot(shortCode));
            localCacheService.safePutToLocalCache(shortCode, shortUrlMapping);
        }
        return shortUrlMapping;
    }

    /**
     * 将短链信息放入集群
     * @param shortCode 短码
     * @param shortUrlMapping 短链信息
     */
    public void putToCache(String shortCode, ShortUrlMapping shortUrlMapping) {
        if (!StringUtils.hasText(shortCode) || shortUrlMapping == null) {
            log.warn("参数为空，跳过缓存操作: shortCode={}", shortCode);
            return;
        }
        // 1.放入本地缓存
        localCacheService.safePutToLocalCache(shortCode, shortUrlMapping);
        // 2.放入集群中
        cacheToRedisCluster(shortCode, shortUrlMapping);

        log.debug("缓存短链信息到集群：{}， 分片槽位: {}",
                shortUrlMapping, shardingStrategyService.calculateSlot(shortCode));
    }

    /**
     * 批量从缓存中获取数据
     * @param shortCodes 短码
     * @return 返回批量数据
     */
    public Map<String, ShortUrlMapping> batchGetFromCache(List<String> shortCodes) {
        if (shortCodes == null || shortCodes.isEmpty()) {
            return Collections.emptyMap();
        }
        Map<String, ShortUrlMapping> shortUrlMappingMap = new HashMap<>();
        List<String> missedCodes = new ArrayList<>();
        // 1.先从本地缓存批量获取
        for (String shortCode : shortCodes) {
            ShortUrlMapping shortUrlMapping = localCacheService.getFromLocalCache(shortCode);
            if (shortUrlMapping != null) {
                shortUrlMappingMap.put(shortCode, shortUrlMapping);
            } else {
                missedCodes.add(shortCode);
            }
        }
        // 2.将未从本地缓存获取的批量去Redis集群查询，并写入本地缓存
        if  (!missedCodes.isEmpty()) {
            Map<String, ShortUrlMapping> stringShortUrlMappingMap = batchGetFromRedisCluster(missedCodes);
            shortUrlMappingMap.putAll(stringShortUrlMappingMap);

            stringShortUrlMappingMap.forEach(localCacheService :: safePutToLocalCache);
        }
        // 3.返回
        log.debug("批量获取缓存: 请求={}, 本地命中={}, Redis命中={}",
                shortCodes.size(), shortCodes.size() - missedCodes.size(),
                shortUrlMappingMap.size() - (shortCodes.size() - missedCodes.size()));

        return shortUrlMappingMap;
    }

    /**
     * 批量放入缓存
     * @param shortUrlMappingMap 数据
     */
    public void batchPutToCache(Map<String, ShortUrlMapping> shortUrlMappingMap) {
        if (shortUrlMappingMap == null || shortUrlMappingMap.isEmpty()) {
            return;
        }
        // 1.批量放入本地缓存
        shortUrlMappingMap.forEach(localCacheService :: safePutToLocalCache);
        // 2.批量放入Redis集群
        batchCacheToRedisCluster(shortUrlMappingMap);

        log.debug("批量缓存到集群: 数量={}", shortUrlMappingMap.size());
    }

    /**
     * 增加访问次数到分片集群
     * @param shortCode 短码
     * @return 返回该key下的数量
     */
    public Long incrementAccessCount(String shortCode) {
        try {
            // 1.拼接key
            String key = generateHashTagKey(shortCode, COUNT_CACHE_KEY);

            // 2.获取原子长整型对象
            RAtomicLong atomicLong = redissonClient.getAtomicLong(key);
            // 3.计数并续期
            long count = atomicLong.incrementAndGet();
            atomicLong.expire(COUNT_EXPIRE_TIME);
            log.debug("访问计数增加: {}, 当前计数: {}, 分片槽位: {}",
                    shortCode, count, shardingStrategyService.calculateSlot(key));

            return count;
        } catch (Exception e) {
            log.error("增加访问计数失败: shortCode={}, error={}", shortCode, e.getMessage());
            return null;
        }
    }

    /**
     * 获取访问次数
     * @param shortCode 短码
     * @return 返回该桶下的次数
     */
    public Long getAccessCount(String shortCode) {
        try {
            // 1.拼接key
            String key = generateHashTagKey(shortCode, COUNT_CACHE_KEY);
            // 2.获取原子长整型对象
            RAtomicLong atomicLong = redissonClient.getAtomicLong(key);
            // 3.取出数据
            long count = atomicLong.get();
            return count;
        } catch (Exception e) {
            log.error("获取访问计数失败: shortCode={}, error={}", shortCode, e.getMessage());
            return 0L;
        }
    }


    /**
     * 原链接哈希值映射，key就是原链接哈希，value是shortCode
     * @param originalUrlHash 原链接哈希值
     * @param shortCode 短码
     */
    public void putUrlHashMapping(String originalUrlHash, String shortCode) {
        try {
            // 1.拼接key
            String hashTagKey = generateHashTagKey(originalUrlHash, HASH_MAPPING_KEY);
            // 2.获取桶对像
            RBucket<Object> bucket = redissonClient.getBucket(hashTagKey);
            // 3.存入分片集群
            bucket.set(shortCode, DEFAULT_EXPIRE_TIME);

            log.debug("缓存URL哈希映射: hash={}, shortCode={}, 分片槽位: {}",
                    originalUrlHash, shortCode, shardingStrategyService.calculateSlot(hashTagKey));
        } catch (Exception e) {
            log.error("缓存URL哈希映射失败: hash={}, error={}", originalUrlHash, e.getMessage());
        }

    }

    /**
     * 从集群中获取URL哈希映射--短码
     * @param originalUrlHash 原始URL哈希
     * @return 返回映射结果
     */
    public String getShortCodeByUrlHash(String originalUrlHash) {
        try {
            // 1.拼接key
            String key = generateHashTagKey(originalUrlHash, HASH_MAPPING_KEY);
            // 2.获取桶对象
            RBucket<String> bucket = redissonClient.getBucket(key);
            // 3.获取映射结果
            String shortCode = bucket.get();
            return shortCode;
        } catch (Exception e) {
            log.error("获取URL哈希映射失败: hash={}, error={}", originalUrlHash, e.getMessage());
            return null;
        }
    }

    /**
     * 删除URL哈希映射
     * @param originalUrlHash 原始URL哈希
     */
    public void removeUrlHashMapping(String originalUrlHash) {
        try {
            String key = generateHashTagKey(originalUrlHash, HASH_MAPPING_KEY);
            RBucket<String> bucket = redissonClient.getBucket(key);
            bucket.delete();
        }  catch (Exception e) {
            log.error("删除URL哈希映射失败: hash={}, error={}", originalUrlHash, e.getMessage());
        }
    }

    /**
     * 批量清除缓存
     * @param shortCode 带短码
     */
    public void evictCache (String shortCode) {
        try {
            // 1.拼接key
            String countKey = generateHashTagKey(shortCode, COUNT_CACHE_KEY);
            String urlKey = generateHashTagKey(shortCode, HASH_MAPPING_KEY);
            // 2.创建管道对象批量删除
            RBatch batch = redissonClient.createBatch();
            batch.getBucket(countKey).deleteAsync();
            batch.getBucket(urlKey).deleteAsync();
            // 3.执行
            batch.execute();
            log.debug("清除集群缓存: {}", shortCode);
        } catch (Exception e) {
            log.error("清除集群缓存失败: shortCode={}, error={}", shortCode, e.getMessage());
        }
    }

    /**
     * 布隆过滤器检查
     */
    public boolean existsInBloomFilter(String shortCode) {
        return tieredBloomFilterService.mightContain(shortCode);
    }

    /**
     * 添加到布隆过滤器
     */
    public void addToBloomFilter(String shortCode) {
        tieredBloomFilterService.put(shortCode);
    }







    // ====================私有方法==========================

    public String generateHashTagKey(String shortCode, String prefix) {
        if  (enableHashTag) {
            return prefix + "{" + shortCode + "}";
        }
        return prefix + shortCode;
    }

    /**
     * 判断是否为热点数据
     */
    private boolean isHotData(ShortUrlMapping shortUrlMapping) {
        return shortUrlMapping.getAccessCount() != null && shortUrlMapping.getAccessCount() > 1000;
    }

    /**
     * 从Redis集群中获取缓存数据
     * @param shortCode 短码
     * @return 返回缓存数据
     */
    public ShortUrlMapping getFromRedisCluster(String shortCode) {
        try {
            // 1.拼接HashTag key
            String key = generateHashTagKey(shortCode, URL_CACHE_KEY);
            // 2.从Redis客户端得到桶对象
            RBucket<String> bucket = redissonClient.getBucket(key);
            // 3.从桶中获取数据
            String json = bucket.get();
            if  (json != null) {
                return objectMapper.readValue(json, ShortUrlMapping.class);
            }
        } catch (JsonProcessingException e) {
            log.error("Redis集群反序列化失败: shortCode={}, error={}", shortCode, e.getMessage());
        } catch (Exception e) {
            log.error("Redis集群查询失败: shortCode={}, error={}", shortCode, e.getMessage());
        }
        return null;
    }

    /**
     * 缓存到Redis
     * @param shortCode 短码
     * @param shortUrlMapping 实体类
     */
    public void cacheToRedisCluster(String shortCode, ShortUrlMapping shortUrlMapping) {
        try {
            // 1.拼接Hash Tag Key
            String key = generateHashTagKey(shortCode, URL_CACHE_KEY);
            // 2.将实体类反序列化为Json数据
            String json = objectMapper.writeValueAsString(shortUrlMapping);
            // 3.获取桶对象
            RBucket<Object> bucket = redissonClient.getBucket(key);
            // 4.判断是否是热点数据存入缓存
            Duration expireTime = isHotData(shortUrlMapping) ? HOT_DATA_EXPIRE_TIME : DEFAULT_EXPIRE_TIME;
            bucket.set(json, expireTime);
            log.debug("Redis集群缓存成功: {}", shortCode);
        } catch (JsonProcessingException e) {
            log.error("Redis集群序列化失败: shortCode={}, error={}", shortCode, e.getMessage());
        }  catch (Exception e) {
            log.error("Redis集群缓存失败: shortCode={}, error={}", shortCode, e.getMessage());
        }
    }

    /**
     * 批量从Redis缓存获取数据
     * @param shortCodes 短码
     * @return 返回批量缓存结果
     */
    public Map<String, ShortUrlMapping> batchGetFromRedisCluster(List<String> shortCodes) {
        Map<String, ShortUrlMapping> result = new HashMap<>();
        try {
            // 1.将短码分组，将在同一个插槽的短码作为Value存储到Map集合中
            Map<Integer, List<String>> shardGroup = shortCodes.stream()
                    .collect(Collectors.groupingBy(code ->
                            shardingStrategyService
                                    .calculateSlot(generateHashTagKey(code, URL_CACHE_KEY))));
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            // 2.遍历Map集合的EntrySet
            for  (Map.Entry<Integer, List<String>> entry : shardGroup.entrySet()) {
                // 3.异步任务，有多少个EntrySet就多少个任务，由异步线程池完成
                CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                    // 4.获取操作批量对象，管道对象，以及异步桶对象
                    RBatch batch = redissonClient.createBatch();
                    Map<String, RBucketAsync<String>> bucketAsyncMap = new HashMap<>();
                    // 5.遍历Entry将短码作为key，异步桶对象作为value
                    for (String shortCode : entry.getValue()) {
                        // 拼接HashTag Key
                        String hashTagKey = generateHashTagKey(shortCode, URL_CACHE_KEY);
                        bucketAsyncMap.put(shortCode, batch.getBucket(hashTagKey));
                    }
                    // 6.打包执行
                    BatchResult<?> batchResult = batch.execute();

                    // 7.遍历每一包放入结果集
                    for (Map.Entry<String, RBucketAsync<String>> bucketEntry : bucketAsyncMap.entrySet()) {
                        try {
                            String json = (String) batchResult.getResponses().get(
                                    new ArrayList<>(bucketAsyncMap.values()).indexOf(bucketEntry.getValue())
                            );
                            if (json != null) {
                                ShortUrlMapping shortUrlMapping = objectMapper.readValue(json, ShortUrlMapping.class);
                                synchronized (result) {
                                    result.put(bucketEntry.getKey(), shortUrlMapping);
                                }
                            }
                        } catch (Exception e) {
                            log.error("批量获取解析失败: shortCode={}, error={}",
                                    bucketEntry.getKey(), e.getMessage());
                        }
                    }
                });
                futures.add(future);
            }
            // 等待所有批量操作完成
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(5, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.error("批量从Redis集群获取失败: error={}", e.getMessage());
        }

        return result;

    }

    /**
     * 批量缓存到Redis集群中
     * @param shortUrlMappings 需要缓存的实体数据
     */
    public void batchCacheToRedisCluster(Map<String, ShortUrlMapping> shortUrlMappings) {
        try {
            Map<Integer, Map<String, ShortUrlMapping>> shardGroup = new HashMap<>();
            // 1.分片分组，key是插槽，Value是需要存储的Map集合
            // 1.1.拼接key计算插槽
            for (Map.Entry<String, ShortUrlMapping> entry : shortUrlMappings.entrySet()) {
                String shortCode = entry.getKey();
                String hashTagKey = generateHashTagKey(shortCode, URL_CACHE_KEY);
                int slot = shardingStrategyService.calculateSlot(hashTagKey);
                shardGroup.computeIfAbsent(slot, k -> new HashMap<>())
                        .put(entry.getKey(), entry.getValue());
            }
            // 2.创建异步任务执行
            List<CompletableFuture<Void>> futures = new ArrayList<>();
            for (Map.Entry<Integer, Map<String, ShortUrlMapping>> entry : shardGroup.entrySet()) {
                CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                    RBatch batch = redissonClient.createBatch();
                    // 3.遍历同一个插槽的Map集合将数据异步存储到Redis集群
                    for (Map.Entry<String, ShortUrlMapping> mappingEntry : entry.getValue().entrySet()) {
                        try {
                            String shortCode = mappingEntry.getKey();
                            String hashTagKey = generateHashTagKey(shortCode, URL_CACHE_KEY);
                            String json = objectMapper.writeValueAsString(mappingEntry.getValue());
                            Duration expireTime = isHotData(mappingEntry.getValue())
                                    ? HOT_DATA_EXPIRE_TIME
                                    : DEFAULT_EXPIRE_TIME;
                            batch.getBucket(hashTagKey).setAsync(json, expireTime);
                        } catch (Exception e) {
                            log.error("批量缓存序列化失败: shortCode={}, error={}",
                                    entry.getKey(), e.getMessage());
                        }
                    }
                    batch.execute();
                });
                futures.add(future);
            }

            // 等待所有批量操作完成
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .get(5, TimeUnit.SECONDS);

        } catch (Exception e) {
            log.error("批量缓存到Redis集群失败: error={}", e.getMessage());
        }
    }

}
