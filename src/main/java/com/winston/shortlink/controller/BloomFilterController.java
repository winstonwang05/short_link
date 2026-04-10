package com.winston.shortlink.controller;

import com.winston.shortlink.dto.ApiResponse;
import com.winston.shortlink.service.BloomFilterStreamService;
import com.winston.shortlink.service.RedisTimeBasedBloomFilterService;
import com.winston.shortlink.service.TieredBloomFilterService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/bloom")
@RequiredArgsConstructor
public class BloomFilterController {

    private final TieredBloomFilterService tieredBloomFilterService;
    private final BloomFilterStreamService streamService;
    private final RedisTimeBasedBloomFilterService redisTimeBasedBloomFilterService;

    @Value("${shortlink.bloom.false-probability:0.01}")
    private double falsePositiveRate;

    @GetMapping("/status")
    public ApiResponse<Map<String, Object>> getStatus() {
        Map<String, Object> data = Map.of(
                "enabled", true,
                "totalElements", 0L,
                "falsePositiveRate", falsePositiveRate,
                "currentSlice", redisTimeBasedBloomFilterService.getCurrentTimeSliceKey(),
                "sliceCount", 0,
                "nodeInfo", tieredBloomFilterService.getNodeInfo(),
                "nodeId", streamService.getNodeId()
        );
        return ApiResponse.success(data);
    }

    @GetMapping("/check/{shortCode}")
    public ApiResponse<Map<String, Object>> checkShortCode(@PathVariable("shortCode") String shortCode) {
        boolean exists = tieredBloomFilterService.mightContain(shortCode);
        return ApiResponse.success(Map.of(
                "shortCode", shortCode,
                "mightExist", exists,
                "nodeId", streamService.getNodeId()
        ));
    }

    @PostMapping("/add/{shortCode}")
    public ApiResponse<Map<String, Object>> addShortCodePath(@PathVariable("shortCode") String shortCode) {
        return doAddShortCode(shortCode);
    }

    @PostMapping("/add")
    public ApiResponse<Map<String, Object>> addShortCodeBody(@RequestBody(required = false) Map<String, String> body) {
        String shortCode = body == null ? null : body.get("shortCode");
        return doAddShortCode(shortCode);
    }

    @PostMapping("/rebuild")
    public ApiResponse<Map<String, Object>> rebuild() {
        String currentSlice = redisTimeBasedBloomFilterService.getCurrentTimeSliceKey();
        redisTimeBasedBloomFilterService.createRedisTimeSlice(currentSlice);
        return ApiResponse.success(Map.of(
                "message", "Bloom filter rebuild trigger accepted",
                "currentSlice", currentSlice,
                "nodeId", streamService.getNodeId()
        ));
    }

    private ApiResponse<Map<String, Object>> doAddShortCode(String shortCode) {
        if (shortCode == null || shortCode.trim().isEmpty()) {
            return ApiResponse.badRequest("shortCode is required");
        }
        String trimmed = shortCode.trim();
        tieredBloomFilterService.put(trimmed);
        return ApiResponse.success(Map.of(
                "message", "Short code added to bloom filter",
                "shortCode", trimmed,
                "nodeId", streamService.getNodeId()
        ));
    }
}
