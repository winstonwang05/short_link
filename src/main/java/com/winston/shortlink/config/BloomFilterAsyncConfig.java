package com.winston.shortlink.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * @description: 布隆过滤器线程配置
 * @author: Winston
 * @date: 2026/2/8 23:04
 * @version: 1.0
 */
@Configuration
public class BloomFilterAsyncConfig {

    @Value("${shortlink.bloom.threadpool.corePoolSize:4}")
    private int corePoolSize;
    @Value("${shortlink.bloom.threadpool.maxPoolSize:8}")
    private int maxPoolSize;
    @Value("${shortlink.bloom.threadpool.queueCapacity:1000}")
    private int queueCapacity;
    @Value("${shortlink.bloom.threadpool.keepAliveSeconds:60}")
    private int keepAliveSeconds;
    @Value("${shortlink.bloom.threadpool.threadNamePrefix:bloom-filter-}")
    private String threadNamePrefix;
    @Value("${shortlink.bloom.threadpool.awaitTerminationSeconds:30}")
    private int awaitTerminationSeconds;


    /**
     * 异步执行方法
     * @return 配置的线程执行事件
     */
    @Bean("bloomFilterExecutor")
    public Executor bloomFilterExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(maxPoolSize);
        executor.setQueueCapacity(queueCapacity);
        executor.setKeepAliveSeconds(keepAliveSeconds);
        executor.setThreadNamePrefix(threadNamePrefix);
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(awaitTerminationSeconds);
        executor.initialize();
        return executor;
    }
}
