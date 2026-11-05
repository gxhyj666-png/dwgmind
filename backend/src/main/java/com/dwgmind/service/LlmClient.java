package com.dwgmind.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 大模型客户端（可选增强层）
 *
 * 支持两类 OpenAI 兼容接口：
 *   provider=deepseek  → https://api.deepseek.com/chat/completions
 *   provider=dashscope → https://dashscope.aliyuncs.com/compatible-mode/v1/chat/completions
 *
 * 设计原则：**不配置 Key 也能完整运行**。
 * 未配置时健康检查返回 false，语义增强自动降级为纯规则引擎，
 * 这样项目在离线/无密钥环境下依旧可演示、可评测。
 */
@Component
public class LlmClient {

    private static final Logger log = LoggerFactory.getLogger(LlmClient.class);
    private final ObjectMapper mapper = new ObjectMapper();
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Value("${dwgmind.llm.enabled:true}")
    private boolean enabled;

    @Value("${dwgmind.llm.provider:deepseek}")
    private String provider;

    @Value("${dwgmind.llm.base-url:https://api.deepseek.com}")
    private String baseUrl;

    @Value("${dwgmind.llm.api-key:}")
    private String apiKey;

    @Value("${dwgmind.llm.model:deepseek-chat}")
    private String model;

    @Value("${dwgmind.llm.timeout-seconds:30}")
    private int timeoutSeconds;

    /** 是否具备真实调用能力 */
    public boolean isConfigured() {
        return enabled && apiKey != null && !apiKey.isBlank();
    }

    public String getProvider() {
        return provider;
    }

    public String getModel() {
        return model;
    }

    /**
     * 单轮对话补全（非流式）
     *
     * @param system 系统提示词
     * @param user   用户输入
     * @return 模型输出；不可用时返回 null（调用方需降级）
     */
    public String chat(String system, String user) {
        if (!isConfigured()) {
            log.debug("LLM 未配置，跳过增强");
            return null;
        }
        try {
            Map<String, Object> body = Map.of(
                    "model", model,
                    "messages", List.of(
                            Map.of("role", "system", "content", system),
                            Map.of("role", "user", "content", user)),
                    "temperature", 0.2,
                    "stream", false
            );
            String url = trimSlash(baseUrl) + "/chat/completions";
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(timeoutSeconds))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(
                            mapper.writeValueAsString(body), StandardCharsets.UTF_8))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            if (resp.statusCode() != 200) {
                log.warn("LLM 调用失败 HTTP {}: {}", resp.statusCode(),
                        truncate(resp.body(), 300));
                return null;
            }
            JsonNode root = mapper.readTree(resp.body());
            JsonNode content = root.path("choices").path(0).path("message").path("content");
            return content.isMissingNode() ? null : content.asText();
        } catch (Exception e) {
            log.warn("LLM 调用异常: {}", e.getMessage());
            return null;
        }
    }

    /** 健康状态（用于 /api/v1/health 与前端能力提示） */
    public Map<String, Object> status() {
        return Map.of(
                "configured", isConfigured(),
                "provider", provider,
                "model", model,
                "mode", isConfigured() ? "LLM_ENHANCED" : "RULE_ONLY",
                "hint", isConfigured()
                        ? "大模型增强已启用（语义推理由规则层 + 大模型共同完成）"
                        : "未配置 LLM_API_KEY，当前以纯规则引擎运行；配置环境变量即可开启大模型增强"
        );
    }

    private String trimSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    private String truncate(String s, int n) {
        if (s == null) return "";
        return s.length() <= n ? s : s.substring(0, n) + "...";
    }
}
