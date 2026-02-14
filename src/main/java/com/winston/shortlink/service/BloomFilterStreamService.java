package com.winston.shortlink.service;

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

/**
 * @description: Redis Stream实现本地布隆过滤器的数据一致性服务
 * @author: Winston
 * @date: 2026/2/13 12:25
 * @version: 1.0
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class BloomFilterStreamService implements ApplicationContextAware {

    private final RedisTemplate<String, Object> redisTemplate;

    private final LocalBloomFilterService localBloomFilterService;

    private ApplicationContext applicationContext;

    @Value("${server.port:8888}")
    private String serverPort;

    @Value("${shortlink.bloom.stream.maxlen:100000}")
    private long streamMaxLen; // 限制Stream长度，防止OOM

    private static final String STREAM_KEY = "bloom_filter_stream";
    private static final String CONSUMER_GROUP = "bloom_sync_group";

    private final AtomicBoolean running = new AtomicBoolean(false);
    private String nodeId;
    private String consumerName;
    private String consumerGroup;

    @Override
    public void setApplicationContext(ApplicationContext applicationContext) throws BeansException {
        this.applicationContext = applicationContext;
    }

    /**
     * 初始化，将消费者组添加唯一标识
     */
    @PostConstruct
    public void init() {
        try {
            // 生成节点id
            nodeId = InetAddress.getLocalHost().getHostAddress()+ ":" + serverPort;
            consumerName = "consumer-" + nodeId;
            consumerGroup = CONSUMER_GROUP +  "-" + nodeId;
            // 创建消费者组
            try {
                redisTemplate.opsForStream().createGroup(STREAM_KEY, ReadOffset.latest(), consumerGroup);
                log.info("创建Redis Stream消费者组： {}", consumerGroup);
            } catch (Exception e) {
                log.info("消费者组已存在: {}", consumerGroup);
            }

            log.info("BloomFilterStreamService初始化完成");
        } catch (Exception e) {
            log.error("初始化BloomFilterStreamService失败", e);
        }
    }

    /**
     * 监听Redis Stream，获取消息并消费
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        log.info("应用启动完成，开始启动布隆过滤器Stream消费者");
        BloomFilterStreamService proxy = applicationContext.getBean(BloomFilterStreamService.class);
        proxy.startConsumer();
    }

    /**
     * 异步线程池消费消息,死循环不断获取消息并消费
     */
    @Async("bloomFilterExecutor")
    public void startConsumer() {
        log.info("消费者线程启动 - 线程名: {}, 线程ID: {}",
                Thread.currentThread().getName(),
                Thread.currentThread().getId());
        running.set(true);
        log.info("启动布隆过滤器Stream消费者: {}", consumerName);

        while (running.get()) {
            try {
                    // 1.获取消息
                List<MapRecord<String, Object, Object>> message = redisTemplate.opsForStream().read(Consumer.from(consumerGroup, consumerName),
                        StreamReadOptions.empty().count(50).block(Duration.ofSeconds(2)),
                        StreamOffset.create(STREAM_KEY, ReadOffset.lastConsumed()));
                // 2.消费消息
                if  (message != null && !message.isEmpty()) {
                    processRecords(message);
                }
            } catch (Exception e) {
                log.error("消费布隆过滤器Stream消息失败", e);
                try {
                    Thread.sleep(1000);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
        log.info("布隆过滤器Stream消费者已停止: {}", consumerName);
    }


    /**
     * 消费消息，添加到本地时间片布隆过滤器中
     * @param records 消息
     */
    public void processRecords(List<MapRecord<String, Object, Object>> records) {
        for (MapRecord<String, Object, Object> record : records) {
            try {
                // 1.获取消息
                String shortCode = (String) record.getValue().get("shortCode");
                String sourceNode = (String) record.getValue().get("sourceNode");
                String action = (String) record.getValue().get("action");
                Long timeStamp = (Long) record.getValue().get("timeStamp");
                // 2.消费消息，添加到本地布隆过滤器中
                // 不能重复消费
                if (!nodeId.equals(sourceNode)) {
                    if ("ADD".equals(action)) {
                        localBloomFilterService.addLocal(shortCode);
                        log.debug("从Stream同步短链到本地布隆过滤器: {} (来源: {})", shortCode, sourceNode);
                    }
                }
                // 3. 确认消息处理完成
                redisTemplate.opsForStream().acknowledge(STREAM_KEY, consumerGroup, record.getId());
            } catch (Exception e) {
                log.error("处理Stream记录失败: {}", record, e);
            }
        }
    }

    /**
     * 发布新增的短码到Stream中
     * @param shortCode 待消费的消息
     */
    public void publishNewShortCode(String shortCode) {
        try {
            Map<String, Object> message = Map.of(
                    "shortCode", shortCode,
                    "sourceNode", nodeId,
                    "action", "ADD",
                    "timestamp", System.currentTimeMillis()
            );
            // 手动裁剪，获取最新的streamMaxLen条数据
            RecordId recordId = redisTemplate.opsForStream().add(STREAM_KEY,
                    message,
                    RedisStreamCommands.XAddOptions.maxlen(streamMaxLen).approximateTrimming(true));
            log.debug("发布短链到Stream: {} (recordId: {},  MaxLen: {})", shortCode, recordId, streamMaxLen);
        } catch (Exception e) {
            log.error("发布短链到Stream失败: shortCode={}", shortCode, e);
        }
    }

    /**
     * 批量广播
     * @param shortCodes 批量消息
     */
    public void publishBatchShortCodes(List<String> shortCodes) {
        try {
            for (String shortCode : shortCodes) {
                publishNewShortCode(shortCode);
            }
            log.info("批量发布{}个短链到Stream", shortCodes.size());
        } catch (Exception e) {
            log.error("批量发布短链到Stream失败", e);
        }
    }

    /**
     * 优雅销毁，包括销毁存在的僵尸消费者组
     */
    @PreDestroy
    public void destroy() {
        running.set(false);
        log.info("正在关闭布隆过滤器Stream服务...");
        try {
            redisTemplate.opsForStream().destroyGroup(STREAM_KEY, consumerGroup);
        } catch  (Exception e) {
            log.warn("清理消费者组失败: {}", consumerGroup);
        }
    }

    public String getNodeId() {
        return nodeId;
    }

}
