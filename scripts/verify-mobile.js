/**
 * DwgMind 移动端 / PWA 验证（无头 Edge + CDP，iPhone 尺寸仿真）
 */
const WebSocket = require('ws');
const { spawn } = require('child_process');
const fs = require('fs');
const http = require('http');

const EDGE = 'C:\\Program Files (x86)\\Microsoft\\Edge\\Application\\msedge.exe';
const PROFILE = 'C:\\Users\\86180\\Desktop\\dwgmind\\.edge-mobile';
const PORT = 9677;
const BASE = process.argv[2] || 'http://127.0.0.1:5173';
const DRAWING_ID = process.argv[3] || '34';
const OUT = 'C:\\Users\\86180\\Desktop\\dwgmind\\docs';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const results = [];
const log = (ok, msg) => {
  results.push({ ok, msg });
  console.log((ok ? '  [PASS] ' : '  [FAIL] ') + msg);
};

(async () => {
  fs.rmSync(PROFILE, { recursive: true, force: true });
  const edge = spawn(EDGE, [
    '--headless=new', '--disable-gpu', '--no-sandbox',
    '--remote-debugging-port=' + PORT, '--user-data-dir=' + PROFILE,
    '--window-size=390,844', 'about:blank'
  ], { stdio: 'ignore' });

  let target = null;
  for (let i = 0; i < 40; i++) {
    try {
      const list = await new Promise((res, rej) => {
        http.get({ host: '127.0.0.1', port: PORT, path: '/json/list' }, (r) => {
          let b = '';
          r.on('data', (c) => (b += c));
          r.on('end', () => res(JSON.parse(b)));
        }).on('error', rej);
      });
      target = list.find((t) => t.type === 'page');
      if (target) break;
    } catch (e) { /* retry */ }
    await sleep(250);
  }
  if (!target) throw new Error('无法连接 Edge 调试端口');

  const ws = new WebSocket(target.webSocketDebuggerUrl);
  await new Promise((res, rej) => { ws.once('open', res); ws.once('error', rej); });

  let id = 0;
  const pending = new Map();
  const consoleErrors = [];
  ws.on('message', (raw) => {
    const m = JSON.parse(raw.toString());
    if (m.id && pending.has(m.id)) {
      const p = pending.get(m.id);
      pending.delete(m.id);
      p(m.result);
      return;
    }
    if (m.method === 'Runtime.exceptionThrown') {
      consoleErrors.push(m.params.exceptionDetails.text || 'exception');
    }
    if (m.method === 'Log.entryAdded' && m.params.entry.level === 'error') {
      const t = m.params.entry.text || '';
      if (!/favicon/i.test(t)) consoleErrors.push(t);
    }
  });

  const send = (method, params = {}) => new Promise((res) => {
    const i = ++id;
    pending.set(i, res);
    ws.send(JSON.stringify({ id: i, method, params }));
  });

  const ev = async (expr) => {
    const r = await send('Runtime.evaluate', {
      expression: expr, returnByValue: true, awaitPromise: true
    });
    if (r && r.exceptionDetails) {
      return { __error: r.exceptionDetails.text + ' ' + ((r.exceptionDetails.exception || {}).description || '') };
    }
    return r.result ? r.result.value : undefined;
  };

  await send('Page.enable');
  await send('Runtime.enable');
  await send('Log.enable');
  await send('Network.enable');
  await send('Network.setCacheDisabled', { cacheDisabled: true });
  await send('Emulation.setDeviceMetricsOverride', {
    width: 390, height: 844, deviceScaleFactor: 3, mobile: true
  });
  await send('Emulation.setTouchEmulationEnabled', { enabled: true, maxTouchPoints: 5 });

  console.log('\n[0] 打开页面（390x844 手机视口）');
  await send('Page.navigate', { url: BASE + '/?drawing=' + DRAWING_ID });
  await sleep(11000);

  console.log('\n[1] PWA 资源可达性');
  const assetSpecs = [
    ['/manifest.webmanifest', 'manifest'],
    ['/icons/icon-192.png', 'image'],
    ['/icons/icon-512.png', 'image'],
    ['/icons/icon-maskable-512.png', 'image'],
    ['/icons/apple-touch-icon.png', 'image'],
    ['/sw.js', 'javascript']
  ];
  for (const spec of assetSpecs) {
    const path = spec[0];
    const expect = spec[1];
    const r = await ev(
      "(async () => { const r = await fetch('" + path + "');" +
      " const t = r.headers.get('content-type'); const b = await r.blob();" +
      " return { status: r.status, type: t, len: b.size }; })()"
    );
    const ok = r && r.status === 200 && (r.type || '').indexOf(expect) >= 0;
    log(ok, path + ' -> ' + (r ? r.status + ' ' + r.type + ' ' + r.len + 'B' : '无响应'));
  }

  const manifest = await ev(
    "(async () => { const r = await fetch('/manifest.webmanifest'); return await r.json(); })()"
  );
  const manifestOk = manifest && manifest.display === 'standalone' &&
    Array.isArray(manifest.icons) && manifest.icons.length >= 3;
  log(manifestOk, 'manifest 有效：display=' + (manifest && manifest.display) +
    '，icons ' + (manifest && manifest.icons ? manifest.icons.length : 0) + ' 个');

  console.log('\n[2] 移动端布局');
  const layout = await ev(
    "(() => {" +
    " const vis = (s) => { const el = document.querySelector(s); if (!el) return null;" +
    "   const cs = getComputedStyle(el); const r = el.getBoundingClientRect();" +
    "   return { display: cs.display, w: Math.round(r.width), h: Math.round(r.height), left: Math.round(r.left), top: Math.round(r.top) }; };" +
    " const c = document.getElementById('dwg-canvas');" +
    " return { mobileNav: vis('.mobile-nav'), sidebar: vis('.sidebar'), inspector: vis('.inspector')," +
    "   canvas: { attr: c.width + 'x' + c.height, css: c.style.width + '/' + c.style.height }," +
    "   vp: { w: window.innerWidth, h: window.innerHeight, mobile: window.matchMedia('(max-width: 900px)').matches } }; })()"
  );
  log(layout.vp.mobile, '视口 ' + layout.vp.w + 'x' + layout.vp.h + '，移动端断点命中');
  log(layout.mobileNav && layout.mobileNav.display === 'flex', '底部导航已显示');
  log(layout.sidebar && layout.sidebar.left < 0, '左侧抽屉默认收起（left=' + (layout.sidebar || {}).left + 'px）');
  log(layout.inspector && layout.inspector.top >= 840, '语义面板默认收起（top=' + (layout.inspector || {}).top + 'px）');
  log(layout.canvas && layout.canvas.css !== 'none', '画布已初始化尺寸 ' + layout.canvas.css);

  const painted = await ev(
    "(() => { const c = document.getElementById('dwg-canvas'); const ctx = c.getContext('2d');" +
    " const d = ctx.getImageData(0, 0, c.width, c.height).data; let n = 0;" +
    " for (let i = 0; i < d.length; i += 4) { if (d[i] > 60 || d[i+1] > 60 || d[i+2] > 60) n++; } return n; })()"
  );
  log(painted > 500, '画布已绘制图元（非背景像素 ' + painted + '）');

  let shot = await send('Page.captureScreenshot', { format: 'png' });
  fs.writeFileSync(OUT + '\\mobile-canvas.png', Buffer.from(shot.data, 'base64'));

  console.log('\n[3] 抽屉交互');
  await ev("(() => { const b = document.querySelectorAll('.mobile-nav button')[0]; b.click(); return true; })()");
  await sleep(700);
  const drawer = await ev(
    "(() => { const s = document.querySelector('.sidebar'); const r = s.getBoundingClientRect();" +
    " return { left: Math.round(r.left), width: Math.round(r.width)," +
    "   open: s.classList.contains('open'), backdrop: !!document.querySelector('.mobile-backdrop') }; })()"
  );
  log(drawer.open && drawer.left >= 0, '抽屉已展开（left=' + drawer.left + 'px, width=' + drawer.width + 'px）');
  log(drawer.backdrop, '遮罩层已出现');
  shot = await send('Page.captureScreenshot', { format: 'png' });
  fs.writeFileSync(OUT + '\\mobile-drawer.png', Buffer.from(shot.data, 'base64'));

  await ev("(() => { document.querySelector('.mobile-backdrop').click(); return true; })()");
  await sleep(600);
  const stillOpen = await ev("(() => document.querySelector('.sidebar').classList.contains('open'))()");
  log(!stillOpen, '点击遮罩可关闭抽屉');

  console.log('\n[4] 语义面板交互');
  await ev("(() => { const b = document.querySelectorAll('.mobile-nav button')[2]; b.click(); return true; })()");
  await sleep(900);
  const panel = await ev(
    "(() => { const i = document.querySelector('.inspector'); const r = i.getBoundingClientRect();" +
    " return { open: i.classList.contains('open'), top: Math.round(r.top), h: Math.round(r.height)," +
    "   tabs: document.querySelectorAll('.inspector .tab').length," +
    "   cards: document.querySelectorAll('.conc-card').length }; })()"
  );
  log(panel.open && panel.top < 844, '语义面板已上滑（top=' + panel.top + 'px, 高=' + panel.h + 'px）');
  log(panel.tabs === 4 && panel.cards > 0, '面板内容正常（4 个页签, ' + panel.cards + ' 条结论）');
  shot = await send('Page.captureScreenshot', { format: 'png' });
  fs.writeFileSync(OUT + '\\mobile-panel.png', Buffer.from(shot.data, 'base64'));

  await ev("(() => { document.querySelector('.mobile-backdrop').click(); return true; })()");
  await sleep(700);

  console.log('\n[5] 触摸手势');
  const panned = await ev(
    "(() => { const c = document.getElementById('dwg-canvas');" +
    " const mk = (type, x, y) => { const t = new Touch({ identifier: 1, target: c, clientX: x, clientY: y, pageX: x, pageY: y });" +
    "   return new TouchEvent(type, { touches: [t], targetTouches: [t], changedTouches: [t], bubbles: true, cancelable: true }); };" +
    " const s0 = window.__dwgmind.state();" +
    " c.dispatchEvent(mk('touchstart', 200, 400));" +
    " c.dispatchEvent(mk('touchmove', 245, 465));" +
    " c.dispatchEvent(mk('touchend', 245, 465));" +
    " const s1 = window.__dwgmind.state();" +
    " return { dx: Math.round(s1.offsetX - s0.offsetX), dy: Math.round(s1.offsetY - s0.offsetY) }; })()"
  );
  log(panned && (Math.abs(panned.dx) > 5 || Math.abs(panned.dy) > 5),
    '单指平移生效（dx=' + (panned || {}).dx + ', dy=' + (panned || {}).dy + '）');

  const pinched = await ev(
    "(() => { const c = document.getElementById('dwg-canvas');" +
    " const mk = (type, x1, y1, x2, y2) => {" +
    "   const t1 = new Touch({ identifier: 1, target: c, clientX: x1, clientY: y1, pageX: x1, pageY: y1 });" +
    "   const t2 = new Touch({ identifier: 2, target: c, clientX: x2, clientY: y2, pageX: x2, pageY: y2 });" +
    "   const ts = [t1, t2];" +
    "   return new TouchEvent(type, { touches: ts, targetTouches: ts, changedTouches: ts, bubbles: true, cancelable: true }); };" +
    " const s0 = window.__dwgmind.state();" +
    " c.dispatchEvent(mk('touchstart', 150, 400, 250, 400));" +
    " c.dispatchEvent(mk('touchmove', 100, 400, 300, 400));" +
    " c.dispatchEvent(mk('touchend', 100, 400, 300, 400));" +
    " const s1 = window.__dwgmind.state();" +
    " return { s0: +s0.scale.toFixed(3), s1: +s1.scale.toFixed(3), ratio: +(s1.scale / s0.scale).toFixed(2) }; })()"
  );
  log(pinched && pinched.ratio > 1.3,
    '双指捏合缩放生效（' + (pinched || {}).s0 + ' -> ' + (pinched || {}).s1 + '，x' + (pinched || {}).ratio + '）');

  console.log('\n[6] 控制台');
  if (consoleErrors.length === 0) {
    log(true, '无 JS 运行时错误');
  } else {
    consoleErrors.slice(0, 4).forEach((e) => console.log('    [WARN] ' + String(e).slice(0, 180)));
    log(false, '存在 ' + consoleErrors.length + ' 条页面错误');
  }

  const passed = results.filter((r) => r.ok).length;
  const failed = results.filter((r) => !r.ok).length;
  console.log('\n========================================');
  console.log('  移动端验证: ' + passed + ' 通过, ' + failed + ' 失败');
  console.log('========================================');
  console.log('截图: docs/mobile-canvas.png / mobile-drawer.png / mobile-panel.png');

  ws.close();
  edge.kill();
  process.exit(failed > 0 ? 1 : 0);
})();
