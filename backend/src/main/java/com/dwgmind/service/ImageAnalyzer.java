package com.dwgmind.service;

import com.dwgmind.domain.GeoEntity;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * 图像图纸分析器（PNG / JPG）
 *
 * 纯 Java 实现，不依赖 OpenCV：
 * 1. 缩放后做灰度二值化
 * 2. 用行/列扫描提取「近似水平/垂直线段」作为图元骨架
 * 3. 输出整图 base64 供前端还原显示（前端原图 + 语义叠加层）
 *
 * 语义增强（标注归属/符号识别）由 SemanticEngine + 可选 LLM 完成。
 */
@Component
public class ImageAnalyzer {

    /** 分析时最长边像素上限，控制 CPU 与内存 */
    private static final int MAX_EDGE = 1400;
    /** 判定为墨迹的灰度阈值 */
    private static final int INK_THRESHOLD = 160;
    /** 一条线段至少连续墨迹像素数 */
    private static final int MIN_RUN = 40;

    public ImageAnalysis analyze(Path imagePath) throws IOException {
        BufferedImage src = ImageIO.read(imagePath.toFile());
        if (src == null) {
            throw new IOException("无法解码图像文件：" + imagePath.getFileName());
        }
        int w0 = src.getWidth();
        int h0 = src.getHeight();
        double scale = 1.0;
        BufferedImage img = src;
        if (Math.max(w0, h0) > MAX_EDGE) {
            scale = (double) MAX_EDGE / Math.max(w0, h0);
            int nw = Math.max(1, (int) (w0 * scale));
            int nh = Math.max(1, (int) (h0 * scale));
            BufferedImage scaled = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = scaled.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.drawImage(src, 0, 0, nw, nh, null);
            g.dispose();
            img = scaled;
        }
        int w = img.getWidth();
        int h = img.getHeight();
        boolean[][] ink = new boolean[h][w];
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                int rgb = img.getRGB(x, y);
                int r = (rgb >> 16) & 0xff;
                int gg = (rgb >> 8) & 0xff;
                int b = rgb & 0xff;
                int gray = (int) (0.299 * r + 0.587 * gg + 0.114 * b);
                ink[y][x] = gray < INK_THRESHOLD;
            }
        }

        List<GeoEntity> entities = new ArrayList<>();
        // 水平线段：逐行扫描连续墨迹
        int rowStep = Math.max(2, h / 120);
        for (int y = 0; y < h; y += rowStep) {
            int x = 0;
            while (x < w) {
                if (!ink[y][x]) { x++; continue; }
                int start = x;
                while (x < w && ink[y][x]) x++;
                int len = x - start;
                if (len >= MIN_RUN) {
                    GeoEntity e = new GeoEntity();
                    e.setType("LINE");
                    e.setLayer("raster-h");
                    e.setX1(start / scale);
                    e.setY1(y / scale);
                    e.setX2((x - 1) / scale);
                    e.setY2(y / scale);
                    e.setConfidence(0.6);
                    entities.add(e);
                }
            }
        }
        // 垂直线段：逐列扫描
        int colStep = Math.max(2, w / 120);
        for (int x = 0; x < w; x += colStep) {
            int y = 0;
            while (y < h) {
                if (!ink[y][x]) { y++; continue; }
                int start = y;
                while (y < h && ink[y][x]) y++;
                int len = y - start;
                if (len >= MIN_RUN) {
                    GeoEntity e = new GeoEntity();
                    e.setType("LINE");
                    e.setLayer("raster-v");
                    e.setX1(x / scale);
                    e.setY1(start / scale);
                    e.setX2(x / scale);
                    e.setY2((y - 1) / scale);
                    e.setConfidence(0.6);
                    entities.add(e);
                }
            }
        }

        // 缩略图（前端预览用，最长边 900px JPEG 质量 0.8）
        String preview = toDataUrl(src, 900);
        return new ImageAnalysis(entities, (double) w0, (double) h0, preview);
    }

    private String toDataUrl(BufferedImage src, int maxEdge) throws IOException {
        int w0 = src.getWidth(), h0 = src.getHeight();
        double scale = Math.min(1.0, (double) maxEdge / Math.max(w0, h0));
        int nw = Math.max(1, (int) (w0 * scale));
        int nh = Math.max(1, (int) (h0 * scale));
        BufferedImage out = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, nw, nh);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.drawImage(src, 0, 0, nw, nh, null);
        g.dispose();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ImageIO.write(out, "jpg", bos);
        return "data:image/jpeg;base64," + java.util.Base64.getEncoder().encodeToString(bos.toByteArray());
    }

    /** 图像分析结果 */
    public static class ImageAnalysis {
        public final List<GeoEntity> entities;
        public final double width;
        public final double height;
        public final String previewDataUrl;

        public ImageAnalysis(List<GeoEntity> entities, double width, double height, String previewDataUrl) {
            this.entities = entities;
            this.width = width;
            this.height = height;
            this.previewDataUrl = previewDataUrl;
        }
    }
}
