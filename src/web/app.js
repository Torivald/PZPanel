'use strict';
const TOKEN = document.querySelector('meta[name=pzp-token]').content;
const $ = s => document.querySelector(s);
const $$ = s => [...document.querySelectorAll(s)];
const esc = s => String(s ?? '').replace(/[&<>"']/g, c => ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]));
const q = s => '"' + String(s).replace(/"/g, "'") + '"';

async function api(path, body) {
  const opt = { headers: { 'X-PZP': TOKEN } };
  if (body !== undefined) { opt.method = 'POST'; opt.headers['Content-Type'] = 'application/json'; opt.body = JSON.stringify(body); }
  const r = await fetch(path, opt);
  if (r.status === 401) { showLogin(); throw new Error('Нужен вход'); }
  const j = await r.json().catch(() => ({}));
  if (!r.ok) throw new Error(j.error || ('HTTP ' + r.status));
  return j;
}
function toast(msg, err) {
  const t = $('#toast'); t.textContent = msg; t.className = 'toast' + (err ? ' err' : '');
  clearTimeout(toast._t); toast._t = setTimeout(() => t.classList.add('hidden'), err ? 7000 : 3500);
}
async function act(fn) { try { const r = await fn(); if (r && r.message) toast(r.message); return r; } catch (e) { toast(e.message, true); } }
const fmtDur = s => { s = Math.floor(s); const d = Math.floor(s/86400), h = Math.floor(s%86400/3600), m = Math.floor(s%3600/60); return (d ? d+'д ' : '') + (h ? h+'ч ' : '') + m + 'м'; };
const fmtTime = ms => ms ? new Date(ms).toLocaleString('ru-RU') : '—';
const fmtSize = b => b < 0 ? '…' : b > 1<<30 ? (b/(1<<30)).toFixed(1)+' ГБ' : (b/(1<<20)).toFixed(0)+' МБ';
const ago = ms => { if (!ms) return '—'; const s = (Date.now()-ms)/1000; return s < 60 ? 'только что' : s < 3600 ? Math.floor(s/60)+' мин назад' : s < 86400 ? Math.floor(s/3600)+' ч назад' : Math.floor(s/86400)+' д назад'; };

// ---------------- login ----------------
function showLogin() { $('#login').classList.remove('hidden'); }
$('#loginForm').addEventListener('submit', async e => {
  e.preventDefault();
  const r = await fetch('/api/login', { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ password: $('#loginPass').value }) });
  if (r.ok) location.reload(); else $('#loginErr').textContent = 'Неверный пароль';
});
if (document.querySelector('meta[name=pzp-login]').content === 'true') showLogin();

// ---------------- tabs ----------------
let tab = location.hash.slice(1).split('@')[0] || 'overview';
const loaders = {};
function openTab(t) {
  tab = t; if (location.hash.slice(1).split('@')[0] !== t) location.hash = t;
  $$('#nav a').forEach(a => a.classList.toggle('active', a.dataset.tab === t));
  $$('.tab').forEach(s => s.classList.toggle('hidden', s.id !== 'tab-' + t));
  if (loaders[t]) loaders[t]();
}
$$('#nav a').forEach(a => a.addEventListener('click', () => openTab(a.dataset.tab)));
window.addEventListener('beforeunload', e => { if (modsDirty || dirtyForms()) { e.preventDefault(); e.returnValue = ''; } });

// ---------------- status ----------------
let status = {};
async function refreshStatus() {
  try { status = await api('/api/status'); } catch (e) { $('#stateText').textContent = 'панель недоступна'; return; }
  const st = status.state, running = status.running;
  const label = { stopped: 'остановлен', starting: 'запускается', running: 'работает', stopping: 'останавливается', restarting: 'перезапуск' }[st] || st;
  $('#stateText').textContent = label + (running && status.owned === false ? ' (вне панели)' : '');
  $('#stateDot').className = 'dot ' + (st === 'running' ? 'on' : running || st === 'starting' || st === 'restarting' ? 'mid' : '');
  const b = $('#stateBadge'); b.textContent = label; b.className = 'badge ' + (st === 'running' ? 'on' : running || st !== 'stopped' ? 'mid' : 'off');
  $('#srvName').textContent = status.serverName; $('#brandSub').textContent = status.publicName || status.serverName;
  $('#srvInfo').innerHTML = [
    ['Процесс', running ? 'PID ' + status.pid + (status.owned ? ', запущен из панели' : ', запущен вне панели (команды идут через RCON)') : '—'],
    ['Время работы', running && status.uptimeSec ? fmtDur(status.uptimeSec) : '—'],
    ['Порт игры / RCON', (status.gamePort || '16261') + ' / ' + (status.rconPort || '—') + (status.rconEnabled ? '' : ' (RCON выключен: пустой RCONPassword)')],
    ['Последнее действие', status.lastAction || '—'],
    ['Ежедневный перезапуск', status.dailyRestart || 'выключен'],
    ['Конфиг', status.paths ? status.paths.ini : ''],
  ].map(([k, v]) => `<span>${esc(k)}</span><span>${esc(v)}</span>`).join('');
  $('#btnStart').disabled = running || st === 'starting';
  $('#btnStop').disabled = !running; $('#btnKill').disabled = !running; $('#btnSave').disabled = !running;
  $('#stats').innerHTML = [
    [lastPlayers ? lastPlayers.online.length : '—', 'онлайн' + (status.maxPlayers ? ' / ' + status.maxPlayers : '')],
    [lastPlayers ? lastPlayers.accounts.length : '—', 'аккаунтов'],
    [lastPlayers ? lastPlayers.characters.filter(c => !c.dead).length : '—', 'живых персонажей'],
    [status.modsCount, 'модов включено'],
    [status.workshopCount, 'предметов Мастерской'],
    [fmtSize(status.worldSize), 'размер мира'],
    [ago(status.lastSave), 'последнее сохранение'],
    [status.diskFreeGb != null ? status.diskFreeGb + ' ГБ' : '—', 'свободно на диске'],
  ].map(([v, l]) => `<div class="stat"><b>${esc(v)}</b><span>${esc(l)}</span></div>`).join('');
  const bn = [];
  if (status.needsRestart) bn.push(`<div class="banner">Конфиги менялись после запуска сервера — изменения применятся после перезапуска. <button class="btn sm" onclick="restartNow()">Перезапустить</button></div>`);
  if (status.paths && !status.paths.iniExists) bn.push(`<div class="banner err">Не найден конфиг сервера ${esc(status.paths.ini)} — проверьте serverName и пути на вкладке «Панель».</div>`);
  $('#banners').innerHTML = bn.join('');
  $('#zbPrompt').classList.toggle('hidden', !status.waitingInput);
}
window.restartNow = () => act(() => api('/api/server/restart', { warnMinutes: +$('#restartWarn').value }));
$('#btnStart').onclick = () => act(() => api('/api/server/start', {})).then(refreshStatus);
$('#btnStop').onclick = () => confirm('Сохранить мир и остановить сервер?') && act(() => api('/api/server/stop', {})).then(refreshStatus);
$('#btnKill').onclick = () => confirm('Убить процесс сервера БЕЗ сохранения? Несохранённый прогресс пропадёт.') && act(() => api('/api/server/stop', { force: true }));
$('#btnSave').onclick = () => act(() => api('/api/server/save', {}));
$('#btnRestart').onclick = () => confirm('Перезапустить сервер' + (+$('#restartWarn').value ? ' с предупреждением игрокам' : ' сразу') + '?') && restartNow();
$('#btnBroadcast').onclick = () => { const m = prompt('Сообщение всем игрокам:'); if (m) act(() => api('/api/console', { cmd: 'servermsg ' + q(m) })); };

// ---------------- console ----------------
let conCursor = -1, conMode = '';
function lineClass(l) {
  if (l.startsWith('> ')) return 'me';
  if (/^ERROR|Exception|SEVERE/.test(l)) return 'e';
  if (/^WARN/.test(l)) return 'w';
  if (/\[ZB\]|ZombieBuddy/.test(l)) return 'zb';
  return '';
}
async function pollConsole() {
  if (tab !== 'overview') return;
  try {
    const r = await api('/api/console?cursor=' + conCursor);
    const el = $('#console');
    if (r.mode !== conMode || r.reset) { el.innerHTML = ''; conMode = r.mode; }
    $('#consoleMode').textContent = r.mode === 'process' ? '— вывод процесса, запущенного панелью' : '— server-console.txt' + (r.error ? ' (' + r.error + ')' : '');
    if (r.lines.length) {
      const frag = document.createDocumentFragment();
      for (const l of r.lines) { const d = document.createElement('div'); d.textContent = l; const c = lineClass(l); if (c) d.className = c; frag.appendChild(d); }
      el.querySelector('.partial')?.remove();
      el.appendChild(frag);
      while (el.childNodes.length > 3000) el.removeChild(el.firstChild);
    }
    el.querySelector('.partial')?.remove();
    if (r.partial) { const d = document.createElement('div'); d.className = 'partial w'; d.textContent = r.partial; el.appendChild(d); $('#zbPromptText').textContent = r.partial; }
    conCursor = r.cursor;
    if ($('#autoscroll').checked) el.scrollTop = el.scrollHeight;
  } catch (e) {}
}
$('#cmdForm').addEventListener('submit', async e => {
  e.preventDefault();
  const c = $('#cmd').value.trim(); if (!c) return;
  $('#cmd').value = '';
  const r = await act(() => api('/api/console', { cmd: c }));
  if (r && r.output) { const d = document.createElement('div'); d.className = 'me'; d.textContent = '> ' + c + '\n' + r.output; $('#console').appendChild(d); }
  pollConsole();
});
$$('.quick [data-cmd]').forEach(b => b.onclick = () => { $('#cmd').value = b.dataset.cmd; $('#cmdForm').requestSubmit(); });
$$('#zbPrompt [data-answer]').forEach(b => b.onclick = () => act(() => api('/api/console', { cmd: b.dataset.answer })).then(pollConsole));
loaders.overview = () => { pollConsole(); loadPlayers(true); };

// ---------------- players ----------------
let lastPlayers = null;
async function loadPlayers(silent) {
  try { lastPlayers = await api('/api/players'); } catch (e) { if (!silent) toast(e.message, true); return; }
  if (tab === 'players') renderPlayers();
  if (tab === 'map') { renderMapList(); map.draw(); }
}
function renderPlayers() {
  const p = lastPlayers; if (!p) return;
  const f = $('#plFilter').value.toLowerCase();
  $('#plNote').innerHTML = (p.rconOk ? `Онлайн: ${p.online.length}. ` : '<span class="dead-txt">RCON недоступен — онлайн неизвестен (сервер выключен или пустой RCONPassword).</span> ')
    + `Позиции — на момент последнего сохранения (${ago(p.positionsSavedAt)}).` + (!+p.saveEveryMinutes ? ' Автосохранение выключено (SaveWorldEveryMinutes=0) — позиции обновляются при выходе игрока или команде save. Для живой карты поставьте SaveWorldEveryMinutes=5 во вкладке «Настройки сервера».' : ` Автосохранение каждые ${esc(p.saveEveryMinutes)} мин.`)
    + (p.errors.length ? ' <span class="dead-txt">' + esc(p.errors.join('; ')) + '</span>' : '');
  const rows = p.accounts.filter(a => !f || JSON.stringify(a).toLowerCase().includes(f)).sort((a, b) => (b.online - a.online) || String(b.lastConnection || '').localeCompare(String(a.lastConnection || '')));
  $('#plTable').innerHTML = '<tr><th>Ник</th><th>Статус</th><th>Роль</th><th>Последний вход</th><th>Персонаж</th><th>Позиция</th><th>Steam</th><th></th></tr>' + rows.map(a => {
    const ch = (a.characters || []);
    const live = ch.find(c => !c.dead) || ch[0];
    return `<tr><td><b>${esc(a.username)}</b>${a.displayName ? '<br><small class="muted">' + esc(a.displayName) + '</small>' : ''}</td>
      <td>${a.online ? '<span class="on-txt">● онлайн</span>' : '<span class="muted">оффлайн</span>'}</td>
      <td>${esc(a.role || '—')}</td><td>${esc(a.lastConnection || '—')}</td>
      <td>${ch.map(c => esc(c.character) + (c.dead ? ' <span class="dead-txt">(мёртв)</span>' : '')).join('<br>') || '—'}</td>
      <td>${live ? `<a href="#" onclick="showOnMap(${live.x},${live.y});return false">${Math.round(live.x)}, ${Math.round(live.y)}${live.z ? ', ' + Math.round(live.z) + ' эт.' : ''}</a>` : '—'}</td>
      <td>${a.steamid ? `<a href="https://steamcommunity.com/profiles/${esc(a.steamid)}" target="_blank" rel="noopener">профиль</a>` : '—'}</td>
      <td><div class="row gap wrap">${a.online ? `<button class="btn sm" onclick="plCmd('kickuser',${esc(JSON.stringify(a.username))})">Кик</button>` : ''}
        <button class="btn sm" onclick="plRole(${esc(JSON.stringify(a.username))})">Роль</button>
        <button class="btn sm danger" onclick="plCmd('banuser',${esc(JSON.stringify(a.username))})">Бан</button></div></td></tr>`;
  }).join('');
}
window.plCmd = (c, u) => confirm(c + ' ' + u + '?') && act(() => api('/api/console', { cmd: c + ' ' + q(u) })).then(() => setTimeout(() => loadPlayers(true), 1500));
window.plRole = u => { const r = prompt('Роль для ' + u + ' (admin, moderator, overseer, gm, observer, user):', 'user'); if (r) act(() => api('/api/console', { cmd: 'setaccesslevel ' + q(u) + ' ' + r })); };
window.showOnMap = (x, y) => { openTab('map'); setTimeout(() => map.center(x, y, 4), 50); };
$('#plFilter').oninput = renderPlayers;
$('#plRefresh').onclick = () => loadPlayers();
loaders.players = () => { renderPlayers(); loadPlayers(); };

// ---------------- map ----------------
const MAPL = {
  towns: ['Города', true], streets: ['Улицы и названия', true], foot: ['Контуры зданий (при приближении)', true],
  heat: ['Плотность зомби (по карте игры)', false], z0: ['Точки появления', false], z1: ['Сюжетные места', false],
  z2: ['Подвалы', false], z3: ['Богатые районы (лут)', false], z4: ['Трейлерные парки', false], z5: ['Фермы и ранчо', false],
};
const ZONE_LAYER = ['z0', 'z1', 'z2', 'z3', 'z4', 'z5', 'z5'];
const ZONE_COL = ['#ffffff', '#ff4fa0', '#a68cff', '#ffd54f', '#7fd8cc', '#c49a7c', '#c49a7c'];
const ZONE_NAME = ['Точка появления', 'Сюжетное место', 'Подвал', 'Богатый район', 'Трейлерный парк', 'Ферма', 'Ранчо'];
const ROOM_RU = {
  pharmacy: 'аптека', pharmacystorage: 'склад аптеки', medical: 'медпункт', medicalstorage: 'медсклад', medclinic: 'клиника', clinic: 'клиника',
  hospitalroom: 'больничная палата', hospitalstorage: 'склад больницы', hospitalhallway: 'больница', dentist: 'стоматология', morgue: 'морг', laboratory: 'лаборатория', vet: 'ветклиника', optometrist: 'оптика',
  policeoffice: 'полиция', policestorage: 'склад полиции', policelocker: 'шкафчики полиции', policegunstorage: 'оружейная полиции', policearchive: 'архив полиции', evidenceroom: 'улики', policeswat: 'спецназ', swatlocker: 'шкафчики SWAT',
  gunstore: 'оружейный магазин', gunstorestorage: 'склад оружейного', gunstorage: 'оружейная', armysurplus: 'армейский магазин', hunting: 'охотничий', knifestore: 'ножи',
  armystorage: 'армейский склад', militarycrate: 'военные ящики', prisoncells: 'тюремные камеры',
  gasstore: 'заправка', gasstorage: 'склад заправки', fossoil: 'заправка Fossoil', gas2go: 'заправка Gas-2-Go',
  grocery: 'продуктовый', grocerystorage: 'склад продуктового', gigamart: 'супермаркет Gigamart', conveniencestore: 'минимаркет', cornerstore: 'магазинчик', generalstore: 'универсам',
  liquorstore: 'алкомаркет', bakery: 'пекарня', butcher: 'мясная лавка', knoxbutcher: 'мясная лавка', zippeestore: 'Zippee Market', candystore: 'сладости',
  toolstore: 'хозяйственный', toolstorage: 'инструменты', garagestorage: 'гараж', garage: 'гараж', shed: 'сарай', warehouse: 'склад', storageunit: 'складская ячейка', factory: 'завод', construction: 'стройка',
  mechanic: 'автомастерская', carsupply: 'автозапчасти', cardealershipoffice: 'автосалон', firestorage: 'пожарная часть', firegarage: 'пожарное депо',
  clothesstore: 'одежда', clothingstore: 'одежда', clothesstorage: 'склад одежды', shoestore: 'обувь', sportstore: 'спорттовары', sportstorage: 'склад спорттоваров', departmentstore: 'универмаг', leatherclothesstore: 'кожа', jewelrystore: 'ювелирный', sewingstore: 'швейный',
  electronicsstore: 'электроника', electronicstore: 'электроника', electronicsstorage: 'склад электроники', camerastore: 'фототовары', movierental: 'видеопрокат', cybercafe: 'интернет-кафе',
  bookstore: 'книжный', library: 'библиотека', universitylibrary: 'библиотека университета', comicstore: 'комиксы',
  restaurant: 'ресторан', restaurantdining: 'ресторан', restaurantkitchen: 'кухня ресторана', bar: 'бар', cafe: 'кафе', diner: 'закусочная', pizzawhirled: 'пиццерия', spiffo_dining: 'Spiffo\'s', burgerdining: 'бургерная', icecream: 'мороженое', brewery: 'пивоварня', stripclub: 'стрип-клуб',
  gym: 'спортзал', boxing: 'бокс', classroom: 'класс', elementaryclassroom: 'начальная школа', secondaryclassroom: 'школа', universityclassroom: 'университет', church: 'церковь', motelroom: 'мотель', motelroomoccupied: 'мотель',
  bank: 'банк', vault: 'хранилище', post: 'почта', farmstorage: 'склад фермы', barn: 'амбар', greenhouse: 'теплица', pawnshop: 'ломбард', toystore: 'игрушки', giftstore: 'сувениры', furniturestore: 'мебель', musicstore: 'музыкальный', petstore: 'зоомагазин', florist: 'цветы',
  cafeteria: 'столовая', cafeteriakitchen: 'кухня столовой', giftstorage: 'склад сувениров', officestorage: 'склад офиса', breakroom: 'комната отдыха', changeroom: 'раздевалка', power: 'электрощитовая', nursery: 'детская палата', restaurantdining_fancy: 'ресторан', restaurantkitchen_fancy: 'кухня ресторана', restaurantkitchen: 'кухня ресторана', grocerystorage: 'склад продуктового', stairwell: 'лестница', hallway: 'коридор', elevator: 'лифт', garbage: 'мусор', balcony: 'балкон', attic: 'чердак', dressingrooms: 'примерочные', lockerroom: 'раздевалка', storagehall: 'склад', clothesstorestorage: 'склад одежды', zippeestorage: 'склад Zippee', gasstoragee: 'склад', policelibrary: 'архив полиции', spiffoskitchen: 'кухня Spiffo\'s', burgerkitchen: 'кухня бургерной', pizzakitchen: 'кухня пиццерии', theatrestorage: 'склад театра', bookstorage: 'склад книг', loggingtruck: 'лесовоз', universityoffice: 'кабинет', universitystorage: 'склад университета', broadcasting: 'телестудия', communications: 'связь', studio: 'студия',
  kitchen: 'кухня', bedroom: 'спальня', kidsbedroom: 'детская', bathroom: 'ванная', livingroom: 'гостиная', diningroom: 'столовая', office: 'офис', laundry: 'прачечная', closet: 'кладовка', hall: 'коридор', empty: 'пусто', emptyoutside: 'двор', storage: 'склад', lobby: 'холл', janitor: 'подсобка', security: 'охрана', theatre: 'театр', bowlingalley: 'боулинг', druglab: 'нарколаборатория',
};
const ruRoom = r => ROOM_RU[r] || r;
const lsGet = (k, d) => { try { const v = localStorage.getItem('pzp.' + k); return v === null ? d : JSON.parse(v); } catch (e) { return d; } };
const lsSet = (k, v) => { try { localStorage.setItem('pzp.' + k, JSON.stringify(v)); } catch (e) {} };
const mapState = {
  layers: Object.assign(Object.fromEntries(Object.entries(MAPL).map(([k, v]) => [k, v[1]])), lsGet('layers', {})),
  cats: lsGet('cats', null), base: lsGet('base', 'game'),
};
let mapData = null, catOn = 0;

const map = (() => {
  const cv = $('#mapCanvas'), ctx = cv.getContext('2d');
  let info = null, cx = 10000, cy = 9000, s = 0.05, w = 0, h = 0;
  const cache = new Map();
  let markers = [], poiHits = [], hover = null, flash = null, selected = null;
  let foot = { rect: null, list: [], pending: false, timer: 0 };
  let heatImg = null, streetBox = [];
  function img(url) {
    let e = cache.get(url);
    if (!e) {
      e = { img: new Image(), ok: false, bad: false };
      e.img.onload = () => { e.ok = true; draw(); };
      e.img.onerror = () => { e.bad = true; };
      e.img.src = url;
      cache.set(url, e);
      if (cache.size > 1200) cache.delete(cache.keys().next().value);
    } else { cache.delete(url); cache.set(url, e); }
    return e;
  }
  const toScreen = (wx, wy) => [(wx - cx) * s + w / 2, (wy - cy) * s + h / 2];
  const toWorld = (sx, sy) => [(sx - w / 2) / s + cx, (sy - h / 2) / s + cy];
  function resize() {
    const r = cv.getBoundingClientRect(), d = devicePixelRatio || 1;
    w = r.width; h = r.height; cv.width = w * d; cv.height = h * d; ctx.setTransform(d, 0, 0, d, 0, 0); draw();
  }
  // --- base: game map (pyramid.zip) ---
  function drawLevel(L) {
    const T = 256 * 2 ** L, [x0, y0, x1, y1] = info.bounds;
    const [wx0, wy0] = toWorld(0, 0), [wx1, wy1] = toWorld(w, h);
    const tx0 = Math.max(0, Math.floor((wx0 - x0) / T)), ty0 = Math.max(0, Math.floor((wy0 - y0) / T));
    const tx1 = Math.min(Math.ceil((x1 - x0) / T) - 1, Math.floor((wx1 - x0) / T)), ty1 = Math.min(Math.ceil((y1 - y0) / T) - 1, Math.floor((wy1 - y0) / T));
    for (let ty = ty0; ty <= ty1; ty++) for (let tx = tx0; tx <= tx1; tx++) {
      const e = img(`/tiles/${L}/${tx}/${ty}.png`);
      const [sx, sy] = toScreen(x0 + tx * T, y0 + ty * T);
      if (e.ok) ctx.drawImage(e.img, sx, sy, T * s + 0.5, T * s + 0.5);
    }
  }
  // --- base: top view (pzmap2dzi base_top) ---
  function drawTopLevel(L, layer) {
    const t = info.top, f = 2 ** (t.skip + t.maxLevel - L), T = t.tileSize;
    const pxOf = (wv, o) => (wv * t.sqr + o) / f, wOf = (pv, o) => (pv * f - o) / t.sqr;
    const [wx0, wy0] = toWorld(0, 0), [wx1, wy1] = toWorld(w, h);
    const wL = Math.ceil(t.w / 2 ** (t.maxLevel - L)), hL = Math.ceil(t.h / 2 ** (t.maxLevel - L));
    const tx0 = Math.max(0, Math.floor(pxOf(wx0, t.x0) / T)), ty0 = Math.max(0, Math.floor(pxOf(wy0, t.y0) / T));
    const tx1 = Math.min(Math.ceil(wL / T) - 1, Math.floor(pxOf(wx1, t.x0) / T)), ty1 = Math.min(Math.ceil(hL / T) - 1, Math.floor(pxOf(wy1, t.y0) / T));
    if ((tx1 - tx0 + 1) * (ty1 - ty0 + 1) > 400) return;
    for (let ty = ty0; ty <= ty1; ty++) for (let tx = tx0; tx <= tx1; tx++) {
      const e = img(`/topview/${layer}/${L}/${tx}_${ty}.${t.ext}`);
      if (!e.ok) continue;
      const [sx, sy] = toScreen(wOf(tx * T, t.x0), wOf(ty * T, t.y0));
      ctx.drawImage(e.img, sx, sy, e.img.naturalWidth * f / t.sqr * s + 0.5, e.img.naturalHeight * f / t.sqr * s + 0.5);
    }
  }
  function drawBase() {
    ctx.imageSmoothingEnabled = s < 1;
    if (mapState.base === 'top' && info.top) {
      const t = info.top, top = t.sqr / 2 ** t.skip;
      const L = Math.max(0, Math.min(t.maxLevel, t.maxLevel - Math.max(0, Math.round(Math.log2(top / s)))));
      const fl = mapState.floor || 0, layers = (t.layers || [0]).filter(x => fl < 0 ? x === fl : x >= 0 && x <= fl);
      if (fl < 0) { for (const LL of [L - 3, L - 1, L]) if (LL >= 0) drawTopLevel(LL, 0); ctx.fillStyle = 'rgba(0,0,0,.6)'; ctx.fillRect(0, 0, w, h); }
      for (const ly of layers) for (const LL of (ly === layers[0] ? [L - 3, L - 1, L] : [L])) if (LL >= 0) drawTopLevel(LL, ly);
    } else {
      const ideal = Math.max(0, Math.min(info.levels, Math.round(Math.log2(1 / s))));
      for (let L = info.levels; L >= ideal; L--) { if (L > ideal + 2) continue; drawLevel(L); }
    }
  }
  // --- overlays ---
  function inView(x0, y0, x1, y1, m = 0) {
    const [a, b] = toScreen(x0, y0), [c, d] = toScreen(x1, y1);
    return c >= -m && d >= -m && a <= w + m && b <= h + m;
  }
  function drawHeat() {
    if (!mapState.layers.heat || !mapData || !mapData.heat) return;
    if (!heatImg) { heatImg = new Image(); heatImg.onload = () => draw(); heatImg.src = '/mapimg/zombies.png'; return; }
    if (!heatImg.complete || !heatImg.naturalWidth) return;
    const hb = mapData.heat, [sx, sy] = toScreen(hb.x0, hb.y0), [ex, ey] = toScreen(hb.x1, hb.y1);
    ctx.save(); ctx.globalAlpha = 0.7; ctx.imageSmoothingEnabled = true; ctx.drawImage(heatImg, sx, sy, ex - sx, ey - sy); ctx.restore();
  }
  function drawZones() {
    if (!mapData) return;
    for (const z of mapData.zones) {
      const [t, , x, y, zw, zh] = z;
      if (!mapState.layers[ZONE_LAYER[t]] || !inView(x, y, x + zw, y + zh, 10)) continue;
      const [sx, sy] = toScreen(x, y);
      if (t === 0) {
        ctx.beginPath(); ctx.moveTo(sx, sy - 6); ctx.lineTo(sx + 6, sy); ctx.lineTo(sx, sy + 6); ctx.lineTo(sx - 6, sy); ctx.closePath();
        ctx.fillStyle = '#fff'; ctx.fill(); ctx.strokeStyle = '#000'; ctx.lineWidth = 1.5; ctx.stroke();
      } else {
        ctx.fillStyle = ZONE_COL[t] + '33'; ctx.strokeStyle = ZONE_COL[t]; ctx.lineWidth = 1.5;
        ctx.fillRect(sx, sy, zw * s, zh * s); ctx.strokeRect(sx, sy, zw * s, zh * s);
      }
    }
  }
  function wantFoot() { return mapState.layers.foot && s >= 0.45; }
  function fetchFoot() {
    const [wx0, wy0] = toWorld(0, 0), [wx1, wy1] = toWorld(w, h);
    const r = foot.rect;
    if (r && wx0 >= r[0] && wy0 >= r[1] && wx1 <= r[2] && wy1 <= r[3]) return;
    clearTimeout(foot.timer);
    foot.timer = setTimeout(async () => {
      const mw = (wx1 - wx0) * 0.5, mh = (wy1 - wy0) * 0.5;
      const q = [wx0 - mw, wy0 - mh, wx1 + mw, wy1 + mh].map(Math.round);
      try {
        const l = await api(`/api/map/buildings?x0=${q[0]}&y0=${q[1]}&x1=${q[2]}&y1=${q[3]}`);
        foot = { rect: q, list: l, timer: 0 }; draw();
      } catch (e) { /* ignore */ }
    }, 200);
  }
  function drawFoot() {
    if (!wantFoot()) return;
    fetchFoot();
    const cats = mapData ? mapData.cats : [];
    for (const [id, cat, rects] of foot.list) {
      const col = cat >= 0 && cats[cat] ? cats[cat].color : '#ffffff';
      const sel = selected && selected.id === id;
      ctx.fillStyle = sel ? col + 'aa' : cat >= 0 ? col + '55' : 'rgba(255,255,255,.16)';
      ctx.strokeStyle = sel ? '#fff' : 'rgba(0,0,0,.55)'; ctx.lineWidth = sel ? 2 : 1;
      for (let i = 0; i < rects.length; i += 4) {
        const [sx, sy] = toScreen(rects[i], rects[i + 1]);
        if (sx > w || sy > h || sx + rects[i + 2] * s < 0 || sy + rects[i + 3] * s < 0) continue;
        ctx.fillRect(sx, sy, rects[i + 2] * s, rects[i + 3] * s);
        if (s >= 1.5 || sel) ctx.strokeRect(sx, sy, rects[i + 2] * s, rects[i + 3] * s);
      }
    }
  }
  function drawStreets() {
    if (!mapState.layers.streets || !mapData || s < 0.2) return;
    ctx.save(); ctx.lineCap = 'round'; ctx.lineJoin = 'round';
    const labels = [];
    mapData.streets.forEach((st, i) => {
      const b = streetBox[i]; if (!inView(b[0], b[1], b[2], b[3], 20)) return;
      const pts = st[2], width = st[1] || 6;
      if (s < 0.5 && width < 8) return;
      ctx.beginPath();
      for (let k = 0; k < pts.length; k += 2) { const [sx, sy] = toScreen(pts[k], pts[k + 1]); k ? ctx.lineTo(sx, sy) : ctx.moveTo(sx, sy); }
      ctx.strokeStyle = 'rgba(255,240,200,.45)'; ctx.lineWidth = Math.max(1.5, width * s * 0.8); ctx.stroke();
      if (s >= 0.9 && st[0]) {
        let best = 0, bk = 0;
        for (let k = 0; k + 3 < pts.length; k += 2) { const d = Math.hypot(pts[k + 2] - pts[k], pts[k + 3] - pts[k + 1]); if (d > best) { best = d; bk = k; } }
        if (best * s > st[0].length * 7) labels.push([st[0], (pts[bk] + pts[bk + 2]) / 2, (pts[bk + 1] + pts[bk + 3]) / 2, Math.atan2(pts[bk + 3] - pts[bk + 1], pts[bk + 2] - pts[bk])]);
      }
    });
    ctx.font = '600 12px Segoe UI, sans-serif'; ctx.textAlign = 'center'; ctx.textBaseline = 'middle';
    const placed = [];
    for (const [name, x, y, a0] of labels) {
      const [sx, sy] = toScreen(x, y);
      if (placed.some(p => Math.hypot(p[0] - sx, p[1] - sy) < 80)) continue;
      placed.push([sx, sy]);
      let a = a0; if (a > Math.PI / 2) a -= Math.PI; if (a < -Math.PI / 2) a += Math.PI;
      ctx.save(); ctx.translate(sx, sy); ctx.rotate(a);
      ctx.lineWidth = 3; ctx.strokeStyle = 'rgba(0,0,0,.8)'; ctx.strokeText(name, 0, 0); ctx.fillStyle = '#ffe9b0'; ctx.fillText(name, 0, 0);
      ctx.restore();
    }
    ctx.restore();
  }
  function drawPois() {
    poiHits = [];
    if (!mapData) return;
    const cats = mapData.cats, rt = mapData.roomTypes;
    const big = s >= 0.2, lab = s >= 1.1;
    ctx.save(); ctx.textAlign = 'center'; ctx.textBaseline = 'middle';
    for (const p of mapData.pois) {
      const [id, cat, x, y, , mask] = p;
      if (!(mask & catOn) && !((1 << cat) & catOn)) continue;
      const [sx, sy] = toScreen(x, y);
      if (sx < -20 || sy < -20 || sx > w + 20 || sy > h + 20) continue;
      const c = cats[cat];
      if (!big) { ctx.fillStyle = c.color; ctx.fillRect(sx - 1.5, sy - 1.5, 3, 3); poiHits.push({ p, sx, sy }); continue; }
      ctx.beginPath(); ctx.arc(sx, sy, 8, 0, Math.PI * 2); ctx.fillStyle = c.color; ctx.fill();
      ctx.lineWidth = selected && selected.id === id ? 3 : 1.5; ctx.strokeStyle = selected && selected.id === id ? '#fff' : '#111'; ctx.stroke();
      ctx.font = '700 10px Segoe UI, sans-serif'; ctx.fillStyle = '#111'; ctx.fillText(c.icon, sx, sy + 0.5);
      if (lab) {
        const t = ruRoom(rt[p[6][0]]);
        ctx.font = '600 11px Segoe UI, sans-serif'; ctx.lineWidth = 3; ctx.strokeStyle = 'rgba(0,0,0,.8)'; ctx.strokeText(t, sx, sy + 17); ctx.fillStyle = '#fff'; ctx.fillText(t, sx, sy + 17);
      }
      poiHits.push({ p, sx, sy });
    }
    ctx.restore();
  }
  function drawTowns() {
    if (!mapState.layers.towns || !mapData || s > 3) return;
    ctx.save(); ctx.textAlign = 'center'; ctx.textBaseline = 'middle';
    const fs = Math.round(Math.max(14, Math.min(26, 12 + s * 40)));
    ctx.font = `700 ${fs}px Segoe UI, sans-serif`;
    for (const [name, x, y] of mapData.towns) {
      const [sx, sy] = toScreen(x, y);
      if (sx < -200 || sy < -50 || sx > w + 200 || sy > h + 50) continue;
      ctx.lineWidth = 5; ctx.strokeStyle = 'rgba(0,0,0,.8)'; ctx.strokeText(name, sx, sy); ctx.fillStyle = '#fff6d6'; ctx.fillText(name, sx, sy);
    }
    ctx.restore();
  }
  function drawMarkers() {
    markers = [];
    const p = lastPlayers; if (!p) return;
    const showOn = $('#mOnline').checked, showOff = $('#mOffline').checked, showDead = $('#mDead').checked, labels = $('#mLabels').checked;
    for (const c of p.characters) {
      if (c.dead ? !showDead : c.online ? !showOn : !showOff) continue;
      const [sx, sy] = toScreen(c.x, c.y);
      if (sx < -20 || sy < -20 || sx > w + 20 || sy > h + 20) continue;
      markers.push({ c, sx, sy });
      ctx.beginPath(); ctx.arc(sx, sy, c.online ? 8 : 6, 0, Math.PI * 2);
      ctx.fillStyle = c.dead ? '#d0574a' : c.online ? '#8fc04a' : '#5aa5d6';
      ctx.fill(); ctx.lineWidth = 2.5; ctx.strokeStyle = '#111'; ctx.stroke();
      if (labels) {
        const t = c.username + (c.character ? ' · ' + c.character : '');
        ctx.font = '700 12px Segoe UI, sans-serif'; ctx.lineWidth = 3; ctx.strokeStyle = 'rgba(0,0,0,.85)';
        ctx.textAlign = 'left'; ctx.textBaseline = 'alphabetic';
        ctx.strokeText(t, sx + 11, sy + 4); ctx.fillStyle = '#fff'; ctx.fillText(t, sx + 11, sy + 4);
      }
    }
  }
  function drawFlash() {
    if (!flash) return;
    const age = (Date.now() - flash.t) / 1000; if (age > 2.5) { flash = null; return; }
    const [sx, sy] = toScreen(flash.x, flash.y);
    ctx.beginPath(); ctx.arc(sx, sy, 12 + age * 30, 0, Math.PI * 2); ctx.strokeStyle = `rgba(255,255,255,${1 - age / 2.5})`; ctx.lineWidth = 3; ctx.stroke();
    requestAnimationFrame(draw);
  }
  let raf = 0;
  function draw() { if (!raf) raf = requestAnimationFrame(() => { raf = 0; drawNow(); }); }
  function drawNow() {
    if (!w) return;
    ctx.fillStyle = '#a3a3a8'; ctx.fillRect(0, 0, w, h);
    if (!info) return;
    drawBase(); drawHeat(); drawZones(); drawFoot(); drawStreets(); drawPois(); drawTowns(); drawMarkers(); drawFlash();
    saveView();
  }
  let viewTimer = 0;
  function saveView() {
    clearTimeout(viewTimer);
    viewTimer = setTimeout(() => { if (tab === 'map') history.replaceState(null, '', `#map@${Math.round(cx)},${Math.round(cy)},${+s.toFixed(3)}`); }, 400);
  }
  function zoomAt(f, sx, sy) {
    const [wx, wy] = toWorld(sx, sy);
    s = Math.max(minScale(), Math.min(24, s * f));
    cx = wx - (sx - w / 2) / s; cy = wy - (sy - h / 2) / s; draw();
  }
  const minScale = () => info ? Math.min(w / (info.bounds[2] - info.bounds[0]), h / (info.bounds[3] - info.bounds[1])) * 0.8 : 0.01;
  // --- hit testing ---
  function hitBuilding(wx, wy) {
    if (!wantFoot()) return null;
    for (const [id, cat, rects] of foot.list)
      for (let i = 0; i < rects.length; i += 4) if (wx >= rects[i] && wy >= rects[i + 1] && wx < rects[i] + rects[i + 2] && wy < rects[i + 1] + rects[i + 3]) return { id, cat };
    return null;
  }
  function hitZone(wx, wy) {
    if (!mapData) return null;
    for (const z of mapData.zones) {
      const [t, , x, y, zw, zh] = z;
      if (!mapState.layers[ZONE_LAYER[t]]) continue;
      if (t === 0) { const [sx, sy] = toScreen(x, y), [px, py] = toScreen(wx, wy); if (Math.hypot(sx - px, sy - py) < 8) return z; continue; }
      if (wx >= x && wy >= y && wx < x + zw && wy < y + zh) return z;
    }
    return null;
  }
  function pick(sx, sy) {
    const m = markers.find(m => Math.hypot(m.sx - sx, m.sy - sy) < 10);
    if (m) return { kind: 'player', c: m.c };
    let best = null, bd = 11;
    for (const ph of poiHits) { const d = Math.hypot(ph.sx - sx, ph.sy - sy); if (d < bd) { bd = d; best = ph.p; } }
    if (best) return { kind: 'poi', p: best };
    const [wx, wy] = toWorld(sx, sy);
    const b = hitBuilding(wx, wy); if (b) return { kind: 'building', b };
    const z = hitZone(wx, wy); if (z) return { kind: 'zone', z };
    return null;
  }
  function tipHtml(hit) {
    if (hit.kind === 'player') {
      const c = hit.c;
      return `<b>${esc(c.username)}</b> — ${esc(c.character || '')}<br>${c.dead ? '<span class="dead-txt">мёртв</span>' : c.online ? '<span class="on-txt">онлайн</span>' : 'оффлайн'} · ${Math.round(c.x)}, ${Math.round(c.y)}${c.z ? ' · этаж ' + Math.round(c.z) : ''}`;
    }
    if (hit.kind === 'poi') {
      const p = hit.p, c = mapData.cats[p[1]], rt = mapData.roomTypes;
      return `<b style="color:${c.color}">${esc(c.label)}</b><br>${p[6].slice(0, 4).map(i => esc(ruRoom(rt[i]))).join(', ')}<br><span class="muted">${p[2]}, ${p[3]} · этажей: ${p[4]}${p[7] ? ' · есть подвал' : ''} · клик — подробнее</span>`;
    }
    if (hit.kind === 'building') {
      const c = hit.b.cat >= 0 ? mapData.cats[hit.b.cat] : null;
      return `${c ? `<b style="color:${c.color}">${esc(c.label)}</b>` : '<b>Жилой дом / здание</b>'}<br><span class="muted">клик — комнаты по этажам</span>`;
    }
    if (hit.kind === 'zone') {
      const [t, name, x, y, zw, zh, , extra] = hit.z;
      return `<b style="color:${ZONE_COL[t]}">${ZONE_NAME[t]}</b>${name ? ' — ' + esc(name) : ''}${extra ? '<br>Профессии: ' + esc(extra) : ''}<br><span class="muted">${x}, ${y}${t ? ` · ${zw}×${zh}` : ''}</span>`;
    }
    return '';
  }
  // --- input ---
  let drag = null;
  cv.addEventListener('mousedown', e => { drag = { x: e.clientX, y: e.clientY, cx, cy, moved: false }; cv.classList.add('dragging'); });
  window.addEventListener('mouseup', e => {
    if (drag && !drag.moved && e.target === cv) {
      const r = cv.getBoundingClientRect(), hit = pick(e.clientX - r.left, e.clientY - r.top);
      if (hit && hit.kind === 'poi') openBuilding(hit.p[0]);
      else if (hit && hit.kind === 'building') openBuilding(hit.b.id);
      else if (!hit) closeCard();
    }
    drag = null; cv.classList.remove('dragging');
  });
  window.addEventListener('mousemove', e => {
    const r = cv.getBoundingClientRect(), sx = e.clientX - r.left, sy = e.clientY - r.top;
    if (drag) { const dx = e.clientX - drag.x, dy = e.clientY - drag.y; if (Math.abs(dx) + Math.abs(dy) > 3) drag.moved = true; cx = drag.cx - dx / s; cy = drag.cy - dy / s; draw(); }
    if (tab !== 'map' || sx < 0 || sy < 0 || sx > w || sy > h) return;
    const [wx, wy] = toWorld(sx, sy);
    $('#mapCoords').textContent = `x ${Math.floor(wx)}, y ${Math.floor(wy)} · ${nearestTown(wx, wy)} · масштаб ${s >= 1 ? s.toFixed(1) + '×' : '1:' + Math.round(1 / s)}`;
    const tip = $('#mapTip');
    hover = drag ? null : pick(sx, sy);
    if (hover) { tip.innerHTML = tipHtml(hover); tip.style.left = Math.min(sx + 14, w - 260) + 'px'; tip.style.top = (sy + 12) + 'px'; tip.classList.remove('hidden'); cv.style.cursor = hover.kind === 'poi' || hover.kind === 'building' ? 'pointer' : ''; }
    else { tip.classList.add('hidden'); cv.style.cursor = ''; }
  });
  cv.addEventListener('mouseleave', () => $('#mapTip').classList.add('hidden'));
  cv.addEventListener('wheel', e => { e.preventDefault(); const r = cv.getBoundingClientRect(); zoomAt(e.deltaY < 0 ? 1.25 : 0.8, e.clientX - r.left, e.clientY - r.top); }, { passive: false });
  cv.addEventListener('dblclick', e => { const r = cv.getBoundingClientRect(); zoomAt(2, e.clientX - r.left, e.clientY - r.top); });
  new ResizeObserver(resize).observe(cv);
  function nearestTown(x, y) {
    if (!mapData || !mapData.towns.length) return '';
    let b = null, bd = 1e9;
    for (const t of mapData.towns) { const d = Math.hypot(t[1] - x, t[2] - y); if (d < bd) { bd = d; b = t; } }
    return bd < 600 ? b[0] : `${(bd / 1000).toFixed(1)} км до ${b[0]}`;
  }
  // --- building card ---
  async function openBuilding(id) {
    try {
      const d = await api('/api/map/building?id=' + id);
      selected = { id };
      const [x0, y0, x1, y1] = d.bounds, mx = Math.round((x0 + x1) / 2), my = Math.round((y0 + y1) / 2);
      const cats = mapData.cats.filter((c, i) => d.catMask & (1 << i));
      const main = d.cat >= 0 ? mapData.cats[d.cat] : null;
      const online = lastPlayers ? lastPlayers.characters.filter(c => c.online) : [];
      $('#mapCard').innerHTML = `<div class="row between"><b style="color:${main ? main.color : '#fff'}">${esc(main ? main.label : 'Жилой дом / здание')}</b><button class="btn sm" id="mcClose">✕</button></div>
        ${cats.length > 1 ? `<div class="small muted">${cats.map(c => esc(c.label)).join(' · ')}</div>` : ''}
        <div class="small mt">Центр: <span class="mono">${mx}, ${my}</span> · ${esc(nearestTown(mx, my))} · ${x1 - x0}×${y1 - y0} кл.</div>
        <div class="floors">${d.floors.map(f => `<div><b>${f.layer < 0 ? 'Подвал ' + f.layer : f.layer === 0 ? 'Этаж 1' : 'Этаж ' + (f.layer + 1)}</b>: ${Object.entries(f.rooms).map(([r, n]) => esc(ruRoom(r)) + (n > 1 ? ' ×' + n : '')).join(', ')}</div>`).join('')}</div>
        <div class="row gap wrap mt">
          <button class="btn sm" id="mcCopy">Копировать координаты</button>
          ${online.length ? `<select id="mcWho">${online.map(c => `<option>${esc(c.username)}</option>`).join('')}</select><button class="btn sm" id="mcTp" title="teleportto">Телепортировать сюда</button>` : ''}
        </div>`;
      $('#mapCard').classList.remove('hidden');
      $('#mcClose').onclick = closeCard;
      $('#mcCopy').onclick = () => { navigator.clipboard && navigator.clipboard.writeText(`${mx},${my},0`); toast('Скопировано: ' + mx + ',' + my + ',0'); };
      if ($('#mcTp')) $('#mcTp').onclick = () => { const u = $('#mcWho').value; if (confirm(`Телепортировать ${u} в ${mx},${my}?`)) act(() => api('/api/console', { cmd: `teleportto ${q(u)} ${mx},${my},0` })); };
      draw();
    } catch (e) { toast(e.message, true); }
  }
  function closeCard() { selected = null; $('#mapCard').classList.add('hidden'); draw(); }
  // --- init ---
  async function init() {
    if (info) return;
    const r = await api('/api/map/info').catch(e => ({ ok: false, error: e.message }));
    if (!r.ok) { $('#mapErr').textContent = 'Карта недоступна: ' + r.error; $('#mapErr').classList.remove('hidden'); return; }
    info = r; resize();
    $('#mBase').querySelector('option[value=top]').disabled = !info.top;
    if (!info.top) { $('#mBase').querySelector('option[value=top]').textContent = 'Вид сверху (не отрендерен — см. PANEL.md)'; if (mapState.base === 'top') mapState.base = 'game'; }
    $('#mBase').value = mapState.base;
    if (info.top && info.top.layers && info.top.layers.length > 1) {
      $('#mFloor').innerHTML = info.top.layers.filter(x => x <= 5).map(x => `<option value="${x}">${x < 0 ? 'подвал ' + x : x === 0 ? '1 этаж' : (x + 1) + ' этаж'}</option>`).join('');
      $('#mFloor').value = String(mapState.floor || 0); $('#mFloorWrap').classList.toggle('hidden', mapState.base !== 'top');
    }
    s = minScale() * 1.25; cx = (info.bounds[0] + info.bounds[2]) / 2; cy = (info.bounds[1] + info.bounds[3]) / 2;
    const hm = location.hash.match(/^#map@(-?\d+),(-?\d+),([\d.]+)/);
    const live = lastPlayers && (lastPlayers.characters.find(c => c.online) || lastPlayers.characters.find(c => !c.dead));
    if (hm) { cx = +hm[1]; cy = +hm[2]; s = +hm[3]; flash = { x: cx, y: cy, t: Date.now() }; }
    else if (live) { cx = live.x; cy = live.y; s = 0.5; }
    draw();
    loadData();
  }
  async function loadData() {
    const st = $('#mapDataStatus');
    try {
      const d = await api('/api/map/data');
      if (d.status === 'loading') { st.textContent = 'Читаю здания и улицы из файлов игры…'; setTimeout(loadData, 1500); return; }
      if (d.status !== 'ready') { st.textContent = 'Слои карты недоступны: ' + (d.error || d.status); return; }
      mapData = d;
      streetBox = d.streets.map(stt => { const p = stt[2]; let a = 1e9, b = 1e9, c = -1e9, e = -1e9; for (let k = 0; k < p.length; k += 2) { a = Math.min(a, p[k]); c = Math.max(c, p[k]); b = Math.min(b, p[k + 1]); e = Math.max(e, p[k + 1]); } return [a, b, c, e]; });
      st.textContent = `Зданий: ${d.buildingsTotal.toLocaleString('ru-RU')}, точек интереса: ${d.pois.length.toLocaleString('ru-RU')}, улиц: ${new Set(d.streets.map(x => x[0])).size}`;
      renderCats(); draw();
    } catch (e) { st.textContent = 'Слои карты недоступны: ' + e.message; }
  }
  return {
    init, draw, openBuilding, closeCard, setFlash(x, y) { flash = { x, y, t: Date.now() }; draw(); },
    center(x, y, sc) { cx = x; cy = y; if (sc) s = sc; draw(); },
    fit(x0, y0, x1, y1) { cx = (x0 + x1) / 2; cy = (y0 + y1) / 2; s = Math.max(0.3, Math.min(6, Math.min(w / (x1 - x0 + 20), h / (y1 - y0 + 20)) * 0.6)); draw(); },
    view: () => ({ cx, cy, s }),
  };
})();

function renderLayers() {
  $('#mLayers').innerHTML = Object.entries(MAPL).map(([k, [label]]) => {
    const zi = ZONE_LAYER.indexOf(k);
    const sw = k === 'heat' ? 'linear-gradient(90deg,#ffc800,#ff0000)' : zi >= 0 ? ZONE_COL[zi] : k === 'streets' ? '#ffe9b0' : k === 'towns' ? '#fff6d6' : '#9aa';
    return `<label><input type="checkbox" data-l="${k}"${mapState.layers[k] ? ' checked' : ''}> <span class="sw" style="background:${sw}"></span>${esc(label)}</label>`;
  }).join('');
  $$('#mLayers input').forEach(i => i.onchange = () => { mapState.layers[i.dataset.l] = i.checked; lsSet('layers', mapState.layers); map.draw(); });
}
function renderCats() {
  if (!mapData) return;
  if (!mapState.cats) mapState.cats = mapData.cats.filter(c => c.id !== 'garage').map(c => c.id);
  const on = new Set(mapState.cats);
  catOn = 0; mapData.cats.forEach((c, i) => { if (on.has(c.id)) catOn |= 1 << i; });
  $('#mCats').innerHTML = mapData.cats.filter(c => c.count).map(c => `<label><input type="checkbox" data-c="${c.id}"${on.has(c.id) ? ' checked' : ''}> <span class="sw round" style="background:${c.color}"></span>${esc(c.label)} <small class="muted">${c.count}</small></label>`).join('');
  $('#poiCount').textContent = mapData.pois.length.toLocaleString('ru-RU');
  $$('#mCats input').forEach(i => i.onchange = () => { mapState.cats = $$('#mCats input:checked').map(x => x.dataset.c); lsSet('cats', mapState.cats); renderCats(); map.draw(); });
}
$('#poiAll').onclick = e => { e.preventDefault(); if (!mapData) return; mapState.cats = mapData.cats.map(c => c.id); lsSet('cats', mapState.cats); renderCats(); map.draw(); };
$('#poiNone').onclick = e => { e.preventDefault(); mapState.cats = []; lsSet('cats', mapState.cats); renderCats(); map.draw(); };
$('#mBase').onchange = () => { mapState.base = $('#mBase').value; lsSet('base', mapState.base); $('#mFloorWrap').classList.toggle('hidden', mapState.base !== 'top' || !$('#mFloor').options.length); map.draw(); };
$('#mFloor').onchange = () => { mapState.floor = +$('#mFloor').value; map.draw(); };
renderLayers();

// --- search ---
function mapSearch(qs) {
  const t = qs.trim().toLowerCase(); if (t.length < 2) return [];
  const out = [];
  if (lastPlayers) for (const c of lastPlayers.characters)
    if ((c.username + ' ' + (c.character || '')).toLowerCase().includes(t)) out.push({ k: 'Игрок', n: c.username + (c.character ? ' · ' + c.character : ''), x: c.x, y: c.y, sc: 4, cls: c.online ? 'on-txt' : c.dead ? 'dead-txt' : '' });
  if (mapData) {
    for (const [n, x, y] of mapData.towns) if (n.toLowerCase().includes(t)) out.push({ k: 'Город', n, x, y, sc: 0.6 });
    const seen = new Set();
    for (const st of mapData.streets) {
      if (!st[0] || seen.has(st[0]) || !st[0].toLowerCase().includes(t)) continue;
      seen.add(st[0]); const p = st[2], m = Math.floor(p.length / 4) * 2;
      out.push({ k: 'Улица', n: st[0], x: p[m], y: p[m + 1], sc: 1.5 });
      if (seen.size > 15) break;
    }
    const rt = mapData.roomTypes, cats = mapData.cats;
    const catHit = new Set(cats.map((c, i) => c.label.toLowerCase().includes(t) || c.id.includes(t) ? i : -1).filter(i => i >= 0));
    const v = map.view();
    const pois = [];
    for (const p of mapData.pois) {
      const types = p[6].map(i => rt[i]);
      if (catHit.has(p[1]) || types.some(r => r.includes(t) || ruRoom(r).toLowerCase().includes(t))) pois.push(p);
    }
    pois.sort((a, b) => Math.hypot(a[2] - v.cx, a[3] - v.cy) - Math.hypot(b[2] - v.cx, b[3] - v.cy));
    for (const p of pois.slice(0, 40)) out.push({ k: cats[p[1]].label, n: p[6].slice(0, 3).map(i => ruRoom(rt[i])).join(', '), x: p[2], y: p[3], sc: 2.5, poi: p[0], color: cats[p[1]].color, near: true });
    if (pois.length > 40) out.push({ k: '', n: `…и ещё ${pois.length - 40} (сначала ближайшие к центру карты)`, x: null });
  }
  return out.slice(0, 80);
}
let searchRes = [];
$('#mapSearch').oninput = () => {
  searchRes = mapSearch($('#mapSearch').value);
  const v = map.view();
  $('#mapResults').innerHTML = searchRes.map((r, i) => r.x === null ? `<div class="muted small">${esc(r.n)}</div>` :
    `<div class="p" data-i="${i}"><span><small class="muted" ${r.color ? `style="color:${r.color}"` : ''}>${esc(r.k)}</small> <span class="${r.cls || ''}">${esc(r.n)}</span></span><span class="muted small">${r.near ? (Math.hypot(r.x - v.cx, r.y - v.cy) / 1000).toFixed(1) + ' км' : Math.round(r.x) + ', ' + Math.round(r.y)}</span></div>`).join('') || ($('#mapSearch').value.trim().length >= 2 ? '<div class="muted small">Ничего не найдено</div>' : '');
  $$('#mapResults .p').forEach(d => d.onclick = () => { const r = searchRes[+d.dataset.i]; map.center(r.x, r.y, r.sc); map.setFlash(r.x, r.y); if (r.poi !== undefined) map.openBuilding(r.poi); });
};

function renderMapList() {
  const p = lastPlayers; if (!p) return;
  $('#mapSaved').textContent = 'Позиции сохранены: ' + ago(p.positionsSavedAt);
  const list = [...p.characters].sort((a, b) => (b.online - a.online) || (a.dead - b.dead));
  $('#mapList').innerHTML = list.map((c, i) => `<div class="p" data-i="${i}"><span>${c.online ? '<span class="on-txt">●</span> ' : c.dead ? '<span class="dead-txt">✖</span> ' : '<span class="muted">●</span> '}${esc(c.username)}</span><span class="muted">${Math.round(c.x)}, ${Math.round(c.y)}</span></div>`).join('') || '<div class="muted small">Персонажей пока нет</div>';
  $$('#mapList .p').forEach(d => d.onclick = () => { const c = list[+d.dataset.i]; map.center(c.x, c.y, 4); map.setFlash(c.x, c.y); });
}
['#mOnline', '#mOffline', '#mDead', '#mLabels'].forEach(s => $(s).onchange = () => map.draw());
$('#btnMapSave').onclick = () => act(() => api('/api/server/save', {})).then(() => setTimeout(() => loadPlayers(true), 6000));
$('#btnGoto').onclick = () => { const m = $('#gotoXY').value.match(/(-?\d+)\D+(-?\d+)/); if (m) { map.center(+m[1], +m[2], 4); map.setFlash(+m[1], +m[2]); } };
$('#gotoXY').onkeydown = e => { if (e.key === 'Enter') $('#btnGoto').click(); };
$('#btnShare').onclick = () => {
  const v = map.view(), url = `${location.origin}/#map@${Math.round(v.cx)},${Math.round(v.cy)},${+v.s.toFixed(3)}`;
  navigator.clipboard ? navigator.clipboard.writeText(url).then(() => toast('Ссылка скопирована: ' + url)) : prompt('Ссылка:', url);
};
loaders.map = async () => { await loadPlayers(true); await map.init(); renderMapList(); map.draw(); };

// ---------------- mods ----------------
let modsState = null, modsOrder = [], wsItems = [], modsDirty = false;
const modIndex = () => Object.fromEntries((modsState.enabled.concat(modsState.available)).map(m => [m.id, m]));
async function loadMods() {
  try { modsState = await api('/api/mods'); } catch (e) { toast(e.message, true); return; }
  modsOrder = modsState.enabled.map(m => m.raw || m.id);
  wsItems = modsState.workshopItems.map(i => i.id);
  setDirty(false); renderMods();
  fetchTitles();
}
async function fetchTitles() {
  const need = modsState.workshopItems.filter(i => !i.title).map(i => i.id);
  if (!need.length) return;
  try {
    const d = await api('/api/workshop/details?ids=' + need.join(','));
    const t = Object.fromEntries(d.map(x => [x.id, x.title]));
    modsState.workshopItems.forEach(i => { if (t[i.id]) i.title = t[i.id]; });
    renderWsItems();
  } catch (e) {}
}
function setDirty(v) { modsDirty = v; $('#modsDirty').textContent = v ? '● Есть несохранённые изменения' : ''; $('#modsSave').disabled = !v; }
function renderMods() {
  if (!modsState) return;
  const idx = modIndex(), f = $('#modFilter').value.toLowerCase();
  const enabledSet = new Set(modsOrder.map(x => x.replace(/^\\+/, '')));
  $('#modsCount').textContent = `${modsOrder.length} включено · ${wsItems.length} предметов Мастерской`;
  const ol = $('#modsEnabled');
  ol.innerHTML = modsOrder.map((raw, i) => {
    const id = raw.replace(/^\\+/, ''), m = idx[id] || { id, name: id, installed: false, issues: [] };
    const orig = modsState.enabled.find(e => e.id === id);
    const issues = orig ? orig.issues : (m.installed ? [] : ['новый — файлы появятся после запуска сервера']);
    const hide = f && !(id.toLowerCase().includes(f) || String(m.name).toLowerCase().includes(f));
    return `<li draggable="true" data-i="${i}" class="${hide ? 'hidden' : ''}"><span class="num">${i + 1}</span><span class="handle">⋮⋮</span>
      <div class="info"><div class="name">${esc(m.name)}${m.java ? '<span class="tag java">Java</span>' : ''}${m.local ? '<span class="tag" title="Локальная копия в Zomboid/mods — не обновляется из Steam">локальный</span>' : ''}${!m.installed ? '<span class="tag">не скачан</span>' : ''}</div>
      <div class="meta">${esc(id)}${m.workshopId ? ' · <a href="https://steamcommunity.com/sharedfiles/filedetails/?id=' + esc(m.workshopId) + '" target="_blank" rel="noopener">' + esc(m.workshopId) + '</a>' : ''}${m.versionMax ? ' · до ' + esc(m.versionMax) : ''}${m.require && m.require.length ? ' · требует: ' + esc(m.require.join(', ')) : ''}</div>
      ${issues.map(x => `<div class="issue">⚠ ${esc(x)}</div>`).join('')}</div>
      <div class="actions"><button class="btn sm" data-up="${i}" title="Выше">↑</button><button class="btn sm" data-down="${i}" title="Ниже">↓</button><button class="btn sm" data-off="${i}" title="Выключить">✕</button></div></li>`;
  }).join('');
  ol.querySelectorAll('[data-up]').forEach(b => b.onclick = () => move(+b.dataset.up, -1));
  ol.querySelectorAll('[data-down]').forEach(b => b.onclick = () => move(+b.dataset.down, 1));
  ol.querySelectorAll('[data-off]').forEach(b => b.onclick = () => { modsOrder.splice(+b.dataset.off, 1); setDirty(true); renderMods(); });
  let dragI = null;
  ol.querySelectorAll('li').forEach(li => {
    li.addEventListener('dragstart', () => { dragI = +li.dataset.i; li.classList.add('drag'); });
    li.addEventListener('dragend', () => li.classList.remove('drag'));
    li.addEventListener('dragover', e => { e.preventDefault(); li.classList.add('over'); });
    li.addEventListener('dragleave', () => li.classList.remove('over'));
    li.addEventListener('drop', e => { e.preventDefault(); li.classList.remove('over'); const to = +li.dataset.i; if (dragI === null || dragI === to) return; const [x] = modsOrder.splice(dragI, 1); modsOrder.splice(to, 0, x); setDirty(true); renderMods(); });
  });
  const av = modsState.available.filter(m => !enabledSet.has(m.id)).filter(m => !f || m.id.toLowerCase().includes(f) || String(m.name).toLowerCase().includes(f));
  $('#modsAvailable').innerHTML = av.map(m => `<div class="it"><div><b>${esc(m.name)}</b>${m.java ? '<span class="tag java">Java</span>' : ''}<br><small class="muted">${esc(m.id)}${m.workshopId ? ' · ' + esc(m.workshopId) : ''}</small></div><button class="btn sm" data-on="${esc(m.id)}" data-ws="${esc(m.workshopId || '')}">Включить</button></div>`).join('') || '<div class="muted small">Нет</div>';
  $$('#modsAvailable [data-on]').forEach(b => b.onclick = () => { enableMod(b.dataset.on, b.dataset.ws); });
  renderWsItems();
}
function renderWsItems() {
  const used = new Set(modsOrder.map(x => { const m = modIndex()[x.replace(/^\\+/, '')]; return m && m.workshopId; }));
  const info = Object.fromEntries((modsState.workshopItems || []).map(i => [i.id, i]));
  $('#wsItems').innerHTML = wsItems.map(id => { const i = info[id] || {}; return `<div class="it"><div>${esc(i.title || id)}<br><small class="muted"><a href="https://steamcommunity.com/sharedfiles/filedetails/?id=${esc(id)}" target="_blank" rel="noopener">${esc(id)}</a>${i.downloaded === false ? ' · ещё не скачан' : ''}${!used.has(id) ? ' · ни один мод из него не включён' : ''}</small></div><button class="btn sm" data-rm="${esc(id)}">Убрать</button></div>`; }).join('');
  $$('#wsItems [data-rm]').forEach(b => b.onclick = () => { wsItems = wsItems.filter(x => x !== b.dataset.rm); setDirty(true); renderWsItems(); });
}
function move(i, d) { const j = i + d; if (j < 0 || j >= modsOrder.length) return; [modsOrder[i], modsOrder[j]] = [modsOrder[j], modsOrder[i]]; setDirty(true); renderMods(); }
function enableMod(id, ws) {
  if (modsOrder.some(x => x.replace(/^\\+/, '') === id)) return;
  const m = modIndex()[id];
  let pos = modsOrder.length;
  if (m && m.require) { /* place after its last requirement */ let last = -1; for (const r of m.require) last = Math.max(last, modsOrder.findIndex(x => x.replace(/^\\+/, '') === r)); if (last >= 0) pos = Math.max(last + 1, Math.min(pos, modsOrder.length)); }
  modsOrder.splice(pos, 0, id);
  if (ws && !wsItems.includes(ws)) wsItems.push(ws);
  setDirty(true); renderMods(); toast('Включён ' + id + ' (позиция ' + (pos + 1) + ') — не забудьте сохранить');
}
$('#modFilter').oninput = renderMods;
$('#modsReload').onclick = () => (!modsDirty || confirm('Отменить несохранённые изменения?')) && loadMods();
$('#modsSave').onclick = () => act(async () => { const r = await api('/api/mods', { mods: modsOrder, workshopItems: wsItems, syncClient: $('#syncClient').checked }); await loadMods(); return r; });
function wsCard(d, opts = {}) {
  const ids = d.modIds || [];
  const have = wsItems.includes(d.id);
  return `<div class="ws" data-id="${esc(d.id)}">${d.preview ? `<img src="${esc(d.preview)}" alt="" loading="lazy">` : ''}<div class="grow">
    <div class="t"><a href="https://steamcommunity.com/sharedfiles/filedetails/?id=${esc(d.id)}" target="_blank" rel="noopener">${esc(d.title || d.id)}</a></div>
    <div class="muted small">${esc(d.id)} · обновлён ${d.updated ? new Date(d.updated * 1000).toLocaleDateString('ru-RU') : '—'} · ${(d.size / 1048576).toFixed(1)} МБ${d.subs ? ' · ' + d.subs + ' подписок' : ''}${(d.tags || []).length ? ' · ' + esc(d.tags.join(', ')) : ''}</div>
    <div class="ids">${ids.length ? ids.map(i => `<label><input type="checkbox" value="${esc(i)}" checked> ${esc(i)}</label>`).join('') : '<span class="muted small">Mod ID в описании не найдены — укажите вручную: </span><input class="manualId" placeholder="ModID1;ModID2" style="width:160px">'}</div>
    ${opts.required && opts.required.length ? '<div class="small mt">Требуется: ' + opts.required.map(r => `${esc(r.title)} (${esc(r.id)})${wsItems.includes(r.id) ? ' ✓' : ''}`).join(', ') + '</div>' : ''}
    <div class="row gap mt"><button class="btn sm primary" data-add="${esc(d.id)}">${have ? 'Включить отмеченные моды' : 'Добавить'}${opts.required && opts.required.length ? ' + требуемые' : ''}</button></div></div></div>`;
}
let wsLast = {};
function bindWsAdd() {
  $$('#wsResults [data-add]').forEach(b => b.onclick = async () => {
    const card = b.closest('.ws'), id = b.dataset.add;
    let ids = [...card.querySelectorAll('.ids input[type=checkbox]:checked')].map(x => x.value);
    const man = card.querySelector('.manualId'); if (man && man.value.trim()) ids = man.value.split(/[;,\s]+/).filter(Boolean);
    const req = (wsLast[id] && wsLast[id].required) || [];
    for (const r of req) {
      if (wsItems.includes(r.id)) continue;
      wsItems.push(r.id);
      for (const mid of (r.modIds || []).slice(0, 1)) if (!modsOrder.includes(mid)) modsOrder.splice(0, 0, mid);
    }
    if (!wsItems.includes(id)) wsItems.push(id);
    for (const mid of ids) if (!modsOrder.some(x => x.replace(/^\\+/, '') === mid)) modsOrder.push(mid);
    setDirty(true); renderMods();
    toast(`Добавлено: предмет ${id}${ids.length ? ', моды ' + ids.join(', ') : ''}${req.length ? ' и требуемые предметы' : ''}. Проверьте порядок и нажмите «Сохранить».`);
  });
}
$('#wsById').onclick = async () => {
  const v = $('#wsInput').value.trim(); if (!v) return;
  $('#wsResults').innerHTML = '<div class="muted small">Загружаю…</div>';
  try { const d = await api('/api/workshop/item?id=' + encodeURIComponent(v)); wsLast[d.id] = d; $('#wsResults').innerHTML = wsCard(d, { required: d.required }); bindWsAdd(); }
  catch (e) { $('#wsResults').innerHTML = '<div class="err">' + esc(e.message) + '</div>'; }
};
$('#wsCollection').onclick = async () => {
  const v = $('#wsInput').value.trim(); if (!v) return;
  $('#wsResults').innerHTML = '<div class="muted small">Загружаю коллекцию…</div>';
  try { const l = await api('/api/workshop/collection?id=' + encodeURIComponent(v)); $('#wsResults').innerHTML = `<div class="small">${l.length} предметов. Добавляйте по одному или все сразу: <button class="btn sm" id="wsAddAll">Добавить все</button></div>` + l.map(d => wsCard(d)).join(''); bindWsAdd(); $('#wsAddAll').onclick = () => $$('#wsResults [data-add]').forEach(b => b.click()); }
  catch (e) { $('#wsResults').innerHTML = '<div class="err">' + esc(e.message) + '</div>'; }
};
$('#wsSearch').onclick = async () => {
  const v = $('#wsInput').value.trim(); if (!v) return;
  $('#wsResults').innerHTML = '<div class="muted small">Ищу в Мастерской…</div>';
  try { const l = await api('/api/workshop/search?q=' + encodeURIComponent(v)); $('#wsResults').innerHTML = l.length ? l.map(d => wsCard(d)).join('') : '<div class="muted small">Ничего не найдено</div>'; bindWsAdd(); }
  catch (e) { $('#wsResults').innerHTML = '<div class="err">' + esc(e.message) + '</div>'; }
};
$('#wsInput').addEventListener('keydown', e => { if (e.key === 'Enter') (/\d{6,}/.test(e.target.value) ? $('#wsById') : $('#wsSearch')).click(); });
loaders.mods = () => { if (!modsState || !modsDirty) loadMods(); };

// ---------------- ini form ----------------
let iniEntries = [];
const dirty = { ini: {}, sb: {}, set: {} };
const dirtyForms = () => Object.keys(dirty.ini).length || Object.keys(dirty.sb).length || Object.keys(dirty.set).length;
async function loadIni() {
  try { const r = await api('/api/config/ini'); iniEntries = r.entries; $('#iniPath').textContent = r.path; dirty.ini = {}; renderIni(); } catch (e) { toast(e.message, true); }
}
function inputFor(value, type, secret) {
  if (type === 'bool' || value === 'true' || value === 'false') return `<select data-t="bool"><option value="true"${value === 'true' ? ' selected' : ''}>true</option><option value="false"${value === 'false' ? ' selected' : ''}>false</option></select>`;
  return `<input data-t="${type || 'raw'}" type="${secret ? 'password' : 'text'}" value="${esc(value)}"${secret ? ' autocomplete="new-password"' : ''}>`;
}
function renderIni() {
  const f = $('#iniFilter').value.toLowerCase();
  $('#iniForm').innerHTML = iniEntries.map((e, i) => {
    if (e.key === 'Mods' || e.key === 'WorkshopItems') return '';
    if (f && !(e.key.toLowerCase().includes(f) || e.comment.toLowerCase().includes(f))) return '';
    const v = dirty.ini[e.key] ?? e.value;
    return `<div class="k">${esc(e.key)}<small>${esc(e.comment)}</small></div><div data-i="${i}">${inputFor(v, null, e.secret)}</div>`;
  }).join('');
  $$('#iniForm [data-i]').forEach(d => { const el = d.firstElementChild, e = iniEntries[+d.dataset.i]; el.onchange = el.oninput = () => { if (el.value === e.value) delete dirty.ini[e.key]; else dirty.ini[e.key] = el.value; el.classList.toggle('changed', el.value !== e.value); }; if (dirty.ini[e.key] !== undefined) el.classList.add('changed'); });
}
$('#iniFilter').oninput = renderIni;
$('#iniSave').onclick = () => { if (!Object.keys(dirty.ini).length) return toast('Нет изменений'); act(async () => { const r = await api('/api/config/ini', { changes: dirty.ini }); await loadIni(); return r; }); };
loaders.ini = () => { if (!Object.keys(dirty.ini).length) loadIni(); };

// ---------------- sandbox form ----------------
let sbEntries = [];
async function loadSb() {
  try {
    const r = await api('/api/config/sandbox'); sbEntries = r.entries; $('#sbPath').textContent = r.path; dirty.sb = {};
    const groups = [...new Set(sbEntries.map(e => e.group))];
    $('#sbGroup').innerHTML = '<option value="">все разделы</option>' + groups.map(g => `<option>${esc(g)}</option>`).join('');
    renderSb();
  } catch (e) { toast(e.message, true); }
}
function renderSb() {
  const f = $('#sbFilter').value.toLowerCase(), g = $('#sbGroup').value;
  let html = '', lastG = null, n = 0;
  sbEntries.forEach((e, i) => {
    if (g && e.group !== g) return;
    if (f && !(e.key.toLowerCase().includes(f) || e.group.toLowerCase().includes(f) || e.comment.toLowerCase().includes(f))) return;
    if (++n > 600) return;
    if (e.group !== lastG) { html += `<div class="grp">${esc(e.group)}</div>`; lastG = e.group; }
    const v = dirty.sb[e.line] ? dirty.sb[e.line].value : e.value;
    html += `<div class="k">${esc(e.key)}<small>${esc(e.comment)}</small></div><div data-i="${i}">${inputFor(v, e.type)}</div>`;
  });
  $('#sbForm').innerHTML = html + (n > 600 ? '<div class="muted">Показаны первые 600 — уточните поиск</div>' : '');
  $$('#sbForm [data-i]').forEach(d => { const el = d.firstElementChild, e = sbEntries[+d.dataset.i]; el.onchange = el.oninput = () => { if (el.value === e.value) delete dirty.sb[e.line]; else dirty.sb[e.line] = { line: e.line, key: e.key, type: e.type, value: el.value }; el.classList.toggle('changed', el.value !== e.value); }; if (dirty.sb[e.line]) el.classList.add('changed'); });
}
$('#sbFilter').oninput = renderSb; $('#sbGroup').onchange = renderSb;
$('#sbSave').onclick = () => { const ch = Object.values(dirty.sb); if (!ch.length) return toast('Нет изменений'); act(async () => { const r = await api('/api/config/sandbox', { changes: ch }); await loadSb(); return r; }); };
loaders.sandbox = () => { if (!Object.keys(dirty.sb).length) loadSb(); };

// ---------------- raw files ----------------
let fileOrig = '';
async function loadFile() {
  try { const r = await api('/api/file?name=' + $('#fileSel').value); $('#filePath').textContent = r.path + (r.exists ? '' : ' (файла нет — будет создан)'); $('#fileText').value = fileOrig = r.text; } catch (e) { toast(e.message, true); }
}
$('#fileSel').onchange = () => { if ($('#fileText').value !== fileOrig && !confirm('Есть несохранённые изменения. Переключить?')) return; loadFile(); };
$('#fileReload').onclick = loadFile;
$('#fileSave').onclick = () => confirm('Сохранить файл? Прежняя версия попадёт в file_backups.') && act(async () => { const r = await api('/api/file?name=' + $('#fileSel').value, { text: $('#fileText').value }); fileOrig = $('#fileText').value; return r; });
loaders.files = () => { if ($('#fileText').value === fileOrig) loadFile(); };

// ---------------- ZombieBuddy ----------------
async function loadZb() {
  try {
    const r = await api('/api/zb'); $('#zbPath').textContent = r.path;
    const mods = (r.data && r.data.mods) || [];
    $('#zbTable').innerHTML = '<tr><th>Мод</th><th>Workshop</th><th>Хэш JAR</th><th>Решение</th><th></th></tr>' + mods.map(m => `<tr><td><b>${esc(m.id)}</b></td><td>${esc(m.workshop_id || '')}</td><td class="mono small">${esc(String(m.jar_hash || '').slice(0, 16))}…</td>
      <td>${m.decision ? '<span class="on-txt">разрешён</span>' : '<span class="dead-txt">запрещён</span>'}</td>
      <td><div class="row gap"><button class="btn sm" data-zb="${m.decision ? 'deny' : 'allow'}" data-id="${esc(m.id)}" data-h="${esc(m.jar_hash)}">${m.decision ? 'Запретить' : 'Разрешить'}</button><button class="btn sm" data-zb="delete" data-id="${esc(m.id)}" data-h="${esc(m.jar_hash)}" title="ZombieBuddy спросит снова">Забыть</button></div></td></tr>`).join('');
    $$('#zbTable [data-zb]').forEach(b => b.onclick = () => act(async () => { const x = await api('/api/zb', { id: b.dataset.id, jar_hash: b.dataset.h, action: b.dataset.zb }); loadZb(); return x; }));
  } catch (e) { toast(e.message, true); }
}
loaders.zb = loadZb;

// ---------------- backups ----------------
async function loadBk() {
  try {
    const r = await api('/api/backups'); $('#bkInfo').textContent = 'Папка: ' + r.dir + '. В бэкап входят мир, база аккаунтов и конфиги сервера.';
    $('#bkProgress').textContent = r.progress;
    $('#bkTable').innerHTML = '<tr><th>Файл</th><th>Размер</th><th>Создан</th><th></th></tr>' + r.list.map(b => `<tr><td>${esc(b.name)}</td><td>${fmtSize(b.size)}</td><td>${fmtTime(b.time)}</td><td><div class="row gap"><a class="btn sm" href="/backup/${encodeURIComponent(b.name)}">Скачать</a><button class="btn sm danger" data-del="${esc(b.name)}">Удалить</button></div></td></tr>`).join('');
    $$('#bkTable [data-del]').forEach(b => b.onclick = () => confirm('Удалить ' + b.dataset.del + '?') && act(async () => { const x = await api('/api/backups/delete', { name: b.dataset.del }); loadBk(); return x; }));
    if (String(r.progress).startsWith('Идёт')) setTimeout(() => tab === 'backups' && loadBk(), 1500);
  } catch (e) { toast(e.message, true); }
}
$('#bkCreate').onclick = () => act(async () => { const r = await api('/api/backups/create', {}); setTimeout(loadBk, 500); return r; });
loaders.backups = loadBk;

// ---------------- panel settings ----------------
const SET_HELP = {
  serverDir: 'Папка Project Zomboid Dedicated Server', startScript: 'Скрипт запуска в папке сервера', zomboidDir: 'Папка Zomboid (конфиги, миры, логи)',
  serverName: 'Имя сервера (-servername): файлы <имя>.ini, <имя>_SandboxVars.lua, мир Saves/Multiplayer/<имя>', bind: '127.0.0.1 — только этот ПК; 0.0.0.0 — вся сеть (нужен panelPassword)',
  port: 'Порт панели', panelPassword: 'Пароль входа в панель (обязателен, если bind не 127.0.0.1)', openBrowser: 'Открывать браузер при запуске панели',
  autoRestartOnCrash: 'Перезапускать сервер, если он упал (только для запущенного из панели)', autoStartServer: 'Запускать сервер вместе с панелью (для панели-службы на Linux)', stopServerOnExit: 'При закрытии панели сохранить мир и выключить сервер, запущенный из неё (для службы на Linux)', stopTimeoutSec: 'Сколько секунд ждать выключения сервера при закрытии панели', dailyRestart: 'Ежедневный перезапуск, ЧЧ:ММ (пусто — выкл)',
  restartWarnMinutes: 'За сколько минут предупреждать игроков о плановом перезапуске', gameVersion: 'Версия игры для проверки модов (versionMax)',
  gameDir: 'Папка клиента игры (для карты и Мастерской)', pyramidZip: 'Путь к pyramid.zip карты (пусто — автопоиск)', workshopDirs: 'Доп. папки Мастерской через ;', zbConfigDir: 'Папка ZombieBuddy (~/.zombie_buddy)'
};
let setOrig = {};
async function loadSet() {
  try {
    const r = await api('/api/settings'); setOrig = r.settings; dirty.set = {}; $('#setPath').textContent = r.file;
    $('#setForm').innerHTML = Object.entries(setOrig).map(([k, v]) => `<div class="k">${esc(k)}<small>${esc(SET_HELP[k] || '')}</small></div><div data-k="${esc(k)}">${inputFor(v, 'raw', k === 'panelPassword')}</div>`).join('');
    $$('#setForm [data-k]').forEach(d => { const el = d.firstElementChild, k = d.dataset.k; el.oninput = el.onchange = () => { if (el.value === String(setOrig[k])) delete dirty.set[k]; else dirty.set[k] = el.value; el.classList.toggle('changed', el.value !== String(setOrig[k])); }; });
  } catch (e) { toast(e.message, true); }
}
$('#setSave').onclick = () => { if (!Object.keys(dirty.set).length) return toast('Нет изменений'); act(async () => { const r = await api('/api/settings', { changes: dirty.set }); await loadSet(); return r; }); };
loaders.settings = loadSet;

// ---------------- loop ----------------
openTab(tab);
// ---------------- load (CPU / RAM) ----------------
let lastStats = null;
const fmtMb = mb => mb == null ? '—' : mb >= 1024 ? (mb / 1024).toFixed(1) + ' ГБ' : Math.round(mb) + ' МБ';
function cssVar(n) { return getComputedStyle(document.documentElement).getPropertyValue(n).trim(); }
function drawLoad(cv, hist, series, maxY, fmtY, mark) {
  const dpr = window.devicePixelRatio || 1, w = cv.clientWidth, h = cv.clientHeight;
  if (!w) return;
  cv.width = w * dpr; cv.height = h * dpr;
  const g = cv.getContext('2d'); g.setTransform(dpr, 0, 0, dpr, 0, 0); g.clearRect(0, 0, w, h);
  const span = 30 * 60 * 1000, now = Date.now(), x = t => w - (now - t) / span * w, y = v => h - 2 - v / maxY * (h - 14);
  g.strokeStyle = cssVar('--line'); g.lineWidth = 1; g.fillStyle = cssVar('--muted'); g.font = '10px system-ui';
  for (const f of [0.5, 1]) { const yy = Math.round(y(maxY * f)) + 0.5; g.beginPath(); g.moveTo(0, yy); g.lineTo(w, yy); g.stroke(); g.fillText(fmtY(maxY * f), 2, yy - 2); }
  if (mark) { const yy = Math.round(y(mark.v)) + 0.5; g.save(); g.setLineDash([4, 4]); g.strokeStyle = mark.color; g.beginPath(); g.moveTo(0, yy); g.lineTo(w, yy); g.stroke(); g.restore(); g.fillStyle = mark.color; g.textAlign = 'right'; g.fillText(mark.label, w - 2, yy - 3); g.textAlign = 'left'; }
  for (const s of series) {
    g.strokeStyle = s.color; g.lineWidth = s.width || 1.5; g.beginPath(); let pen = false;
    for (const p of hist) { const v = p[s.i]; if (v == null) { pen = false; continue; } const X = x(p[0]), Y = y(Math.min(v, maxY)); pen ? g.lineTo(X, Y) : g.moveTo(X, Y); pen = true; }
    g.stroke();
  }
}
function renderLoad() {
  const r = lastStats; if (!r) return;
  const H = r.history || [], acc = cssVar('--accent'), mut = cssVar('--muted'), info = cssVar('--info');
  $('#loadSys').textContent = (r.sysCpu != null ? 'машина: ЦПУ ' + Math.round(r.sysCpu) + '%' : '') +
    (r.sysMemTotalMb ? ', ОЗУ ' + fmtMb(r.sysMemUsedMb) + ' из ' + fmtMb(r.sysMemTotalMb) : '') + (r.cores ? ', ядер ' + r.cores : '');
  if (r.running) {
    $('#loadCpu').textContent = r.cpu != null ? r.cpu.toFixed(0) + '%' : '…';
    $('#loadCpuSub').innerHTML = (r.cpuCores != null ? `≈ ${r.cpuCores} ядра из ${r.cores}` : 'замеряю…') +
      `<span class="legend-i" style="background:${acc}"></span>сервер<span class="legend-i" style="background:${mut}"></span>вся машина`;
    $('#loadRam').textContent = fmtMb(r.rssMb);
    $('#loadRamSub').innerHTML = (r.xmxMb ? `лимит Java (-Xmx) ${fmtMb(r.xmxMb)}` : '') + (r.peakRssMb ? `, пик ${fmtMb(r.peakRssMb)}` : '') +
      (r.processes > 1 ? `, процессов ${r.processes}` : '') + (r.threads ? `, потоков ${r.threads}` : '');
  } else {
    $('#loadCpu').textContent = '—'; $('#loadRam').textContent = '—';
    $('#loadCpuSub').textContent = 'сервер не запущен'; $('#loadRamSub').textContent = '';
  }
  drawLoad($('#cpuChart'), H, [{ i: 3, color: mut, width: 1 }, { i: 1, color: acc, width: 2 }], 100, v => Math.round(v) + '%');
  const ramVals = H.map(p => p[2] || 0), top = Math.max(r.xmxMb ? r.xmxMb * 1.25 : 0, ...ramVals, 1024);
  drawLoad($('#ramChart'), H, [{ i: 2, color: info, width: 2 }], Math.ceil(top / 1024) * 1024, fmtMb, r.xmxMb ? { v: r.xmxMb, color: cssVar('--warn'), label: 'лимит Java ' + fmtMb(r.xmxMb) } : null);
}
async function loadStats() {
  if (tab !== 'overview' || document.hidden) return;
  try { lastStats = await api('/api/stats'); renderLoad(); } catch (e) {}
}
window.addEventListener('resize', renderLoad);
const _ovl = loaders.overview; loaders.overview = () => { if (_ovl) _ovl(); setTimeout(() => { renderLoad(); loadStats(); }, 0); };

refreshStatus();
loadStats();
setInterval(loadStats, 5000);
setInterval(refreshStatus, 3000);
setInterval(pollConsole, 1500);
setInterval(() => { if (tab === 'map' || tab === 'players' || tab === 'overview') loadPlayers(true); }, 10000);
