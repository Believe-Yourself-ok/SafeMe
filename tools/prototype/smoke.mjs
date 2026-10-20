#!/usr/bin/env node
/**
 * SAD-319 runtime smoke test for the SafeMe HTML prototype.
 *
 * Drives the real page in Chromium (Playwright) instead of jsdom: the prototype
 * is CSS-transition + hash-router driven, and jsdom does not apply the layout
 * the assertions depend on. No npm install is required — Playwright is resolved
 * from the environment (PLAYWRIGHT_MODULE, the local node_modules, or the
 * sandbox copy).
 *
 *   node tools/prototype/smoke.mjs            # run all checks
 *   node tools/prototype/smoke.mjs --verbose  # also print passing checks
 *
 * Exit code 0 only when every check passes.
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
const VERBOSE = process.argv.includes('--verbose')

function loadPlaywright () {
  const candidates = [process.env.PLAYWRIGHT_MODULE, 'playwright', '/opt/playwright/node_modules/playwright']
  for (const c of candidates) {
    if (!c) continue
    try { return require(c) } catch { /* try next */ }
  }
  throw new Error('playwright not found — set PLAYWRIGHT_MODULE to the module path')
}
const { chromium } = loadPlaywright()

const MIME = { '.html': 'text/html', '.css': 'text/css', '.js': 'text/javascript', '.png': 'image/png' }

function serve () {
  const server = http.createServer((req, res) => {
    const rel = decodeURIComponent(req.url.split('?')[0]).replace(/^\/+/, '') || 'index.html'
    const file = path.join(PROTO, rel)
    if (!file.startsWith(PROTO) || !fs.existsSync(file) || fs.statSync(file).isDirectory()) {
      res.writeHead(404); res.end('not found'); return
    }
    res.writeHead(200, { 'content-type': MIME[path.extname(file)] || 'application/octet-stream' })
    fs.createReadStream(file).pipe(res)
  })
  return new Promise(resolve => server.listen(0, '127.0.0.1', () => resolve(server)))
}

const results = []
const check = (name, ok, detail = '') => results.push({ name, ok: !!ok, detail })

// single source of truth: the prototype's own ORDER registry
const ORDER = (() => {
  const js = fs.readFileSync(path.join(PROTO, 'js/app.js'), 'utf8')
  const m = js.match(/const ORDER = \[([^\]]+)\]/)
  if (!m) throw new Error('ORDER registry not found in app.js')
  return [...m[1].matchAll(/'([a-z0-9]+)'/g)].map(x => x[1])
})()

async function main () {
  const server = await serve()
  const port = server.address().port
  const browser = await chromium.launch()
  const page = await browser.newPage({ viewport: { width: 1400, height: 900 } })

  const consoleErrors = []
  page.on('console', m => { if (m.type() === 'error') consoleErrors.push(m.text()) })
  page.on('pageerror', e => consoleErrors.push(String(e)))

  await page.goto(`http://127.0.0.1:${port}/index.html`, { waitUntil: 'load' })
  check('page loads', await page.title() !== '')

  const api = await page.evaluate(() => ({
    nav: typeof window.nav, openSheet: typeof window.openSheet,
    closeSheets: typeof window.closeSheets, toast: typeof window.toast,
  }))
  check('prototype JS exposes its handler API', Object.values(api).every(t => t === 'function'), JSON.stringify(api))

  // ---- every screen renders, and only it is visible -----------------------
  const screens = ORDER
  let screenFails = []
  for (const id of screens) {
    await page.evaluate(s => window.nav(s), id)
    const state = await page.evaluate(s => {
      const el = document.getElementById('sc-' + s)
      if (!el) return { ok: false, reason: 'missing section' }
      const shown = Array.from(document.querySelectorAll('.screen.show')).map(n => n.id)
      return { ok: el.classList.contains('show') && shown.length === 1, shown }
    }, id)
    if (!state.ok) screenFails.push(`${id}: ${JSON.stringify(state)}`)
  }
  check(`${screens.length} screens navigate & isolate`, screenFails.length === 0, screenFails.join(' | '))

  // ---- bottom nav: 4 tabs, correct highlight ------------------------------
  const navFails = []
  for (const tab of ['home', 'block', 'schedule', 'profile']) {
    await page.evaluate(t => window.nav(t), tab)
    const ok = await page.evaluate(t => {
      const bar = document.getElementById('nav')
      const on = Array.from(bar.querySelectorAll('button.on')).map(b => b.dataset.nav)
      return bar.classList.contains('show') && on.length === 1 && on[0] === t
    }, tab)
    if (!ok) navFails.push(tab)
  }
  check('bottom nav shows/highlights on the 4 tab roots', navFails.length === 0, navFails.join(','))

  const navHidden = await page.evaluate(async () => {
    window.nav('keywords')
    return document.getElementById('nav').classList.contains('show')
  })
  check('bottom nav hides on pushed screens', navHidden === false)

  // ---- sheets open and close ---------------------------------------------
  const sheets = await page.evaluate(() => Array.from(document.querySelectorAll('.sheet')).map(s => s.id))
  const sheetFails = []
  for (const id of sheets) {
    const opened = await page.evaluate(s => {
      const el = document.getElementById(s)
      if (!el) return { ok: false, reason: 'missing' }
      el.classList.add('show')
      document.getElementById('scrim').classList.add('show')
      const isOpen = el.classList.contains('show')
      window.closeSheets()
      return { ok: isOpen && !el.classList.contains('show'), isOpen }
    }, id)
    if (!opened.ok) sheetFails.push(id)
  }
  check(`${sheets.length} sheets open & close`, sheetFails.length === 0, sheetFails.join(','))

  // ---- permission flow ----------------------------------------------------
  const perms = await page.evaluate(() => {
    window.nav('permissions')
    const pills = document.querySelectorAll('#sc-permissions [data-grant]').length
    return pills
  })
  check('permission screen renders a grant pill', perms >= 1, `pills=${perms}`)

  // ---- theme switch -------------------------------------------------------
  const themes = await page.evaluate(() => {
    const out = {}
    document.documentElement.setAttribute('data-theme', 'dark'); out.dark = getComputedStyle(document.body).backgroundColor
    document.documentElement.setAttribute('data-theme', 'light'); out.light = getComputedStyle(document.body).backgroundColor
    return out
  })
  check('dark theme applies a different background', themes.dark !== themes.light, JSON.stringify(themes))

  // ---- renderers do not throw --------------------------------------------
  const renderErr = await page.evaluate(() => {
    // real renderer names in app.js (grepped, not guessed)
    const fns = ['histRender', 'renderSocial', 'kwRender', 'qaRender', 'renderAppPicker',
                 'renderLock', 'renderTime', 'renderDotsId', 'vpnStatus', 'kwMgCard', 'schedCount']
    const missing = fns.filter(f => typeof window[f] !== 'function')
    try { fns.filter(f => typeof window[f] === 'function').forEach(f => window[f]()) } catch (e) { return String(e) }
    return missing.length ? 'missing: ' + missing.join(',') : ''
  })
  check('state renderers run without throwing', renderErr === '', renderErr)

  check('no console errors', consoleErrors.length === 0, consoleErrors.slice(0, 3).join(' | '))

  await browser.close()
  server.close()

  for (const r of results) {
    if (r.ok && !VERBOSE) continue
    console.log(`${r.ok ? 'PASS' : 'FAIL'}  ${r.name}${r.ok ? '' : `\n      → ${r.detail}`}`)
  }
  const failed = results.filter(r => !r.ok)
  console.log(`\nsmoke: ${results.length - failed.length}/${results.length} passed`)
  process.exit(failed.length ? 1 : 0)
}

main().catch(e => { console.error(e); process.exit(1) })
