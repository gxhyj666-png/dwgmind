package com.dwgmind.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * SSE 进度推送服务
 *
 * 图纸解析是长任务，用 SSE 把「上传 → 感知层 → 语义层 → 应用层」每一步进度
 * 实时推给前端，形成「10 秒出结果」的可见过程。
 */
@Service
public class ProgressService {

    private static final Logger log = LoggerFactory.getLogger(ProgressService.class);
    private final Map<String, SseEmitter> emitters = new ConcurrentHashMap<>();
    private final Map<String, Long> lastSeen = new ConcurrentHashMap<>();
    private final ExecutorService pool = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "sse-dispatch");
        t.setDaemon(true);
        return t;
    });

    public SseEmitter subscribe(String taskId) {
        SseEmitter emitter = new SseEmitter(30 * 60 * 1000L); // 30 分钟
        emitters.put(taskId, emitter);
        lastSeen.put(taskId, System.currentTimeMillis());
        emitter.onCompletion(() -> cleanup(taskId));
        emitter.onTimeout(() -> cleanup(taskId));
        emitter.onError(e -> cleanup(taskId));
        // 立刻发一条连接确认
        send(taskId, Map.of("stage", "CONNECTED", "message", "进度通道已建立", "percent", 0));
        return emitter;
    }

    public void send(String taskId, Map<String, Object> payload) {
        lastSeen.put(taskId, System.currentTimeMillis());
        SseEmitter emitter = emitters.get(taskId);
        if (emitter == null) return;
        pool.submit(() -> {
            try {
                emitter.send(SseEmitter.event().name("progress").data(payload));
            } catch (IOException | IllegalStateException e) {
                cleanup(taskId);
            }
        });
    }

    public void complete(String taskId, Map<String, Object> payload) {
        SseEmitter emitter = emitters.get(taskId);
        if (emitter == null) return;
        pool.submit(() -> {
            try {
                emitter.send(SseEmitter.event().name("done").data(payload));
                emitter.complete();
            } catch (IOException | IllegalStateException e) {
                log.debug("SSE 完成推送异常: {}", e.getMessage());
            } finally {
                cleanup(taskId);
            }
        });
    }

    public void fail(String taskId, String message) {
        SseEmitter emitter = emitters.get(taskId);
        if (emitter == null) return;
        pool.submit(() -> {
            try {
                emitter.send(SseEmitter.event().name("error").data(Map.of("message", message)));
                emitter.complete();
            } catch (IOException | IllegalStateException ignored) {
            } finally {
                cleanup(taskId);
            }
        });
    }

    private void cleanup(String taskId) {
        emitters.remove(taskId);
        lastSeen.remove(taskId);
    }

    @PreDestroy
    public void shutdown() {
        pool.shutdownNow();
        emitters.values().forEach(SseEmitter::complete);
        emitters.clear();
    }
}
