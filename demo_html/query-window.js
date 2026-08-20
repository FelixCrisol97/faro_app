'use strict';
/* Ventana de consulta separada — mismo diseño que query_window_screen.dart:
   proceso/estado 100% independiente de la ventana principal (ni siquiera
   comparte STATE vía window.opener) — fiel a la arquitectura real de Faro
   (cada ventana es su propio motor/ProviderScope, todo lo compartido pasa
   por disco, nunca en vivo). Por eso esta página trae su propia copia
   mínima de la lógica de resaltado en vez de importar app.js. */

const SQL_KEYWORDS = new Set(['select','from','where','insert','into','update','set','delete','join','left','right','inner','outer','full','cross','on','group','by','order','having','limit','offset','and','or','not','in','is','null','like','between','exists','as','distinct','union','all','create','table','alter','drop','values','returning','case','when','then','else','end','asc','desc','with']);

function tokenizeSql(text) {
  const tokens = [];
  let i = 0; const n = text.length;
  const push = (s, e, cls) => { if (e > s) tokens.push({ start: s, end: e, cls }); };
  while (i < n) {
    const c = text[i];
    if (c === "'") { let j = i + 1; while (j < n) { if (text[j] === "'") { if (text[j+1] === "'") { j += 2; continue; } j++; break; } j++; } push(i, j, 'str'); i = j; continue; }
    if (c === '-' && text[i+1] === '-') { let j = text.indexOf('\n', i); if (j === -1) j = n; push(i, j, 'com'); i = j; continue; }
    if (c === '/' && text[i+1] === '*') { let j = text.indexOf('*/', i+2); j = j === -1 ? n : j + 2; push(i, j, 'com'); i = j; continue; }
    if (/[0-9]/.test(c)) { let j = i; while (j < n && /[0-9.]/.test(text[j])) j++; push(i, j, 'num'); i = j; continue; }
    if (/[A-Za-z_]/.test(c)) { let j = i; while (j < n && /[A-Za-z_0-9]/.test(text[j])) j++; const w = text.slice(i, j).toLowerCase(); push(i, j, SQL_KEYWORDS.has(w) ? 'kw' : null); i = j; continue; }
    push(i, i + 1, null); i++;
  }
  return tokens;
}
function escapeHtml(s) { return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;'); }
function buildHighlightedHtml(text) {
  const tokens = tokenizeSql(text);
  let html = '';
  for (const t of tokens) {
    const seg = escapeHtml(text.slice(t.start, t.end));
    html += t.cls ? `<span class="tok-${t.cls}">${seg}</span>` : seg;
  }
  return html;
}

function toast(message) {
  const stack = document.getElementById('toast-stack');
  const el = document.createElement('div'); el.className = 'toast'; el.textContent = message;
  stack.appendChild(el); setTimeout(() => el.remove(), 3000);
}

function findDb(id) {
  for (const s of DEMO.servers) { const d = s.databases.find(db => db.id === id); if (d) return { db: d, server: s }; }
  const d = DEMO.ungrouped.find(db => db.id === id);
  return d ? { db: d, server: null } : null;
}

document.addEventListener('DOMContentLoaded', () => {
  const params = new URLSearchParams(location.search);
  const dbId = params.get('db');
  const found = findDb(dbId) || { db: DEMO.ungrouped[0], server: null };
  const { db, server } = found;

  document.getElementById('qw-icon').innerHTML = icon('database', 18);
  document.getElementById('qw-title').textContent = server ? `${server.name} · ${db.name}` : db.name;
  document.getElementById('qw-engine').textContent = db.engine === 'sqlServer' ? 'SQL Server' : 'PostgreSQL';
  document.getElementById('qw-run').innerHTML = icon('play', 15) + '<span>F5</span>';
  document.getElementById('qw-format').innerHTML = icon('align_left', 15) + '<span>Formatear</span>';

  const input = document.getElementById('qw-input');
  const highlight = document.getElementById('qw-highlight');
  const gutter = document.getElementById('qw-gutter');

  function refresh() {
    highlight.innerHTML = buildHighlightedHtml(input.value);
    const lines = input.value.split('\n').length;
    let g = ''; for (let i = 1; i <= lines; i++) g += `<div>${i}</div>`;
    gutter.innerHTML = g;
    highlight.scrollTop = input.scrollTop; highlight.scrollLeft = input.scrollLeft;
    gutter.scrollTop = input.scrollTop;
  }
  input.addEventListener('input', refresh);
  input.addEventListener('scroll', refresh);
  refresh();

  function runQuery() {
    const resultsCard = document.getElementById('qw-results');
    resultsCard.innerHTML = `<div class="row gap-2 t-body-sm t-muted" style="padding:var(--space-2);"><span class="spinner"></span> Ejecutando…</div>`;
    setTimeout(() => {
      const source = DEMO.sampleResult;
      let thead = '<tr>' + source.columns.map((c, i) => `<th>${c}<span class="col-type">${source.types[i]}</span></th>`).join('') + '</tr>';
      let tbody = source.rows.map(r => '<tr>' + r.map(v => `<td>${v}</td>`).join('') + '</tr>').join('');
      resultsCard.innerHTML = `
        <div class="results-pills-row"><span class="tag tag-success">${db.name} · ${source.rows.length} filas</span><span class="flex-1"></span><button class="btn btn-secondary" id="qw-export">${icon('download',15)}<span>Exportar CSV</span></button></div>
        <div class="results-table-wrap" style="margin-top:var(--space-3);"><table class="results-grid"><thead>${thead}</thead><tbody>${tbody}</tbody></table></div>`;
      document.getElementById('qw-export').onclick = () => {
        const rows = [source.columns, ...source.rows];
        const csv = rows.map(r => r.join(',')).join('\r\n');
        const blob = new Blob([csv], { type: 'text/csv;charset=utf-8;' });
        const url = URL.createObjectURL(blob);
        const a = document.createElement('a'); a.href = url; a.download = 'faro_export.csv';
        document.body.appendChild(a); a.click(); a.remove(); URL.revokeObjectURL(url);
        toast('CSV exportado');
      };
      toast('Consulta ejecutada');
    }, 600);
  }
  document.getElementById('qw-run').onclick = runQuery;
  document.getElementById('qw-format').onclick = () => {
    input.value = input.value.replace(/\b(FROM|WHERE|GROUP BY|ORDER BY|JOIN)\b/gi, '\n$1');
    refresh(); toast('SQL formateado');
  };
  document.addEventListener('keydown', (e) => { if (e.key === 'F5') { e.preventDefault(); runQuery(); } });

  document.getElementById('qw-split').addEventListener('mousedown', (e) => {
    const toolbar = document.getElementById('qw-toolbar');
    const startY = e.clientY, startH = toolbar.offsetHeight;
    function onMove(ev) { toolbar.style.flex = `0 0 ${Math.max(160, startH + (ev.clientY - startY))}px`; }
    function onUp() { document.removeEventListener('mousemove', onMove); document.removeEventListener('mouseup', onUp); }
    document.addEventListener('mousemove', onMove); document.addEventListener('mouseup', onUp);
  });
});
