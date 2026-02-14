package com.winston.shortlink.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * @description: 分布式锁服务
 * @author: Winston
 * @date: 2026/2/14 10:08
 * @version: 1.0
 */

@Slf4j
@Service
@RequiredArgsConstructor
public class DistributedLockService {
    private final RedissonClient redissonClient;

    private static final String LOCK_PREFIX = "shortlink:lock:";
    private static final long DEFAULT_WAIT_TIME = 3L; // 等待锁的时间
    private static final long DEFAULT_LEASE_TIME = 10L; // 锁的持有时间

    /**
     * 执行带锁的操作
     */
    public <T> T executeWithLock(String lockKey, Supplier<T> supplier) {
        return executeWithLock(lockKey, DEFAULT_WAIT_TIME, DEFAULT_LEASE_TIME, TimeUnit.SECONDS, supplier);
    }

    /**
     * 执行带锁的操作（自定义超时时间）
     */
    public <T> T executeWithLock(String lockKey, long waitTime, long leaseTime, TimeUnit timeUnit, Supplier<T> supplier) {
        String fullLockKey = LOCK_PREFIX + lockKey;
        RLock lock = redissonClient.getLock(fullLockKey);

        try {
            // 尝试获取锁
            boolean acquired = lock.tryLock(waitTime, leaseTime, timeUnit);
            if (!acquired) {
                log.warn("获取分布式锁失败: lockKey={}", fullLockKey);
                throw new RuntimeException("系统繁忙，请稍后重试");
            }

            log.debug("获取分布式锁成功: lockKey={}", fullLockKey);
            return supplier.get();

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("获取分布式锁被中断: lockKey={}", fullLockKey, e);
            throw new RuntimeException("操作被中断", e);
        } finally {
            // 释放锁
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
                log.debug("释放分布式锁: lockKey={}", fullLockKey);
            }
        }
    }
}
