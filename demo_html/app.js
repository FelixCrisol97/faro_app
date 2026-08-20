'use strict';
/* ==========================================================================
   Faro — demo interactiva (réplica visual/funcional de la app real)
   Todo el estado vive en memoria (STATE) + localStorage para tema/acento,
   nada se manda a ningún servidor — es una demo de front-end puro para
   presentar la propuesta a un cliente.
   ========================================================================== */

/* ------------------------------ STATE ------------------------------ */
const STATE = {
  theme: localStorage.getItem('faro-demo-theme') || 'light',
  accent: localStorage.getItem('faro-demo-accent') || 'indigo',
  massMode: false,
  selectedDbIds: new Set(),
  expandedServers: new Set(DEMO.servers.map(s => s.id)),
  expandedDbs: new Set(),
  loadedSchema: new Set(),
  expandedSchemaCategories: {}, // dbId -> Set(categoryKey)
  searchQuery: '',
  activePanel: null, // null | 'historial' | 'favoritos' | 'apariencia'
  tabs: [], // {id, serverId, databaseId, label}
  activeTabId: null, // null = home "Consulta"
  contexts: {
    home: { editorText: '', filePath: null, savedText: null, result: null, running: false, paginated: false },
  },
  history: DEMO.history.slice(),
  favorites: DEMO.favorites.slice(),
  dragPayload: null,
};

function ctx(id) {
  const key = id || 'home';
  if (!STATE.contexts[key]) {
    STATE.contexts[key] = { editorText: '', filePath: null, savedText: null, result: null, running: false, paginated: false };
  }
  return STATE.contexts[key];
}
function activeCtx() { return ctx(STATE.activeTabId); }

function findServer(id) { return DEMO.servers.find(s => s.id === id) || null; }
function findDb(id) {
  for (const s of DEMO.servers) {
    const d = s.databases.find(db => db.id === id);
    if (d) return { db: d, server: s };
  }
  const d = DEMO.ungrouped.find(db => db.id === id);
  if (d) return { db: d, server: null };
  return null;
}
function allDatabases() {
  return [...DEMO.servers.flatMap(s => s.databases), ...DEMO.ungrouped];
}
function totalDatabaseCount() { return allDatabases().length; }

/* ------------------------------ THEME / ACCENT ------------------------------ */
function applyTheme() {
  document.documentElement.setAttribute('data-theme', STATE.theme);
  document.documentElement.setAttribute('data-accent', STATE.accent);
}
function setTheme(isDark) {
  STATE.theme = isDark ? 'dark' : 'light';
  localStorage.setItem('faro-demo-theme', STATE.theme);
  applyTheme();
  renderAll(); // colors baked into some inline SVG/markup choices
}
function setAccent(name) {
  STATE.accent = name;
  localStorage.setItem('faro-demo-accent', name);
  applyTheme();
  renderAll();
}

/* ------------------------------ TOAST ------------------------------ */
function toast(message) {
  const stack = document.getElementById('toast-stack');
  const el = document.createElement('div');
  el.className = 'toast';
  el.textContent = message;
  stack.appendChild(el);
  setTimeout(() => el.remove(), 3000);
}

/* ------------------------------ MODAL SHELL ------------------------------ */
function openModal(title, bodyHtml, actionsHtml, wide) {
  document.getElementById('modal-title').textContent = title;
  document.getElementById('modal-body').innerHTML = bodyHtml;
  document.getElementById('modal-actions').innerHTML = actionsHtml;
  document.getElementById('modal-el').classList.toggle('modal-wide', !!wide);
  document.getElementById('modal-backdrop').classList.add('open');
  const firstInput = document.querySelector('#modal-body input, #modal-body textarea');
  const primaryBtn = document.querySelector('#modal-actions .btn-primary, #modal-actions .btn-danger');
  setTimeout(() => { (primaryBtn || firstInput)?.focus(); }, 30);
}
function closeModal() {
  document.getElementById('modal-backdrop').classList.remove('open');
}
document.addEventListener('DOMContentLoaded', () => {
  document.getElementById('modal-backdrop').addEventListener('mousedown', (e) => {
    if (e.target.id === 'modal-backdrop') closeModal();
  });
});

/* ------------------------------ CONTEXT MENU ------------------------------ */
function showCtxMenu(items, x, y) {
  const menu = document.getElementById('ctx-menu');
  menu.innerHTML = items.map((it, i) => it.sep
    ? '<div class="ctx-menu-sep"></div>'
    : `<div class="ctx-menu-item" data-i="${i}">${icon(it.icon, 15)}<span>${it.label}</span></div>`
  ).join('');
  menu.querySelectorAll('.ctx-menu-item').forEach(el => {
    el.addEventListener('click', () => {
      hideCtxMenu();
      const it = items[Number(el.dataset.i)];
      it.onClick && it.onClick();
    });
  });
  const w = 260, h = items.length * 38 + 16;
  const left = Math.min(x, window.innerWidth - w - 8);
  const top = Math.min(y, window.innerHeight - h - 8);
  menu.style.left = left + 'px';
  menu.style.top = top + 'px';
  menu.classList.add('open');
}
function hideCtxMenu() { document.getElementById('ctx-menu').classList.remove('open'); }
document.addEventListener('mousedown', (e) => {
  const menu = document.getElementById('ctx-menu');
  if (menu.classList.contains('open') && !menu.contains(e.target)) hideCtxMenu();
});

/* ==========================================================================
   TREE (sidebar)
   ========================================================================== */
function toggleServerExpanded(id) {
  STATE.expandedServers.has(id) ? STATE.expandedServers.delete(id) : STATE.expandedServers.add(id);
  renderTree();
}
function toggleDbExpanded(id) {
  STATE.expandedDbs.has(id) ? STATE.expandedDbs.delete(id) : STATE.expandedDbs.add(id);
  renderTree();
}
function loadSchema(dbId) {
  STATE.loadedSchema.add(dbId);
  renderTree();
}
function toggleSchemaCategory(dbId, cat) {
  const set = STATE.expandedSchemaCategories[dbId] || (STATE.expandedSchemaCategories[dbId] = new Set());
  set.has(cat) ? set.delete(cat) : set.add(cat);
  renderTree();
}

function selectDatabase(dbId) {
  if (STATE.massMode) {
    STATE.selectedDbIds.has(dbId) ? STATE.selectedDbIds.delete(dbId) : STATE.selectedDbIds.add(dbId);
  } else {
    if (STATE.selectedDbIds.has(dbId)) {
      STATE.selectedDbIds.clear();
    } else {
      STATE.selectedDbIds.clear();
      STATE.selectedDbIds.add(dbId);
      STATE.expandedDbs.add(dbId);
      STATE.loadedSchema.add(dbId);
    }
  }
  renderTree();
  renderToolbar();
}

function toggleDbMode(dbId) {
  const found = findDb(dbId);
  if (!found) return;
  if (found.db.mode === 'development') {
    found.db.mode = 'readOnly';
    renderTree(); renderToolbar();
    return;
  }
  openModal(
    'Cambiar a Consultas sin restricciones',
    `<p class="t-body">En este modo se permite ejecutar cualquier tipo de consulta contra "<b>${found.db.name}</b>", incluyendo las que modifican datos.</p>`,
    `<button class="btn btn-secondary" id="mode-cancel">Cancelar</button>
     <button class="btn btn-primary" id="mode-confirm">Confirmar</button>`
  );
  document.getElementById('mode-cancel').onclick = closeModal;
  document.getElementById('mode-confirm').onclick = () => {
    found.db.mode = 'development';
    closeModal(); renderTree(); renderToolbar();
    toast('Modo actualizado');
  };
}

function testConnection(dbId) {
  const found = findDb(dbId);
  if (!found) return;
  found.db.testStatus = 'testing';
  renderTree();
  setTimeout(() => {
    found.db.testStatus = 'connected';
    found.db.testError = null;
    renderTree();
    toast(`Conexión exitosa: ${found.db.name}`);
  }, 700);
}

function dbIconAndTooltipForTest(status) {
  switch (status) {
    case 'testing': return { html: '<span class="spinner"></span>', tip: 'Probando…' };
    case 'connected': return { html: `<span style="color:var(--success-base);display:flex;">${icon('circle_check', 13)}</span>`, tip: 'Conectado (clic para volver a probar)' };
    case 'failed': return { html: `<span style="color:var(--error-base);display:flex;">${icon('circle_alert', 13)}</span>`, tip: 'Error de conexión (clic para volver a probar)' };
    default: return { html: `<span style="color:var(--text-muted);display:flex;">${icon('plug', 13)}</span>`, tip: 'Probar conexión' };
  }
}

const SCHEMA_CATEGORIES = [
  { key: 'tables', label: 'Tablas', icon: 'columns' },
  { key: 'views', label: 'Vistas', icon: 'eye' },
  { key: 'functions', label: 'Funciones', icon: 'function_square' },
  { key: 'procedures', label: 'Procedimientos', icon: 'terminal' },
  { key: 'triggers', label: 'Triggers', icon: 'zap' },
];

function schemaObjectMenuItems(dbId, engine, type, name) {
  const base = [
    { icon: 'search', label: 'Generar SELECT', onClick: () => insertGeneratedSql(`SELECT *\nFROM ${name};`) },
  ];
  if (type === 'tables') {
    base.push({ icon: 'pencil', label: 'Generar UPDATE', onClick: () => insertGeneratedSql(generateUpdateSql(name)) });
  }
  base.push({ icon: 'file_code', label: 'Generar script CREATE', onClick: () => insertGeneratedSql(generateCreateSql(engine, type, name)) });
  if (type === 'tables') {
    base.push({ icon: 'upload', label: 'Importar CSV…', onClick: () => openImportCsvDialog(dbId, name) });
  }
  base.push({ sep: true });
  base.push({ icon: 'panel_top', label: 'Abrir en pestaña', onClick: () => openInTab(dbId) });
  base.push({ icon: 'external_link', label: 'Abrir en nueva ventana', onClick: () => openInWindow(dbId) });
  return base;
}

function generateUpdateSql(table) {
  const cols = DEMO.tableColumns[table] || [{ name: 'id', pk: true }, { name: 'valor', pk: false }];
  const pk = cols.find(c => c.pk) || cols[0];
  const setCols = cols.filter(c => c !== pk).map(c => `  ${c.name} = ?`).join(',\n');
  return `UPDATE ${table}\nSET\n${setCols}\nWHERE ${pk.name} = ?;`;
}
function generateCreateSql(engine, type, name) {
  if (type === 'tables') {
    const cols = DEMO.tableColumns[name] || [{ name: 'id', type: 'integer', pk: true }];
    const lines = cols.map(c => `  ${c.name} ${c.type}${c.pk ? ' PRIMARY KEY' : ''}`).join(',\n');
    return `CREATE TABLE ${name} (\n${lines}\n);`;
  }
  return `-- Script CREATE de ${name} (${engine === 'sqlServer' ? 'SQL Server' : 'PostgreSQL'})\n-- (definición real obtenida del catálogo del motor)`;
}
function insertGeneratedSql(sql) {
  STATE.activeTabId = null;
  ctx(null).editorText = sql;
  renderTabsBar(); renderEditorFromState();
  toast('Script insertado en Consulta');
}

function openInTab(dbId) {
  const found = findDb(dbId);
  if (!found) return;
  const id = 'tab-' + Math.random().toString(36).slice(2, 8);
  STATE.tabs.push({ id, serverId: found.server ? found.server.id : null, databaseId: dbId,
    label: found.server ? `${found.server.name} · ${found.db.name}` : found.db.name });
  STATE.activeTabId = id;
  ctx(id).editorText = '';
  renderTabsBar(); renderEditorFromState(); renderToolbar();
}
function openInWindow(dbId) {
  const found = findDb(dbId);
  if (!found) return;
  window.open('query-window.html?db=' + encodeURIComponent(dbId), '_blank',
    'width=980,height=680');
}

function renderTree() {
  const q = STATE.searchQuery.trim().toLowerCase();
  const matchesDb = (db) => db.name.toLowerCase().includes(q) || db.databaseName.toLowerCase().includes(q);
  const visibleServers = q ? DEMO.servers.filter(s => s.name.toLowerCase().includes(q) || s.databases.some(matchesDb)) : DEMO.servers;

  let html = '';
  for (const server of visibleServers) {
    html += renderServerNode(server);
  }
  html += `<div class="tree-drop-zone" data-drop="server-tail" style="height:6px;"></div>`;
  html += `<div style="height:${8}px;"></div>`;
  if (!q) html += renderUngroupedSection();

  const scroll = document.getElementById('tree-scroll');
  scroll.innerHTML = html;
  wireTreeEvents(scroll);
}

function renderServerNode(server) {
  const expanded = STATE.expandedServers.has(server.id) || STATE.searchQuery.trim() !== '';
  let rows = '';
  if (expanded) {
    if (STATE.massMode && server.databases.length) {
      const allSel = server.databases.every(d => STATE.selectedDbIds.has(d.id));
      rows += `<div style="text-align:right;"><button class="btn btn-ghost" data-mass-toggle-server="${server.id}">${allSel ? 'Ninguna' : 'Todas'}</button></div>`;
    }
    for (const db of server.databases) rows += renderDatabaseRow(db, server);
  }
  return `
    <div class="tree-row" draggable="true" data-drag="server:${server.id}" data-drop-target="server:${server.id}" data-ctx="server:${server.id}">
      <span class="drag-handle">${icon('grip_vertical', 13)}</span>
      <span class="icon-tap" data-toggle-server="${server.id}" style="padding:2px;">
        <span class="chevron ${expanded ? 'expanded' : ''}">${icon('chevron_right', 14)}</span>
      </span>
      <span style="color:var(--accent-base); display:flex;">${icon('server', 13)}</span>
      <span class="tree-row-label" style="font-weight:600;" data-toggle-server="${server.id}">${escapeHtml(server.name)}</span>
      <span class="tree-row-count">${server.databases.length}</span>
      <span class="icon-tap" data-tooltip="Editar servidor" data-edit-server="${server.id}">${icon('pencil', 13)}</span>
      <span class="icon-tap" data-tooltip="Agregar base de datos" data-add-db="${server.id}">${icon('database', 13)}<span style="position:relative;left:-4px;top:3px;">${icon('plus', 8)}</span></span>
      <span class="icon-tap" data-tooltip="Credenciales por defecto de este servidor" data-server-creds="${server.id}">${icon('key', 13)}</span>
      <span class="icon-tap" data-tooltip="Eliminar servidor" data-remove-server="${server.id}">${icon('trash', 13)}</span>
    </div>
    ${expanded ? `<div class="tree-children" data-drop-append="${server.id}">${rows}</div>` : ''}
  `;
}

function renderUngroupedSection() {
  let rows = '';
  for (const db of DEMO.ungrouped) rows += renderDatabaseRow(db, null);
  return `
    <div class="section-label">
      <span>Sin grupo</span>
      <span class="icon-tap" data-tooltip="Agregar base de datos" data-add-db="ungrouped">${icon('plus', 14)}</span>
    </div>
    <div data-drop-append="ungrouped">${rows}</div>
    <div class="tree-drop-zone" data-drop="ungrouped-tail" style="height:10px;"></div>
  `;
}

function renderDatabaseRow(db, server) {
  const selected = STATE.selectedDbIds.has(db.id);
  const expanded = STATE.expandedDbs.has(db.id);
  const loaded = STATE.loadedSchema.has(db.id);
  const test = dbIconAndTooltipForTest(db.testStatus);
  const modeIcon = db.mode === 'readOnly'
    ? `<span style="color:var(--success-base);display:flex;">${icon('lock', 13)}</span>`
    : `<span style="color:var(--warn-base);display:flex;">${icon('lock_open', 13)}</span>`;
  const modeTip = db.mode === 'readOnly' ? 'Solo lectura (clic para cambiar)' : 'Consultas sin restricciones (clic para cambiar)';

  let schemaHtml = '';
  if (expanded) {
    schemaHtml = loaded
      ? renderSchemaCategories(db)
      : `<div class="load-structure-btn"><button class="btn btn-ghost" data-load-schema="${db.id}">${icon('list_tree', 14)}<span>Cargar estructura</span></button></div>`;
  }

  return `
    <div class="tree-row ${selected ? 'selected' : ''}" draggable="true" data-drag="db:${db.id}:${server ? server.id : ''}" data-drop-target="db:${db.id}:${server ? server.id : ''}">
      <span class="drag-handle">${icon('grip_vertical', 13)}</span>
      <span class="icon-tap" data-toggle-db="${db.id}" style="padding:2px;">
        <span class="chevron ${expanded ? 'expanded' : ''}">${icon('chevron_right', 12)}</span>
      </span>
      <span style="color:${selected ? 'var(--accent-soft-text)' : 'var(--text-muted)'};display:flex;">${icon('database', 13)}</span>
      <span class="tree-row-label" data-tooltip="${db.host ? 'IP: ' + escapeHtml(db.host) : 'Sin host configurado'}" data-select-db="${db.id}" style="cursor:pointer; ${selected ? 'color:var(--accent-soft-text);' : ''}">${escapeHtml(db.name)}</span>
      <span class="icon-tap" data-tooltip="${test.tip}" data-test-db="${db.id}">${test.html}</span>
      <span class="icon-tap" data-tooltip="${modeTip}" data-toggle-mode="${db.id}">${modeIcon}</span>
      <span class="icon-tap" data-tooltip="Editar base de datos" data-edit-db="${db.id}">${icon('pencil', 13)}</span>
      <span class="icon-tap" data-tooltip="Credenciales solo para esta base de datos (opcional)" data-db-creds="${db.id}">${icon('key', 13)}</span>
      <span class="icon-tap" data-tooltip="Eliminar base de datos" data-remove-db="${db.id}">${icon('trash', 13)}</span>
    </div>
    ${expanded ? `<div class="db-children">${schemaHtml}</div>` : ''}
  `;
}

function renderSchemaCategories(db) {
  const schema = DEMO.schemas[db.id] || { tables: [], views: [], functions: [], procedures: [], triggers: [] };
  const expandedSet = STATE.expandedSchemaCategories[db.id] || new Set();
  let html = '<div class="input-icon-wrap" style="margin-bottom:4px;"><span class="leading" style="left:8px;">' + icon('search', 12) + '</span><input class="input" placeholder="Buscar en el esquema…" style="padding:6px 6px 6px 26px; font-size:12px;" disabled></div>';
  for (const cat of SCHEMA_CATEGORIES) {
    const items = schema[cat.key] || [];
    const open = expandedSet.has(cat.key);
    html += `<div class="schema-group-header" data-toggle-schema-cat="${db.id}:${cat.key}">
      <span class="chevron ${open ? 'expanded' : ''}" style="width:12px;">${icon('chevron_right', 11)}</span>
      <span style="color:var(--text-muted); display:flex;">${icon(cat.icon, 12)}</span>
      <span style="font-size:12px; flex:1;">${cat.label}</span>
      ${open ? `<span class="schema-badge">${items.length}</span><span class="icon-tap" style="padding:2px;" data-refresh-schema-cat="${db.id}:${cat.key}">${icon('refresh', 11)}</span>` : ''}
    </div>`;
    if (open) {
      if (items.length === 0) {
        html += `<div class="t-caption" style="padding-left:26px;">Sin objetos.</div>`;
      } else {
        for (const name of items) {
          html += `<div class="schema-object-row" data-schema-obj="${db.id}:${cat.key}:${escapeAttr(name)}">
            <span style="width:12px;"></span>
            <span style="color:var(--text-muted); display:flex;">${icon(cat.icon, 11)}</span>
            <span class="text-ellipsis" style="flex:1;">${escapeHtml(name)}</span>
          </div>`;
        }
      }
    }
  }
  return html;
}

function wireTreeEvents(root) {
  root.querySelectorAll('[data-toggle-server]').forEach(el => el.addEventListener('click', (e) => { e.stopPropagation(); toggleServerExpanded(el.dataset.toggleServer); }));
  root.querySelectorAll('[data-toggle-db]').forEach(el => el.addEventListener('click', (e) => { e.stopPropagation(); toggleDbExpanded(el.dataset.toggleDb); }));
  root.querySelectorAll('[data-select-db]').forEach(el => el.addEventListener('click', (e) => { e.stopPropagation(); selectDatabase(el.dataset.selectDb); }));
  root.querySelectorAll('[data-select-db]').forEach(el => el.addEventListener('contextmenu', (e) => {
    e.preventDefault(); e.stopPropagation();
    const dbId = el.dataset.selectDb;
    const found = findDb(dbId);
    const items = [
      { icon: 'panel_top', label: 'Abrir en pestaña', onClick: () => openInTab(dbId) },
      { icon: 'external_link', label: 'Abrir en nueva ventana', onClick: () => openInWindow(dbId) },
    ];
    if (found.server) items.push({ icon: 'database_zap', label: 'Descubrir más bases de datos en esta IP', onClick: () => openDiscoverDialog(found.server, found.db) });
    showCtxMenu(items, e.clientX, e.clientY);
  }));
  root.querySelectorAll('[data-test-db]').forEach(el => el.addEventListener('click', (e) => { e.stopPropagation(); testConnection(el.dataset.testDb); }));
  root.querySelectorAll('[data-toggle-mode]').forEach(el => el.addEventListener('click', (e) => { e.stopPropagation(); toggleDbMode(el.dataset.toggleMode); }));
  root.querySelectorAll('[data-edit-server]').forEach(el => el.addEventListener('click', (e) => { e.stopPropagation(); openEditServerDialog(el.dataset.editServer); }));
  root.querySelectorAll('[data-add-db]').forEach(el => el.addEventListener('click', (e) => { e.stopPropagation(); openAddDatabaseDialog(el.dataset.addDb === 'ungrouped' ? null : el.dataset.addDb); }));
  root.querySelectorAll('[data-server-creds]').forEach(el => el.addEventListener('click', (e) => { e.stopPropagation(); openCredentialsDialog('server', el.dataset.serverCreds); }));
  root.querySelectorAll('[data-remove-server]').forEach(el => el.addEventListener('click', (e) => { e.stopPropagation(); openRemoveDialog('server', el.dataset.removeServer); }));
  root.querySelectorAll('[data-edit-db]').forEach(el => el.addEventListener('click', (e) => { e.stopPropagation(); openEditDatabaseDialog(el.dataset.editDb); }));
  root.querySelectorAll('[data-db-creds]').forEach(el => el.addEventListener('click', (e) => { e.stopPropagation(); openCredentialsDialog('database', el.dataset.dbCreds); }));
  root.querySelectorAll('[data-remove-db]').forEach(el => el.addEventListener('click', (e) => { e.stopPropagation(); openRemoveDialog('database', el.dataset.removeDb); }));
  root.querySelectorAll('[data-load-schema]').forEach(el => el.addEventListener('click', (e) => { e.stopPropagation(); loadSchema(el.dataset.loadSchema); }));
  root.querySelectorAll('[data-toggle-schema-cat]').forEach(el => el.addEventListener('click', (e) => {
    e.stopPropagation();
    const [dbId, cat] = el.dataset.toggleSchemaCat.split(':');
    toggleSchemaCategory(dbId, cat);
  }));
  root.querySelectorAll('[data-refresh-schema-cat]').forEach(el => el.addEventListener('click', (e) => {
    e.stopPropagation();
    const icnEl = el.querySelector('svg');
    if (icnEl) icnEl.classList.add('spin');
    setTimeout(() => toast('Estructura actualizada'), 500);
  }));
  root.querySelectorAll('[data-schema-obj]').forEach(el => el.addEventListener('contextmenu', (e) => {
    e.preventDefault(); e.stopPropagation();
    const [dbId, cat, name] = el.dataset.schemaObj.split(':');
    const found = findDb(dbId);
    showCtxMenu(schemaObjectMenuItems(dbId, found.db.engine, cat, name), e.clientX, e.clientY);
  }));
  root.querySelectorAll('[data-schema-obj]').forEach(el => el.addEventListener('click', (e) => {
    e.stopPropagation();
    const [dbId, cat, name] = el.dataset.schemaObj.split(':');
    const found = findDb(dbId);
    showCtxMenu(schemaObjectMenuItems(dbId, found.db.engine, cat, name), e.clientX, e.clientY);
  }));
  root.querySelectorAll('[data-mass-toggle-server]').forEach(el => el.addEventListener('click', (e) => {
    e.stopPropagation();
    const server = findServer(el.dataset.massToggleServer);
    const allSel = server.databases.every(d => STATE.selectedDbIds.has(d.id));
    server.databases.forEach(d => allSel ? STATE.selectedDbIds.delete(d.id) : STATE.selectedDbIds.add(d.id));
    renderTree(); renderToolbar();
  }));

  // Server context menu (reorder)
  root.querySelectorAll('[data-ctx^="server:"]').forEach(el => el.addEventListener('contextmenu', (e) => {
    e.preventDefault();
    const id = el.dataset.ctx.split(':')[1];
    const idx = DEMO.servers.findIndex(s => s.id === id);
    const items = [];
    if (idx > 0) items.push({ icon: 'chevrons_up', label: 'Mover al inicio', onClick: () => { DEMO.servers.splice(idx, 1); DEMO.servers.unshift(findServer(id) || DEMO.servers[idx]); renderTree(); } });
    if (idx > 0) items.push({ icon: 'chevron_up', label: 'Subir', onClick: () => { const [s] = DEMO.servers.splice(idx, 1); DEMO.servers.splice(idx - 1, 0, s); renderTree(); } });
    if (idx < DEMO.servers.length - 1) items.push({ icon: 'chevron_down', label: 'Bajar', onClick: () => { const [s] = DEMO.servers.splice(idx, 1); DEMO.servers.splice(idx + 1, 0, s); renderTree(); } });
    if (items.length) showCtxMenu(items, e.clientX, e.clientY);
  }));

  wireDragAndDrop(root);
}

/* -------- Drag & drop (reorder / regroup — simplified but real) -------- */
function wireDragAndDrop(root) {
  root.querySelectorAll('[data-drag]').forEach(el => {
    el.addEventListener('dragstart', (e) => {
      STATE.dragPayload = el.dataset.drag;
      e.dataTransfer.effectAllowed = 'move';
      e.dataTransfer.setData('text/plain', el.dataset.drag);
    });
  });
  root.querySelectorAll('[data-drop-target]').forEach(el => {
    el.addEventListener('dragover', (e) => { e.preventDefault(); el.style.outline = '2px solid var(--accent-base)'; el.style.outlineOffset = '-2px'; });
    el.addEventListener('dragleave', () => { el.style.outline = ''; });
    el.addEventListener('drop', (e) => {
      e.preventDefault(); el.style.outline = '';
      handleDrop(el.dataset.dropTarget);
    });
  });
  root.querySelectorAll('[data-drop-append]').forEach(el => {
    el.addEventListener('dragover', (e) => e.preventDefault());
    el.addEventListener('drop', (e) => {
      e.preventDefault();
      const targetServerId = el.dataset.dropAppend === 'ungrouped' ? null : el.dataset.dropAppend;
      handleDropOnServer(targetServerId);
    });
  });
  root.querySelectorAll('[data-drop]').forEach(el => {
    el.addEventListener('dragover', (e) => { e.preventDefault(); el.classList.add('drag-over'); });
    el.addEventListener('dragleave', () => el.classList.remove('drag-over'));
    el.addEventListener('drop', (e) => {
      e.preventDefault(); el.classList.remove('drag-over');
      if (el.dataset.drop === 'server-tail') moveServerToEnd();
      if (el.dataset.drop === 'ungrouped-tail') handleDropOnServer(null);
    });
  });
}

function handleDropOnServer(targetServerId) {
  const payload = STATE.dragPayload;
  if (!payload) return;
  const [type, id, fromServerId] = payload.split(':');
  if (type !== 'db') return;
  moveDatabase(id, fromServerId || null, targetServerId);
  STATE.dragPayload = null;
  renderTree();
}

function handleDrop(targetKey) {
  const payload = STATE.dragPayload;
  if (!payload) return;
  const [ttype, tid] = targetKey.split(':');
  const [type, id, fromServerId] = payload.split(':');
  if (type === 'server' && ttype === 'server') {
    if (id === tid) return;
    const from = DEMO.servers.findIndex(s => s.id === id);
    const to = DEMO.servers.findIndex(s => s.id === tid);
    const [s] = DEMO.servers.splice(from, 1);
    DEMO.servers.splice(to, 0, s);
  } else if (type === 'db' && ttype === 'server') {
    moveDatabase(id, fromServerId || null, tid);
  } else if (type === 'db' && ttype === 'db') {
    const targetId = targetKey.split(':')[1];
    if (id === targetId) return;
    const foundTarget = findDb(targetId);
    const targetServerId = foundTarget.server ? foundTarget.server.id : null;
    if (fromServerId === '' && targetServerId === null) {
      // merge two ungrouped databases into a brand-new server
      openMergeDialog(id, targetId);
      STATE.dragPayload = null;
      return;
    }
    moveDatabase(id, fromServerId || null, targetServerId);
  }
  STATE.dragPayload = null;
  renderTree();
}

function moveServerToEnd() {
  const payload = STATE.dragPayload;
  if (!payload) return;
  const [type, id] = payload.split(':');
  if (type !== 'server') return;
  const idx = DEMO.servers.findIndex(s => s.id === id);
  if (idx === -1) return;
  const [s] = DEMO.servers.splice(idx, 1);
  DEMO.servers.push(s);
  STATE.dragPayload = null;
  renderTree();
}

function removeDbFromWhereverItIs(dbId) {
  for (const s of DEMO.servers) {
    const idx = s.databases.findIndex(d => d.id === dbId);
    if (idx !== -1) return s.databases.splice(idx, 1)[0];
  }
  const idx = DEMO.ungrouped.findIndex(d => d.id === dbId);
  if (idx !== -1) return DEMO.ungrouped.splice(idx, 1)[0];
  return null;
}
function moveDatabase(dbId, fromServerId, toServerId) {
  const db = removeDbFromWhereverItIs(dbId);
  if (!db) return;
  if (toServerId === null) {
    DEMO.ungrouped.push(db);
  } else {
    const server = findServer(toServerId);
    if (server) server.databases.push(db);
    else DEMO.ungrouped.push(db);
  }
}

function openMergeDialog(dbId1, dbId2) {
  const d1 = findDb(dbId1)?.db, d2 = findDb(dbId2)?.db;
  if (!d1 || !d2) return;
  openModal('Crear servidor',
    `<p class="t-body">Vas a agrupar <b>${escapeHtml(d1.name)}</b> y <b>${escapeHtml(d2.name)}</b> en un nuevo servidor.</p>
     <div class="field"><label>Nombre del servidor</label><input class="input" id="merge-name" value="Nuevo servidor" autofocus></div>`,
    `<button class="btn btn-secondary" id="merge-cancel">Cancelar</button>
     <button class="btn btn-primary" id="merge-confirm">Crear</button>`);
  document.getElementById('merge-cancel').onclick = closeModal;
  document.getElementById('merge-confirm').onclick = () => {
    const name = document.getElementById('merge-name').value.trim() || 'Nuevo servidor';
    removeDbFromWhereverItIs(dbId1);
    removeDbFromWhereverItIs(dbId2);
    const newServer = { id: 'srv-' + Math.random().toString(36).slice(2, 8), name, databases: [d1, d2] };
    DEMO.servers.push(newServer);
    STATE.expandedServers.add(newServer.id);
    closeModal(); renderTree();
    toast(`Servidor "${name}" creado`);
  };
}

/* ==========================================================================
   TABS BAR
   ========================================================================== */
function renderTabsBar() {
  const bar = document.getElementById('tabs-bar');
  if (STATE.tabs.length === 0) { bar.innerHTML = ''; bar.style.display = 'none'; return; }
  bar.style.display = 'flex';
  let html = `<div class="tab-chip ${STATE.activeTabId === null ? 'active' : ''}" data-activate-tab="home">
      <span class="tab-chip-label">${icon('search', 13)}<span>Consulta</span></span>
    </div>`;
  for (const tab of STATE.tabs) {
    const active = STATE.activeTabId === tab.id;
    html += `<div class="tab-chip ${active ? 'active' : ''}">
      <span class="tab-chip-label" data-activate-tab="${tab.id}">${icon('database', 13)}<span>${escapeHtml(tab.label)}</span></span>
      <span class="tab-chip-close icon-tap" data-close-tab="${tab.id}">${icon('x', 13)}</span>
    </div>`;
  }
  bar.innerHTML = html;
  bar.querySelectorAll('[data-activate-tab]').forEach(el => el.addEventListener('click', () => {
    STATE.activeTabId = el.dataset.activateTab === 'home' ? null : el.dataset.activateTab;
    renderTabsBar(); renderEditorFromState(); renderToolbar(); renderResults();
  }));
  bar.querySelectorAll('[data-close-tab]').forEach(el => el.addEventListener('click', (e) => {
    e.stopPropagation();
    const id = el.dataset.closeTab;
    const idx = STATE.tabs.findIndex(t => t.id === id);
    STATE.tabs.splice(idx, 1);
    delete STATE.contexts[id];
    if (STATE.activeTabId === id) STATE.activeTabId = null;
    renderTabsBar(); renderEditorFromState(); renderToolbar(); renderResults();
  }));
}

/* ==========================================================================
   TOOLBAR
   ========================================================================== */
function currentTargets() {
  if (STATE.activeTabId) {
    const tab = STATE.tabs.find(t => t.id === STATE.activeTabId);
    if (!tab) return [];
    const found = findDb(tab.databaseId);
    return found ? [{ server: found.server, database: found.db }] : [];
  }
  return [...STATE.selectedDbIds].map(id => findDb(id)).filter(Boolean).map(f => ({ server: f.server, database: f.db }));
}

function renderToolbar() {
  const targets = currentTargets();
  const titleEl = document.getElementById('toolbar-title');
  const total = totalDatabaseCount();
  const serversInvolved = new Map();
  targets.forEach(t => serversInvolved.set(t.server ? t.server.id : '', t.server ? t.server.name : null));

  let title;
  if (serversInvolved.size === 0) title = 'Consulta';
  else if (serversInvolved.size === 1 && targets.length === 1) {
    title = targets[0].server ? `${targets[0].server.name} · ${targets[0].database.name}` : targets[0].database.name;
  } else if (serversInvolved.size === 1) {
    const val = [...serversInvolved.values()][0];
    title = val || `${targets.length} bases sin grupo`;
  } else {
    title = `${serversInvolved.size} servidores`;
  }
  titleEl.textContent = title;

  const modeTagEl = document.getElementById('toolbar-mode-tag');
  if (targets.length === 0) { modeTagEl.innerHTML = ''; }
  else {
    const allRO = targets.every(t => t.database.mode === 'readOnly');
    const allDev = targets.every(t => t.database.mode === 'development');
    const label = allRO ? 'Solo lectura' : allDev ? 'Consultas sin restricciones' : 'Modos mixtos';
    const cls = allRO ? 'tag-neutral' : 'tag-warn-soft';
    modeTagEl.innerHTML = `<span class="tag ${cls}">${escapeHtml(label)}</span>`;
  }

  const countEl = document.getElementById('toolbar-selection-count');
  countEl.textContent = STATE.activeTabId ? '' : `${targets.length} de ${total} bases seleccionadas`;

  const c = activeCtx();
  const filename = c.filePath ? c.filePath.split('/').pop() : null;
  const actions = document.getElementById('toolbar-actions');
  actions.innerHTML = `
    ${c.running
      ? `<button class="btn btn-secondary" id="btn-run">${icon('square', 15)}<span>Cancelar</span></button>`
      : `<button class="btn btn-primary" id="btn-run" ${targets.length === 0 ? 'disabled' : ''}>${icon('play', 15)}<span>F5</span></button>`}
    <button class="btn btn-secondary" id="btn-load">${icon('upload', 15)}<span>Cargar</span></button>
    <button class="btn btn-secondary" id="btn-save" ${c.filePath && c.editorText !== c.savedText ? '' : 'disabled'}>${icon('save', 15)}<span>Guardar</span></button>
    <button class="btn btn-secondary" id="btn-export-sql">${icon('download', 15)}<span>Exportar SQL</span></button>
    <button class="btn btn-secondary" id="btn-format">${icon('align_left', 15)}<span>Formatear</span></button>
    <button class="btn btn-secondary" id="btn-favorite">${icon('star', 15)}<span>Favorito</span></button>
    ${filename ? `<div class="file-indicator">${icon('file_text', 13)}<span>${escapeHtml(filename)}</span>${c.editorText !== c.savedText ? '<span class="unsaved-dot">•</span>' : ''}</div>` : ''}
  `;
  document.getElementById('btn-run').onclick = () => c.running ? cancelRun() : runQuery();
  document.getElementById('btn-load').onclick = loadFromFileDemo;
  document.getElementById('btn-save').onclick = saveToFileDemo;
  document.getElementById('btn-export-sql').onclick = exportSqlDemo;
  document.getElementById('btn-format').onclick = formatSqlDemo;
  document.getElementById('btn-favorite').onclick = () => openSaveFavoriteDialog(c.editorText);
}

/* ==========================================================================
   SQL EDITOR
   ========================================================================== */
const SQL_KEYWORDS = new Set(['select','from','where','insert','into','update','set','delete','join','left','right','inner','outer','full','cross','on','group','by','order','having','limit','offset','and','or','not','in','is','null','like','between','exists','as','distinct','union','all','create','table','alter','drop','values','returning','case','when','then','else','end','asc','desc','with']);

function tokenizeSql(text) {
  const tokens = [];
  let i = 0;
  const n = text.length;
  const push = (start, end, cls) => { if (end > start) tokens.push({ start, end, cls }); };
  while (i < n) {
    const c = text[i];
    if (c === "'") {
      let j = i + 1;
      while (j < n) { if (text[j] === "'") { if (text[j + 1] === "'") { j += 2; continue; } j++; break; } j++; }
      push(i, j, 'str'); i = j; continue;
    }
    if (c === '-' && text[i + 1] === '-') {
      let j = text.indexOf('\n', i); if (j === -1) j = n;
      push(i, j, 'com'); i = j; continue;
    }
    if (c === '/' && text[i + 1] === '*') {
      let j = text.indexOf('*/', i + 2); j = j === -1 ? n : j + 2;
      push(i, j, 'com'); i = j; continue;
    }
    if (/[0-9]/.test(c)) {
      let j = i; while (j < n && /[0-9.]/.test(text[j])) j++;
      push(i, j, 'num'); i = j; continue;
    }
    if (/[A-Za-z_]/.test(c)) {
      let j = i; while (j < n && /[A-Za-z_0-9]/.test(text[j])) j++;
      const word = text.slice(i, j).toLowerCase();
      push(i, j, SQL_KEYWORDS.has(word) ? 'kw' : null);
      i = j; continue;
    }
    push(i, i + 1, null); i++;
  }
  return tokens;
}
function escapeHtml(s) { return String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;'); }
function escapeAttr(s) { return String(s).replace(/"/g, '&quot;'); }

function buildHighlightedHtml(text, searchRanges, activeMatchIndex, wordRanges) {
  const tokens = tokenizeSql(text);
  const points = new Set([0, text.length]);
  tokens.forEach(t => { points.add(t.start); points.add(t.end); });
  (searchRanges || []).forEach(r => { points.add(r.start); points.add(r.end); });
  (wordRanges || []).forEach(r => { points.add(r.start); points.add(r.end); });
  const sorted = Array.from(points).sort((a, b) => a - b);
  let html = '';
  for (let k = 0; k < sorted.length - 1; k++) {
    const s = sorted[k], e = sorted[k + 1];
    if (s >= e) continue;
    const tok = tokens.find(t => t.start <= s && e <= t.end);
    const cls = tok ? tok.cls : null;
    const classes = [];
    if (cls) classes.push('tok-' + cls);
    let matchIdx = -1;
    if (searchRanges) matchIdx = searchRanges.findIndex(r => r.start <= s && e <= r.end);
    if (matchIdx !== -1) classes.push(matchIdx === activeMatchIndex ? 'search-hl-active' : 'search-hl');
    else if (wordRanges && wordRanges.some(r => r.start <= s && e <= r.end)) classes.push('word-hl');
    const segment = escapeHtml(text.slice(s, e));
    html += classes.length ? `<span class="${classes.join(' ')}">${segment}</span>` : segment;
  }
  return html || '';
}

const EDITOR_SEARCH = { open: false, query: '', matches: [], activeIndex: -1 };

function findMatches(text, query) {
  if (!query) return [];
  const lower = text.toLowerCase();
  const q = query.toLowerCase();
  const matches = [];
  let i = 0;
  while (true) {
    const idx = lower.indexOf(q, i);
    if (idx === -1) break;
    matches.push({ start: idx, end: idx + q.length });
    i = idx + q.length;
  }
  return matches;
}

function wordAt(text, offset) {
  const re = /[A-Za-z_][A-Za-z0-9_]*/g;
  let m;
  while ((m = re.exec(text))) {
    if (m.index <= offset && offset <= m.index + m[0].length) return { word: m[0], start: m.index, end: m.index + m[0].length };
  }
  return null;
}

let lastText = '';
let highlightWordRange = null;

function renderEditorFromState() {
  const c = activeCtx();
  const input = document.getElementById('sql-editor-input');
  input.value = c.editorText;
  lastText = c.editorText;
  EDITOR_SEARCH.open = false;
  EDITOR_SEARCH.query = ''; EDITOR_SEARCH.matches = []; EDITOR_SEARCH.activeIndex = -1;
  document.getElementById('editor-search-bar').classList.remove('open');
  document.getElementById('editor-search-toggle').classList.remove('hidden');
  highlightWordRange = null;
  refreshEditorVisuals();
}

function refreshEditorVisuals() {
  const input = document.getElementById('sql-editor-input');
  const highlight = document.getElementById('sql-highlight');
  const gutter = document.getElementById('sql-gutter');
  const text = input.value;
  const searchRanges = EDITOR_SEARCH.open ? EDITOR_SEARCH.matches : null;
  highlight.innerHTML = buildHighlightedHtml(text, searchRanges, EDITOR_SEARCH.activeIndex, highlightWordRange ? [highlightWordRange] : null);
  const lineCount = text.split('\n').length;
  let g = '';
  for (let i = 1; i <= lineCount; i++) g += `<div>${i}</div>`;
  gutter.innerHTML = g;
  syncScroll();
}
function syncScroll() {
  const input = document.getElementById('sql-editor-input');
  const highlight = document.getElementById('sql-highlight');
  const gutter = document.getElementById('sql-gutter');
  highlight.scrollTop = input.scrollTop; highlight.scrollLeft = input.scrollLeft;
  gutter.scrollTop = input.scrollTop;
}

function onEditorInput() {
  const input = document.getElementById('sql-editor-input');
  const c = activeCtx();
  c.editorText = input.value;
  if (input.value !== lastText) {
    highlightWordRange = null;
    if (EDITOR_SEARCH.open) recomputeSearchMatches(false);
  }
  lastText = input.value;
  updateAutocomplete();
  refreshEditorVisuals();
  renderToolbar();
}

function onEditorSelectionChange() {
  const input = document.getElementById('sql-editor-input');
  if (input.selectionStart === input.selectionEnd) {
    const w = wordAt(input.value, input.selectionStart);
    highlightWordRange = w && !EDITOR_SEARCH.open ? { start: w.start, end: w.end } : null;
  } else {
    highlightWordRange = null;
  }
  refreshEditorVisuals();
}

/* ---- Autocomplete ---- */
const AC = { open: false, items: [], activeIndex: 0, replaceFrom: 0, replaceLen: 0 };

function detectTrigger(upToCursor) {
  let m = /from\s+([A-Za-z0-9_]*)$/i.exec(upToCursor);
  if (m) return { target: 'table', partial: m[1], replaceFrom: m.index + m[0].length - m[1].length };
  m = /(?:where|and|or|on|having|order\s+by|group\s+by|select|,\s*)\s*([A-Za-z0-9_]*)$/i.exec(upToCursor);
  if (m && /(where|and|or|on|having|by|select|,)\s*[A-Za-z0-9_]*$/i.test(upToCursor)) {
    return { target: 'column', partial: m[1], replaceFrom: m.index + m[0].length - m[1].length };
  }
  return null;
}

function updateAutocomplete() {
  const input = document.getElementById('sql-editor-input');
  const popup = document.getElementById('autocomplete-popup');
  if (input.selectionStart !== input.selectionEnd) { closeAutocomplete(); return; }
  const upToCursor = input.value.slice(0, input.selectionStart);
  const trigger = detectTrigger(upToCursor);
  if (!trigger) { closeAutocomplete(); return; }
  const pool = trigger.target === 'table' ? DEMO.tableNames : DEMO.columnNames;
  const items = pool.filter(n => n.toLowerCase().startsWith(trigger.partial.toLowerCase())).slice(0, 50);
  if (items.length === 0) { closeAutocomplete(); return; }
  AC.open = true; AC.items = items; AC.activeIndex = 0;
  AC.replaceFrom = trigger.replaceFrom; AC.replaceLen = trigger.partial.length;
  renderAutocomplete();
}
function renderAutocomplete() {
  const popup = document.getElementById('autocomplete-popup');
  popup.innerHTML = AC.items.map((it, i) => `<div class="autocomplete-item ${i === AC.activeIndex ? 'active' : ''}" data-ac-i="${i}">${escapeHtml(it)}</div>`).join('');
  popup.classList.add('open');
  popup.querySelectorAll('[data-ac-i]').forEach(el => el.addEventListener('mousedown', (e) => {
    e.preventDefault();
    applyAutocomplete(Number(el.dataset.acI));
  }));
  positionAutocomplete();
}
function positionAutocomplete() {
  const input = document.getElementById('sql-editor-input');
  const popup = document.getElementById('autocomplete-popup');
  // Approximate caret position via a mirror div.
  const mirror = document.createElement('div');
  const cs = getComputedStyle(input);
  ['fontFamily','fontSize','lineHeight','padding','border','boxSizing','width','whiteSpace','wordBreak'].forEach(p => mirror.style[p] = cs[p]);
  mirror.style.position = 'absolute'; mirror.style.visibility = 'hidden'; mirror.style.height = 'auto';
  mirror.style.top = '-9999px';
  const upTo = input.value.slice(0, AC.replaceFrom);
  mirror.textContent = upTo;
  const marker = document.createElement('span');
  marker.textContent = '​';
  mirror.appendChild(marker);
  input.parentElement.appendChild(mirror);
  const top = marker.offsetTop - input.scrollTop;
  const left = marker.offsetLeft - input.scrollLeft;
  mirror.remove();
  popup.style.top = (top + 22) + 'px';
  popup.style.left = Math.min(left, input.clientWidth - 244) + 'px';
}
function closeAutocomplete() { AC.open = false; document.getElementById('autocomplete-popup').classList.remove('open'); }
function applyAutocomplete(index) {
  const input = document.getElementById('sql-editor-input');
  const name = AC.items[index];
  const text = input.value;
  const newText = text.slice(0, AC.replaceFrom) + name + text.slice(AC.replaceFrom + AC.replaceLen);
  input.value = newText;
  const pos = AC.replaceFrom + name.length;
  input.setSelectionRange(pos, pos);
  closeAutocomplete();
  onEditorInput();
  input.focus();
}

/* ---- Editor search (Ctrl+F) ---- */
function openEditorSearch() {
  EDITOR_SEARCH.open = true;
  document.getElementById('editor-search-bar').classList.add('open');
  document.getElementById('editor-search-toggle').classList.add('hidden');
  document.getElementById('editor-search-input').focus();
  recomputeSearchMatches(true);
}
function closeEditorSearch() {
  EDITOR_SEARCH.open = false; EDITOR_SEARCH.matches = []; EDITOR_SEARCH.activeIndex = -1;
  document.getElementById('editor-search-bar').classList.remove('open');
  document.getElementById('editor-search-toggle').classList.remove('hidden');
  document.getElementById('sql-editor-input').focus();
  refreshEditorVisuals();
}
function recomputeSearchMatches(jump) {
  const input = document.getElementById('sql-editor-input');
  const q = document.getElementById('editor-search-input').value;
  EDITOR_SEARCH.query = q;
  EDITOR_SEARCH.matches = findMatches(input.value, q);
  if (EDITOR_SEARCH.activeIndex >= EDITOR_SEARCH.matches.length) EDITOR_SEARCH.activeIndex = EDITOR_SEARCH.matches.length ? EDITOR_SEARCH.matches.length - 1 : -1;
  if (jump && EDITOR_SEARCH.matches.length) EDITOR_SEARCH.activeIndex = 0;
  document.getElementById('editor-search-count').textContent = EDITOR_SEARCH.matches.length
    ? `${EDITOR_SEARCH.activeIndex + 1} de ${EDITOR_SEARCH.matches.length}` : '0 de 0';
  refreshEditorVisuals();
  if (jump) jumpToActiveMatch();
}
function searchNext() {
  if (!EDITOR_SEARCH.matches.length) return;
  EDITOR_SEARCH.activeIndex = (EDITOR_SEARCH.activeIndex + 1) % EDITOR_SEARCH.matches.length;
  document.getElementById('editor-search-count').textContent = `${EDITOR_SEARCH.activeIndex + 1} de ${EDITOR_SEARCH.matches.length}`;
  refreshEditorVisuals(); jumpToActiveMatch();
}
function searchPrev() {
  if (!EDITOR_SEARCH.matches.length) return;
  EDITOR_SEARCH.activeIndex = (EDITOR_SEARCH.activeIndex - 1 + EDITOR_SEARCH.matches.length) % EDITOR_SEARCH.matches.length;
  document.getElementById('editor-search-count').textContent = `${EDITOR_SEARCH.activeIndex + 1} de ${EDITOR_SEARCH.matches.length}`;
  refreshEditorVisuals(); jumpToActiveMatch();
}
function jumpToActiveMatch() {
  const m = EDITOR_SEARCH.matches[EDITOR_SEARCH.activeIndex];
  if (!m) return;
  const input = document.getElementById('sql-editor-input');
  const before = input.value.slice(0, m.start);
  const lines = before.split('\n').length;
  input.scrollTop = Math.max(0, (lines - 3) * 21);
  syncScroll();
}

/* ==========================================================================
   RUN / RESULTS
   ========================================================================== */
function runQuery() {
  const targets = currentTargets();
  if (targets.length === 0) return;
  const c = activeCtx();
  c.running = true;
  renderToolbar();
  renderResultsRunning();
  setTimeout(() => {
    c.running = false;
    const source = targets.length > 1 ? DEMO.massResult : DEMO.sampleResult;
    c.result = { columns: source.columns, types: source.types, rows: source.rows.slice(),
      perDb: targets.map(t => ({ name: t.database.name, host: t.database.host, blocked: t.database.mode === 'readOnly' && /update|delete|insert/i.test(c.editorText), rowCount: source.rows.length })) };
    c.paginated = false;
    addHistoryEntry(c, targets);
    renderToolbar(); renderResults();
    toast('Consulta ejecutada');
  }, 650);
}
function cancelRun() {
  const c = activeCtx();
  c.running = false;
  renderToolbar(); renderResultsFromState();
  toast('Consulta cancelada');
}
function addHistoryEntry(c, targets) {
  const serverLabel = targets.length === 1
    ? (targets[0].server ? targets[0].server.name : 'Sin grupo')
    : `${new Set(targets.map(t => t.server ? t.server.id : '')).size} servidores`;
  STATE.history.unshift({
    id: 'h' + Math.random().toString(36).slice(2, 8),
    time: new Date().toLocaleTimeString('es-MX', { hour12: false }),
    query: c.editorText || '(vacío)',
    server: serverLabel, dbCount: targets.length,
    rows: c.result ? c.result.rows.length : 0, status: 'success',
  });
  if (STATE.activePanel === 'historial') renderSidePanelContent();
}

function renderResultsRunning() {
  document.getElementById('results-empty').classList.add('hidden');
  const body = document.getElementById('results-body');
  body.classList.remove('hidden');
  document.getElementById('results-pills').innerHTML = `<span class="row gap-2 t-body-sm t-muted"><span class="spinner"></span> Ejecutando…</span>`;
  document.getElementById('results-table-wrap').innerHTML = '';
  document.getElementById('load-more-bar').classList.add('hidden');
}

function renderResultsFromState() { renderResults(); }

function renderResults() {
  const c = activeCtx();
  const emptyEl = document.getElementById('results-empty');
  const bodyEl = document.getElementById('results-body');
  if (c.running) { renderResultsRunning(); return; }
  if (!c.result) {
    emptyEl.classList.remove('hidden'); bodyEl.classList.add('hidden');
    return;
  }
  emptyEl.classList.add('hidden'); bodyEl.classList.remove('hidden');
  const result = c.result;

  document.getElementById('results-pills').innerHTML = result.perDb.map(o => o.blocked
    ? `<span class="tag tag-success" data-tooltip="Esta base de datos está en modo Solo lectura: solo se permiten consultas SELECT">${icon('lock', 12)}${escapeHtml(o.name)} · Solo lectura</span>`
    : `<span class="tag tag-success">${escapeHtml(o.name)} · ${o.rowCount} filas</span>`
  ).join('');

  const wrap = document.getElementById('results-table-wrap');
  let thead = '<tr>' + result.columns.map((col, i) => `<th>${escapeHtml(col)}<span class="col-type">${escapeHtml(result.types[i] || '')}</span></th>`).join('') + '</tr>';
  let tbody = result.rows.map(row => '<tr>' + row.map(v => `<td>${v === null ? '<span class="t-muted">NULL</span>' : escapeHtml(String(v))}</td>`).join('') + '</tr>').join('');
  wrap.innerHTML = `<table class="results-grid"><thead>${thead}</thead><tbody>${tbody}</tbody></table>`;

  document.getElementById('btn-export-csv').innerHTML = `${icon('download', 15)}<span>Exportar CSV</span>`;
  document.getElementById('btn-export-csv').onclick = () => exportCsv(result);
  const releaseBtn = document.getElementById('btn-release-results');
  releaseBtn.classList.remove('hidden');
  releaseBtn.innerHTML = icon('eraser', 16);
  releaseBtn.onclick = () => { c.result = null; renderResults(); toast('Resultados liberados de memoria'); };
  document.getElementById('load-more-bar').classList.add('hidden');
}

function exportCsv(result) {
  const rows = [result.columns, ...result.rows];
  const csv = rows.map(r => r.map(v => {
    const s = v === null || v === undefined ? '' : String(v);
    return /[",\n]/.test(s) ? '"' + s.replace(/"/g, '""') + '"' : s;
  }).join(',')).join('\r\n');
  const blob = new Blob([csv], { type: 'text/csv;charset=utf-8;' });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  const stamp = new Date().toISOString().replace(/[:T]/g, '-').slice(0, 16);
  a.href = url; a.download = `faro_export_productos_${stamp}.csv`;
  document.body.appendChild(a); a.click(); a.remove();
  URL.revokeObjectURL(url);
  toast('CSV exportado');
}

/* ==========================================================================
   TOOLBAR ACTIONS: cargar / guardar / exportar sql / formatear / favorito
   ========================================================================== */
function loadFromFileDemo() {
  const input = document.createElement('input');
  input.type = 'file'; input.accept = '.sql,.txt';
  input.onchange = () => {
    const file = input.files[0];
    if (!file) return;
    const reader = new FileReader();
    reader.onload = () => {
      const c = activeCtx();
      c.editorText = String(reader.result);
      c.filePath = file.name; c.savedText = c.editorText;
      renderEditorFromState(); renderToolbar();
      toast(`Cargado: ${file.name}`);
    };
    reader.readAsText(file);
  };
  input.click();
}
function saveToFileDemo() {
  const c = activeCtx();
  c.savedText = c.editorText;
  renderToolbar();
  toast(`Guardado: ${c.filePath || 'consulta.sql'}`);
}
function exportSqlDemo() {
  const c = activeCtx();
  const blob = new Blob([c.editorText], { type: 'text/plain;charset=utf-8;' });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url; a.download = 'consulta.sql';
  document.body.appendChild(a); a.click(); a.remove();
  URL.revokeObjectURL(url);
  toast('SQL exportado');
}
function formatSqlDemo() {
  const c = activeCtx();
  const kwUpper = [...SQL_KEYWORDS].sort((a, b) => b.length - a.length);
  let text = c.editorText;
  const tokens = tokenizeSql(text);
  let out = '';
  let last = 0;
  for (const t of tokens) {
    out += text.slice(last, t.start);
    const seg = text.slice(t.start, t.end);
    out += t.cls === 'kw' ? seg.toUpperCase() : seg;
    last = t.end;
  }
  out += text.slice(last);
  out = out.replace(/\b(FROM|WHERE|GROUP BY|ORDER BY|HAVING|LIMIT|OFFSET|JOIN|LEFT JOIN|INNER JOIN|SET|VALUES)\b/g, '\n$1');
  c.editorText = out;
  c.filePath && (c.savedText = c.savedText); // formatting keeps "unsaved" if a file is open
  renderEditorFromState();
  toast('SQL formateado');
}

/* ==========================================================================
   SIDE PANELS (Historial / Favoritos / Apariencia)
   ========================================================================== */
function openPanel(name) {
  STATE.activePanel = STATE.activePanel === name ? null : name;
  renderSidePanel();
}
function closePanel() { STATE.activePanel = null; renderSidePanel(); }

function renderSidePanel() {
  const overlay = document.getElementById('side-panel');
  const barrier = document.getElementById('dismiss-barrier');
  overlay.classList.toggle('open', !!STATE.activePanel);
  barrier.classList.toggle('active', !!STATE.activePanel);
  renderSidePanelContent();
  renderTreePanelIcons();
}
function renderSidePanelContent() {
  const content = document.getElementById('side-panel-content');
  if (STATE.activePanel === 'historial') content.innerHTML = renderHistorialHtml();
  else if (STATE.activePanel === 'favoritos') content.innerHTML = renderFavoritosHtml();
  else if (STATE.activePanel === 'apariencia') content.innerHTML = renderAparienciaHtml();
  else { content.innerHTML = ''; return; }
  wireSidePanelEvents(content);
}

function renderHistorialHtml() {
  if (STATE.history.length === 0) return `<div class="empty-state"><div class="t-body">Todavía no se ha ejecutado ninguna consulta.</div></div>`;
  return STATE.history.map(h => `
    <div class="card history-card">
      <div class="history-card-top">
        <span class="history-time">${h.time}</span>
        <span class="flex-1"></span>
        <span class="icon-tap" data-tooltip="Copiar consulta" data-hist-copy="${h.id}">${icon('copy', 15)}</span>
        <span class="icon-tap" data-tooltip="Guardar como favorito" data-hist-fav="${h.id}">${icon('star', 15)}</span>
        <button class="btn btn-ghost" data-hist-reuse="${h.id}">Reusar</button>
      </div>
      <div class="history-query font-mono">${escapeHtml(h.query)}</div>
      <div class="history-meta">
        <span>${escapeHtml(h.server)}</span><span>·</span>
        <span>${h.dbCount} BD(s)</span><span>·</span>
        <span>${h.rows} filas</span>
        <span class="tag ${h.status === 'success' ? 'tag-success' : 'tag-warn-soft'}">${h.status === 'success' ? 'Éxito' : 'Parcial'}</span>
      </div>
    </div>`).join('');
}
function renderFavoritosHtml() {
  if (STATE.favorites.length === 0) return `<div class="empty-state"><div class="t-body">Guarda una consulta desde Consulta para verla aquí.</div></div>`;
  return `<div class="favorites-grid">${STATE.favorites.map(f => `
    <div class="card favorite-card">
      <div class="t-heading text-ellipsis">${escapeHtml(f.name)}</div>
      <div class="fav-body"><div class="fav-query">${escapeHtml(f.query)}</div></div>
      <div class="fav-actions">
        <button class="btn btn-primary" data-fav-use="${f.id}">Usar</button>
        <span class="flex-1"></span>
        <span class="btn-icon" data-tooltip="Eliminar" data-fav-remove="${f.id}" style="width:36px;height:36px;">${icon('trash', 16)}</span>
      </div>
    </div>`).join('')}</div>`;
}
function renderAparienciaHtml() {
  const isDark = STATE.theme === 'dark';
  const accents = ['indigo','violet','blue','teal','rose','amber'];
  const accentColors = { indigo:'#6366F1', violet:'#8B5CF6', blue:'#2563EB', teal:'#0D9488', rose:'#E11D48', amber:'#D97706' };
  return `
    <div class="card" style="padding:var(--space-4); margin-bottom:var(--space-3);">
      <div class="t-heading" style="margin-bottom:var(--space-2);">Tema</div>
      <div class="segmented">
        <button class="${!isDark ? 'active' : ''}" data-set-theme="light">${icon('sun', 13)}<span>Claro</span></button>
        <button class="${isDark ? 'active' : ''}" data-set-theme="dark">${icon('moon', 13)}<span>Oscuro</span></button>
      </div>
    </div>
    <div class="card" style="padding:var(--space-4); margin-bottom:var(--space-3);">
      <div class="t-heading" style="margin-bottom:var(--space-2);">Color de acento</div>
      <div class="accent-swatches">
        ${accents.map(a => `<span class="accent-swatch ${STATE.accent === a ? 'selected' : ''}" data-set-accent="${a}"><span class="accent-swatch-dot" style="background:${accentColors[a]}"></span></span>`).join('')}
      </div>
    </div>
    <div class="card" style="padding:var(--space-4);">
      <div class="t-heading" style="margin-bottom:var(--space-2);">Atajos de teclado</div>
      ${shortcutGroupHtml('Consulta', [['F5','Ejecutar / cancelar'],['Ctrl+G','Guardar el archivo abierto']])}
      ${shortcutGroupHtml('Editor SQL', [['Ctrl+F','Buscar en el script'],['Ctrl + / Ctrl+Rueda','Acercar (zoom)'],['Ctrl - / Ctrl+Rueda','Alejar (zoom)'],['Ctrl+0','Restablecer el zoom'],['↑ / ↓','Moverse entre sugerencias de autocompletado'],['Enter / Tab','Aceptar la sugerencia'],['Esc','Cerrar las sugerencias']])}
      ${shortcutGroupHtml('Buscador del editor (Ctrl+F)', [['Enter','Siguiente coincidencia'],['Shift+Enter','Coincidencia anterior'],['Esc','Cerrar el buscador']])}
    </div>
  `;
}
function shortcutGroupHtml(title, rows) {
  return `<div class="shortcut-group">
    <div class="shortcut-group-title">${escapeHtml(title)}</div>
    ${rows.map(([k, d]) => `<div class="shortcut-row"><span class="shortcut-key">${escapeHtml(k)}</span><span class="shortcut-desc">${escapeHtml(d)}</span></div>`).join('')}
  </div>`;
}

function wireSidePanelEvents(root) {
  root.querySelectorAll('[data-hist-reuse]').forEach(el => el.addEventListener('click', () => {
    const h = STATE.history.find(x => x.id === el.dataset.histReuse);
    STATE.activeTabId = null; ctx(null).editorText = h.query;
    closePanel(); renderTabsBar(); renderEditorFromState(); renderToolbar();
  }));
  root.querySelectorAll('[data-hist-copy]').forEach(el => el.addEventListener('click', () => {
    const h = STATE.history.find(x => x.id === el.dataset.histCopy);
    navigator.clipboard?.writeText(h.query).catch(() => {});
    toast('Consulta copiada al portapapeles');
  }));
  root.querySelectorAll('[data-hist-fav]').forEach(el => el.addEventListener('click', () => {
    const h = STATE.history.find(x => x.id === el.dataset.histFav);
    openSaveFavoriteDialog(h.query);
  }));
  root.querySelectorAll('[data-fav-use]').forEach(el => el.addEventListener('click', () => {
    const f = STATE.favorites.find(x => x.id === el.dataset.favUse);
    STATE.activeTabId = null; ctx(null).editorText = f.query;
    closePanel(); renderTabsBar(); renderEditorFromState(); renderToolbar();
  }));
  root.querySelectorAll('[data-fav-remove]').forEach(el => el.addEventListener('click', () => {
    STATE.favorites = STATE.favorites.filter(x => x.id !== el.dataset.favRemove);
    renderSidePanelContent();
  }));
  root.querySelectorAll('[data-set-theme]').forEach(el => el.addEventListener('click', () => setTheme(el.dataset.setTheme === 'dark')));
  root.querySelectorAll('[data-set-accent]').forEach(el => el.addEventListener('click', () => setAccent(el.dataset.setAccent)));
}

function renderTreePanelIcons() {
  const wrap = document.getElementById('tree-panel-icons');
  const panels = [
    { key: 'historial', icon: 'history', label: 'Historial' },
    { key: 'favoritos', icon: 'star', label: 'Favoritos' },
    { key: 'apariencia', icon: 'palette', label: 'Apariencia' },
  ];
  wrap.innerHTML = panels.map(p => `<span class="icon-tap" data-tooltip="${p.label}" data-open-panel="${p.key}" style="color:${STATE.activePanel === p.key ? 'var(--accent-base)' : 'var(--text-muted)'};">${icon(p.icon, 16)}</span>`).join('');
  wrap.querySelectorAll('[data-open-panel]').forEach(el => el.addEventListener('click', () => openPanel(el.dataset.openPanel)));
}

/* ==========================================================================
   DIALOGS
   ========================================================================== */
function openAddDatabaseDialog(serverId) {
  openModal('Agregar base de datos', `
    <div class="segmented" id="add-db-engine">
      <button class="active" data-eng="postgres">PostgreSQL</button>
      <button data-eng="sqlServer">SQL Server</button>
    </div>
    <div class="field"><label>Alias</label><input class="input" id="add-db-alias" placeholder="Bodega Norte"></div>
    <div class="field"><label>Host</label><input class="input" id="add-db-host" placeholder="192.168.1.10:5432"></div>
    <div class="field"><label>Nombre real de la base de datos</label><input class="input" id="add-db-name" placeholder="bodega"></div>
    <div class="field"><label>Usuario (opcional)</label><input class="input" id="add-db-user"></div>
    <div class="field"><label>Contraseña (opcional)</label><input class="input" type="password" id="add-db-pass"></div>
  `, `<button class="btn btn-secondary" id="add-db-cancel">Cancelar</button><button class="btn btn-primary" id="add-db-confirm" autofocus>Agregar</button>`);
  let engine = 'postgres';
  document.querySelectorAll('#add-db-engine button').forEach(b => b.addEventListener('click', () => {
    document.querySelectorAll('#add-db-engine button').forEach(x => x.classList.remove('active'));
    b.classList.add('active'); engine = b.dataset.eng;
  }));
  document.getElementById('add-db-cancel').onclick = closeModal;
  document.getElementById('add-db-confirm').onclick = () => {
    const alias = document.getElementById('add-db-alias').value.trim();
    const host = document.getElementById('add-db-host').value.trim();
    const name = document.getElementById('add-db-name').value.trim();
    if (!alias || !host || !name) { toast('Alias, Host y el nombre real son obligatorios — no se agregó nada.'); return; }
    const db = { id: 'db-' + Math.random().toString(36).slice(2, 8), name: alias, databaseName: name, host, engine, mode: 'readOnly', testStatus: 'idle' };
    if (serverId) findServer(serverId).databases.push(db); else DEMO.ungrouped.push(db);
    closeModal(); renderTree();
    toast(`Base de datos "${alias}" agregada`);
  };
}

function openAddServerDialogNote() {
  // Not reachable in the real 2026-08-19 app (grouping now happens only via
  // drag-merge) — kept out of the demo intentionally to match current behavior.
}

function openEditServerDialog(serverId) {
  const server = findServer(serverId);
  if (!server) return;
  openModal('Editar servidor', `
    <div class="field"><label>Nombre</label><input class="input" id="edit-srv-name" value="${escapeAttr(server.name)}"></div>
  `, `<button class="btn btn-secondary" id="edit-srv-cancel">Cancelar</button><button class="btn btn-primary" id="edit-srv-confirm" autofocus>Guardar</button>`);
  document.getElementById('edit-srv-cancel').onclick = closeModal;
  document.getElementById('edit-srv-confirm').onclick = () => {
    const name = document.getElementById('edit-srv-name').value.trim();
    if (name) server.name = name;
    closeModal(); renderTree(); renderToolbar();
  };
}

function openEditDatabaseDialog(dbId) {
  const found = findDb(dbId);
  if (!found) return;
  const db = found.db;
  openModal('Editar base de datos', `
    <div class="segmented" id="edit-db-engine">
      <button class="${db.engine === 'postgres' ? 'active' : ''}" data-eng="postgres">PostgreSQL</button>
      <button class="${db.engine === 'sqlServer' ? 'active' : ''}" data-eng="sqlServer">SQL Server</button>
    </div>
    <div class="field"><label>Alias</label><input class="input" id="edit-db-alias" value="${escapeAttr(db.name)}"></div>
    <div class="field"><label>Host</label><input class="input" id="edit-db-host" value="${escapeAttr(db.host)}"></div>
    <div class="field"><label>Nombre real de la base de datos</label><input class="input" id="edit-db-name" value="${escapeAttr(db.databaseName)}"></div>
    <div class="field"><label>Usuario (opcional)</label><input class="input" id="edit-db-user" placeholder="(sin cambios)"></div>
    <div class="field"><label>Contraseña (opcional)</label><input class="input" type="password" id="edit-db-pass" placeholder="(sin cambios)"></div>
  `, `<button class="btn btn-secondary" id="edit-db-cancel">Cancelar</button><button class="btn btn-primary" id="edit-db-confirm" autofocus>Guardar</button>`);
  let engine = db.engine;
  document.querySelectorAll('#edit-db-engine button').forEach(b => b.addEventListener('click', () => {
    document.querySelectorAll('#edit-db-engine button').forEach(x => x.classList.remove('active'));
    b.classList.add('active'); engine = b.dataset.eng;
  }));
  document.getElementById('edit-db-cancel').onclick = closeModal;
  document.getElementById('edit-db-confirm').onclick = () => {
    const alias = document.getElementById('edit-db-alias').value.trim();
    const host = document.getElementById('edit-db-host').value.trim();
    const name = document.getElementById('edit-db-name').value.trim();
    if (alias) db.name = alias;
    if (host) db.host = host;
    if (name) db.databaseName = name;
    db.engine = engine;
    closeModal(); renderTree(); renderToolbar();
    toast('Base de datos actualizada');
  };
}

function openCredentialsDialog(scope, id) {
  const isServer = scope === 'server';
  const server = isServer ? findServer(id) : findDb(id)?.server;
  const title = isServer ? `Credenciales — ${findServer(id).name}` : `Credenciales — ${findDb(id).db.name}`;
  const helpText = isServer
    ? 'Usuario y contraseña por defecto para todas las bases de datos de este servidor.'
    : (server ? `Vacío por defecto: usa las credenciales del servidor "${server.name}". Llena estos campos solo si esta base de datos necesita un usuario distinto.` : 'Esta base de datos no pertenece a ningún servidor, así que estas son sus únicas credenciales.');
  openModal(title, `
    <p class="t-body-sm t-muted">${escapeHtml(helpText)}</p>
    <div class="field"><label>Usuario</label><input class="input" id="creds-user"></div>
    <div class="field"><label>Contraseña</label><input class="input" type="password" id="creds-pass"></div>
  `, `<button class="btn btn-secondary" id="creds-cancel">Cancelar</button><button class="btn btn-primary" id="creds-confirm" autofocus>Guardar</button>`);
  document.getElementById('creds-cancel').onclick = closeModal;
  document.getElementById('creds-confirm').onclick = () => { closeModal(); toast('Credenciales guardadas'); };
}

function openRemoveDialog(scope, id) {
  const isServer = scope === 'server';
  const name = isServer ? findServer(id)?.name : findDb(id)?.db.name;
  openModal(`Eliminar ${isServer ? 'servidor' : 'base de datos'}`, `
    <p class="t-body">¿Eliminar "<b>${escapeHtml(name || '')}</b>"? Esta acción no se puede deshacer.</p>
  `, `<button class="btn btn-secondary" id="rm-cancel" autofocus>Cancelar</button><button class="btn btn-danger" id="rm-confirm">Eliminar</button>`);
  document.getElementById('rm-cancel').onclick = closeModal;
  document.getElementById('rm-confirm').onclick = () => {
    if (isServer) {
      const idx = DEMO.servers.findIndex(s => s.id === id);
      if (idx !== -1) DEMO.servers.splice(idx, 1);
    } else {
      removeDbFromWhereverItIs(id);
      STATE.selectedDbIds.delete(id);
    }
    closeModal(); renderTree(); renderToolbar();
    toast('Eliminado');
  };
}

function openDiscoverDialog(server, fromDb) {
  const found = ['bodega_norte','bodega_sur','bodega_test'];
  openModal('Descubrir bases de datos', `
    <p class="t-body-sm t-muted">Encontradas en ${escapeHtml(fromDb.host)}:</p>
    ${found.map(n => `<label class="row gap-2" style="padding:6px 0;"><input type="checkbox" checked> <span class="t-body">${n}</span></label>`).join('')}
  `, `<button class="btn btn-secondary" id="disc-cancel">Cancelar</button><button class="btn btn-primary" id="disc-confirm" autofocus>Agregar seleccionadas</button>`);
  document.getElementById('disc-cancel').onclick = closeModal;
  document.getElementById('disc-confirm').onclick = () => { closeModal(); toast('Bases de datos agregadas'); };
}

function openSaveFavoriteDialog(query) {
  openModal('Guardar como favorito', `
    <div class="field"><label>Nombre</label><input class="input" id="fav-name" placeholder="Mi consulta"></div>
    <div class="t-mono" style="font-size:12px; max-height:120px; overflow:auto; background:var(--surface-alt); border-radius:8px; padding:8px;">${escapeHtml(query || '')}</div>
  `, `<button class="btn btn-secondary" id="fav-cancel">Cancelar</button><button class="btn btn-primary" id="fav-confirm" autofocus>Guardar</button>`);
  document.getElementById('fav-cancel').onclick = closeModal;
  document.getElementById('fav-confirm').onclick = () => {
    const name = document.getElementById('fav-name').value.trim() || 'Sin nombre';
    STATE.favorites.unshift({ id: 'f' + Math.random().toString(36).slice(2, 8), name, query: query || '' });
    closeModal();
    if (STATE.activePanel === 'favoritos') renderSidePanelContent();
    toast('Favorito guardado');
  };
}

function openImportCsvDialog(dbId, table) {
  const found = findDb(dbId);
  const servers = found?.server ? [found.server] : null;
  openModal('Importar CSV', `
    <p class="t-body-sm t-muted">Importar filas a <b>${escapeHtml(table)}</b>.</p>
    <div class="field"><label>Archivo CSV</label><input class="input" type="file" id="csv-file" accept=".csv"></div>
    <div class="field"><label>Bases de datos destino</label>
      ${(servers ? servers[0].databases : [found.db]).map(d => `<label class="row gap-2" style="padding:4px 0;"><input type="checkbox" checked ${d.id === dbId ? 'checked' : ''}> <span class="t-body">${escapeHtml(d.name)}</span></label>`).join('')}
    </div>
  `, `<button class="btn btn-secondary" id="csv-cancel">Cancelar</button><button class="btn btn-primary" id="csv-confirm" autofocus>Importar</button>`);
  document.getElementById('csv-cancel').onclick = closeModal;
  document.getElementById('csv-confirm').onclick = () => {
    closeModal();
    toast('Importando CSV…');
    setTimeout(() => toast('CSV importado: 128 filas insertadas'), 900);
  };
}

/* ==========================================================================
   GLOBAL WIRING
   ========================================================================== */
function renderAll() {
  applyTheme();
  renderTree();
  renderTabsBar();
  renderToolbar();
  renderEditorFromState();
  renderResults();
  renderSidePanel();
  wireMassModeSegmented();
  wireStaticIcons();
}

function wireMassModeSegmented() {
  const wrap = document.getElementById('mass-mode-segmented');
  wrap.innerHTML = `
    <button class="${!STATE.massMode ? 'active' : ''}" data-mass="false">Individual</button>
    <button class="${STATE.massMode ? 'active' : ''}" data-mass="true">Masiva</button>
  `;
  wrap.querySelectorAll('[data-mass]').forEach(b => b.addEventListener('click', () => {
    STATE.massMode = b.dataset.mass === 'true';
    if (!STATE.massMode) STATE.selectedDbIds.clear();
    document.getElementById('btn-mass-discover').classList.toggle('hidden', !STATE.massMode);
    wireMassModeSegmented();
    renderTree(); renderToolbar();
  }));
  document.getElementById('btn-mass-discover').innerHTML = icon('database_zap', 16);
  document.getElementById('btn-mass-discover').onclick = () => toast('Buscando en todas las IPs seleccionadas…');
}

function wireStaticIcons() {
  document.getElementById('tree-search-icon').innerHTML = icon('search', 14);
  document.getElementById('tree-search-clear').innerHTML = icon('x', 14);
  document.getElementById('btn-add-database').innerHTML = '<span style="text-align:left;">+ Agregar base de datos</span>';
  document.getElementById('btn-add-database').onclick = () => openAddDatabaseDialog(null);
  document.getElementById('btn-config-menu').innerHTML = icon('settings', 16);
  document.getElementById('btn-config-menu').onclick = (e) => {
    e.stopPropagation();
    showCtxMenu([
      { icon: 'upload', label: 'Importar configuración', onClick: () => toast('(demo) Selecciona un archivo .json') },
      { icon: 'download', label: 'Exportar configuración', onClick: () => toast('Configuración exportada') },
    ], e.clientX, e.clientY);
  };
  document.getElementById('btn-close-panel').innerHTML = icon('x', 16);
  document.getElementById('btn-close-panel').onclick = closePanel;
  document.getElementById('dismiss-barrier').onclick = closePanel;
  document.getElementById('editor-search-toggle').innerHTML = icon('search', 15);
  document.getElementById('editor-search-toggle').onclick = openEditorSearch;
  document.getElementById('editor-search-prev').innerHTML = icon('chevron_up', 14);
  document.getElementById('editor-search-next').innerHTML = icon('chevron_down', 14);
  document.getElementById('editor-search-close').innerHTML = icon('x', 14);
  document.getElementById('editor-search-prev').onclick = searchPrev;
  document.getElementById('editor-search-next').onclick = searchNext;
  document.getElementById('editor-search-close').onclick = closeEditorSearch;
  document.getElementById('results-empty-icon').innerHTML = icon('database', 32);
}

document.addEventListener('DOMContentLoaded', () => {
  renderAll();

  const searchInput = document.getElementById('tree-search-input');
  searchInput.addEventListener('input', () => {
    STATE.searchQuery = searchInput.value;
    document.getElementById('tree-search-clear').classList.toggle('hidden', !searchInput.value);
    renderTree();
  });
  document.getElementById('tree-search-clear').addEventListener('click', () => {
    searchInput.value = ''; STATE.searchQuery = '';
    document.getElementById('tree-search-clear').classList.add('hidden');
    renderTree();
  });

  document.getElementById('tree-resize').addEventListener('mousedown', (e) => {
    const startX = e.clientX;
    const shell = document.getElementById('tree-shell');
    const startW = shell.offsetWidth;
    document.getElementById('tree-resize').classList.add('dragging');
    function onMove(ev) { shell.style.width = Math.min(480, Math.max(180, startW + (ev.clientX - startX))) + 'px'; }
    function onUp() { document.removeEventListener('mousemove', onMove); document.removeEventListener('mouseup', onUp); document.getElementById('tree-resize').classList.remove('dragging'); }
    document.addEventListener('mousemove', onMove); document.addEventListener('mouseup', onUp);
  });

  document.getElementById('split-resize').addEventListener('mousedown', (e) => {
    const toolbar = document.getElementById('toolbar-card');
    const pane = toolbar.parentElement;
    const startY = e.clientY;
    const startH = toolbar.offsetHeight;
    document.getElementById('split-resize').classList.add('dragging');
    function onMove(ev) {
      const total = pane.offsetHeight;
      const newH = Math.min(total - 160, Math.max(160, startH + (ev.clientY - startY)));
      toolbar.style.flex = `0 0 ${newH}px`;
    }
    function onUp() { document.removeEventListener('mousemove', onMove); document.removeEventListener('mouseup', onUp); document.getElementById('split-resize').classList.remove('dragging'); }
    document.addEventListener('mousemove', onMove); document.addEventListener('mouseup', onUp);
  });

  document.getElementById('side-panel-resize').addEventListener('mousedown', (e) => {
    const overlay = document.getElementById('side-panel');
    const startX = e.clientX;
    const startW = overlay.offsetWidth;
    function onMove(ev) { overlay.style.width = Math.min(640, Math.max(320, startW + (ev.clientX - startX))) + 'px'; }
    function onUp() { document.removeEventListener('mousemove', onMove); document.removeEventListener('mouseup', onUp); }
    document.addEventListener('mousemove', onMove); document.addEventListener('mouseup', onUp);
  });

  const input = document.getElementById('sql-editor-input');
  input.addEventListener('input', onEditorInput);
  input.addEventListener('scroll', syncScroll);
  input.addEventListener('click', onEditorSelectionChange);
  input.addEventListener('keyup', (e) => {
    if (!['ArrowLeft','ArrowRight','ArrowUp','ArrowDown','Home','End'].includes(e.key)) return;
    onEditorSelectionChange();
  });
  input.addEventListener('keydown', (e) => {
    if (AC.open) {
      if (e.key === 'ArrowDown') { e.preventDefault(); AC.activeIndex = (AC.activeIndex + 1) % AC.items.length; renderAutocomplete(); return; }
      if (e.key === 'ArrowUp') { e.preventDefault(); AC.activeIndex = (AC.activeIndex - 1 + AC.items.length) % AC.items.length; renderAutocomplete(); return; }
      if (e.key === 'Enter' || e.key === 'Tab') { e.preventDefault(); applyAutocomplete(AC.activeIndex); return; }
      if (e.key === 'Escape') { closeAutocomplete(); return; }
    }
    if (e.ctrlKey && e.key.toLowerCase() === 'f') { e.preventDefault(); openEditorSearch(); }
  });

  document.getElementById('editor-search-input').addEventListener('input', () => recomputeSearchMatches(true));
  document.getElementById('editor-search-input').addEventListener('keydown', (e) => {
    if (e.key === 'Enter') { e.preventDefault(); e.shiftKey ? searchPrev() : searchNext(); }
    if (e.key === 'Escape') closeEditorSearch();
  });

  document.addEventListener('keydown', (e) => {
    const tag = document.activeElement?.tagName;
    if (e.key === 'F5') { e.preventDefault(); const c = activeCtx(); c.running ? cancelRun() : runQuery(); }
    if (e.ctrlKey && e.key.toLowerCase() === 'g') { e.preventDefault(); saveToFileDemo(); }
    if (e.key === 'Escape') { closeModal(); hideCtxMenu(); }
  });
});
