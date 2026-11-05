package com.dwgmind.web;

import com.dwgmind.common.Result;
import com.dwgmind.domain.*;
import com.dwgmind.service.DrawingService;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * 图纸与语义数据接口
 */
@RestController
@RequestMapping("/api/v1/drawings")
@CrossOrigin(originPatterns = "*")
public class DrawingController {

    private final DrawingService drawingService;

    public DrawingController(DrawingService drawingService) {
        this.drawingService = drawingService;
    }

    /** 上传图纸（上传成功后自动触发解析，返回 drawing + taskId） */
    @PostMapping("/upload")
    public Result<Map<String, Object>> upload(@RequestParam("file") org.springframework.web.multipart.MultipartFile file)
            throws IOException {
        Drawing d = drawingService.upload(file);
        String taskId = drawingService.startParse(d.getId());
        Map<String, Object> data = new java.util.LinkedHashMap<>();
        data.put("drawing", d);
        data.put("taskId", taskId);
        data.put("streamUrl", "/api/v1/tasks/" + taskId + "/stream");
        return Result.ok(data);
    }

    /** 图纸列表 */
    @GetMapping
    public Result<List<Drawing>> list() {
        return Result.ok(drawingService.list());
    }

    /** 图纸详情 */
    @GetMapping("/{id}")
    public Result<Drawing> detail(@PathVariable Long id) {
        return Result.ok(drawingService.get(id));
    }

    /** 图元列表 */
    @GetMapping("/{id}/entities")
    public Result<List<GeoEntity>> entities(@PathVariable Long id) {
        return Result.ok(drawingService.entities(id));
    }

    /** 语义关系列表 */
    @GetMapping("/{id}/relations")
    public Result<List<SemanticRelation>> relations(@PathVariable Long id) {
        return Result.ok(drawingService.relations(id));
    }

    /** 语义结论列表 */
    @GetMapping("/{id}/inferences")
    public Result<List<SemanticInference>> inferences(@PathVariable Long id) {
        return Result.ok(drawingService.inferences(id));
    }

    /** BOM 清单 */
    @GetMapping("/{id}/bom")
    public Result<List<Map<String, Object>>> bom(@PathVariable Long id) {
        return Result.ok(drawingService.bom(id));
    }

    /** 结论溯源 */
    @GetMapping("/trace/{inferenceId}")
    public Result<Map<String, Object>> trace(@PathVariable Long inferenceId) {
        return Result.ok(drawingService.trace(inferenceId));
    }
}
