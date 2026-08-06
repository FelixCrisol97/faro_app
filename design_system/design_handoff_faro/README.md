# Handoff: Faro — Consulta multi-bodega

## Overview
Faro is a desktop tool that runs one SQL query against many distributed databases at once (grouped by server), instead of connecting to each one manually. This bundle documents the interactive HTML prototype built to design that experience, so the team can rebuild it as a native **Flutter desktop app**.

## About the design files
The files in this bundle (`faro_prototype.html` and the `design_system/` folder) are **design references built in HTML/CSS**, not production code. Do not embed a webview or try to port the HTML/CSS 1:1. The task is to **recreate this UI natively in Flutter** (widgets, `ThemeData`, real state management) reproducing the same layout, colors, typography, spacing, and interaction behavior described below.

`faro_prototype.html` is a self-contained runtime component (it needs the project's dev environment to actually execute); to just look at it, open the project preview. This README plus the screenshots (if attached) are the authoritative spec — read the HTML for markup/structure reference only.

## Fidelity
**High-fidelity.** Colors, type, spacing, radii and component states below are final; recreate pixel-close using Flutter's own widgets (do not import CSS).

## Design tokens

Colors (light theme):
- Background: `#F8FAFC` (slate-50), Surface (cards/panels): `#FFFFFF`, Surface alt (chips, table header, hover): `#F1F5F9`
- Text: `#0F172A` (slate-900), Text muted: `#475569`, Border: `#E2E8F0`
- Accent: user-selectable brand color — six options, each with a light-mode and a brighter dark-mode variant (base / hover / active / soft-tint / soft-tint-text): Índigo `#6366F1`, Violeta `#8B5CF6`, Azul `#2563EB`, Teal `#0D9488`, Rosa `#E11D48`, Ámbar `#D97706`. In Flutter, model this as a small enum → `ColorScheme` map (don't hardcode one brand color; every screen reads the active accent).
- Semantic colors (independent of the brand accent, same in both themes' intent): Success (emerald `#059669` / dark `#34D399`), Error (red `#DC2626` / dark `#F87171`), Warning/Dev-mode banner (solid amber `#B45309` with white text — always solid, not tinted).
- Dark theme: background `#0F172A`, surface `#1E293B`, surface alt `#334155`, text `#F1F5F9`, muted `#AEBACB`, border `#334155` — plus the brighter per-accent dark variants above (dark mode intentionally uses lighter/more saturated steps of the same hue so accents stay legible on a dark ground instead of looking washed out).

Typography:
- Headings: "Sora", weight 600–700. Use for h1–h4, card titles, dialog titles, nav brand.
- Body/UI: "Manrope", weight 500 for body copy and controls (500 rather than 400 — reads noticeably crisper at small UI sizes than regular weight).
- Both are Google Fonts; bundle them as Flutter assets (`pubspec.yaml` fonts) rather than fetching at runtime.

Spacing: roughly an 8px-based scale (4/8/12/16/20/28px) for padding and gaps between elements.

Radii: 7–9px on small controls (buttons, inputs, chips, segmented control), 16px on containers (cards, dialogs). Accent-swatch pickers are the one fully-round (circular) exception.

Shadows (elevation): soft, neutral ink-tinted (`rgba(15,23,42, …)`), never pure black. Three steps: sm (card lift), md (dropdowns/autocomplete), lg (dialogs, `0 20px 50px rgba(15,23,42,0.25)`).

Segmented controls (mode toggle, engine picker, theme picker): a rounded track (`surface-2`, 10px radius) containing 2 buttons; the selected button gets a white/surface pill background + accent-tinted text + a subtle shadow, the unselected one is transparent with muted text.

## Screens / views

### 1. Consulta (main / default screen)
Two-pane layout: fixed-width left sidebar (~264px) + flexible main content, below a top nav bar.

**Sidebar**
- "Servidores" list: each server is a clickable row (rounded, 16px radius) showing server name (heading font, 14px), a small "DEV" pill tag if in Desarrollo mode, and a caption line "Engine · N bases". The active/selected server row gets a tinted terracotta background with darker terracotta text (not the theme's default text color, so it stays legible in dark mode too).
- "Bases de datos" section below: checkboxes for every database that belongs to the active server, plus a "Todas / Ninguna" toggle button that selects/deselects all at once.

**Main — toolbar card**
- Header row: active server name (heading font), a status tag ("Solo lectura" neutral pill, or "Desarrollo" accent pill), and a small "N de M bases seleccionadas" caption (kept on one line, `white-space: nowrap`).
- Action row (single row, wraps on narrow widths): **Ejecutar / Cancelar is ONE toggle button** — same button, same position: shows "Ejecutar" (primary/filled, play-triangle icon) when idle; while a query is running it switches in place to "Cancelar" (secondary/outlined, stop-square icon) and clicking it aborts the run. Next to it: "Cargar" (upload icon, opens a file picker for .sql/.txt), "Formatear" (align icon, reformats the SQL text), "Favorito" (star icon, opens a save-favorite dialog).
- SQL editor: a monospace multi-line text area (6 rows, 16px radius). Typing `FROM <partial-name>` opens a small floating autocomplete list of table names directly under the cursor position; clicking an item inserts it. If the developer selects a text range in the editor, that exact selection is what runs; otherwise, if the box has multiple `;`-separated statements, the **last** one runs.
- Persistent "Desarrollo" warning: when the active server is in Desarrollo mode, a solid terracotta banner (with alert-triangle icon) sits above the toolbar card, present the whole time that server is active — a subtle recurring soft pulse (glow) animation keeps it noticeable without being distracting.

**Main — results card**
- Empty state: centered muted icon + one line of help text, shown before any run.
- Running state: centered spinner icon (rotating) + "Ejecutando en N bases de datos…" text.
- Results state (fades/slides in ~250ms): a row of small pills, one per queried database, each showing name + row count (success, sage-tinted) or an error mark (neutral pill + alert icon) if that database's query failed — this is how per-database success/error is communicated when querying more than one at once. An "Exportar CSV" button downloads the combined result. Below: a data table. When only one database was queried, columns are exactly what the query returned (e.g. `sku, nombre, existencia`). When multiple were queried, an extra leading `origen_bd` column is prepended so every row is traceable to its source database.

### 2. Historial
A single table, newest first: Hora, Consulta (truncated, monospace), Servidor, # BDs, Filas, Estado (tag: Éxito / Parcial / Bloqueada — "Bloqueada" is what read-only protection produces), and a "Reusar" button that loads that query back into Consulta.

### 3. Favoritos
A responsive card grid. Each card: favorite name (heading font), the saved query previewed in monospace, a primary "Usar" button (loads it into Consulta) and a ghost trash-icon button to delete it.

### 4. Administración
List of server cards. Each card header: server icon, **server name is an inline-editable text field** (borderless until hover/focus, then shows a border) so the team can rename the group directly, an engine tag (PostgreSQL / SQL Server), the host/IP in monospace, and a segmented control (Solo lectura / Desarrollo) to change that server's mode — switching TO Desarrollo requires a confirmation dialog explaining the consequence; switching back to read-only is immediate.
Below, one row per database: **database name is also an inline-editable text field** (same borderless-until-focus treatment), a connection-test status pill ("Probando…" / "Conectado"), and a "Probar conexión" button. A "+ Agregar base de datos" link opens a small dialog to add one. A page-level "Agregar servidor" button opens a dialog with name / engine (segmented) / host fields.

### 5. Apariencia
A narrow settings column: a "Tema" card with a Claro/Oscuro segmented control (sun/moon icons), and a "Color de acento" card with six round swatch buttons (índigo, violeta, azul, teal, rosa, ámbar) — the selected one gets a dark ring; clicking any re-themes the whole app's accent (buttons, links, focus rings, selected states, active nav item).

## Interactions & behavior
- Top nav is a persistent 5-item link bar (Consulta / Historial / Favoritos / Administración / Apariencia); the active item is colored with the accent.
- All dialogs (favorite save, dev-mode confirm, add server, add database) are centered modals over a dimmed backdrop, with a quick fade+scale-in (~180ms).
- Every interactive control has its own hover tint and focus ring (2px accent outline) — never a browser-default blue ring.
- Theme and accent changes transition smoothly (~250ms color/background fade) instead of snapping.
- Read-only enforcement: any server not in Desarrollo mode blocks non-SELECT statements (see the blocked UPDATE example in Historial) with an inline explanation — this must be enforced by the real backend/query layer, not just the UI.

## State management
Key pieces of state a Flutter implementation will need:
- Current screen/tab (enum).
- List of servers, each with: id, name, engine (postgres/sqlserver), host, mode (read-only/dev), list of databases (id, name, selected: bool).
- Currently selected server id.
- SQL editor text + cursor/selection.
- Query run state: idle / running / done, plus last result (columns, rows, per-database success/error + row counts) and whether it was cancelled.
- Execution history list (append on each run).
- Favorites list (add/remove).
- Theme (light/dark) and accent (one of 6 brand colors) — persist across sessions.
- Transient UI state: which dialog is open, autocomplete suggestions, per-database "test connection" status.

## Assets
No external images — only inline icons (Lucide icon set, stroke weight 2) and the two Google Fonts (Sora, Manrope). Recreate icons with a Flutter icon package that includes Lucide, or vector them as `CustomPainter`/SVG assets.

## Files
- `faro_prototype.html` — the interactive HTML prototype (structure/behavior reference; current visual direction — modern SaaS, indigo/violet-family accents, no longer the earlier warm/earthy system).
