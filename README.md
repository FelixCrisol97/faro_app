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
- Background: `#F5EAD8` (warm cream)
- Surface (cards, panels): `#EBDDC5`
- Text: `#201E1D`
- Divider: `#201E1D` at 16% opacity
- Accent (primary, terracotta): `#C67139`
- Accent 2 (secondary, sage): `#7A8A5E`
- Each color has a 100–900 tonal ramp generated in OKLCH (100/200/300 = light tints for fills/hovers, 500 = base, 700–900 = dark, for text on tinted fills and pressed states). Ask design for the full ramp values if needed — in Flutter, generate these once as a `ColorScheme`/custom ramp class rather than hardcoding each step.
- Dark theme: swaps background→neutral-900, surface→neutral-800, text→neutral-100, divider→neutral-700 (same accent ramps).
- Accent picker: user can switch the whole app's primary accent between the terracotta ramp and the sage ramp (a single "accent" setting, not per-element).

Typography:
- Headings: "Caprasimo" (display serif-ish, weight 400 only — never bold/condensed substitutes). Use for h1–h4, card titles, dialog titles, nav brand.
- Body/UI: "Figtree", regular weight for body copy and controls.
- Both are Google Fonts; bundle them as Flutter assets (`pubspec.yaml` fonts) rather than fetching at runtime.

Spacing scale (base unit ×1.10 density): 4.4 / 8.8 / 13.2 / 17.6 / 26.4 px — use as a fixed `EdgeInsets`/gap scale.

Radii: small controls/containers 16px, large containers (cards, dialogs) 28px, pill controls (buttons, inputs, tags, segmented control) fully round (`999px` → `StadiumBorder` in Flutter).

Shadows (elevation): soft, ink-tinted, warm — not pure black. Three steps: sm (subtle card lift), md (dropdowns/autocomplete), lg (dialogs).

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
A "servidor" is just a named, freely-defined grouping of databases that share one engine and one read-only/Desarrollo mode — a chain of bodega branches, a central host with several company databases, or any other grouping the user finds useful (by function, by schema, etc.). It deliberately has no host/IP of its own: IPs live per database, since bodegas typically each have a different one.

List of server cards. Each card header: server icon, **server name is an inline-editable text field** (borderless until hover/focus, then shows a border) so the team can rename the group directly, an engine tag (PostgreSQL / SQL Server), a segmented control (Solo lectura / Desarrollo) to change that server's mode — switching TO Desarrollo requires a confirmation dialog explaining the consequence; switching back to read-only is immediate — a key-icon button opening a small dialog for that servidor's default login (username/password, used by every database in the group unless overridden below), and a trash-icon button to delete the whole servidor (confirm dialog; also clears its stored credentials).
Below, one row per database: **an alias (inline-editable text, free-form — only used to tell databases apart inside Faro, e.g. in results/`origen_bd`)**, **the host (host:puerto, inline-editable monospace, with a placeholder/red-icon nudge while empty)**, **the real database name the engine knows it by (inline-editable monospace — deliberately separate from the alias, since it's routine for every bodega to share the identical real name while only the host differs)**, a connection-test status pill ("Probando…" / "Conectado"), a key-icon button for a per-database credentials override (optional — only needed when one bodega's login differs from the servidor default), a "Probar conexión" button, and a trash-icon button (confirm dialog; also clears any stored override). A "+ Agregar base de datos" link opens a small dialog asking for alias + host + real database name. A page-level "Agregar servidor" button opens a dialog with name / engine (segmented) / username+password (optional) fields. "Importar configuración" / "Exportar configuración" buttons round-trip the whole server list as JSON (credentials excluded — those stay in the OS secure store); importing always replaces the current list, after a confirmation.

### 5. Apariencia
A narrow settings column: a "Tema" card with a Claro/Oscuro segmented control (sun/moon icons), and a "Color de acento" card with two round swatch buttons (terracotta / sage) — the selected one gets a dark ring; clicking either re-themes the whole app's accent.

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
- Theme (light/dark) and accent (terracotta/sage) — persist across sessions.
- Transient UI state: which dialog is open, autocomplete suggestions, per-database "test connection" status.

## Assets
No external images — only inline icons (Lucide icon set, 2.75 stroke weight) and the two Google Fonts (Caprasimo, Figtree). Recreate icons with a Flutter icon package that includes Lucide, or vector them as `CustomPainter`/SVG assets.

## Files
- `faro_prototype.html` — the interactive HTML prototype (structure/behavior reference).
- `design_system/` — the Organic design system's stylesheet + guide, source of every token above.
