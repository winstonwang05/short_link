package com.winston.shortlink.controller;


import com.winston.shortlink.generator.ShortCodeGenerator;
import com.winston.shortlink.service.ClockSyncMonitorService;
import com.winston.shortlink.service.MachineIdService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.bouncycastle.asn1.x500.style.RFC4519Style.cn;

/**
 * 系统监控接口
 */
@Slf4j
@RestController
@RequestMapping("/api/monitor")
@RequiredArgsConstructor
public class MonitorController {

    private final ShortCodeGenerator shortCodeGenerator;
    private final MachineIdService machineIdService;
    private final ClockSyncMonitorService clockSyncMonitorService;
    
    /**
     * 获取ID生成器状态
     */
    @GetMapping("/generator-status")
    public Map<String, Object> getGeneratorStatus() {
        ShortCodeGenerator.GeneratorStatus status = shortCodeGenerator.getStatus();
        
        Map<String, Object> result = new HashMap<>();
        result.put("machineId", status.getMachineId());
        result.put("configuredLength", status.getConfiguredLength());
        result.put("lastTimestamp", status.getLastTimestamp());
        result.put("lastTimestampFormatted", Instant.ofEpochMilli(status.getLastTimestamp()).toString());
        result.put("currentSequence", status.getCurrentSequence());
        result.put("maxValue", status.getMaxValue());
        result.put("clockBackwardsStats", Map.of(
                "small", status.getSmallBackwardsCount(),
                "medium", status.getMediumBackwardsCount(),
                "severe", status.getSevereBackwardsCount()
        ));
        
        // 时钟信息
        long localTime = System.currentTimeMillis();
        long referenceTime = clockSyncMonitorService.getReferenceTime();
        result.put("clockInfo", Map.of(
                "localTime", localTime,
                "localTimeFormatted", Instant.ofEpochMilli(localTime).toString(),
                "referenceTime", referenceTime,
                "referenceTimeFormatted", Instant.ofEpochMilli(referenceTime).toString(),
                "drift", localTime - referenceTime
        ));
        
        return result;
    }
    
    /**
     * 生成测试ID
     */
    @GetMapping("/generate-test-id")
    public Map<String, Object> generateTestId() {
        long id = shortCodeGenerator.generateId();
        String shortCode = shortCodeGenerator.generateShortCode();
        
        Map<String, Object> result = new HashMap<>();
        result.put("id", id);
        result.put("shortCode", shortCode);
        result.put("timestamp", System.currentTimeMillis());
        result.put("machineId", machineIdService.getMachineId());
        
        return result;
    }
}