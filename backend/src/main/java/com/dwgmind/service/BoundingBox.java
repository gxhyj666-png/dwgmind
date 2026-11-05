package com.dwgmind.service;

import com.dwgmind.domain.GeoEntity;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 二维包围盒工具
 */
public final class BoundingBox {

    public final double x1, y1, x2, y2;

    public BoundingBox(double x1, double y1, double x2, double y2) {
        this.x1 = Math.min(x1, x2);
        this.y1 = Math.min(y1, y2);
        this.x2 = Math.max(x1, x2);
        this.y2 = Math.max(y1, y2);
    }

    public double width() {
        return Math.max(0, x2 - x1);
    }

    public double height() {
        return Math.max(0, y2 - y1);
    }

    public double area() {
        return width() * height();
    }

    public double centerX() {
        return (x1 + x2) / 2.0;
    }

    public double centerY() {
        return (y1 + y2) / 2.0;
    }

    /** 延长边（用于把靠近的图元视为同一零件，解决线头未完全闭合的问题） */
    public BoundingBox inflate(double pad) {
        return new BoundingBox(x1 - pad, y1 - pad, x2 + pad, y2 + pad);
    }

    public boolean contains(double px, double py) {
        return px >= x1 && px <= x2 && py >= y1 && py <= y2;
    }

    public boolean intersects(BoundingBox o) {
        return x1 <= o.x2 && o.x1 <= x2 && y1 <= o.y2 && o.y1 <= y2;
    }

    /** 中心点距离 */
    public double centerDistance(BoundingBox o) {
        double dx = centerX() - o.centerX();
        double dy = centerY() - o.centerY();
        return Math.sqrt(dx * dx + dy * dy);
    }

    /** 由单个图元求包围盒；圆/圆弧按半径扩展 */
    public static BoundingBox of(GeoEntity e) {
        double x1 = nz(e.getX1()), y1 = nz(e.getY1());
        double x2 = e.getX2() == null ? x1 : e.getX2();
        double y2 = e.getY2() == null ? y1 : e.getY2();
        double r = e.getRadius() == null ? 0 : e.getRadius();
        return new BoundingBox(Math.min(x1, x2) - r, Math.min(y1, y2) - r,
                Math.max(x1, x2) + r, Math.max(y1, y2) + r);
    }

    public static BoundingBox of(List<GeoEntity> list) {
        double x1 = Double.MAX_VALUE, y1 = Double.MAX_VALUE;
        double x2 = -Double.MAX_VALUE, y2 = -Double.MAX_VALUE;
        for (GeoEntity e : list) {
            BoundingBox b = of(e);
            x1 = Math.min(x1, b.x1);
            y1 = Math.min(y1, b.y1);
            x2 = Math.max(x2, b.x2);
            y2 = Math.max(y2, b.y2);
        }
        if (x1 == Double.MAX_VALUE) return new BoundingBox(0, 0, 0, 0);
        return new BoundingBox(x1, y1, x2, y2);
    }

    private static double nz(Double v) {
        return v == null ? 0.0 : v;
    }
}
