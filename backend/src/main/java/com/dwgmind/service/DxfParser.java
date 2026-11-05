package com.dwgmind.service;

import com.dwgmind.common.BusinessException;
import com.dwgmind.common.ResultCode;
import com.dwgmind.domain.GeoEntity;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * DXF 解析器（ASCII DXF，支持 AutoCAD R12 及以上的组码结构）
 *
 * 设计取舍：不引入重量级 CAD 库，直接读组码。
 * DXF 本质是 (组码, 值) 成对出现的文本，Java 解析既稳又可控；
 * 复杂几何运算交给可选 Python 边车。
 */
@Component
public class DxfParser {

    private static final Logger log = LoggerFactory.getLogger(DxfParser.class);
    private final ObjectMapper mapper = new ObjectMapper();

    public boolean isDxf(String fileName) {
        String n = fileName == null ? "" : fileName.toLowerCase();
        return n.endsWith(".dxf");
    }

    /**
     * 解析 DXF 文件，返回图元列表（按实体顺序，index 即临时 entityId 顺序）
     * 默认取 ENTITIES 段；若不存在则退化为全文扫描。
     */
    public List<GeoEntity> parse(Path dxfPath) throws IOException {
        List<String[]> pairs = readPairs(dxfPath);
        if (pairs.isEmpty()) {
            throw new BusinessException(ResultCode.PARSE_FAILED, "DXF 文件为空或无法读取");
        }

        // 1. 定位 ENTITIES 段
        int start = -1, end = pairs.size();
        for (int i = 0; i < pairs.size(); i++) {
            String code = pairs.get(i)[0];
            String value = pairs.get(i)[1];
            if ("0".equals(code) && "SECTION".equalsIgnoreCase(value)) {
                // 下一对应当是 2 / ENTITIES
                if (i + 1 < pairs.size() && "2".equals(pairs.get(i + 1)[0])
                        && "ENTITIES".equalsIgnoreCase(pairs.get(i + 1)[1])) {
                    start = i + 2;
                }
            }
            if (start >= 0 && "0".equals(code) && "ENDSEC".equalsIgnoreCase(value)) {
                end = i;
                break;
            }
        }
        if (start < 0) {
            start = 0;
        }

        // 2. 按 0 组码切分实体块
        List<Map<String, List<String>>> blocks = new ArrayList<>();
        Map<String, List<String>> current = new HashMap<>();
        String currentType = null;
        for (int i = start; i < end; i++) {
            String code = pairs.get(i)[0].trim();
            String value = pairs.get(i)[1];
            if ("0".equals(code)) {
                if (currentType != null) {
                    current.put("__type__", List.of(currentType));
                    blocks.add(current);
                }
                current = new HashMap<>();
                currentType = value.trim();
            } else if (currentType != null) {
                current.computeIfAbsent(code, k -> new ArrayList<>()).add(value);
            }
        }
        if (currentType != null && !current.isEmpty()) {
            current.put("__type__", List.of(currentType));
            blocks.add(current);
        }

        // 3. 实体块 -> 图元
        List<GeoEntity> entities = new ArrayList<>();
        for (Map<String, List<String>> b : blocks) {
            String type = first(b, "__type__");
            if (type == null) continue;
            String upper = type.toUpperCase(Locale.ROOT);
            GeoEntity e = new GeoEntity();
            e.setLayer(first(b, "8"));
            e.setConfidence(0.95);
            switch (upper) {
                case "LINE" -> {
                    e.setType("LINE");
                    e.setX1(d(first(b, "10"))); e.setY1(d(first(b, "20")));
                    e.setX2(d(first(b, "11"))); e.setY2(d(first(b, "21")));
                }
                case "CIRCLE" -> {
                    e.setType("CIRCLE");
                    e.setX1(d(first(b, "10"))); e.setY1(d(first(b, "20")));
                    e.setRadius(d(first(b, "40")));
                }
                case "ARC" -> {
                    e.setType("ARC");
                    e.setX1(d(first(b, "10"))); e.setY1(d(first(b, "20")));
                    e.setRadius(d(first(b, "40")));
                    e.setStartAngle(d(first(b, "50")));
                    e.setEndAngle(d(first(b, "51")));
                }
                case "TEXT", "MTEXT" -> {
                    e.setType("TEXT");
                    e.setX1(d(first(b, "10"))); e.setY1(d(first(b, "20")));
                    String v = first(b, "1");
                    if (v == null) v = first(b, "3");
                    e.setText(cleanText(v));
                    e.setConfidence(0.9);
                }
                case "DIMENSION" -> {
                    e.setType("DIMENSION");
                    e.setX1(d(first(b, "10"))); e.setY1(d(first(b, "20")));
                    e.setX2(d(first(b, "11"))); e.setY2(d(first(b, "21")));
                    String tv = first(b, "1");
                    String measured = null;
                    // 组码 42 为实际测量值（部分软件写入）
                    if (first(b, "42") != null) measured = first(b, "42");
                    e.setText(measured != null ? measured + (tv != null ? " " + tv : "")
                            : (tv != null ? tv : ""));
                    e.setConfidence(0.85);
                }
                case "LWPOLYLINE", "POLYLINE" -> {
                    e.setType("POLYLINE");
                    // 取首尾部作为包围盒近似，点序列放 attributes
                    List<String> xs = b.getOrDefault("10", List.of());
                    List<String> ys = b.getOrDefault("20", List.of());
                    if (xs.isEmpty() || ys.isEmpty()) continue;
                    List<List<Double>> pts = new ArrayList<>();
                    for (int i = 0; i < Math.min(xs.size(), ys.size()); i++) {
                        pts.add(List.of(d(xs.get(i)), d(ys.get(i))));
                    }
                    double minX = pts.stream().mapToDouble(p -> p.get(0)).min().orElse(0);
                    double minY = pts.stream().mapToDouble(p -> p.get(1)).min().orElse(0);
                    double maxX = pts.stream().mapToDouble(p -> p.get(0)).max().orElse(0);
                    double maxY = pts.stream().mapToDouble(p -> p.get(1)).max().orElse(0);
                    e.setX1(minX); e.setY1(minY); e.setX2(maxX); e.setY2(maxY);
                    e.setAttributes(mapper.writeValueAsString(Map.of("points", pts)));
                }
                default -> {
                    // 其他实体（INSERT/SPLINE/HATCH 等）先记录类型，不参与几何推理
                    e.setType("OTHER");
                    e.setX1(d(first(b, "10"))); e.setY1(d(first(b, "20")));
                    e.setConfidence(0.3);
                    e.setAttributes(mapper.writeValueAsString(Map.of("rawType", upper)));
                }
            }
            entities.add(e);
        }

        log.info("DXF 解析完成：{} 个实体块 -> {} 个图元", blocks.size(), entities.size());
        return entities;
    }

    private List<String[]> readPairs(Path path) throws IOException {
        List<String[]> list = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(
                new InputStreamReader(Files.newInputStream(path), StandardCharsets.UTF_8))) {
            String codeLine;
            while ((codeLine = r.readLine()) != null) {
                String code = codeLine.trim();
                String value = r.readLine();
                if (value == null) break;
                list.add(new String[]{code, value.trim()});
            }
        } catch (java.nio.charset.MalformedInputException e) {
            // 部分中文 DXF 为 GBK 编码，退回 GBK 再读一次
            list.clear();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(Files.newInputStream(path), "GBK"))) {
                String codeLine;
                while ((codeLine = r.readLine()) != null) {
                    String code = codeLine.trim();
                    String value = r.readLine();
                    if (value == null) break;
                    list.add(new String[]{code, value.trim()});
                }
            }
        }
        return list;
    }

    private String first(Map<String, List<String>> b, String code) {
        List<String> v = b.get(code);
        return (v == null || v.isEmpty()) ? null : v.get(0);
    }

    private Double d(String s) {
        if (s == null || s.isBlank()) return 0.0;
        try {
            return Double.parseDouble(s.trim());
        } catch (NumberFormatException ex) {
            return 0.0;
        }
    }

    /** 清理 DXF 文本里的控制符与 %% 转义 */
    private String cleanText(String s) {
        if (s == null) return "";
        return s.replace("\\P", " ")
                .replace("%%d", "°").replace("%%c", "⌀").replace("%%p", "±")
                .replace("%%%", "%")
                .trim();
    }
}
