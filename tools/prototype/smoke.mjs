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

  // ---- social blocking: master off dims rows, extras rows appear -------- #
  const social = await page.evaluate(async () => {
    window.nav('socialblocking')
    const read = () => ({
      rows: document.querySelectorAll('#launchList .launch-row').length,
      extras: document.querySelectorAll('#launchList .launch-row.extra').length,
      dim: [...document.querySelectorAll('#launchList .launch-row')].map(r => getComputedStyle(r).opacity),
      on: document.querySelectorAll('#launchList .launch-row .sw.on').length,
      sub: document.getElementById('socialMasterSub').textContent,
      master: document.getElementById('socialMasterPill').textContent.trim(),
      footnote: (document.body.textContent.match(/How it works/) || []).length
    })
    const before = read()
    window.togSocialMaster(document.getElementById('socialMaster'))
    await new Promise(r => setTimeout(r, 60))
    const off = read()
    window.togSocialMaster(document.getElementById('socialMaster'))
    await new Promise(r => setTimeout(r, 60))
    document.getElementById('socialMaster').classList.add('on')
    window.togLaunch(document.querySelector('#launchList .launch-row .sw'), 'TikTok')
    return { before, off, footnote: before.footnote }
  })
  check('social: master off dims every row to 0.45 and clears the switches',
    social.off.dim.every(o => Math.abs(parseFloat(o) - 0.45) < 0.02) && social.off.on === 0
    && social.off.master === 'Master OFF' && social.off.sub === 'Paused — tap to protect'
    && social.before.master === 'Master ON' && social.before.sub === 'Whole apps & tabs protected',
    JSON.stringify(social.off))

  // ---- delete account is a centred dialog, not a sheet ------------------ #
  const del = await page.evaluate(async () => {
    window.openDlg('dlgDelete')
    await new Promise(r => setTimeout(r, 60))
    const d = document.getElementById('dlgDelete')
    const shown = getComputedStyle(d).display !== 'none'
    const title = d.querySelector('.dlg-title').textContent
    window.closeDlg()
    await new Promise(r => setTimeout(r, 60))
    return { shown, title, closed: getComputedStyle(d).display === 'none',
             sheet: !!document.getElementById('sheetDelete') }
  })
  check('delete account opens a centred dialog with production copy',
    del.shown && del.closed && !del.sheet && del.title === 'Delete account?', JSON.stringify(del))

  // ---- backup progress + error states ---------------------------------- #
  // BackupScreen.kt: busy swaps the action label (backup_exporting/restoring) and
  // disables both cards; failures are toasts carrying the backup_err_* string.
  const backup = await page.evaluate(async () => {
    const read = () => {
      const e = document.getElementById('backupExportBtn'), r = document.getElementById('backupRestoreBtn')
      return { exp: e.textContent, res: r.textContent, expDisabled: e.disabled, resDisabled: r.disabled,
               toast: document.getElementById('toasts').textContent.trim(),
               box: !!document.getElementById('backupStatus') }
    }
    const out = {}
    window.setDemo('backup', 'exporting');   await new Promise(r => setTimeout(r, 40)); out.exporting = read()
    window.setDemo('backup', 'restoring');   await new Promise(r => setTimeout(r, 40)); out.restoring = read()
    document.getElementById('toasts').innerHTML = ''
    window.setDemo('backup', 'not_json');    await new Promise(r => setTimeout(r, 40)); out.notJson = read()
    document.getElementById('toasts').innerHTML = ''
    window.setDemo('backup', 'rollback');    await new Promise(r => setTimeout(r, 40)); out.rollback = read()
    document.getElementById('toasts').innerHTML = ''
    window.setDemo('backup', 'idle');        await new Promise(r => setTimeout(r, 40)); out.idle = read()
    return out
  })
  check('backup: busy swaps the action labels, errors toast backup_err_*',
    backup.exporting.exp === 'Creating backup…' && backup.exporting.expDisabled
    && backup.exporting.resDisabled && backup.exporting.res === 'Restore'
    && backup.restoring.res === 'Restoring…' && backup.restoring.exp === 'Export'
    && backup.notJson.toast === "This file isn't valid JSONC"
    && backup.rollback.toast.startsWith('Restore failed and couldn')
    && backup.idle.exp === 'Export' && !backup.idle.expDisabled && !backup.idle.box,
    JSON.stringify(backup))

  // ---- schedule states: populated / overnight / disabled / empty -------- #
  const sched = await page.evaluate(async () => {
    window.nav('schedule')
    const read = () => ({
      count: document.getElementById('schedHeroCount').textContent,
      pill: document.getElementById('schedHeroPill').textContent.trim(),
      sub: document.getElementById('schedHeroSub').textContent,
      times: [...document.querySelectorAll('#schedList .sched-time-txt')].map(t => t.textContent),
      apps: [...document.querySelectorAll('#schedList .sched-card')].map(c => c.querySelectorAll('.pill')[1].textContent),
      off: [...document.querySelectorAll('#schedList .sched-card')].filter(c => !c.querySelector('.sw').classList.contains('on')).length,
      // ScheduleScreen renders no list-level empty text; the hero sub owns that copy.
      cards: document.querySelectorAll('#schedList .sched-card').length,
      a11y: getComputedStyle(document.getElementById('schA11yWarn')).display !== 'none',
      exact: getComputedStyle(document.getElementById('schExactWarn')).display !== 'none',
      home: [document.getElementById('homeBlocked').textContent, document.getElementById('homeSchedules').textContent]
    })
    window.setDemo('lists', 'populated'); await new Promise(r => setTimeout(r, 60))
    const populated = read()
    window.setDemo('lists', 'empty'); await new Promise(r => setTimeout(r, 60))
    const empty = read()
    window.setDemo('lists', 'populated'); await new Promise(r => setTimeout(r, 60))
    // Banner predicates: shouldShowA11yWarning / shouldShowExactAlarmWarning.
    const flags = {}
    window.setDemo('a11y', 'on'); window.setDemo('exact', 'granted')
    await new Promise(r => setTimeout(r, 40)); flags.bothOk = read()
    window.setDemo('a11y', 'off'); window.setDemo('exact', 'not_granted')
    await new Promise(r => setTimeout(r, 40)); flags.bothMissing = read()
    window.setDemo('a11y', 'on')
    return { populated, empty, flags }
  })
  check('schedule: hero count/pill/boundary, (+1) overnight row, disabled card',
    sched.populated.count === '2 active schedules' && sched.populated.pill === 'On'
    && /^Next boundary · \w+ at (\w+ )?\d{2}:\d{2}$/.test(sched.populated.sub)
    && sched.populated.times.includes('23:00 – 06:00 (+1)')
    && sched.populated.apps.includes('All apps') && sched.populated.apps.includes('3 apps')
    && sched.populated.off === 1 && sched.populated.cards === 3
    && sched.populated.home[1] === '2',
    JSON.stringify(sched.populated))
  check('schedule: empty first-launch state clears cards + Home counters',
    sched.empty.count === '0 active schedules' && sched.empty.pill === 'Off'
    && sched.empty.sub === 'No schedules yet — create one' && sched.empty.cards === 0
    && !sched.empty.a11y && !sched.empty.exact                 // no enabled schedule, no nag
    && sched.empty.home[0] === '0' && sched.empty.home[1] === '0',
    JSON.stringify(sched.empty))
  check('schedule: warning banners follow their predicates (a11y + exact alarm)',
    sched.flags.bothOk.a11y === false && sched.flags.bothOk.exact === false
    && sched.flags.bothMissing.a11y === true && sched.flags.bothMissing.exact === true,
    JSON.stringify(sched.flags))

  // ---- block-screen Exclude Apps card: count-driven sub ------------------ #
  const blkExclude = await page.evaluate(async () => {
    window.nav('otherfeatures')
    const sub = () => document.getElementById('blkExcludeSub').textContent
    const before = sub()
    document.getElementById('blkExcludeSub').closest('.qa').click()
    await new Promise(r => setTimeout(r, 80))
    const sheet = document.getElementById('sheetBlkExclude')
    const opened = sheet.classList.contains('show')
      && sheet.querySelector('h3').textContent === 'Exclude apps'
      && sheet.querySelector('.s-sub').textContent
        === 'Excluded apps are never blocked by keyword, porn keyword, website, or image/video-search blocking.'
    document.querySelectorAll('#blkExcludeList .li')[0].click()
    await new Promise(r => setTimeout(r, 40))
    const counted = document.getElementById('selAllBlkExcludeBtn').textContent
    ;[...sheet.querySelectorAll('.btn-primary')].pop().click()
    await new Promise(r => setTimeout(r, 80))
    const one = sub()
    document.getElementById('blkExcludeSub').closest('.qa').click()
    await new Promise(r => setTimeout(r, 60))
    ;[...document.getElementById('sheetBlkExclude').querySelectorAll('.btn-secondary')][1].click()
    ;[...document.getElementById('sheetBlkExclude').querySelectorAll('.btn-primary')].pop().click()
    await new Promise(r => setTimeout(r, 80))
    return { before, opened, counted, one, cleared: sub() }
  })
  check('block: Exclude Apps card sub follows blk_exclude_count',
    blkExclude.before === 'Never blocked by keywords or porn blocking'
    && blkExclude.opened && blkExclude.counted === 'Select All (1)'
    && blkExclude.one === '1 app excluded'
    && blkExclude.cleared === 'Never blocked by keywords or porn blocking',
    JSON.stringify(blkExclude))

  // ---- DNS/VPN status matrix -------------------------------------------- #
  const vpnStates = await page.evaluate(async () => {
    const read = () => ({
      pill: document.getElementById('vpnPill').textContent.trim(),
      title: document.getElementById('vpnTitle').textContent,
      sub: document.getElementById('vpnSub').textContent,
      sw: document.getElementById('vpnToggle').classList.contains('on')
    })
    const out = {}
    for (const v of ['on', 'off', 'custom', 'system', 'schedule']) {
      window.setDemo('vpn', v)
      await new Promise(r => setTimeout(r, 30))
      out[v] = read()
    }
    window.setDemo('vpn', 'on')
    return out
  })
  check('vpn: on/off/presets bundle their status copy like vpnStatusSub()',
    vpnStates.on.sub === 'Cloudflare Family · 0 exempt' && vpnStates.on.pill === 'Active'
    && vpnStates.off.sub === 'Tap to re-enable protection' && vpnStates.off.pill === 'Off'
    && !vpnStates.off.sw
    && vpnStates.custom.sub.startsWith('Custom · 1.1.1.1 · ')
    && vpnStates.system.sub.startsWith('System-wide · ')
    && vpnStates.schedule.sub.endsWith('· schedule block active'),
    JSON.stringify(vpnStates))

  // ---- block overlay: countdown empties the arc then unlocks Close -------- #
  const blockGate = await page.evaluate(async () => {
    while (window.bsDwell > 3) window.dwellStep(-1)
    window.dwellStep(-1); window.dwellStep(-1)     // clamp proves 3 is the floor
    const preview = document.getElementById('bsPreviewCount').textContent
    window.openBlockov()
    await new Promise(r => setTimeout(r, 120))
    const fg = document.querySelector('#blockov .ring-lg .fg')
    const startOffset = parseFloat(fg.getAttribute('stroke-dashoffset'))
    const lockedAtStart = document.getElementById('boClose').classList.contains('locked')
    await new Promise(r => setTimeout(r, 3300))
    const endOffset = parseFloat(fg.getAttribute('stroke-dashoffset'))
    const readyAtEnd = document.getElementById('boClose').classList.contains('ready')
    const num = document.getElementById('boNum').textContent
    const dwellLabel = document.getElementById('bsDwellVal').textContent
    window.closeBlockov()
    return { preview, dwellLabel, startOffset, endOffset, lockedAtStart, readyAtEnd, num }
  })
  check('block overlay: dwell clamps at 3s, arc empties, Close unlocks at 0',
    blockGate.dwellLabel === '3s' && blockGate.preview === 'Closing in 3s.'
    && blockGate.lockedAtStart && blockGate.readyAtEnd
    && blockGate.startOffset === 0 && blockGate.endOffset === 326.7 && blockGate.num === '0',
    JSON.stringify(blockGate))

  // ---- app lock gate: wrong code -> backoff -> lockout countdown --------- #
  const lockGate = await page.evaluate(async () => {
    window.setDemo('lock', 'gate')
    await new Promise(r => setTimeout(r, 80))
    const open = document.getElementById('lockov').classList.contains('show')
    const dots = document.querySelectorAll('#unlockDots .pindot').length
    const wrong = []
    for (let i = 0; i < 5; i++) {
      window.unlockPin('9'); window.unlockPin('9'); window.unlockPin('9'); window.unlockPin('9')
      await new Promise(r => setTimeout(r, 40))
      wrong.push(document.getElementById('lockErr').textContent)
    }
    window.setDemo('lock', 'lockout')
    await new Promise(r => setTimeout(r, 80))
    const lockoutMsg = document.getElementById('lockErr').textContent
    const bio = document.getElementById('lockBio').textContent.trim()
    window.closeLockov()
    window.setDemo('lock', 'closed')
    return { open, dots, wrong, lockoutMsg, bio }
  })
  check('app lock gate: 4-dot PIN gate, wrong-code copy, 5-min lockout countdown',
    lockGate.open && lockGate.dots === 4
    && lockGate.wrong[0] === 'Wrong code — try again'
    && lockGate.wrong[4] === 'Too many attempts — try again in 1s'
    && /^Too many attempts — try again in \d+m \d+s$/.test(lockGate.lockoutMsg)
    && lockGate.bio === 'Use Face ID / fingerprint',
    JSON.stringify(lockGate))

  // ---- app lock screen: off / on / disable dialog / wizard / biometric --- #
  const lockScreen = await page.evaluate(async () => {
    window.nav('applock')
    const read = () => {
      const rows = [...document.querySelectorAll('#sc-applock .list .li')]
      const d = document.getElementById('lockDisableBtn')
      return {
        hero: document.getElementById('lockHeroTitle').textContent,
        pill: document.getElementById('lockHeroPill').textContent.trim(),
        disable: getComputedStyle(d).display,
        disableH: Math.round(d.getBoundingClientRect().height),
        rows: rows.map(r => ({
          off: r.classList.contains('row-off'),
          sw: r.querySelector('.sw[data-toggle]') ? r.querySelector('.sw').classList.contains('on') : null,
          val: r.querySelector('.sw[data-toggle]') ? r.querySelector('.sw').dataset.lockval : null
        }))
      }
    }
    // start from a known state: the gate test above leaves App Lock on
    window.confirmDisableLock()
    await new Promise(r => setTimeout(r, 80))
    document.getElementById('toasts').innerHTML = ''
    const off = read()
    document.querySelectorAll('#sc-applock .list .li')[1].querySelector('.sw').click()
    await new Promise(r => setTimeout(r, 30))
    const afterDimClick = read()
    document.querySelector('#sc-applock .list .li').click()
    await new Promise(r => setTimeout(r, 30))
    const autoWhileOff = document.getElementById('sheetAutoLock').classList.contains('show')

    // gate state turns the lock on (setDemo shares one state object with the screen)
    window.setDemo('lock', 'gate')
    await new Promise(r => setTimeout(r, 80))
    window.closeLockov()
    await new Promise(r => setTimeout(r, 60))
    const on = read()
    document.querySelector('#sc-applock .list .li').click()
    await new Promise(r => setTimeout(r, 60))
    const autoWhileOn = document.getElementById('sheetAutoLock').classList.contains('show')
    window.closeSheets()

    // DisableButton -> AlertDialog -> confirm
    document.getElementById('lockDisableBtn').click()
    await new Promise(r => setTimeout(r, 60))
    const dlg = document.getElementById('dlgLockDisable')
    const dlgOpen = dlg.classList.contains('show')
      && !document.querySelector('#sheetLockSetup').classList.contains('show')
    const dlgTitle = dlg.querySelector('.dlg-title').textContent
    document.getElementById('toasts').innerHTML = ''
    dlg.querySelectorAll('.dlg-btn')[1].click()
    await new Promise(r => setTimeout(r, 120))
    const afterDisable = read()
    const disableToast = document.getElementById('toasts').textContent.trim()

    // setup wizard: mismatch keeps the sheet open, a match grants the lock
    window.openLockSetup()
    await new Promise(r => setTimeout(r, 60))
    document.querySelector('#setM .li').click()
    document.getElementById('setupNext').click()
    await new Promise(r => setTimeout(r, 40))
    ;['1', '2', '3', '4'].forEach(d => window.setPin(d, 'c'))
    document.getElementById('setCNext').click()
    await new Promise(r => setTimeout(r, 40))
    const step3 = document.getElementById('setupTitle').textContent
    ;['1', '1', '1', '1'].forEach(d => window.setPin(d, 'v'))
    document.getElementById('toasts').innerHTML = ''
    document.getElementById('setVDone').click()
    await new Promise(r => setTimeout(r, 120))
    const mismatchToast = document.getElementById('toasts').textContent.trim()
    const stillOpen = document.getElementById('sheetLockSetup').classList.contains('show')
    ;['1', '2', '3', '4'].forEach(d => window.setPin(d, 'v'))
    document.getElementById('toasts').innerHTML = ''
    document.getElementById('setVDone').click()
    await new Promise(r => setTimeout(r, 900))
    const afterSetup = { hero: document.getElementById('lockHeroTitle').textContent,
                         toast: document.getElementById('toasts').textContent.trim() }

    // biometric: unavailable -> toast, and the row loses its active tint
    window.setDemo('bio', 'unavailable')
    window.setDemo('lock', 'gate')
    await new Promise(r => setTimeout(r, 80))
    const bioActiveWhenUnavailable = document.getElementById('lockBio').classList.contains('active')
    document.getElementById('toasts').innerHTML = ''
    window.bioUnlock()
    await new Promise(r => setTimeout(r, 60))
    const bioToast = document.getElementById('toasts').textContent.trim()
    window.closeLockov()
    window.setDemo('bio', 'available')

    // dwell ceiling (BlockScreenViewModel: coerceIn(3, 120))
    window.nav('block')
    for (let i = 0; i < 200; i++) window.dwellStep(1)
    const dwellMax = document.getElementById('bsDwellVal').textContent
    for (let i = 0; i < 200; i++) window.dwellStep(-1)
    const dwellMin = document.getElementById('bsDwellVal').textContent

    return { off, afterDimClick, autoWhileOff, on, autoWhileOn, dlgOpen, dlgTitle, afterDisable,
             disableToast, step3, mismatchToast, stillOpen, afterSetup, bioActiveWhenUnavailable,
             bioToast, dwellMax, dwellMin }
  })
  check('app lock: off state dims + inerts every setting and hides Disable',
    lockScreen.off.hero === 'App Lock is off' && lockScreen.off.pill === 'Off'
    && lockScreen.off.disable === 'none'
    && lockScreen.off.rows.every(r => r.off)
    && lockScreen.off.rows[1].sw === false && lockScreen.off.rows[1].val === '1'
    && lockScreen.afterDimClick.rows[1].sw === false     // MasterSwitch(clickable(enabled))
    && lockScreen.autoWhileOff === false,
    JSON.stringify(lockScreen.off))
  check('app lock: on state restores stored switches and shows Disable (h52)',
    lockScreen.on.hero === 'Locked with PIN' && lockScreen.on.pill === 'On'
    && lockScreen.on.disable === 'flex' && lockScreen.on.disableH === 52
    && lockScreen.on.rows.every(r => !r.off)
    && lockScreen.on.rows[1].sw === true && lockScreen.autoWhileOn === true,
    JSON.stringify(lockScreen.on))
  check('app lock: AlertDialog confirm disables the lock with al_toast_disabled',
    lockScreen.dlgOpen && lockScreen.dlgTitle === 'Turn off App Lock?'
    && lockScreen.afterDisable.hero === 'App Lock is off'
    && lockScreen.afterDisable.disable === 'none'
    && lockScreen.afterDisable.rows.every(r => r.off)
    && lockScreen.disableToast === 'App Lock is off',
    JSON.stringify({ dlgOpen: lockScreen.dlgOpen, title: lockScreen.dlgTitle,
                     after: lockScreen.afterDisable.hero, toast: lockScreen.disableToast }))
  check('app lock: wizard mismatch keeps the sheet, a match turns the lock on',
    lockScreen.step3 === 'Confirm your lock'
    && lockScreen.mismatchToast.includes('Codes don\'t match — try again') && lockScreen.stillOpen
    && lockScreen.afterSetup.hero === 'Locked with PIN'
    && lockScreen.afterSetup.toast.includes('App Lock is on'),
    JSON.stringify({ step3: lockScreen.step3, mismatch: lockScreen.mismatchToast,
                     after: lockScreen.afterSetup }))
  check('app lock: unavailable biometrics drop the active tint and toast',
    lockScreen.bioActiveWhenUnavailable === false
    && lockScreen.bioToast.includes("Biometrics aren't available on this device"),
    JSON.stringify({ active: lockScreen.bioActiveWhenUnavailable, toast: lockScreen.bioToast }))
  check('block screen: dwell clamps to 3-120s like BlockScreenViewModel',
    lockScreen.dwellMax === '120s' && lockScreen.dwellMin === '3s',
    `${lockScreen.dwellMin}..${lockScreen.dwellMax}`)

  // ---- home state machine: every variant renders like the ViewModel ------ #
  const heroStates = await page.evaluate(async () => {
    window.nav('home')
    const read = () => ({
      title: document.getElementById('homeHeroTitle').textContent,
      sub: document.getElementById('homeHeroSub').textContent,
      pill: document.getElementById('homePill').textContent.trim(),
      pillCls: document.getElementById('homePill').className,
      offset: parseFloat(document.getElementById('homeHeroRing').getAttribute('stroke-dashoffset')),
      master: document.getElementById('homeMaster').classList.contains('on')
    })
    const out = {}
    for (const v of ['full', 'partial', 'paused']) {
      document.getElementById('stHero').value = v
      window.setDemo('hero', v)
      await new Promise(r => setTimeout(r, 30))
      out[v] = read()
    }
    window.setDemo('hero', 'partial')
    return out
  })
  check('hero: full   -> "Fully protected" + green pill + full ring + switch on',
    heroStates.full.title === 'Fully protected' && heroStates.full.pill === 'Protected'
    && heroStates.full.pillCls.includes(' g') && heroStates.full.offset === 0
    && heroStates.full.master, JSON.stringify(heroStates.full))
  check('hero: partial -> "Partially protected" + amber "Attention" pill',
    heroStates.partial.title === 'Partially protected' && heroStates.partial.pill === 'Attention'
    && heroStates.partial.pillCls.includes(' o'), JSON.stringify(heroStates.partial))
  check('hero: paused -> "Protection paused", ring empty, master switch off',
    heroStates.paused.title === 'Protection paused' && heroStates.paused.pill === 'Paused'
    && heroStates.paused.offset === 163.4 && !heroStates.paused.master,
    JSON.stringify(heroStates.paused))

  // ---- accessibility banner only shows when the state is known-off ------- #
  const a11yStates = await page.evaluate(async () => {
    const out = {}
    for (const v of ['off', 'checking', 'on']) {
      document.getElementById('stA11y').value = v
      window.setDemo('a11y', v)
      await new Promise(r => setTimeout(r, 30))
      out[v] = getComputedStyle(document.getElementById('a11yBanner')).display
    }
    window.setDemo('a11y', 'off')
    return out
  })
  check('a11y banner shows only when known-off (hidden for checking/on)',
    a11yStates.off !== 'none' && a11yStates.checking === 'none' && a11yStates.on === 'none',
    JSON.stringify(a11yStates))

  // ---- header greeting override + quick-action empty state --------------- #
  const headerStates = await page.evaluate(async () => {
    document.getElementById('stGreet').value = 'morning'
    window.setDemo('greeting', 'morning')
    await new Promise(r => setTimeout(r, 30))
    const morning = document.getElementById('homeGreeting').textContent
    const dateOk = /^[A-Z][a-z]+, \d{1,2}:\d{2} (AM|PM)$/.test(document.getElementById('homeDate').textContent)
    for (let i = 0; i < 4; i++) window.qaRemove(0)
    const empty = document.querySelector('#sc-home .grid2').textContent.trim()
    window.qaReset()
    document.getElementById('stGreet').value = 'live'
    window.setDemo('greeting', 'live')
    return { morning, dateOk, empty }
  })
  check('header: greeting prefix + live date line, quick actions fall back to empty copy',
    headerStates.morning === 'Good morning, Alex' && headerStates.dateOk
    && headerStates.empty.startsWith('No quick actions yet'),
    JSON.stringify(headerStates))

  // ---- block-screen sheets: image tiles + message chips ------------------- #
  const blockSheets = await page.evaluate(async () => {
    const wait = ms => new Promise(r => setTimeout(r, ms))
    const cs = (el, p) => getComputedStyle(el)[p]
    document.getElementById('toasts').innerHTML = ''
    window.openSheet('sheetBsImg'); await wait(80)
    const tile = document.querySelector('#sheetBsImg .img-tile.img-ocean')
    const glow = cs(tile, 'boxShadow')
    tile.click(); await wait(40)
    const selectedGlow = cs(tile, 'boxShadow')
    const selectedBorder = cs(tile, 'borderColor')
    ;[...document.querySelectorAll('#sheetBsImg .btn-primary')].pop().click()
    await wait(60)
    const imgToast = document.querySelector('#toasts .toast')?.textContent || ''

    document.getElementById('toasts').innerHTML = ''
    window.openSheet('sheetBsMsg'); await wait(80)
    const chip = document.querySelector('#bsMsgChips .chip')
    const chipH = cs(chip, 'height')
    chip.click(); await wait(40)
    const chipOn = chip.classList.contains('on')
    const fieldValue = document.getElementById('bsMsgInput').value
    // free text that is not a suggestion clears every chip
    const inp = document.getElementById('bsMsgInput')
    inp.value = 'stay calm'; window.syncMsgChips(); await wait(20)
    const anyOn = [...document.querySelectorAll('#bsMsgChips .chip')].some(c => c.classList.contains('on'))
    // an empty message is allowed (production has no guard)
    inp.value = ''; window.syncMsgChips()
    ;[...document.querySelectorAll('#sheetBsMsg .btn-primary')].pop().click()
    await wait(60)
    const msgToasts = [...document.querySelectorAll('#toasts .toast')].map(t => t.textContent)
    const msgRow = document.getElementById('bsMsgSub').textContent
    // image row subtitle after choosing "ocean"
    const imgRow = document.getElementById('bsImgSub').textContent
    return { glow, selectedGlow, selectedBorder, imgToast, chipH, chipOn, fieldValue,
             anyOn, msgToasts, msgRow, imgRow }
  })
  check('block sheets: tiles use a brand border only; message chips are derived + 32dp',
    blockSheets.glow === 'none' && blockSheets.selectedGlow === 'none'
    && blockSheets.imgToast === 'Motivation image set to ocean'
    && blockSheets.chipH === '32px' && blockSheets.chipOn === true
    && blockSheets.fieldValue === 'Stay safe, Alex' && blockSheets.anyOn === false
    && blockSheets.msgToasts.includes('Message saved')
    && blockSheets.msgRow === '\u201C\u201D'
    && blockSheets.imgRow === 'ocean \u2022 persistable',
    JSON.stringify(blockSheets))

  // ---- time window sheet: inline validation + wrapping steppers ----------- #
  const timeSheet = await page.evaluate(async () => {
    const wait = ms => new Promise(r => setTimeout(r, ms))
    window.nav('schedule'); await wait(60)
    window.openSheet('sheetTime')
    window.timeTarget = 'start'
    document.getElementById('timeH').textContent = '21'
    document.getElementById('timeM').textContent = '00'
    window.renderTime(); await wait(40)
    // step down past 0 -> wraps to 23 (production modulo), never clamps
    for (let i = 0; i < 22; i++) window.timeStep('h', -1)
    const wrapped = document.getElementById('timeH').textContent
    // make start == end (23:00 vs the 23:00 default end) and press Done
    document.getElementById('toasts').innerHTML = ''
    const sheet = document.getElementById('sheetTime')
    ;[...sheet.querySelectorAll('.btn-primary')].pop().click()
    await wait(60)
    const errShown = document.getElementById('timeErr').textContent
    const stillOpen = sheet.classList.contains('show')
    const toasts = document.getElementById('toasts').children.length
    // any stepper edit clears the message
    window.timeStep('m', 1)
    await wait(40)
    const errCleared = document.getElementById('timeErr').textContent
    ;[...sheet.querySelectorAll('.btn-outline')].pop().click()
    await wait(60)
    return { wrapped, errShown, stillOpen, toasts, errCleared }
  })
  check('time sheet: inline error blocks Done, wraps 0->23, edit clears it',
    timeSheet.wrapped === '11' && timeSheet.errShown === "Start and end can't be the same"
    && timeSheet.stillOpen === true && timeSheet.toasts === 0
    && timeSheet.errCleared === '',
    JSON.stringify(timeSheet))

  // ---- app-picker sheets: AppPickerSheet vs SocialPickerSheet ------------- #
  const pickers = await page.evaluate(async () => {
    const cs = (el, prop) => getComputedStyle(el)[prop]
    const wait = ms => new Promise(r => setTimeout(r, ms))
    window.openSheet('sheetApps')
    await wait(60)
    const bulk = document.querySelector('#sheetApps .picker-bulk')
    const list = document.getElementById('appList')
    const rows = {
      gap: cs(bulk, 'gap'),
      bulkTop: cs(bulk, 'marginTop'),
      listTop: cs(list, 'marginTop'),
      listMax: cs(list, 'maxHeight'),
    }
    window.filterApps('zzzz')
    await wait(40)
    const emptyApp = document.querySelector('#appList .app-empty')
    rows.emptyText = emptyApp ? emptyApp.textContent : null
    rows.emptySize = emptyApp ? cs(emptyApp, 'fontSize') : null
    // bulk actions are silent in production
    document.getElementById('toasts').innerHTML = ''
    window.selectAllApps(); await wait(30)
    const toastAfterSelect = document.getElementById('toasts').children.length
    window.deselectAllApps(); await wait(30)
    document.getElementById('appSearch').value = ''
    window.closeSheets(); await wait(80)

    window.openSheet('sheetSocialApps')
    await wait(60)
    const sList = document.getElementById('socialAppList')
    const sRow = document.querySelector('#socialAppList .li')
    const sT = sRow ? sRow.querySelector('.t') : null
    const social = {
      listHeight: cs(sList, 'height'),
      bulkTop: cs(document.querySelector('#sheetSocialApps .picker-bulk'), 'marginTop'),
      rowPad: sRow ? cs(sRow, 'padding') : null,
      labelSize: sT ? cs(sT, 'fontSize') : null,
      labelWeight: sT ? cs(sT, 'fontWeight') : null,
      doneTop: cs(document.querySelector('#sheetSocialApps .btn-primary'), 'marginTop'),
    }
    document.getElementById('socialAppSearch').value = 'zzzz'
    window.filterSocialApps('zzzz')
    await wait(40)
    const sEmpty = document.querySelector('#socialAppList .app-empty')
    social.emptyText = sEmpty ? sEmpty.textContent : null
    social.emptyHeight = sEmpty ? cs(sEmpty, 'height') : null
    social.listHeightEmpty = cs(sList, 'height')
    window.closeSheets()
    return { rows, toastAfterSelect, social }
  })
  check('pickers: AppPickerSheet gaps/380dp/empty copy; SocialPickerSheet 320dp rows',
    pickers.rows.gap === '10px' && pickers.rows.bulkTop === '14px'
    && pickers.rows.listTop === '14px' && pickers.rows.listMax === '380px'
    && pickers.rows.emptyText === 'No apps found' && pickers.rows.emptySize === '14px'
    && pickers.toastAfterSelect === 0
    && pickers.social.listHeight === '320px' && pickers.social.bulkTop === '12px'
    && pickers.social.rowPad === '8px' && pickers.social.labelSize === '14px'
    && pickers.social.labelWeight === '500' && pickers.social.doneTop === '18px'
    && pickers.social.emptyText === 'No apps match your search'
    && pickers.social.emptyHeight === '160px'
    && pickers.social.listHeightEmpty === '160px',
    JSON.stringify(pickers))

  // ---- toast docks above the bottom nav on a tab root --------------------
  const toastGap = await page.evaluate(async () => {
    window.nav('home')
    window.toast('docking check')
    await new Promise(r => setTimeout(r, 60))
    const pill = document.querySelector('#toasts .toast')
    if (!pill) return { ok: false, reason: 'no toast rendered' }
    const t = pill.getBoundingClientRect()
    const nav = document.getElementById('nav').getBoundingClientRect()
    return { ok: t.bottom <= nav.top + 0.5, toastBottom: t.bottom, navTop: nav.top }
  })
  check('toast sits above the bottom nav', toastGap.ok, JSON.stringify(toastGap))

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
