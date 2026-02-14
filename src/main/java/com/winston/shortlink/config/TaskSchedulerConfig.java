package com.winston.shortlink.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * @description: 任务调度器线程池配置
 * @author: Winston
 * @date: 2026/2/8 22:33
 * @version: 1.0
 */
@Configuration
public class TaskSchedulerConfig {


    @Value("${shortlink.scheduler.threadpool.poolSize:8}")
    private int poolSize;

    @Value("${shortlink.scheduler.threadpool.threadNamePrefix:scheduler-}")
    private String threadNamePrefix;

    @Value("${shortlink.scheduler.threadpool.awaitTerminationSeconds:30}")
    private int awaitTerminationSeconds;

    @Value("${shortlink.scheduler.threadpool.waitForTasksToCompleteOnShutdown:true}")
    private boolean waitForTasksToCompleteOnShutdown;

    @Bean("taskScheduler")
    public TaskScheduler taskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(poolSize);
        scheduler.setThreadNamePrefix(threadNamePrefix);
        scheduler.setWaitForTasksToCompleteOnShutdown(waitForTasksToCompleteOnShutdown);
        scheduler.setAwaitTerminationSeconds(awaitTerminationSeconds);
        scheduler.initialize();
        return scheduler;
    }
}
