package com.winston.shortlink.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.Map;

/**
 * 钉钉通知服务
 * 支持告警通知和业务消息推送
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class DingTalkNotificationService {

    private final WebClient webClient = WebClient.builder().build();
    private final ObjectMapper objectMapper;

    @Value("${dingtalk.webhook.url:}")
    private String webhookUrl;

    @Value("${dingtalk.webhook.secret:}")
    private String secret;

    /**
     * 发送告警消息
     */
    public void sendAlertMessage(String alertName, String severity, String description, String instance) {
        try {
            String title = String.format("🚨 短链系统告警 - %s", alertName);
            String content = buildAlertContent(alertName, severity, description, instance);
            sendMarkdownMessage(title, content);
        } catch (Exception e) {
            log.error("发送钉钉告警消息失败", e);
        }
    }

    /**
     * 发送业务消息
     */
    public void sendBusinessMessage(String title, String content) {
        try {
            sendMarkdownMessage(title, content);
        } catch (Exception e) {
            log.error("发送钉钉业务消息失败", e);
        }
    }

    /**
     * 发送系统状态消息
     */
    public void sendSystemStatusMessage(String qps, String responseTime, String cacheHitRate) {
        try {
            String title = "📊 短链系统状态报告";
            String content = buildSystemStatusContent(qps, responseTime, cacheHitRate);
            sendMarkdownMessage(title, content);
        } catch (Exception e) {
            log.error("发送系统状态消息失败", e);
        }
    }

    private void sendMarkdownMessage(String title, String content) {
        if (webhookUrl == null || webhookUrl.isEmpty()) {
            log.warn("钉钉webhook URL未配置，跳过消息发送");
            return;
        }

        Map<String, Object> message = new HashMap<>();
        message.put("msgtype", "markdown");
        
        Map<String, Object> markdown = new HashMap<>();
        markdown.put("title", title);
        markdown.put("text", content);
        message.put("markdown", markdown);

        // 添加@所有人（可选）
        Map<String, Object> at = new HashMap<>();
        at.put("isAtAll", false);
        message.put("at", at);

        webClient.post()
                .uri(webhookUrl)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(message)
                .retrieve()
                .bodyToMono(String.class)
                .doOnSuccess(response -> log.info("钉钉消息发送成功: {}", response))
                .doOnError(error -> log.error("钉钉消息发送失败", error))
                .subscribe();
    }

    private String buildAlertContent(String alertName, String severity, String description, String instance) {
        return String.format(
                "### 🚨 短链系统告警\n\n" +
                "**告警名称:** %s\n\n" +
                "**告警级别:** %s\n\n" +
                "**实例:** %s\n\n" +
                "**描述:** %s\n\n" +
                "**时间:** %s\n\n" +
                "**处理建议:**\n" +
                "- 立即检查服务状态\n" +
                "- 查看系统监控面板\n" +
                "- 检查日志文件\n",
                alertName, severity, instance, description,
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        );
    }

    private String buildSystemStatusContent(String qps, String responseTime, String cacheHitRate) {
        return String.format(
                "### 📊 短链系统状态报告\n\n" +
                "**当前QPS:** %s\n\n" +
                "**平均响应时间:** %s ms\n\n" +
                "**缓存命中率:** %s%%\n\n" +
                "**报告时间:** %s\n\n" +
                "系统运行正常 ✅",
                qps, responseTime, cacheHitRate,
                LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
        );
    }
}