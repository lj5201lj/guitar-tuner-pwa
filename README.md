# 弦准 · 吉他调音器 PWA

一个无需后端的移动端吉他调音器，使用 React、TypeScript、Web Audio API 和 YIN 算法实现。

## Android 原生版

`android-wrapper/` 从 2.0.0 起是独立的 Kotlin 原生 Android 应用，不是网站封装：

- UI 和麦克风采集均由 Android 原生 API 实现。
- 调音使用 `AudioRecord` 读取 PCM，并通过 YIN 算法检测音高。
- 页面、算法与吉他参考音均打入 APK，运行时不访问 GitHub Pages，可完全离线使用。
- Android 6.0 及以上可安装；首次使用需要授予麦克风权限。

2.1.0 将调音器和另一个项目的节拍器合并在同一个原生 App：

- 顶部切换“调音器 / 节拍器”，切页停止上一工具的录音或播放，退到后台也自动停止。
- 点弦播放真实吉他采样，1 秒后自动渐弱结束，无需手动停止。
- 参考音播放时保留麦克风连接但暂停分析；结束后等待 400 ms 余音、80 ms 采样排空，再重置滤波、YIN 窗口与平滑历史。旧 UI 更新以代次标识作废。
- 节拍器复用独立节拍器项目的 `BeatClock` / `MetronomeEngine` 音频采样帧调度，支持 30–300 BPM、点按测速、2/4、3/4、4/4、6/8（3+3）、清脆/木鱼/电子音色和音量。设置保存于本机。
- 6/8 的 BPM 按附点四分音符计，第 1、4 个八分音符提示音加重音。
- 包名、签名和自定义调弦存储沿用 2.0.0，可覆盖安装；独立节拍器的设置不会跨应用自动导入。

Android 构建使用 JDK 17、SDK 37.0、AGP 9.4.0（内置 Kotlin，编译器固定为 2.4.10）、Gradle 9.6.0，目标 SDK 仍为 36。配置 `JAVA_HOME`、`ANDROID_HOME` 和原发布密钥对应的 `ANDROID_KEYSTORE_*` 环境变量后执行：

```bash
gradle -p android-wrapper :app:testDebugUnitTest :app:lintRelease :app:assembleRelease
```

APK 在 `android-wrapper/app/build/outputs/apk/release/app-release.apk`，GitHub Actions 同时提供已签名构建。参考音隔离采用播放期间屏蔽和恢复前清空采样，不声称对任意房间、外置扬声器或长蓝牙延迟完成声学回声消除。

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
