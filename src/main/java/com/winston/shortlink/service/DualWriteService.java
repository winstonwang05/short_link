package com.winston.shortlink.service;

import com.winston.shortlink.entity.ShortUrlMapping;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.concurrent.CompletableFuture;

import static com.winston.shortlink.constant.CommonConstants.*;

/**
 * 双写服务实现
 */
@Slf4j
@Service
public class DualWriteService {

    private final DataSource dataSource;
    private final DataSource newDataSource;

    public DualWriteService(@Qualifier("dataSource") DataSource dataSource,
                            @Qualifier("newDataSource") DataSource newDataSource) {
        this.dataSource = dataSource;
        this.newDataSource = newDataSource;
    }

    /**
     * 双写插入短链数据
     */
    @Transactional
    public void dualWriteInsert(ShortUrlMapping mapping) {
        try {
            // 1. 先写入原数据源（保证业务连续性）
            insertTodataSource(mapping);

            // 2. 异步写入新数据源
            asyncInsertToNewDataSource(mapping);

            log.info("双写插入成功: shortCode={}", mapping.getShortCode());
        } catch (Exception e) {
            log.error("双写插入失败: shortCode={}, error={}", mapping.getShortCode(), e.getMessage(), e);
            throw new RuntimeException("双写插入失败", e);
        }
    }

    /**
     * 双写更新短链数据
     */
    @Transactional
    public void dualWriteUpdate(ShortUrlMapping mapping) {
        try {
            // 1. 先更新原数据源
            updateTodataSource(mapping);

            // 2. 异步更新新数据源
            asyncUpdateToNewDataSource(mapping);

            log.info("双写更新成功: shortCode={}", mapping.getShortCode());
        } catch (Exception e) {
            log.error("双写更新失败: shortCode={}, error={}", mapping.getShortCode(), e.getMessage(), e);
            throw new RuntimeException("双写更新失败", e);
        }
    }

    /**
     * 插入到原数据源
     */
    private void insertTodataSource(ShortUrlMapping mapping) throws SQLException {
        String sql = "INSERT INTO short_url_mapping (short_code, origin_url, origin_url_hash, " +
                "create_time, update_time, expire_days, access_count, status, creator) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";

        // 计算原数据源的分片路由信息
        String shortCode = mapping.getShortCode();
        int dbIndex = Math.abs(shortCode.hashCode()) % SHARDING_DATABASE_COUNT;  // 原数据源16个库
        int tableIndex = Math.abs(shortCode.hashCode()) % SHARDING_TABLE_COUNT; // 原数据源64张表
        String targetDb = "short_link_" + dbIndex;
        String targetTable = "short_url_mapping_" + tableIndex;

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            setStatementParameters(stmt, mapping);
            stmt.executeUpdate();

            log.info("原数据源插入成功: shortCode={}, 路由到 {}.{}",
                    mapping.getShortCode(), targetDb, targetTable);
        }
    }

    /**
     * 异步插入到新数据源
     */
    @Async("dualWriteExecutor")
    public void asyncInsertToNewDataSource(ShortUrlMapping mapping) {
        try {
            insertToNewDataSource(mapping);
        } catch (Exception e) {
            log.error("新数据源异步插入失败: shortCode={}, error={}",
                    mapping.getShortCode(), e.getMessage(), e);
            // 记录失败数据，用于后续补偿
            recordFailedWrite(mapping, "INSERT", e.getMessage());
        }
    }

    /**
     * 插入到新数据源
     */
    private void insertToNewDataSource(ShortUrlMapping mapping) throws SQLException {
        String sql = "INSERT INTO short_url_mapping (short_code, origin_url, origin_url_hash, " +
                "create_time, update_time, expire_days, access_count, status, creator) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)";

        try (Connection conn = newDataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            setStatementParameters(stmt, mapping);
            stmt.executeUpdate();

            log.info("新数据源插入成功: shortCode={}", mapping.getShortCode());
        }
    }

    /**
     * 更新到原数据源
     */
    private void updateTodataSource(ShortUrlMapping mapping) throws SQLException {
        String sql = "UPDATE short_url_mapping SET origin_url=?, origin_url_hash=?, " +
                "update_time=?, expire_days=?, access_count=?, status=?, creator=? " +
                "WHERE short_code=?";

        try (Connection conn = dataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            setUpdateStatementParameters(stmt, mapping);
            stmt.executeUpdate();

            log.debug("原数据源更新成功: shortCode={}", mapping.getShortCode());
        }
    }

    /**
     * 异步更新到新数据源
     */
    @Async("dualWriteExecutor")
    public void asyncUpdateToNewDataSource(ShortUrlMapping mapping) {
        try {
            updateToNewDataSource(mapping);
        } catch (Exception e) {
            log.error("新数据源异步更新失败: shortCode={}, error={}",
                    mapping.getShortCode(), e.getMessage(), e);
            // 记录失败数据，用于后续补偿
            recordFailedWrite(mapping, "UPDATE", e.getMessage());
        }
    }

    /**
     * 更新到新数据源
     */
    private void updateToNewDataSource(ShortUrlMapping mapping) throws SQLException {
        String sql = "UPDATE short_url_mapping SET origin_url=?, origin_url_hash=?, " +
                "update_time=?, expire_days=?, access_count=?, status=?, creator=? " +
                "WHERE short_code=?";

        try (Connection conn = newDataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            setUpdateStatementParameters(stmt, mapping);
            stmt.executeUpdate();

            log.debug("新数据源更新成功: shortCode={}", mapping.getShortCode());
        }
    }

    /**
     * 设置插入语句参数
     */
    private void setStatementParameters(PreparedStatement stmt, ShortUrlMapping mapping) throws SQLException {
        stmt.setString(1, mapping.getShortCode());
        stmt.setString(2, mapping.getOriginUrl());
        stmt.setString(3, mapping.getOriginUrlHash());
        stmt.setTimestamp(4, java.sql.Timestamp.valueOf(mapping.getCreateTime()));
        stmt.setTimestamp(5, mapping.getUpdateTime() == null ? java.sql.Timestamp.valueOf(LocalDateTime.now()) :
                java.sql.Timestamp.valueOf(mapping.getUpdateTime()));
        stmt.setInt(6, mapping.getExpireDays());
        stmt.setLong(7, mapping.getAccessCount());
        stmt.setInt(8, mapping.getStatus());
        stmt.setString(9, mapping.getCreator());
    }

    /**
     * 设置更新语句参数
     */
    private void setUpdateStatementParameters(PreparedStatement stmt, ShortUrlMapping mapping) throws SQLException {
        stmt.setString(1, mapping.getOriginUrl());
        stmt.setString(2, mapping.getOriginUrlHash());
        stmt.setTimestamp(3, java.sql.Timestamp.valueOf(mapping.getUpdateTime()));
        stmt.setInt(4, mapping.getExpireDays());
        stmt.setLong(5, mapping.getAccessCount());
        stmt.setInt(6, mapping.getStatus());
        stmt.setString(7, mapping.getCreator());
        stmt.setString(8, mapping.getShortCode());
    }

    /**
     * 记录失败的写入操作，用于后续补偿
     */
    private void recordFailedWrite(ShortUrlMapping mapping, String operation, String errorMessage) {
        // 这里可以写入失败日志表或消息队列，用于后续数据补偿
        log.error("记录失败写入: shortCode={}, operation={}, error={}",
                mapping.getShortCode(), operation, errorMessage);
    }
}
