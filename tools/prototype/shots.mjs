#!/usr/bin/env node
/**
 * SAD-319 screenshot harness — renders every prototype screen in both themes
 * plus each sheet, so a change can be eyeballed as a set rather than one page
 * at a time. Output is git-ignored by default.
 *
 *   node tools/prototype/shots.mjs                 # → <repo>/.prototype-shots/
 *   node tools/prototype/shots.mjs --out /tmp/x    # custom dir
 *   node tools/prototype/shots.mjs --screen home   # one screen (repeatable)
 *   node tools/prototype/shots.mjs --sheets        # also capture each sheet
 *
 * The viewport is the prototype's own phone content box (392×852 CSS px), which
 * is the px→dp contract documented in docs/03-ui-design-system.md.
 */
import http from 'node:http'
import fs from 'node:fs'
import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { createRequire } from 'node:module'

const require = createRequire(import.meta.url)
const HERE = path.dirname(fileURLToPath(import.meta.url))
const ROOT = path.resolve(HERE, '../..')
const PROTO = path.join(ROOT, 'Reference/prototype')

function loadPlaywright () {
  for (const c of [process.env.PLAYWRIGHT_MODULE, 'playwright', '/opt/playwright/node_modules/playwright']) {
    if (!c) continue
    try { return require(c) } catch { /* next */ }
  }
  throw new Error('playwright not found — set PLAYWRIGHT_MODULE')
}
const { chromium } = loadPlaywright()

const arg = (name, fallback) => {
  const i = process.argv.indexOf(name)
  return i === -1 ? fallback : (process.argv[i + 1] ?? true)
}
const OUT = path.resolve(arg('--out', path.join(ROOT, '.prototype-shots')))
const ONLY = process.argv.reduce((acc, a, i, arr) => (a === '--screen' ? acc.concat(arr[i + 1]) : acc), [])
const WITH_SHEETS = process.argv.includes('--sheets')

const MIME = { '.html': 'text/html', '.css': 'text/css', '.js': 'text/javascript', '.png': 'image/png' }
const ORDER = (() => {
  const js = fs.readFileSync(path.join(PROTO, 'js/app.js'), 'utf8')
  const m = js.match(/const ORDER = \[([^\]]+)\]/)
  return [...m[1].matchAll(/'([a-z0-9]+)'/g)].map(x => x[1])
})()

function serve () {
  const server = http.createServer((req, res) => {
    const rel = decodeURIComponent(req.url.split('?')[0]).replace(/^\/+/, '') || 'index.html'
    const file = path.join(PROTO, rel)
    if (!file.startsWith(PROTO) || !fs.existsSync(file) || fs.statSync(file).isDirectory()) {
      res.writeHead(404); res.end(); return
    }
    res.writeHead(200, { 'content-type': MIME[path.extname(file)] || 'application/octet-stream' })
    fs.createReadStream(file).pipe(res)
  })
  return new Promise(r => server.listen(0, '127.0.0.1', () => r(server)))
}

async function main () {
  fs.mkdirSync(OUT, { recursive: true })
  const server = await serve()
  const port = server.address().port
  const browser = await chromium.launch()
  const shots = []

  for (const theme of ['light', 'dark']) {
    const page = await browser.newPage({
      viewport: { width: 392, height: 852 },
      deviceScaleFactor: 2,
      colorScheme: theme,
    })
    await page.addInitScript(t => localStorage.setItem('safeme_theme', t === 'dark' ? 'Dark' : 'Light'),
      theme)
    await page.goto(`http://127.0.0.1:${port}/index.html`, { waitUntil: 'load' })
    // strip the desktop harness so each shot is exactly the 392×852 content box
    await page.addStyleTag({ content: `
      .ptb,.hint{display:none!important}
      .stage{left:0!important;padding:0!important;display:block!important}
      .phone{width:392px!important;height:852px!important;min-height:0!important;
             border-radius:0!important;box-shadow:none!important}
      .notch{display:none!important}
    ` })

    for (const id of (ONLY.length ? ONLY : ORDER)) {
      await page.evaluate(s => window.nav(s), id)
      await page.waitForTimeout(120)
      await page.evaluate(s => { document.getElementById('sc-' + s).scrollTop = 0 }, id)
      const file = path.join(OUT, `${id}__${theme}.png`)
      await page.screenshot({ path: file, clip: { x: 0, y: 0, width: 392, height: 852 } })
      shots.push(file)
    }

    if (WITH_SHEETS) {
      const sheets = await page.evaluate(() => Array.from(document.querySelectorAll('.sheet')).map(s => s.id))
      for (const id of sheets) {
        await page.evaluate(s => {
          document.getElementById('scrim').classList.add('show')
          document.getElementById(s).classList.add('show')
        }, id)
        await page.waitForTimeout(120)
        const file = path.join(OUT, `${id}__${theme}.png`)
        await page.screenshot({ path: file, clip: { x: 0, y: 0, width: 392, height: 852 } })
        shots.push(file)
      }
    }
    await page.close()
  }

  await browser.close()
  server.close()

  const index = path.join(OUT, 'index.html')
  fs.writeFileSync(index, `<!doctype html><meta charset="utf-8"><title>SafeMe prototype shots</title>
<style>body{font:13px system-ui;background:#faf7f3;margin:24px}h2{margin:24px 0 8px}
img{width:196px;border:1px solid #eae3db;border-radius:10px;margin:0 8px 8px 0;background:#fff}</style>
<h1>SafeMe prototype — ${shots.length} shots</h1>
${[['light', 'Light'], ['dark', 'Dark']].map(([k, label]) => `<h2>${label}</h2>` +
  shots.filter(f => f.includes(`__${k}`)).map(f => `<img src="${path.basename(f)}" title="${path.basename(f)}">`).join('')).join('')}
`)
  console.log(`wrote ${shots.length} screenshots → ${OUT}`)
  console.log(`contact sheet → ${index}`)
}

main().catch(e => { console.error(e); process.exit(1) })
