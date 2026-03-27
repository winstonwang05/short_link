package com.winston.shortlink.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.Map;

import static com.winston.shortlink.constant.CommonConstants.SHARDING_DATABASE_COUNT;
import static com.winston.shortlink.constant.CommonConstants.SHARDING_TABLE_COUNT;

/**
 * 扩容监控服务
 */
@Slf4j
@Service
public class ExpansionMonitorService {

    private final DataSource dataSource;
    private final DataSource newDataSource;

    public ExpansionMonitorService(@Qualifier("dataSource") DataSource dataSource,
                                   @Qualifier("newDataSource") DataSource newDataSource) {
        this.dataSource = dataSource;
        this.newDataSource = newDataSource;
    }

    /**
     * 定期监控数据一致性
     */
    @Scheduled(fixedRate = 300000) // 每5分钟检查一次
    public void monitorDataConsistency() {
        try {
            Map<String, Long> originalCounts = getDataCounts(dataSource, "原数据源");
            Map<String, Long> newCounts = getDataCounts(newDataSource, "新数据源");

            // 比较数据量
            for (String key : originalCounts.keySet()) {
                Long originalCount = originalCounts.get(key);
                Long newCount = newCounts.getOrDefault(key, 0L);

                if (!originalCount.equals(newCount)) {
                    log.warn("数据不一致检测: {} - 原数据源: {}, 新数据源: {}",
                            key, originalCount, newCount);
                }
            }

            log.info("数据一致性检查完成");
        } catch (Exception e) {
            log.error("数据一致性监控失败", e);
        }
    }

    /**
     * 获取数据源的数据统计
     */
    private Map<String, Long> getDataCounts(DataSource dataSource, String sourceName) {
        Map<String, Long> counts = new HashMap<>();

        try (Connection conn = dataSource.getConnection()) {
            // 统计各个数据库的记录数
            for (int dbIndex = 0; dbIndex < SHARDING_DATABASE_COUNT; dbIndex++) {
                for (int tableIndex = 0; tableIndex < SHARDING_TABLE_COUNT; tableIndex++) {
                    String tableName = "short_url_mapping_" + tableIndex;
                    String sql = "SELECT COUNT(*) FROM " + tableName;

                    try (PreparedStatement stmt = conn.prepareStatement(sql);
                         ResultSet rs = stmt.executeQuery()) {

                        if (rs.next()) {
                            long count = rs.getLong(1);
                            String key = "ds_" + dbIndex + "." + tableName;
                            counts.put(key, count);
                        }
                    } catch (Exception e) {
                        log.debug("统计表 {} 记录数失败: {}", tableName, e.getMessage());
                    }
                }
            }
        } catch (Exception e) {
            log.error("获取 {} 数据统计失败", sourceName, e);
        }

        return counts;
    }
}
