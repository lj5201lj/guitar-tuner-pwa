// Publish the exact same offline policies as the native APK, without external scripts or tracking.
import { readFile, writeFile } from 'node:fs/promises'
import { fileURLToPath } from 'node:url'
const root = new URL('../', import.meta.url)
const raw = 'android-wrapper/app/src/main/res/'
const escape = (value) => value.replaceAll('&', '&amp;').replaceAll('<', '&lt;').replaceAll('>', '&gt;')
const zh = await readFile(new URL(`${raw}raw-zh/privacy_policy.txt`, root), 'utf8')
const en = await readFile(new URL(`${raw}raw/privacy_policy.txt`, root), 'utf8')
const html = `<!doctype html>
<html lang="zh-CN"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
<meta name="referrer" content="no-referrer"><title>弦准 / Xianzhun · 隐私政策 / Privacy policy</title>
<style>body{max-width:780px;margin:auto;padding:24px;font:16px/1.8 system-ui,sans-serif;background:#10120f;color:#f4f6ee}a{color:#efff9d}h1{font-size:24px}pre{white-space:pre-wrap;overflow-wrap:anywhere;font:inherit}summary{font-size:21px;cursor:pointer;padding:16px 0}footer{border-top:1px solid #555;padding-top:20px}</style></head>
<body><h1>弦准 / Xianzhun</h1><p>原生 Android 应用隐私政策 / Native Android app privacy policy</p>
<nav><a href="#zh">简体中文</a> · <a href="#en">English</a></nav>
<section id="zh" lang="zh-CN"><h2>简体中文</h2><pre>${escape(zh)}</pre></section>
<section id="en" lang="en"><h2>English</h2><pre>${escape(en)}</pre></section>
<footer><a href="https://github.com/lj5201lj/guitar-tuner-pwa/issues" rel="noopener noreferrer">公开问题反馈 / Public feedback</a><p>此页面无统计、Cookie、外部字体或第三方脚本。托管服务自身的网络访问记录由托管方管理。<br>This page embeds no tracking, cookies, third-party fonts or scripts. Hosting providers manage their own access records.</p></footer></body></html>`
const target = new URL('dist/privacy.html', root)
await writeFile(target, html, 'utf8')
console.log(`Published bilingual native privacy policy: ${fileURLToPath(target)}`)
