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

## Android 2.2.0：语言选择

- 点击顶部“文/A”，选择“跟随系统 / 简体中文 / English”；选择保存于本机。
- 调音、节拍器、参考音、权限提示、自定义调弦与无障碍名称使用统一的 Android 字符串资源。未提供翻译的系统语言回退到英语。
- Android 13+ 使用 `LocaleManager`，支持系统“应用语言”入口；Android 6–12 使用配置上下文并在切换时重建 Activity。
- 切换会停止录音和播放，保留当前工具、调弦方案和节拍器设置；不自动重开麦克风。
- APK 与未来 AAB 都包含完整双语资源，离线可切换。
- 同时修正深色自定义调弦面板中弦号、八度和频率的对比度。

## Android 2.3.0：识别自检与发布基础

- 顶部“⋮”→“麦克风自检”：6 秒检查输入是否有样本、有声音、能否得到稳定音高，并提示系统静音/屏蔽、轻声和失真。首次录音先说明用途；永久拒绝后可直达系统权限设置。
- 较弱声音经 YIN 周期性复核后再判断，不再直接由旧固定门限丢弃；停顿重置旧平滑，换弦要求一致的新音高。强二次/三次泛音增加倍周期复核，局部多点插值降低噪声下的偏差。
- 大音名显示实际检测音，目标音、弦号与频率单列；超出 ±50 cents 明示量程，超过 100 cents 提醒先核对弦号和八度，不再提示盲目拧紧。稳定准确 650 ms 后提供一次可关闭的系统触感，旧读数灰显且不确认。
- “⋮”→“关于与隐私”：离线中英政策、版本、吉他采样 CC BY 3.0 署名、调音帮助、公开反馈和可选复制问题模板。无 INTERNET/VIBRATE 权限，不自动发送信息。
- 政策源为 Android `res/raw/` 与 `res/raw-zh/`；`npm run build` 同时生成 `dist/privacy.html` 供公开查看。政策明确仅适用于原生 APK，而非原 PWA。

质量和上架待办见 `android-wrapper/MARKET-READINESS.md`，真机验收表见 `android-wrapper/qa/REAL-DEVICE-CHECKLIST.md`。软件改动不表示应用宝已审核通过；真实手机拨弦和发布主体材料仍需单独验收。维护者未提供主体和邮箱，不虚构这些信息，先保留项目账号及公开反馈入口。

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
