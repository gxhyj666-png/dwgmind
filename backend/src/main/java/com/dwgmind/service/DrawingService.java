package com.dwgmind.service;

import com.dwgmind.common.BusinessException;
import com.dwgmind.common.ResultCode;
import com.dwgmind.domain.Drawing;
import com.dwgmind.domain.GeoEntity;
import com.dwgmind.domain.SemanticInference;
import com.dwgmind.domain.SemanticRelation;
import com.dwgmind.repository.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 图纸服务：编排「上传 → 感知层 → 语义层 → 应用层」全流程
 */
@Service
public class DrawingService {

    private static final Logger log = LoggerFactory.getLogger(DrawingService.class);
    private static final Set<String> SUPPORTED = Set.of("dxf", "dwg", "pdf", "png", "jpg", "jpeg", "bmp");

    private final DrawingRepository drawingRepo;
    private final GeoEntityRepository geoRepo;
    private final SemanticRelationRepository relationRepo;
    private final SemanticInferenceRepository inferenceRepo;
    private final DxfParser dxfParser;
    private final ImageAnalyzer imageAnalyzer;
    private final SemanticEngine semanticEngine;
    private final LlmClient llmClient;
    private final CacheService cacheService;
    private final ProgressService progressService;
    private final ObjectMapper mapper = new ObjectMapper();

    @Value("${dwgmind.upload-dir:./uploads}")
    private String uploadDir;

    private final ExecutorService parsePool = Executors.newFixedThreadPool(4, r -> {
        Thread t = new Thread(r, "parse-worker");
        t.setDaemon(true);
        return t;
    });

    public DrawingService(DrawingRepository drawingRepo, GeoEntityRepository geoRepo,
                          SemanticRelationRepository relationRepo, SemanticInferenceRepository inferenceRepo,
                          DxfParser dxfParser, ImageAnalyzer imageAnalyzer, SemanticEngine semanticEngine,
                          LlmClient llmClient, CacheService cacheService, ProgressService progressService) {
        this.drawingRepo = drawingRepo;
        this.geoRepo = geoRepo;
        this.relationRepo = relationRepo;
        this.inferenceRepo = inferenceRepo;
        this.dxfParser = dxfParser;
        this.imageAnalyzer = imageAnalyzer;
        this.semanticEngine = semanticEngine;
        this.llmClient = llmClient;
        this.cacheService = cacheService;
        this.progressService = progressService;
    }

    @PostConstruct
    public void init() throws IOException {
        Files.createDirectories(Paths.get(uploadDir));
        log.info("上传目录: {}", Paths.get(uploadDir).toAbsolutePath());
    }

    @PreDestroy
    public void destroy() {
        parsePool.shutdownNow();
    }

    // ================= 上传 =================

    public Drawing upload(MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new BusinessException(ResultCode.BAD_REQUEST, "文件为空");
        }
        String original = Optional.ofNullable(file.getOriginalFilename()).orElse("unnamed");
        String ext = extOf(original);
        if (!SUPPORTED.contains(ext)) {
            throw new BusinessException(ResultCode.UNSUPPORTED_FORMAT,
                    "不支持的格式 ." + ext + "，支持：" + String.join("/", SUPPORTED));
        }

        Path dir = Paths.get(uploadDir, String.valueOf(System.currentTimeMillis()));
        Files.createDirectories(dir);
        String safeName = System.currentTimeMillis() + "_" + original.replaceAll("[\\\\/:*?\"<>|]", "_");
        Path target = dir.resolve(safeName);
        try (InputStream in = file.getInputStream()) {
            Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
        }

        String sha = sha256(target);
        Drawing d = new Drawing();
        d.setFileName(original);
        d.setFileType(ext);
        d.setFileSize(Files.size(target));
        d.setStoragePath(target.toAbsolutePath().toString());
        d.setSha256(sha);
        d.setStatus("UPLOADED");
        drawingRepo.save(d);

        // 打榜：上传即计分（演示用）
        cacheService.score("default", "DwgMind", 1);
        log.info("图纸上传成功: id={}, file={}, size={}B, sha={}", d.getId(), original, d.getFileSize(), sha);
        return d;
    }

    // ================= 异步解析 =================

    public String startParse(Long drawingId) {
        Drawing d = drawingRepo.findById(drawingId)
                .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "图纸不存在"));
        String taskId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        parsePool.submit(() -> doParse(d, taskId));
        return taskId;
    }

    /** 同步解析，返回结果（测试/调试用） */
    public Drawing parseSync(Long drawingId) {
        Drawing d = drawingRepo.findById(drawingId)
                .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "图纸不存在"));
        doParse(d, null);
        return drawingRepo.findById(drawingId).orElse(d);
    }

    private void doParse(Drawing d, String taskId) {
        long begin = System.currentTimeMillis();
        try {
            updateStatus(d, "PARSING", null);
            progress(taskId, "SENSING", "正在读取图纸文件…", 10);

            Path path = Paths.get(d.getStoragePath());
            List<GeoEntity> entities;
            String engine = "RULE";

            // ---------- 感知层 ----------
            if (dxfParser.isDxf(d.getFileName())) {
                entities = dxfParser.parse(path);
                progress(taskId, "SENSING", "DXF 组码解析完成，提取到 " + entities.size() + " 个图元", 35);
                BoundingBox all = BoundingBox.of(entities);
                d.setCanvasWidth(all.width());
                d.setCanvasHeight(all.height());
            } else if (isImage(d.getFileType())) {
                ImageAnalyzer.ImageAnalysis ia = imageAnalyzer.analyze(path);
                entities = ia.entities;
                d.setCanvasWidth(ia.width);
                d.setCanvasHeight(ia.height);
                d.setThumbnail(ia.previewDataUrl);
                progress(taskId, "SENSING", "图像还原与骨架提取完成，得到 " + entities.size() + " 条线族", 35);
            } else {
                // PDF / DWG：当前版本给出明确降级提示，不伪造结果
                throw new BusinessException(ResultCode.UNSUPPORTED_FORMAT,
                        "." + d.getFileType() + " 暂未接入解析器，请上传 DXF 或 PNG/JPG 图纸（DWG 请先在 CAD 中另存为 DXF）");
            }

            if (entities.isEmpty()) {
                throw new BusinessException(ResultCode.PARSE_FAILED,
                        "未从图纸中提取到有效图元，请检查文件是否为空心/损坏图纸");
            }

            // 落库图元，获得真实 entityId
            for (GeoEntity e : entities) {
                e.setDrawingId(d.getId());
            }
            entities = geoRepo.saveAll(entities);
            long count = entities.size();
            progress(taskId, "SENSING", "图元持久化完成（" + count + " 个）", 50);

            // ---------- 语义层 ----------
            progress(taskId, "SEMANTIC", "正在建立图元归属关系与尺寸约束关系…", 60);
            SemanticEngine.SemanticResult sr = semanticEngine.analyze(d.getId(), entities);

            // ---------- 大模型增强（可选）----------
            if (llmClient.isConfigured()) {
                progress(taskId, "SEMANTIC", "调用大模型进行语义增强…", 70);
                String enhanced = llmClient.chat(
                        "你是资深机械制图工程师，擅长从图纸图元统计推断零件结构与工艺。只输出一段 120 字以内的中文专业分析。",
                        String.format("图纸「%s」，图元总数 %d，其中线 %d、圆/弧 %d、文本注记 %d、尺寸标注 %d，图层数 %d。请给出这个零件的结构判断与可能的加工工艺建议。",
                                d.getFileName(), count,
                                entities.stream().filter(e -> "LINE".equals(e.getType())).count(),
                                entities.stream().filter(e -> "CIRCLE".equals(e.getType()) || "ARC".equals(e.getType())).count(),
                                entities.stream().filter(e -> "TEXT".equals(e.getType())).count(),
                                entities.stream().filter(e -> "DIMENSION".equals(e.getType())).count(),
                                entities.stream().map(e -> e.getLayer() == null ? "0" : e.getLayer()).distinct().count()));
                if (enhanced != null && !enhanced.isBlank()) {
                    SemanticInference inf = new SemanticInference();
                    inf.setDrawingId(d.getId());
                    inf.setConclusionType("LLM_INSIGHT");
                    inf.setTitle("大模型结构研判");
                    inf.setContent(enhanced.trim());
                    inf.setConfidence(0.82);
                    inf.setEngine("LLM");
                    inf.setReviewStatus("AUTO");
                    inf.setTraceJson("[]");
                    sr.inferences.add(inf);
                    engine = "LLM_ENHANCED";
                }
            }

            relationRepo.saveAll(sr.relations);
            inferenceRepo.saveAll(sr.inferences);
            progress(taskId, "APPLY", "关系与结论已落库（关系 " + sr.relations.size() + " 条，结论 " + sr.inferences.size() + " 条）", 85);

            // ---------- 应用层（BOM 与统计）----------
            d.setStatus("PARSED");
            d.setSemanticStatus(engine);
            d.setEngine(engine);
            d.setEntityCount((int) count);
            d.setConclusionCount(sr.inferences.size());
            d.setParseCostMs(System.currentTimeMillis() - begin);
            d.setErrorMessage(null);
            drawingRepo.save(d);

            // Redis 缓存解析摘要（同一文件再次上传可直接命中）
            if (d.getSha256() != null) {
                cacheService.putParseResult(d.getSha256(),
                        mapper.writeValueAsString(Map.of(
                                "drawingId", d.getId(),
                                "entityCount", count,
                                "conclusionCount", sr.inferences.size(),
                                "parseCostMs", d.getParseCostMs(),
                                "engine", engine)),
                        Duration.ofMinutes(30));
            }

            progressService.complete(taskId, Map.of(
                    "stage", "DONE",
                    "percent", 100,
                    "drawingId", d.getId(),
                    "entityCount", count,
                    "conclusionCount", sr.inferences.size(),
                    "parseCostMs", d.getParseCostMs(),
                    "engine", engine,
                    "message", "解析完成：" + count + " 个图元，" + sr.inferences.size() + " 条语义结论"
            ));
            log.info("解析完成 drawing={} 耗时={}ms 图元={} 结论={}", d.getId(), d.getParseCostMs(), count, sr.inferences.size());

        } catch (BusinessException be) {
            markFailed(d, be.getMessage(), taskId);
        } catch (Exception e) {
            log.error("解析异常 drawing={}", d.getId(), e);
            markFailed(d, "解析异常：" + e.getMessage(), taskId);
        }
    }

    private void markFailed(Drawing d, String msg, String taskId) {
        d.setStatus("FAILED");
        d.setErrorMessage(msg);
        drawingRepo.save(d);
        progressService.fail(taskId, msg);
        log.warn("解析失败 drawing={}: {}", d.getId(), msg);
    }

    private void updateStatus(Drawing d, String status, String err) {
        d.setStatus(status);
        d.setErrorMessage(err);
        drawingRepo.save(d);
    }

    private void progress(String taskId, String stage, String message, int percent) {
        if (taskId == null) return;
        progressService.send(taskId, Map.of(
                "stage", stage, "message", message, "percent", percent,
                "ts", System.currentTimeMillis()));
        cacheService.taskProgress(taskId, "stage", stage, Duration.ofHours(2));
        cacheService.taskProgress(taskId, "percent", String.valueOf(percent), Duration.ofHours(2));
    }

    // ================= 查询 =================

    public List<Drawing> list() {
        return drawingRepo.findAllByOrderByIdDesc();
    }

    public Drawing get(Long id) {
        return drawingRepo.findById(id)
                .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "图纸不存在"));
    }

    public List<GeoEntity> entities(Long drawingId) {
        return geoRepo.findByDrawingIdOrderByIdAsc(drawingId);
    }

    public List<SemanticRelation> relations(Long drawingId) {
        return relationRepo.findByDrawingIdOrderByIdAsc(drawingId);
    }

    public List<SemanticInference> inferences(Long drawingId) {
        return inferenceRepo.findByDrawingIdOrderByIdAsc(drawingId);
    }

    /** BOM 视图：把 BOM_ITEM 结论整理成结构化清单 */
    public List<Map<String, Object>> bom(Long drawingId) {
        List<SemanticInference> all = inferenceRepo.findByDrawingIdOrderByIdAsc(drawingId);
        Map<Long, List<SemanticInference>> byPart = new LinkedHashMap<>();
        for (SemanticInference i : all) {
            if (i.getPartId() == null) continue;
            byPart.computeIfAbsent(i.getPartId(), k -> new ArrayList<>()).add(i);
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (Map.Entry<Long, List<SemanticInference>> e : byPart.entrySet()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("partId", e.getKey());
            row.put("partName", "P-" + String.format("%03d", e.getKey()));
            Optional<SemanticInference> bomItem = e.getValue().stream()
                    .filter(i -> "BOM_ITEM".equals(i.getConclusionType())).findFirst();
            List<String> dims = e.getValue().stream()
                    .filter(i -> "DIMENSION_ATTACH".equals(i.getConclusionType()))
                    .map(SemanticInference::getContent).toList();
            row.put("summary", bomItem.map(SemanticInference::getContent).orElse("-"));
            row.put("dimensions", dims);
            row.put("conclusionCount", e.getValue().size());
            row.put("confidence", bomItem.map(SemanticInference::getConfidence).orElse(0.5));
            rows.add(row);
        }
        return rows;
    }

    /** 结论溯源：按结论 id 返回其在图纸上的坐标，用于前端高亮 */
    public Map<String, Object> trace(Long inferenceId) {
        SemanticInference inf = inferenceRepo.findById(inferenceId)
                .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "结论不存在"));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("inferenceId", inf.getId());
        m.put("title", inf.getTitle());
        m.put("conclusionType", inf.getConclusionType());
        m.put("confidence", inf.getConfidence());
        m.put("reviewStatus", inf.getReviewStatus());
        m.put("trace", inf.getTraceJson());
        return m;
    }

    @Transactional
    public SemanticInference review(Long inferenceId, String action, String correctedContent) {
        SemanticInference inf = inferenceRepo.findById(inferenceId)
                .orElseThrow(() -> new BusinessException(ResultCode.NOT_FOUND, "结论不存在"));
        if ("CONFIRM".equalsIgnoreCase(action)) {
            inf.setReviewStatus("CONFIRMED");
            cacheService.score("review", "DwgMind", 1);
        } else if ("CORRECT".equalsIgnoreCase(action)) {
            inf.setReviewStatus("CORRECTED");
            inf.setCorrectedContent(correctedContent);
            // 人工修正回流：此处即为训练样本沉淀点
            cacheService.score("review", "DwgMind", 2);
        } else {
            throw new BusinessException(ResultCode.BAD_REQUEST, "未知复核动作：" + action);
        }
        return inferenceRepo.save(inf);
    }

    private boolean isImage(String ext) {
        return Set.of("png", "jpg", "jpeg", "bmp").contains(ext);
    }

    private String extOf(String name) {
        int i = name.lastIndexOf('.');
        return i < 0 ? "" : name.substring(i + 1).toLowerCase(Locale.ROOT);
    }

    private String sha256(Path p) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            try (DigestInputStream dis = new DigestInputStream(Files.newInputStream(p), md)) {
                byte[] buf = new byte[8192];
                while (dis.read(buf) != -1) {
                    // 读完即可
                }
            }
            StringBuilder sb = new StringBuilder();
            for (byte b : md.digest()) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            return null;
        }
    }
}
