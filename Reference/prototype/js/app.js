// ---------- Screen registry ----------
  const NAV = {home:'Home',block:'Block',schedule:'Schedule',profile:'Profile'};
  const SCREENS = {
    welcome:{group:'Onboarding'}, permissions:{group:'Onboarding'}, permbattery:{group:'Onboarding'}, perma11y:{group:'Onboarding'},
    home:{group:'Main',nav:true}, block:{group:'Main',nav:true}, schedule:{group:'Main',nav:true}, profile:{group:'Main',nav:true},
    keywords:{group:'Blocking'}, blockscreen:{group:'Blocking'}, vpn:{group:'Blocking'}, antitamper:{group:'Blocking'},
    selfprotect:{group:'Blocking'}, servicepicker:{group:'Blocking'}, titleblock:{group:'Blocking'}, otherfeatures:{group:'Blocking'}, socialblocking:{group:'Blocking'},
    applock:{group:'Protection'}, backup:{group:'Protection'}, troubleshoot:{group:'Protection'}, about:{group:'Protection'},
    history:{group:'Activity & Schedules'}, scheduleedit:{group:'Activity & Schedules'}, quickactions:{group:'Activity & Schedules'}
  };
  const ORDER = ['welcome','permissions','permbattery','perma11y','home','block','schedule','profile','keywords','blockscreen','vpn','antitamper','selfprotect','servicepicker','titleblock','otherfeatures','socialblocking','applock','backup','troubleshoot','about','history','scheduleedit','quickactions'];
  let stack = [];

  // Build prototype navigator
  const groups = {};
  ORDER.forEach((s,i)=>{ (groups[SCREENS[s].group] = groups[SCREENS[s].group]||[]).push([s,i]); });
  const ptb = document.getElementById('ptbGroups');
  for(const g in groups){
    ptb.insertAdjacentHTML('beforeend',`<div class="ptb-grp">${g}</div>`);
    groups[g].forEach(([s,i])=>{
      ptb.insertAdjacentHTML('beforeend',`<a href="#/s/${s}" data-pt="${s}" class="${s==='home'?'on':''}">${title(s)}<span class="d">${String(i+1).padStart(2,'0')}</span></a>`);
    });
  }

  function title(s){ return s.replace(/([A-Z])/g,' $1').replace(/^./,c=>c.toUpperCase()); }

  function show(name, push){
    document.querySelectorAll('.screen').forEach(el=>{
      // never fade the screen we are navigating to (re-entering a tab must not
      // clear it — production's launchSingleTop behaves the same way)
      if(el.classList.contains('show') && el.id !== 'sc-'+name){
        el.classList.add('leaving');               // exit = 200ms fade, no slide
        setTimeout(()=>el.classList.remove('leaving'), 220);
      }
      el.classList.remove('show');
    });
    document.getElementById('sc-'+name).classList.add('show');
    document.getElementById('sc-'+name).scrollTop = 0;
    const cfg = SCREENS[name]||{};
    const navEl = document.getElementById('nav');
    navEl.classList.toggle('show', !!cfg.nav);
    // toasts clear the bottom bar on tab roots (see .toasts.above-nav)
    document.getElementById('toasts').classList.toggle('above-nav', !!cfg.nav);
    document.querySelectorAll('#nav button').forEach(b=>b.classList.toggle('on', b.dataset.nav===name));
    document.querySelectorAll('#ptbGroups a').forEach(a=>a.classList.toggle('on', a.dataset.pt===name));
    document.getElementById('statusbar').style.visibility = 'visible';
    if(push) stack.push(name);
  }

  function nav(name){ show(name, true); location.hash = '#/s/'+name; }
  function back(){
    if(stack.length>1){ stack.pop(); const prev = stack[stack.length-1]; show(prev,false); location.hash='#/s/'+prev; }
    else nav('home');
  }
  const wasOnboarded = localStorage.getItem('safeme_onboarded');
  function permBack(name){ if(wasOnboarded) back(); else nav(name); }
  function permAdvance(btn){
    const nx = btn.dataset.next;
    if(nx) nav(nx); else finishOnboard();
  }

  /* Production flow: 3 steps (notifications, battery, accessibility). */
  function grantPerm(btn){
    const key = btn.dataset.perm;
    if(btn.classList.contains('granted')){ permAdvance(btn); return; }
    btn.classList.add('granted');
    btn.textContent = 'Granted \u2713';
    if(key) localStorage.setItem('safeme_perm_'+key,'1');
    toast('Permission granted \u2713');
    permStatus();
    setTimeout(()=>permAdvance(btn), 700);
  }

  function skipPerm(btn){
    toast('Skipped \u2014 you can grant it later from Profile');
    permAdvance(btn);
  }

  function finishOnboard(){
    const ok = ['notifications','a11y'].every(k=>localStorage.getItem('safeme_perm_'+k)==='1');
    if(!ok){ toast('Grant the required permissions to continue'); nav('permissions'); return; }
    const was = !!localStorage.getItem('safeme_onboarded');
    localStorage.setItem('safeme_onboarded','1');
    toast(was ? 'All permissions granted \u2014 you\u2019re fully protected' : 'Welcome to SafeMe \u2014 you\u2019re protected');
    nav('home');
  }

  function permStatus(){
    const keys = ['notifications','battery','a11y'];
    const granted = keys.filter(k=>localStorage.getItem('safeme_perm_'+k)==='1').length;
    const el = document.getElementById('permCardSub');
    if(el) el.textContent = granted + ' of 3 granted \u00b7 finish setup';
    keys.forEach(k=>{
      const btn = document.querySelector('.grant-pill[data-perm="'+k+'"]');
      if(btn && localStorage.getItem('safeme_perm_'+k)==='1'){ btn.classList.add('granted'); btn.textContent='Granted \u2713'; }
    });
  }

  // hash router
  window.addEventListener('hashchange', ()=>{
    const m = location.hash.match(/#\/s\/(\w+)/);
    const name = m ? m[1] : (localStorage.getItem('safeme_onboarded') ? 'home' : 'welcome');
    if(document.getElementById('sc-'+name)){ show(name, stack[stack.length-1]!==name); }
  });

  // ---------- Generic toggle ----------
  document.addEventListener('click', e=>{
    const t = e.target.closest('.sw[data-toggle]');
    if(t){ t.classList.toggle('on');
      if(t.id==='homeMaster'){ DEMO.home.paused=!t.classList.contains('on'); renderHome(); }
      toast(t.classList.contains('on')?'On':'Off'); if(t.closest('#schedList')) schedCount(); if(t.closest('#titleList')) titleCount(); if(t.id==='vpnToggle') vpnStatus(); }
  });

  // ---------- In-screen tabs ----------
  document.querySelectorAll('.tabs[data-tabs]').forEach(tb=>{
    tb.querySelectorAll('button').forEach(btn=>{
      btn.addEventListener('click', ()=>{
        tb.querySelectorAll('button').forEach(b=>b.classList.remove('on'));
        btn.classList.add('on');
        tb.parentElement.querySelectorAll('[data-panel-view]').forEach(p=>p.style.display='none');
        const panel = tb.parentElement.querySelector('[data-panel-view="'+btn.dataset.panel+'"]');
        if(panel) panel.style.display='block';
      });
    });
  });

  // ---------- Sheet category chips (KeywordManagerScreen CategoryPicker) ----------
  function pickCat(btn){
    btn.parentElement.querySelectorAll('.chip').forEach(c=>c.classList.remove('on'));
    btn.classList.add('on');
  }

  // ---------- App catalog (single source of truth for every App Picker) ----------
  const APP_CATS = ['Social','Video & Music','Messaging','Payment','Shopping','Games','News & Productivity','Other'];
  const APPS = [
    {name:'TikTok',        pkg:'com.zhiliaoapp.musically',          bg:'#FDEEE2', fg:'#F97316', ic:'<path d="M10 18V6l8-2v11"/><circle cx="7" cy="18" r="2.5"/><circle cx="15" cy="15.5" r="2.5"/>'},
    {name:'Instagram',     pkg:'com.instagram.android',             bg:'#FDEAF4', fg:'#E1306C', ic:'<rect x="3" y="3" width="18" height="18" rx="5"/><circle cx="12" cy="12" r="4"/><path d="M17 7h.01"/>'},
    {name:'Reddit',        pkg:'com.reddit.frontpage',              bg:'#FDE7E7', fg:'#FF4500', ic:'<circle cx="12" cy="12" r="8"/><path d="M12 8v5M9 13h.01M15 13h.01M9.5 16.5a3.5 3.5 0 005 0"/>'},
    {name:'Snapchat',      pkg:'com.snapchat.android',              bg:'#FDF3E3', fg:'#B78A00', ic:'<path d="M12 3a4 4 0 014 4c0 1.5-.6 3-1.6 4.4L16 15c-1.6 1.2-3 1.5-4 1.5S9.6 16.2 8 15l1.6-3.6C8.6 10 8 8.5 8 7a4 4 0 014-4z"/>'},
    {name:'Facebook',      pkg:'com.facebook.katana',               bg:'#E7F0FD', fg:'#1877F2', ic:'<rect x="3" y="3" width="18" height="18" rx="5"/><path d="M13 9h2V7h-2a3 3 0 00-3 3v2H9v2h1v4h2v-4h2l1-2h-3v-2a1 1 0 011-1z"/>'},
    {name:'X (Twitter)',   pkg:'com.twitter.android',               bg:'#EFEFEF', fg:'#0F1419', ic:'<path d="M4 4l16 16M20 4L4 20"/>'},
    {name:'YouTube',       pkg:'com.google.android.youtube',        bg:'#FDE7E7', fg:'#FF0000', ic:'<rect x="3" y="5" width="18" height="14" rx="4"/><path d="M10 9l5 3-5 3z"/>'},
    {name:'Netflix',       pkg:'com.netflix.mediaclient',           bg:'#FBEAEA', fg:'#E50914', ic:'<path d="M6 3v18l6-9 6 9V3"/>'},
    {name:'Spotify',       pkg:'com.spotify.music',                 bg:'#E6F7EC', fg:'#1DB954', ic:'<circle cx="12" cy="12" r="9"/><path d="M8 9.5c4-1 7 0 8 .5M8.5 13c3-.8 5.5 0 6.5.4M9 16.2c2-.5 4 0 4.5.3"/>'},
    {name:'Apple Music',   pkg:'com.apple.android.music',           bg:'#FDE7EC', fg:'#E0245E', ic:'<path d="M9 18V6l8-2v11"/><circle cx="7" cy="18" r="2"/><circle cx="15" cy="15" r="2"/>'},
    {name:'WhatsApp',      pkg:'com.whatsapp',                     bg:'#E6F7EC', fg:'#25D366', ic:'<path d="M12 3a9 9 0 00-7.8 13.5L3 21l4.7-1.2A9 9 0 1012 3z"/>'},
    {name:'Telegram',      pkg:'org.telegram.messenger',           bg:'#E7F0FD', fg:'#229ED9', ic:'<path d="M21 4L3 10.5l6 2.5M21 4l-4.5 16-5.5-7M21 4L9 13"/>'},
    {name:'Messages',      pkg:'com.google.android.apps.messaging', bg:'#E7F0EC', fg:'#34C759', ic:'<path d="M21 12a8 8 0 01-11.5 7.2L4 21l1.8-5A8 8 0 1121 12z"/><path d="M9 12h.01M12 12h.01M15 12h.01"/>'},
    {name:'Amazon',        pkg:'com.amazon.mShop.android.shopping', bg:'#FDEADF', fg:'#FF9900', ic:'<path d="M4 9a8 8 0 0116 0M20 5v4h-4M5 18c2.5 1.5 11.5 1.5 14 0"/>'},
    {name:'AliExpress',    pkg:'com.alibaba.aliexpresshd',          bg:'#FDE7DF', fg:'#E62E04', ic:'<rect x="3" y="5" width="18" height="14" rx="3"/><path d="M8 15V9l3 3 3-3v6"/>'},
    {name:'Shein',         pkg:'com.zzkko',                        bg:'#EFE9E5', fg:'#1E1E1E', ic:'<path d="M13 2L4 14h6l-1 8 9-12h-6z"/>'},
    {name:'Roblox',        pkg:'com.roblox.client',                bg:'#E9E9EA', fg:'#000000', ic:'<rect x="3" y="3" width="18" height="18" rx="4"/><path d="M9 16V8l6 8V8"/>'},
    {name:'Candy Crush',   pkg:'com.king.candycrushsaga',          bg:'#FDEBF3', fg:'#E91E8C', ic:'<circle cx="12" cy="12" r="8"/><path d="M12 8v8M8 12h8"/>'},
    {name:'Subway Surfers',pkg:'com.kiloo.subwaysurf',             bg:'#E6F4F5', fg:'#1FB5C9', ic:'<path d="M4 15l5-3 5 1 6-4"/><circle cx="7" cy="17" r="1.6"/><circle cx="17" cy="17" r="1.6"/>'},
    {name:'Chrome',        pkg:'com.android.chrome',               bg:'#FFF4E5', fg:'#4285F4', ic:'<circle cx="12" cy="12" r="9"/><circle cx="12" cy="12" r="3.5"/><path d="M12 12L5 8M12 12l7-4"/>'},
    {name:'Gmail',         pkg:'com.google.android.gm',            bg:'#FDE7E7', fg:'#EA4335', ic:'<rect x="3" y="5" width="18" height="14" rx="2"/><path d="M4 7l8 6 8-6"/>'},
    {name:'Maps',          pkg:'com.google.android.apps.maps',     bg:'#E7F4EC', fg:'#34A853', ic:'<path d="M12 21s-7-5.2-7-11a7 7 0 0114 0c0 5.8-7 11-7 11z"/><circle cx="12" cy="10" r="2.5"/>'},
    {name:'Calculator',    pkg:'com.google.android.calculator',    bg:'#EEF0F2', fg:'#3C4043', ic:'<rect x="5" y="3" width="14" height="18" rx="2"/><path d="M8 7h8M8 12h.01M12 12h.01M16 12h.01M8 16h.01M12 16h.01M16 16h.01"/>'},
    // Payment · banking · wallets · mobile money (discovered from package metadata)
    {name:'Google Pay',    pkg:'com.google.android.apps.walletnfcrel', bg:'#E7F0FD', fg:'#1A73E8', ic:'<path d="M3 7a2 2 0 012-2h12a2 2 0 012 2v10a2 2 0 01-2 2H5a2 2 0 01-2-2z"/><path d="M16 12h.01"/>'},
    {name:'PayPal',        pkg:'com.paypal.android.p2pmobile',     bg:'#E7EFFB', fg:'#0070BA', ic:'<circle cx="12" cy="12" r="9"/><path d="M14 9.2a2.2 2.2 0 00-4.4.5c0 2.4 4.8 1.4 4.8 4a2.2 2.2 0 01-4.4.4"/>'},
    {name:'Venmo',         pkg:'com.venmo',                        bg:'#E7F3FA', fg:'#3D95CE', ic:'<path d="M7 4l5 11 5-11"/>'},
    {name:'Cash App',      pkg:'block.util.cashapp',               bg:'#E7F5EA', fg:'#00A82D', ic:'<path d="M12 5v14M8.5 8.5a2 2 0 012-1.5H13a2 2 0 012 2 2 2 0 01-2 2h-2a2 2 0 00-2 2 2 2 0 002 2h2.5a2 2 0 002-1.5"/>'},
    {name:'Paytm',         pkg:'net.one97.paytm',                  bg:'#EDF2FB', fg:'#00BAF2', ic:'<path d="M9 16V6h3.5a2.5 2.5 0 010 5H9"/>'},
    {name:'PhonePe',       pkg:'com.phonepe.app',                  bg:'#EFEAF8', fg:'#5F259F', ic:'<path d="M13 2L4 14h6l-1 8 9-12h-6z"/>'}
  ];
  // Default selection for the schedule picker (keeps the “12 apps selected” demo in sync)
  const DEFAULT_APP_SEL = new Set(['TikTok','Instagram','YouTube','Reddit','Snapchat','Facebook','X (Twitter)','WhatsApp','Netflix','Spotify','Roblox','Chrome']);
  const appPickSel = new Set(DEFAULT_APP_SEL);
  const vpnPickSel = new Set();

  // ---------- Dynamic app classifier ----------
  // The category is derived on the fly from each installed app's package metadata
  // (app name + package id), mirroring what PackageManager exposes on device. Apps
  // are never shipped with a hand-tagged category — these keyword rules decide the
  // grouping so payment / shopping / social / media / messaging / games / tools are
  // binned consistently and anything unrecognised falls back to Other.
  function classifyApp(name, pkg){
    const s=(' '+(name||'').toLowerCase()+' '+(pkg||'').toLowerCase()+' ').replace(/\s+/g,' ');
    // Payment · banking · wallets · mobile money & fintech
    if(/\bpay(pal|tm)?\b|\bpayment\b|\bbank(s|ing)?\b|\bwallet\b|\bvenmo\b|\bzelle\b|\bupi\b|\bnetbank\b|credit ?card|debit|debit ?card|\batm\b|fintech|phonepe|\bmomo\b|alipay|wechat ?pay|samsung ?pay|apple ?pay|google ?pay|\bcash ?app\b|cashapp|\btransfer\b|remit|stripe|\bsquare\b|crypto|bitcoin|coinbase|revolut|monzo|chime|\bwise\b|klarna|afterpay|affirm|amazon ?pay/i.test(s)) return 'Payment';
    // Shopping · e-commerce · marketplaces
    if(/\bshop(ping)?\b|ecommerce|e-commerce|marketplace|\bmarket\b|\bstore\b|\bamazon\b|aliexpress|shein|temu|ebay|etsy|walmart|\btarget\b|best ?buy|costco|flipkart|meesho|snapdeal|myntra|daraz|lazada|taobao|alibaba|jd\.com|\bbuy\b|\bpurchase\b|h&m|uniqlo|zara|asos|zalando|sephora|\bcart\b|wish|shopee|rakuten|mercado|wayfair|homedepot|ikea/i.test(s)) return 'Shopping';
    // Social media
    if(/social|tiktok|instagram|reddit|snapchat|facebook|twitter|weibo|threads|linkedin|pinterest|tumblr|be ?real|quora|nextdoor|clubhouse/i.test(s)) return 'Social';
    // Video & music
    if(/video|youtube|netflix|spotify|\bmusic\b|prime ?video|disney|hulu|hbo|twitch|vimeo|bilibili|soundcloud|audiobook|podcast|apple ?music|dailymotion|crunchyroll|\bmedia\b/i.test(s)) return 'Video & Music';
    // Messaging
    if(/messag|whatsapp|telegram|signal|slack|imessage|rcs|\bsms\b|\bchat\b|wechat|weixin|viber|\bkakao\b|discord|messenger|\bline\b|textnow/i.test(s)) return 'Messaging';
    // Games
    if(/game|gaming|roblox|clash|crush|candy ?crush|subway ?surfer|sudoku|chess|solitaire|minecraft|fortnite|arcade|puzzle|wordle|league|dota|among ?us|pokemon|tetris|temple ?run/i.test(s)) return 'Games';
    // News & productivity tools
    if(/chrome|browser|gmail|\bmail\b|maps|calculator|calend|\bdocs\b|\bdrive\b|\bfiles\b|news|\boffice\b|\bproduct\b|\bword\b|excel|\bslides?\b|\bpdf\b|reader|\bnote\b|\bkeep\b|\bphoto\b|translate|search|\bclock\b|recorder|\bcontact\b|weather|finance|stocks|sheets|calendar/i.test(s)) return 'News & Productivity';
    return 'Other';
  }

  function appChip(a){
    return '<div class="ic" style="background:'+a.bg+';color:'+a.fg+'"><svg viewBox="0 0 24 24">'+a.ic+'</svg></div>';
  }
  // Shared renderer: groups the app catalog by category with consistent headers everywhere.
  function renderAppPicker(host, mode, sel, query){
    if(!host) return;
    const q=(query||'').trim().toLowerCase();
    let html='';
    const byCat={};
    APPS.forEach(a=>{ const c=classifyApp(a.name, a.pkg); (byCat[c]=byCat[c]||[]).push(a); });
    APP_CATS.forEach(cat=>{
      const apps=byCat[cat]||[];
      if(!apps.length) return;
      let rows='', shown=0;
      apps.forEach(a=>{
        const hit=!q || a.name.toLowerCase().includes(q) || a.pkg.toLowerCase().includes(q);
        if(!hit) return;
        shown++;
        const on=sel.has(a.name);
        rows += '<div class="li" data-app="'+a.name+'" onclick="togSel(this)">'+appChip(a)
             + '<div class="tx"><div class="t">'+a.name+'</div><div class="s">'+a.pkg+'</div></div>'
             + '<div class="checkbox'+(on?' on':'')+'"><svg viewBox="0 0 24 24"><path d="M5 12l5 5L20 6"/></svg></div></div>';
      });
      if(!shown) return;
      html += '<div class="app-group" data-cat="'+cat+'">'
           + '<div class="group-label">'+cat+'</div>'
           + '<div class="list">'+rows+'</div></div>';
    });
    if(!html) html = '<div class="app-empty">No apps match your search</div>';
    host.innerHTML = html;
  }
  // Rebuild the active picker from the shared catalog when its sheet opens.
  function refreshAppPicker(id){
    const cfg={
      sheetApps:['appList','appSearch','multi'],
      sheetVpnApps:['vpnAppList','vpnAppSearch','multi'],
      sheetSocialApps:['socialAppList','socialAppSearch','multi']
    }[id];
    if(!cfg) return;
    const host=document.getElementById(cfg[0]);
    const search=document.getElementById(cfg[1]); if(search) search.value='';
    const sel = id==='sheetApps' ? appPickSel : id==='sheetVpnApps' ? vpnPickSel : SOCIAL.wholeBlocked;
    renderAppPicker(host, cfg[2], sel, '');
    updatePickerCounts();
  }

  // ---------- Sheets ----------
  let delayTimer=null;
  function openSheet(id){
    document.getElementById('scrim').classList.add('show');
    document.getElementById(id).classList.add('show');
    if(id==='sheetDelay'){ startDelay(); }
    if(id==='sheetApps'||id==='sheetVpnApps'||id==='sheetSocialApps'){ refreshAppPicker(id); }
    if(id==='sheetTitle'){
      titleEditId=null;
      const tt=document.getElementById('titleSheetTitle'); if(tt) tt.textContent='Add title rule';
      const sb=document.getElementById('titleSaveBtn'); if(sb) sb.textContent='Add Title';
      const dl=document.getElementById('titleDel'); if(dl) dl.style.display='none';
      const inp=document.getElementById('titleInput'); if(inp) inp.value='';
      document.querySelectorAll('#sheetTitle #titleMode button').forEach((b,i)=>b.classList.toggle('on',i===0));
    }
    if(id==='sheetKw'){
      const kt=document.getElementById('kwSheetTitle'); if(kt) kt.textContent='Add keyword';
      const kb=document.getElementById('kwSaveBtn'); if(kb) kb.textContent='Add';
      const ki=document.getElementById('kwInput'); if(ki) ki.value='';
      document.querySelectorAll('#kwCat .chip').forEach((b,i)=>b.classList.toggle('on',i===0));
    }
    if(id==='sheetSite'){
      const st=document.getElementById('siteSheetTitle'); if(st) st.textContent='Add website';
      const sb=document.getElementById('siteSaveBtn'); if(sb) sb.textContent='Add';
      const si=document.getElementById('siteInput'); if(si) si.value='';
      document.querySelectorAll('#siteCat .chip').forEach((b,i)=>b.classList.toggle('on',i===0));
    }
    if(id==='sheetDnsCustom'){
      const v4=document.getElementById('dnsIpv4'), v6=document.getElementById('dnsIpv6');
      if(v4) v4.value=dnsV4; if(v6) v6.value=dnsV6;
    }
    if(id==='sheetBsImg'){
      const cur=document.querySelector('#sheetBsImg .img-tile[data-img="'+bsImg+'"]')||document.querySelector('#sheetBsImg .img-tile.clear');
      document.querySelectorAll('#sheetBsImg .img-tile').forEach(t=>t.classList.remove('on'));
      if(cur) cur.classList.add('on');
    }
    if(id==='sheetBsMsg'){
      const mi=document.getElementById('bsMsgInput'); if(mi) mi.value=bsMsg;
      document.querySelectorAll('#bsMsgChips .chip').forEach((b,i)=>b.classList.toggle('on',i===0));
    }
  }
  function closeSheets(){
    document.getElementById('scrim').classList.remove('show');
    document.querySelectorAll('.sheet').forEach(s=>s.classList.remove('show'));
    if(delayTimer){ clearInterval(delayTimer); delayTimer=null; }
  }
  function addKeyword(){
    const v = document.getElementById('kwInput').value.trim();
    if(!v){ toast('Keyword can\u2019t be empty'); return; }
    const cat = document.querySelector('#kwCat .chip.on').textContent.trim();
    if(kwSource==='screen'){ kwSource='manage'; if(kwSave(v,cat)) closeSheets(); return; }
    toast('Keyword saved');
  }
  function addSite(){
    const si = document.getElementById('siteInput');
    let v = (si.value||'').trim().replace(/^https?:\/\//i,'').replace(/\/+$/,'').toLowerCase();
    if(!v){ toast('Website can\u2019t be empty'); return; }
    if(/\s/.test(v) || v.indexOf('.')===-1){ toast('Enter a valid domain (e.g. example.com)'); return; }
    const cat = document.querySelector('#siteCat .chip.on').textContent.trim();
    if(kwSource==='screen'){ kwSource='manage'; if(kwSave(v,cat)) closeSheets(); return; }
    toast('Website saved');
  }
  function togSel(li){
    const c=li.querySelector('.checkbox'); c.classList.toggle('on');
    const name=li.dataset.app||(li.querySelector('.t')||{textContent:''}).textContent;
    let set = null;
    if(li.closest('#appList')) set = appPickSel;
    else if(li.closest('#vpnAppList')) set = vpnPickSel;
    else if(li.closest('#socialAppList')) set = SOCIAL.wholeBlocked;
    if(set){ if(c.classList.contains('on')) set.add(name); else set.delete(name); }
    updatePickerCounts();
    if(set===SOCIAL.wholeBlocked) renderSocial();
  }

  // ---------- Time delay gate ----------
  function testDelay(){
    openSheet('sheetDelay');
  }
  function startDelay(){
    let n=12; const num=document.getElementById('delayNum'); const fill=document.getElementById('delayFill');
    fill.style.width='100%';
    clearInterval(delayTimer);
    delayTimer = setInterval(()=>{
      n--; if(n<=0){ clearInterval(delayTimer); delayTimer=null; num.textContent='0'; fill.style.width='0%'; toast('Change applied'); closeSheets(); return; }
      num.textContent=n; fill.style.width=(n/12*100)+'%';
    }, 1000);
  }
  function cancelDelay(){ if(delayTimer){clearInterval(delayTimer); delayTimer=null;} closeSheets(); toast('Change cancelled'); }

  // ---------- Block overlay ----------
  let bsDwell=5, bsMsg='Stay safe, Alex. You chose this.', bsImg='';
  function renderBlockPreview(){
    const msg=document.getElementById('bsPreviewMsg'); if(msg) msg.textContent=bsMsg;
    const cnt=document.getElementById('bsPreviewCount'); if(cnt) cnt.textContent='Closing in '+bsDwell+'s…';
    const dv=document.getElementById('bsDwellVal'); if(dv) dv.textContent=bsDwell+'s';
    const is=document.getElementById('bsImgSub'); if(is) is.textContent=bsImg?(bsImg+' · persistable'):'None · SAF persistable URI';
    const pi=document.getElementById('bsPreviewImg'); if(pi){ if(bsImg){ pi.style.display='block'; pi.className='img-'+bsImg; } else { pi.style.display='none'; pi.className=''; } }
    const ms=document.getElementById('bsMsgSub'); if(ms) ms.textContent='"'+bsMsg+'"';
    const bm=document.getElementById('boMsg'); if(bm) bm.textContent=bsMsg;
    const why=document.getElementById('boWhy'); const sw=document.getElementById('bsWhySw'); if(why&&sw) why.style.display=sw.classList.contains('on')?'':'none';
  }
  function dwellStep(d){
    bsDwell=Math.max(3,Math.min(120,bsDwell+d));
    renderBlockPreview();
  }
  function pickImg(btn){
    document.querySelectorAll('#sheetBsImg .img-tile').forEach(t=>t.classList.remove('on'));
    btn.classList.add('on');
  }
  function saveImg(){
    const sel=document.querySelector('#sheetBsImg .img-tile.on'); if(sel) bsImg=sel.dataset.img||'';
    renderBlockPreview(); closeSheets(); toast(bsImg?('Motivation image set to '+bsImg):'No motivation image');
  }
  function pickMsgChip(chip){
    document.querySelectorAll('#bsMsgChips .chip').forEach(c=>c.classList.remove('on'));
    chip.classList.add('on');
    const inp=document.getElementById('bsMsgInput'); if(inp) inp.value=chip.textContent;
  }
  function saveMsg(){
    const v=(document.getElementById('bsMsgInput').value||'').trim();
    if(!v){ toast('Message can’t be empty'); return; }
    bsMsg=v; renderBlockPreview(); closeSheets(); toast('Message saved');
  }
  function openBlockov(){
    renderBlockPreview();
    document.getElementById('blockov').classList.add('show');
    let n=bsDwell; const num=document.getElementById('boNum'); const close=document.getElementById('boClose');
    close.classList.remove('ready'); close.classList.add('locked'); num.textContent=n;
    const t = setInterval(()=>{
      n--; num.textContent=Math.max(n,0);
      if(n<=0){ clearInterval(t); close.classList.add('ready'); close.classList.remove('locked'); }
    },1000);
  }
  function closeBlockov(){ document.getElementById('blockov').classList.remove('show'); toast('Back to the app'); }
  // Block-screen settings persist to localStorage (simulates SAF persistence),
  // mirroring the app's Save changes button on the Block Screen.
  const BS_KEY='safeme_blockscreen';
  function saveBlockScreen(){
    try{
      const sw=document.getElementById('bsWhySw');
      localStorage.setItem(BS_KEY, JSON.stringify({dwell:bsDwell,msg:bsMsg,img:bsImg,why:sw?sw.classList.contains('on'):true}));
    }catch(e){}
    toast('Changes saved');
  }
  function loadBlockScreen(){
    try{
      const raw=localStorage.getItem(BS_KEY); if(!raw) return;
      const s=JSON.parse(raw)||{};
      if(Number.isFinite(s.dwell)) bsDwell=Math.max(3,Math.min(120,s.dwell));
      if(typeof s.msg==='string' && s.msg) bsMsg=s.msg;
      if(typeof s.img==='string') bsImg=s.img;
      const sw=document.getElementById('bsWhySw');
      if(sw) sw.classList.toggle('on', !!s.why);
    }catch(e){}
  }
  loadBlockScreen();
  renderBlockPreview();

  // ---------- Presets / pickers ----------
  function pickDns(el){
    const l=el.closest('.list'); l.querySelectorAll('.checkbox').forEach(c=>c.classList.remove('on')); el.querySelector('.checkbox').classList.add('on');
    vpnPreset=el.querySelector('.t').textContent;
    if(vpnPreset==='Custom preset'){
      const v4=document.getElementById('dnsIpv4'), v6=document.getElementById('dnsIpv6');
      if(v4) v4.value=dnsV4; if(v6) v6.value=dnsV6;
      openSheet('sheetDnsCustom');
    } else { toast('Preset: '+vpnPreset); }
    vpnStatus();
  }
  // ---------- App Lock ----------
  let lockState='off', lockMethod='pin', lockCode='', lockAuto='Immediately';
  let wizMethod='pin', wizPin='', wizValPin='', patOrder=[], patOrderV=[];
  let unlockVal='', unlockPat=[];
  function methodLabel(){ return {pin:'PIN',password:'Password',pattern:'Pattern'}[lockMethod]; }
  function renderLock(){
    const hero=document.getElementById('lockHero'); if(!hero) return;
    hero.classList.toggle('lock-on', lockState==='on'); hero.classList.toggle('lock-off', lockState!=='on');
    document.getElementById('lockHeroTitle').textContent = lockState==='on' ? 'Locked with '+methodLabel() : 'App Lock is off';
    document.getElementById('lockHeroSub').textContent = lockState==='on' ? 'SafeMe locks automatically when you leave the app.' : 'Lock SafeMe with a PIN, password or pattern before anyone else opens it.';
    const pill=document.getElementById('lockHeroPill'); pill.className='pill '+(lockState==='on'?'g':'r'); pill.innerHTML='<span class="pdot"></span>'+(lockState==='on'?'On':'Off');
    const autoMap={Immediately:'Immediately after lock','After 15 seconds':'After 15 seconds idle','After 30 seconds':'After 30 seconds idle','After 1 minute':'After 1 minute idle','After 5 minutes':'After 5 minutes idle',Off:'Never auto-lock'};
    const av=document.getElementById('autoVal'); if(av) av.textContent=autoMap[lockAuto]||lockAuto;
  }
  function openLockSetup(){
    wizMethod=lockMethod; wizPin=''; wizValPin=''; patOrder=[]; patOrderV=[];
    const pc=document.getElementById('pcInput'); if(pc) pc.value='';
    const pv=document.getElementById('pvInput'); if(pv) pv.value='';
    document.querySelectorAll('#sheetLockSetup .patgrid button').forEach(b=>{ b.classList.remove('on'); b.textContent=''; });
    const ok=document.getElementById('setupOk'); if(ok) ok.classList.remove('show');
    showSetStep(1);
    document.getElementById('scrim').classList.add('show');
    document.getElementById('sheetLockSetup').classList.add('show');
  }
  function setLockMethod(m){
    wizMethod=m;
    document.querySelectorAll('#setM .li').forEach(li=>li.querySelector('.checkbox').classList.toggle('on', li.dataset.m===m));
    const nb=document.getElementById('setupNext'); if(nb) nb.disabled=false;
  }
  function showSetStep(n){
    ['setM','setC','setV'].forEach((id,i)=>{ const el=document.getElementById(id); if(el) el.style.display=(i+1===n)?'block':'none'; });
    document.querySelectorAll('#sheetLockSetup .steps span').forEach((s,i)=>s.classList.toggle('on', i<n));
    const t=document.getElementById('setupTitle'), sub=document.getElementById('setupSub');
    if(n===1){ t.textContent='Choose lock method'; sub.textContent='How do you want to lock SafeMe?'; }
    else if(n===2){ t.textContent='Create your lock'; sub.textContent='Enter a code to lock SafeMe'; }
    else { t.textContent='Confirm your lock'; sub.textContent='Enter it again to confirm'; }
    if(n===1) document.querySelectorAll('#setM .li').forEach(li=>li.querySelector('.checkbox').classList.toggle('on', li.dataset.m===wizMethod));
    document.querySelectorAll('.set-body').forEach(body=>{ body.querySelectorAll('.set-ui').forEach(ui=>{ ui.style.display=ui.dataset.m===wizMethod?'block':'none'; }); });
    const ok1=document.getElementById('setCNext'), ok2=document.getElementById('setVDone'), nb=document.getElementById('setupNext');
    if(nb) nb.disabled=true;
    if(ok1) ok1.disabled=!wizInputValid('c');
    if(ok2) ok2.disabled=!wizInputValid('v');
  }
  function setupStep(n){ if(n===2) resetWiz('c'); if(n===3) resetWiz('v'); showSetStep(n); }
  function resetWiz(stage){
    if(stage==='c'){ wizPin=''; patOrder=[]; const pc=document.getElementById('pcInput'); if(pc) pc.value=''; }
    else { wizValPin=''; patOrderV=[]; const pv=document.getElementById('pvInput'); if(pv) pv.value=''; }
    document.querySelectorAll(stage==='c'?'#patGridC button':'#patGridV button').forEach(b=>{ b.classList.remove('on'); b.textContent=''; });
    renderDotsId(stage==='c'?'pcDots':'pvDots', stage==='c'?wizPin:wizValPin);
  }
  function getWizCode(stage){
    if(wizMethod==='pin') return stage==='c'?wizPin:wizValPin;
    if(wizMethod==='password'){ const el=document.getElementById(stage==='c'?'pcInput':'pvInput'); return el?el.value:''; }
    return stage==='c'?patOrder.join('-'):patOrderV.join('-');
  }
  function wizInputValid(stage){ const v=getWizCode(stage); if(wizMethod==='pin'||wizMethod==='password') return v.length>=4; return v.split('-').length>=4; }
  function refreshWizBtn(stage){ const ok=wizInputValid(stage); const btn=document.getElementById(stage==='c'?'setCNext':'setVDone'); if(btn) btn.disabled=!ok; }
  function setPin(d,stage){ const max=(stage==='c'?wizPin:wizValPin).length>=6; if(max) return; if(stage==='c') wizPin+=d; else wizValPin+=d; renderDotsId(stage==='c'?'pcDots':'pvDots', stage==='c'?wizPin:wizValPin); refreshWizBtn(stage); }
  function pinBackS(stage){ if(stage==='c') wizPin=wizPin.slice(0,-1); else wizValPin=wizValPin.slice(0,-1); renderDotsId(stage==='c'?'pcDots':'pvDots', stage==='c'?wizPin:wizValPin); refreshWizBtn(stage); }
  function wizInput(stage){ refreshWizBtn(stage); }
  function patTap(btn,stage){ const arr=stage==='c'?patOrder:patOrderV; const i=btn.dataset.i; if(arr.includes(i)) return; arr.push(i); btn.classList.add('on'); btn.textContent=arr.length; refreshWizBtn(stage); }
  function renderDotsId(elId,val){ const host=document.getElementById(elId); if(!host) return; host.querySelectorAll('.pindot').forEach((d,i)=>d.classList.toggle('f', i<val.length)); }
  function saveLock(){
    const c=getWizCode('c'), v=getWizCode('v');
    if(!wizInputValid('v') || c!==v){
      const card=document.getElementById('setV'); card.classList.remove('shake'); void card.offsetWidth; card.classList.add('shake');
      resetWiz('v'); toast('Codes don\'t match — try again'); return;
    }
    lockState='on'; lockMethod=wizMethod; lockCode=c;
    const ok=document.getElementById('setupOk'); ok.classList.add('show');
    setTimeout(()=>{ ok.classList.remove('show'); closeSheets(); renderLock(); toast('App Lock is on'); }, 700);
  }
  function pickAuto(el,label){ el.closest('.list').querySelectorAll('.checkbox').forEach(c=>c.classList.remove('on')); el.querySelector('.checkbox').classList.add('on'); lockAuto=label; renderLock(); toast('Auto-lock: '+label); closeSheets(); }
  function lockNow(){ unlockVal=''; unlockPat=[]; renderUnlock(); document.getElementById('lockov').classList.add('show'); }
  function closeLockov(){ document.getElementById('lockov').classList.remove('show'); }
  function renderUnlock(){
    const b=document.getElementById('unlockBody');
    document.getElementById('lockovSub').textContent='Enter your '+methodLabel()+' to unlock';
    if(lockMethod==='pin'){
      b.innerHTML='<div class="pindots" id="unlockDots">'+'<span class="pindot"></span>'.repeat(lockCode.length)+'</div><div class="keypad">'+
        ['1','2','3','4','5','6','7','8','9'].map(n=>'<button onclick="unlockPin(\''+n+'\')">'+n+'</button>').join('')+
        '<button class="sym" style="visibility:hidden"></button><button onclick="unlockPin(\'0\')">0</button><button class="sym" onclick="unlockBack()">⌫</button></div>';
    }
    else if(lockMethod==='password'){
      b.innerHTML='<label class="field" style="margin-top:16px"><svg viewBox="0 0 24 24"><path d="M12 3a4 4 0 00-4 4v3H6a1 1 0 00-1 1v9a1 1 0 001 1h12a1 1 0 001-1v-9a1 1 0 00-1-1h-2V7a4 4 0 00-4-4z"/></svg><input id="unlockPass" type="password" placeholder="Enter password" oninput="unlockPass(this.value)"></label>';
    }
    else {
      b.innerHTML='<div class="patgrid" id="unlockPat">'+[1,2,3,4,5,6,7,8,9].map(i=>'<button data-i="'+i+'" onclick="patUnlock(this)"></button>').join('')+'</div><div class="s-sub" style="text-align:center;margin-top:12px">Draw your pattern</div>';
    }
    document.getElementById('lockErr').textContent='';
  }
  function unlockPin(d){ if(unlockVal.length>=lockCode.length) return; unlockVal+=d; renderDotsId('unlockDots',unlockVal); if(unlockVal.length===lockCode.length) checkUnlock(); }
  function unlockBack(){ unlockVal=unlockVal.slice(0,-1); renderDotsId('unlockDots',unlockVal); }
  function unlockPass(v){ unlockVal=v; if(v.length>=lockCode.length) checkUnlock(); }
  function patUnlock(btn){ const i=btn.dataset.i; if(unlockPat.includes(i)) return; unlockPat.push(i); btn.classList.add('on'); btn.textContent=unlockPat.length; if(unlockPat.length>=lockCode.split('-').length){ unlockVal=unlockPat.join('-'); checkUnlock(); } }
  function checkUnlock(){
    if(unlockVal===lockCode){ closeLockov(); toast('Unlocked'); renderLock(); }
    else {
      unlockVal=''; unlockPat=[];
      const card=document.getElementById('lockovCard'); card.classList.remove('shake'); void card.offsetWidth; card.classList.add('shake');
      renderUnlock(); document.getElementById('lockErr').textContent='Wrong code — try again';
    }
  }
  renderLock();

  // ---------- Theme ----------
  let themePref = (function(){ try{ return localStorage.getItem('safeme_theme') || 'System'; }catch(e){ return 'System'; } })();
  const mqDark = window.matchMedia('(prefers-color-scheme: dark)');
  function applyTheme(){
    const dark = themePref==='Dark' || (themePref==='System' && mqDark.matches);
    document.documentElement.setAttribute('data-theme', dark ? 'dark' : 'light');
  }
  function setTheme(btn,label){
    btn.parentElement.querySelectorAll('button').forEach(b=>b.classList.remove('on'));
    btn.classList.add('on');
    themePref = label;
    try{ localStorage.setItem('safeme_theme', label); }catch(e){}
    applyTheme();
    toast(label==='System'?'Following system theme':label+' theme applied');
  }
  function initTheme(){
    document.querySelectorAll('#themeSeg button').forEach(b=>b.classList.toggle('on', b.textContent.trim()===themePref));
    applyTheme();
  }
  try{ mqDark.addEventListener('change', applyTheme); }catch(e){ mqDark.addListener(applyTheme); }
  initTheme();

  let schedEditId=null, timeTarget='start', timeH=21, timeM=0;
  function h12(h){ return (h%12)||12; }
  function fmt12h(h24){ const p=h24.split(':'); const h=+p[0], m=p[1]; return h12(h)+'<span class="sep">:</span>'+m+'<span class="ampm"> '+(h<12?'AM':'PM')+'</span>'; }
  function setTimep(el,h24){ el.dataset.h24=h24; el.innerHTML=fmt12h(h24); }
  function saveSchedule(){
    const name=document.getElementById('schedName');
    if(!name||!name.value.trim()){ toast('Give your schedule a name'); return; }
    const days=[...document.querySelectorAll('#sc-scheduleedit .day')].map((d,i)=>d.classList.contains('on')?i:-1).filter(i=>i>=0);
    if(!days.length){ toast('Pick at least one day'); return; }
    const st=document.getElementById('timeStart'), en=document.getElementById('timeEnd');
    const start=(st&&st.dataset.h24)||'21:00', end=(en&&en.dataset.h24)||'23:00';
    if(start===end){ toast('Start and end can\u2019t be the same'); return; }
    const mode=(document.querySelector('#sc-scheduleedit .seg button.on')||{textContent:'Both'}).textContent;
    const apps=(parseInt((document.getElementById('schedAppCount')||{}).textContent||'0',10)||0);
    const enEl=document.getElementById('schedEnabled');
    const enabled=!enEl||enEl.classList.contains('on');
    const dayNames=['Mon','Tue','Wed','Thu','Fri','Sat','Sun'];
    const o={name:name.value.trim(),days:daysLabel(days),daysraw:days.map(i=>dayNames[i]).join(','),start:start,end:end,mode:mode,modetxt:modeTxt(mode),apps:apps,enabled:enabled};
    const html=schedCardHTML(o);
    if(schedEditId){ schedEditId.outerHTML=html; schedEditId=null; }
    else { document.getElementById('schedList').insertAdjacentHTML('beforeend',html); }
    schedCount(); nav('schedule'); toast('Schedule saved — alarms set');
  }
  function schedCardHTML(o){
    return '<div class="sched-card" data-name="'+o.name+'" data-days="'+o.days+'" data-daysraw="'+o.daysraw+'" data-start="'+o.start+'" data-end="'+o.end+'" data-mode="'+o.mode+'" data-modetxt="'+o.modetxt+'" data-apps="'+o.apps+'" data-enabled="'+(o.enabled===false?'0':'1')+'">'+
      '<div class="sched-head">'+
        '<div class="ic o"><svg viewBox="0 0 24 24"><rect x="4" y="5" width="16" height="16" rx="3"/><path d="M4 10h16M9 3v4M15 3v4"/></svg></div>'+
        '<div class="tx"><div class="t">'+o.name+'</div><div class="s">'+o.days+'</div></div>'+
        '<div class="sw'+(o.enabled===false?'':' on')+'" data-toggle></div>'+
      '</div>'+
      '<div class="sched-body">'+
        '<div class="sched-time"><svg viewBox="0 0 24 24"><circle cx="12" cy="12" r="9"/><path d="M12 7v5l3 2"/></svg><span class="sched-time-txt">'+o.start+' – '+o.end+(o.end<o.start?' (+1)':'')+'</span></div>'+
        '<div class="sched-pills"><span class="pill g">'+o.modetxt+'</span><span class="pill b">'+(Number(o.apps)?o.apps+' apps':'All apps')+'</span></div>'+
      '</div>'+
      '<div class="sched-foot"><span class="sched-next"><svg viewBox="0 0 24 24"><rect x="4" y="5" width="16" height="16" rx="3"/><path d="M4 10h16M9 3v4M15 3v4"/></svg>Next · '+o.days+'</span><button class="btn btn-ghost sm" onclick="openSchedEdit(this)">Edit</button></div>'+
    '</div>';
  }
  function daysLabel(arr){ const full=['Mon','Tue','Wed','Thu','Fri','Sat','Sun']; return arr.length===7?'Daily':arr.map(i=>full[i]).join(' · '); }
  function modeTxt(m){ return m==='Both'?'Internet + Launch':m==='Internet'?'Internet blocked':'Launch blocked'; }
  function openSchedEdit(btn){
    const title=document.getElementById('schedEditTitle'), save=document.getElementById('schedSaveBtn'), del=document.getElementById('schedDel');
    const name=document.getElementById('schedName');
    const days=[...document.querySelectorAll('#sc-scheduleedit .day')];
    const dayNames=['Mon','Tue','Wed','Thu','Fri','Sat','Sun'];
    const ts=document.getElementById('timeStart'), te=document.getElementById('timeEnd');
    const seg=[...document.querySelectorAll('#sc-scheduleedit .seg button')];
    const apc=document.getElementById('schedAppCount'), aps=document.getElementById('schedAppSub');
    if(!btn){
      schedEditId=null;
      if(del) del.style.display='none';
      if(title) title.textContent='New Schedule';
      if(save) save.textContent='Create schedule';
      if(name) name.value='';
      days.forEach((d,i)=>d.classList.toggle('on',i<3));
      setTimep(ts,'21:00'); setTimep(te,'23:00');
      seg.forEach(b=>b.classList.toggle('on',b.textContent==='Both'));
      if(apc) apc.textContent='No apps selected';
      if(aps) aps.textContent='No apps — schedule blocks everything';
      const en0=document.getElementById('schedEnabled'); if(en0) en0.classList.add('on');
      nav('scheduleedit'); return;
    }
    const c=btn.closest('.sched-card'); schedEditId=c;
    if(del) del.style.display='block';
    if(title) title.textContent='Edit Schedule';
    if(save) save.textContent='Save changes';
    if(name) name.value=c.dataset.name;
    const raw=(c.dataset.daysraw||'').split(',');
    days.forEach((d,i)=>d.classList.toggle('on',raw.includes(dayNames[i])));
    setTimep(ts,c.dataset.start||'21:00'); setTimep(te,c.dataset.end||'23:00');
    seg.forEach(b=>b.classList.toggle('on',b.textContent===c.dataset.mode));
    const n=Number(c.dataset.apps||0);
    if(apc) apc.textContent=n?(n+' apps selected'):'No apps selected';
    if(aps) aps.textContent=n?'Tap Choose to change apps':'No apps — schedule blocks everything';
    const en=document.getElementById('schedEnabled'); if(en) en.classList.toggle('on', c.dataset.enabled!=='0');
    nav('scheduleedit');
  }
  function togDay(btn){
    btn.classList.toggle('on');
    if(!document.querySelectorAll('#sc-scheduleedit .day.on').length){ btn.classList.add('on'); toast('Pick at least one day'); }
  }
  function togSeg(btn){ btn.parentElement.querySelectorAll('button').forEach(b=>b.classList.remove('on')); btn.classList.add('on'); }
  function openTime(t){
    timeTarget=t;
    const el=document.getElementById(t==='start'?'timeStart':'timeEnd');
    const m=((el&&el.dataset.h24)||(el?el.textContent:'21:00')).split(':');
    timeH=+m[0]; timeM=+(m[1]||'0');
    renderTime();
    document.getElementById('scrim').classList.add('show');
    document.getElementById('sheetTime').classList.add('show');
  }
  function renderTime(){
    document.getElementById('timeH').textContent=String(h12(timeH));
    document.getElementById('timeM').textContent=String(timeM).padStart(2,'0');
    document.querySelectorAll('#sheetTime .seg button').forEach(b=>b.classList.toggle('on',b.textContent.trim()===(timeH<12?'AM':'PM')));
  }
  function timeStep(u,d){
    if(u==='h'){ timeH=Math.min(23,Math.max(0,timeH+d)); }
    else { timeM=Math.min(59,Math.max(0,timeM+d)); }
    renderTime();
  }
  function togAmpm(btn){
    if(btn.textContent.trim()==='AM') timeH=timeH%12;
    else timeH=(timeH%12)+12;
    renderTime();
  }
  function timeDone(){
    const v=String(timeH).padStart(2,'0')+':'+String(timeM).padStart(2,'0');
    const el=document.getElementById(timeTarget==='start'?'timeStart':'timeEnd');
    setTimep(el,v);
    const a=(document.getElementById('timeStart').dataset.h24)||'21:00', b=(document.getElementById('timeEnd').dataset.h24)||'23:00';
    const cap=document.getElementById('schedOvernight');
    if(cap) cap.style.display = (b<a)?'block':'none';
    if(a===b){ toast('Start and end can\u2019t be the same'); return; }
    closeSheets();
  }
  function delSchedule(){
    if(!schedEditId) return;
    schedEditId.remove(); schedEditId=null;
    schedCount(); nav('schedule'); toast('Schedule deleted');
  }
  function schedCount(){
    const all=document.querySelectorAll('#schedList .sched-card');
    const on=[...all].filter(c=>c.querySelector('.sw').classList.contains('on')).length;
    const c=document.getElementById('schedHeroCount'); if(c) c.textContent=on+' active schedule'+(on===1?'':'s');
    const p=document.getElementById('schedHeroPill'); if(p){ p.className='pill '+(on?'g':'r'); p.innerHTML='<span class="pdot"></span>'+(on?'On':'Off'); }
    const sub=document.getElementById('schedHeroSub');
    if(sub){
      const first=on?[...all].find(c=>c.querySelector('.sw').classList.contains('on')):null;
      sub.textContent=first?('Next boundary · '+(first.dataset.name||'Schedule')+' at '+(first.dataset.start||'--:--')):(on===0&&all.length?'All schedules paused':'No schedules yet — create one');
    }
    const emp=document.getElementById('schedEmpty'); if(emp) emp.style.display=all.length?'none':'block';
    const hs=document.getElementById('homeSchedules'); if(hs) hs.textContent=String(on);
  }
  schedCount();

  // ---------- Title Block ----------
  let titleEditId=null;
  function titleSearch(){
    const q=(document.getElementById('titleSearch').value||'').trim().toLowerCase();
    let shown=0;
    document.querySelectorAll('#titleList .kw-row').forEach(r=>{
      const hit=!q||(r.querySelector('.t').textContent+' '+(r.querySelector('.s').textContent||'')).toLowerCase().includes(q);
      r.style.display=hit?'':'none';
      if(hit) shown++;
    });
    const nm=document.getElementById('titleNoMatch'); if(nm) nm.style.display=(q&&!shown)?'':'none';
  }
  function addTitle(){
    const inp=document.getElementById('titleInput');
    const v=(inp&&inp.value.trim())||'';
    if(!v){ toast('Enter a title to block'); return; }
    const mode=(document.querySelector('#sheetTitle #titleMode button.on')||{textContent:'Contains'}).textContent;
    const dup=[...document.querySelectorAll('#titleList .kw-row')].some(r=>r!==titleEditId&&r.querySelector('.t').textContent.toLowerCase()===v.toLowerCase());
    if(dup){ toast('Rule already exists'); return; }
    const row=document.createElement('div');
    row.className='kw-row';
    row.innerHTML='<div class="ic d"><svg viewBox="0 0 24 24"><path d="M4 6h16M4 12h16M4 18h16"/></svg></div><div class="tx"><div class="t"></div><div class="s"></div></div><div class="sw on" data-toggle></div><button class="act" onclick="editTitle(this)"><svg viewBox="0 0 24 24"><path d="M9 5l7 7-7 7"/></svg></button>';
    row.querySelector('.t').textContent=v;
    row.querySelector('.s').textContent=mode;
    if(titleEditId){ titleEditId.replaceWith(row); titleEditId=null; }
    else { document.getElementById('titleList').appendChild(row); }
    titleCount(); titleSearch(); closeSheets(); toast('Rule added');
  }
  function editTitle(btn){
    const li=btn.closest('.kw-row');
    titleEditId=li;
    const v=li.querySelector('.t').textContent;
    const mode=(li.querySelector('.s').textContent||'Contains');
    const inp=document.getElementById('titleInput'); if(inp) inp.value=v;
    document.querySelectorAll('#sheetTitle #titleMode button').forEach(b=>b.classList.toggle('on',b.textContent===mode));
    const tt=document.getElementById('titleSheetTitle'); if(tt) tt.textContent='Edit title rule';
    const sb=document.getElementById('titleSaveBtn'); if(sb) sb.textContent='Save changes';
    const dl=document.getElementById('titleDel'); if(dl) dl.style.display='';
    document.getElementById('scrim').classList.add('show');
    document.getElementById('sheetTitle').classList.add('show');
  }
  function delTitle(){
    if(!titleEditId) return;
    titleEditId.remove(); titleEditId=null;
    closeSheets(); titleCount(); toast('Rule deleted');
  }
  function titleCount(){
    const all=document.querySelectorAll('#titleList .kw-row');
    const on=[...all].filter(r=>r.querySelector('.sw').classList.contains('on')).length;
    const h=document.getElementById('titleHeroTitle'); if(h) h.textContent=all.length?(on?'Title blocking is on':'Title blocking is paused'):'No title rules yet';
    const tag=document.querySelector('#titleHero .tag'); if(tag) tag.textContent=all.length?(on?'Active':'Paused'):'Setup';
    const sub=document.getElementById('titleHeroSub');
    if(sub) sub.textContent=all.length?(all.length+' rule'+(all.length===1?'':'s')+' · '+on+' active · matching in real time'):'Add a rule to start blocking by window title';
    const empty=document.getElementById('titleEmpty'); if(empty) empty.style.display=all.length?'none':'';
  }
  titleCount();
  (function(){
    const inp=document.getElementById('titleInput');
    if(inp) inp.addEventListener('keydown',e=>{ if(e.key==='Enter') addTitle(); });
  })();
  function updatePickerCounts(){
    const a=document.getElementById('selAllAppsBtn'), b=document.getElementById('deselAllAppsBtn');
    if(a) a.textContent='Select All ('+appPickSel.size+')';
    if(b) b.textContent='Deselect All ('+appPickSel.size+')';
    const c=document.getElementById('selAllVpnBtn'), d=document.getElementById('deselAllVpnBtn');
    if(c) c.textContent='Select All ('+vpnPickSel.size+')';
    if(d) d.textContent='Deselect All ('+vpnPickSel.size+')';
    const e=document.getElementById('selAllSocialBtn'), f=document.getElementById('deselAllSocialBtn');
    if(e) e.textContent='Select All ('+SOCIAL.wholeBlocked.size+')';
    if(f) f.textContent='Deselect All ('+SOCIAL.wholeBlocked.size+')';
  }
  function selectAllApps(){
    const q=(document.getElementById('appSearch')||{}).value||'';
    APPS.forEach(a=>appPickSel.add(a.name));
    renderAppPicker(document.getElementById('appList'),'multi',appPickSel,q);
    updatePickerCounts();
    toast('All apps selected');
  }
  function deselectAllApps(){
    const q=(document.getElementById('appSearch')||{}).value||'';
    appPickSel.clear();
    renderAppPicker(document.getElementById('appList'),'multi',appPickSel,q);
    updatePickerCounts();
    toast('Deselected all apps');
  }
  function appsDone(){
    const names=[...appPickSel];
    const editor=document.getElementById('sc-scheduleedit');
    if(editor && editor.classList.contains('show')){
      const apc=document.getElementById('schedAppCount'), aps=document.getElementById('schedAppSub');
      if(apc) apc.textContent=names.length+' app'+(names.length===1?'':'s')+' selected';
      if(aps) aps.textContent=names.length?(names.slice(0,4).join(', ')+(names.length>4?'…':'')):'No apps — schedule blocks everything';
    }
    closeSheets(); toast(names.length+' app'+(names.length===1?'':'s')+' selected');
  }
  function filterApps(q){
    const v=(q||'').trim().toLowerCase();
    document.querySelectorAll('#appList .app-group').forEach(g=>{
      let any=false;
      g.querySelectorAll('.li').forEach(r=>{
        const t=(r.querySelector('.t').textContent+' '+(r.querySelector('.s')||{textContent:''}).textContent).toLowerCase();
        const hit=!v||t.includes(v);
        r.style.display=hit?'':'none';
        if(hit) any=true;
      });
      g.style.display=any?'':'none';
    });
  }

  // ---------- Social Media Blocking (two gates) ----------
  const SOCIAL_KEY='safeme_social_blocking_v1';
  const SOCIAL={ enabled: true, wholeBlocked: new Set(['TikTok','Instagram','X / Twitter','Reddit','Twitch']), youtube:true, facebook:true, snapchat:true };
  function loadSocial(){
    try{
      const raw=localStorage.getItem(SOCIAL_KEY); if(!raw) return;
      const j=JSON.parse(raw)||{};
      SOCIAL.enabled=!!j.enabled;
      SOCIAL.wholeBlocked=new Set(Array.isArray(j.wholeBlocked)?j.wholeBlocked:[]);
      if(typeof j.youtube==='boolean') SOCIAL.youtube=j.youtube;
      if(typeof j.facebook==='boolean') SOCIAL.facebook=j.facebook;
      if(typeof j.snapchat==='boolean') SOCIAL.snapchat=j.snapchat;
    }catch(e){}
  }
  function saveSocial(){
    try{ localStorage.setItem(SOCIAL_KEY, JSON.stringify({enabled:SOCIAL.enabled, wholeBlocked:[...SOCIAL.wholeBlocked], youtube:SOCIAL.youtube, facebook:SOCIAL.facebook, snapchat:SOCIAL.snapchat})); }catch(e){}
  }
  function appByName(name){ return APPS.find(a=>a.name===name) || {name:name, bg:'#25282E', fg:'#9A9AA0', ic:'<circle cx="12" cy="12" r="8"/>'};
  }
  function renderSocial(){
    const m=document.getElementById('socialMaster');
    if(m) m.classList.toggle('on', SOCIAL.enabled);
    // master pill — SafeMe light
    const mp=document.getElementById('socialMasterPill');
    if(mp){
      if(SOCIAL.enabled){
        mp.innerHTML='<span style="width:7px;height:7px;border-radius:50%;background:var(--success);box-shadow:0 0 0 4px rgba(46,125,91,.12)"></span>Master ON';
        mp.style.color='var(--success)';
      } else {
        mp.innerHTML='<span style="width:7px;height:7px;border-radius:50%;background:var(--ink-3)"></span>Master OFF';
        mp.style.color='var(--ink-3)';
      }
    }
    const ms=document.getElementById('socialMasterSub');
    if(ms) ms.textContent = SOCIAL.enabled ? 'Whole apps & tabs protected' : 'Paused — tap to protect';
    const hw=document.getElementById('socialHeroWhole');
    if(hw){ hw.textContent = SOCIAL.wholeBlocked.size+' launch'; }
    const ht=document.getElementById('socialHeroTabs');
    if(ht){
      const gated = (SOCIAL.youtube?1:0)+(SOCIAL.facebook?1:0)+(SOCIAL.snapchat?1:0);
      ht.textContent = (SOCIAL.enabled?gated:0)+'/3 tabs';
    }
    const tabPill=document.getElementById('tabActivePill');
    if(tabPill){
      const gated = (SOCIAL.youtube?1:0)+(SOCIAL.facebook?1:0)+(SOCIAL.snapchat?1:0);
      const active = SOCIAL.enabled ? gated : 0;
      tabPill.textContent = active+' active';
      tabPill.style.background = active? 'var(--brand-soft)' : 'var(--surface)';
      tabPill.style.color = active? 'var(--brand-dark)' : 'var(--ink-3)';
      tabPill.style.borderColor = active? 'rgba(217,119,87,.25)' : 'var(--line)';
    }
    // launch rows
    document.querySelectorAll('.launch-row .sw').forEach(sw=>{
      const name=sw.dataset.launch;
      const on = SOCIAL.enabled && SOCIAL.wholeBlocked.has(name);
      sw.classList.toggle('on', on);
    });
    document.querySelectorAll('.launch-row').forEach(row=>{
      row.style.opacity = SOCIAL.enabled ? '1' : '.45';
      row.style.pointerEvents = SOCIAL.enabled ? 'auto' : 'none';
    });
    // presets highlight
    const deep=document.getElementById('presetDeep'), bal=document.getElementById('presetBalanced'), rel=document.getElementById('presetRelax');
    const wholeN=SOCIAL.wholeBlocked.size;
    const tabsN=(SOCIAL.youtube?1:0)+(SOCIAL.facebook?1:0)+(SOCIAL.snapchat?1:0);
    let preset='none';
    if(!SOCIAL.enabled) preset='relax';
    else if(wholeN>=4 && tabsN===3) preset='deep';
    else if(wholeN===0 && tabsN===3) preset='balanced';
    if(deep){ deep.style.background = preset==='deep' ? 'var(--brand-soft)' : 'var(--surface)'; deep.style.borderColor = preset==='deep' ? 'var(--brand)' : 'var(--line)'; deep.style.borderWidth = preset==='deep' ? '1.5px' : '1px'; }
    if(bal){ bal.style.background = preset==='balanced' ? 'var(--brand-soft)' : 'var(--surface)'; bal.style.borderColor = preset==='balanced' ? 'var(--brand)' : 'var(--line)'; bal.style.borderWidth = preset==='balanced' ? '1.5px' : '1px'; }
    if(rel){ rel.style.background = preset==='relax' ? 'var(--brand-soft)' : 'var(--surface)'; rel.style.borderColor = preset==='relax' ? 'var(--brand)' : 'var(--line)'; rel.style.borderWidth = preset==='relax' ? '1.5px' : '1px'; }

    // tabs
    const yt=document.getElementById('socialYoutube'); if(yt) yt.classList.toggle('on', SOCIAL.enabled && SOCIAL.youtube);
    const fb=document.getElementById('socialFacebook'); if(fb) fb.classList.toggle('on', SOCIAL.enabled && SOCIAL.facebook);
    const sc=document.getElementById('socialSnapchat'); if(sc) sc.classList.toggle('on', SOCIAL.enabled && SOCIAL.snapchat);
    document.querySelectorAll('#sc-socialblocking .list .sw, #sc-socialblocking [id^="social"] .sw').forEach(sw=>{
      // keep minimal dim already handled, but ensure tabs dim when master off
    });
    // hide compat
    const ws=document.getElementById('socialWholeSub'); if(ws) ws.style.display='none';
    const chips=document.getElementById('socialWholeChips'); if(chips) chips.style.display='none';
    const cp=document.getElementById('socialCountPill'); if(cp) cp.style.display='none';
    // dynamic extra apps beyond default 5
    const launchList=document.getElementById('launchList');
    if(launchList){
      const defaults=new Set(['TikTok','Instagram','X / Twitter','Reddit','Twitch']);
      launchList.querySelectorAll('.launch-row.extra').forEach(r=>{
        const nm=r.dataset.app;
        if(!SOCIAL.wholeBlocked.has(nm)) r.remove();
      });
      SOCIAL.wholeBlocked.forEach(name=>{
        if(defaults.has(name)) return;
        const exists = launchList.querySelector('.launch-row[data-app="'+name+'"]');
        if(exists) return;
        const a=appByName(name);
        const row=document.createElement('div');
        row.className='launch-row extra';
        row.dataset.app=name;
        row.style.display='flex';
        row.style.alignItems='center';
        row.style.gap='12px';
        row.style.background='var(--surface)';
        row.style.border='1px solid var(--line)';
        row.style.borderRadius='16px';
        row.style.padding='12px';
        const ic=document.createElement('div');
        ic.style.width='42px'; ic.style.height='42px'; ic.style.borderRadius='14px'; ic.style.background=a.bg; ic.style.color=a.fg;
        ic.style.display='flex'; ic.style.alignItems='center'; ic.style.justifyContent='center'; ic.style.flex='none';
        ic.innerHTML='<svg viewBox="0 0 24 24" style="width:18px;height:18px;stroke:currentColor;fill:none;stroke-width:2;stroke-linecap:round;stroke-linejoin:round">'+a.ic+'</svg>';
        const tx=document.createElement('div'); tx.style.flex='1'; tx.style.minWidth='0';
        tx.innerHTML='<div style="font-size:14px;font-weight:600;color:var(--ink);line-height:1.2">'+name+'</div><div style="font-size:11.5px;color:var(--ink-2);margin-top:2px">'+a.pkg+'</div>';
        const sw=document.createElement('div'); sw.className='sw on'; sw.dataset.launch=name;
        sw.onclick=function(){ togLaunch(sw, name); };
        sw.classList.toggle('on', SOCIAL.enabled && SOCIAL.wholeBlocked.has(name));
        row.appendChild(ic); row.appendChild(tx); row.appendChild(sw);
        launchList.appendChild(row);
      });
    }
    updatePickerCounts();
  }
  function applyPreset(which){
    if(which==='deep'){
      SOCIAL.enabled=true;
      SOCIAL.wholeBlocked=new Set(['TikTok','Instagram','X / Twitter','Reddit','Twitch']);
      SOCIAL.youtube=true; SOCIAL.facebook=true; SOCIAL.snapchat=true;
    } else if(which==='balanced'){
      SOCIAL.enabled=true;
      SOCIAL.wholeBlocked.clear();
      SOCIAL.youtube=true; SOCIAL.facebook=true; SOCIAL.snapchat=true;
    } else if(which==='relax'){
      SOCIAL.enabled=false;
    }
    saveSocial(); renderSocial();
    const labels={deep:'Deep Work \u2014 all apps & tabs', balanced:'Balanced \u2014 tabs only', relax:'Relax \u2014 all paused'};
    toast(labels[which]);
  }
  function togLaunch(el, name){
    if(!SOCIAL.enabled){ toast('Turn on Master first'); return; }
    if(SOCIAL.wholeBlocked.has(name)) SOCIAL.wholeBlocked.delete(name);
    else SOCIAL.wholeBlocked.add(name);
    saveSocial(); renderSocial();
    toast(name + (SOCIAL.wholeBlocked.has(name) ? ' blocked' : ' allowed'));
  }
  function togSocialMaster(el){
    SOCIAL.enabled = !SOCIAL.enabled;
    el.classList.toggle('on', SOCIAL.enabled);
    saveSocial(); renderSocial();
    toast(SOCIAL.enabled ? 'Social Media Blocking — on' : 'Social Media Blocking — off');
    actAdd(SOCIAL.enabled?'block':'schedule', SOCIAL.enabled?'Social blocking enabled':'Social blocking paused', SOCIAL.enabled? (SOCIAL.wholeBlocked.size+' whole · '+(SOCIAL.youtube+SOCIAL.facebook+SOCIAL.snapchat)+' tabs') : 'Master off · both gates paused', new Date().toLocaleTimeString([],{hour:'2-digit',minute:'2-digit'}));
  }
  function togSocialFeature(el, key){
    if(!SOCIAL.enabled){ toast('Turn on Social Media Blocking first'); return; }
    if(key==='youtube') SOCIAL.youtube=!SOCIAL.youtube;
    if(key==='facebook') SOCIAL.facebook=!SOCIAL.facebook;
    if(key==='snapchat') SOCIAL.snapchat=!SOCIAL.snapchat;
    saveSocial(); renderSocial();
    const labels={youtube:'YouTube Shorts',facebook:'Facebook Reels',snapchat:'Snapchat Spotlight'};
    const on = key==='youtube'?SOCIAL.youtube : key==='facebook'?SOCIAL.facebook : SOCIAL.snapchat;
    toast(labels[key]+' '+(on?'blocked':'allowed'));
  }
  function openSocialApps(){ refreshAppPicker('sheetSocialApps'); document.getElementById('scrim').classList.add('show'); document.getElementById('sheetSocialApps').classList.add('show'); }
  function filterSocialApps(q){
    const v=(q||'').trim().toLowerCase();
    document.querySelectorAll('#socialAppList .app-group').forEach(g=>{
      let any=false;
      g.querySelectorAll('.li').forEach(r=>{
        const t=(r.querySelector('.t').textContent+' '+(r.querySelector('.s')||{textContent:''}).textContent).toLowerCase();
        const hit=!v||t.includes(v);
        r.style.display=hit?'':'none';
        if(hit) any=true;
      });
      g.style.display=any?'':'none';
    });
  }
  function selectAllSocialApps(){
    const q=(document.getElementById('socialAppSearch')||{}).value||'';
    APPS.forEach(a=>SOCIAL.wholeBlocked.add(a.name));
    renderAppPicker(document.getElementById('socialAppList'),'multi',SOCIAL.wholeBlocked,q);
    updatePickerCounts(); renderSocial(); saveSocial(); toast('All apps blocked (whole-app)');
  }
  function deselectAllSocialApps(){
    const q=(document.getElementById('socialAppSearch')||{}).value||'';
    SOCIAL.wholeBlocked.clear();
    renderAppPicker(document.getElementById('socialAppList'),'multi',SOCIAL.wholeBlocked,q);
    updatePickerCounts(); renderSocial(); saveSocial(); toast('Cleared whole-app blocks');
  }
  function socialAppsDone(){
    saveSocial(); renderSocial(); closeSheets();
    toast(SOCIAL.wholeBlocked.size ? SOCIAL.wholeBlocked.size+' app'+(SOCIAL.wholeBlocked.size===1?'':'s')+' blocked (whole-app)' : 'No whole-app blocks');
  }
  function socialRemoveApp(name){
    SOCIAL.wholeBlocked.delete(name);
    saveSocial(); renderSocial(); toast(name+' — unblocked');
  }
  loadSocial();

  // ---------- DNS & VPN ----------
  let vpnPreset='Cloudflare Family', dnsV4='1.1.1.1', dnsV6='', vpnWhitelist=[], vpnNotif='Default';
  function saveDns(){
    const v4=document.getElementById('dnsIpv4').value.trim();
    const v6=document.getElementById('dnsIpv6').value.trim();
    if(!/^(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)(\.(25[0-5]|2[0-4]\d|1\d\d|[1-9]?\d)){3}$/.test(v4)){ toast('Enter a valid IPv4 address'); return; }
    if(v6 && !/^([0-9a-fA-F]{0,4}:){2,7}[0-9a-fA-F]{0,4}$/.test(v6)){ toast('Enter a valid IPv6 address'); return; }
    dnsV4=v4; dnsV6=v6;
    const cs=document.getElementById('dnsCustomSub'); if(cs) cs.textContent='IPv4 '+v4+(v6?' · IPv6 '+v6:'');
    vpnStatus(); closeSheets(); toast('Custom DNS saved');
  }
  function selectAllVpnApps(){
    const q=(document.getElementById('vpnAppSearch')||{}).value||'';
    APPS.forEach(a=>vpnPickSel.add(a.name));
    renderAppPicker(document.getElementById('vpnAppList'),'multi',vpnPickSel,q);
    updatePickerCounts();
    toast('All apps selected');
  }
  function deselectAllVpnApps(){
    const q=(document.getElementById('vpnAppSearch')||{}).value||'';
    vpnPickSel.clear();
    renderAppPicker(document.getElementById('vpnAppList'),'multi',vpnPickSel,q);
    updatePickerCounts();
    toast('Deselected all apps');
  }
  function vpnAppsDone(){
    vpnWhitelist=[...vpnPickSel];
    const sub=document.getElementById('vpnWhitelistSub'); if(sub) sub.textContent=vpnWhitelist.length?vpnWhitelist.length+' app'+(vpnWhitelist.length===1?'':'s')+' exempt':'No apps exempt';
    closeSheets(); toast(vpnWhitelist.length?vpnWhitelist.length+' app'+(vpnWhitelist.length===1?'':'s')+' whitelisted':'Whitelist cleared'); vpnStatus();
  }
  function filterVpnApps(q){
    const v=(q||'').trim().toLowerCase();
    document.querySelectorAll('#vpnAppList .app-group').forEach(g=>{
      let any=false;
      g.querySelectorAll('.li').forEach(r=>{
        const t=(r.querySelector('.t').textContent+' '+(r.querySelector('.s')||{textContent:''}).textContent).toLowerCase();
        const hit=!v||t.includes(v);
        r.style.display=hit?'':'none';
        if(hit) any=true;
      });
      g.style.display=any?'':'none';
    });
  }
  function setVpnNotif(btn){
    vpnNotif=btn.textContent;
    const w=document.getElementById('vpnNotifWrap'); if(w) w.style.display=vpnNotif==='Custom'?'':'none';
    toast('Notification: '+vpnNotif);
  }
  function vpnStatus(){
    const on=document.getElementById('vpnToggle').classList.contains('on');
    const p=document.getElementById('vpnPill'); if(p){ p.className='pill '+(on?'g':'r'); p.innerHTML='<span class="pdot"></span>'+(on?'Active':'Off'); }
    const tt=document.getElementById('vpnTitle'); if(tt) tt.textContent=on?'VPN filtering is on':'VPN filtering is off';
    const s=document.getElementById('vpnSub');
    if(s){
      const base=vpnPreset==='Custom preset'?('Custom · '+dnsV4+(dnsV6?' · '+dnsV6:'')):vpnPreset;
      s.textContent=on?(base+' · '+vpnWhitelist.length+' exempt'):'Tap to re-enable protection';
    }
  }
  vpnStatus();

  // ---------- Accessibility status ----------
  // ---------- DEMO state (SAD-319 Phase 3) ----------
  // One object drives every prototype state so each production variant is
  // reachable from the toolbar instead of being hardcoded in markup.
  const DEMO = {
    home: {
      layers: 8, total: 10, attention: 2,
      paused: false,
      a11y: 'off',                  // 'off' | 'checking' | 'on'
      greeting: 'live',             // 'live' -> derived from the clock, like HomeViewModel
      blockedToday: 0,
      schedules: 0
    }
  };
  const DEMO_STR = {
    protected: 'Protected', paused: 'Paused', attention: 'Attention',
    full: 'Fully protected', partial: 'Partially protected', pausedTitle: 'Protection paused',
    resume: 'Turn on master protection to resume',
    morning: 'Good morning', afternoon: 'Good afternoon', evening: 'Good evening',
    userName: 'Alex'
  };
  /** Mirrors HomeViewModel.currentGreeting(): 5..11 morning, 12..16 afternoon, else evening. */
  function clockGreeting(){
    const h=new Date().getHours();
    return h>=5&&h<=11 ? 'morning' : (h>=12&&h<=16 ? 'afternoon' : 'evening');
  }
  /** Mirrors HomeViewModel.currentDateLine(): SimpleDateFormat("EEEE, h:mm a"). */
  function clockDateLine(){
    const d=new Date();
    const day=d.toLocaleDateString('en-US',{weekday:'long'});
    let h=d.getHours()%12; if(h===0) h=12;
    const mm=String(d.getMinutes()).padStart(2,'0');
    return day+', '+h+':'+mm+' '+(d.getHours()<12?'AM':'PM');
  }
  function setDemo(key, value){
    const d = DEMO.home;
    if(key === 'hero'){
      if(value === 'full'){ d.paused = false; d.attention = 0; d.layers = d.total; }
      if(value === 'partial'){ d.paused = false; d.layers = 8; d.attention = 2; }
      if(value === 'paused'){ d.paused = true; }
    }
    if(key === 'a11y'){ d.a11y = value; /* keep the simulated service flag in step */ a11yOn = value === 'on'; }
    if(key === 'greeting') d.greeting = value;
    renderHome();
  }

  /** Home: title / subtitle / ring / pill / banner, exactly as HomeViewModel composes them. */
  function renderHome(){
    const d = DEMO.home;
    const paused = d.paused;
    const clean = d.attention === 0;
    const title = paused ? DEMO_STR.pausedTitle : (clean ? DEMO_STR.full : DEMO_STR.partial);
    const sub = paused ? DEMO_STR.resume
      : (clean ? 'All ' + d.total + ' layers active'
               : d.layers + ' of ' + d.total + ' layers active · ' + d.attention + ' need attention');

    const t = document.getElementById('homeHeroTitle'); if(t) t.textContent = title;
    const s = document.getElementById('homeHeroSub');   if(s) s.textContent = sub;

    const ring = document.getElementById('homeHeroRing');
    if(ring){
      // HomeViewModel: heroProgress = if (paused) 0f else layers.progress
      const progress = paused ? 0 : (d.total ? d.layers / d.total : 0);
      ring.setAttribute('stroke-dashoffset', String(Math.round(163.4 * (1 - progress) * 10) / 10));
    }

    // pill: paused → amber "Paused"; clean → green "Protected"; else amber "Attention"
    const pill = document.getElementById('homePill');
    if(pill){
      const green = !paused && clean;
      pill.className = 'pill ' + (green ? 'g' : 'o');
      const label = paused ? DEMO_STR.paused : (green ? DEMO_STR.protected : DEMO_STR.attention);
      pill.innerHTML = '<span class="pdot"></span>' + label;
    }

    const g = document.getElementById('homeGreeting');
    if(g){
      const which = d.greeting === 'live' ? clockGreeting() : d.greeting;
      g.textContent = DEMO_STR[which] + ', ' + DEMO_STR.userName;
    }
    const dl = document.getElementById('homeDate');
    if(dl) dl.textContent = clockDateLine();

    // MasterProtectionCard(enabled = state.masterProtection) — paused == switch off
    const master = document.getElementById('homeMaster');
    if(master) master.classList.toggle('on', !paused);

    // banner renders only when accessibility is known-off (HomeScreen.kt:129)
    const banner = document.getElementById('a11yBanner');
    if(banner) banner.style.display = d.a11y === 'off' ? 'flex' : 'none';

    const blocked = document.getElementById('homeBlocked');
    if(blocked) blocked.textContent = String(d.blockedToday);
    const sched = document.getElementById('homeSchedules');
    if(sched) sched.textContent = String(d.schedules);
  }

  let a11yOn=false;
  function openA11y(){
    toast('Opening: Accessibility → SafeMe…');
    setTimeout(()=>{ a11yOn=true; DEMO.home.a11y='on';
      const sel=document.getElementById('stA11y'); if(sel) sel.value='on';
      a11yStatus(); renderHome(); toast('Accessibility service enabled'); }, 1600);
  }
  function a11yStatus(){
    const b=document.getElementById('a11yBanner');
    const on=a11yOn;
    if(b && document.getElementById('sc-home')){ b.setAttribute('data-a11y', on?'on':'off'); }
    if(b){
      b.style.transition='max-height .3s ease, opacity .3s ease, padding .3s ease, margin .3s ease, border-width .3s ease';
      b.style.overflow='hidden';
      if(on){
        b.style.maxHeight='0'; b.style.opacity='0'; b.style.paddingTop='0'; b.style.paddingBottom='0'; b.style.borderWidth='0'; b.style.marginTop='0'; b.style.pointerEvents='none';
        b.style.background='rgba(34,181,115,.07)'; b.style.borderColor='var(--success)';
      } else {
        b.style.maxHeight='90px'; b.style.opacity='1'; b.style.paddingTop=''; b.style.paddingBottom=''; b.style.borderWidth=''; b.style.marginTop='12px'; b.style.pointerEvents='';
        b.style.background='rgba(244,67,54,.07)'; b.style.borderColor='var(--danger)';
      }
    }
    const ic=document.getElementById('a11yIc'); if(ic) ic.className='ic '+(on?'g':'r');
    const p=document.getElementById('a11yPill'); if(p){ p.className='pill '+(on?'g':'r'); p.innerHTML='<span class="pdot"></span>'+(on?'Enabled':'Disabled'); }
    const tt=document.getElementById('a11yTitle'); if(tt) tt.textContent=on?'Accessibility service is on':'Accessibility service is off';
    const s=document.getElementById('a11ySub'); if(s) s.textContent=on?'Core blocking engine active':"SafeMe can't detect blocked content until you enable it";
  }

  // ---------- Toast ----------
  function toast(msg){
    const box=document.getElementById('toasts');
    const t=document.createElement('div'); t.className='toast'; t.textContent=msg;
    box.appendChild(t);
    requestAnimationFrame(()=>t.classList.add('show'));
    setTimeout(()=>{ t.classList.remove('show'); setTimeout(()=>t.remove(),300); }, 2400);
  }


  // ---------- Keyword Manager (production layout) ----------
  const KW = { keywords:{blocklist:[], whitelist:[]}, websites:{blocked:[], trusted:[]} };
  let kwSelType='keywords', kwSelTab='blocklist', kwSource='manage', kwEditing=null;
  const KWCAT = {};
  /* Block screen's Manage card: production navigates (keywords?type=..&tab=..),
     it does not open a sheet. */
  function kwGo(type){ kwType(type); nav('keywords'); }
  const KW_EMPTY = {
    'keywords|blocklist':'No custom keywords yet. Add one to get started.',
    'keywords|whitelist':'No whitelisted keywords.',
    'websites|blocked':'No custom websites blocked yet.',
    'websites|trusted':'No trusted websites.'
  };
  function kwOverride(){ return kwSelTab==='whitelist'||kwSelTab==='trusted'; }
  function kwLabels(){
    const note=document.getElementById('kwNote');
    if(note){
      note.style.display = kwOverride()?'block':'none';
      note.textContent = kwSelTab==='trusted' ? 'Trusted websites override the blocked list.' : 'Whitelist keywords override the blocklist.';
    }
    const lbl=document.getElementById('kwAddLabel');
    if(lbl) lbl.textContent = kwOverride()
      ? (kwSelType==='keywords'?'Add whitelist':'Add trusted')
      : (kwSelType==='keywords'?'Add keyword':'Add website');
    const q=document.getElementById('kwQuery');
    if(q) q.placeholder = kwSelType==='keywords' ? 'Search keywords…' : 'Search websites…';
  }
  function kwType(t){
    kwSelType = t;
    kwSelTab  = t==='keywords' ? 'blocklist' : 'blocked';
    document.querySelectorAll('[data-seg="kwtype"] button').forEach(b=>b.classList.toggle('on', b.dataset.val===t));
    document.querySelectorAll('[data-seg="kwtab"] button').forEach(b=>{
      b.classList.toggle('hidden', b.dataset.for!==t);
      b.classList.toggle('on', b.dataset.val===kwSelTab);
    });
    const q=document.getElementById('kwQuery'); if(q) q.value='';
    kwLabels(); kwRender();
  }
  function kwTab(tab){
    kwSelTab = tab;
    document.querySelectorAll('[data-seg="kwtab"] button').forEach(b=>b.classList.toggle('on', b.dataset.val===tab));
    kwLabels(); kwRender();
  }
  function kwRender(){
    const q=(document.getElementById('kwQuery').value||'').trim().toLowerCase();
    const items=(KW[kwSelType][kwSelTab]||[]).filter(v=>v.toLowerCase().indexOf(q)>=0);
    const host=document.getElementById('kwList');
    if(host) host.innerHTML = items.map(kwRowHTML).join('');
    const empty=document.getElementById('kwEmpty');
    if(empty){
      empty.style.display = items.length?'none':'block';
      empty.textContent = KW_EMPTY[kwSelType+'|'+kwSelTab];
    }
    kwMgCard();
  }
  function kwMgCard(){
    const s2=document.getElementById('mgCardSub');
    if(s2) s2.textContent = KW.keywords.blocklist.length+' keywords · '+KW.websites.blocked.length+' websites · '+KW.websites.trusted.length+' trusted';
  }
  function kwRowHTML(v){
    const art = kwSelType==='keywords'
      ? '<path d="M4 9h16M4 15h16M10 3L8 21M16 3l-2 18"/>'
      : '<circle cx="12" cy="12" r="9"/><path d="M12 2a14.5 14.5 0 000 20 14.5 14.5 0 000-20M2 12h20"/>';
    const sub = kwOverride() ? (kwSelType==='keywords'?'Whitelisted':'Trusted') : ((KWCAT[v]||'Custom')+' · blocked');
    return '<div class="kw-row" data-val="'+v+'"><div class="ic '+(kwOverride()?'g':'r')+'"><svg viewBox="0 0 24 24">'+art+'</svg></div>'
      + '<div class="tx"><div class="t">'+v+'</div><div class="s">'+sub+'</div></div>'
      + '<button class="act" onclick="kwEdit(this)"><svg viewBox="0 0 24 24"><path d="M17 3a2.8 2.8 0 1 1 4 4L7.5 20.5 2 22l1.5-5.5z"/></svg></button>'
      + '<button class="act" style="color:var(--danger)" onclick="kwRemove(this)"><svg viewBox="0 0 24 24"><path d="M4 7h16M9 7V5h6v2M7 7l1 13h8l1-13"/></svg></button></div>';
  }
  function kwAdd(){ kwEditing=null; kwSource='screen'; openSheet(kwSelType==='keywords'?'sheetKw':'sheetSite'); }
  function kwEdit(btn){
    const row=btn.closest('.kw-row'); if(!row) return;
    kwEditing=row.dataset.val; kwSource='screen';
    openSheet(kwSelType==='keywords'?'sheetKw':'sheetSite');
    const inp=document.getElementById(kwSelType==='keywords'?'kwInput':'siteInput');
    if(inp) inp.value=kwEditing;
  }
  function kwRemove(btn){
    const row=btn.closest('.kw-row'); if(!row) return;
    const v=row.dataset.val;
    KW[kwSelType][kwSelTab] = KW[kwSelType][kwSelTab].filter(x=>x!==v);
    delete KWCAT[v];
    kwRender(); toast('Deleted');
  }
  function kwSave(v, cat){
    const list = KW[kwSelType][kwSelTab];
    if(kwEditing){
      const i = list.indexOf(kwEditing);
      if(i>=0){ list[i]=v; delete KWCAT[kwEditing]; }
      if(cat) KWCAT[v]=cat;
      kwEditing=null; kwRender(); toast('Updated'); return true;
    }
    if(list.indexOf(v)>=0){ toast('Already on the list'); return false; }
    list.push(v); if(cat) KWCAT[v]=cat; kwRender();
    toast((kwSelType==='keywords'?'Keyword':'Website')+' added to '+kwSelTab);
    return true;
  }

  // ---------- Activity log (Home feed + History) ----------
  const ACTIVITY = [];
  function actAdd(type,title,sub,time){
    const last = ACTIVITY[0];
    if(last && last.type===type && last.title===title) return;
    ACTIVITY.unshift({type:type,title:title,sub:sub,time:time});
    if(ACTIVITY.length>50) ACTIVITY.length=50;
    feedRender(); histRender();
  }
  function actDot(t){
    return ({block:'var(--brand)',schedule:'var(--success)',vpn:'var(--warning)',a11y:'var(--danger)'})[t] || 'var(--ink-3)';
  }
  function feedRender(){
    const host=document.getElementById('homeFeed'), empty=document.getElementById('homeFeedEmpty');
    if(!host) return;
    host.innerHTML = ACTIVITY.slice(0,3).map(e=>
      '<div class="f"><span class="fd" style="background:'+actDot(e.type)+'"></span><div class="ft">'
      + '<div class="t">'+e.title+'</div><div class="s">'+e.sub+'</div><div class="tm">'+e.time+'</div></div></div>').join('');
    if(empty) empty.style.display = ACTIVITY.length?'none':'block';
  }
  function histRender(){
    const host=document.getElementById('histList'), empty=document.getElementById('histEmpty');
    if(!host) return;
    host.innerHTML = ACTIVITY.map(e=>
      '<div style="display:flex;gap:12px;padding:12px 0;border-bottom:1px solid var(--line)">'
      + '<span style="width:10px;height:10px;border-radius:50%;margin-top:5px;flex:none;background:'+actDot(e.type)+'"></span>'
      + '<div style="flex:1"><div style="font-size:14px;font-weight:600">'+e.title+'</div>'
      + '<div style="font-size:12px;color:var(--ink-2);margin-top:2px">'+e.sub+'</div>'
      + '<div style="font-size:11px;color:var(--ink-3);margin-top:3px">'+e.time+'</div></div></div>').join('');
    if(empty) empty.style.display = ACTIVITY.length?'none':'block';
  }

  // ---------- Schedule banners / Accessibility protection ----------
  function dismissBanner(btn){ const b=btn.closest('.banner'); if(b) b.style.display='none'; }
  function apToggleAdb(btn){
    const box=document.getElementById('apAdb'); if(!box) return;
    const show = box.style.display==='none';
    box.style.display = show?'block':'none';
    btn.textContent = show?'Hide instructions':'Show ADB setup instructions';
  }
  function svcSearch(inp){
    const q=(inp.value||'').trim().toLowerCase(); let n=0;
    document.querySelectorAll('#svcList .svc-row').forEach(r=>{
      const hit=!q||r.dataset.name.toLowerCase().indexOf(q)>=0;
      r.style.display=hit?'':'none'; if(hit) n++;
    });
    const e=document.getElementById('svcEmpty'); if(e) e.style.display=n?'none':'block';
  }

  // ---------- Quick actions ----------
  const QA_POOL=[
    {id:'keyword', t:'Add keyword',  s:'Block more sites'},
    {id:'schedule',t:'New schedule', s:'Recurring window'},
    {id:'backup',  t:'Backup',       s:'Export to JSON'},
    {id:'applock', t:'App lock',     s:'Protect apps'},
    {id:'websites',t:'Websites',     s:'Block sites'},
    {id:'vpn',     t:'VPN',          s:'Internet filtering'},
    {id:'history', t:'History',      s:'View activity'}
  ];
  const QA_ICON={
    keyword:'M4 9h16M4 15h16M10 3L8 21M16 3l-2 18',
    schedule:'M4 5h16v16H4z|M4 10h16M9 3v4M15 3v4',
    backup:'M21 15v4a2 2 0 01-2 2H5a2 2 0 01-2-2v-4M7 10l5 5 5-5M12 15V3',
    applock:'M6 11h12v9H6z|M9 11V8a3 3 0 016 0v3',
    websites:'M12 2a14.5 14.5 0 000 20 14.5 14.5 0 000-20M2 12h20',
    vpn:'M12 2l8 3v5c0 5-3 9-8 12-5-3-8-7-8-12V5z|M9 12l2 2 4-4',
    history:'M12 7v5l3 2|M21 12a9 9 0 11-3-6.7'
  };
  const QA_TARGET={keyword:'keywords',schedule:'scheduleedit',backup:'backup',applock:'applock',websites:'keywords',vpn:'vpn',history:'history'};
  let qaOn=['keyword','schedule','backup','applock'];
  function qaIcon(id){ return (QA_ICON[id]||'').split('|').map(d=>'<path d="'+d+'"/>').join(''); }
  function qaRow(a,on,i,total){
    const arrows = '<button class="act" onclick="qaMove('+i+',-1)" '+(i===0?'disabled style="opacity:.3"':'')+'><svg viewBox="0 0 24 24"><path d="M6 15l6-6 6 6"/></svg></button>'
      + '<button class="act" onclick="qaMove('+i+',1)" '+(i===total-1?'disabled style="opacity:.3"':'')+'><svg viewBox="0 0 24 24"><path d="M6 9l6 6 6-6"/></svg></button>'
      + '<button class="act" style="color:var(--danger)" onclick="qaRemove('+i+')"><svg viewBox="0 0 24 24"><path d="M6 6l12 12M18 6L6 18"/></svg></button>';
    const add = '<button class="act" onclick="qaAdd(\''+a.id+'\')"><svg viewBox="0 0 24 24"><path d="M12 5v14M5 12h14"/></svg></button>';
    return '<div class="kw-row" style="border-radius:16px;padding:12px 14px;margin-bottom:8px">'
      + '<div class="ic" style="width:36px;height:36px;border-radius:11px"><svg viewBox="0 0 24 24" style="width:18px;height:18px">'+qaIcon(a.id)+'</svg></div>'
      + '<div class="tx"><div class="t">'+a.t+'</div><div class="s">'+a.s+'</div></div>' + (on?arrows:add) + '</div>';
  }
  function qaRender(){
    const on=qaOn.map(id=>QA_POOL.filter(a=>a.id===id)[0]).filter(Boolean);
    const hidden=QA_POOL.filter(a=>qaOn.indexOf(a.id)<0);
    const onHost=document.getElementById('qaOn'), addHost=document.getElementById('qaAdd'), empty=document.getElementById('qaEmpty');
    if(onHost) onHost.innerHTML = on.map((a,i)=>qaRow(a,true,i,on.length)).join('');
    if(addHost) addHost.innerHTML = hidden.map(a=>qaRow(a,false)).join('');
    if(empty) empty.style.display = on.length?'none':'block';
    homeQuickRender();
  }
  function qaMove(i,d){ const j=i+d; if(j<0||j>=qaOn.length) return; const t=qaOn[i]; qaOn[i]=qaOn[j]; qaOn[j]=t; qaRender(); }
  function qaRemove(i){ qaOn.splice(i,1); qaRender(); }
  function qaAdd(id){ if(qaOn.indexOf(id)<0) qaOn.push(id); qaRender(); }
  function qaReset(){ qaOn=['keyword','schedule','backup','applock']; qaRender(); toast('Restore default actions'); }
  function homeQuickRender(){
    const grid=document.querySelector('#sc-home .grid2'); if(!grid) return;
    if(!qaOn.length){
      grid.innerHTML='<div style="grid-column:1/-1;font-size:12.5px;line-height:18px;color:var(--ink-2)">No quick actions yet — tap Edit to add some</div>';
      return;
    }
    grid.innerHTML = qaOn.map(id=>{
      const a=QA_POOL.filter(x=>x.id===id)[0]; if(!a) return '';
      return '<button class="qa" onclick="nav(\''+QA_TARGET[id]+'\')"><div class="ic"><svg viewBox="0 0 24 24">'+qaIcon(id)+'</svg></div>'
        + '<div class="t">'+a.t+'</div><div class="s">'+a.s+'</div></button>';
    }).join('');
  }

  // ---------- Boot ----------
  (function boot(){
    const onboarded = localStorage.getItem('safeme_onboarded');
    const m = location.hash.match(/#\/s\/(\w+)/);
    const name = (m && document.getElementById('sc-'+m[1])) ? m[1] : null;
    const obScreens = ['welcome','permissions','permbattery','perma11y'];
    if(!onboarded){ show(name || 'welcome'); }
    else if(name && !obScreens.includes(name)){ nav(name); }
    else { show('home'); }
    // permissions init
    permStatus();
    kwType('keywords');
    qaRender();
    renderHome();
    setInterval(()=>{
      if(DEMO.home.greeting==='live') renderHome();
    }, 60000);
    feedRender();
    histRender();
    renderSocial();
  })();
