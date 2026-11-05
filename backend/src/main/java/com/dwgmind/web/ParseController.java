package com.dwgmind.web;

import com.dwgmind.common.Result;
import com.dwgmind.domain.Drawing;
import com.dwgmind.domain.SemanticInference;
import com.dwgmind.service.DrawingService;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 解析任务与复核接口
 */
@RestController
@RequestMapping("/api/v1")
@CrossOrigin(originPatterns = "*")
public class ParseController {

    private final DrawingService drawingService;

    public ParseController(DrawingService drawingService) {
        this.drawingService = drawingService;
    }

    /** 触发解析（异步），返回 taskId */
    @PostMapping("/drawings/{id}/parse")
    public Result<Map<String, Object>> parse(@PathVariable Long id) {
        String taskId = drawingService.startParse(id);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("taskId", taskId);
        data.put("drawingId", id);
        data.put("streamUrl", "/api/v1/tasks/" + taskId + "/stream");
        return Result.ok(data);
    }

    /** 同步解析（调试用） */
    @PostMapping("/drawings/{id}/parse-sync")
    public Result<Drawing> parseSync(@PathVariable Long id) {
        return Result.ok(drawingService.parseSync(id));
    }

    /** 语义结论复核：CONFIRM / CORRECT */
    @PostMapping("/inferences/{id}/review")
    public Result<SemanticInference> review(@PathVariable Long id,
                                            @RequestBody Map<String, String> body) {
        String action = body.getOrDefault("action", "CONFIRM");
        String content = body.get("content");
        return Result.ok(drawingService.review(id, action, content));
    }

    /** 复核队列（低置信度结论） */
    @GetMapping("/drawings/{id}/review-queue")
    public Result<List<SemanticInference>> reviewQueue(@PathVariable Long id) {
        List<SemanticInference> list = drawingService.inferences(id).stream()
                .filter(i -> "PENDING_REVIEW".equals(i.getReviewStatus()))
                .toList();
        return Result.ok(list);
    }
}
