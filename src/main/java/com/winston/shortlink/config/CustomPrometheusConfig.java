package com.winston.shortlink.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.boot.actuate.autoconfigure.metrics.MeterRegistryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * @description: Prometheus自定义配置
 * @author: Winston
 * @date: 2026/2/19 10:01
 * @version: 1.0
 */
@Configuration
public class CustomPrometheusConfig {

    @Bean
    public MeterRegistryCustomizer<MeterRegistry> metricsCommonTags() {
        return registry -> {
            registry.config()
                    .commonTags("application", "short-link")
                    .commonTags("version", "1.0")
                    .commonTags("environment", "local");
        };
    }

    // 自定义业务指标
    @Bean
    public Counter shortLinkCreateCounter(MeterRegistry meterRegistry) {
        return Counter.builder("shortlink_create_total")
                .description("短链创建总数")
                .register(meterRegistry);
    }


    @Bean
    public Counter shortLinkAccessCounter(MeterRegistry meterRegistry) {
        return Counter.builder("shortlink_access_total")
                .description("短链访问总数")
                .register(meterRegistry);
    }

    @Bean
    public Timer shortLinkCreateTimer(MeterRegistry meterRegistry) {
        return Timer.builder("shortlink_create_duration")
                .description("短链创建耗时")
                .register(meterRegistry);
    }


}
