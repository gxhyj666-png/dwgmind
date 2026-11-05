package com.dwgmind.domain;

import jakarta.persistence.*;

/**
 * 几何图元：感知层产物（线段 / 圆弧 / 标注 / 文本 / 符号 / 圆）
 * 坐标以图纸坐标系为准（DWG/DXF 的世界坐标，或图像像素坐标）
 */
@Entity
@Table(name = "geo_entity", indexes = {
        @Index(name = "idx_geo_drawing", columnList = "drawingId"),
        @Index(name = "idx_geo_type", columnList = "type")
})
public class GeoEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long drawingId;

    /** LINE / ARC / CIRCLE / TEXT / DIMENSION / SYMBOL / POLYLINE */
    private String type;

    /** DXF 图层名 */
    private String layer;

    /** 归属零件（语义层回填） */
    private Long partId;

    private Double x1;
    private Double y1;
    private Double x2;
    private Double y2;

    /** 圆的半径 / 圆弧半径 */
    private Double radius;

    /** 圆弧起止角 */
    private Double startAngle;
    private Double endAngle;

    /** 文本内容或尺寸标注值 */
    @Column(columnDefinition = "TEXT")
    private String text;

    /** 置信度 0-1（用于人机协同复核） */
    private Double confidence;

    /** 附加属性 JSON */
    @Column(columnDefinition = "TEXT")
    private String attributes;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getDrawingId() { return drawingId; }
    public void setDrawingId(Long drawingId) { this.drawingId = drawingId; }
    public String getType() { return type; }
    public void setType(String type) { this.type = type; }
    public String getLayer() { return layer; }
    public void setLayer(String layer) { this.layer = layer; }
    public Long getPartId() { return partId; }
    public void setPartId(Long partId) { this.partId = partId; }
    public Double getX1() { return x1; }
    public void setX1(Double x1) { this.x1 = x1; }
    public Double getY1() { return y1; }
    public void setY1(Double y1) { this.y1 = y1; }
    public Double getX2() { return x2; }
    public void setX2(Double x2) { this.x2 = x2; }
    public Double getY2() { return y2; }
    public void setY2(Double y2) { this.y2 = y2; }
    public Double getRadius() { return radius; }
    public void setRadius(Double radius) { this.radius = radius; }
    public Double getStartAngle() { return startAngle; }
    public void setStartAngle(Double startAngle) { this.startAngle = startAngle; }
    public Double getEndAngle() { return endAngle; }
    public void setEndAngle(Double endAngle) { this.endAngle = endAngle; }
    public String getText() { return text; }
    public void setText(String text) { this.text = text; }
    public Double getConfidence() { return confidence; }
    public void setConfidence(Double confidence) { this.confidence = confidence; }
    public String getAttributes() { return attributes; }
    public void setAttributes(String attributes) { this.attributes = attributes; }
}
