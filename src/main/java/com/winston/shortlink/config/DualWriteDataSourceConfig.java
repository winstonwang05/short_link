package com.winston.shortlink.config;

import lombok.extern.slf4j.Slf4j;
import org.apache.shardingsphere.driver.api.yaml.YamlShardingSphereDataSourceFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;

import javax.sql.DataSource;
import java.io.File;
import java.io.IOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.Map;

/**
 * 双写数据源配置
 */
@Slf4j
@Configuration
public class DualWriteDataSourceConfig {

//    /**
//     * 原数据源配置
//     */
//    @Bean("dataSource")
//    public DataSource dataSource() {
//        // 使用现有的ShardingSphere配置
//        return createShardingSphereDataSource("sharding.yaml");
//    }

    /**
     * 新数据源配置
     */
    @Bean("newDataSource")
    public DataSource newDataSource() {
        // 使用新的ShardingSphere配置
        return createShardingSphereDataSource("sharding-new.yaml");
    }

    /**
     * 创建 ShardingSphere 数据源
     *
     * @param configFile YAML 配置文件名（如 "sharding.yaml" 或 "sharding-new.yaml"）
     * @return ShardingSphere 数据源
     */
    private DataSource createShardingSphereDataSource(String configFile) {
        try {
            log.info("开始创建 ShardingSphere 数据源，配置文件: {}", configFile);

            // 从 classpath 加载配置文件
            ClassPathResource resource = new ClassPathResource(configFile);
            if (!resource.exists()) {
                log.error("配置文件不存在: {}", configFile);
                throw new IllegalArgumentException("配置文件不存在: " + configFile);
            }

            File yamlFile = resource.getFile();
            log.debug("配置文件路径: {}", yamlFile.getAbsolutePath());

            // 使用 ShardingSphere 的 YAML 工厂创建数据源
            DataSource dataSource = YamlShardingSphereDataSourceFactory.createDataSource(yamlFile);

            // 验证数据源连接
            validateDataSource(dataSource, configFile);

            log.info("成功创建 ShardingSphere 数据源，配置文件: {}", configFile);
            return dataSource;

        } catch (SQLException e) {
            log.error("创建 ShardingSphere 数据源失败，SQL异常，配置文件: {}", configFile, e);
            throw new RuntimeException("创建 ShardingSphere 数据源失败: " + e.getMessage(), e);
        } catch (IOException e) {
            log.error("读取配置文件失败: {}", configFile, e);
            throw new RuntimeException("读取配置文件失败: " + e.getMessage(), e);
        } catch (Exception e) {
            log.error("创建数据源时发生未知错误，配置文件: {}", configFile, e);
            throw new RuntimeException("创建数据源失败: " + e.getMessage(), e);
        }
    }

    /**
     * 验证数据源连接
     *
     * @param dataSource 数据源
     * @param configFile 配置文件名
     * @throws SQLException 连接异常
     */
    private void validateDataSource(DataSource dataSource, String configFile) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            if (connection != null && !connection.isClosed()) {
                log.info("数据源连接验证成功，配置文件: {}, 数据库: {}",
                        configFile, connection.getMetaData().getURL());
            } else {
                throw new SQLException("数据源连接无效");
            }
        } catch (SQLException e) {
            log.error("数据源连接验证失败，配置文件: {}", configFile, e);
            throw e;
        }
    }

    /**
     * 获取数据源连接信息（用于调试）
     *
     * @param dataSource 数据源
     * @return 连接信息
     */
    public Map<String, String> getDataSourceInfo(DataSource dataSource) {
        Map<String, String> info = new HashMap<>();

        try (Connection connection = dataSource.getConnection()) {
            var metaData = connection.getMetaData();
            info.put("databaseProductName", metaData.getDatabaseProductName());
            info.put("databaseProductVersion", metaData.getDatabaseProductVersion());
            info.put("driverName", metaData.getDriverName());
            info.put("driverVersion", metaData.getDriverVersion());
            info.put("url", metaData.getURL());
            info.put("userName", metaData.getUserName());

            log.debug("数据源信息: {}", info);

        } catch (SQLException e) {
            log.error("获取数据源信息失败", e);
            info.put("error", e.getMessage());
        }

        return info;
    }
}
