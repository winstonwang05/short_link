/*
package com.winston.shortlink.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.winston.shortlink.entity.ShortUrlMapping;
import lombok.AllArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;

*/
/**
 * @description: 多级缓存服务 本地缓存 + Redis缓存
 * @author: Winston
 * @date: 2026/2/1 12:30
 * @version: 1.0
 *//*

@Service
@AllArgsConstructor
public class CacheService {

    private static final Logger logger = LoggerFactory.getLogger(CacheService.class);

    private static final String CACHE_PREFIX = "shortlink:";
    private static final String URL_CACHE_KEY = CACHE_PREFIX + "url:";
    private static final String COUNT_CACHE_KEY = CACHE_PREFIX + "count:";
    private static final String BLOOM_FILTER_KEY = CACHE_PREFIX + "bloom";

    private static final Duration DEFAULT_EXPIRE_TIME = Duration.ofHours(1);
    private static final Duration HOT_DATA_EXPIRE_TIME = Duration.ofHours(24);

    private final RedisTemplate<String, String> redisTemplate;
    private final ObjectMapper objectMapper;
    */
/**
     * 统一缓存接口 - 从多级缓存中获取短链信息
     * 先查询本地缓存，再查询Redis缓存
     *//*

    public ShortUrlMapping getFromCathe(String shortCode) {
        // 先从本地缓存获取
        ShortUrlMapping shortUrlMapping = getFromLocalCache(shortCode);
        if (shortUrlMapping != null) {
            logger.debug("本地缓存命中： {}", shortCode);
            return shortUrlMapping;
        }
        // 本地缓存不存在，查询Redis缓存
        shortUrlMapping = getFromRedis(shortCode);
        if (shortUrlMapping != null) {
            logger.debug("Redis缓存命中： {}", shortCode);
            // 将Redis缓存放入本地缓存
            putToLocalCathe(shortCode, shortUrlMapping);
        }
        return shortUrlMapping;
    }

    */
/**
     * 统一放入缓存，将短链信息放入多级缓存
     * @param shortCode 短链编码
     * @param shortUrlMapping 短链信息
     *//*

    public void putToCache(String shortCode, ShortUrlMapping shortUrlMapping) {
        // 放入本地缓存
        putToLocalCathe(shortCode, shortUrlMapping);
        // 放入Redis缓存
        cacheToRedis(shortCode, shortUrlMapping);
        logger.debug("缓存短链信息：{}", shortCode);
    }

    */
/**
     * 将短链信息放入Redis缓存
     * @param shortCode 短链编码
     * @param shortUrlMapping 短链信息实体类
     *//*

    private void cacheToRedis(String shortCode, ShortUrlMapping shortUrlMapping) {
        try {
            // 拼接key
            String key = URL_CACHE_KEY + shortCode;
            // 将实体类转化为Json数据
            String json = objectMapper.writeValueAsString(shortUrlMapping);
            // 存入redis中
            // 如果是热点信息，缓存的时间越长
            Duration expireTime = (isHotData(shortUrlMapping)) ? HOT_DATA_EXPIRE_TIME : DEFAULT_EXPIRE_TIME;
            redisTemplate.opsForValue().set(key, json, expireTime);
            logger.debug("Redis缓存成功 ： {}", shortCode);
        } catch (JsonProcessingException e) {
            logger.error("Redis反序列化失败： {}", e.getMessage());
        } catch (Exception e) {
            logger.error("Redis缓存失败： {}", e.getMessage());
        }
    }

    */
/**
     * 判断是否是热点数据
     * @param shortUrlMapping 实体类
     * @return 返回是否是热点数据
     *//*

    public boolean isHotData(ShortUrlMapping shortUrlMapping) {
        return shortUrlMapping.getAccessCount() > 1000;
    }

    */
/**
     * 从Redis缓存中查询
     * @param shortCode 短链编码
     * @return 返回短链信息
     *//*

    public ShortUrlMapping getFromRedis(String shortCode) {
        try {
            String key = URL_CACHE_KEY + shortCode;
            String json = redisTemplate.opsForValue().get(key);
            if (json != null) {
                logger.debug("Redis缓存命中");
                return objectMapper.readValue(json, ShortUrlMapping.class);
            }
        } catch (JsonProcessingException e) {
            logger.error("Redis反序列化失败： {}", e.getMessage());
        } catch (Exception e) {
            logger.error("Redis查询失败: {}", e.getMessage());
        }
        return null;
    }

    */
/**
     * Redis中的缓存放入本地缓存
     * @param shortCode 短链编码
     * @param shortUrlMapping 短链信息实体类
     * @return 返回短链信息实体类
     *//*

    @CachePut(value = "shortUrls", key = "#shortCode")
    public ShortUrlMapping putToLocalCathe(String shortCode, ShortUrlMapping shortUrlMapping) {
        logger.debug("放入本地缓存 ： {}", shortCode);
        return shortUrlMapping;
    }

    */
/**
     * 从本地缓存中获取短链信息
     * @param shortCode 短链编码
     * @return 返回实体类
     *//*

    @Cacheable (value = "shortUrls", key = "#shortCode")
    public ShortUrlMapping getFromLocalCache(String shortCode) {
        return null;
    }

    */
/**
     * 缓存访问次数
     * @param shortCode 短链编码
     * @param count 需要存储到缓存的数量
     *//*

    public void cacheAccessCount(String shortCode, Long count) {
    try {
        // 拼接key
        String key = COUNT_CACHE_KEY + shortCode;
        // 存储到redis中
        redisTemplate.opsForValue().set(key, count.toString(), DEFAULT_EXPIRE_TIME);
    } catch (Exception e) {
        logger.error("缓存访问数量失败： {}", e.getMessage());
        }

    }

    */
/**
     * 获取缓存访问数量
     * @param shortCode 短链编码
     * @return 返回缓存访问数量
     *//*

    public Long getCacheAccessCount(String shortCode) {
        try {
            // 拼接key
            String key = COUNT_CACHE_KEY + shortCode;
            // 从Redis中获取数量
            String count = redisTemplate.opsForValue().get(key);
            return count == null ? null : Long.valueOf(count);
        } catch (Exception e) {
            logger.error("获取缓存访问数量失败， {}", e.getMessage());
            return null;
        }
    }

    */
/**
     * 增加访问次数缓存
     * @param shortCode 短链编码
     * @return 返回增加后的结果
     *//*

    public Long incrementAccessCount(String shortCode) {
        try {
            String key = COUNT_CACHE_KEY + shortCode;
            return redisTemplate.opsForValue().increment(key);
        } catch (Exception e) {
            logger.error("增加访问次数失败: {}", e.getMessage());
            return null;
        }
    }

    */
/**
     * 预热缓存
     * @param shortCode 短链编码
     * @param shortUrlMapping 短链信息实体类
     *//*

    public void warmUpCache(String shortCode, ShortUrlMapping shortUrlMapping) {
        // 预热到Redis缓存
        cacheToRedis(shortCode, shortUrlMapping);
        // 访问缓存数量
        cacheAccessCount(shortCode, shortUrlMapping.getAccessCount());
    }

    public void batchWarmUp(List<ShortUrlMapping> shortUrlMappings) {
        shortUrlMappings.parallelStream().forEach(shortUrlMapping -> {
            try {
                warmUpCache(shortUrlMapping.getShortCode(), shortUrlMapping);
            } catch (Exception e) {
                logger.error("批量预热缓存失败: {}", e.getMessage());
            }
        });
    }

    */
/**
     * 布隆过滤器检查
     * @param shortCode 短码
     * @return 返回布尔
     *//*

    public boolean mightExist(String shortCode) {
        try {
            return redisTemplate.opsForSet().isMember(BLOOM_FILTER_KEY, shortCode);
        } catch (Exception e) {
            logger.error("布隆过滤器检查失败: {}", e.getMessage());
            // 布隆过滤器失败时，为了安全起见返回true，允许查询继续
            return true;
        }
    }

    */
/**
     * 添加到布隆过滤器
     * @param shortCode 短链编码
     *//*

    public void addToBloomFilter(String shortCode) {
        try {
            redisTemplate.opsForSet().add(BLOOM_FILTER_KEY, shortCode);
            logger.debug("添加到布隆过滤器: {}", shortCode);
        } catch (Exception e) {
            logger.error("添加到布隆过滤器失败：: {}", e.getMessage());
        }
    }

    public boolean existsInBloomFilter(String shortCode) {
        return mightExist(shortCode);
    }

    */
/**
     * 删除缓存
     * @param shortCode 短码
     *//*

    @CacheEvict(value = "shortUrls", key = "#shortCode")
    public void evictCache(String shortCode) {
        try {
            // 拼接key
            String urlKey = URL_CACHE_KEY + shortCode;
            String countKey = COUNT_CACHE_KEY + shortCode;
            // 删除缓存
            redisTemplate.delete(urlKey);
            redisTemplate.delete(countKey);
            logger.debug("删除缓存： {}", shortCode);
        } catch (Exception e) {
            logger.error("删除缓存失败：{}", e.getMessage());
        }
    }
    */
/**
     * 清理过期缓存
     *//*

    public void cleanExpiredCache() {
        // 这里可以实现定时清理过期缓存的逻辑
        logger.info("开始清理过期缓存");
        // 实际实现可以扫描Redis中的过期键并删除
    }

    */
/**
     * 获取缓存统计信息
     *//*

    public String getCacheStats() {
        // 返回缓存命中率等统计信息
        return "缓存统计信息 - 待实现";
    }

}
*/
