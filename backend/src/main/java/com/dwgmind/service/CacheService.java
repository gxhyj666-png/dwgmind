package com.dwgmind.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;

/**
 * 缓存与榜单服务（Redis）
 *
 * 四个用途：
 *   1. 解析结果缓存（按文件 SHA-256 去重，同一张图纸不重复消费算力）
 *   2. 实时打榜 ZSET
 *   3. 接口限流计数
 *   4. 长任务进度 Hash
 *
 * Redis 不可用时全部降级为 no-op，保证主流程不被中间件拖垮。
 */
@Service
public class CacheService {

    private static final Logger log = LoggerFactory.getLogger(CacheService.class);
    private static final String PREFIX = "dwg:";

    private final StringRedisTemplate redis;

    @Value("${dwgmind.rate-limit.enabled:true}")
    private boolean rateLimitEnabled;

    @Value("${dwgmind.rate-limit.per-minute:120}")
    private int perMinute;

    public CacheService(StringRedisTemplate redis) {
        this.redis = redis;
    }

    // ---------- 1. 缓存 ----------

    public void putParseResult(String sha256, String json, Duration ttl) {
        try {
            redis.opsForValue().set(PREFIX + "parse:" + sha256, json, ttl);
        } catch (Exception e) {
            log.warn("Redis 写入缓存失败（降级忽略）: {}", e.getMessage());
        }
    }

    public String getParseResult(String sha256) {
        try {
            return redis.opsForValue().get(PREFIX + "parse:" + sha256);
        } catch (Exception e) {
            return null;
        }
    }

    // ---------- 2. 打榜 ----------

    public void score(String topicId, String team, double delta) {
        try {
            redis.opsForZSet().incrementScore(PREFIX + "lb:" + topicId, team, delta);
        } catch (Exception e) {
            log.warn("Redis 写榜单失败（降级忽略）: {}", e.getMessage());
        }
    }

    public java.util.List<java.util.Map<String, Object>> leaderboard(String topicId, int topN) {
        java.util.List<java.util.Map<String, Object>> list = new java.util.ArrayList<>();
        try {
            var set = redis.opsForZSet().reverseRangeWithScores(PREFIX + "lb:" + topicId, 0, topN - 1L);
            if (set != null) {
                int rank = 1;
                for (var t : set) {
                    java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
                    m.put("rank", rank++);
                    m.put("team", t.getValue());
                    m.put("score", t.getScore());
                    list.add(m);
                }
            }
        } catch (Exception e) {
            log.warn("Redis 读榜单失败（降级返回空）: {}", e.getMessage());
        }
        return list;
    }

    // ---------- 3. 限流 ----------

    /**
     * @return true 表示放行；false 表示超限
     */
    public boolean allow(String key) {
        if (!rateLimitEnabled) return true;
        try {
            String k = PREFIX + "rl:" + key + ":" + (System.currentTimeMillis() / 60000);
            Long v = redis.opsForValue().increment(k);
            if (v != null && v == 1L) {
                redis.expire(k, Duration.ofSeconds(70));
            }
            return v == null || v <= perMinute;
        } catch (Exception e) {
            // Redis 异常时放行，避免缓存不可用导致服务整体不可用
            return true;
        }
    }

    // ---------- 4. 任务进度 ----------

    public void taskProgress(String taskId, String field, String value, Duration ttl) {
        try {
            String k = PREFIX + "task:" + taskId;
            redis.opsForHash().put(k, field, value);
            redis.expire(k, ttl);
        } catch (Exception e) {
            log.warn("Redis 写任务进度失败: {}", e.getMessage());
        }
    }

    public java.util.Map<Object, Object> taskStatus(String taskId) {
        try {
            return redis.opsForHash().entries(PREFIX + "task:" + taskId);
        } catch (Exception e) {
            return java.util.Map.of();
        }
    }

    /** Redis 是否可用（健康检查用） */
    public boolean ping() {
        try {
            String pong = redis.execute((org.springframework.data.redis.core.RedisCallback<String>) connection ->
                    connection.ping());
            return "PONG".equalsIgnoreCase(pong);
        } catch (Exception e) {
            return false;
        }
    }
}
