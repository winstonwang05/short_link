package com.winston.shortlink.filter;

import com.google.common.hash.BloomFilter;
import com.google.common.hash.Funnels;
import lombok.extern.slf4j.Slf4j;

import java.nio.charset.Charset;
import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicLong;

/**
 * @description: 单个时间片的布隆过滤器
 * @author: Winston
 * @date: 2026/2/11 22:17
 * @version: 1.0
 */
@Slf4j
public class TimeSliceBloomFilter {
    /**
     * 时间片的唯一标识
     */
    private final String sliceKey;

    private final BloomFilter<String> bloomFilter;

    private final LocalDateTime createTime;

    // 每个时间片预期容量（6小时 * 1万TPS * 3600秒 = 2.16亿）
    private static final long EXPECTED_INSERTIONS = 216_000_000L;
    /**
     * 允许的误判率
     */
    private static final double FALSE_PROBABILITY = 0.01;
    /**
     * 计数器，统计实际存了多少数据
     */
    private final AtomicLong elementCount = new AtomicLong(0);

    public TimeSliceBloomFilter (String sliceKey) {
        this.sliceKey = sliceKey;
        this.createTime = LocalDateTime.now();
        this.bloomFilter = BloomFilter.create(
                Funnels.stringFunnel(Charset.defaultCharset()),
                EXPECTED_INSERTIONS,
                FALSE_PROBABILITY
        );
        log.info("创建时间片布隆过滤器: {}, 预期容量: {}, 误判率: {}",
                sliceKey, EXPECTED_INSERTIONS, FALSE_PROBABILITY);
    }

    public boolean mightContain (String shortCode) {
        return bloomFilter.mightContain(shortCode);
    }

    public void add (String shortCode) {
        bloomFilter.put(shortCode);
        elementCount.incrementAndGet();
    }

    public long getApproximateElementCount() {
        return elementCount.get();
    }

    public String getSliceKey() {
        return sliceKey;
    }

    public LocalDateTime getCreateTime() {
        return createTime;
    }

    public double getCurrentFalseProbability() {
        return bloomFilter.expectedFpp();
    }

}
