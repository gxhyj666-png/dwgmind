package com.dwgmind.web;

import com.dwgmind.service.CacheService;
import com.dwgmind.service.LlmClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.dwgmind.service.ProgressService;
import com.dwgmind.service.DrawingService;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 系统接口：健康检查、进度流、榜单、问答
 */
@RestController
@RequestMapping("/api/v1")
@CrossOrigin(originPatterns = "*")
public class SystemController {

    private final CacheService cacheService;
    private final LlmClient llmClient;
    private final ProgressService progressService;
    private final DrawingService drawingService;
    private final DataSource dataSource;

    @Value("${dwgmind.sidecar.enabled:false}")
    private boolean sidecarEnabled;

    @Value("${dwgmind.sidecar.base-url:http://127.0.0.1:8100}")
    private String sidecarBaseUrl;

    public SystemController(CacheService cacheService, LlmClient llmClient,
                            ProgressService progressService, DrawingService drawingService,
                            DataSource dataSource) {
        this.cacheService = cacheService;
        this.llmClient = llmClient;
        this.progressService = progressService;
        this.drawingService = drawingService;
        this.dataSource = dataSource;
    }

    /** 健康检查：把「哪些能力已就绪、哪些降级」如实告诉前端 */
    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("status", "UP");
        m.put("service", "dwgmind");
        m.put("version", "1.0.0");
        m.put("database", dbOk() ? "UP" : "DOWN");
        m.put("redis", cacheService.ping() ? "UP" : "DOWN");
        m.put("llm", llmClient.status());
        m.put("sidecar", Map.of("enabled", sidecarEnabled, "baseUrl", sidecarBaseUrl));
        m.put("capabilities", List.of(
                "DXF 组码解析",
                "PNG/JPG 图纸骨架提取",
                "零件连通分量分组",
                "尺寸标注归属推理",
                "BOM 自动生成",
                "结论溯源坐标",
                "人工复核回流",
                "SSE 实时进度",
                "Redis 缓存与打榜"
        ));
        return m;
    }

    /** SSE 进度流 */
    @GetMapping(value = "/tasks/{taskId}/stream", produces = "text/event-stream;charset=UTF-8")
    public SseEmitter stream(@PathVariable String taskId) {
        return progressService.subscribe(taskId);
    }

    /** 实时打榜 */
    @GetMapping("/leaderboard")
    public Map<String, Object> leaderboard(@RequestParam(defaultValue = "default") String topic,
                                           @RequestParam(defaultValue = "10") int top) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("topic", topic);
        m.put("items", cacheService.leaderboard(topic, top));
        return m;
    }

    private final Map<String, Integer> qaCounter = new ConcurrentHashMap<>();

    /**
     * 图纸知识库问答
     * 有 LLM 时用大模型总结；无 LLM 时用结论检索 + 规则拼装，保证离线可用。
     */
    @PostMapping("/drawings/{id}/qa")
    public Map<String, Object> qa(@PathVariable Long id, @RequestBody Map<String, String> body) {
        String question = body.getOrDefault("question", "").trim();
        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("drawingId", id);
        resp.put("question", question);
        if (question.isEmpty()) {
            resp.put("answer", "请先输入问题，例如「这个图纸有几处孔位？」");
            resp.put("engine", "RULE");
            return resp;
        }

        var inferences = drawingService.inferences(id);
        // 关键词检索结论（近似 RAG 的召回层）
        List<String> candidates = inferences.stream()
                .filter(i -> hit(i.getTitle() + " " + i.getContent(), question))
                .map(i -> String.format("[%s|置信度 %.2f] %s", i.getConclusionType(),
                        i.getConfidence() == null ? 0 : i.getConfidence(), i.getContent()))
                .limit(12)
                .toList();

        // 统计类问题直接给数字，不靠文本检索撞运气
        String stats = statsAnswer(question, inferences);
        if (stats != null && candidates.isEmpty()) {
            resp.put("answer", stats);
            resp.put("engine", "RULE_STATS");
            resp.put("citations", 0);
            return resp;
        }

        // 汇总类问题：直接给结构化工程图概况（不依赖关键词命中）
        if (isSummaryQuestion(question)) {
            java.util.List<String> used = inferences.stream()
                    .map(i -> String.format("[%s] %s", i.getConclusionType(), i.getContent()))
                    .toList();
            resp.put("answer", buildSummary(id, used));
            resp.put("engine", llmClient.isConfigured() ? "LLM_SUMMARY" : "RULE_SUMMARY");
            resp.put("citations", used.size());
            return resp;
        }

        if (llmClient.isConfigured()) {
            String ctx = String.join("\n", candidates.isEmpty()
                    ? inferences.stream().limit(10).map(i -> i.getContent()).toList()
                    : candidates);
            String answer = llmClient.chat(
                    "你是工业图纸知识库助手，基于给定的图纸语义结论作答，回答要短、要具体、给出数字；不要编造结论之外的内容。",
                    "图纸语义结论：\n" + ctx + "\n\n用户问题：" + question);
            if (answer != null) {
                resp.put("answer", answer.trim());
                resp.put("engine", "LLM");
                // 未命中时是"全量兜底"作答，引用数如实反映实际喂给模型的结论条数
                resp.put("citations", candidates.isEmpty()
                        ? Math.min(10, inferences.size()) : candidates.size());
                resp.put("fallback", candidates.isEmpty());
                return resp;
            }
        }
        // 规则降级
        String ruleAnswer = candidates.isEmpty()
                ? "在已提取的 " + inferences.size() + " 条语义结论中未检索到直接相关内容。\n可尝试询问：有几个零件、有哪些孔位特征、尺寸归属情况、有多少条待复核结论。"
                : "根据图纸语义结论：\n- " + String.join("\n- ", candidates);
        resp.put("answer", ruleAnswer);
        resp.put("engine", "RULE");
        resp.put("citations", candidates.size());
        return resp;
    }

    // ================= 汇总类问答 =================

    private static final String[] SUMMARY_WORDS = {
            "汇总", "总结", "概况", "概述", "摘要", "梳理", "整体情况", "介绍一下", "说明一下", "全部"
    };

    private boolean isSummaryQuestion(String q) {
        if (q == null) return false;
        String s = q.replaceAll("\\s+", "");
        for (String w : SUMMARY_WORDS) {
            if (s.contains(w)) return true;
        }
        return false;
    }

    /** 结构化汇总：从语义结论直接拼装，不依赖模型也不依赖关键词命中 */
    private String buildSummary(Long drawingId, java.util.List<String> rawLines) {
        var d = drawingService.get(drawingId);
        var infs = drawingService.inferences(drawingId);
        var bom = drawingService.bom(drawingId);

        java.util.List<String> lines = new java.util.ArrayList<>();
        String fileName = d.getFileName() == null ? "未命名图纸" : d.getFileName();
        lines.add("工程图汇总 · " + fileName);
        lines.add("图元 " + (d.getEntityCount() == null ? 0 : d.getEntityCount())
                + " 个，语义结论 " + infs.size() + " 条，解析耗时 "
                + (d.getParseCostMs() == null ? 0 : d.getParseCostMs()) + " ms，引擎 "
                + (d.getEngine() == null ? "RULE" : d.getEngine()));

        if (!bom.isEmpty()) {
            lines.add("");
            lines.add("一、零件与外形");
            for (var row : bom) {
                lines.add("- " + row.get("partName") + "：" + row.get("summary"));
            }
        }

        java.util.List<String> dims = infs.stream()
                .filter(i -> "DIMENSION_ATTACH".equals(i.getConclusionType()))
                .map(i -> i.getContent()).toList();
        if (!dims.isEmpty()) {
            lines.add("");
            lines.add("二、尺寸标注（" + dims.size() + " 条）");
            dims.forEach(x -> lines.add("- " + x));
        }

        java.util.List<String> notes = infs.stream()
                .filter(i -> "PROCESS_NOTE".equals(i.getConclusionType()))
                .map(i -> i.getContent()).toList();
        if (!notes.isEmpty()) {
            lines.add("");
            lines.add("三、工艺与图面注记（" + notes.size() + " 条）");
            notes.forEach(x -> lines.add("- " + x));
        }

        java.util.List<String> llm = infs.stream()
                .filter(i -> "LLM".equals(i.getEngine()))
                .map(i -> i.getContent()).toList();
        if (!llm.isEmpty()) {
            lines.add("");
            lines.add("四、大模型研判");
            llm.forEach(x -> lines.add("- " + x));
        }

        long pending = infs.stream().filter(i -> "PENDING_REVIEW".equals(i.getReviewStatus())).count();
        lines.add("");
        lines.add("五、复核状态");
        lines.add("- 待人工复核 " + pending + " 条，已自动确认 " + (infs.size() - pending) + " 条");
        return String.join("\n", lines);
    }

    /** 统计类问答（零件数/孔位数/待复核数/尺寸数） */
    private String statsAnswer(String question, java.util.List<com.dwgmind.domain.SemanticInference> infs) {
        String q = question == null ? "" : question;
        long parts = infs.stream().filter(i -> "PART_IDENTIFY".equals(i.getConclusionType())).count();
        long holes = infs.stream().filter(i -> "CONTOUR_GROUP".equals(i.getConclusionType())).count();
        long dims = infs.stream().filter(i -> "DIMENSION_ATTACH".equals(i.getConclusionType())).count();
        long pending = infs.stream().filter(i -> "PENDING_REVIEW".equals(i.getReviewStatus())).count();
        long unmatched = infs.stream().filter(i -> "DIMENSION_ATTACH".equals(i.getConclusionType())
                && i.getPartId() == null).count();
        StringBuilder sb = new StringBuilder();
        java.util.List<String> asks = new java.util.ArrayList<>();
        if (q.contains("零件") || q.contains("部件") || q.contains("几个")) {
            asks.add("识别到 " + parts + " 个零件/部件区域");
        }
        if (q.contains("孔") || q.contains("圆弧") || q.contains("特征")) {
            asks.add("识别到 " + holes + " 处孔位/圆弧特征组");
        }
        if (q.contains("尺寸") || q.contains("标注")) {
            asks.add("共有 " + dims + " 条尺寸标注，其中 " + unmatched + " 条未能自动归属到具体零件");
        }
        if (q.contains("复核") || q.contains("确认") || q.contains("风险")) {
            asks.add(pending + " 条结论置信度偏低，已进入人工复核队列");
        }
        if (asks.isEmpty()) return null;
        return String.join("；", asks) + "。";
    }

    /**
     * 关键词命中判断（中文适配）
     *
     * 踩过的坑：原先只按空格/标点切词，中文长句（如"汇总此工程图情况"）会变成
     * 一个超长 token，永远匹配不到任何结论 —— 表现为"命中 0 条"。
     * 现在改为：ASCII 词整体匹配 + 中文按 bigram（相邻两字）切分匹配。
     */
    private boolean hit(String text, String question) {
        if (text == null || question == null) return false;
        String t = text.toLowerCase();
        String q = question.toLowerCase();
        // 1. 英文/数字词
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("[a-z0-9_\\-]{2,}").matcher(q);
        while (m.find()) {
            if (t.contains(m.group())) return true;
        }
        // 2. 中文 bigram
        String cjk = q.replaceAll("[^\\u4e00-\\u9fa5]", "");
        for (int i = 0; i + 2 <= cjk.length(); i++) {
            if (t.contains(cjk.substring(i, i + 2))) return true;
        }
        return false;
    }

    private boolean dbOk() {
        try (Connection c = dataSource.getConnection()) {
            return c.isValid(2);
        } catch (Exception e) {
            return false;
        }
    }
}
