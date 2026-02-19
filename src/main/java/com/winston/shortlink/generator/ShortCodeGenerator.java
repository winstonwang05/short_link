package com.winston.shortlink.generator;

import com.winston.shortlink.config.ShortCodeConfig;
import com.winston.shortlink.service.ClockSyncMonitorService;
import com.winston.shortlink.service.MachineIdService;
import com.winston.shortlink.util.Base62Util;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * @description: 分布式短码生成器, 基于雪花算法，利用JDK21虚拟线程，支持动态配置短链长度，增强的始终回拨处理
 * @author: Winston
 * @date: 2026/2/14 23:58
 * @version: 1.0
 */
@Slf4j
@Component
public class ShortCodeGenerator {
    // 时间戳位数 - 根据短链长度动态计算，默认适配8位Base62
    private volatile long TIMESTAMP_BITS = 28L;
    // 机器ID位数 - 适配8位Base62
    private volatile long MACHINE_ID_BITS = 10L;
    // 序列号位数 - 适配8位Base62
    private volatile long SEQUENCE_BITS = 12L;

    // 最大值 - 动态计算
    private volatile long MAX_MACHINE_ID;
    private volatile long MAX_SEQUENCE;

    // 位移量 - 动态计算
    private volatile long MACHINE_ID_SHIFT;
    private volatile long TIMESTAMP_SHIFT;

    // 起始时间戳 (2024-01-01 00:00:00 UTC)
    private static final long START_TIMESTAMP = 1704067200000L;

    // 时钟回拨阈值
    private static final long CLOCK_BACKWARDS_SMALL_THRESHOLD = 5L; // 小幅回拨阈值(ms)
    private static final long CLOCK_BACKWARDS_MEDIUM_THRESHOLD = 50L; // 中等回拨阈值(ms)

    private final MachineIdService machineIdService;
    private final ClockSyncMonitorService clockSyncMonitorService;
    private final ShortCodeConfig shortCodeConfig;

    // 用来记录序列号在同一毫秒生成的个数，最大为4096
    private final AtomicLong sequence = new AtomicLong(0L);
    private volatile long lastTimestamp = -1L;
    private final ReentrantLock lock = new ReentrantLock();

    // 缓存最大值，避免重复计算
    private volatile long cachedMaxValue = -1L;
    private volatile int cachedLength = -1;

    // 时钟回拨统计
    private volatile int smallBackwardsCount = 0;
    private volatile int mediumBackwardsCount = 0;
    private volatile int severeBackwardsCount = 0;
    private volatile long lastBackwardsTime = 0L;

    /**
     * 构造方法注入
     */
    public ShortCodeGenerator(MachineIdService machineIdService,
                              ClockSyncMonitorService clockSyncMonitorService,
                              ShortCodeConfig shortCodeConfig) {
        this.machineIdService = machineIdService;
        this.clockSyncMonitorService = clockSyncMonitorService;
        this.shortCodeConfig = shortCodeConfig;

        // 根据短链长度动态计算位数分配
        calculateBitDistribution();

        long machineId = machineIdService.getMachineId();
        if (machineId < 0 || machineId > MAX_MACHINE_ID) {
            throw new IllegalArgumentException(
                    String.format("机器ID必须在0-%d之间，当前值: %d", MAX_MACHINE_ID, machineId));
        }

        log.info("短码生成器初始化完成 - 机器ID: {}, 配置长度: {}, 位数分配[时间:{}, 机器:{}, 序列:{}]",
                machineId, shortCodeConfig.getLength(), TIMESTAMP_BITS, MACHINE_ID_BITS, SEQUENCE_BITS);
    }

    /**
     * 根据短链长度动态计算位数分配
     * 目标：确保生成的ID能被Base62编码后不超过配置的长度
     */
    private void calculateBitDistribution() {
        int targetLength = shortCodeConfig.getLength();

        // 计算Base62在指定长度下的最大位数
        long maxValue = (long) Math.pow(Base62Util.getBase(), targetLength) - 1;
        int maxBits = Long.SIZE - Long.numberOfLeadingZeros(maxValue);

        // 预留机器ID和序列号位数
        long reservedBits = 22L; // 10位机器ID + 12位序列号

        // 时间戳位数 = 总位数 - 保留位数
        TIMESTAMP_BITS = Math.max(24, maxBits - reservedBits); // 至少保留24位时间戳
        MACHINE_ID_BITS = 10L;
        SEQUENCE_BITS = 12L;

        // 重新计算最大值和位移量
        MAX_MACHINE_ID = (1L << MACHINE_ID_BITS) - 1;
        MAX_SEQUENCE = (1L << SEQUENCE_BITS) - 1;
        MACHINE_ID_SHIFT = SEQUENCE_BITS;
        TIMESTAMP_SHIFT = SEQUENCE_BITS + MACHINE_ID_BITS;

        log.debug("位数计算完成 - 目标长度: {}位, 总位数: {}, 时间戳: {}, 机器ID: {}, 序列: {}",
                targetLength, maxBits, TIMESTAMP_BITS, MACHINE_ID_BITS, SEQUENCE_BITS);
    }

    /**
     * 生成唯一ID
     * @return 返回唯一ID
     */
    public long generateId() {
        lock.lock();
        try {
            // 1.获取当前时间戳判断是否出现时间回拨，如果出现
            long currentTimestamp = getCurrentTimestamp();
            if (currentTimestamp < lastTimestamp) {
                long offset = lastTimestamp - currentTimestamp;
                // 记录时钟回拨事件
                recordClockBackwards(offset);
                if (offset <= CLOCK_BACKWARDS_SMALL_THRESHOLD) {
                    // 小度回拨，等待追上
                    try {
                        Thread.sleep(offset << 1);
                        // 再次判断，如果还是回拨，直接用上一次的时间戳
                        currentTimestamp = getCurrentTimestamp();
                        if  (currentTimestamp < lastTimestamp) {
                            log.warn("等待后仍检测到时钟回拨({}ms)，使用上次时间戳", offset);
                            currentTimestamp = lastTimestamp;
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("等待时钟同步被中断", e);
                    }
                } else if  (offset <= CLOCK_BACKWARDS_MEDIUM_THRESHOLD) {
                    // 中等回拨，使用上次时间戳
                    log.warn("检测到中等时钟回拨({}ms)，使用上次时间戳", offset);
                    currentTimestamp = lastTimestamp;
                } else {
                    // 大幅回拨，使用备用时间源
                    log.error("检测到严重时钟回拨({}ms)，启用备用时间源", offset);
                    currentTimestamp = getBackupTimestamp();
                }
            }
            // 2.如果当前时间戳等于上一次生成的时间戳，计数+1
            if (currentTimestamp ==  lastTimestamp) {
                long seq = sequence.incrementAndGet() & MAX_SEQUENCE;
                if (seq == 0) {
                    // 3.如果序列计数满了，利用下一毫秒的时间戳，计数重置
                    currentTimestamp= waitNextMillis(currentTimestamp);
                    sequence.set(0L);
                }
            } else {
                // 新的毫秒，直接重置计数
                sequence.set(0L);
            }
            // 4.组装ID
            lastTimestamp = currentTimestamp;

            // 组装ID
            long id = ((currentTimestamp - START_TIMESTAMP) << TIMESTAMP_SHIFT)
                    | (machineIdService.getMachineId() << MACHINE_ID_SHIFT)
                    | sequence.get();
            // 5.对长度最大值取余
            // 确保ID不超过指定长度Base62编码的最大值
            long maxValue = getMaxValueForCurrentLength();
            return Math.abs(id) % maxValue;
        } finally {
            lock.unlock();
        }
    }


    /**
     * 记录始终回拨事件次数
     */
    private void recordClockBackwards(long offset) {
        long now = System.currentTimeMillis();

        // 更新统计
        if (offset <= CLOCK_BACKWARDS_SMALL_THRESHOLD) {
            smallBackwardsCount++;
        } else if (offset <= CLOCK_BACKWARDS_MEDIUM_THRESHOLD) {
            mediumBackwardsCount++;
        } else {
            severeBackwardsCount++;
        }

        // 避免日志过多，限制记录频率
        if (now - lastBackwardsTime > 60000) { // 每分钟最多记录一次详细日志
            log.warn("时钟回拨统计 - 小幅: {}, 中等: {}, 严重: {}",
                    smallBackwardsCount, mediumBackwardsCount, severeBackwardsCount);
            lastBackwardsTime = now;
        }
    }

    /**
     * 生成短码
     * @return 通过Base62工具将唯一ID生成短码
     */
    public String generateShortCode() {
        long id = generateId();
        int targetLength = shortCodeConfig.getLength();
        String shortCode = Base62Util.encodeWithExactLength(id, targetLength);
        // 确保不超过配置的长度
        if (shortCode.length() > targetLength) {
            shortCode = shortCode.substring(0, targetLength);
            log.debug("短码长度超限，已截取到{}位: {}", targetLength, shortCode);
        }

        return shortCode;
    }

    /**
     * 批量生成短码 (利用JDK21的虚拟线程)
     */
    public String[] generateBatchShortCodes(int count) {
        if (count <= 0) {
            throw new IllegalArgumentException("批量生成数量必须大于0");
        }

        if (count > 10000) {
            log.warn("批量生成数量较大: {}, 建议分批处理", count);
        }

        return java.util.stream.IntStream.range(0, count)
                .parallel()
                .mapToObj(i -> generateShortCode())
                .toArray(String[]::new);
    }

    /**
     * 获取当前配置长度对应的最大值 （带缓存优化--volatile）
     * @return 返回对应配置长度最大值
     */
    private long getMaxValueForCurrentLength() {
        int currentLength = shortCodeConfig.getLength();
        // 如果长度变化了，需要更新缓存的长度
        if (currentLength != cachedLength) {
            // 需要加锁更新，防止多线程下并发更新
            synchronized (this) {
                // 双重检查
                if (currentLength != cachedLength) {
                    cachedMaxValue = Base62Util.getMaxValue(currentLength);
                    cachedLength = currentLength;
                    log.debug("更新缓存的最大值: length={}, maxValue={}",
                            currentLength, cachedMaxValue);
                }
            }
        }
        return cachedMaxValue;
    }

    /**
     * 获取备用时间戳 （使用时钟同步服务）--Redis服务时钟
     * @return 返回时间戳
     */
    private long getBackupTimestamp() {
        long referenceTime = clockSyncMonitorService.getReferenceTime();

        // 最后保障，确保时间不会倒退
        return Math.max(lastTimestamp + 1, referenceTime);
    }

    /**
     * 获取当前时间戳
     */
    private long getCurrentTimestamp() {
        return Instant.now().toEpochMilli();
    }

    /**
     * 获取下一毫秒
     * @param lastTimestamp 上一次生成的时间戳
     * @return 返回下一毫秒的时间戳
     */
    private long waitNextMillis(long lastTimestamp) {
        long currentTimestamp = getCurrentTimestamp();
        while (currentTimestamp <= lastTimestamp) {
            Thread.onSpinWait();
            currentTimestamp = getCurrentTimestamp();
        }

        return currentTimestamp;
    }
    /**
     * 获取生成器状态信息（用于监控）
     */
    public GeneratorStatus getStatus() {
        return new GeneratorStatus(
                machineIdService.getMachineId(),
                shortCodeConfig.getLength(),
                lastTimestamp,
                sequence.get(),
                getMaxValueForCurrentLength(),
                smallBackwardsCount,
                mediumBackwardsCount,
                severeBackwardsCount
        );
    }

    /**
     * 生成器状态信息
     */
    public static class GeneratorStatus {
        private final long machineId;
        private final int configuredLength;
        private final long lastTimestamp;
        private final long currentSequence;
        private final long maxValue;
        private final int smallBackwardsCount;
        private final int mediumBackwardsCount;
        private final int severeBackwardsCount;

        public GeneratorStatus(long machineId, int configuredLength,
                               long lastTimestamp, long currentSequence, long maxValue,
                               int smallBackwardsCount, int mediumBackwardsCount, int severeBackwardsCount) {
            this.machineId = machineId;
            this.configuredLength = configuredLength;
            this.lastTimestamp = lastTimestamp;
            this.currentSequence = currentSequence;
            this.maxValue = maxValue;
            this.smallBackwardsCount = smallBackwardsCount;
            this.mediumBackwardsCount = mediumBackwardsCount;
            this.severeBackwardsCount = severeBackwardsCount;
        }

        // Getters
        public long getMachineId() { return machineId; }
        public int getConfiguredLength() { return configuredLength; }
        public long getLastTimestamp() { return lastTimestamp; }
        public long getCurrentSequence() { return currentSequence; }
        public long getMaxValue() { return maxValue; }
        public int getSmallBackwardsCount() { return smallBackwardsCount; }
        public int getMediumBackwardsCount() { return mediumBackwardsCount; }
        public int getSevereBackwardsCount() { return severeBackwardsCount; }

        @Override
        public String toString() {
            return String.format(
                    "GeneratorStatus{machineId=%d, length=%d, lastTimestamp=%d, sequence=%d, maxValue=%d, 时钟回拨统计[小=%d,中=%d,大=%d]}",
                    machineId, configuredLength, lastTimestamp, currentSequence, maxValue,
                    smallBackwardsCount, mediumBackwardsCount, severeBackwardsCount
            );
        }
    }


}
