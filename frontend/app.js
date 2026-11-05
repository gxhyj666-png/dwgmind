/* 图语者 DwgMind · 前端主逻辑（Vue 3 + Element Plus + Canvas 2D） */
const { createApp, ref, reactive, computed, onMounted, nextTick } = Vue;

/**
 * 后端地址解析：
 *   1. 页面 URL 带 ?api=http://host:port 时优先使用（便于部署到任意后端）
 *   2. 前端与后端同源（例如后端直接托管了前端）时用相对路径
 *   3. 否则默认本机 8080（开发/演示时的标准拓扑：前端 5173，后端 8080）
 */
const API_BASE = (() => {
    const q = new URLSearchParams(location.search).get('api');
    if (q) return q.replace(/\/$/, '');
    if (location.port === '8080' || location.port === '') return '';
    return location.protocol + '//' + location.hostname + ':8080';
})();
const WS_BASE = API_BASE.replace(/^http/, 'ws');

const app = createApp({
    setup() {
        // ================= 基础状态 =================
        const health = reactive({ database: '-', redis: '-', llm: {}, capabilities: [], sidecar: {} });
        const drawings = ref([]);
        const current = ref(null);
        const entities = ref([]);
        const inferences = ref([]);
        const reviewQueue = ref([]);
        const bom = ref([]);
        const leaderboard = ref([]);

        const tab = ref('conclusions');
        const parsing = ref(false);
        const progressPercent = ref(0);
        const progressMessage = ref('');
        const dragOver = ref(false);

        const selectedInference = ref(null);
        const correctDialog = ref(false);
        const correctText = ref('');
        const correctTarget = ref(null);
        const showAbout = ref(false);

        // ---------- 移动端：左侧抽屉 + 底部语义面板 ----------
        const drawerOpen = ref(false);
        const panelOpen = ref(false);
        const isMobile = () => window.matchMedia('(max-width: 900px)').matches;
        function toggleDrawer() {
            drawerOpen.value = !drawerOpen.value;
            if (drawerOpen.value) panelOpen.value = false;
        }
        function togglePanel() {
            panelOpen.value = !panelOpen.value;
            if (panelOpen.value) drawerOpen.value = false;
            // 面板展开后画布可视区变化，重算一次取景
            nextTick(() => setTimeout(resizeCanvas, 280));
        }
        function closeMobileOverlays() {
            drawerOpen.value = false;
            panelOpen.value = false;
        }

        const question = ref('');
        const asking = ref(false);
        const answer = ref('');
        const answerEngine = ref('');
        const answerCitations = ref(0);
        const qaResultRef = ref(null);

        // 每次出新答案后把结果区滚回顶部，避免停留在上一次的位置
        Vue.watch(answer, async () => {
            await nextTick();
            if (qaResultRef.value) qaResultRef.value.scrollTop = 0;
        });

        const llmReady = computed(() => !!(health.llm && health.llm.configured));

        // ================= 画布 =================
        const canvasWrap = ref(null);
        let canvas, ctx;
        const view = reactive({ scale: 1, offsetX: 0, offsetY: 0, dragging: false, lastX: 0, lastY: 0 });
        const selectedTrace = ref([]);

        function typeName(t) {
            return ({
                PART_IDENTIFY: '零件识别', DIMENSION_ATTACH: '尺寸归属', CONTOUR_GROUP: '轮廓特征',
                BOM_ITEM: 'BOM', PROCESS_NOTE: '图面注记', RISK_NOTE: '协同提示', LLM_INSIGHT: '大模型研判'
            })[t] || t;
        }
        function statusName(s) {
            return ({ AUTO: '自动', PENDING_REVIEW: '待复核', CONFIRMED: '已确认', CORRECTED: '已修正' })[s] || s;
        }
        function statusClass(s) {
            if (s === 'PENDING_REVIEW') return 'warn';
            if (s === 'CONFIRMED' || s === 'CORRECTED') return 'ok';
            return '';
        }

        // ---------- 接口 ----------
        async function api(path, opts) {
            const res = await fetch(API_BASE + path, opts);
            const json = await res.json();
            if (json && json.code && json.code !== 200) {
                throw new Error(json.message || '请求失败');
            }
            return json.data !== undefined ? json.data : json;
        }

        async function loadHealth() {
            try {
                const h = await api('/api/v1/health');
                Object.assign(health, h);
            } catch (e) { /* 忽略 */ }
        }
        async function loadDrawings() {
            try { drawings.value = await api('/api/v1/drawings') || []; } catch (e) { }
        }
        async function loadLeaderboard() {
            try {
                const r = await api('/api/v1/leaderboard?topic=default');
                leaderboard.value = r.items || [];
            } catch (e) { }
        }
        async function refreshAll() {
            await Promise.all([loadHealth(), loadDrawings(), loadLeaderboard()]);
        }

        // ---------- 选择图纸 ----------
        async function openDrawing(id) {
            const d = await api('/api/v1/drawings/' + id);
            current.value = d;
            await loadSemantics(id);
            await nextTick();
            ensureCanvasReady();
            fitView();
            resizeCanvas();
        }

        async function loadSemantics(id) {
            const [ents, infs, bomRows, rq] = await Promise.all([
                api('/api/v1/drawings/' + id + '/entities'),
                api('/api/v1/drawings/' + id + '/inferences'),
                api('/api/v1/drawings/' + id + '/bom'),
                api('/api/v1/drawings/' + id + '/review-queue')
            ]);
            entities.value = ents || [];
            inferences.value = infs || [];
            bom.value = bomRows || [];
            reviewQueue.value = rq || [];
            selectedInference.value = null;
            selectedTrace.value = [];
            drawCanvas();
        }

        // ---------- 上传 ----------
        function onPick(ev) {
            const f = ev.target.files && ev.target.files[0];
            if (f) doUpload(f);
            ev.target.value = '';
        }
        function onDrop(ev) {
            dragOver.value = false;
            const f = ev.dataTransfer.files && ev.dataTransfer.files[0];
            if (f) doUpload(f);
        }

        async function doUpload(file) {
            parsing.value = true;
            progressPercent.value = 2;
            progressMessage.value = '正在上传图纸…';
            try {
                const fd = new FormData();
                fd.append('file', file);
                const res = await fetch(API_BASE + '/api/v1/drawings/upload', { method: 'POST', body: fd });
                const json = await res.json();
                if (json.code !== 200) throw new Error(json.message);
                const { drawing, taskId } = json.data;
                current.value = drawing;
                entities.value = [];
                inferences.value = [];
                bom.value = [];
                reviewQueue.value = [];
                drawCanvas();
                await loadDrawings();
                await listenProgress(taskId, drawing.id);
            } catch (e) {
                parsing.value = false;
                ElementPlus.ElMessage.error('上传失败：' + e.message);
            }
        }

        function listenProgress(taskId, drawingId) {
            return new Promise((resolve) => {
                const es = new EventSource(API_BASE + '/api/v1/tasks/' + taskId + '/stream');
                es.addEventListener('progress', (ev) => {
                    try {
                        const p = JSON.parse(ev.data);
                        progressPercent.value = p.percent || progressPercent.value;
                        progressMessage.value = p.message || '';
                    } catch (e) { }
                });
                es.addEventListener('done', async (ev) => {
                    es.close();
                    parsing.value = false;
                    progressPercent.value = 100;
                    try {
                        const p = JSON.parse(ev.data);
                        ElementPlus.ElMessage.success(p.message || '解析完成');
                    } catch (e) { }
                    current.value = await api('/api/v1/drawings/' + drawingId);
                    await loadSemantics(drawingId);
                    await refreshAll();
                    fitView();
                    resolve();
                });
                es.addEventListener('error', async (ev) => {
                    try {
                        const p = JSON.parse(ev.data);
                        ElementPlus.ElMessage.error('解析失败：' + p.message);
                    } catch (e) {
                        ElementPlus.ElMessage.error('解析失败，请查看后端日志');
                    }
                    es.close();
                    parsing.value = false;
                    current.value = await api('/api/v1/drawings/' + drawingId);
                    await loadDrawings();
                    resolve();
                });
                // 兜底：60 秒后强制结束，避免卡住
                setTimeout(() => {
                    if (es.readyState !== 2) {
                        es.close();
                        if (parsing.value) {
                            parsing.value = false;
                            loadSemantics(drawingId).catch(() => { });
                        }
                        resolve();
                    }
                }, 60000);
            });
        }

        // ---------- 复核 ----------
        async function doReview(inf, action, content) {
            try {
                await api('/api/v1/inferences/' + inf.id + '/review', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ action, content })
                });
                ElementPlus.ElMessage.success(action === 'CONFIRM' ? '已确认' : '已修正，样本已回流');
                await loadSemantics(current.value.id);
                drawCanvas();
            } catch (e) {
                ElementPlus.ElMessage.error(e.message);
            }
        }
        function openCorrect(inf) {
            correctTarget.value = inf;
            correctText.value = inf.content;
            correctDialog.value = true;
        }
        async function submitCorrect() {
            if (!correctTarget.value) return;
            correctDialog.value = false;
            await doReview(correctTarget.value, 'CORRECT', correctText.value);
        }

        // ---------- 选择结论 => 高亮溯源 ----------
        async function selectInference(inf) {
            selectedInference.value = inf;
            try {
                const t = await api('/api/v1/drawings/trace/' + inf.id);
                let arr = [];
                try { arr = JSON.parse(t.trace || '[]'); } catch (e) { }
                selectedTrace.value = Array.isArray(arr) ? arr : [];
            } catch (e) {
                selectedTrace.value = [];
            }
            drawCanvas();
        }

        // ---------- 问答 ----------
        async function ask() {
            if (!current.value) { ElementPlus.ElMessage.warning('请先上传或选择一张图纸'); return; }
            if (!question.value.trim()) return;
            asking.value = true;
            answer.value = '';
            try {
                const r = await api('/api/v1/drawings/' + current.value.id + '/qa', {
                    method: 'POST',
                    headers: { 'Content-Type': 'application/json' },
                    body: JSON.stringify({ question: question.value })
                });
                answer.value = r.answer;
                answerEngine.value = r.engine;
                answerCitations.value = r.citations || 0;
            } catch (e) {
                ElementPlus.ElMessage.error(e.message);
            } finally {
                asking.value = false;
            }
        }

        // ================= 画布绘制 =================
        function resizeCanvas() {
            if (!canvas) {
                canvas = document.getElementById('dwg-canvas');
                if (canvas) ctx = canvas.getContext('2d');
            }
            if (!canvas || !ctx) return;
            const wrap = canvasWrap.value || document.querySelector('.canvas-wrap');
            if (!wrap) return;
            const rect = wrap.getBoundingClientRect();
            if (rect.width < 10 || rect.height < 10) return;
            const dpr = window.devicePixelRatio || 1;
            const w = Math.max(1, Math.round(rect.width * dpr));
            const h = Math.max(1, Math.round(rect.height * dpr));
            if (canvas.width !== w || canvas.height !== h) {
                canvas.width = w;
                canvas.height = h;
            }
            canvas.style.width = rect.width + 'px';
            canvas.style.height = rect.height + 'px';
            ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
            drawCanvas();
        }

        /**
         * 等待画布容器具备真实尺寸再初始化。
         * 踩过的坑：<canvas> 默认是 300x150 位图，若挂载早期容器尚未布局完成，
         * 就会一直以默认尺寸绘制——图元全部画到视野之外，表现为「画布空白」。
         */
        function ensureCanvasReady(tries = 0) {
            if (!canvas) {
                canvas = document.getElementById('dwg-canvas');
                if (canvas) ctx = canvas.getContext('2d');
            }
            const wrap = canvasWrap.value || document.querySelector('.canvas-wrap');
            const rect = wrap ? wrap.getBoundingClientRect() : { width: 0, height: 0 };
            if (!canvas || !ctx || rect.width < 50 || rect.height < 50) {
                if (tries < 50) setTimeout(() => ensureCanvasReady(tries + 1), 100);
                return false;
            }
            resizeCanvas();
            return true;
        }

        function isRaster(fileType) {
            return ['png', 'jpg', 'jpeg', 'bmp'].includes((fileType || '').toLowerCase());
        }

        /**
         * 取景范围：
         * 以「几何图元」（线/弧/圆）为主体，文本与尺寸只在不超出主体 1.8 倍时纳入，
         * 避免图纸边角的标题栏/图号把整体视图缩得过小。
         */
        function computeBounds() {
            if (isRaster(current.value && current.value.fileType)) {
                return { x1: 0, y1: 0, x2: (current.value && current.value.canvasWidth) || 1, y2: (current.value && current.value.canvasHeight) || 1 };
            }
            const geom = entities.value.filter(e => ['LINE', 'CIRCLE', 'ARC', 'POLYLINE'].includes(e.type));
            const box = (list) => {
                let x1 = Infinity, y1 = Infinity, x2 = -Infinity, y2 = -Infinity;
                list.forEach(e => {
                    const r = e.radius || 0;
                    const ex1 = Math.min(e.x1 ?? 0, e.x2 ?? e.x1 ?? 0) - r;
                    const ey1 = Math.min(e.y1 ?? 0, e.y2 ?? e.y1 ?? 0) - r;
                    const ex2 = Math.max(e.x1 ?? 0, e.x2 ?? e.x1 ?? 0) + r;
                    const ey2 = Math.max(e.y1 ?? 0, e.y2 ?? e.y1 ?? 0) + r;
                    x1 = Math.min(x1, ex1); y1 = Math.min(y1, ey1);
                    x2 = Math.max(x2, ex2); y2 = Math.max(y2, ey2);
                });
                return { x1, y1, x2, y2 };
            };
            let b = box(geom);
            if (!isFinite(b.x1)) b = box(entities.value);
            if (!isFinite(b.x1)) return { x1: 0, y1: 0, x2: 1, y2: 1 };

            // 主体范围外扩 40%，仅纳入落在其中的文本/尺寸
            const gw = Math.max(1, b.x2 - b.x1), gh = Math.max(1, b.y2 - b.y1);
            const limit = { x1: b.x1 - gw * 0.4, y1: b.y1 - gh * 0.4, x2: b.x2 + gw * 0.4, y2: b.y2 + gh * 0.4 };
            const extra = entities.value.filter(e =>
                ['TEXT', 'DIMENSION'].includes(e.type) &&
                (e.x1 ?? 0) >= limit.x1 && (e.x1 ?? 0) <= limit.x2 &&
                (e.y1 ?? 0) >= limit.y1 && (e.y1 ?? 0) <= limit.y2);
            if (extra.length) {
                const eb = box(extra);
                b = {
                    x1: Math.min(b.x1, eb.x1), y1: Math.min(b.y1, eb.y1),
                    x2: Math.max(b.x2, eb.x2), y2: Math.max(b.y2, eb.y2)
                };
            }
            const w = Math.max(1, b.x2 - b.x1), h = Math.max(1, b.y2 - b.y1);
            return { x1: b.x1 - w * 0.06, y1: b.y1 - h * 0.06, x2: b.x2 + w * 0.06, y2: b.y2 + h * 0.06 };
        }

        /**
         * 取景视口：以画布「实际位图尺寸」为准，而不是外层容器尺寸。
         * 踩过的坑：canvas 为 absolute+inset:0，其 CSS 高度可能比容器少几像素，
         * 若用容器高度计算偏移，内容会被整体推出可视区。
         */
        function viewport() {
            const dpr = window.devicePixelRatio || 1;
            if (canvas && canvas.width > 1 && canvas.height > 1) {
                return { width: canvas.width / dpr, height: canvas.height / dpr };
            }
            const wrap = canvasWrap.value || document.querySelector('.canvas-wrap');
            if (!wrap) return { width: 0, height: 0 };
            const r = wrap.getBoundingClientRect();
            return { width: r.width, height: r.height };
        }

        function fitView() {
            if (!current.value) return;
            const vp = viewport();
            if (vp.width < 10 || vp.height < 10) return;
            const ws = worldSize();
            const pad = 32;
            const s = Math.min((vp.width - pad * 2) / Math.max(1, ws.w), (vp.height - pad * 2) / Math.max(1, ws.h));
            view.scale = s > 0 ? s : 1;
            const contentW = ws.w * view.scale, contentH = ws.h * view.scale;
            view.offsetX = (vp.width - contentW) / 2;
            view.offsetY = (vp.height - contentH) / 2;
            window.__dwgLastFit = {
                vp: { width: vp.width, height: vp.height },
                world: { w: ws.w, h: ws.h, raster: ws.raster },
                bounds: ws.b,
                scale: view.scale, contentW, contentH,
                offsetX: view.offsetX, offsetY: view.offsetY
            };
            drawCanvas();
        }

        function zoomBy(f) {
            if (!current.value) return;
            const vp = viewport();
            if (vp.width < 10) return;
            const cx = vp.width / 2, cy = vp.height / 2;
            const wx = (cx - view.offsetX) / view.scale;
            const wy = (cy - view.offsetY) / view.scale;
            view.scale *= f;
            view.offsetX = cx - wx * view.scale;
            view.offsetY = cy - wy * view.scale;
            drawCanvas();
        }

        /**
         * 统一世界坐标系：Y 轴向下、原点左上。
         * DXF 原始坐标 Y 轴向上，因此在绘图上下文里整体翻转一次（见 applyWorldTransform），
         * 这样 fitView 求出的包围盒==屏幕可见范围，不会出现「上半张图被裁掉」。
         */
        function worldSize() {
            const b = computeBounds();
            const raster = isRaster(current.value && current.value.fileType);
            if (raster) {
                const w = (current.value && current.value.canvasWidth) || (b.x2);
                const h = (current.value && current.value.canvasHeight) || (b.y2);
                return { w: Math.max(1, w), h: Math.max(1, h), b: { x1: 0, y1: 0, x2: w, y2: h }, raster: true };
            }
            return { w: Math.max(1, b.x2 - b.x1), h: Math.max(1, b.y2 - b.y1), b, raster: false };
        }

        /** 把世界坐标映射到画布：缩放 + 平移，DXF 额外翻转 Y */
        function applyWorldTransform(extra) {
            const { b, raster } = worldSize();
            const dpr = window.devicePixelRatio || 1;
            ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
            const s = view.scale;
            if (raster) {
                // 栅格：原点即左上，无需翻转
                ctx.translate(view.offsetX, view.offsetY);
                ctx.scale(s, s);
            } else {
                // 矢量：绕视口上边界翻转 Y，再把世界 b.y1 对齐到顶部
                ctx.translate(view.offsetX, (view.offsetY || 0));
                ctx.scale(s, -s);
                ctx.translate(0, -(b.y2));
            }
            if (extra) extra();
        }

        /** 世界坐标 -> 屏幕坐标（仅用于交互命中与提示，绘制走变换矩阵） */
        function toScreen(x, y) {
            const { b, raster } = worldSize();
            if (raster) {
                return { x: x * view.scale + view.offsetX, y: y * view.scale + view.offsetY };
            }
            // 矢量：世界 y 向上，屏幕 y 向下
            return {
                x: (x - b.x1) * view.scale + view.offsetX,
                y: (b.y2 - y) * view.scale + view.offsetY
            };
        }

        function drawCanvas() {
            if (!ctx || !current.value) {
                if (ctx) {
                    const dpr = window.devicePixelRatio || 1;
                    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
                    ctx.clearRect(0, 0, canvas.width / dpr, canvas.height / dpr);
                    ctx.fillStyle = '#0b0f18';
                    ctx.fillRect(0, 0, canvas.width / dpr, canvas.height / dpr);
                }
                return;
            }
            const dpr = window.devicePixelRatio || 1;
            // 1) 清屏（屏幕坐标系）
            ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
            ctx.clearRect(0, 0, canvas.width / dpr, canvas.height / dpr);
            ctx.fillStyle = '#0b0f18';
            ctx.fillRect(0, 0, canvas.width / dpr, canvas.height / dpr);

            // 2) 栅格底图（在世界变换下绘制，保证与原图对齐）
            if (isRaster(current.value.fileType) && current.value.thumbnail) {
                if (!window.__dwgRasterImg || window.__dwgRasterSrc !== current.value.thumbnail) {
                    const img = new Image();
                    img.onload = () => {
                        window.__dwgRasterImg = img;
                        window.__dwgRasterSrc = current.value.thumbnail;
                        drawCanvas();
                    };
                    img.src = current.value.thumbnail;
                    return;
                }
                applyWorldTransform(() => {
                    ctx.globalAlpha = 0.9;
                    ctx.drawImage(window.__dwgRasterImg, 0, 0, worldSize().w, worldSize().h);
                    ctx.globalAlpha = 1;
                });
            }

            // 3) 图元与高亮（世界坐标系绘制）
            drawEntitiesLayer();
        }

        function drawEntitiesLayer() {
            // 结论高亮区域（垫在图元下面）
            if (selectedTrace.value && selectedTrace.value.length) {
                applyWorldTransform(() => {
                    ctx.save();
                    ctx.fillStyle = 'rgba(240, 180, 41, 0.18)';
                    ctx.strokeStyle = '#f0b429';
                    ctx.lineWidth = 2 / view.scale;
                    ctx.setLineDash([6 / view.scale, 4 / view.scale]);
                    selectedTrace.value.forEach(t => {
                        const x = t.x - (t.w || 1) / 2;
                        const y = t.y - (t.h || 1) / 2;
                        ctx.fillRect(x, y, Math.max(0.5, t.w || 1), Math.max(0.5, t.h || 1));
                        ctx.strokeRect(x, y, Math.max(0.5, t.w || 1), Math.max(0.5, t.h || 1));
                    });
                    ctx.restore();
                });
            }

            applyWorldTransform(() => {
                const raster = isRaster(current.value && current.value.fileType);
                ctx.lineWidth = 1.2 / view.scale;
                ctx.lineJoin = 'round';
                ctx.lineCap = 'round';
                // 文字需要独立缩放，避免被翻转
                ctx.save();
                entities.value.forEach(e => {
                    let color = '#4f8cff';
                    if (e.reviewStatus === 'PENDING_REVIEW') color = '#f0b429';
                    if (e.type === 'DIMENSION' || e.type === 'TEXT') color = '#8fb8ff';
                    ctx.strokeStyle = color;
                    ctx.fillStyle = color;
                    ctx.globalAlpha = raster ? 0.38 : 1;

                    if (e.type === 'LINE') {
                        ctx.beginPath();
                        ctx.moveTo(e.x1 ?? 0, e.y1 ?? 0);
                        ctx.lineTo(e.x2 ?? e.x1 ?? 0, e.y2 ?? e.y1 ?? 0);
                        ctx.stroke();
                    } else if (e.type === 'CIRCLE') {
                        const r = e.radius || 0;
                        if (r > 0) {
                            ctx.beginPath();
                            ctx.arc(e.x1 ?? 0, e.y1 ?? 0, r, 0, Math.PI * 2);
                            ctx.stroke();
                        }
                    } else if (e.type === 'ARC') {
                        const r = e.radius || 0;
                        if (r > 0) {
                            const a1 = (e.startAngle || 0) * Math.PI / 180;
                            const a2 = (e.endAngle || 0) * Math.PI / 180;
                            ctx.beginPath();
                            ctx.arc(e.x1 ?? 0, e.y1 ?? 0, r, a1, a2, false);
                            ctx.stroke();
                        }
                    } else if (e.type === 'DIMENSION') {
                        const cx = e.x1 ?? 0, cy = e.y1 ?? 0;
                        const k = 4 / view.scale;
                        ctx.beginPath();
                        ctx.moveTo(cx - k, cy); ctx.lineTo(cx + k, cy);
                        ctx.moveTo(cx, cy - k); ctx.lineTo(cx, cy + k);
                        ctx.stroke();
                        if (e.text) {
                            drawWorldText(e.text, cx + k * 1.6, cy - k * 1.6, 11, '#f0b429');
                        }
                    } else if (e.type === 'TEXT') {
                        drawWorldText(e.text || '', e.x1 ?? 0, e.y1 ?? 0, 12, '#9fc2ff');
                    }
                });
                ctx.globalAlpha = 1;
                ctx.restore();
            });
        }

        /** 在世界坐标系里绘制可读文字（抵消 Y 翻转与缩放） */
        function drawWorldText(text, wx, wy, px, color) {
            const { b, raster } = worldSize();
            const p = raster
                ? { x: wx, y: wy }
                : { x: wx, y: wy };
            ctx.save();
            ctx.translate(p.x, p.y);
            ctx.scale(1, -1);           // 抵消 Y 翻转
            ctx.scale(1 / view.scale, 1 / view.scale); // 抵消缩放
            ctx.fillStyle = color;
            ctx.font = px + 'px "Microsoft YaHei", sans-serif';
            ctx.textAlign = 'left';
            ctx.textBaseline = 'alphabetic';

            // 屏幕空间剩余宽度：文字超出画布右边界时自动压缩，避免被裁掉
            const scr = toScreen(wx, wy);
            const vp = viewport();
            const remainPx = vp.width - scr.x - 6;
            const worldLimit = remainPx > 20 ? remainPx * view.scale : 0;
            if (worldLimit > 0 && ctx.measureText(text).width > worldLimit) {
                ctx.fillText(text, 0, 0, worldLimit);
            } else {
                ctx.fillText(text, 0, 0);
            }
            ctx.restore();
        }

        // ---------- 画布交互 ----------
        function onWheel(ev) {
            if (!current.value) return;
            ev.preventDefault();
            const rect = canvas.getBoundingClientRect();
            const mx = ev.clientX - rect.left, my = ev.clientY - rect.top;
            const wx = (mx - view.offsetX) / view.scale;
            const wy = (my - view.offsetY) / view.scale;
            const factor = ev.deltaY < 0 ? 1.12 : 1 / 1.12;
            view.scale *= factor;
            view.offsetX = mx - wx * view.scale;
            view.offsetY = my - wy * view.scale;
            drawCanvas();
        }
        function onDown(ev) {
            view.dragging = true;
            view.lastX = ev.clientX;
            view.lastY = ev.clientY;
            canvas.classList.add('dragging');
        }
        function onMove(ev) {
            if (!view.dragging) return;
            view.offsetX += ev.clientX - view.lastX;
            view.offsetY += ev.clientY - view.lastY;
            view.lastX = ev.clientX;
            view.lastY = ev.clientY;
            drawCanvas();
        }
        function onUp() {
            view.dragging = false;
            canvas.classList.remove('dragging');
        }

        // ---------- 触摸手势：单指平移 / 双指捏合缩放 ----------
        let touchState = { mode: null, x: 0, y: 0, dist: 0, cx: 0, cy: 0 };

        function touchDistance(t1, t2) {
            const dx = t1.clientX - t2.clientX;
            const dy = t1.clientY - t2.clientY;
            return Math.sqrt(dx * dx + dy * dy);
        }

        function onTouchStart(ev) {
            if (!current.value) return;
            if (ev.touches.length === 1) {
                touchState.mode = 'pan';
                touchState.x = ev.touches[0].clientX;
                touchState.y = ev.touches[0].clientY;
                canvas.classList.add('dragging');
            } else if (ev.touches.length === 2) {
                touchState.mode = 'pinch';
                touchState.dist = touchDistance(ev.touches[0], ev.touches[1]);
                const rect = canvas.getBoundingClientRect();
                touchState.cx = (ev.touches[0].clientX + ev.touches[1].clientX) / 2 - rect.left;
                touchState.cy = (ev.touches[0].clientY + ev.touches[1].clientY) / 2 - rect.top;
            }
        }

        function onTouchMove(ev) {
            if (!current.value || !touchState.mode) return;
            ev.preventDefault(); // 阻止页面滚动/缩放，画布内手势独占
            if (touchState.mode === 'pan' && ev.touches.length === 1) {
                const t = ev.touches[0];
                view.offsetX += t.clientX - touchState.x;
                view.offsetY += t.clientY - touchState.y;
                touchState.x = t.clientX;
                touchState.y = t.clientY;
                drawCanvas();
            } else if (touchState.mode === 'pinch' && ev.touches.length === 2) {
                const d = touchDistance(ev.touches[0], ev.touches[1]);
                if (touchState.dist > 0) {
                    const factor = d / touchState.dist;
                    // 以双指中点为锚点缩放
                    const wx = (touchState.cx - view.offsetX) / view.scale;
                    const wy = (touchState.cy - view.offsetY) / view.scale;
                    view.scale = Math.max(0.02, Math.min(200, view.scale * factor));
                    view.offsetX = touchState.cx - wx * view.scale;
                    view.offsetY = touchState.cy - wy * view.scale;
                }
                touchState.dist = d;
                drawCanvas();
            }
        }

        function onTouchEnd() {
            touchState.mode = null;
            canvas.classList.remove('dragging');
        }

        // 双击复位取景（手机上没有滚轮，给一个快速回到全局的入口）
        let lastTap = 0;
        function onTouchEndWithTap(ev) {
            onTouchEnd();
            const now = Date.now();
            if (ev.touches.length === 0 && now - lastTap < 300) {
                fitView();
            }
            lastTap = now;
        }

        // ---------- 示例图 ----------
        async function loadSample() {
            try {
                const blob = await (await fetch(location.protocol + '//' + location.host + '/samples/sample.dxf')).blob();
                const file = new File([blob], 'sample-bracket.dxf', { type: 'application/dxf' });
                doUpload(file);
            } catch (e) {
                ElementPlus.ElMessage.error('示例图加载失败：' + e.message);
            }
        }

        // ================= 生命周期 =================
        onMounted(async () => {
            // 画布初始化要等容器布局完成，否则会停留在 300x150 默认位图尺寸
            const ok = ensureCanvasReady();
            if (!ok) {
                // 容器尺寸迟迟不到（极端情况）也要挂上事件，避免交互失效
                canvas = document.getElementById('dwg-canvas');
                if (canvas) ctx = canvas.getContext('2d');
            }
            window.addEventListener('resize', () => { resizeCanvas(); if (current.value) fitView(); });
            if (window.ResizeObserver) {
                const wrap = canvasWrap.value || document.querySelector('.canvas-wrap');
                if (wrap) {
                    const ro = new ResizeObserver(() => { resizeCanvas(); });
                    ro.observe(wrap);
                }
            }
            if (canvas) {
                canvas.addEventListener('wheel', onWheel, { passive: false });
                canvas.addEventListener('mousedown', onDown);
                // 触摸手势（手机 / 平板）
                canvas.addEventListener('touchstart', onTouchStart, { passive: true });
                canvas.addEventListener('touchmove', onTouchMove, { passive: false });
                canvas.addEventListener('touchend', onTouchEndWithTap, { passive: true });
                canvas.addEventListener('touchcancel', onTouchEnd, { passive: true });
                // 阻止 iOS 双击缩放整页
                canvas.style.touchAction = 'none';
            }
            window.addEventListener('mousemove', onMove);
            window.addEventListener('mouseup', onUp);

            // 注册 Service Worker（仅 HTTPS / localhost 生效，局域网 http 下浏览器会跳过）
            if ('serviceWorker' in navigator && window.isSecureContext) {
                navigator.serviceWorker.register('/sw.js').catch(() => { /* 忽略：非安全上下文 */ });
            }

            // 调试/自检钩子：暴露只读运行状态，便于自动化验证与现场排障
            window.__dwgmind = {
                state: () => ({
                    canvasAttr: canvas ? canvas.width + 'x' + canvas.height : 'none',
                    cssSize: canvas ? canvas.style.width + '/' + canvas.style.height : 'none',
                    scale: view.scale, offsetX: view.offsetX, offsetY: view.offsetY,
                    bounds: (() => {
                        if (!current.value) return null;
                        const b = computeBounds();
                        const raster = isRaster(current.value.fileType);
                        return {
                            x1: b.x1, y1: b.y1, x2: b.x2, y2: b.y2,
                            screenX1: b.x1 * view.scale + view.offsetX,
                            screenX2: b.x2 * view.scale + view.offsetX,
                            screenY1: (raster ? b.y1 : -b.y1) * view.scale + view.offsetY,
                            screenY2: (raster ? b.y2 : -b.y2) * view.scale + view.offsetY
                        };
                    })(),
                    entities: entities.value.length, cards: inferences.value.length,
                    currentId: current.value ? current.value.id : null,
                    currentStatus: current.value ? current.value.status : null,
                    traceItems: selectedTrace.value.length
                }),
                redraw: () => { ensureCanvasReady(); if (current.value) fitView(); },
                fit: () => { fitView(); return { scale: view.scale, ox: view.offsetX, oy: view.offsetY }; },
                wrapInfo: () => {
                    const w = canvasWrap.value || document.querySelector('.canvas-wrap');
                    if (!w) return 'no wrap';
                    const r = w.getBoundingClientRect();
                    return { tag: w.tagName + '.' + w.className, w: r.width, h: r.height };
                },
                dpr: () => window.devicePixelRatio
            };

            await refreshAll();
            // 支持 ?drawing=<id> 直接打开指定图纸（演示/截图用）
            const params = new URLSearchParams(location.search);
            const wantId = params.get('drawing');
            if (wantId) {
                try { await openDrawing(parseInt(wantId, 10)); } catch (e) { }
            } else {
                // 自动打开最近一张已解析图纸
                const ready = drawings.value.find(d => d.status === 'PARSED');
                if (ready) {
                    await openDrawing(ready.id);
                }
            }
            // 订阅复核广播
            try {
                const ws = new WebSocket(WS_BASE + '/ws/review');
                ws.onmessage = () => { if (current.value) loadSemantics(current.value.id).then(drawCanvas); };
            } catch (e) { }
            // 定时刷新榜单与健康
            setInterval(() => { loadHealth(); loadLeaderboard(); }, 15000);
        });

        return {
            health, drawings, current, entities, inferences, reviewQueue, bom, leaderboard,
            canvasWrap,
            tab, parsing, progressPercent, progressMessage, dragOver,
            drawerOpen, panelOpen, toggleDrawer, togglePanel, closeMobileOverlays,
            selectedInference, correctDialog, correctText, showAbout,
            question, asking, answer, answerEngine, answerCitations, llmReady, qaResultRef,
            typeName, statusName, statusClass,
            loadSample, refreshAll, openDrawing, onPick, onDrop,
            zoomBy, fitView, doReview, openCorrect, submitCorrect,
            selectInference, ask
        };
    }
});

app.use(ElementPlus);
app.mount('#app');








