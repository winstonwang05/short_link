package com.winston.shortlink.config;

import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import lombok.AllArgsConstructor;
import org.springframework.context.annotation.Configuration;


@AllArgsConstructor
@Configuration
public class MonitoringConfig {
    
    private final MeterRegistry meterRegistry;
    
    @PostConstruct
    public void configureMetrics() {
        // 自定义业务指标
        meterRegistry.gauge("shortlink.cache.hit.ratio", this, MonitoringConfig::getCacheHitRatio);
        meterRegistry.gauge("shortlink.database.connections", this, MonitoringConfig::getDatabaseConnections);
    }
    
    private double getCacheHitRatio() {
        // 实现缓存命中率计算逻辑
        return 0.95; // 示例值
    }
    
    private double getDatabaseConnections() {
        // 实现数据库连接数统计逻辑
        return 100; // 示例值
    }
}