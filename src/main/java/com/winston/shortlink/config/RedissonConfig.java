package com.winston.shortlink.config;

import lombok.Data;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.redisson.config.ReadMode;
import org.redisson.config.SubscriptionMode;
import org.redisson.connection.balancer.RoundRobinLoadBalancer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * @description: 分布式管理Redis集群
 * @author: Winston
 * @date: 2026/2/8 21:59
 * @version: 1.0
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "spring.data.redis")
public class RedissonConfig {

    private String password;
    private Cluster cluster;

    @Data
    public static class Cluster {
        private List<String> nodes;
        private int maxRedirects;
    }

    @Value("${shortlink.cluster.enable-read-write-split:true}")
    private boolean enableReadWriteSplit;

    @Value("${shortlink.cluster.connection-timeout:10000}")
    private int connectionTimeout;

    @Value("${shortlink.cluster.socket-timeout:10000}")
    private int socketTimeout;


    @Bean
    public RedissonClient redissonClient() {
        Config config = new Config();

        String[] nodes = cluster.getNodes().stream()
                .map(node -> "redis://" + node)
                .toArray(String[]::new);

        config.useClusterServers()
                .addNodeAddress(nodes)
                .setPassword(password)
                // 连接池配置 - 针对集群分片优化
                .setMasterConnectionMinimumIdleSize(20)
                .setMasterConnectionPoolSize(100)
                .setSlaveConnectionMinimumIdleSize(20)
                .setSlaveConnectionPoolSize(100)
                // 超时配置
                .setIdleConnectionTimeout(10000)
                .setConnectTimeout(connectionTimeout)
                .setTimeout(socketTimeout)
                // 重试配置
                .setRetryAttempts(3)
                .setRetryInterval(1500)
                // 集群扫描配置
                .setScanInterval(2000)
                // 读写模式配置 - 支持读写分离
                .setReadMode(enableReadWriteSplit ? ReadMode.SLAVE : ReadMode.MASTER)
                .setSubscriptionMode(SubscriptionMode.MASTER)
                // 负载均衡
                .setLoadBalancer(new RoundRobinLoadBalancer())
                // 集群故障转移
                .setFailedSlaveReconnectionInterval(3000)
                .setFailedSlaveCheckInterval(60000)
                // 启用集群拓扑刷新
                .setCheckSlotsCoverage(false)
                // 集群分片优化
                .setPingConnectionInterval(30000)
                .setKeepAlive(true);

        return Redisson.create(config);
    }

}
