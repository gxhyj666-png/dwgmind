# 图语者 DwgMind

> 工业图纸语义引擎 —— 把散落在 PDF / DXF / 扫描件里的工程图纸，解析成可检索、可计算、可复用的结构化资产。

**参赛**：2026 年东莞市工业 AI 应用创新挑战"百景大赛" · 开放创新赛道
**主题**：百景解码，千智赋能

---

## 一、30 秒跑起来

### 前置条件（本机已全部就绪）

| 依赖 | 版本 | 本机状态 |
|---|---|---|
| JDK | 17 | ✅ `C:\Users\86180\.jdks\corretto-17.0.20.1` |
| MySQL | 8.0.42 | ✅ 服务运行中，库 `dwgmind` 已建 |
| Redis | 5.0.14.1 | ✅ 服务运行中，127.0.0.1:6379 |
| Node.js | ≥ 18 | ✅ v24.20.0（仅用于前端静态服务） |

### 方式一：一键启动 / 停止（推荐）

**启动**：双击 **`start.bat`**
自动完成：校验 JDK 17 → 清理被占用的 8080/5173 端口 → 启动前后端 → 轮询等待就绪 → 打开浏览器

**停止**：双击 **`stop.bat`**
自动按端口（8080 后端 / 5173 前端）找到进程并结束，逐个打印被停止的 PID，最后回查端口确认已释放

> 实际逻辑在 `scripts/start.ps1` 与 `scripts/stop.ps1`，`.bat` 只做 ASCII 调用，
> 避免中文在 GBK 控制台下乱码导致 PowerShell 只收到半句命令。
> 也可以直接关掉任务栏上的 `DwgMind-Backend` / `DwgMind-Frontend` 窗口。

访问：**http://127.0.0.1:5173**

### 方式二：手动启动

```bat
:: 后端
set JAVA_HOME=C:\Users\86180\.jdks\corretto-17.0.20.1
cd backend
mvn -DskipTests package
java -jar target\dwgmind.jar

:: 前端（另开一个终端）
cd frontend
node server.js 5173
```

### 方式三：只跑后端（后端已托管前端静态资源时会用到）

```bat
java -jar backend\target\dwgmind.jar
:: 打开 http://127.0.0.1:8080
```

> 若前端与后端不同源，前端会自动指向 `http://<当前host>:8080`；
> 也可用 `?api=http://其他地址:端口` 指定后端。

---

## 二、它做了什么（一句话版）

上传一张工程图纸 → **感知层**提取几何图元 → **语义层**建立"哪根线属于哪个零件、这个尺寸约束谁"的归属关系 → **应用层**输出零件清单、BOM、工艺注记与每一条结论在图纸上的**溯源坐标**，低置信度结论自动进入**人工复核**队列，确认结果回流为样本。

### 核心闭环（可在浏览器里完整走一遍）

```
上传 DXF/图片
   ↓  SSE 实时进度（阶段 + 百分比）
感知层：DXF 组码解析 / 图像骨架提取 → N 个图元
   ↓
语义层：并查集空间聚类 → 零件分组 → 尺寸归属 → 关系图
   ↓
应用层：BOM 清单 / 图面注记识别 / 风险提示
   ↓
点击任意结论 → 图纸上高亮它的出处（溯源）
   ↓
低置信度结论 → 复核队列 → 确认/修正 → 样本回流
```

---

## 三、已验证的功能清单

| # | 能力 | 验证方式 | 结果 |
|---|---|---|---|
| 1 | DXF 组码解析（LINE/CIRCLE/ARC/TEXT/DIMENSION/LWPOLYLINE） | 示例图纸 25 个实体块 → 25 个图元 | ✅ 132ms |
| 2 | 零件空间聚类（同层并查集 + 跨层合并 + 参考层挂靠） | 21 个几何图元正确归为 1 个零件 P-001 | ✅ |
| 3 | 尺寸标注归属推理 | 4 条尺寸全部自动归属 P-001 | ✅ |
| 4 | BOM 自动生成 | 输出零件摘要、外形尺寸、含孔特征 | ✅ |
| 5 | 结论溯源 | 点击结论 → 返回坐标 → 画布高亮（1498 个琥珀色像素） | ✅ |
| 6 | 人工复核与样本回流 | 待复核 4 条 → 确认后状态变 CONFIRMED、榜单加分 | ✅ |
| 7 | 图纸知识问答 | "这张图有几个零件？" → 规则统计应答 | ✅ |
| 8 | SSE 实时进度 | 上传即推送阶段进度，完成后自动刷新 | ✅ |
| 9 | Redis 缓存 / 打榜 / 限流 / 任务进度 | `dwg:lb:default` 排行、`dwg:rl:*` 限流键 | ✅ |
| 10 | 全链路 UI | 无头浏览器 16 项断言全通过，无 JS 运行时错误 | ✅ |
| 11 | **DeepSeek 大模型增强** | 真实调用成功：新增「大模型结构研判」结论，问答应答 1.4s | ✅ |
| 12 | **LLM 自动降级** | 未配置 Key 时自动切 RULE_ONLY，健康检查如实告知 | ✅ |

界面截图：`docs/ui-workspace.png`

---

## 四、技术栈

### 后端

| 层 | 选型 |
|---|---|
| 框架 | Spring Boot 3.2.5 + JDK 17 |
| 持久化 | MySQL 8.0 + Spring Data JPA（`ddl-auto: update`） |
| 缓存/排行/限流 | Redis 5.0（StringRedisTemplate） |
| 实时 | SSE（Spring MVC SseEmitter）+ WebSocket（协同复核广播） |
| 图纸解析 | 自研 DXF 组码解析器 + Java2D 图像骨架提取 |
| 语义推理 | 规则引擎（连通分量 + 几何归属）+ 可选大模型增强 |
| 大模型 | DeepSeek / 通义千问（OpenAI 兼容协议，未配置 Key 自动降级为纯规则引擎） |

### 前端

Vue 3.5（CDN 本地化，无构建步骤）+ Element Plus 2.8 + ECharts 5.5
+ **Canvas 2D 世界坐标变换渲染**（缩放/平移/溯源高亮）

> 前端零构建：`frontend/index.html` + `app.js` + `vendor/` 本地依赖，
> 由 `frontend/server.js`（Node 内置 http，零依赖）托管。

---

## 五、项目结构

```
dwgmind/
├─ start.bat                 一键启动（校验 JDK → 起后端 → 起前端 → 开浏览器）
├─ smoke-test.bat            接口冒烟测试
├─ backend/                  Spring Boot 后端
│  ├─ pom.xml
│  └─ src/main/java/com/dwgmind/
│     ├─ DwgMindApplication.java
│     ├─ common/             统一响应体、错误码、全局异常
│     ├─ config/             WebSocket 协同复核通道
│     ├─ domain/             Drawing / GeoEntity / SemanticRelation / SemanticInference
│     ├─ repository/         JPA 仓储
│     ├─ service/
│     │  ├─ DxfParser.java       DXF 组码解析（含 GBK 兼容）
│     │  ├─ ImageAnalyzer.java   图像骨架提取 + 缩略图
│     │  ├─ BoundingBox.java     包围盒与空间距离
│     │  ├─ SemanticEngine.java  零件聚类 + 关系构建 + 结论生成 + 溯源
│     │  ├─ DrawingService.java  全流程编排（异步解析 + 缓存 + 复核）
│     │  ├─ ProgressService.java SSE 进度推送
│     │  ├─ CacheService.java    Redis 缓存/榜单/限流/任务进度
│     │  └─ LlmClient.java       大模型可选增强（降级安全）
│     └─ web/                 DrawingController / ParseController / SystemController
├─ frontend/                 前端单页应用（零构建）
│  ├─ index.html
│  ├─ app.js                 Vue 3 逻辑 + Canvas 世界坐标渲染
│  ├─ server.js              零依赖静态服务器
│  ├─ vendor/                Vue / Element Plus / ECharts 本地化
│  └─ samples/sample.dxf     示例图纸（含轮廓/孔位/尺寸/注记/中心线）
├─ sidecar/                  Python 几何/OCR 边车（可选）
│  └─ main.py                FastAPI，OCR 需 tesseract，DXF 需 ezdxf
├─ scripts/
│  └─ verify-ui.js           CDP 无头浏览器端到端验证（16 项断言）
├─ docs/
│  ├─ ui-workspace.png       界面截图
│  ├─ 技术及接口选型-v2.md    选型文档
│  └─ 验证报告.md            构建与验证记录
└─ uploads/                  图纸存储目录
```

---

## 六、手机 / 平板访问（PWA）

### 手机访问（同一 Wi-Fi 下，零安装）

1. 电脑上双击 **`start.bat`**，控制台会打印手机访问地址，例如：

   `http://192.168.20.7:5173`

2. 手机浏览器（Chrome / Safari / Edge）打开该地址
3. 打开后界面会自动切换为移动布局：
   - 画布铺满屏幕，**单指拖动平移、双指捏合缩放、双击复位**
   - 底部导航：**图纸**（左侧抽屉：上传与历史）/ **适应** / **语义**（底部上滑面板：结论 / 复核 / BOM / 问答）

> 前提：手机与电脑在同一局域网；Windows 防火墙已放行 Node.js 与 Java。

### 安装到桌面（像 App 一样）

`manifest.webmanifest` 与图标已配置，支持添加到主屏：

- **Android / Chrome**：菜单 →「添加到主屏幕」或「安装应用」
- **iOS / Safari**：分享按钮 →「添加到主屏幕」

安装后以独立窗口运行，无浏览器地址栏。

### 关于离线能力的说明（如实告知）

Service Worker（`sw.js`）已实现，但浏览器规定它**只在安全上下文生效**（HTTPS 或 localhost）。
因此：

| 访问方式 | 界面 | 添加到主屏 | 离线缓存 |
|---|---|---|---|
| `http://localhost:5173` | ✅ | ✅ | ✅ |
| `http://192.168.x.x:5173`（局域网） | ✅ | ✅（快捷方式） | ❌ 浏览器不支持 |
| HTTPS（自签或域名） | ✅ | ✅ | ✅ |

局域网 HTTP 下仍可正常使用全部功能，只是没有离线缓存。
若需要完整 PWA 能力，可给 Node 静态服务加自签证书跑 HTTPS（浏览器会提示证书风险）。

---
## 七、接口一览

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/v1/health` | 健康检查（MySQL / Redis / LLM / 能力清单） |
| POST | `/api/v1/drawings/upload` | 上传图纸（自动触发解析） |
| GET | `/api/v1/drawings` | 图纸列表 |
| GET | `/api/v1/drawings/{id}` | 图纸详情 |
| GET | `/api/v1/drawings/{id}/entities` | 图元列表 |
| GET | `/api/v1/drawings/{id}/relations` | 语义关系 |
| GET | `/api/v1/drawings/{id}/inferences` | 语义结论 |
| GET | `/api/v1/drawings/{id}/bom` | BOM 清单 |
| GET | `/api/v1/drawings/{id}/review-queue` | 复核队列 |
| GET | `/api/v1/drawings/trace/{inferenceId}` | 结论溯源坐标 |
| POST | `/api/v1/drawings/{id}/parse` | 异步重新解析 |
| POST | `/api/v1/inferences/{id}/review` | 复核（CONFIRM / CORRECT） |
| POST | `/api/v1/drawings/{id}/qa` | 图纸知识问答 |
| GET | `/api/v1/tasks/{taskId}/stream` | SSE 进度流 |
| GET | `/api/v1/leaderboard` | 实时榜单（Redis ZSET） |
| WS | `/ws/review` | 多人复核广播 |

---

## 八、大模型接入（已配置 DeepSeek）

系统默认**已接入 DeepSeek 并验证通过**（docs/验证报告.md 有实测记录）。
未配置 Key 时会自动降级为纯规则引擎，功能完整可用。

### 配置方式（二选一）

**方式 A：本机配置文件（推荐，仓库内已 gitignore）**

编辑根目录 llm.local.env：

```
LLM_ENABLED=true
LLM_PROVIDER=deepseek
LLM_BASE_URL=https://api.deepseek.com
LLM_MODEL=deepseek-chat
LLM_API_KEY=sk-你的密钥
```

start.bat 启动时会自动读取该文件并注入进程环境变量。

**方式 B：系统环境变量**

```bat
setx LLM_API_KEY "sk-你的密钥"
setx LLM_PROVIDER "deepseek"
```

### 接入后的效果

| 能力 | 接入前（规则引擎） | 接入后（LLM 增强） |
|---|---|---|
| 语义结论 | 13 条（规则生成） | **14 条**，新增「大模型结构研判」 |
| 结论内容 | 几何统计（"16 个图元，包围盒 340×180"） | 结合材料与标注的工艺判断（"6061-T6 适合铣削，M8×1.25 攻丝"） |
| 图纸问答 | 关键词命中 + 统计应答 | **自然语言推理应答**，可解释依据 |
| 响应耗时 | 57—132ms | **1.1—1.9 秒**（含大模型往返） |
| 顶栏标识 | 规则引擎 | LLM 增强 |

### 支持的服务商（均为 OpenAI 兼容协议）

| 服务商 | BASE_URL | 推荐模型 |
|---|---|---|
| DeepSeek | https://api.deepseek.com | deepseek-chat |
| 阿里云百炼 | https://dashscope.aliyuncs.com/compatible-mode/v1 | qwen-plus |

> 安全提示：llm.local.env 已在 .gitignore 中排除，**不会进入提交包**。

---
## 九、可选增强：Python 边车（OCR）

```bat
cd sidecar
pip install fastapi uvicorn pillow python-multipart ezdxf
python -m uvicorn main:app --host 127.0.0.1 --port 8100
```

OCR 需要额外安装 [Tesseract](https://github.com/UB-Mannheim/tesseract/wiki) 并加入 PATH。
边车不启动不影响主流程——Java 内置解析已可独立工作。

---

## 十、已知边界（如实说明）

1. **DWG / PDF 未直接解析**：请先在 CAD 中另存为 DXF，或导出 PNG/JPG。系统会给出明确提示而非伪造结果。
2. **图像类图纸为"骨架级"理解**：提取水平/垂直线族用于定位，不具备 OCR 全文识别（需边车 + Tesseract）。
3. **语义分组基于几何邻近与图层**：对规范制图（图层规范、线头闭合）效果好；潦草手绘图的置信度会下降，这正是"人工复核"存在的意义。
4. **大模型为可选增强**：未配置 Key 时自动降级，不影响演示与评测。






---

## 十一、常见问题

### Q1：双击 start.bat 后，服务起来但过一会儿就没了？

正常情况下不会。start.bat 用 Start-Process 把 Java 与 Node 启动为**独立进程**，
脚本窗口关掉、PowerShell 退出，服务都会继续运行。

**已实测验证（2026-10-28）**：通过完全脱离父进程的方式启动后，
启动器进程退出 → java.exe 与 node.exe 的父进程变为空（成为孤儿进程）→
等待 30 秒后健康检查仍为 UP。说明服务生命周期不依赖启动器。

**唯一例外**：如果你是通过**其他程序间接调用** start.bat（IDE 终端、AI 助手、
CI 任务、批处理流水线等），那些宿主环境在任务结束时会清理自己启动的整个进程树，
服务会被连带结束——这不是脚本的问题，而是宿主的行为。

解决办法：直接在**文件资源管理器里双击** start.bat，
或在独占的 PowerShell 窗口里执行 scripts\start.ps1。
若被宿主误杀，重新双击一次即可，start.bat 会自动清理残留端口。

### Q2：怎么确认服务在跑？

- 浏览器打开 http://127.0.0.1:5173 （前端界面）
- 或访问 http://127.0.0.1:8080/api/v1/health （健康检查 JSON）
- 或命令行：netstat -ano | findstr ":8080 :5173"

### Q3：端口被占用导致启动失败？

start.bat 会自动检测并结束占用 8080 / 5173 的进程，无需手工处理。
若想保留正在运行的服务，先不要重复启动。

### Q4：问答里点「提问」没反应 / 结果滚不动？

- 没反应：确认已选中一张图纸（左侧历史列表或「载入示例图」）
- 滚不动：结果区是独立滚动容器，鼠标需在**回答文字区域内**滚动；
  若仍无效，按 F12 查看 Console 是否有报错，并反馈截图

### Q5：大模型没有生效（顶栏显示「规则引擎」）？

按顺序检查：

1. 根目录是否存在 llm.local.env（不是 .example）
2. 文件里 LLM_API_KEY= 后面是否填了真实 Key
3. 重启服务（环境变量在进程启动时读取，改完必须重启）
4. 访问 /api/v1/health，看 llm.configured 是否为 	rue
5. 若为 alse，说明 Key 没被读到——检查系统环境变量或文件路径

