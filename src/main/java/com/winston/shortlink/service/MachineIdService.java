package com.winston.shortlink.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RMap;
import org.redisson.api.RMapCache;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Service;

import java.lang.management.ManagementFactory;
import java.net.InetAddress;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static com.winston.shortlink.constant.RedisKeyConstants.*;

/**
 * @description: 机器ID分配服务，保证机器ID在分布式下唯一
 * @author: Winston
 * @date: 2026/2/14 11:07
 * @version: 1.0
 */
@Slf4j
@Service
    public class MachineIdService implements InitializingBean {

    private final RedissonClient redissonClient;
    /**
     * 分布式锁服务
     */
    private final DistributedLockService distributedLockService;

    private volatile long machineId = -1;
    private String nodeIdentifier;

    public MachineIdService (RedissonClient redissonClient, DistributedLockService distributedLockService) {
        this.redissonClient = redissonClient;
        this.distributedLockService = distributedLockService;
    }

    @Override
    public void afterPropertiesSet() throws Exception {
        try {
            // 获取当前节点信息
            nodeIdentifier = getNodeIdentifier();
            // 分配机器ID
            assignMachineId();
            // 开启心跳检测
            startHeartBeat();
            log.info("机器ID服务初始化完成 - 节点: {}, 机器ID: {}", nodeIdentifier, machineId);
        } catch (Exception e) {
            log.error("机器ID服务初始化失败", e);
            throw new RuntimeException("无法初始化机器ID服务", e);
        }
    }

    /**
     * 分配机器ID
     */
    private void assignMachineId() {
        // 1.获取MapCache对象，key节点标识，value是所分配的机器ID
        RMapCache<String, Long> machineIdMap = redissonClient.getMapCache(MACHINE_ID_KEY);
        // 2.判断MapCache是否存在该节点的机器ID，有就复用
        if (machineIdMap.containsKey(nodeIdentifier)) {
            machineId =  machineIdMap.get(nodeIdentifier);
            log.info("复用已分配的机器ID: {}", machineId);
            return;
        }
        // 3.没有，需要分布式锁下生成
        machineId = distributedLockService.executeWithLock(MACHINE_ID_LOCK, 10, 30, TimeUnit.SECONDS, () -> {
            // 4.获取到分布式锁之后，双重检查之后再生成机器ID
            if (machineIdMap.containsKey(nodeIdentifier)) {
                return machineIdMap.get(nodeIdentifier);
            }
            // 获取已经正在使用过的ID
            Set<Long> usedIds = new HashSet<>(machineIdMap.values());
            for (long i = 0; i <= MAX_MACHINE_ID; i++) {
                if  (!usedIds.contains(i)) {
                    machineIdMap.putIfAbsent(nodeIdentifier, i, MACHINE_ID_EXPIRE_TIME, TimeUnit.SECONDS);
                    log.info("自动分配机器ID: {}", i);
                    return i;
                }
            }
            throw new RuntimeException("无可用机器ID，已达到最大限制: " + MAX_MACHINE_ID);
        });
    }

    /**
     * 获取节点唯一标识
     * @return 返回唯一标识
     */
    private String getNodeIdentifier() {
        try {
            String hostName = InetAddress.getLocalHost().getHostName();
            String ip = InetAddress.getLocalHost().getHostAddress();
            String pid = ManagementFactory.getRuntimeMXBean().getName().split("@")[0];
            return String.format("%s-%s-%s", hostName, ip, pid);
        } catch (Exception e) {
            // 备用方案
            String randomId = String.valueOf(System.currentTimeMillis() % 100000);
            log.warn("无法获取节点信息，使用备用标识: node-{}", randomId);
            return "node-" + randomId;
        }
    }

    /**
     * 启动心跳任务， 定期更新机器ID注册表
     */
    private void startHeartBeat() {
        Thread heartBeatThread = new Thread(() -> {
            RMapCache<Object, Object> machineIdMap = redissonClient.getMapCache(MACHINE_ID_KEY);
            while (!Thread.currentThread().isInterrupted()) {
                try {
                    // 更新心跳时间, 也就是覆盖原来的, 并设置有效期为60秒
                    machineIdMap.put(nodeIdentifier, machineId, MACHINE_ID_EXPIRE_TIME,  TimeUnit.SECONDS);
                    // 每三十秒检查一次
                    Thread.sleep(30000);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.info("机器ID心跳线程被中断");
                    break;
                } catch (Exception e) {
                    log.error("机器ID心跳更新失败", e);
                    try {
                        Thread.sleep(5000); // 失败后短暂等待
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        });
        heartBeatThread.setName("machine-id-heartbeat");
        heartBeatThread.setDaemon(true);
        heartBeatThread.start();

        // 添加关闭钩子，释放机器ID
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            try {
                RMap<String, Long> machineIdMap = redissonClient.getMap(MACHINE_ID_KEY);
                machineIdMap.remove(nodeIdentifier);
                log.info("应用关闭，释放机器ID: {}", machineId);
            } catch (Exception e) {
                log.error("释放机器ID失败", e);
            }
        }));

    }

    /**
     * 获取当前节点的机器ID
     */
    public long getMachineId() {
        if (machineId < 0) {
            throw new IllegalStateException("机器ID尚未初始化");
        }
        return machineId;
    }
}
