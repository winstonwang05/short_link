package com.winston.shortlink.config;

import lombok.extern.slf4j.Slf4j;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.misc.RedisURI;
import org.redisson.config.Config;
import org.redisson.config.ReadMode;
import org.redisson.config.SubscriptionMode;
import org.redisson.connection.balancer.RoundRobinLoadBalancer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.URI;
import java.util.Arrays;
import java.util.List;

/**
 * @description: 分布式管理Redis集群
 * @author: Winston
 * @date: 2026/2/8 21:59
 * @version: 1.0
 */
@Slf4j
@Configuration
public class RedissonConfig {

    @Value("${shortlink.redisson.password:${spring.data.redis.password:}}")
    private String password;

    @Value("${shortlink.redisson.nodes:127.0.0.1:7001,127.0.0.1:7002,127.0.0.1:7003}")
    private String clusterNodes;

    @Value("${shortlink.cluster.enable-read-write-split:true}")
    private boolean enableReadWriteSplit;

    @Value("${shortlink.cluster.connection-timeout:10000}")
    private int connectionTimeout;

    @Value("${shortlink.cluster.socket-timeout:10000}")
    private int socketTimeout;

    @Value("${shortlink.redisson.nat-to-localhost:true}")
    private boolean natToLocalhost;


    @Bean
    public RedissonClient redissonClient() {
        Config config = new Config();

        List<String> parsedNodes = Arrays.stream(clusterNodes.split(","))
                .map(String::trim)
                .filter(node -> !node.isEmpty())
                .toList();

        String[] nodes = parsedNodes.stream()
                .map(this::normalizeSeedNodeAddress)
                .toArray(String[]::new);

        log.info("Initializing Redisson with raw nodes: {}, effective nodes: {}", parsedNodes, Arrays.asList(nodes));

        var clusterServersConfig = config.useClusterServers()
                .addNodeAddress(nodes)
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

        if (natToLocalhost) {
            clusterServersConfig.setNatMapper(uri -> {
                String host = uri.getHost();
                if (!"127.0.0.1".equals(host) && !"localhost".equalsIgnoreCase(host)) {
                    return new RedisURI(uri.getScheme(), "127.0.0.1", uri.getPort());
                }
                return uri;
            });
            log.info("Redisson NAT mapper enabled: non-local Redis hosts -> 127.0.0.1");
        }

        if (password != null && !password.isBlank()) {
            clusterServersConfig.setPassword(password);
        }

        return Redisson.create(config);
    }

    private String normalizeSeedNodeAddress(String node) {
        String address = node.startsWith("redis://") || node.startsWith("rediss://") ? node : "redis://" + node;
        if (!natToLocalhost) {
            return address;
        }

        URI uri = URI.create(address);
        String host = uri.getHost();
        if (host == null || "127.0.0.1".equals(host) || "localhost".equalsIgnoreCase(host)) {
            return address;
        }

        int port = uri.getPort();
        if (port <= 0) {
            log.warn("Invalid Redis node address: {}, keep as-is", address);
            return address;
        }
        String normalized = uri.getScheme() + "://127.0.0.1:" + port;
        log.info("Mapped seed Redis node {} -> {}", address, normalized);
        return normalized;
    }

}
