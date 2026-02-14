package com.winston.shortlink.constant;

import java.time.Duration;

/**
 * @description: Redis存储的常量类
 * @author: Winston
 * @date: 2026/2/9 11:55
 * @version: 1.0
 */
public class RedisKeyConstants {

    public static final String CACHE_PREFIX = "shortlink:";

    // 布隆过滤器相关的key
    public static final String BLOOM_FILTER_NAME = "shortlink_bloom_filter";
    public static final String BLOOM_SET_KEY = CACHE_PREFIX + "bloom";

    // 缓存相关key
    public static final String URL_CACHE_KEY = CACHE_PREFIX + "url:";
    public static final String COUNT_CACHE_KEY = CACHE_PREFIX + "count:";
    public static final String HASH_MAPPING_KEY = CACHE_PREFIX + "hash:";

    // 缓存的有效期
    public static final Duration DEFAULT_EXPIRE_TIME = Duration.ofHours(1);
    public static final Duration HOT_DATA_EXPIRE_TIME = Duration.ofHours(24);
    public static final Duration COUNT_EXPIRE_TIME = Duration.ofDays(7);

    // Redis Stream相关常量
    public static final String STREAM_KEY = "local_cache_stream";
    public static final String CONSUMER_GROUP = "cache_sync_group";


    //长链接hash值相关key
    public static final String URL_HASH_MAPPING_KEY = CACHE_PREFIX + "url_hash:";

    // 分片集群总插槽数
    public static final int REDIS_CLUSTER_SLOTS = 16384;

    /**
     * 机器相关key
     */
    public static final String MACHINE_ID_KEY = "shortlink:machine-id-registry";
    public static final String MACHINE_ID_LOCK = "shortlink:machine-id-lock";
    // 10位机器ID的最大值
    public static final long MAX_MACHINE_ID = 1023;
    public static final long MACHINE_ID_EXPIRE_TIME = 60;
}

