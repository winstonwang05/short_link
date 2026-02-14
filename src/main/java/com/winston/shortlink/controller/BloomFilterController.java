package com.winston.shortlink.controller;

import com.winston.shortlink.service.BloomFilterStreamService;
import com.winston.shortlink.service.TieredBloomFilterService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * @description: 布隆过滤器控制器
 * @author: Winston
 * @date: 2026/2/13 22:33
 * @version: 1.0
 */
@RestController
@RequestMapping("/api/bloom")
@RequiredArgsConstructor
public class BloomFilterController {

    private final TieredBloomFilterService tieredBloomFilterService;
    private final BloomFilterStreamService streamService;

    /**
     * 获取布隆过滤器状态
     */
    @GetMapping("/status")
    public Map<String, Object> getStatus() {
        return Map.of(
                "nodeInfo", tieredBloomFilterService.getNodeInfo(),
                "nodeId", streamService.getNodeId()
        );
    }

    /**
     * 手动测试短链是否存在
     */
    @GetMapping("/check/{shortCode}")
    public Map<String, Object> checkShortCode(@PathVariable("shortCode") String shortCode) {
        boolean exists = tieredBloomFilterService.mightContain(shortCode);
        return Map.of(
                "shortCode", shortCode,
                "mightExist", exists,
                "nodeId", streamService.getNodeId()
        );
    }

    /**
     * 手动添加短链（测试用）
     */
    @PostMapping("/add/{shortCode}")
    public Map<String, Object> addShortCode(@PathVariable("shortCode") String shortCode) {
        tieredBloomFilterService.put(shortCode);
        return Map.of(
                "message", "短链已添加到布隆过滤器",
                "shortCode", shortCode,
                "nodeId", streamService.getNodeId()
        );
    }

}
