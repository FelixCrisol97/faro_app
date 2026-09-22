# Handoff: Faro — Rediseño visual (Consulta)

## Qué es esto
Especificación exacta del mockup interactivo publicado en:
**https://claude.ai/artifact/73p3kSFKCE5PKSC9USx5r8**

El mockup reproduce la pantalla "Consulta" tal como existe hoy en `java_faroapp` (mismos
menús, mismos botones, mismo árbol de conexiones, mismos textos), pero aplicando con más
profundidad los tokens que **ya están definidos** en `faro-java-prototipo.html` (la fuente
original de `app.css`/`theme-light.css`/`theme-dark.css`). No se inventó una paleta nueva:
todo valor de color, radio, sombra y tipografía de este documento existe ya en ese
prototipo. Lo que agrega el rediseño es:

1. Un conjunto de **tokens nuevos** para diferenciar motores por color (Postgres / SQL
   Server) — no existen todavía en `app.css`.
2. Un conjunto de **reglas de composición** que hoy no están aplicadas con esta
   consistencia: tarjetas con sombra en vez de superficies planas, acciones de fila
   (editar/eliminar) reveladas solo al pasar el mouse, candado de modo siempre visible con
   color semántico, halo en el punto de estado de conexión.

Este documento es la referencia de implementación — sigue el mismo formato que
`design_system/design_handoff_faro/README.md` (el handoff anterior del proyecto), pero
para esta iteración. Cada sección incluye, donde aplica, el nombre exacto de la variable
`-token-*` de `app.css` a la que corresponde, para minimizar ambigüedad al portarlo.

Todas las medidas son las mismas usadas en el archivo del mockup
(`Main.dc.html` dentro del artifact); ningún valor de esta tabla fue redondeado o
aproximado después del hecho.

---

## 1. Tokens de color

### 1.1 Neutros y semánticos (ya existen en `theme-light.css` / `theme-dark.css`)

| Token del mockup | Claro | Oscuro | Variable en `app.css` |
| --- | --- | --- | --- |
| `--background` | `#F8FAFC` | `#0F172A` | `-token-background` |
| `--surface` | `#FFFFFF` | `#1E293B` | `-token-surface` |
| `--surface-alt` | `#F1F5F9` | `#334155` | `-token-surface-alt` |
| `--text` | `#0F172A` | `#F1F5F9` | `-token-text` |
| `--text-muted` | `#475569` | `#AEBACB` | `-token-text-muted` |
| `--border` | `#E2E8F0` | `#334155` | `-token-border` |
| `--success-base` | `#059669` | `#34D399` | `-token-success-base` |
| `--success-soft` | `#ECFDF5` | `rgba(52,211,153,.16)` | `-token-success-soft` |
| `--success-soft-text` | `#047857` | `#6EE7B7` | `-token-success-soft-text` |
| `--error-base` | `#DC2626` | `#F87171` | `-token-error-base` |
| `--error-soft` | `#FEF2F2` | `rgba(248,113,113,.16)` | `-token-error-soft` |
| `--error-soft-text` | `#B91C1C` | `#FCA5A5` | `-token-error-soft-text` |
| `--warn-base` | `#B45309` | `#FBBF24` | `-token-warn-base` |
| `--warn-soft` | `#FEF3C7` | `rgba(180,83,9,.22)` | `-token-warn-soft` |
| `--warn-soft-text` | `#92400E` | `#FCD34D` | `-token-warn-soft-text` |

### 1.2 Acentos — 6 opciones (ya existen; el mockup deja elegirlas en vivo desde el panel Tweaks)

| Acento | Base claro | Hover claro | Soft claro | Soft-text claro | Base oscuro | Soft oscuro | Soft-text oscuro |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Índigo (default) | `#6366F1` | `#4F46E5` | `#EEF2FF` | `#4338CA` | `#818CF8` | `rgba(129,140,248,.18)` | `#C7D2FE` |
| Violeta | `#8B5CF6` | `#7C3AED` | `#F5F3FF` | `#6D28D9` | `#A78BFA` | `rgba(167,139,250,.18)` | `#DDD6FE` |
| Azul | `#2563EB` | `#1D4ED8` | `#EFF6FF` | `#1D4ED8` | `#60A5FA` | `rgba(96,165,250,.18)` | `#BFDBFE` |
| Teal | `#0D9488` | `#0F766E` | `#F0FDFA` | `#0F766E` | `#2DD4BF` | `rgba(45,212,191,.18)` | `#99F6E4` |
| Rosa | `#E11D48` | `#BE123C` | `#FFF1F2` | `#BE123C` | `#FB7185` | `rgba(251,113,133,.18)` | `#FECDD3` |
| Ámbar | `#D97706` | `#B45309` | `#FFFBEB` | `#B45309` | `#FBBF24` | `rgba(251,191,36,.18)` | `#FDE68A` |

Mapea a `-token-accent-base` / `-token-accent-hover` / `-token-accent-active` /
`-token-accent-soft` / `-token-accent-soft-text` (el valor "active" no se usó en el
mockup — ningún elemento tiene un estado `:pressed` distinto del `:hover`).

Texto sobre el acento sólido (botón "Ejecutar"): **`#FFFFFF` fijo**, igual en los dos
temas — el mockup no usa el acento "negro" que sí existe en `app.css`
(`-token-accent-on`); si el equipo lo mantiene como séptima opción, seguir usando ese
token en vez de `#FFFFFF` fijo para ese caso puntual.

### 1.3 Identidad de motor — **NUEVO, no existe en `app.css` todavía**

| Token | Claro | Oscuro | Uso |
| --- | --- | --- | --- |
| `--pg-bg` | `rgba(51,103,145,.12)` | `rgba(96,165,250,.18)` | Fondo de la insignia de motor PostgreSQL |
| `--pg-text` | `#2C5877` | `#93C5FD` | Texto/letra "Pg" sobre esa insignia |
| `--mssql-bg` | `rgba(204,41,39,.12)` | `rgba(248,113,113,.18)` | Fondo de la insignia de motor SQL Server |
| `--mssql-text` | `#B91C1C` | `#FCA5A5` | Texto/letra "MS" sobre esa insignia |

Sugerencia de nombre en `app.css`: `-token-engine-pg-bg` / `-token-engine-pg-text` /
`-token-engine-mssql-bg` / `-token-engine-mssql-text`, agregados a `theme-light.css` y
`theme-dark.css` igual que el resto de los `-token-*`.

---

## 2. Tipografía

Familias (idénticas a las 3 ya cargadas como `.ttf` en
`src/main/resources/com/faro/app/fonts/`): **Sora** (`--font-heading`), **Manrope**
(`--font-body`), **JetBrains Mono** (`--font-mono`).

| Elemento | Familia | Peso | Tamaño | Color (token) | Extra |
| --- | --- | --- | --- | --- | --- |
| Nombre de la app ("Faro", barra de título) | Sora | 700 | 12.5px | `--text` | letter-spacing .01em |
| Subtítulo barra de título | Manrope | 500 | 11.5px | `--text-muted` | |
| Ítems de la barra de menú | Manrope | 500 | 12.5px | `--text` | |
| Etiqueta del botón "Ejecutar" | Manrope | 700 | 13px | `#FFFFFF` fijo | |
| Atajo "F5" dentro de "Ejecutar" | JetBrains Mono | 500 | 10.5px | `#FFFFFF` a 85% opacidad | |
| Etiqueta de botones fantasma (Nueva consulta/Abrir/Guardar/Formatear/Favorito) | Manrope | 600 | 12.5px | `--text` | |
| Píldora "N de M bases seleccionadas" (toolbar) | Manrope | 700 | 12px | `--accent-soft-text` | |
| Chip "Timeout 30 s" — valor | Manrope | 600 | 12px | `--text` | |
| Chip "Timeout 30 s" — etiqueta | Manrope | 500 | 12px | `--text-muted` | |
| Placeholder de búsqueda ("Buscar base…") | Manrope | 500 | 12.5px | `--text-muted` | |
| Chip "Todas" | Manrope | 700 | 11.5px | `--accent-soft-text` | |
| Caption "N bases" junto al chip "Todas" | Manrope | 500 | 11px | `--text-muted` | |
| Etiqueta de sección ("BODEGAS DE PRUEBA…", "SIN GRUPO") | Manrope | 700 | 11px | `--text-muted` | mayúsculas, letter-spacing .05em |
| Contador de grupo (chip "5" / "1") | JetBrains Mono | 700 | 10px | `--text-muted` | |
| Nombre de conexión (fila del árbol) | Manrope | 600 | 12.5px | `--text` | trunca con `…` |
| Host:puerto (fila del árbol) | JetBrains Mono | 500 | 9.5px | `--text-muted` | |
| Letra de insignia de motor ("Pg" / "MS") | JetBrains Mono | 700 | 9.5px | `--pg-text` / `--mssql-text` | |
| Link "Agregar conexión" | Manrope | 600 | 12px | `--text-muted` | |
| Título de tarjeta ("Bodegas de prueba (Docker)") | Sora | 700 | 15px | `--text` | |
| Píldora "Solo lectura" | Manrope | 700 | 10.5px | `--text-muted` | |
| Caption "N de M seleccionadas" (tarjeta) | Manrope | 500 | 11.5px | `--text-muted` | nowrap |
| Pestaña de consulta activa ("Consulta 1") | Manrope | 700 | 12.5px | `--text` | |
| Texto del editor SQL | JetBrains Mono | 500 | 13.5px | `--text` | line-height 1.6 |
| Palabras clave SQL (SELECT/FROM/WHERE/ORDER BY/DESC/LIMIT) | JetBrains Mono | 700 | 13.5px | `--accent-base` | |
| Literal numérico SQL (`0`, `200`) | JetBrains Mono | 500 | 13.5px | `--warn-base` | |
| Comentario SQL (`-- …`) | JetBrains Mono | 500 | 13.5px | `--text-muted` | *itálica* |
| Número de línea del editor | JetBrains Mono | 500 | 13.5px | `--text-muted` | |
| Barra de info del editor ("Ln 3, Col 18…") | JetBrains Mono | 500 | 10.5px | `--text-muted` | |
| Pestaña de resultados activa ("Resultados") | Manrope | 700 | 13px | `--text` | |
| Pestañas inactivas ("Ejecución", "Diagnóstico") | Manrope | 600 | 13px | `--text-muted` | |
| Badge de conteo en "Diagnóstico" | Manrope | 700 | 10.5px | `--text-muted` | |
| Píldora de resultado por base (éxito) | Manrope | 700 | 11.5px | `--success-soft-text` | |
| Píldora de resultado por base (error) | Manrope | 700 | 11.5px | `--error-soft-text` | |
| Caption "1,436 filas combinadas · 0.94 s" | Manrope | 500 | 11.5px | `--text-muted` | |
| Etiqueta "Exportar CSV" | Manrope | 600 | 11.5px | `--text` | |
| Encabezado de columna de la tabla | Manrope | 700 | 12px | `--text` | |
| Celda `origen_bd` | JetBrains Mono | 500 | 12.5px | `--text-muted` | |
| Celda `sku` | JetBrains Mono | 500 | 12.5px | `--text` | |
| Celda `nombre` | Manrope | 500 | 12.5px | `--text` | |
| Celda `existencia` | JetBrains Mono | 500 | 12.5px | `--text` | alineada a la derecha |
| Texto de la barra de estado inferior | Manrope | 500 | 11px | `--text-muted` | |

---

## 3. Radios — `--radius-control:9px` / `--radius-chip:6px` / `--radius-container:14px`

Estos tres valores son **exactamente** los ya definidos como `--radius-control`,
`--radius-chip` y `--radius-container` en `faro-java-prototipo.html`.

| Radio | Valor | Dónde se usa |
| --- | --- | --- |
| `--radius-control` | 9px, las 4 esquinas | Botón "Ejecutar", botones fantasma de la toolbar, chip "Timeout", toggle de tema, botón "Exportar CSV" |
| `--radius-chip` | 6px, las 4 esquinas | Chip de conteo de grupo ("5", "1") |
| `--radius-container` | 14px, las 4 esquinas | Tarjeta de consulta, tarjeta de resultados |
| Fila de conexión (sidebar) | 10px, las 4 esquinas | Contenedor de cada fila de base de datos |
| Insignia de motor (Pg/MS) | 7px, las 4 esquinas | El cuadrito de 22×22 con "Pg"/"MS" |
| Checkbox de selección | 3px, las 4 esquinas | Caja de 16×16 antes/después de marcar |
| Botones de acción de fila (editar/eliminar) | 6px, las 4 esquinas | Los dos íconos que aparecen al pasar el mouse |
| Botón de engranaje (barra de estado) | 5px, las 4 esquinas | Ícono de ajustes, esquina inferior izquierda |
| Círculo del selector de acento | 999px (círculo completo) | Los 6 puntos de color en la toolbar |
| Píldoras (chips redondeados) | 999px (cápsula completa) | "Todas", contador seleccionadas, "Solo lectura", "Timeout", badges de resultado por base, badge de "Diagnóstico" |

**Único caso de radio asimétrico en todo el mockup:** el botón "+" para nueva pestaña de
consulta, a la derecha de "Consulta 1", usa `border-radius: 6px 6px 0 0` — **redondeado
solo arriba** (6px en las dos esquinas superiores), **recto abajo** (0 en las dos
esquinas inferiores), porque su borde inferior se apoya justo sobre la línea divisoria de
1px que separa la fila de pestañas del resto de la tarjeta; con las 4 esquinas
redondeadas se vería un hueco entre el botón y esa línea.

Todo lo demás (barra de título, barra de menú, toolbar, panel del sidebar, barra de
estado, encabezado/filas de la tabla, subrayado de pestañas) es **recto, radio 0** — son
barras estructurales, no controles ni contenedores.

---

## 4. Sombras — `--shadow-sm` / `--shadow-md` / `--shadow-lg`

| Token | Claro | Oscuro | Uso en este mockup |
| --- | --- | --- | --- |
| `--shadow-sm` | `0 1px 2px rgba(15,23,42,.06), 0 4px 12px rgba(15,23,42,.06)` | `0 1px 2px rgba(0,0,0,.3), 0 4px 14px rgba(0,0,0,.24)` | Tarjeta de consulta y tarjeta de resultados (única sombra que aparece en pantalla) |
| `--shadow-md` | `0 10px 30px rgba(15,23,42,.15)` | `0 10px 30px rgba(0,0,0,.45)` | No usada en esta pantalla — reservada para menús desplegables/autocompletado, igual que ya hace `app.css` en `.context-menu` |
| `--shadow-lg` | `0 24px 60px rgba(15,23,42,.22)` | `0 24px 60px rgba(0,0,0,.55)` | No usada en esta pantalla — reservada para diálogos modales |

El halo del punto de estado de conexión (verde, en cada fila) **no es una sombra de
elevación**: es `box-shadow: 0 0 0 3px var(--success-soft)` — un anillo sólido de 3px del
mismo tono suave que ya existe para los badges de éxito, no un blur.

---

## 5. Especificación por componente

### 5.1 Barra de título — 34px de alto
- Fondo `--surface-alt`, borde inferior 1px `--border`.
- Padding `0 6px 0 14px`.
- Marca: cuadrado de 13×13px, radio 4px, fondo `--accent-base`.
- Nombre "Faro" pegado a la marca con 9px de espacio; subtítulo con el mismo espaciado.
- 3 botones de ventana (minimizar/maximizar/cerrar) a la derecha: 38px de ancho × 34px de
  alto cada uno (ocupan todo el alto de la barra), ícono 11×11px trazo 2.2, color
  `--text-muted`, radio 0.

### 5.2 Barra de menú — 32px de alto
- Fondo `--surface`, borde inferior 1px `--border`, padding horizontal 10px.
- 7 ítems ("Archivo", "Editar", "Consulta", "Conexiones", "Ver", "Herramientas",
  "Ayuda"), cada uno padding `5px 10px`, radio 6px, separados por 2px de espacio.
- Hover: fondo `--surface-alt` (sin cambio de color de texto — ya está en `--text`).

### 5.3 Barra de herramientas — 56px de alto
- Fondo `--surface`, borde inferior 1px `--border`, padding horizontal 16px, gap 8px
  entre elementos.
- Orden exacto, izquierda a derecha: **Ejecutar** (relleno de acento) → divisor 1×24px →
  **Nueva consulta / Abrir / Guardar / Formatear / Favorito** (fantasma, borde
  `--border`) → divisor → **píldora de seleccionadas** → **chip de Timeout** → espacio
  flexible → **6 puntos de acento** → divisor → **toggle de tema**.
- Botón "Ejecutar": padding `9px 16px`, sin borde, ícono de play 13×13 relleno sólido.
- Botones fantasma: padding `8px 13px`, borde 1px `--border`, ícono 14×14 trazo 2.
- Divisores: 1px de ancho, 24px de alto, color `--border`.
- Selector de acento: 6 círculos de 16×16px con 2px de padding interno; el
  seleccionado lleva un doble anillo `box-shadow: 0 0 0 2px var(--surface), 0 0 0 4px
  <color del acento>` — el primer anillo (2px, color de la superficie) separa visualmente
  el segundo anillo (2px, color del acento) del propio punto de color.
- Toggle de tema: 34×34px, borde 1px `--border`, radio 9px, ícono sol/luna 15×15 trazo 2.

### 5.4 Panel izquierdo — 288px de ancho fijo
- Fondo `--surface`, borde derecho 1px `--border`, padding 14px, gap vertical 16px entre
  bloques (buscador, grupo Docker, grupo Sin grupo, link inferior).
- **Buscador**: píldora completa (radio 999px), fondo `--surface-alt`, padding `8px
  12px`, ícono de lupa 14×14 trazo 2.
- **Fila de filtro**: chip "Todas" (radio 999px, fondo `--accent-soft`) + caption "N
  bases" + botón circular "+" de 26×26px (borde 1px `--border`, radio 999px).
- **Encabezado de grupo**: chevron 11×11 + etiqueta en mayúsculas + chip de conteo
  (radio `--radius-chip`) + ícono de tres puntos a la derecha. Padding `4px 6px 8px 6px`.
- **Fila de conexión** — de izquierda a derecha, todo en una sola línea de 9px de gap:
  1. Checkbox 16×16px, radio 3px, borde 1.6px `--border`; marcado = fondo y borde
     `--accent-base` + palomita blanca de 10×10 trazo 3.
  2. Punto de estado 8×8px, círculo, `--success-base` con halo `0 0 0 3px
     --success-soft`.
  3. Insignia de motor 22×22px, radio 7px: Postgres = fondo `--pg-bg` texto `--pg-text`
     letra "Pg"; SQL Server = fondo `--mssql-bg` texto `--mssql-text` letra "MS".
  4. Nombre (línea 1) + host:puerto monoespaciado (línea 2), columna que crece y trunca.
  5. Candado 12×12px, trazo 2.4, extremos y uniones redondeadas — **siempre visible**
     (no se oculta con el hover): cerrado + `--text-muted` = modo normal/solo lectura;
     abierto + `--success-base` = modo "sin restricciones"/desarrollo (una sola fila del
     mockup, PostgreSQL 12, está en este estado, a propósito, para mostrar el contraste).
  6. Lápiz + bote de basura, 22×22px cada uno, radio 6px, ícono 12×12 trazo 2.3 — **ocultos
     por defecto** (`opacity:0`) y revelados (`opacity:1`, transición `.12s ease`) solo
     cuando el mouse está sobre la fila completa, no solo sobre el ícono.
  - Toda la fila: padding `8px 6px`, radio 10px; fondo `--surface-alt` al pasar el mouse
    (el mismo evento de hover que revela los íconos de acción).
- **Link inferior** "+ Agregar conexión": ícono 13×13 + texto, color `--text-muted`,
  padding `8px 6px`.
- Scrollbar del panel: pista transparente, thumb 10px de ancho color `--border` con
  radio 6px y un borde de 2px del color `--surface` alrededor (para que se vea "flotando"
  sobre el fondo) — mismo lenguaje visual que ya define `app.css` para `.scroll-bar`.

### 5.5 Tarjeta de consulta
- Contenedor: fondo `--surface`, radio `--radius-container` (14px), `box-shadow:
  var(--shadow-sm)`, `overflow:hidden`.
- Encabezado: padding `14px 16px 10px 16px`; título Sora 15px + píldora "Solo lectura" +
  caption alineada a la derecha, todo en una fila con 10px de gap.
- Fila de pestañas: padding horizontal 12px, borde inferior 1px `--border`; pestaña
  activa con borde inferior 2px `--accent-base` y padding `8px 14px 10px 14px`; botón
  "+" nueva pestaña con el radio asimétrico descrito en la sección 3.
- Editor SQL: padding externo `14px 16px 16px 16px`; superficie interna fondo
  `--surface-alt`, radio 10px, padding interno `14px 16px`, monoespaciado 13.5px,
  interlineado 1.6 — colores de sintaxis según la tabla de tipografía (§2).
- Barra de info bajo el editor: padding `8px 4px 0 4px`, `justify-content:space-between`,
  ambos textos monoespaciados 10.5px `--text-muted`.

### 5.6 Tarjeta de resultados
- Mismo contenedor que la tarjeta de consulta (radio 14px, `--shadow-sm`).
- Fila de pestañas: "Resultados" (activa) / "Ejecución" / "Diagnóstico" con badge de
  conteo; gap 18px entre pestañas, padding horizontal 16px, borde inferior 1px
  `--border`; pestaña activa con el mismo tratamiento de subrayado de 2px que en la
  tarjeta de consulta.
- Fila de píldoras por base: padding `12px 16px`, gap 8px con wrap, borde inferior 1px
  `--border`; cada píldora `5px 11px` de padding, radio 999px, ícono 11×11 (palomita en
  éxito trazo 3, triángulo de alerta en error trazo 2.4); a la derecha, caption de
  totales + botón "Exportar CSV" (fantasma, mismo tratamiento que los botones de la
  toolbar).
- Tabla: grid de 4 columnas con proporción `1.3fr 1fr 1.6fr 0.8fr`
  (`origen_bd / sku / nombre / existencia`); encabezado fondo `--surface-alt`, `position:
  sticky; top:0`, padding de celda `9px 14px`; filas con borde inferior 1px `--border` y
  fondo `--surface-alt` al pasar el mouse; padding de celda de fila `8px 14px`; la
  columna `existencia` alineada a la derecha en encabezado y celdas.
- **Nota deliberada**: no hay cebra (zebra) en las filas — coincide con el criterio ya
  documentado en `app.css` ("SIN cebra, SIN resaltado de selección — es una vista de
  datos de solo lectura").

### 5.7 Barra de estado — 26px de alto
- Fondo `--surface`, borde superior 1px `--border`, padding horizontal 14px, gap 14px,
  todo el texto en `--text-muted` 11px.
- Orden: ícono de engranaje (20×20px, radio 5px) → punto + "N conexiones · pool X/Y" →
  "Timeout 30 s · fetch 1000" → espacio flexible → "Memoria NN MB" → "JDK 25".
- El punto junto a "N conexiones" es 6×6px (más chico que el de las filas del árbol, que
  es 8×8px) y no lleva halo — es un indicador de estado global, no por conexión
  individual.

---

## 6. Estados de interacción

| Estado | Regla |
| --- | --- |
| Hover de fila de conexión | Fondo de la fila → `--surface-alt`; simultáneamente, los íconos de editar/eliminar pasan de `opacity:0` a `opacity:1` (transición `.12s ease`) |
| Hover de botón fantasma / ícono | Fondo → `--surface-alt` |
| Hover de acento (swatch) | `transform: scale(1.1)` |
| Seleccionado — checkbox | Fondo y borde → `--accent-base`, palomita blanca |
| Seleccionado — pestaña | Borde inferior 2px `--accent-base`, texto pasa de `--text-muted`/600 a `--text`/700 |
| Seleccionado — acento activo | Doble anillo descrito en §5.3 |
| Foco de teclado | No representado en el mockup (es una demo estática de mouse) — el equipo debe seguir el estándar ya definido en el proyecto: `outline: 2px solid var(--accent-base); outline-offset: 2px`, nunca el foco azul por defecto |
| Deshabilitado | No representado — seguir el patrón ya existente en `app.css` (opacidad 1, color atenuado por `--text-muted`, nunca opacidad multiplicada) |

---

## 7. Iconografía

Todos los íconos son trazo (`stroke`), sin relleno salvo donde se indica, estilo
Lucide, `stroke-linecap`/`stroke-linejoin: round` donde el ícono tiene ángulos.

| Ícono | Tamaño | Grosor de trazo | Dónde |
| --- | --- | --- | --- |
| Lupa | 14×14 | 2 | Buscador |
| Más (+) | 12–14 | 2.4 / 2 | Botón "+" agregar conexión, "Nueva consulta", nueva pestaña |
| Chevron abajo | 11×11 | 2.4 | Encabezado de cada grupo |
| Tres puntos (relleno) | 14×14 | — (fill) | Menú de opciones del grupo |
| Base de datos (elipse + arcos) | 13×13 | 2.2 | Píldora de "N bases seleccionadas" |
| Candado cerrado/abierto | 12×12 | 2.4 | Modo de cada conexión |
| Lápiz | 12×12 | 2.3 | Editar conexión |
| Bote de basura | 12×12 | 2.3 | Eliminar conexión |
| Play (relleno) | 13×13 | — (fill) | Botón "Ejecutar" |
| Subir/abrir archivo | 14×14 | 2 | Botón "Abrir" |
| Guardar (disquete) | 14×14 | 2 | Botón "Guardar" |
| Alinear/formatear | 14×14 | 2 | Botón "Formatear" |
| Estrella | 14×14 | 2 | Botón "Favorito" |
| Chevron abajo (pequeño) | 11×11 | 2.4 | Chip de "Timeout" |
| Sol / Luna | 15×15 | 2 | Toggle de tema |
| Palomita | 10–11 | 3 | Checkbox marcado, píldora de éxito |
| Triángulo de alerta | 11×11 | 2.4 | Píldora de error |
| Descargar | 12×12 | 2.2 | "Exportar CSV" |
| Engranaje | 12×12 | 2 | Barra de estado |

---

## 8. Fuentes a empaquetar
Sin cambios respecto al proyecto actual — las 3 familias ya están en
`src/main/resources/com/faro/app/fonts/` (`Sora-Variable.ttf`, `Manrope-Variable.ttf`,
`JetBrainsMono-Variable.ttf`). Este rediseño no requiere ningún archivo de fuente nuevo.

---

## 9. Fuera de alcance de este documento
El mockup muestra un único estado "feliz" con resultados ya cargados, para poder enseñar
la tabla y las píldoras. No cubre: el estado vacío (antes de ejecutar), el estado
"ejecutando" (spinner), diálogos (agregar/editar conexión, credenciales, preferencias,
importar CSV) ni el panel de Diagnóstico/Ejecución en detalle — esos ya tienen su propio
tratamiento visual en `app.css` (`.exec-*`, `.diagnostic-*`) y no se tocaron aquí; si el
equipo quiere que se detallen con el mismo nivel de precisión, es un documento aparte.

---

## 10. Guía de implementación en `java_faroapp` — dónde toca cada cambio

Las secciones 1-9 dicen **qué** debe verse. Esta dice **dónde**, contra el código real de
`java_faroapp` tal como está hoy (`ConnectionTreeCell.java`, `AccentPalette.java`,
`app.css`, `theme-light.css`/`theme-dark.css`), para que nadie tenga que adivinar en qué
archivo entra cada regla. Verificado leyendo esas clases, no supuesto.

### 10.1 Tokens de color por motor — `theme-light.css` / `theme-dark.css`
Agregar al bloque `.root` de cada archivo, con el mismo patrón que ya usan
`-token-success-soft`/`-token-success-soft-text`:

```css
/* theme-light.css */
-token-engine-pg-bg: rgba(51,103,145,.12);
-token-engine-pg-text: #2C5877;
-token-engine-mssql-bg: rgba(204,41,39,.12);
-token-engine-mssql-text: #B91C1C;

/* theme-dark.css */
-token-engine-pg-bg: rgba(96,165,250,.18);
-token-engine-pg-text: #93C5FD;
-token-engine-mssql-bg: rgba(248,113,113,.18);
-token-engine-mssql-text: #FCA5A5;
```

### 10.2 Insignia de motor a color — corrección importante sobre el mockup
El mockup dibuja la insignia como un cuadrito fijo de 22×22 con una letra ("Pg"/"MS").
**Eso no coincide con el dato real**: `DbEngine.badge()` (`model/DbEngine.java`) devuelve
`"PG"` o `"MSSQL"` — 5 caracteres, no entran en un cuadrado fijo — y
`ConnectionTreeCell.updateDatabaseRow()` ya hace `engineBadge.setText(db.engine().badge())`
tal cual. **No cambien el texto del badge** (sería un cambio de producto, no de estilo):
mantengan `.tree-engine-badge` como una píldora de ancho automático (como ya es hoy) y
solo agréguenle color por motor. En `app.css`, donde hoy está:

```css
.tree-engine-badge {
    -fx-background-color: -token-surface-alt;
    /* ... */
}
```

agregar dos clases nuevas junto a esa:

```css
.tree-engine-badge-postgres {
    -fx-background-color: -token-engine-pg-bg;
    -fx-text-fill: -token-engine-pg-text;
}
.tree-engine-badge-mssql {
    -fx-background-color: -token-engine-mssql-bg;
    -fx-text-fill: -token-engine-mssql-text;
}
```

Y en `ConnectionTreeCell.updateDatabaseRow()` (cerca de la línea 922, junto a
`engineBadge.setText(...)`), alternar la clase igual que ya hace el candado con
`tree-mode-icon-unrestricted` (línea ~942-949 — copiar ese mismo patrón de "solo mutar si
cambió", no reescribir la lista de estilos en cada repintado):

```java
boolean isPostgres = db.engine() == DbEngine.POSTGRES;
String engineStyleClass = isPostgres ? "tree-engine-badge-postgres" : "tree-engine-badge-mssql";
// quitar la clase contraria si estaba puesta, agregar la que corresponde — solo si cambió
```

### 10.3 Orden de la insignia en la fila — decisión pendiente del equipo
El mockup pone la insignia junto al punto de estado, **antes** del nombre. El código
actual la pone en `trailingIconsBox`, **después** del nombre, junto al candado y los
botones de editar/eliminar (`ConnectionTreeCell.java`, línea ~425:
`trailingIconsBox = new HBox(6, modeIcon, engineBadge, editButton, deleteButton)`).
Dos caminos, ambos válidos:
- **Igualar el mockup**: mover `engineBadge` a `leadingIconsBox` (línea ~423):
  `leadingIconsBox = new HBox(6, checkBox, statusDot, engineBadge);` y sacarlo de
  `trailingIconsBox`. Cambio de una línea.
- **Dejarlo donde está**: aplicar solo el color (10.2) sin mover el nodo — más barato,
  cero riesgo de romper el alineado ya calibrado a mano (ver los comentarios de
  `leadingIconsBox`/`trailingIconsBox` en esa misma clase, que documentan varias rondas
  de ajuste fino de alineación).

Que decida el equipo — el mockup muestra la intención, no es obligatorio mover el nodo
para "seguirlo al pie de la letra" en este punto puntual.

### 10.4 Candado siempre visible con color según el modo — ya está hecho
Este punto del rediseño **ya existe tal cual en el código actual**
(`modeIcon` + clase `tree-mode-icon-unrestricted`, `ConnectionTreeCell.java` líneas
940-949, con sus tokens ya en `app.css`). No hace falta ningún cambio acá — es la única
pieza del mockup que el código de hoy iguala al 100%, se las señalo para que no le
dediquen tiempo de más.

### 10.5 Revelar editar/eliminar solo al pasar el mouse
Hoy `editButton`/`deleteButton` son siempre visibles (`.tree-edit-button` en `app.css`,
sin ninguna regla de hover a nivel de fila).

**Camino recomendado — CSS puro, sin tocar `ConnectionTreeCell.java`.** JavaFX sí soporta
selectores de pseudo-clase + descendiente (ya lo usan en este mismo archivo:
`.rail-button:selected .icon-stroke`), así que alcanza con agregar a `app.css`:

```css
.tree-edit-button {
    -fx-opacity: 0;
}
.connection-tree .tree-cell:filled:hover .tree-edit-button {
    -fx-opacity: 1;
}
```

**Limitación real a comunicar al equipo:** JavaFX no tiene `transition` declarativo en
CSS — con la regla de arriba el ícono aparece y desaparece de golpe, no con el fundido de
120ms del mockup. Para el fundido habría que agregar un `FadeTransition` en Java sobre
`editButton`/`deleteButton`, enganchado a `databaseRow.setOnMouseEntered`/
`setOnMouseExited` — el mismo patrón que ya existe en esta clase para `inUsePulse`
(líneas 228 y 1009-1019, cópienlo de ahí). Mi recomendación: arrancar con la versión
CSS-only (≈15 minutos, ya da el 90% del efecto) y dejar el fundido como mejora opcional
después, no como parte de un primer corte.

Nota de tamaño: `.tree-edit-button` hoy mide 20×20px con radio 5; el mockup lo dibujó a
22×22 con radio 6. Diferencia mínima, no vale la pena tocarla solo por esto.

### 10.6 Tarjetas con sombra (panel de Consulta / Resultados)
Es el cambio más grande del rediseño y el único que toca FXML, no solo CSS: hoy el
contenido central es superficie plana contra `-token-background`, sin ningún contenedor
de tarjeta.

1. En `main-view.fxml`, envolver el bloque del editor SQL y el bloque de resultados —
   cada uno por separado — en un `VBox` (o `StackPane`) nuevo con `styleClass="query-card"`
   / `styleClass="results-card"`.
2. En `app.css`, agregar:
   ```css
   .query-card, .results-card {
       -fx-background-color: -token-surface;
       -fx-background-radius: 14; /* --radius-container */
       -fx-effect: dropshadow(gaussian, rgba(15,23,42,0.06), 12, 0, 0, 4);
   }
   ```
   **Nota de fidelidad:** `--shadow-sm` del prototipo son en realidad DOS sombras
   apiladas (`0 1px 2px rgba(...), 0 4px 12px rgba(...)`); `-fx-effect` en una sola
   línea de CSS solo admite un efecto simple — replicar el doble reborde exacto
   requeriría anidar `DropShadow` en Java (`setInput(otroDropShadow)`). Recomiendo
   aproximar con la sombra única de arriba, ya calibrada para verse parecida — la
   diferencia es casi imperceptible y no vale el costo de mantenimiento de hacerlo en
   Java.
3. Revisar en `MainController.java` si algo referencia por `fx:id` los contenedores que
   se estén envolviendo, para no romper esos bindings al anidar un nivel más de FXML.

### 10.7 Selector de acento en la toolbar
No hace falta construir nada nuevo: la app **ya tiene** los 7 acentos completos
(`AccentPalette`, incluyendo "negro", que el mockup no mostró porque solo cubrió los 6
cromáticos). Si quieren un acceso rápido en la toolbar además del panel de Preferencias
existente, es cuestión de reusar `AccentPalette.swatchHex(name)` para pintar los
círculos — agregando el 7º ("negro") para que el atajo cubra lo mismo que ya soporta la
app, no menos.

### 10.8 Resumen de riesgo y esfuerzo por cambio

| Cambio | Archivos | Riesgo | Esfuerzo estimado |
| --- | --- | --- | --- |
| Tokens de color por motor (10.1) | `theme-light.css`, `theme-dark.css` | Bajo | 10 min |
| Insignia de motor a color (10.2) | `app.css`, `ConnectionTreeCell.java` | Bajo | 30 min |
| Reordenar insignia (10.3, opcional) | `ConnectionTreeCell.java` | Bajo | 5 min |
| Revelado hover editar/eliminar, sin fundido (10.5) | `app.css` | Bajo | 15 min |
| Revelado hover con fundido (10.5, opcional) | `ConnectionTreeCell.java` | Medio | 1-2 h |
| Tarjetas con sombra (10.6) | `main-view.fxml`, `app.css`, revisar `MainController.java` | Medio-alto | 2-4 h |
| Acento "negro" en atajo de toolbar (10.7, opcional) | FXML + controller de la toolbar | Bajo | 20 min |

Orden sugerido: 10.1 → 10.2 → 10.5 (sin fundido) primero — son los cambios de menor
riesgo y ya dejan la mayor parte del efecto visual del rediseño. 10.6 (tarjetas) al final,
por ser el único que toca layout de FXML y el más fácil de romper si se apura.
