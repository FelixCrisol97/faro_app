// Faro demo — inline SVG icon set, hand-drawn in Lucide's own style
// (24x24 grid, stroke=currentColor, stroke-width=2, round caps/joins,
// no fill) so the demo has zero external/network dependencies — no
// icon-font CDN, nothing that can fail to load during a client pitch.

const ICONS = {
  search: '<circle cx="11" cy="11" r="7"/><path d="m20 20-3.2-3.2"/>',
  x: '<path d="M18 6 6 18"/><path d="m6 6 12 12"/>',
  settings: '<circle cx="12" cy="12" r="3"/><path d="M19.4 15a1.7 1.7 0 0 0 .3 1.9l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-1.9-.3 1.7 1.7 0 0 0-1 1.5V21a2 2 0 1 1-4 0v-.1a1.7 1.7 0 0 0-1-1.6 1.7 1.7 0 0 0-1.9.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.7 1.7 0 0 0 .3-1.9 1.7 1.7 0 0 0-1.5-1H3a2 2 0 1 1 0-4h.1a1.7 1.7 0 0 0 1.6-1 1.7 1.7 0 0 0-.3-1.9l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.7 1.7 0 0 0 1.9.3H9a1.7 1.7 0 0 0 1-1.5V3a2 2 0 1 1 4 0v.1a1.7 1.7 0 0 0 1 1.5 1.7 1.7 0 0 0 1.9-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.7 1.7 0 0 0-.3 1.9V9a1.7 1.7 0 0 0 1.5 1H21a2 2 0 1 1 0 4h-.1a1.7 1.7 0 0 0-1.5 1z"/>',
  sun: '<circle cx="12" cy="12" r="4"/><path d="M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4"/>',
  moon: '<path d="M20 14.9A9 9 0 1 1 9.1 4a7 7 0 0 0 10.9 10.9Z"/>',
  server: '<rect x="2" y="3" width="20" height="7" rx="1.5"/><rect x="2" y="14" width="20" height="7" rx="1.5"/><path d="M6 6.5h.01M6 17.5h.01"/>',
  database: '<ellipse cx="12" cy="5" rx="8" ry="3"/><path d="M4 5v14c0 1.7 3.6 3 8 3s8-1.3 8-3V5"/><path d="M4 12c0 1.7 3.6 3 8 3s8-1.3 8-3"/>',
  chevron_right: '<path d="m9 6 6 6-6 6"/>',
  chevron_down: '<path d="m6 9 6 6 6-6"/>',
  chevron_up: '<path d="m18 15-6-6-6 6"/>',
  chevrons_up: '<path d="m17 11-5-5-5 5"/><path d="m17 18-5-5-5 5"/>',
  pencil: '<path d="M17 3a2.85 2.83 0 1 1 4 4L7.5 20.5 2 22l1.5-5.5Z"/><path d="m15 5 4 4"/>',
  key: '<circle cx="7.5" cy="15.5" r="5.5"/><path d="m21 2-9.6 9.6"/><path d="m15.5 7.5 3 3L22 7l-3-3"/>',
  trash: '<path d="M3 6h18"/><path d="M19 6v14a2 2 0 0 1-2 2H7a2 2 0 0 1-2-2V6m3 0V4a2 2 0 0 1 2-2h4a2 2 0 0 1 2 2v2"/><path d="M10 11v6M14 11v6"/>',
  plus: '<path d="M12 5v14M5 12h14"/>',
  lock: '<rect x="4" y="11" width="16" height="10" rx="2"/><path d="M8 11V7a4 4 0 0 1 8 0v4"/>',
  lock_open: '<rect x="4" y="11" width="16" height="10" rx="2"/><path d="M8 11V7a4 4 0 0 1 7.6-1.8"/>',
  plug: '<path d="M12 22v-5"/><path d="M9 8V2M15 8V2"/><path d="M6 8h12l-1 6a5 5 0 0 1-5 4h0a5 5 0 0 1-5-4Z"/>',
  circle_check: '<circle cx="12" cy="12" r="9"/><path d="m8.5 12.5 2.5 2.5 4.5-5"/>',
  circle_alert: '<circle cx="12" cy="12" r="9"/><path d="M12 8v5"/><path d="M12 16h.01"/>',
  play: '<path d="M7 4.5v15l13-7.5Z"/>',
  square: '<rect x="5" y="5" width="14" height="14" rx="2"/>',
  upload: '<path d="M12 16V4"/><path d="m6.5 9.5 5.5-5.5 5.5 5.5"/><path d="M4 16v3a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2v-3"/>',
  save: '<path d="M5 3h11l4 4v13a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1Z"/><path d="M8 3v6h7V3"/><path d="M8 21v-7h8v7"/>',
  download: '<path d="M12 4v12"/><path d="m6.5 10.5 5.5 5.5 5.5-5.5"/><path d="M4 20h16"/>',
  align_left: '<path d="M4 5h16M4 10h10M4 15h16M4 20h10"/>',
  star: '<path d="m12 2.5 3 6.4 6.9.8-5 4.9 1.2 7-6.1-3.4-6.1 3.4 1.2-7-5-4.9 6.9-.8Z"/>',
  file_text: '<path d="M6 2h9l5 5v13a1.5 1.5 0 0 1-1.5 1.5h-13A1.5 1.5 0 0 1 4 20V3.5A1.5 1.5 0 0 1 6 2Z"/><path d="M14 2v5h5"/><path d="M8 13h8M8 17h5"/>',
  eraser: '<path d="m20 20-9-9 6.5-6.5a2 2 0 0 1 2.8 0l3.7 3.7a2 2 0 0 1 0 2.8L17.5 17.5"/><path d="M6 15l4.5 4.5"/><path d="M4 20h9"/>',
  list_tree: '<path d="M4 4h4M4 10h4M4 16h4"/><path d="M9 4h11M9 10h7M9 16h9"/><path d="M4 4v16"/>',
  columns: '<rect x="3" y="4" width="18" height="16" rx="1.5"/><path d="M9.5 4v16M15 4v16"/>',
  file_code: '<path d="M6 2h9l5 5v13a1.5 1.5 0 0 1-1.5 1.5h-13A1.5 1.5 0 0 1 4 20V3.5A1.5 1.5 0 0 1 6 2Z"/><path d="M14 2v5h5"/><path d="m9.5 13-2 2 2 2M13.5 13l2 2-2 2"/>',
  panel_top: '<rect x="3" y="4" width="18" height="16" rx="1.5"/><path d="M3 9h18"/>',
  external_link: '<path d="M18 13v6a1.5 1.5 0 0 1-1.5 1.5h-11A1.5 1.5 0 0 1 4 19V8a1.5 1.5 0 0 1 1.5-1.5H11"/><path d="M15 3h6v6"/><path d="M10 14 21 3"/>',
  database_zap: '<ellipse cx="12" cy="5" rx="8" ry="3"/><path d="M4 5v6c0 1.7 3.6 3 8 3q.9 0 1.7-.1"/><path d="M4 11v6c0 1.7 3.6 3 8 3 .3 0 .7 0 1-.03"/><path d="m14.5 9.5-3 4.5h3l-3 4.5"/>',
  copy: '<rect x="9" y="9" width="12" height="12" rx="1.5"/><path d="M5 15H4a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1h10a1 1 0 0 1 1 1v1"/>',
  history: '<path d="M3 3v5h5"/><path d="M3.1 12a9 9 0 1 0 2-6.3L3 8.3"/><path d="M12 7v5l3.5 2"/>',
  palette: '<circle cx="12" cy="12" r="9"/><circle cx="7.5" cy="10.5" r="1.2" fill="currentColor" stroke="none"/><circle cx="10" cy="7" r="1.2" fill="currentColor" stroke="none"/><circle cx="15" cy="7.5" r="1.2" fill="currentColor" stroke="none"/><circle cx="16.5" cy="12" r="1.2" fill="currentColor" stroke="none"/><path d="M12 21a1.5 1.5 0 0 1 0-3c1 0 1.8-.9 1.7-1.9-.1-.9.6-1.6 1.5-1.6H17a4 4 0 0 0 4-4c0-5-4.5-9-9-9a9 9 0 0 0 0 18Z"/>',
  eye: '<path d="M2 12s3.5-7 10-7 10 7 10 7-3.5 7-10 7-10-7-10-7Z"/><circle cx="12" cy="12" r="3"/>',
  function_square: '<rect x="3" y="3" width="18" height="18" rx="2"/><path d="M14 8.5c0-1.4 1-2.5 2.3-2.5.5 0 .9.1 1.2.3M12.5 8.5H16M10.3 8.5 9 17M8 12.5h3.6"/>',
  terminal: '<rect x="2.5" y="4" width="19" height="16" rx="2"/><path d="m7 9 3 3-3 3"/><path d="M13 15h4"/>',
  zap: '<path d="M13 2 4 14h6l-1 8 9-12h-6Z"/>',
  grip_vertical: '<circle cx="9" cy="6" r="1" fill="currentColor" stroke="none"/><circle cx="9" cy="12" r="1" fill="currentColor" stroke="none"/><circle cx="9" cy="18" r="1" fill="currentColor" stroke="none"/><circle cx="15" cy="6" r="1" fill="currentColor" stroke="none"/><circle cx="15" cy="12" r="1" fill="currentColor" stroke="none"/><circle cx="15" cy="18" r="1" fill="currentColor" stroke="none"/>',
  refresh: '<path d="M3 12a9 9 0 0 1 15.3-6.4L21 8"/><path d="M21 3v5h-5"/><path d="M21 12a9 9 0 0 1-15.3 6.4L3 16"/><path d="M3 21v-5h5"/>',
  filter: '<path d="M4 5h16l-6.5 8v6l-3-2v-4Z"/>',
  triangle_alert: '<path d="M11 3.5a1 1 0 0 1 2 0l9 15.5a1 1 0 0 1-.9 1.5H2.9a1 1 0 0 1-.9-1.5Z"/><path d="M12 9v5"/><path d="M12 17.5h.01"/>',
  loader: '<path d="M12 3v3M12 18v3M5.6 5.6l2.1 2.1M16.3 16.3l2.1 2.1M3 12h3M18 12h3M5.6 18.4l2.1-2.1M16.3 7.7l2.1-2.1"/>',
  clock: '<circle cx="12" cy="12" r="9"/><path d="M12 7v5l3.5 2"/>',
  folder: '<path d="M3 6.5A1.5 1.5 0 0 1 4.5 5H9l2 2.5h8.5A1.5 1.5 0 0 1 21 9v9a1.5 1.5 0 0 1-1.5 1.5h-15A1.5 1.5 0 0 1 3 18Z"/>',
  layers: '<path d="m12 3 9 5-9 5-9-5Z"/><path d="m3 13 9 5 9-5"/>',
  code: '<path d="m9 8-4 4 4 4"/><path d="m15 8 4 4-4 4"/>',
  info: '<circle cx="12" cy="12" r="9"/><path d="M12 16v-5"/><path d="M12 8h.01"/>',
  monitor: '<rect x="3" y="4" width="18" height="12" rx="1.5"/><path d="M8 20h8M12 16v4"/>',
  gauge: '<circle cx="12" cy="13" r="8"/><path d="M12 13 15.5 9"/><path d="M8 4.4A8 8 0 0 1 12 3M16 4.4a8 8 0 0 1 0 0"/>',
  columns3: '<rect x="3" y="4" width="18" height="16" rx="1.5"/><path d="M9 4v16M15 4v16"/>',
};

/**
 * Returns an inline <svg> string for `name` at `size`px, using the
 * current CSS `color` (stroke=currentColor) unless `strokeWidth` is
 * overridden. Falls back to a blank 0x0 svg for an unknown name rather
 * than throwing — a missing icon should never crash the demo.
 */
function icon(name, size = 16, strokeWidth = 2) {
  const body = ICONS[name];
  if (!body) return `<svg width="${size}" height="${size}" viewBox="0 0 24 24"></svg>`;
  return `<svg width="${size}" height="${size}" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="${strokeWidth}" stroke-linecap="round" stroke-linejoin="round">${body}</svg>`;
}
