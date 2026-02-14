package com.winston.shortlink.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.context.annotation.Configuration;

/**
 * @description: 数据库连接池配置（HikariCP）
 * @author: Winston
 * @date: 2026/2/8 22:21
 * @version: 1.0
 */
@Configuration
public class DatabaseConfig {

    /**
     * 优化数据库连接池配置
     */
    public HikariDataSource createOptimizedDataSource(String jdbcUrl, String username, String password) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(username);
        config.setPassword(password);
        config.setDriverClassName("com.mysql.cj.jdbc.Driver");

        // 连接池优化配置
        config.setMaximumPoolSize(50); // 每个数据源最大连接数
        config.setMinimumIdle(10);     // 最小空闲连接数
        config.setConnectionTimeout(30000);    // 连接超时时间
        config.setIdleTimeout(600000);         // 空闲超时时间
        config.setMaxLifetime(1800000);        // 连接最大生命周期
        config.setLeakDetectionThreshold(60000); // 连接泄漏检测阈值

        // MySQL优化参数
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
