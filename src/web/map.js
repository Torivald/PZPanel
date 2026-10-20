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
  function drawTopLevel(L) {
    const t = info.top, f = 2 ** (t.skip + t.maxLevel - L), T = t.tileSize;
    const pxOf = (wv, o) => (wv * t.sqr + o) / f, wOf = (pv, o) => (pv * f - o) / t.sqr;
    const [wx0, wy0] = toWorld(0, 0), [wx1, wy1] = toWorld(w, h);
    const wL = Math.ceil(t.w / 2 ** (t.maxLevel - L)), hL = Math.ceil(t.h / 2 ** (t.maxLevel - L));
    const tx0 = Math.max(0, Math.floor(pxOf(wx0, t.x0) / T)), ty0 = Math.max(0, Math.floor(pxOf(wy0, t.y0) / T));
    const tx1 = Math.min(Math.ceil(wL / T) - 1, Math.floor(pxOf(wx1, t.x0) / T)), ty1 = Math.min(Math.ceil(hL / T) - 1, Math.floor(pxOf(wy1, t.y0) / T));
    if ((tx1 - tx0 + 1) * (ty1 - ty0 + 1) > 400) return;
    for (let ty = ty0; ty <= ty1; ty++) for (let tx = tx0; tx <= tx1; tx++) {
      const e = img(`/topview/${L}/${tx}_${ty}.${t.ext}`);
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
      for (const LL of [L - 3, L - 1, L]) if (LL >= 0) drawTopLevel(LL);
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
    const big = s >= 0.12, lab = s >= 1.1;
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
$('#mBase').onchange = () => { mapState.base = $('#mBase').value; lsSet('base', mapState.base); map.draw(); };
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
