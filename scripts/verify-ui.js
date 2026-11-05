/**
 * DwgMind UI 真实浏览器验证（Chrome DevTools Protocol）
 *
 * 用无头 Edge 打开前端，等待真实网络请求完成后：
 *   1. 断言关键 UI 元素与数据已渲染
 *   2. 点击历史图纸，验证画布与结论面板联动
 *   3. 截图输出，供人工复核
 *
 * 用法：node scripts/verify-ui.js [port] [drawingId]
 */
const { spawn } = require('child_process');
const fs = require('fs');
const path = require('path');
const http = require('http');
const WebSocket = require('ws');

const PORT = parseInt(process.argv[2] || '5173', 10);
const DRAWING_ID = process.argv[3] || '';
const EDGE = 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe';
const DEBUG_PORT = 9333;
const ROOT = path.resolve(__dirname, '..');
const OUT_DIR = path.join(ROOT, 'docs');
const PROFILE = path.join(ROOT, '.edge-profile-verify');

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

function httpJson(port, pathname) {
    return new Promise((resolve, reject) => {
        const req = http.get({ host: '127.0.0.1', port, path: pathname, timeout: 5000 }, (res) => {
            let body = '';
            res.on('data', (c) => (body += c));
            res.on('end', () => {
                try { resolve(JSON.parse(body)); } catch (e) { reject(e); }
            });
        });
        req.on('error', reject);
        req.on('timeout', () => { req.destroy(new Error('timeout')); });
    });
}

class Cdp {
    constructor(ws) {
        this.ws = ws;
        this.id = 0;
        this.pending = new Map();
        this.events = [];
        ws.on('message', (raw) => {
            const msg = JSON.parse(raw.toString());
            if (msg.id && this.pending.has(msg.id)) {
                const { resolve, reject } = this.pending.get(msg.id);
                this.pending.delete(msg.id);
                if (msg.error) reject(new Error(JSON.stringify(msg.error)));
                else resolve(msg.result);
            } else if (msg.method) {
                this.events.push(msg);
            }
        });
    }
    send(method, params = {}) {
        const id = ++this.id;
        return new Promise((resolve, reject) => {
            this.pending.set(id, { resolve, reject });
            this.ws.send(JSON.stringify({ id, method, params }));
            setTimeout(() => {
                if (this.pending.has(id)) {
                    this.pending.delete(id);
                    reject(new Error('CDP timeout: ' + method));
                }
            }, 30000);
        });
    }
    async eval(expression) {
        const r = await this.send('Runtime.evaluate', {
            expression,
            returnByValue: true,
            awaitPromise: true
        });
        if (r.exceptionDetails) {
            throw new Error('JS 异常: ' + JSON.stringify(r.exceptionDetails.exception && r.exceptionDetails.exception.description || r.exceptionDetails.text));
        }
        return r.result.value;
    }
}

async function main() {
    fs.mkdirSync(OUT_DIR, { recursive: true });
    fs.rmSync(PROFILE, { recursive: true, force: true });

    const args = [
        '--headless=new', '--disable-gpu', '--no-sandbox', '--no-first-run',
        '--remote-debugging-port=' + DEBUG_PORT,
        '--user-data-dir=' + PROFILE,
        '--window-size=1600,900',
        'about:blank'
    ];
    const edge = spawn(EDGE, args, { stdio: 'ignore' });
    const results = [];
    const log = (ok, msg) => {
        results.push({ ok, msg });
        console.log((ok ? '  [PASS] ' : '  [FAIL] ') + msg);
    };

    try {
        // 等待调试端口
        let target = null;
        for (let i = 0; i < 40; i++) {
            try {
                const list = await httpJson(DEBUG_PORT, '/json/list');
                target = list.find((t) => t.type === 'page');
                if (target) break;
            } catch (e) { /* 未就绪 */ }
            await sleep(250);
        }
        if (!target) throw new Error('无法连接 Edge 调试端口');

        const ws = new WebSocket(target.webSocketDebuggerUrl, { perMessageDeflate: false });
        await new Promise((res, rej) => {
            ws.once('open', res);
            ws.once('error', rej);
        });
        const cdp = new Cdp(ws);
        await cdp.send('Page.enable');
        await cdp.send('Runtime.enable');
        await cdp.send('Log.enable');
        await cdp.send('Emulation.setDeviceMetricsOverride', {
            width: 1600, height: 900, deviceScaleFactor: 1, mobile: false
        });

        // 收集页面错误
        const pageErrors = [];
        cdp.ws.on('message', (raw) => {
            const m = JSON.parse(raw.toString());
            if (m.method === 'Runtime.exceptionThrown') {
                pageErrors.push(JSON.stringify(m.params.exceptionDetails.text));
            }
            if (m.method === 'Log.entryAdded' && m.params.entry.level === 'error') {
                if (!/favicon/i.test(m.params.entry.text + (m.params.entry.url || ''))) pageErrors.push(m.params.entry.text);
            }
        });

        // ---------- 打开页面 ----------
        const url = 'http://127.0.0.1:' + PORT + '/' + (DRAWING_ID ? '?drawing=' + DRAWING_ID : '');
        await cdp.send('Page.navigate', { url });
        console.log('\n打开: ' + url);

        // 等待 Vue 挂载 + 数据加载（真实网络）
        let ready = false;
        let lastState = null;
        for (let i = 0; i < 60; i++) {
            await sleep(500);
            try {
                const state = await cdp.eval(`(() => {
                    const app = document.querySelector('#app');
                    if (!app) return { mounted: false, reason: 'no #app' };
                    if (!app.__vue_app__) return { mounted: false, reason: 'vue not mounted' };
                    const chips = [...document.querySelectorAll('.chip')].map(e => e.textContent.trim());
                    const history = [...document.querySelectorAll('.history-item')].map(e => e.textContent.trim());
                    const cards = document.querySelectorAll('.conc-card').length;
                    return { mounted: true, chips, history, cards, bodyLen: document.body.innerText.length };
                })()`);
                lastState = state;
                if (state && state.mounted) {
                    if (state.chips.some((c) => /UP/.test(c))) { ready = true; break; }
                }
            } catch (e) {
                lastState = { error: e.message };
            }
        }
        if (!ready) {
            console.error('[诊断] 最后一次页面状态: ' + JSON.stringify(lastState));
            if (pageErrors.length) {
                console.error('[诊断] 页面错误:');
                pageErrors.slice(0, 8).forEach((e) => console.error('   ' + e.slice(0, 300)));
            }
            throw new Error('页面在 30 秒内未完成初始化');
        }
        await sleep(1500);

        // ---------- 断言 1：健康状态 ----------
        const state1 = await cdp.eval(`(() => ({
            chips: [...document.querySelectorAll('.chip')].map(e => e.textContent.trim()),
            history: [...document.querySelectorAll('.history-item')].length,
            cards: document.querySelectorAll('.conc-card').length,
            tabs: [...document.querySelectorAll('.tab')].map(e => e.textContent.trim()),
            canvasSize: (() => { const c = document.getElementById('dwg-canvas'); return c ? c.width + 'x' + c.height : 'none'; })(),
            emptyCanvas: !!document.querySelector('.canvas-empty')
        }))()`);
        console.log('\n[1] 初始化状态');
        log(state1.chips.some((c) => /MySQL\s+UP/.test(c)), 'MySQL 连接状态显示 UP');
        log(state1.chips.some((c) => /Redis\s+UP/.test(c)), 'Redis 连接状态显示 UP');
        log(state1.chips.some((c) => c.includes('规则引擎') || c.includes('LLM')), '分析引擎状态已展示');
        log(state1.history > 0, '历史图纸列表已加载（' + state1.history + ' 条）');
        log(state1.tabs.length === 4, '四个语义面板标签渲染完成');

        // ---------- 断言 2：点击图纸 -> 画布与结论联动 ----------
        console.log('\n[2] 打开图纸并验证语义渲染');
        await cdp.eval(`(() => {
            const first = document.querySelector('.history-item');
            if (first) first.click();
            return true;
        })()`);
        await sleep(2500);

        const state2 = await cdp.eval(`(() => {
            const cards = [...document.querySelectorAll('.conc-card')];
            const canvas = document.getElementById('dwg-canvas');
            // 采样画布像素，判断是否真的画了东西
            let painted = 0;
            if (canvas) {
                const ctx = canvas.getContext('2d');
                const data = ctx.getImageData(0, 0, canvas.width, canvas.height).data;
                for (let i = 0; i < data.length; i += 4) {
                    if (data[i] > 40 || data[i + 1] > 40 || data[i + 2] > 40) painted++;
                }
            }
            return {
                cards: cards.length,
                firstCardTitle: cards[0] ? cards[0].querySelector('.conc-head') ? cards[0].querySelector('.conc-head').textContent.trim() : '' : '',
                emptyCanvas: !!document.querySelector('.canvas-empty'),
                paintedSamples: painted,
                legend: !!document.querySelector('.canvas-legend')
            };
        })()`);
        log(state2.cards > 0, '语义结论卡片渲染（' + state2.cards + ' 条）');
        log(state2.cards > 0 && state2.firstCardTitle.length > 0, '结论标题可读：' + state2.firstCardTitle.slice(0, 40));
        log(!state2.emptyCanvas, '画布空态已消失（已进入图纸视图）');
        log(state2.paintedSamples > 500, '画布已绘制图元像素（采样命中 ' + state2.paintedSamples + '）');
        log(state2.legend, '图例已显示');

        // ---------- 断言 3：点击结论 -> 溯源高亮 ----------
        console.log('\n[3] 点击结论验证溯源高亮');
        await cdp.eval(`(() => {
            const card = document.querySelector('.conc-card');
            if (card) card.click();
            return true;
        })()`);
        await sleep(1500);
        const state3 = await cdp.eval(`(() => ({
            selected: document.querySelectorAll('.conc-card.selected').length,
            painted: (() => {
                const canvas = document.getElementById('dwg-canvas');
                if (!canvas) return 0;
                const ctx = canvas.getContext('2d');
                const data = ctx.getImageData(0, 0, canvas.width, canvas.height).data;
                let n = 0;
                // 检测琥珀色高亮（#f0b429 附近）
                for (let i = 0; i < data.length; i += 16) {
                    if (data[i] > 180 && data[i + 1] > 120 && data[i + 1] < 220 && data[i + 2] < 120) n++;
                }
                return n;
            })()
        }))()`);
        log(state3.selected === 1, '选中卡片高亮生效');
        log(state3.painted > 50, '图纸上出现溯源高亮框（琥珀色像素 ' + state3.painted + '）');

        // ---------- 断言 4：BOM / 复核 / 问答页签 ----------
        console.log('\n[4] 切换面板');
        for (const [idx, name] of [[2, 'BOM'], [1, '复核'], [3, '问答']]) {
            await cdp.eval(`(() => { document.querySelectorAll('.tab')[${idx}].click(); return true; })()`);
            await sleep(700);
            const info = await cdp.eval(`(() => ({
                active: (document.querySelector('.tab.active') || {}).textContent || '',
                rows: document.querySelectorAll('.el-table__row').length,
                pending: document.querySelectorAll('.conc-card.pending').length,
                qa: !!(document.querySelector('.qa-input-area') && document.querySelector('.qa-result-area')),
                qaScrollableStyle: (() => {
                    const a = document.querySelector('.qa-result-area');
                    if (!a) return 'none';
                    const cs = getComputedStyle(a);
                    return cs.overflowY + '/minH=' + cs.minHeight;
                })()
            }))()`);
            if (name === 'BOM') log(info.rows > 0 || info.active.includes('BOM'), 'BOM 面板可用（' + info.rows + ' 行）');
            if (name === '复核') log(info.active.includes('复核'), '复核面板可用（待复核 ' + info.pending + ' 条）');
            if (name === '问答') { log(info.qa, '问答面板可用（输入区 + 独立滚动结果区）'); log(info.qaScrollableStyle.includes('auto'), '问答结果区可滚动（overflow-y=' + info.qaScrollableStyle + '）'); }
        }

        // ---------- 截图 ----------
        await cdp.eval(`(() => { document.querySelectorAll('.tab')[0].click(); return true; })()`);
        await sleep(600);
        const shot = await cdp.send('Page.captureScreenshot', { format: 'png' });
        const shotPath = path.join(OUT_DIR, 'ui-workspace.png');
        fs.writeFileSync(shotPath, Buffer.from(shot.data, 'base64'));
        console.log('\n截图已保存: ' + shotPath);

        // ---------- 页面错误汇总 ----------
        console.log('\n[5] 控制台错误');
        if (pageErrors.length === 0) {
            log(true, '无 JS 运行时错误');
        } else {
            pageErrors.slice(0, 5).forEach((e) => console.log('  [WARN] ' + e.slice(0, 200)));
            log(false, '存在 ' + pageErrors.length + ' 条页面错误');
        }

        const passed = results.filter((r) => r.ok).length;
        const failed = results.filter((r) => !r.ok).length;
        console.log('\n========================================');
        console.log('  验证结果: ' + passed + ' 通过, ' + failed + ' 失败');
        console.log('========================================');
        ws.close();
        process.exitCode = failed > 0 ? 1 : 0;
    } catch (e) {
        console.error('\n验证异常: ' + e.message);
        process.exitCode = 1;
    } finally {
        edge.kill();
        await sleep(500);
        fs.rmSync(PROFILE, { recursive: true, force: true });
    }
}

main();


