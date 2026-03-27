package com.winston.shortlink.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.atomic.AtomicLong;

@Configuration
@RequiredArgsConstructor
public class MonitoringConfig {

    private final MeterRegistry meterRegistry;

    // 缓存命中/未命中计数
    private final AtomicLong cacheHitCount = new AtomicLong(0);
    private final AtomicLong cacheMissCount = new AtomicLong(0);

    @Getter
    private Counter createCounter;
    @Getter
    private Counter createFailCounter;
    @Getter
    private Counter accessCounter;
    @Getter
    private Timer createTimer;

    @PostConstruct
    public void configureMetrics() {
        // 短链创建计数器
        createCounter = Counter.builder("shortlink.create.total")
                .description("短链创建总次数")
                .register(meterRegistry);

        // 短链创建失败计数器
        createFailCounter = Counter.builder("shortlink.create.fail.total")
                .description("短链创建失败总次数")
                .register(meterRegistry);

        // 短链访问计数器
        accessCounter = Counter.builder("shortlink.access.total")
                .description("短链访问总次数")
                .register(meterRegistry);

        // 短链创建耗时
        createTimer = Timer.builder("shortlink.create.duration")
                .description("短链创建耗时")
                .register(meterRegistry);

        // 缓存命中率（动态计算）
        meterRegistry.gauge("shortlink.cache.hit.ratio", this, MonitoringConfig::getCacheHitRatio);
    }

    public void recordCacheHit() {
        cacheHitCount.incrementAndGet();
    }

    public void recordCacheMiss() {
        cacheMissCount.incrementAndGet();
    }

    private double getCacheHitRatio() {
        long hits = cacheHitCount.get();
        long total = hits + cacheMissCount.get();
        return total == 0 ? 0.0 : (double) hits / total;
    }
}
