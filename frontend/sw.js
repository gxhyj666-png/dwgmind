/**
 * DwgMind Service Worker
 *
 * 策略：
 *   - 页面导航（HTML）：network-first，离线回退缓存 —— 保证代码更新即时生效
 *   - 静态资源（app.js / vendor / icons）：stale-while-revalidate —— 快速启动
 *   - API 请求（/api/）：完全不拦截 —— 数据永远取实时
 *
 * 注意：Service Worker 只在安全上下文（HTTPS 或 localhost）生效。
 * 通过局域网 http://192.168.x.x 访问时浏览器不会注册 SW，
 * 但 manifest 仍然可用（可「添加到主屏幕」），只是没有离线缓存能力。
 */
const VERSION = 'dwgmind-v1';
const SHELL_CACHE = VERSION + '-shell';
const ASSET_CACHE = VERSION + '-assets';

const SHELL = ['/', '/index.html', '/manifest.webmanifest', '/icons/icon-192.png'];

self.addEventListener('install', (event) => {
    event.waitUntil(
        caches.open(SHELL_CACHE).then((cache) => cache.addAll(SHELL)).then(() => self.skipWaiting())
    );
});

self.addEventListener('activate', (event) => {
    event.waitUntil(
        caches.keys().then((keys) =>
            Promise.all(
                keys.filter((k) => !k.startsWith(VERSION)).map((k) => caches.delete(k))
            )
        ).then(() => self.clients.claim())
    );
});

self.addEventListener('fetch', (event) => {
    const req = event.request;
    if (req.method !== 'GET') return;

    const url = new URL(req.url);

    // 跨域 API（后端 8080）：不缓存，交给浏览器直连
    if (url.pathname.startsWith('/api/')) return;

    // 页面导航：network-first
    if (req.mode === 'navigate') {
        event.respondWith(
            fetch(req)
                .then((resp) => {
                    const copy = resp.clone();
                    caches.open(SHELL_CACHE).then((c) => c.put(req, copy));
                    return resp;
                })
                .catch(() => caches.match(req).then((r) => r || caches.match('/index.html')))
        );
        return;
    }

    // 静态资源：stale-while-revalidate
    event.respondWith(
        caches.match(req).then((cached) => {
            const network = fetch(req)
                .then((resp) => {
                    if (resp && resp.status === 200) {
                        const copy = resp.clone();
                        caches.open(ASSET_CACHE).then((c) => c.put(req, copy));
                    }
                    return resp;
                })
                .catch(() => cached);
            return cached || network;
        })
    );
});
