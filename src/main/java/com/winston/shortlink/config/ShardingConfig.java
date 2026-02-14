/*
package com.winston.shortlink.config;

import org.apache.shardingsphere.sharding.api.sharding.standard.PreciseShardingValue;
import org.apache.shardingsphere.sharding.api.sharding.standard.RangeShardingValue;
import org.apache.shardingsphere.sharding.api.sharding.standard.StandardShardingAlgorithm;

import java.util.Collection;
import java.util.List;
import java.util.Properties;

*/
/**
 * @description: ShardingSphere的配置类
 * @author: Winston
 * @date: 2026/1/28 11:55
 * @version: 1.0
 *//*

public class ShardingConfig {

    */
/**
     * 自定义的分库算法
     *//*

    public static class DataBaseShardingAlgorithm implements StandardShardingAlgorithm<String> {
        @Override
        public String doSharding(Collection<String> collection, PreciseShardingValue<String> preciseShardingValue) {
            // 1.从SQL中获取分片键也就是shortcode
            String shortcode = preciseShardingValue.getValue();

            // 2.计算得到shortcode的哈希值并取绝对值
            int hash = Math.abs(shortcode.hashCode());

            // 3.对得到的结果对16取模
            int dbIndex = hash % 16;

            // 4.拼接成逻辑数据库返回

            return "ds_" + dbIndex;
        }

        @Override
        public Collection<String> doSharding(Collection<String> availableTargetNames, RangeShardingValue<String> rangeShardingValue) {
            // 处理范围查询，比如between，短链系统一般是精确查询，直接返回所有可用的目标
            return availableTargetNames;
        }

        // 初始化方法，如果算法需要参数配置，可以在这里读取 props
        @Override
        public void init(Properties props) {

        }
        // 定义算法类型名称，在YAML配置文件中通过该名字来引用该算法
        @Override
        public String getType() {
            return "DATABASE_HASH";
        }
    }

    */
/**
     * 自定义分表算法
     *//*

    public static class TableShardingAlgorithm implements StandardShardingAlgorithm<String> {

        @Override
        public String doSharding(Collection<String> collection, PreciseShardingValue<String> preciseShardingValue) {
            // 1.从SQL中获取shortcode
            String shortcode = preciseShardingValue.getValue();
            // 2.对shortcode取哈希值并去绝对值
            int hash = Math.abs(shortcode.hashCode());
            // 3.对64取模
            int tableIndex = hash % 64;
            // 4.返回具体表明
            return "short_url_mapping_" + tableIndex;
        }

        @Override
        public Collection<String> doSharding(Collection<String> collection, RangeShardingValue<String> rangeShardingValue) {
            // 范围查询返回空列表，表示不支持短码的范围路由（短链场景通常不按范围查）
            return List.of();
        }

        @Override
        public String getType() {
            // 定义算法类型名称
            return "table-hash";
        }
    }

}
*/
