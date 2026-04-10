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
 * Dual-write datasource configuration.
 */
@Slf4j
@Configuration
public class DualWriteDataSourceConfig {

    @Bean("dataSource")
    public DataSource dataSource() {
        return createShardingSphereDataSource("sharding.yaml");
    }

    @Bean("newDataSource")
    public DataSource newDataSource() {
        return createShardingSphereDataSource("sharding-new.yaml");
    }

    private DataSource createShardingSphereDataSource(String configFile) {
        try {
            log.info("Creating ShardingSphere datasource with config: {}", configFile);

            ClassPathResource resource = new ClassPathResource(configFile);
            if (!resource.exists()) {
                throw new IllegalArgumentException("Config file does not exist: " + configFile);
            }

            File yamlFile = resource.getFile();
            DataSource dataSource = YamlShardingSphereDataSourceFactory.createDataSource(yamlFile);
            validateDataSource(dataSource, configFile);

            log.info("ShardingSphere datasource created successfully: {}", configFile);
            return dataSource;
        } catch (SQLException e) {
            throw new RuntimeException("Failed to create ShardingSphere datasource: " + configFile, e);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read datasource config file: " + configFile, e);
        }
    }

    private void validateDataSource(DataSource dataSource, String configFile) throws SQLException {
        try (Connection connection = dataSource.getConnection()) {
            if (connection == null || connection.isClosed()) {
                throw new SQLException("Invalid datasource connection");
            }
            log.info("Datasource validation success, config: {}, url: {}",
                    configFile, connection.getMetaData().getURL());
        }
    }

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
        } catch (SQLException e) {
            log.error("Failed to get datasource info", e);
            info.put("error", e.getMessage());
        }
        return info;
    }
}