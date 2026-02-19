package com.winston.shortlink.dao;

import com.winston.shortlink.entity.ShortUrlMapping;
import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
import jakarta.persistence.PersistenceContext;
import jakarta.persistence.Query;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.shardingsphere.infra.hint.HintManager;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.winston.shortlink.constant.CommonConstants.SHARDING_DATABASE_COUNT;
import static com.winston.shortlink.constant.CommonConstants.SHARDING_TABLE_COUNT;

/**
 * @description: 短链数据访问层 - 基于EntityManager实现 JPA
 * @author: Winston
 * @date: 2026/2/16 10:31
 * @version: 1.0
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class ShortUrlDao {

    @PersistenceContext
    private EntityManager entityManager;
    // 注入原生数据源池
    @Qualifier("rawDataSourcePool")
    private final Map<Integer, DataSource> rawDataSourcePool;

    /**
     * 检查原始URL哈希值是否存在
     */
    public boolean existsByOriginUrlHash(String originUrlHash) {
        Query query = entityManager.createNativeQuery(
                "SELECT COUNT(*) FROM short_url_mapping WHERE origin_url_hash = ?"
        );
        query.setParameter(1, originUrlHash);
        Number count = (Number) query.getSingleResult();
        return count.longValue() > 0;
    }

    /**
     * 根据短链编码查询短链信息
     */
    public Optional<ShortUrlMapping> findByShortCode (String shortCode) {
        Query query = entityManager.createNativeQuery(
                "SELECT * FROM short_url_mapping WHERE short_code = ? LIMIT 1",
                ShortUrlMapping.class
        );
        query.setParameter(1, shortCode);
        try {
            ShortUrlMapping result = (ShortUrlMapping) query.getSingleResult();
            return Optional.of(result);
        } catch (NoResultException e) {
            return Optional.empty();
        }
    }
    /**
     * 根据原始URL查找短链
     */
    public Optional<ShortUrlMapping> findByOriginUrl(String originUrl) {
        Query query = entityManager.createNativeQuery(
                "SELECT * FROM short_url_mapping WHERE origin_url = ? LIMIT 1",
                ShortUrlMapping.class
        );
        query.setParameter(1, originUrl);
        try {
            ShortUrlMapping result = (ShortUrlMapping) query.getSingleResult();
            return Optional.of(result);
        } catch (NoResultException e) {
            return Optional.empty();
        }
    }

    /**
     * 检查短链编码是否存在
     */
    public boolean existsByShortCode(String shortCode) {
        Query query = entityManager.createNativeQuery(
                "SELECT COUNT(*) FROM short_url_mapping WHERE short_code = ?"
        );
        query.setParameter(1, shortCode);
        Number count = (Number) query.getSingleResult();
        return count.longValue() > 0;
    }

    /**
     * 增加访问次数
     */
    @Transactional
    public int incrementAccessCount(String shortCode) {
        Query query = entityManager.createNativeQuery(
                "UPDATE short_url_mapping SET access_count = access_count + 1 WHERE short_code = ?"
        );
        query.setParameter(1, shortCode);
        return query.executeUpdate();
    }

    /**
     * 批量增加访问次数
     */
    @Transactional
    public int incrementAccessCountBatch(String shortCode, Long count) {
        Query query = entityManager.createNativeQuery(
                "UPDATE short_url_mapping SET access_count = access_count + ? WHERE short_code = ?"
        );
        query.setParameter(1, count);
        query.setParameter(2, shortCode);
        return query.executeUpdate();
    }

    /**
     * 查找过期的短链
     */
    @SuppressWarnings("unchecked")
    public List<ShortUrlMapping> findExpiredUrlsByShortCode(String shortCode, LocalDateTime expireTime) {
        Query query = entityManager.createNativeQuery(
                "SELECT * FROM short_url_mapping WHERE short_code = ? AND create_time < ? AND expire_days > 0",
                ShortUrlMapping.class
        );
        query.setParameter(1, shortCode);
        query.setParameter(2, expireTime);
        return query.getResultList();
    }
    /**
     * 更新访问次数
     */
    @Transactional
    public int updateAccessCount(String shortCode, Long accessCount) {
        Query query = entityManager.createNativeQuery(
                "UPDATE short_url_mapping SET access_count = ? WHERE short_code = ?"
        );
        query.setParameter(1, accessCount);
        query.setParameter(2, shortCode);
        return query.executeUpdate();
    }
    /**
     * 根据ID查找
     */
    public Optional<ShortUrlMapping> findById(String shortCode) {
        ShortUrlMapping entity = entityManager.find(ShortUrlMapping.class, shortCode);
        return Optional.ofNullable(entity);
    }

    /**
     * 删除短链映射
     */
    @Transactional
    public void deleteById(String shortCode) {
        ShortUrlMapping entity = entityManager.find(ShortUrlMapping.class, shortCode);
        if (entity != null) {
            entityManager.remove(entity);
        }
    }

    /**
     * 查找所有记录
     */
    @SuppressWarnings("unchecked")
    public List<ShortUrlMapping> findAll() {
        Query query = entityManager.createNativeQuery(
                "SELECT * FROM short_url_mapping",
                ShortUrlMapping.class
        );
        return query.getResultList();
    }

    /**
     * 统计总数
     */
    public long count() {
        Query query = entityManager.createNativeQuery(
                "SELECT COUNT(*) FROM short_url_mapping"
        );
        Number count = (Number) query.getSingleResult();
        return count.longValue();
    }

    /**
     * 智能查询：优先尝试计算分片位置，失败则降级到热点分片查询
     */
    public ShortUrlMapping findByOriginUrlHash(String originUrlHash) {
        int dbIndex = Math.abs(originUrlHash.hashCode()) % SHARDING_DATABASE_COUNT;
        int tableIndex = Math.abs(originUrlHash.hashCode()) % SHARDING_TABLE_COUNT;

        try (HintManager hintManager = HintManager.getInstance()) {
            // 强制路由到指定数据库
            hintManager.addDatabaseShardingValue("short_link", dbIndex);

            // 强制路由到指定表
            hintManager.addTableShardingValue("short_url_mapping", tableIndex);

            String sql = "SELECT * FROM short_url_mapping WHERE origin_url_hash = ? LIMIT 1";
            Query query = entityManager.createNativeQuery(sql, ShortUrlMapping.class);
            query.setParameter(1, originUrlHash);

            List<ShortUrlMapping> results = query.getResultList();
            return results.isEmpty() ? null : results.get(0);

        } catch (Exception e) {
            // 2. 降级到热点分片查询（只查询前4个最活跃的分片）
            return findByOriginUrlHashInHotShards(originUrlHash);
        }
    }

    /**
     * 热点分片查询：只查询最活跃的几个分片
     */
    private ShortUrlMapping findByOriginUrlHashInHotShards(String originUrlHash) {
        // 基于经验，大部分数据集中在前几个分片
        int[] hotShards = {0, 1, 2, 3}; // 可根据实际情况调整

        for (int dbIndex : hotShards) {
            for (int tableIndex = 0; tableIndex < 8; tableIndex++) { // 只查询前8个表
                try {
                    String sql = "SELECT * FROM short_url_mapping_" + tableIndex + " WHERE origin_url_hash = ? LIMIT 1";
                    Query query = entityManager.createNativeQuery(sql, ShortUrlMapping.class);
                    query.setParameter(1, originUrlHash);
                    query.setHint("sharding.databases.value", "ds_" + dbIndex);

                    List<ShortUrlMapping> results = query.getResultList();
                    if (!results.isEmpty()) {
                        return results.get(0);
                    }
                } catch (Exception ignored) {
                    // 继续下一个分片
                }
            }
        }
        return null;
    }


    /**
     * 优化保存方法：在冲突时进行智能查询
     */
    @Transactional
    public ShortUrlMapping save(ShortUrlMapping entity, int dbIndex, int tableIndex) {
        // 1.预检验：判断是否存在,第一次都是不存在的
        String shortCode = entity.getShortCode();
        String originUrlHash = entity.getOriginUrlHash();
        log.info("开始保存短链 - shortCode: {}, originUrlHash: {}", shortCode, originUrlHash);

        log.info("预校验 - shortCode: {}, 计算得到 dbIndex: {}, tableIndex: {}", shortCode, dbIndex, tableIndex);
        Optional<ShortUrlMapping> existingOpt = preCheckByShortCode(dbIndex, tableIndex, shortCode, originUrlHash);
        if (existingOpt.isPresent()) {
            // 状态同步更新
            ShortUrlMapping existing = existingOpt.get();
            log.info("预校验发现已存在记录，直接返回并更新访问次数");
            entity.setShortCode(existing.getShortCode());
            // 更新现有记录
            existing.setCreateTime(LocalDateTime.now());
            existing.setExpireDays(entity.getExpireDays());
            existing.setUpdateTime(LocalDateTime.now());
            existing.setAccessCount(existing.getAccessCount() + 1);
            return entityManager.merge(existing);
        }
        // 2.预检验通过，进入try代码块，提交并冲刷数据到数据库
        try {
            entityManager.persist(entity);
            entityManager.flush();
            log.info("保存成功 - shortCode: {}", shortCode);
            return entity;

        } catch (Exception e) {
            // 3.如果冲刷失败，进入catch代码块，执行clear代码块，并再次检验
            String errorMsg = e.getMessage();
            log.warn("保存异常 - shortCode: {}, error: {}", shortCode, errorMsg);
            if (errorMsg != null && (errorMsg.contains("Duplicate entry") ||
                    errorMsg.contains("origin_url_hash"))) {

                entityManager.clear();

                // 3. 发生冲突时，再次精确查询（可能是并发导致的）
                Optional<ShortUrlMapping> conflictRecord = preCheckByShortCode(dbIndex, tableIndex, shortCode, originUrlHash);
                if (conflictRecord.isPresent()) {
                    ShortUrlMapping existing = conflictRecord.get();
                    log.info("冲突处理 - 找到已存在记录，更新访问次数");
                    existing.setCreateTime(LocalDateTime.now());
                    existing.setExpireDays(entity.getExpireDays());
                    existing.setUpdateTime(LocalDateTime.now());
                    existing.setAccessCount(existing.getAccessCount() + 1);
                    return entityManager.merge(existing);
                }
            }
            throw e;
        }

    }

    /**
     * 预校验方法：在保存前根据shortCode精确查询目标分片
     */
    /**
     * 预校验查询 - 优先使用shortCode精确查询，fallback到originUrlHash查询
     */
    public Optional<ShortUrlMapping> preCheckByShortCode(int dbIndex, int tableIndex, String shortCode, String originUrlHash) {
        // shortCode未找到，通过originUrlHash使用原生数据源精确查询
        log.debug("分片键查询未命中，尝试原生数据源查询: shortCode={}, originUrlHash={}", shortCode, originUrlHash);
        return queryHashUrlWithRawDataSource(dbIndex, tableIndex, originUrlHash);
    }


    /**
     * 使用原生数据源进行精确查询 - 完全绕过ShardingSphere
     */
    private Optional<ShortUrlMapping> queryHashUrlWithRawDataSource(int dbIndex, int tableIndex, String originUrlHash) {
        // 获取对应的原生数据源
        DataSource rawDataSource = rawDataSourcePool.get(dbIndex);
        if (rawDataSource == null) {
            log.error("未找到对应的原生数据源: dbIndex={}", dbIndex);
            return Optional.empty();
        }

        String tableName = "short_url_mapping_" + tableIndex;
        String sql = "SELECT short_code, origin_url, origin_url_hash, create_time, update_time, " +
                "expire_days, access_count, status, creator FROM " + tableName +
                " WHERE origin_url_hash = ? LIMIT 1";

        try (Connection conn = rawDataSource.getConnection();
             PreparedStatement stmt = conn.prepareStatement(sql)) {

            stmt.setString(1, originUrlHash);

            long startTime = System.currentTimeMillis();
            try (ResultSet rs = stmt.executeQuery()) {
                long duration = System.currentTimeMillis() - startTime;

                if (rs.next()) {
                    ShortUrlMapping mapping = mapResultSetToEntity(rs);
                    log.info("预校验成功 - 原生数据源精确查询: db=short_link_{}, table={}, 耗时: {}ms",
                            dbIndex, tableName, duration);
                    return Optional.of(mapping);
                }

                log.debug("预校验未找到记录 - 原生数据源: db=short_link_{}, table={}, 耗时: {}ms",
                        dbIndex, tableName, duration);
                return Optional.empty();
            }

        } catch (SQLException e) {
            log.error("原生数据源查询异常: db=short_link_{}, table={}, originUrlHash={}, error={}",
                    dbIndex, tableName, originUrlHash, e.getMessage(), e);
            return Optional.empty();
        }
    }

    private ShortUrlMapping mapResultSetToEntity(ResultSet rs) throws SQLException {
        ShortUrlMapping mapping = new ShortUrlMapping();
        mapping.setShortCode(rs.getString("short_code"));
        mapping.setOriginUrl(rs.getString("origin_url"));
        mapping.setOriginUrlHash(rs.getString("origin_url_hash"));
        mapping.setCreateTime(rs.getTimestamp("create_time").toLocalDateTime());
        mapping.setUpdateTime(rs.getTimestamp("update_time").toLocalDateTime());
        mapping.setExpireDays(rs.getInt("expire_days"));
        mapping.setAccessCount(rs.getLong("access_count"));
        mapping.setStatus(rs.getInt("status"));
        mapping.setCreator(rs.getString("creator"));
        return mapping;
    }




}
