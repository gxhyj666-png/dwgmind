package com.dwgmind.service;

import com.dwgmind.domain.GeoEntity;
import com.dwgmind.domain.SemanticInference;
import com.dwgmind.domain.SemanticRelation;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * 语义引擎（规则层）
 *
 * 输入：图元列表（几何图元）
 * 输出：
 *   1. 零件分组（连通分量 + 图层聚类）
 *   2. 语义关系（图元归属零件 / 尺寸约束零件 / 轮廓包含）
 *   3. 语义结论（零件识别 / 尺寸归属 / BOM 条目 / 工艺提示 / 风险提示），每条带溯源坐标
 *
 * 这是 DwgMind 的「读关系」核心：不是识别符号，而是建立图元之间的归属关系。
 */
@Component
public class SemanticEngine {

    private static final Logger log = LoggerFactory.getLogger(SemanticEngine.class);
    private final ObjectMapper mapper = new ObjectMapper();

    /** 结论类型 */
    public static final String T_PART = "PART_IDENTIFY";
    public static final String T_DIM = "DIMENSION_ATTACH";
    public static final String T_CONTOUR = "CONTOUR_GROUP";
    public static final String T_BOM = "BOM_ITEM";
    public static final String T_RISK = "RISK_NOTE";

    /** 低于此置信度进入人工复核 */
    private static final double REVIEW_THRESHOLD = 0.72;

    /**
     * 语义分析主入口
     */
    public SemanticResult analyze(Long drawingId, List<GeoEntity> entities) {
        SemanticResult result = new SemanticResult();

        List<GeoEntity> geometry = entities.stream()
                .filter(e -> !"TEXT".equals(e.getType()) && !"DIMENSION".equals(e.getType())
                        && !"OTHER".equals(e.getType()))
                .toList();
        List<GeoEntity> texts = entities.stream()
                .filter(e -> "TEXT".equals(e.getType())).toList();
        List<GeoEntity> dims = entities.stream()
                .filter(e -> "DIMENSION".equals(e.getType())).toList();

        // ---------- 1. 零件分组：按「图层 + 空间邻近」做连通分量 ----------
        List<PartGroup> groups = groupParts(geometry);
        long partSeq = 1;
        Map<Long, Integer> entityToPart = new HashMap<>();
        List<Long> partIds = new ArrayList<>();

        for (PartGroup g : groups) {
            long partId = partSeq++;
            partIds.add(partId);
            BoundingBox bb = BoundingBox.of(g.entities);
            for (GeoEntity e : g.entities) {
                entityToPart.put(e.getId(), (int) partId);
            }
            // 图元归属关系
            for (GeoEntity e : g.entities) {
                SemanticRelation r = new SemanticRelation();
                r.setDrawingId(drawingId);
                r.setRelationType("OWNERSHIP");
                r.setSourceEntityId(e.getId());
                r.setTargetEntityId(partId);
                r.setWeight(0.9);
                r.setEvidence("连通分量分析：与同组图元空间邻近（图层 " + safeLayer(e.getLayer()) + "）");
                result.relations.add(r);
            }
            // 零件识别结论
            SemanticInference partInf = new SemanticInference();
            partInf.setDrawingId(drawingId);
            partInf.setConclusionType(T_PART);
            partInf.setPartId(partId);
            partInf.setTitle("零件 P-" + String.format("%03d", partId));
            partInf.setContent(String.format(
                    "识别到零件区域 P-%03d：包含 %d 个几何图元，包围盒 %.1f × %.1f，图层 [%s]",
                    partId, g.entities.size(), bb.width(), bb.height(), g.layer));
            partInf.setConfidence(clamp(0.7 + Math.min(0.2, g.entities.size() * 0.01)));
            partInf.setEngine("RULE");
            partInf.setReviewStatus(partInf.getConfidence() >= REVIEW_THRESHOLD ? "AUTO" : "PENDING_REVIEW");
            partInf.setTraceJson(traceOf(partInf, g.entities, bb, null));
            result.inferences.add(partInf);

            // BOM 条目
            SemanticInference bom = new SemanticInference();
            bom.setDrawingId(drawingId);
            bom.setConclusionType(T_BOM);
            bom.setPartId(partId);
            bom.setTitle("BOM · P-" + String.format("%03d", partId));
            bom.setContent(String.format("P-%03d | 图元数 %d | 外形尺寸 %.1f × %.1f | 推测类别 %s",
                    partId, g.entities.size(), bb.width(), bb.height(), classify(bb, g.entities)));
            bom.setConfidence(0.75);
            bom.setEngine("RULE");
            bom.setReviewStatus(0.75 >= REVIEW_THRESHOLD ? "AUTO" : "PENDING_REVIEW");
            bom.setTraceJson(traceOf(bom, g.entities, bb, null));
            result.inferences.add(bom);

            // 轮廓包含关系（零件包围盒包含的圆/圆弧说明存在孔位）
            List<GeoEntity> holes = g.entities.stream()
                    .filter(e -> "CIRCLE".equals(e.getType()) || "ARC".equals(e.getType())).toList();
            if (!holes.isEmpty()) {
                SemanticInference contour = new SemanticInference();
                contour.setDrawingId(drawingId);
                contour.setConclusionType(T_CONTOUR);
                contour.setPartId(partId);
                contour.setTitle("轮廓 · P-" + String.format("%03d", partId));
                contour.setContent(String.format("P-%03d 内含 %d 处圆弧/圆孔特征，疑似孔位或倒角", partId, holes.size()));
                contour.setConfidence(0.8);
                contour.setEngine("RULE");
                contour.setReviewStatus("AUTO");
                contour.setTraceJson(traceOf(contour, holes, bb, null));
                result.inferences.add(contour);
            }
        }

        // ---------- 2. 尺寸标注归属：最近零件 ----------
        Map<Long, List<GeoEntity>> dimsByPart = new HashMap<>();
        for (GeoEntity d : dims) {
            BoundingBox db = BoundingBox.of(d).inflate(1.0);
            long bestPart = -1;
            double bestDist = Double.MAX_VALUE;
            for (int gi = 0; gi < groups.size(); gi++) {
                BoundingBox pb = BoundingBox.of(groups.get(gi).entities);
                if (pb.inflate(pb.width() * 0.5 + 1).contains(db.centerX(), db.centerY())
                        || pb.inflate(1.0).intersects(db)) {
                    double dist = pb.centerDistance(db);
                    if (dist < bestDist) {
                        bestDist = dist;
                        bestPart = partIds.get(gi);
                    }
                }
            }
            long partId = bestPart;
            if (partId > 0) {
                dimsByPart.computeIfAbsent(partId, k -> new ArrayList<>()).add(d);
                SemanticRelation r = new SemanticRelation();
                r.setDrawingId(drawingId);
                r.setRelationType("DIMENSION_OF");
                r.setSourceEntityId(d.getId());
                r.setTargetEntityId(partId);
                r.setWeight(0.85);
                r.setEvidence("尺寸标注与零件区域空间邻近（距离 " + String.format("%.1f", bestDist) + "）");
                result.relations.add(r);
            }

            SemanticInference inf = new SemanticInference();
            inf.setDrawingId(drawingId);
            inf.setConclusionType(T_DIM);
            inf.setPartId(partId > 0 ? partId : null);
            inf.setTitle("尺寸标注" + (partId > 0 ? " · P-" + String.format("%03d", partId) : "（未关联）"));
            String value = d.getText() == null || d.getText().isBlank() ? "(无文本)" : d.getText();
            inf.setContent(partId > 0
                    ? "尺寸「" + value + "」归属零件 P-" + String.format("%03d", partId)
                    : "尺寸「" + value + "」未能归属到具体零件，建议人工确认");
            inf.setConfidence(partId > 0 ? 0.8 : 0.45);
            inf.setEngine("RULE");
            inf.setReviewStatus(partId > 0 && inf.getConfidence() >= REVIEW_THRESHOLD ? "AUTO" : "PENDING_REVIEW");
            inf.setTraceJson(traceOf(inf, List.of(d), BoundingBox.of(d).inflate(2), null));
            result.inferences.add(inf);
        }

        // ---------- 3. 文本注释：识别为工艺/图号提示 ----------
        for (GeoEntity t : texts) {
            String content = t.getText() == null ? "" : t.getText();
            if (content.isBlank()) continue;
            SemanticInference inf = new SemanticInference();
            inf.setDrawingId(drawingId);
            inf.setConclusionType("PROCESS_NOTE");
            inf.setTitle("图面注记");
            inf.setContent("注记文本「" + content + "」" + guessNoteMeaning(content));
            inf.setConfidence(0.7);
            inf.setEngine("RULE");
            inf.setReviewStatus(0.7 >= REVIEW_THRESHOLD ? "AUTO" : "PENDING_REVIEW");
            inf.setTraceJson(traceOf(inf, List.of(t), BoundingBox.of(t).inflate(2), null));
            result.inferences.add(inf);
        }

        // ---------- 4. 风险提示：低置信度与人机协同入口 ----------
        long pending = result.inferences.stream().filter(i -> "PENDING_REVIEW".equals(i.getReviewStatus())).count();
        SemanticInference risk = new SemanticInference();
        risk.setDrawingId(drawingId);
        risk.setConclusionType(T_RISK);
        risk.setTitle("人机协同提示");
        risk.setContent(String.format(
                "共生成 %d 条语义结论，其中 %d 条置信度低于 %.2f，已自动进入人工复核队列；确认结果将回流为样本。",
                result.inferences.size(), pending, REVIEW_THRESHOLD));
        risk.setConfidence(0.9);
        risk.setEngine("RULE");
        risk.setReviewStatus("AUTO");
        risk.setTraceJson("[]");
        result.inferences.add(risk);

        // 回填图元归属
        for (GeoEntity e : entities) {
            Integer pid = entityToPart.get(e.getId());
            if (pid != null) e.setPartId(pid.longValue());
        }

        log.info("语义分析完成：零件 {} 个，关系 {} 条，结论 {} 条（待复核 {}）",
                groups.size(), result.relations.size(), result.inferences.size(), pending);
        return result;
    }

    // ================= 零件分组 =================

    /**
     * 分组策略（两段式）：
     *   1. 同一图层内，用「图层包围盒对角线的 3%」作为间隙阈值做并查集连通
     *      —— 解决线头未闭合、相邻轮廓被切碎的问题，同时避免把所有东西粘成一坨；
     *   2. 参考层（CENTER / 中心线）不单独成零件，挂到最近的真实零件上；
     *   3. 同一图层内存在明显不连通的大块（间距超过阈值 8 倍）时保持分离，避免误合并。
     */
    private List<PartGroup> groupParts(List<GeoEntity> geometry) {
        Map<String, List<GeoEntity>> byLayer = new LinkedHashMap<>();
        for (GeoEntity e : geometry) {
            byLayer.computeIfAbsent(safeLayer(e.getLayer()), k -> new ArrayList<>()).add(e);
        }

        List<PartGroup> groups = new ArrayList<>();
        for (Map.Entry<String, List<GeoEntity>> entry : byLayer.entrySet()) {
            String layer = entry.getKey();
            List<GeoEntity> list = entry.getValue();
            if (isReferenceLayer(layer)) continue; // 参考层稍后挂靠

            BoundingBox all = BoundingBox.of(list);
            double diag = Math.sqrt(all.width() * all.width() + all.height() * all.height());
            double gap = Math.max(2.0, diag * 0.03);
            double splitGap = Math.max(gap * 8, diag * 0.25);

            int n = list.size();
            int[] parent = new int[n];
            for (int i = 0; i < n; i++) parent[i] = i;

            for (int i = 0; i < n; i++) {
                BoundingBox bi = BoundingBox.of(list.get(i));
                for (int j = i + 1; j < n; j++) {
                    BoundingBox bj = BoundingBox.of(list.get(j));
                    if (distance(bi, bj) <= gap) union(parent, i, j);
                }
            }

            Map<Integer, PartGroup> cluster = new LinkedHashMap<>();
            for (int i = 0; i < n; i++) {
                int root = find(parent, i);
                cluster.computeIfAbsent(root, k -> {
                    PartGroup g = new PartGroup();
                    g.layer = layer;
                    return g;
                }).entities.add(list.get(i));
            }

            // 阈值 8 倍内的大块再合并（处理同一图层多个相近轮廓）
            List<PartGroup> layerGroups = new ArrayList<>(cluster.values());
            List<PartGroup> merged = new ArrayList<>();
            boolean[] used = new boolean[layerGroups.size()];
            for (int i = 0; i < layerGroups.size(); i++) {
                if (used[i]) continue;
                PartGroup base = layerGroups.get(i);
                used[i] = true;
                BoundingBox bb = BoundingBox.of(base.entities);
                boolean changed = true;
                while (changed) {
                    changed = false;
                    for (int j = 0; j < layerGroups.size(); j++) {
                        if (used[j]) continue;
                        PartGroup cand = layerGroups.get(j);
                        BoundingBox cb = BoundingBox.of(cand.entities);
                        if (distance(bb, cb) <= splitGap) {
                            base.entities.addAll(cand.entities);
                            used[j] = true;
                            bb = union(bb, cb);
                            changed = true;
                        }
                    }
                }
                merged.add(base);
            }
            groups.addAll(merged);
        }

        // ---------- 跨图层合并：几何类图层彼此贴近时合并为同一零件 ----------
        // 例如「外形轮廓 OUTLINE」与「孔位 HOLES」本就属于同一零件，不应拆成两个
        double globalDiag;
        {
            BoundingBox allGeo = BoundingBox.of(geometry);
            globalDiag = Math.sqrt(allGeo.width() * allGeo.width() + allGeo.height() * allGeo.height());
        }
        double crossGap = Math.max(4.0, globalDiag * 0.08);
        List<PartGroup> crossMerged = new ArrayList<>();
        boolean[] usedGroup = new boolean[groups.size()];
        for (int i = 0; i < groups.size(); i++) {
            if (usedGroup[i]) continue;
            PartGroup base = groups.get(i);
            usedGroup[i] = true;
            BoundingBox bb = BoundingBox.of(base.entities);
            boolean changed = true;
            int guard = 0;
            while (changed && guard++ < 50) {
                changed = false;
                for (int j = 0; j < groups.size(); j++) {
                    if (usedGroup[j]) continue;
                    PartGroup cand = groups.get(j);
                    // 纯文本/标注图层不参与几何合并，避免把注记吞进零件
                    if (isTextOnly(cand)) continue;
                    BoundingBox cb = BoundingBox.of(cand.entities);
                    if (distance(bb, cb) <= crossGap) {
                        base.entities.addAll(cand.entities);
                        usedGroup[j] = true;
                        bb = union(bb, cb);
                        changed = true;
                    }
                }
            }
            crossMerged.add(base);
        }
        // 文本图层保持独立成组
        for (int i = 0; i < groups.size(); i++) {
            if (!usedGroup[i] && isTextOnly(groups.get(i))) {
                crossMerged.add(groups.get(i));
            }
        }
        groups = crossMerged;

        // 参考层挂靠：中心线/辅助线归入与之最近的零件
        for (Map.Entry<String, List<GeoEntity>> entry : byLayer.entrySet()) {
            if (!isReferenceLayer(entry.getKey())) continue;
            for (GeoEntity ref : entry.getValue()) {
                BoundingBox rb = BoundingBox.of(ref).inflate(2);
                PartGroup best = null;
                double bestDist = Double.MAX_VALUE;
                for (PartGroup g : groups) {
                    BoundingBox gb = BoundingBox.of(g.entities);
                    double d = distance(gb, rb);
                    if (d < bestDist) {
                        bestDist = d;
                        best = g;
                    }
                }
                if (best != null) {
                    best.entities.add(ref);
                } else {
                    PartGroup g = new PartGroup();
                    g.layer = entry.getKey();
                    g.entities.add(ref);
                    groups.add(g);
                }
            }
        }
        return groups;
    }

    /** 两个包围盒之间的最小间距（相交为 0） */
    private double distance(BoundingBox a, BoundingBox b) {
        double dx = Math.max(0, Math.max(a.x1 - b.x2, b.x1 - a.x2));
        double dy = Math.max(0, Math.max(a.y1 - b.y2, b.y1 - a.y2));
        return Math.sqrt(dx * dx + dy * dy);
    }

    private boolean isReferenceLayer(String layer) {
        String l = layer.toUpperCase(Locale.ROOT);
        return l.contains("CENTER") || l.contains("CENTRE") || l.contains("中心")
                || l.contains("AXIS") || l.contains("辅助") || l.contains("HIDDEN") || l.contains("虚线");
    }

    /** 组内是否只含文本/尺寸标注（这类图层不应被几何合并吞并） */
    private boolean isTextOnly(PartGroup g) {
        return g.entities.stream().allMatch(e ->
                "TEXT".equals(e.getType()) || "DIMENSION".equals(e.getType()));
    }

    private int find(int[] parent, int x) {
        while (parent[x] != x) {
            parent[x] = parent[parent[x]];
            x = parent[x];
        }
        return x;
    }

    private void union(int[] parent, int a, int b) {
        int ra = find(parent, a), rb = find(parent, b);
        if (ra != rb) parent[ra] = rb;
    }

    private BoundingBox union(BoundingBox a, BoundingBox b) {
        return new BoundingBox(
                Math.min(a.x1, b.x1), Math.min(a.y1, b.y1),
                Math.max(a.x2, b.x2), Math.max(a.y2, b.y2));
    }

    private String classify(BoundingBox bb, List<GeoEntity> list) {
        long lines = list.stream().filter(e -> "LINE".equals(e.getType())).count();
        List<GeoEntity> circles = list.stream()
                .filter(e -> "CIRCLE".equals(e.getType()) || "ARC".equals(e.getType())).toList();
        long circleCount = circles.size();
        double ratio = bb.width() > 0 ? bb.height() / bb.width() : 0;
        StringBuilder sb = new StringBuilder();
        if (ratio > 0.85 && ratio < 1.15) sb.append("方形类零件");
        else if (ratio <= 0.85) sb.append("横向长条类零件");
        else sb.append("纵向长条类零件");

        if (circleCount > 0) {
            long distinctR = circles.stream()
                    .map(e -> Math.round((e.getRadius() == null ? 0 : e.getRadius()) * 10))
                    .distinct().count();
            sb.append("，含 ").append(circleCount).append(" 处圆弧/圆特征");
            if (distinctR == 1) sb.append("（同径孔位，疑似规则孔阵）");
            else sb.append("（半径不一，疑似孔位与凸台/沉孔混合）");
        }
        if (lines > 60) sb.append("，线条密集（疑似复杂轮廓）");
        return sb.toString();
    }

    private String guessNoteMeaning(String text) {
        String t = text.toUpperCase(Locale.ROOT);
        if (t.contains("M") && t.matches(".*\\d+\\s*[X×]\\s*\\d+.*")) return "，疑似螺纹规格";
        if (t.contains("孔") || t.contains("HOLE") || t.matches(".*[⌀ØΦφ].*")) return "，疑似孔径/孔位标注";
        if (t.contains("材") || t.contains("MATERIAL")) return "，疑似材质说明";
        if (t.contains("热处理") || t.contains("HRC") || t.contains("淬火")) return "，疑似热处理要求";
        if (t.contains("Ra") || t.contains("粗糙度")) return "，疑似表面粗糙度要求";
        if (t.length() <= 8) return "，疑似零件编号或图号";
        return "";
    }

    private String traceOf(SemanticInference inf, List<GeoEntity> refs, BoundingBox bb, Long entityId) {
        try {
            List<Map<String, Object>> trace = new ArrayList<>();
            for (GeoEntity e : refs) {
                BoundingBox b = BoundingBox.of(e);
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("entityId", e.getId());
                item.put("x", b.centerX());
                item.put("y", b.centerY());
                item.put("w", Math.max(1.0, b.width()));
                item.put("h", Math.max(1.0, b.height()));
                trace.add(item);
            }
            return mapper.writeValueAsString(trace);
        } catch (Exception ex) {
            return "[]";
        }
    }

    private double clamp(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }

    private String safeLayer(String layer) {
        return layer == null || layer.isBlank() ? "0" : layer;
    }

    /** 零件分组中间结构 */
    private static class PartGroup {
        String layer;
        final List<GeoEntity> entities = new ArrayList<>();
    }

    /** 语义分析结果 */
    public static class SemanticResult {
        public final List<SemanticRelation> relations = new ArrayList<>();
        public final List<SemanticInference> inferences = new ArrayList<>();
    }
}


