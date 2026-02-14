package com.winston.shortlink.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.Map;

/**
 * @description: 原生数据库的配置 -- 绕过ShardingSphere的精准查询
 * @author: Winston
 * @date: 2026/2/11 20:33
 * @version: 1.0
 */
@Slf4j
@Configuration
public class RawDataSourceConfig {

    @Value("${raw.datasource.host:127.0.0.1}")
    private String host;

    @Value("${raw.datasource.port:3306}")
    private String port;

    @Value("${raw.datasource.username:root}")
    private String username;

    @Value("${raw.datasource.password:susan123}")
    private String password;

    @Value("${raw.datasource.database-count:32}")
    private int databaseCount;

    /**
     * 创建原生数据源池
     */
    @Bean("rawDataSourcePool")
    public Map<Integer, DataSource> rawDataSourcePool() {
        Map<Integer, DataSource> dataSourcePool = new HashMap<>();

        for (int i = 0; i < databaseCount; i++) {
            try {
                DataSource dataSource = createRawDataSource(i);
                dataSourcePool.put(i, dataSource);
                log.info("成功创建原生数据源: short_link_{}", i);
            } catch (Exception e) {
                log.error("创建原生数据源失败: short_link_{}, error={}", i, e.getMessage(), e);
                throw new RuntimeException("创建原生数据源失败: short_link_" + i, e);
            }
        }

        log.info("原生数据源池初始化完成，共创建{}个数据源", dataSourcePool.size());
        return dataSourcePool;
    }

    /**
     * 创建单个原生数据源
     */
    private DataSource createRawDataSource(int dbIndex) {
        HikariConfig config = new HikariConfig();

        // 基础连接配置
        config.setDriverClassName("com.mysql.cj.jdbc.Driver");
        config.setJdbcUrl(String.format(
                "jdbc:mysql://%s:%s/short_link_%d?useUnicode=true&characterEncoding=utf8&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai",
                host, port, dbIndex
        ));
        config.setUsername(username);
        config.setPassword(password);

        // 连接池配置
        config.setPoolName("RawHikariCP-" + dbIndex);
        config.setMinimumIdle(2);  // 最小空闲连接数
        config.setMaximumPoolSize(10);  // 最大连接数
        config.setConnectionTimeout(30000);  // 连接超时30秒
        config.setIdleTimeout(600000);  // 空闲超时10分钟
        config.setMaxLifetime(1800000);  // 连接最大生命周期30分钟
        config.setLeakDetectionThreshold(60000);  // 连接泄漏检测阈值1分钟

        // 性能优化配置
        config.addDataSourceProperty("cachePrepStmts", "true");
        config.addDataSourceProperty("prepStmtCacheSize", "250");
        config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
        config.addDataSourceProperty("useServerPrepStmts", "true");
        config.addDataSourceProperty("rewriteBatchedStatements", "true");
        config.addDataSourceProperty("cacheResultSetMetadata", "true");
        config.addDataSourceProperty("cacheServerConfiguration", "true");
        config.addDataSourceProperty("elideSetAutoCommits", "true");
        config.addDataSourceProperty("maintainTimeStats", "false");

        return new HikariDataSource(config);
    }
}
