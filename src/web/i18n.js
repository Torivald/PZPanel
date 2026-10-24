// PZ Panel: выбор языка интерфейса (ru / en). Выбор хранится в браузере каждого пользователя (localStorage).
// Исходный текст панели — русский; английский подставляется «на лету»: словарь в lang_en.js (PZ_EN = [[рус, англ], ...],
// {} в строке — подставляемое значение). Так переводятся и тексты страницы, и сообщения сервера, и confirm/prompt.
(function () {
  const CYR = /[А-Яа-яЁё]/;
  let stored = null;
  try { stored = localStorage.getItem('pzp.lang'); } catch (e) {}
  const guess = (navigator.language || 'ru').toLowerCase().startsWith('ru') ? 'ru' : 'en';
  window.LANG = stored === 'ru' || stored === 'en' ? stored : guess;
  window.LOCALE = window.LANG === 'en' ? 'en-GB' : 'ru-RU';
  document.documentElement.lang = window.LANG;

  const esc = s => s.replace(/[.*+?^$()|[\]\\]/g, '\\$&').replace(/[{}]/g, m => '\\' + m);
  const WORD = /[А-Яа-яЁёA-Za-z0-9]/;
  const entries = [];
  const cache = new Map();

  function build() {
    if (entries.length || !window.PZ_EN) return;
    for (const [ru, en] of window.PZ_EN) {
      const parts = ru.split('{}');
      const anchor = parts.filter(p => CYR.test(p)).sort((a, b) => b.length - a.length)[0] || '';
      if (!anchor) continue;
      let src = parts.map(esc).join('(.+?)');
      if (ru.endsWith('{}')) src = src.replace(/\(\.\+\?\)$/, '(.+)'); // завершающий {} забирает остаток текста
      // короткие общие слова («Удалить», «сервер», «из {}») заменяем только когда это весь текст узла,
      // иначе они ломают длинные русские фразы
      const exact = !/^[МГКТ]Б$/.test(ru) && WORD.test(ru[0]) && parts.join('').length < 14;
      if (exact) { entries.push({ re: new RegExp('^(\\s*)' + src + '(\\s*)$'), en, anchor, len: ru.length, n: parts.length - 1, exact: true }); continue; }
      if (WORD.test(ru[0])) src = '(?<![А-Яа-яЁёA-Za-z0-9])' + src;
      if (WORD.test(ru[ru.length - 1])) src += '(?![А-Яа-яЁё])';
      entries.push({ re: new RegExp(src, 'g'), en, anchor, len: ru.length, n: parts.length - 1 });
    }
    entries.sort((a, b) => b.len - a.len);
  }

  function tr(text) {
    if (window.LANG !== 'en' || typeof text !== 'string' || !CYR.test(text)) return text;
    build();
    const hit = cache.get(text);
    if (hit !== undefined) return hit;
    let out = text;
    for (const e of entries) {
      if (!out.includes(e.anchor) && e.n === 0) continue;
      if (e.n > 0 && !out.includes(e.anchor)) continue;
      if (e.exact) {
        out = out.replace(e.re, (...m) => {
          let i = 1;
          return m[1] + e.en.replace(/\{\}/g, () => { const g = m[++i]; return typeof g === 'string' ? tr(g) : ''; }) + m[m.length - 3];
        });
        if (!CYR.test(out)) break;
        continue;
      }
      out = out.replace(e.re, (...m) => {
        let i = 0;
        return e.en.replace(/\{\}/g, () => { const g = m[++i]; return typeof g === 'string' ? tr(g) : ''; });
      });
      if (!CYR.test(out)) break;
    }
    if (cache.size > 5000) cache.clear();
    cache.set(text, out);
    return out;
  }
  window.tr = tr;

  // ---- DOM ----
  const ATTRS = ['title', 'placeholder', 'alt', 'aria-label'];
  function fixText(n) {
    const v = n.nodeValue;
    if (!v || !CYR.test(v)) return;
    const p = n.parentNode;
    if (p && (p.nodeName === 'SCRIPT' || p.nodeName === 'STYLE' || p.nodeName === 'TEXTAREA')) return;
    const t = tr(v);
    if (t !== v) n.nodeValue = t;
  }
  function fixEl(el) {
    for (const a of ATTRS) {
      const v = el.getAttribute && el.getAttribute(a);
      if (v && CYR.test(v)) { const t = tr(v); if (t !== v) el.setAttribute(a, t); }
    }
    if (el.nodeName === 'INPUT' && (el.type === 'button' || el.type === 'submit') && CYR.test(el.value)) el.value = tr(el.value);
    if (el.nodeName === 'OPTION' && CYR.test(el.textContent)) { /* текст узла переведётся ниже */ }
  }
  function walk(root) {
    if (root.nodeType === 3) { fixText(root); return; }
    if (root.nodeType !== 1) return;
    fixEl(root);
    const w = document.createTreeWalker(root, NodeFilter.SHOW_ELEMENT | NodeFilter.SHOW_TEXT);
    let n;
    while ((n = w.nextNode())) { if (n.nodeType === 3) fixText(n); else fixEl(n); }
  }

  if (window.LANG === 'en') {
    document.title = tr(document.title);
    walk(document.body);
    new MutationObserver(muts => {
      for (const m of muts) {
        if (m.type === 'childList') m.addedNodes.forEach(walk);
        else if (m.type === 'characterData') fixText(m.target);
        else if (m.type === 'attributes') fixEl(m.target);
      }
    }).observe(document.body, { childList: true, subtree: true, characterData: true, attributes: true, attributeFilter: ATTRS });
    const _c = window.confirm, _p = window.prompt, _a = window.alert;
    window.confirm = m => _c.call(window, tr(String(m)));
    window.prompt = (m, d) => _p.call(window, tr(String(m)), d);
    window.alert = m => _a.call(window, tr(String(m)));
  }

  // ---- переключатель ----
  window.setLang = l => { try { localStorage.setItem('pzp.lang', l); } catch (e) {} location.reload(); };
  const sel = document.getElementById('langSel');
  if (sel) { sel.value = window.LANG; sel.onchange = () => window.setLang(sel.value); }
})();
