package com.winston.shortlink.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * 双写异步执行器配置
 */
@Configuration
@EnableAsync
public class DualWriteExecutorConfig {

    @Value("${shortlink.dualwrite.threadpool.corePoolSize:10}")
    private int corePoolSize;
    @Value("${shortlink.dualwrite.threadpool.maxPoolSize:20}")
    private int maxPoolSize;
    @Value("${shortlink.dualwrite.threadpool.queueCapacity:1000}")
    private int queueCapacity;
    @Value("${shortlink.dualwrite.threadpool.threadNamePrefix:dual-write-}")
    private String threadNamePrefix;

    @Bean("dualWriteExecutor")
    public Executor dualWriteExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();

        // 核心线程数
        executor.setCorePoolSize(corePoolSize);

        // 最大线程数
        executor.setMaxPoolSize(maxPoolSize);

        // 队列容量
        executor.setQueueCapacity(queueCapacity);

        // 线程名前缀
        executor.setThreadNamePrefix(threadNamePrefix);

        // 拒绝策略：调用者运行
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());

        // 等待任务完成后关闭
        executor.setWaitForTasksToCompleteOnShutdown(true);

        // 等待时间
        executor.setAwaitTerminationSeconds(60);

        executor.initialize();
        return executor;
    }
}