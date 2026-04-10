package com.winston.shortlink.service;

import com.winston.shortlink.entity.ShortUrlMapping;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.event.EventListener;
import org.springframework.data.redis.connection.RedisStreamCommands;
import org.springframework.data.redis.connection.stream.*;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.net.InetAddress;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.winston.shortlink.constant.RedisKeyConstants.CONSUMER_GROUP;
import static com.winston.shortlink.constant.RedisKeyConstants.STREAM_KEY;

/**
 * @description: Redis Stream实现分布式本地缓存同步
 * @author: Winston
 * @date: 2026/2/9 17:24
 * @version: 1.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LocalCacheStreamService implements ApplicationContextAware {
    private final RedisTemplate<String, Object> redisTemplate;
    private ApplicationContext applicationContext;

    @Value("${server.port:8888}")
    private String serverPort;

    // 资源保护：限制Stream最大长度
    @Value("${shortlink.cache.stream.maxlen:10000}")
    private long streamMaxLen;

    // 运行状态控制，保证在多线程下的可见性
    private final AtomicBoolean running = new AtomicBoolean(false);
    private String nodeId;
    private String consumerName;
    private String consumerGroup;

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
    }

    /**
     * 一旦依赖注入就会执行，创建消费者组
     */
    @PostConstruct
    public void init() {
        try {
            // 1.生成节点id，消费者名字
            nodeId = InetAddress.getLocalHost().getHostAddress() + ":" + serverPort;
            consumerName = "cache-consumer-" + nodeId;
            consumerGroup = CONSUMER_GROUP + "-" + nodeId;
            // 2.创建Redis Stream消费者组
            try {
                redisTemplate.opsForStream().createGroup(STREAM_KEY, ReadOffset.latest(), consumerGroup);
                log.info("创建本地缓存Stream消费者组： {}", consumerGroup);
            } catch (Exception e) {
                log.info("本地缓存消费者组已经存在： {}",  consumerGroup);
            }

            log.info("LocalCacheStreamService初始化完成 - 节点: {}", nodeId);

        } catch (Exception e) {
            log.error("初始化LocalCacheStreamService失败", e);
        }
    }

    /**
     * 监听Stream消费者组
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        LocalCacheStreamService proxy = applicationContext.getBean(LocalCacheStreamService.class);
        proxy.startConsume();
    }

    /**
     * 使用独立的线程池来获取消息并消费
     */
    @Async("bloomFilterExecutor")
    public void startConsume() {
        log.info("本地缓存消费者线程启动 - 线程名 : {}, 节点 ：{}",
                Thread.currentThread().getName(), nodeId);
        running.set(true);
        while (running.get()) {
            try {
                // 1.获取Redis Stream中的消息
                List<MapRecord<String, Object, Object>> records = redisTemplate.opsForStream()
                        .read(Consumer.from(consumerGroup, consumerName),
                                StreamReadOptions.empty().count(50).block(Duration.ofSeconds(2)),
                                StreamOffset.create(STREAM_KEY, ReadOffset.lastConsumed()));
                // 2.消费消息
                if (records != null && !records.isEmpty()) {
                    processRecords(records);
                }
            } catch (Exception e) {
                log.error("消费本地缓存Stream消息失败", e);
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        log.info("本地缓存Stream消费者已停止: {}", consumerName);
    }

    /**
     * 消费者消费消息
     * @param records 待消费的消息
     */
    private void processRecords(List<MapRecord<String, Object, Object>> records) {
        for  (MapRecord<String, Object, Object> record : records) {
            try {
                Map<Object, Object> message = record.getValue();
                String action = toStringValue(message.get("action"));
                String shortCode = toStringValue(message.get("shortCode"));
                String sourceNode = toStringValue(message.get("sourceNode"));
                Long timestamp = toLongValue(message.get("timestamp"));
                // 不能消费自己的消费
                if (sourceNode != null && !sourceNode.equals(nodeId)) {
                    switch (action) {
                        case "PUT" ->{
                            // 从Redis缓存中获取完整数据并同步到本地缓存
                            syncCacheFromRedis(shortCode, sourceNode);
                        }
                        case "EVICT" -> {
                            // 从本地缓存中删除数据
                            LocalCacheService localCacheService = applicationContext.getBean(LocalCacheService.class);
                            // 无广播删除
                            localCacheService.evictFromLocalCacheOnly(shortCode);

                        }
                        default -> log.warn("未知的缓存操作: {}", action);
                    }
                }

                if (timestamp != null) {
                    log.trace("处理本地缓存Stream消息, timestamp={}", timestamp);
                }
                // 确认消息处理完成(ACK)
                redisTemplate.opsForStream().acknowledge(STREAM_KEY, consumerGroup, record.getId());
            } catch (Exception e) {
                log.error("处理本地缓存Stream记录失败: {}", record, e);
            }
        }
    }

    private String toStringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private Long toLongValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 将Redis缓存数据同步到本地缓存
     * @param shortCode 短码
     * @param sourceNode 当前节点id
     */
    private void syncCacheFromRedis(String shortCode, String sourceNode) {
        try {
            // 1.根据短码从Redis集群中获取实体类
            ClusterAwareCacheService clusterAwareCacheService =
                    applicationContext.getBean(ClusterAwareCacheService.class);
            ShortUrlMapping mapping = clusterAwareCacheService.getFromCache(shortCode);
            // 2.将数据更新到本地缓存
            if  (mapping != null) {
                LocalCacheService localCacheService = applicationContext.getBean(LocalCacheService.class);
                // 无广播
                localCacheService.putToLocalCacheOnly(shortCode, mapping);
                log.debug("从Stream同步数据到本地缓存: {} (来源: {})", shortCode, sourceNode);
            }
        } catch (Exception e) {
            log.error("从Redis同步数据到本地缓存失败: shortCode={}, sourceNode={}",
                    shortCode, sourceNode, e);
        }

    }


    /**
     * 发布事件
     */
    public void publishCacheEvent(String shortCode, String action) {
        try {
            // 1.获取消息
            Map<String, Object> message = Map.of(
                    "shortCode", shortCode,
                    "sourceNode", nodeId,
                    "action", action,
                    "timestamp", System.currentTimeMillis()
            );
            // 2.将消息放入Redis Stream中
            RecordId recordId = redisTemplate.opsForStream().add(STREAM_KEY,
                    message,
                    RedisStreamCommands.XAddOptions.maxlen(streamMaxLen).approximateTrimming(true));
            log.debug("发布本地缓存{}事件到Stream: {} (recordId: {})", action, shortCode, recordId);
        } catch (Exception e) {
            log.error("发布本地缓存事件到Stream失败: shortCode={}, action={}", shortCode, action, e);
        }
    }

    /**
     * 发布添加到本地缓存事件
     * @param shortCode 短码
     */
    public void publishCachePut(String shortCode) {
        publishCacheEvent(shortCode, "PUT");
    }

    /**
     * 发布删除本地缓存数据时间
     * @param shortCode 短码
     */
    public void publishCacheEvict(String shortCode) {
        publishCacheEvent(shortCode, "EVICT");
    }

    @PreDestroy
    public void shutdown() {
        running.set(false);
        try {
            // 核心修改：停机时销毁当前节点的消费者组，防止Redis元数据堆积
            redisTemplate.opsForStream().destroyGroup(STREAM_KEY, consumerGroup);
            log.info("已清理并关闭本地缓存Stream服务: {}", nodeId);
        } catch (Exception e) {
            log.warn("清理消费者组失败: {}", consumerGroup);
        }
        log.info("正在关闭本地缓存Stream服务...");
    }

    public String getNodeId() {
        return nodeId;
    }

}
