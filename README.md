# 弦准 · 吉他调音器 PWA

一个无需后端的移动端吉他调音器，使用 React、TypeScript、Web Audio API 和 YIN 算法实现。

## Android 原生版

`android-wrapper/` 从 2.0.0 起是独立的 Kotlin 原生 Android 应用，不是网站封装：

- UI 和麦克风采集均由 Android 原生 API 实现。
- 调音使用 `AudioRecord` 读取 PCM，并通过 YIN 算法检测音高。
- 页面、算法与吉他参考音均打入 APK，运行时不访问 GitHub Pages，可完全离线使用。
- Android 6.0 及以上可安装；首次使用需要授予麦克风权限。

网页 PWA 与 Android 原生版是两个独立前端，PWA 仍保留在 `src/` 和 `public/`。

## 本地运行

```bash
npm install
npm run dev
```

浏览器打开 `http://localhost:5173`。首次点击“开启麦克风”时允许麦克风权限。

## 同一 Wi-Fi 下用手机访问

```bash
npm run dev -- --host 0.0.0.0
```

终端会显示 `Network` 地址，例如 `http://192.168.1.20:5173`。电脑和手机需处于同一局域网，且防火墙需允许 Node.js/Vite 访问专用网络。

> 手机浏览器通常只允许 HTTPS 页面读取麦克风。局域网 HTTP 地址可以查看界面，但要实际调音，请使用下方的 HTTPS 部署地址或可信的本地 HTTPS 方案。

## 检查与构建

```bash
npm run test:pitch
npm run check
npm run build
npm run preview
```

构建产物位于 `dist/`，可部署到任何提供 HTTPS 的静态托管服务，例如 Cloudflare Pages、Vercel、Netlify 或 GitHub Pages。

## 添加到主屏幕

- Android Chrome：打开 HTTPS 地址，点击页面右上角“安装”，或在浏览器菜单中选择“安装应用”。
- iPhone Safari：点击底部“分享”按钮，再选择“添加到主屏幕”。
