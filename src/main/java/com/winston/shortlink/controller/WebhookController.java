package com.winston.shortlink.controller;


import com.winston.shortlink.service.DingTalkNotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Webhook控制器
 * 处理AlertManager的告警回调
 */
@Slf4j
@RestController
@RequestMapping("/webhook")
@RequiredArgsConstructor
public class WebhookController {

    private final DingTalkNotificationService dingTalkService;

    /**
     * 处理AlertManager告警回调
     */
    @PostMapping("/dingtalk/alert")
    public String handleAlert(@RequestBody Map<String, Object> payload) {
        try {
            log.info("收到AlertManager告警回调: {}", payload);
            
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> alerts = (List<Map<String, Object>>) payload.get("alerts");
            
            if (alerts != null && !alerts.isEmpty()) {
                for (Map<String, Object> alert : alerts) {
                    processAlert(alert);
                }
            }
            
            return "success";
        } catch (Exception e) {
            log.error("处理告警回调失败", e);
            return "error";
        }
    }

    private void processAlert(Map<String, Object> alert) {
        @SuppressWarnings("unchecked")
        Map<String, Object> labels = (Map<String, Object>) alert.get("labels");
        @SuppressWarnings("unchecked")
        Map<String, Object> annotations = (Map<String, Object>) alert.get("annotations");
        
        String alertName = (String) labels.get("alertname");
        String severity = (String) labels.get("severity");
        String instance = (String) labels.get("instance");
        String description = (String) annotations.get("description");
        
        dingTalkService.sendAlertMessage(alertName, severity, description, instance);
    }
}