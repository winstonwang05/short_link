package com.winston.shortlink.service;

import com.winston.shortlink.entity.ShortUrlMapping;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import javax.sql.DataSource;
import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicLong;

import static com.winston.shortlink.constant.CommonConstants.SHARDING_DATABASE_COUNT;
import static com.winston.shortlink.constant.CommonConstants.SHARDING_TABLE_COUNT;

/**
 * 数据迁移服务
 */
@Slf4j
@Service
public class DataMigrationService {

    private final DataSource dataSource;
    // 保留原有的 ShardingSphere 数据源用于写入新数据库
    private final DataSource newDataSource;

    // 原生 JDBC 数据源缓存
    private final DataSource[] nativeDataSources = new DataSource[16];

    private static final int BATCH_SIZE = 100000; // 每批10万条记录
    private static final int THREAD_COUNT = 16; // 16个数据库并行迁移

    private final AtomicLong totalMigrated = new AtomicLong(0);
    private final AtomicLong totalFailed = new AtomicLong(0);

    public DataMigrationService(@Qualifier("dataSource") DataSource dataSource,
                                @Qualifier("newDataSource") DataSource newDataSource) {
        this.dataSource = dataSource;
        this.newDataSource = newDataSource;
        initNativeDataSources();
    }

    /**
     * 初始化原生 JDBC 数据源（绕过 ShardingSphere）
     */
    private void initNativeDataSources() {
        for (int i = 0; i < SHARDING_DATABASE_COUNT; i++) {
            nativeDataSources[i] = createNativeDataSource(i);
        }
    }

    /**
     * 创建原生 JDBC 数据源
     */
    private DataSource createNativeDataSource(int dbIndex) {
        HikariConfig config = new HikariConfig();
        config.setDriverClassName("com.mysql.cj.jdbc.Driver");
        config.setJdbcUrl(String.format(
            "jdbc:mysql://localhost:3306/short_link_%d?useUnicode=true&characterEncoding=utf8&useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai",
            dbIndex
        ));
        config.setUsername("root");
        config.setPassword("123456"); // 请替换为实际密码
        config.setMaximumPoolSize(5);
        config.setMinimumIdle(1);
        config.setConnectionTimeout(30000);
        config.setIdleTimeout(600000);
        config.setMaxLifetime(1800000);

        return new HikariDataSource(config);
    }

    /**
     * 启动数据迁移
     */
    public void startMigration() {
        log.info("开始历史数据迁移...");

        List<CompletableFuture<Void>> futures = new ArrayList<>();

        // 为每个数据库启动迁移任务
        for (int dbIndex = 0; dbIndex < SHARDING_DATABASE_COUNT; dbIndex++) {
            final int currentDbIndex = dbIndex;
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                migrateDatabase(currentDbIndex);
            });
            futures.add(future);
        }

        // 等待所有迁移任务完成
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenRun(() -> {
                    log.info("数据迁移完成! 总迁移数量: {}, 失败数量: {}",
                            totalMigrated.get(), totalFailed.get());
                })
                .exceptionally(throwable -> {
                    log.error("数据迁移过程中发生错误", throwable);
                    return null;
                });
    }

    /**
     * 迁移单个数据库
     */
    private void migrateDatabase(int dbIndex) {
        log.info("开始迁移数据库 ds_{}", dbIndex);

        try {
            // 迁移该数据库下的所有表
            for (int tableIndex = 0; tableIndex < SHARDING_TABLE_COUNT; tableIndex++) {
                migrateTable(dbIndex, tableIndex);
            }

            log.info("数据库 ds_{} 迁移完成", dbIndex);
        } catch (Exception e) {
            log.error("数据库 ds_{} 迁移失败", dbIndex, e);
        }
    }

    /**
     * 迁移单个表
     */
    private void migrateTable(int dbIndex, int tableIndex) {
        String tableName = "short_url_mapping_" + tableIndex;
        log.info("开始迁移表 ds_{}.{}", dbIndex, tableName);

        try {
            long offset = 0;
            long migratedCount = 0;

            while (true) {
                // 分批读取数据
                List<ShortUrlMapping> batch = readBatchData(dbIndex, tableName, offset, BATCH_SIZE);

                if (batch.isEmpty()) {
                    break; // 没有更多数据
                }

                // 批量写入新数据源
                int successCount = writeBatchToNewDataSource(batch);

                migratedCount += successCount;
                totalMigrated.addAndGet(successCount);
                totalFailed.addAndGet(batch.size() - successCount);

                offset += BATCH_SIZE;

                // 限流控制，避免对线上业务造成影响
                Thread.sleep(100); // 每批间隔100ms

                log.debug("表 ds_{}.{} 已迁移 {} 条记录", dbIndex, tableName, migratedCount);
            }

            log.info("表 ds_{}.{} 迁移完成，共迁移 {} 条记录", dbIndex, tableName, migratedCount);

        } catch (Exception e) {
            log.error("表 ds_{}.{} 迁移失败", dbIndex, tableName, e);
        }
    }

    /**
     * 分批读取原数据源数据（使用原生 JDBC）
     */
    private List<ShortUrlMapping> readBatchData(int dbIndex, String tableName, long offset, int batchSize) {
        List<ShortUrlMapping> result = new ArrayList<>();

        String sql = String.format(
                "SELECT short_code, origin_url, origin_url_hash, create_time, update_time, " +
                        "expire_days, access_count, status, creator " +
                        "FROM %s ORDER BY create_time LIMIT %d OFFSET %d",
                tableName, batchSize, offset
        );

        // 使用原生 JDBC 数据源，完全绕过 ShardingSphere
        try (Connection conn = nativeDataSources[dbIndex].getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {

            log.debug("正在从数据库 short_link_{} 的表 {} 读取数据，offset={}, batchSize={}",
                     dbIndex, tableName, offset, batchSize);

            while (rs.next()) {
                ShortUrlMapping mapping = new ShortUrlMapping();
                mapping.setShortCode(rs.getString("short_code"));
                mapping.setOriginUrl(rs.getString("origin_url"));
                mapping.setOriginUrlHash(rs.getString("origin_url_hash"));
                mapping.setCreateTime(rs.getTimestamp("create_time").toLocalDateTime());
                mapping.setUpdateTime(rs.getTimestamp("update_time").toLocalDateTime());
                mapping.setExpireDays(rs.getObject("expire_days", Integer.class));
                mapping.setAccessCount(rs.getLong("access_count"));
                mapping.setStatus(rs.getInt("status"));
                mapping.setCreator(rs.getString("creator"));

                result.add(mapping);
            }

            log.debug("从数据库 short_link_{} 的表 {} 读取到 {} 条记录",
                     dbIndex, tableName, result.size());

        } catch (SQLException e) {
            log.error("读取数据失败: dbIndex={}, tableName={}, offset={}, error={}",
                    dbIndex, tableName, offset, e.getMessage(), e);
        }

        return result;
    }

    /**
     * 批量写入新数据源（通过 ShardingSphere 自动路由）
     */
    private int writeBatchToNewDataSource(List<ShortUrlMapping> batch) {
        int successCount = 0;

        String sql = "INSERT INTO short_url_mapping (short_code, origin_url, origin_url_hash, " +
                "create_time, update_time, expire_days, access_count, status, creator) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?) " +
                "ON DUPLICATE KEY UPDATE " +
                "origin_url=VALUES(origin_url), origin_url_hash=VALUES(origin_url_hash), " +
                "update_time=VALUES(update_time), expire_days=VALUES(expire_days), " +
                "access_count=VALUES(access_count), status=VALUES(status), creator=VALUES(creator)";

        try (Connection conn = newDataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            conn.setAutoCommit(false);

            for (ShortUrlMapping mapping : batch) {
                try {
                    stmt.setString(1, mapping.getShortCode());
                    stmt.setString(2, mapping.getOriginUrl());
                    stmt.setString(3, mapping.getOriginUrlHash());
                    stmt.setTimestamp(4, Timestamp.valueOf(mapping.getCreateTime()));
                    stmt.setTimestamp(5, Timestamp.valueOf(mapping.getUpdateTime()));
                    stmt.setObject(6, mapping.getExpireDays());
                    stmt.setLong(7, mapping.getAccessCount());
                    stmt.setInt(8, mapping.getStatus());
                    stmt.setString(9, mapping.getCreator());

                    stmt.addBatch();
                    successCount++;
                } catch (Exception e) {
                    log.error("准备批量插入数据失败: shortCode={}", mapping.getShortCode(), e);
                }
            }

            stmt.executeBatch();
            conn.commit();

            log.info("本批次总共写入 {} 条记录到新数据源（由 ShardingSphere 自动路由）", successCount);

        } catch (SQLException e) {
            log.error("批量写入新数据源失败", e);
            successCount = 0; // 整批失败
        }

        return successCount;
    }

    /**
     * 获取迁移进度
     */
    public MigrationProgress getMigrationProgress() {
        return new MigrationProgress(
                totalMigrated.get(),
                totalFailed.get(),
                calculateTotalRecords()
        );
    }

    /**
     * 计算总记录数（估算）
     */
    private long calculateTotalRecords() {
        // 这里可以实现更精确的记录数统计
        return 0L;
    }

    /**
     * 迁移进度信息
     */
    public static class MigrationProgress {
        private final long migratedCount;
        private final long failedCount;
        private final long totalCount;

        public MigrationProgress(long migratedCount, long failedCount, long totalCount) {
            this.migratedCount = migratedCount;
            this.failedCount = failedCount;
            this.totalCount = totalCount;
        }

        public double getProgressPercentage() {
            if (totalCount == 0) return 0.0;
            return (double) migratedCount / totalCount * 100;
        }

        // getters...
        public long getMigratedCount() {
            return migratedCount;
        }

        public long getFailedCount() {
            return failedCount;
        }

        public long getTotalCount() {
            return totalCount;
        }
    }
}
