"""
图语者 DwgMind · Python 几何/OCR 边车（可选）

职责边界：只做「无状态」的图形与文本处理，不持有任何业务数据。
  - POST /py/v1/ocr      图片 -> 文本块 + 坐标（需要可选依赖 tesseract）
  - POST /py/v1/dxf/parse DXF -> 规范化图元 JSON
  - GET  /py/v1/health   能力自检

启动：python -m uvicorn main:app --host 127.0.0.1 --port 8100
说明：Java 主服务未启用边车时（dwgmind.sidecar.enabled=false），
      内置 DXF 解析与图像骨架提取已可独立完成，边车缺失不影响主流程。
"""
from __future__ import annotations

import io
import json
import shutil
from typing import Any, Dict, List

try:
    from fastapi import FastAPI, File, UploadFile
    from fastapi.responses import JSONResponse
    from PIL import Image
except ImportError as exc:  # pragma: no cover
    raise SystemExit(
        "缺少依赖，请先执行: pip install fastapi uvicorn pillow python-multipart"
    ) from exc

app = FastAPI(title="DwgMind Sidecar", version="1.0.0")

HAS_TESSERACT = bool(shutil.which("tesseract"))
HAS_EZDXF = True
try:
    import ezdxf  # noqa: F401
except Exception:  # pragma: no cover
    HAS_EZDXF = False


@app.get("/py/v1/health")
def health() -> Dict[str, Any]:
    return {
        "status": "UP",
        "service": "dwgmind-sidecar",
        "capabilities": {
            "ocr": HAS_TESSERACT,
            "dxf": HAS_EZDXF,
            "image": True,
        },
        "hint": "OCR 需要安装 tesseract 并把可执行文件加入 PATH；DXF 解析依赖 ezdxf",
    }


@app.post("/py/v1/ocr")
async def ocr(file: UploadFile = File(...)) -> JSONResponse:
    raw = await file.read()
    img = Image.open(io.BytesIO(raw))
    w, h = img.size
    if not HAS_TESSERACT:
        return JSONResponse({
            "ok": False,
            "imageWidth": w,
            "imageHeight": h,
            "blocks": [],
            "reason": "tesseract 未安装，OCR 不可用（Java 内置图像骨架提取仍可工作）",
        })
    import pytesseract  # 延迟导入
    data = pytesseract.image_to_data(img, output_type=pytesseract.Output.DICT, lang="chi_sim+eng")
    blocks: List[Dict[str, Any]] = []
    n = len(data.get("text", []))
    for i in range(n):
        text = (data["text"][i] or "").strip()
        if not text:
            continue
        try:
            conf = float(data["conf"][i])
        except (TypeError, ValueError):
            conf = -1.0
        if conf < 40:
            continue
        blocks.append({
            "text": text,
            "confidence": round(conf / 100.0, 3),
            "x": data["left"][i],
            "y": data["top"][i],
            "w": data["width"][i],
            "h": data["height"][i],
        })
    return JSONResponse({
        "ok": True,
        "imageWidth": w,
        "imageHeight": h,
        "blockCount": len(blocks),
        "blocks": blocks,
    })


@app.post("/py/v1/dxf/parse")
async def dxf_parse(file: UploadFile = File(...)) -> JSONResponse:
    if not HAS_EZDXF:
        return JSONResponse({"ok": False, "reason": "ezdxf 未安装"}, status_code=503)
    import ezdxf
    raw = await file.read()
    try:
        doc = ezdxf.read(io.StringIO(raw.decode("utf-8", errors="ignore")))
    except Exception as exc:
        return JSONResponse({"ok": False, "reason": f"DXF 解析失败: {exc}"}, status_code=400)

    entities: List[Dict[str, Any]] = []
    msp = doc.modelspace()
    for e in msp:
        t = e.dxftype()
        layer = e.dxf.get("layer", "0")
        if t == "LINE":
            entities.append({
                "type": "LINE", "layer": layer,
                "x1": e.dxf.start.x, "y1": e.dxf.start.y,
                "x2": e.dxf.end.x, "y2": e.dxf.end.y,
            })
        elif t == "CIRCLE":
            entities.append({
                "type": "CIRCLE", "layer": layer,
                "x1": e.dxf.center.x, "y1": e.dxf.center.y,
                "radius": e.dxf.radius,
            })
        elif t == "ARC":
            entities.append({
                "type": "ARC", "layer": layer,
                "x1": e.dxf.center.x, "y1": e.dxf.center.y,
                "radius": e.dxf.radius,
                "startAngle": e.dxf.start_angle, "endAngle": e.dxf.end_angle,
            })
        elif t in ("TEXT", "MTEXT"):
            entities.append({
                "type": "TEXT", "layer": layer,
                "x1": e.dxf.insert.x, "y1": e.dxf.insert.y,
                "text": e.plain_text() if hasattr(e, "plain_text") else e.dxf.get("text", ""),
            })
    return JSONResponse({"ok": True, "entityCount": len(entities), "entities": json.loads(json.dumps(entities))})


if __name__ == "__main__":  # pragma: no cover
    import uvicorn
    uvicorn.run(app, host="127.0.0.1", port=8100)
