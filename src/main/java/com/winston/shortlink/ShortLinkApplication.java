package com.winston.shortlink;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/**
 * 短链系统启动类
 * 支持100万QPS的高性能短链服务
 * @author Winston
 */
@EnableTransactionManagement // 添加事务管理
@EnableAsync // 开启异步任务
@EnableCaching // 开启缓存支持
@EnableScheduling // 开启定时任务
@SpringBootApplication
public class ShortLinkApplication {

    public static void main(String[] args) {
        SpringApplication.run(ShortLinkApplication.class, args);
    }

}
