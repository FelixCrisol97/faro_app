# Especificaciones de diseño — Faro (documento completo)

## 0. Qué es esto y de dónde sale cada dato

Este documento cubre **toda la app**, no solo la pantalla Consulta: colores, tipografía,
espaciado, sombras, una **sección dedicada de animaciones** (lo que faltaba en el primer
handoff) y la especificación de cada pantalla/diálogo — Conexiones, Historial, Favoritos,
Preferencias, Agregar base de datos, Importar CSV, menús y el resto.

**Fuentes, en orden de autoridad:**
1. `faro-java-prototipo.html` (y su versión legible, `prototipo_decodificado.html`, en esta
   misma carpeta) — el prototipo interactivo real del proyecto, la misma fuente que ya usó
   el equipo para escribir `app.css`. Es la referencia de más peso: cuando este documento da
   un valor sin más aclaración, viene de ahí.
2. El código Java real (`app.css`, `theme-light.css`/`theme-dark.css`, `ConnectionTreeCell.java`,
   `AccentPalette.java`, `PreferencesDialogController.java`) — para confirmar qué ya existe
   y, sobre todo, para **detectar dónde el equipo ya se apartó del prototipo a propósito**.
   Esos casos se marcan explícitamente como "decisión ya tomada, no revertir" — el ejemplo
   más claro es el tamaño del checkbox (ver §8).
3. Donde el prototipo vivo no especifica algo pero el handoff original del proyecto
   (`design_system/design_handoff_faro/README.md`, hoy superado en casi todo lo demás) sí lo
   mencionaba — como la animación de apertura de diálogos — se usa como **recomendación**,
   marcada como tal, no como algo verificado contra el archivo vivo.

**Relación con `rediseno-visual-handoff.md`** (el documento anterior, en esta misma
carpeta): ese archivo se queda como la referencia de implementación línea-por-línea para la
pantalla Consulta contra `ConnectionTreeCell.java`/`app.css` — no se duplica acá completo,
la sección 6.6-6.8 de este documento la resume y remite a él para el detalle de código. Este
documento nuevo es el que cubre **todo lo demás**: Historial, Favoritos, diálogos, y — lo que
pedían — animaciones.

---

## 1. Tokens de color

### 1.1 Neutros y semánticos

| Token | Claro | Oscuro |
| --- | --- | --- |
| `--background` | `#F8FAFC` | `#0F172A` |
| `--surface` | `#FFFFFF` | `#1E293B` |
| `--surface-alt` | `#F1F5F9` | `#334155` |
| `--text` | `#0F172A` | `#F1F5F9` |
| `--text-muted` | `#475569` | `#AEBACB` |
| `--border` | `#E2E8F0` | `#334155` |
| `--success-base` | `#059669` | `#34D399` |
| `--success-soft` | `#ECFDF5` | `rgba(52,211,153,.16)` |
| `--success-soft-text` | `#047857` | `#6EE7B7` |
| `--error-base` | `#DC2626` | `#F87171` |
| `--error-soft` | `#FEF2F2` | `rgba(248,113,113,.16)` |
| `--error-soft-text` | `#B91C1C` | `#FCA5A5` |
| `--warn-base` | `#B45309` | `#FBBF24` |
| `--warn-soft` | `#FEF3C7` | `rgba(180,83,9,.22)` |
| `--warn-soft-text` | `#92400E` | `#FCD34D` |
| `--backdrop` (fondo tras un diálogo) | `rgba(15,23,42,.55)` | `rgba(2,6,23,.66)` |

Uso semántico, para que "buenos colores" no se quede en la tabla: **success** es solo para
resultado positivo real (conexión ok, ejecución sin error, fila cargada) — nunca decorativo.
**Warn** es para todo lo que necesita atención sin ser un error todavía: modo "Sin
restricciones", una base respondiendo lento, una ejecución cancelándose. **Error** es solo
para fallo real (conexión caída, timeout, excepción SQL). Nunca usar warn donde va error ni
viceversa — es la diferencia entre "hay que mirarlo" y "algo se rompió".

### 1.2 Acento — 7 opciones (6 del prototipo + "negro", ya en `AccentPalette.java`)

| Acento | Base claro | Hover claro | Soft claro | Soft-text claro | Base oscuro | Soft oscuro | Soft-text oscuro |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Índigo (default) | `#6366F1` | `#4F46E5` | `#EEF2FF` | `#4338CA` | `#818CF8` | `rgba(129,140,248,.18)` | `#C7D2FE` |
| Violeta | `#8B5CF6` | `#7C3AED` | `#F5F3FF` | `#6D28D9` | `#A78BFA` | `rgba(167,139,250,.18)` | `#DDD6FE` |
| Azul | `#2563EB` | `#1D4ED8` | `#EFF6FF` | `#1D4ED8` | `#60A5FA` | `rgba(96,165,250,.18)` | `#BFDBFE` |
| Teal | `#0D9488` | `#0F766E` | `#F0FDFA` | `#0F766E` | `#2DD4BF` | `rgba(45,212,191,.18)` | `#99F6E4` |
| Rosa | `#E11D48` | `#BE123C` | `#FFF1F2` | `#BE123C` | `#FB7185` | `rgba(251,113,133,.18)` | `#FECDD3` |
| Ámbar | `#D97706` | `#B45309` | `#FFFBEB` | `#B45309` | `#FBBF24` | `rgba(251,191,36,.18)` | `#FDE68A` |
| Negro (solo en la app real, no en el prototipo web) | `#18181B` | `#09090B` | `#F4F4F5` | `#18181B` | `#FAFAFA` | `rgba(250,250,250,.16)` | `#E4E4E7` |

**Texto sobre el acento sólido** (botón "Ejecutar", pestaña activa si algún día lleva
relleno): no es blanco fijo — `AccentPalette.Tokens#onAccent` ya calcula el color legible
correcto por acento (blanco para los 7 en su versión "de texto oscuro sobre fondo claro",
pero **texto oscuro `#18181B`** para teal/ámbar en tema oscuro y para "negro" en tema
claro, donde el acento es demasiado claro para texto blanco — medido: teal oscuro con
blanco da 1.9:1 de contraste, con `#18181B` da 9.5:1). Esto **ya está bien resuelto en el
código**, ningún cambio pendiente acá — se documenta para que nadie lo "simplifique" de
vuelta a blanco fijo pensando que es una inconsistencia.

**Palabra reservada del editor SQL** (`editorKeyword`): igual al acento base para los 6
colores, pero fijo en índigo (`#6366F1`/`#818CF8`) para el acento "negro" — si no, con todo
el texto del editor en negrita y el acento coincidiendo con el color de texto normal, las
palabras reservadas se volverían invisibles. También ya resuelto.

### 1.3 Identidad de motor (nuevo, ver §8 del handoff de Consulta)
`--pg-bg` / `--pg-text` (azul, tono PostgreSQL) y `--mssql-bg` / `--mssql-text` (rojo, tono
SQL Server) — valores y ubicación exacta de implementación en `rediseno-visual-handoff.md`
§10.1. Se reutilizan en todo este documento donde aparece una insignia de motor (árbol,
Historial, Favoritos).

---

## 2. Tipografía

Tres familias, ya empaquetadas en `src/main/resources/com/faro/app/fonts/`: **Sora**
(`--font-heading`, peso 600/700, solo títulos — nunca cuerpo de texto), **Manrope**
(`--font-body`, cuerpo y controles, normalmente peso 500-600), **JetBrains Mono**
(`--font-mono`, SQL, hosts, cifras, atajos de teclado).

Tamaños vistos en todo el prototipo (además de los ya documentados en
`rediseno-visual-handoff.md` §2 para Consulta):

| Contexto | Familia | Peso | Tamaño |
| --- | --- | --- | --- |
| Título de diálogo ("Agregar base de datos", "Preferencias") | Sora | 600 | 17px |
| Encabezado de sección dentro de un diálogo/panel ("Tema", "Color de acento") | Sora | 600 | 13.5px |
| Etiqueta de campo (`<label>` sobre un input) | Manrope | 600 | 11.5px |
| Texto de input / botón de diálogo | Manrope | 600 | 12.5px |
| Atajo de teclado en un menú o en la lista de Atajos | JetBrains Mono | 500 | 10.5-11px |
| Tarjeta de Historial — hora | Manrope | 700 | 12px |
| Tarjeta de Historial — query previsualizada | JetBrains Mono | 500 | 11px |
| Tarjeta de Historial — metadata (filas, servidor) | Manrope | 500 | 11px |
| Tarjeta de Favoritos — título | Sora | 600 | 13px |
| Tarjeta de Favoritos — query previsualizada | JetBrains Mono | 500 | 11px |
| Encabezado de columna de resultados — nombre | Manrope | 700 | 12px |
| Encabezado de columna de resultados — tipo de dato (línea 2, bajo el nombre) | Manrope | 500 | 10px, `--text-muted` |
| Fila de Ejecución — nombre de base | Manrope | 500 | 12.5px |
| Fila de Ejecución — host | JetBrains Mono | 500 | 10.5px |
| Fila de Ejecución — filas/tiempo (alineado a la derecha) | JetBrains Mono | 500 | 11.5px |
| Línea de Diagnóstico | JetBrains Mono | 500 (nivel en 700) | 11.5px, interlineado 1.9 |
| Placeholder de buscador | Manrope | 500 | 12.5px |
| Segmento de control (Individual/Masiva, motor, tema) | Manrope | 600 | 12-12.5px |
| Toast de confirmación | Manrope | 500 | 12.5px |

---

## 3. Espaciado, radios y sombras

Sin cambios respecto al handoff de Consulta — se repiten acá porque este documento es el
que debe bastarse solo:

- **Radios**: `--radius-control: 9px` (botones, inputs, chips), `--radius-chip: 6px`
  (badges pequeños), `--radius-container: 14px` (tarjetas y diálogos), `999px` (píldoras,
  segmentos, swatches). Contextos nuevos vistos en este documento: menús desplegables y
  menú contextual usan **10-12px**, no 14 — son más chicos que una tarjeta pero más grandes
  que un chip; tratarlos como su propia categoría, "radio de menú".
- **Sombras**: `--shadow-sm` (tarjetas: Consulta, Historial, Favoritos), `--shadow-md`
  (menús desplegables, menú contextual, combo-box abierto — confirmado también en
  `app.css` línea 1386, `.combo-box-popup .list-view`), `--shadow-lg` (diálogos modales).
- **Fondo tras un diálogo** (`--backdrop`): un color propio, no `rgba(0,0,0,.5)` genérico —
  ver §1.1. Es semitransparente sobre el `--text`/`--background` del tema, para que el
  velo se sienta parte de la misma paleta y no un gris neutro importado.

---

## 4. Animaciones — catálogo completo

Es la sección que faltaba. Cada entrada dice el disparador, la curva/duración exacta (tal
cual está en `faro-java-prototipo.html`), y si ya existe en el código Java o es nueva.

| # | Animación | Disparador | Curva / duración exacta | Estado en Java |
| --- | --- | --- | --- | --- |
| 1 | Rotación de flecha de expandir/colapsar (árbol de conexiones, categorías de esquema) | Clic en el grupo/categoría | `transform: rotate(90deg)`, `transition: transform .12s ease` | **Nueva** — hoy la flecha cambia de forma sin girar (ver §7 más abajo) |
| 2 | Barra de progreso por base en la pestaña Ejecución | Mientras una consulta corre | `transition: width .12s linear` | Nueva a nivel visual — `ExecutionTableFactory` ya tiene `.exec-bar`, confirmar si ya anima el ancho o solo lo fija |
| 3 | Barra "N de 1,240 filas en memoria" (carga progresiva de resultados grandes) | Cada vez que se cargan 200 filas más | `transition: width .2s ease` | Relacionado con el streaming de resultados que ya llegó a `main` (ver conversación) — confirmar con el equipo si esa barra ya existe |
| 4 | Spinner circular (exportando CSV, cargando esquema) | Exportación en curso / expandiendo una base con esquema no cacheado | `@keyframes spin { to { transform: rotate(360deg) } }`, `animation: spin .8s linear infinite` — anillo con un lado transparente | **Ya existe**: `MainController` usa `RotateTransition` para "Exportando…" (ver `.export-spinner` en `app.css`) y `ConnectionTreeCell` usa `ProgressIndicator` real para "Cargando esquema…". Mismo lenguaje visual, ya resuelto |
| 5 | Toast de confirmación ("Guardado", "CSV exportado…") | Cualquier acción de confirmación rápida | Entrada: `opacity 0→1` + `translateY(10px→0)`, `.18s ease`. Sin animación de salida explícita (desaparece a los ~2.6s) | **Nueva** — revisar si la app ya tiene algún mecanismo de toast/snackbar o si las confirmaciones hoy son solo diálogos/`Alert` |
| 6 | Pulso de "conexión en uso" (punto de estado del árbol) | Mientras esa base tiene una consulta corriendo | `opacity` 1 ↔ 0.35, 600ms por medio ciclo, indefinido, con `auto-reverse` | **Ya existe y coincide exacto** — `ConnectionTreeCell#inUsePulse`, `FadeTransition` 1.0→0.35, `Duration.millis(600)`, `Animation.INDEFINITE`, `setAutoReverse(true)`. No tocar, ya es fiel al prototipo |
| 7 | Revelado de editar/eliminar al pasar el mouse sobre una fila | Hover de fila de conexión | `opacity: 0→1`, `.12s ease` | Ver `rediseno-visual-handoff.md` §10.5 — CSS-only sin fundido, o `FadeTransition` para el fundido real |
| 8 | **[Recomendado, no confirmado en el prototipo vivo]** Apertura de diálogo | Cualquier diálogo modal | `opacity 0→1` + `scale 0.96→1`, `~180ms ease-out`; el backdrop hace fade aparte, mismo tiempo | El handoff original del proyecto lo menciona ("quick fade+scale-in ~180ms") pero el `faro-java-prototipo.html` actual no lo implementa en su lógica de estado — es una recomendación consistente con la intención original, no algo ya validado |
| 9 | **[Recomendado]** Transición de color al cambiar tema o acento | Toggle de tema/acento en Preferencias | `~250ms`, color/fondo | Mismo caso que el anterior — intención del handoff original, no confirmada en el archivo vivo. En JavaFX esto es más caro de lo que parece (no hay transición de color declarativa entre hojas de estilo) — tratarlo como mejora opcional, no bloqueante |
| 10 | **[Recomendado]** Aparición de resultados al completar una consulta | Ejecución termina | Fade/slide breve (~200-250ms) | Mencionado en el handoff original de Consulta, no en este prototipo — opcional |

**Nota para el equipo sobre JavaFX y animación:** de las 10, las **1, 2, 3, 5, 8, 9 y 10**
necesitan una `Transition` de Java (`RotateTransition`, algo enlazado al `widthProperty` de
una barra, `FadeTransition`, etc.) porque JavaFX no tiene `transition:`/`@keyframes`
declarativo en CSS — no son "una línea de CSS", son código. Las **4 y 6 ya están resueltas**
con ese mismo mecanismo, cópienlo de ahí en vez de reinventar el patrón. La **7** puede
salir con CSS puro (sin fundido) como primer corte, según el handoff de Consulta.

---

## 5. Componentes compartidos (se repiten en varias pantallas — documentarlos una vez)

### 5.1 Control segmentado (pill de 2-3 opciones)
Track: `background: var(--surface-alt)`, `border-radius: 10px`, `padding: 3px`, `gap: 2px`.
Opción seleccionada: `background: var(--surface)`, `box-shadow: var(--shadow-sm)`,
`color: var(--text)`. Opción no seleccionada: transparente, `color: var(--text-muted)`.
Texto siempre Manrope 600, 12-12.5px. Usado en: Individual/Masiva (panel Conexiones),
motor PostgreSQL/SQL Server (diálogo Agregar base), Claro/Oscuro (Preferencias →
Apariencia).

### 5.2 Selector de acento (swatch circular)
Círculo de 34×34px, `border-radius: 999px`, `padding: 3px`; el color real del acento va
en un círculo interior (así el padding deja ver el anillo). Seleccionado: `border: 2px solid
var(--text)`. No seleccionado: `border: 2px solid transparent`. Ya implementado en
`PreferencesDialogController`/`.pref-accent-swatch` — con un detalle extra que el prototipo
web no necesita: un contorno fino (`.pref-accent-dot`, `stroke: -token-text-muted`)
alrededor del propio círculo de color, porque el acento "negro" en tema oscuro es casi el
mismo color que el fondo del diálogo y desaparecería sin él. Mantener ese contorno — es una
mejora real sobre el prototipo, no una desviación a corregir.

### 5.3 Interruptor (toggle switch) — **nuevo, no existe hoy en el código**
Visto en Preferencias → Rendimiento ("Cancelar también en el servidor", "Exportar CSV en
segundo plano", etc.): pista de 38×21px, `border-radius: 999px`; encendido =
`background: var(--accent-base)` con el círculo (15×15px, blanco) pegado a la derecha
(`right: 3px`); apagado = `background: var(--border)` con el círculo (mismo tamaño,
`background: var(--surface)`) pegado a la izquierda (`left: 3px`). El prototipo no define
una transición explícita para el deslizamiento del círculo — se recomienda
`transition: left .15s ease` (o el equivalente animado en Java) para que no se sienta un
salto. **Antes de construirlo**, confirmar con el equipo si Preferencias ya usa `CheckBox`
nativo para estas opciones — si es así, esto es un cambio de componente además de estilo,
no solo CSS nuevo.

### 5.4 Badge / chip / píldora
Ya documentado por tipo en `rediseno-visual-handoff.md` — la regla general: `radius: 6px`
para chips de conteo/tipo de dato, `999px` para todo lo demás (estado, motor, etiquetas de
Historial/Favoritos). Fondo siempre del par `--*-soft`/`--*-soft-text` correspondiente,
nunca el color base sólido (el base sólido es solo para elementos grandes: botones, punto
de estado).

### 5.5 Menú desplegable / menú contextual
`background: var(--surface)`, `border: 1px solid var(--border)`, `border-radius: 10-12px`,
`box-shadow: var(--shadow-md)`, `padding: 6px`. Cada ítem: `padding: 7-8px 10px`,
`border-radius: 7px`, hover `background: var(--surface-alt)`; si lleva atajo de teclado, va
a la derecha en `--font-mono`, 10.5px, `--text-muted`. Separador: línea de 1px
`--border` con `margin: 5px 2px`. Ya implementado en `.context-menu` de `app.css` para los
menús de la barra superior — el menú contextual (clic derecho en una fila del árbol o en un
objeto de esquema) debe seguir exactamente el mismo tratamiento, no uno propio.

### 5.6 Diálogo modal
`width` variable según contenido (520px Agregar base, 460px Importar CSV, 640×440px
Preferencias — con panel lateral de pestañas). `background: var(--surface)`,
`border-radius: var(--radius-container)` (14px), `box-shadow: var(--shadow-lg)`, recortado
(`overflow: hidden`). Estructura fija de 3 bloques: título (padding `16px 18px 0`, Sora
600 17px) → cuerpo con scroll propio si hace falta (`padding: 14px 18px`, `gap: 12px` entre
campos) → pie con borde superior 1px `--border` (`padding: 12px 18px 16px`, botones
alineados a la derecha salvo una acción secundaria a la izquierda cuando existe, como
"Probar conexión"). Fondo tras el diálogo: `--backdrop` (§1.1), cubriendo toda la ventana.

### 5.7 Campo de texto — decisión pendiente, ver §8
El prototipo dibuja TODOS los inputs como píldora completa (`border-radius: 999px`); la
app real hoy usa `.text-field`/`.combo-box` con radio 8px, rectangular. Ver §8 antes de
tocar esto — es una decisión de diseño, no un simple ajuste de CSS.

---

## 6. Especificación por pantalla

### 6.1-6.2 Barra de título, menú y toolbar
Ya cubiertas en detalle en `rediseno-visual-handoff.md` §5.1-5.3 — sin cambios, se
reutiliza tal cual.

### 6.3 Riel de íconos + panel de Conexiones
- **Riel** (46px de ancho): 3 botones verticales (Conexiones/Historial/Favoritos,
  34×34px, radio 9px) + un espacio flexible + botón de Preferencias abajo. Seleccionado:
  `background: var(--accent-soft)`, ícono `color: var(--accent-base)`. No seleccionado:
  ícono `color: var(--text-muted)`, sin fondo.
- **Encabezado del panel** (34px): título del panel activo en mayúsculas, Sora 600 11.5px,
  letter-spacing .06em, `color: var(--text-muted)`, borde inferior 1px `--border`.
- **Buscador**: input píldora con ícono de lupa a la izquierda (14px, posición absoluta),
  placeholder "Buscar servidor o base de datos…".
- **Fila de filtro**: segmentado Individual/Masiva (§5.1) + botón cuadrado "+" (30×30px,
  borde 1px, radio 9px) para agregar base de datos.
- **Árbol**: grupo (16-17px ícono de servidor, nombre Sora 600 12.5px, conteo a la
  derecha en `--text-muted`) → base de datos (checkbox, punto de color de 7px de estado,
  nombre 12.5px, insignia de motor mono 9.5px en chip `radius: 4px`) → categoría de
  esquema (Tablas/Vistas/Funciones/Procedimientos/Triggers, con conteo en chip mono
  `radius: 5px`) → objeto individual (ícono de tabla + nombre, 12px). Cada nivel indenta
  ~18px respecto al anterior. Punto de estado: verde `--success-base` = conectado,
  ámbar `--warn-base` = advertencia (ver la fila "Tienda Polanco" del prototipo, con
  insignia "SIN RESTRICCIONES"), gris `--text-muted`/`--border` = sin verificar. Fila
  seleccionada (modo Individual, radio en vez de checkbox): `background: var(--accent-soft)`.

### 6.4 Historial
Lista de tarjetas (`background: var(--surface)`, `border: 1px solid var(--border)`,
`border-radius: 12px`, `box-shadow: var(--shadow-sm)`, `padding: 10px`, `margin-bottom:
8px` entre tarjetas). Cada tarjeta: fila superior con hora (Manrope 700 12px) + píldora de
estado (`radius: 5px`, `padding: 1px 6px`, 10px 700 — verde `success-soft` para éxito,
rojo `error-soft` para cancelada/bloqueada); debajo, la query previsualizada en monospace
11px, truncada a una línea; al final, metadata en `--text-muted` 11px (filas, servidor, o
"KILL enviado · N bases" cuando fue cancelada).

### 6.5 Favoritos
Mismo tratamiento de tarjeta que Historial (`shadow-sm`, `radius: 12px`). Contenido:
título en Sora 600 13px, query previsualizada en monospace 11px `--text-muted`, fila de
tags (chip `accent-soft` para la categoría principal, `surface-alt` para el resto) —
**el prototipo no muestra acá el botón "Usar" ni el de eliminar** que sí describía el
handoff original; confirmar con el equipo si van (mismo tratamiento ghost/trash-icon que
ya usa Consulta) o si Favoritos es solo consulta+tags y la acción de "usar" es hacer clic
en la tarjeta entera.

### 6.6 Pestañas de consulta (multi-tab)
Cada pestaña: ícono de archivo + nombre +, si tiene cambios sin guardar, un punto ámbar
(`color: var(--warn-base)`, 15px, `line-height: 0`) a la derecha del nombre — detalle que
**no estaba en el handoff de Consulta anterior**, agregarlo. Activa: borde inferior 2px
`--accent-base`, `background: var(--background)` (no `--surface`, para que se note que
"se hunde" hacia el panel de abajo). Botón "+" al final, mismo tratamiento que el resto de
los botones-ícono ghost.

### 6.7 Editor SQL
Ya cubierto en `rediseno-visual-handoff.md` §5.5 — un detalle nuevo confirmado acá: la
barra de info bajo el editor no es solo "Ln X, Col Y" — el prototipo real muestra también
qué motores están involucrados cuando hay más de uno seleccionado ("Ln 5, Col 24 · UTF-8 ·
PostgreSQL + SQL Server").

### 6.8 Resultados / Ejecución / Diagnóstico
- **Pestañas inferiores**: mismo lenguaje que las de consulta (subrayado 2px acento),
  cada una con un badge de conteo (`accent-soft` si esa pestaña está activa,
  `surface-alt`/`text-muted` si no).
- **Resultados**: fila de píldoras por base (ya cubierta) + tabla con encabezado de
  **dos líneas** — nombre de columna (700, 12px) y, debajo, el tipo de dato real
  (`text`, `integer`, `numeric(10,2)`) en 500, 10px, `--text-muted`. Esto no estaba en el
  mockup de Consulta original — agregarlo si el equipo lo considera útil para leer
  resultados de tablas con tipos mixtos. Pie de tabla con carga progresiva: barra de 170px
  (`radius: 999px`, relleno `--accent-base`, `transition: width .2s ease`), etiqueta "N de
  M filas en memoria" y botón "Cargar 200 más" — patrón exclusivo de resultados grandes,
  ver la nota de la fila 3 en el catálogo de animaciones.
- **Ejecución**: una fila por base con punto de color + nombre + host (mono) + badge de
  estado (`en cola` gris / `ejecutando` acento / `listo` verde / `cancelando`+`cancelada`
  ámbar / `error` rojo) + barra de progreso individual + filas + tiempo (alineados a la
  derecha, mono) + botón "×" para cancelar esa base sola.
- **Diagnóstico**: log de texto corrido, mono 11.5px, interlineado 1.9, con el nivel en
  700 y su color semántico (`INFO` verde, `WARN` ámbar, `ERROR` rojo, `DEBUG` muted) — ya
  documentado, sin cambios.

### 6.9 Barra de estado
Ya cubierta — el detalle nuevo es el **spinner de exportación in-line** (animación #4 del
catálogo): un anillo de 11px con un borde transparente, girando junto al texto
"Exportando CSV… N%", en `color: var(--accent-soft-text)`.

### 6.10 Menú contextual
Ver §5.5 — mismo tratamiento que los menús de la barra superior, posicionado en las
coordenadas del clic derecho. Opciones vistas: "Generar SELECT/UPDATE/script CREATE",
"Importar CSV…", separador, "Abrir en pestaña" / "Abrir en ventana nueva" /
"Credenciales…".

### 6.11 Diálogo "Agregar base de datos"
520px de ancho. Segmentado de motor (PostgreSQL/SQL Server) arriba de todo. Grid de 2
columnas para los campos (Nombre visible, Base de datos, Host, Puerto, Usuario,
Contraseña) — todos con `<label>` (§2) sobre un input píldora (§5.7, pendiente de
decisión). Selector de modo (Solo lectura/Sin restricciones) como **dos píldoras tipo
radio**, no un segmentado: la opción activa lleva `border: 1px solid var(--accent-base)`
+ `background: var(--accent-soft)` + un punto relleno dentro de un círculo; la inactiva,
borde `--border` y círculo vacío. Grid de 2 columnas para Pool/Timeout. Banner de
confirmación de conexión (`background: var(--success-soft)`, `radius: 10px`, ícono de
palomita) cuando la prueba fue exitosa. Pie: "Probar conexión" a la izquierda (secundario),
"Cancelar" + "Guardar" a la derecha.

### 6.12 Diálogo "Importar CSV"
460px. Zona de "drag and drop" (`border: 1.5px dashed var(--border)`, `radius: 12px`,
`padding: 20px`, texto centrado con el enlace "búscalo en tu equipo" en color de acento).
Grid de 2 columnas (Separador, Tamaño de lote). Checkbox "La primera fila contiene los
encabezados" con el mismo tratamiento de casilla que el árbol de conexiones. Pie: solo
"Cancelar" + "Importar" (sin acción secundaria a la izquierda, a diferencia de Agregar
base).

### 6.13 Diálogo "Preferencias"
640×440px, con panel lateral fijo de 170px (`background: var(--surface-alt)`) y 3
pestañas verticales (Apariencia/Rendimiento/Atajos, mismo tratamiento que un ítem de menú
seleccionado — `background: var(--surface)` + `shadow-sm` cuando está activa).
- **Apariencia**: segmentado Claro/Oscuro, selector de acento (§5.2, ya implementado),
  slider de tamaño de fuente del editor (pista 4px `surface-alt`, relleno `accent-base`,
  perilla circular de 14px).
- **Rendimiento**: filas etiqueta+input (Timeout de consulta/conexión, conexiones
  máximas, fetch size) y filas etiqueta+interruptor (§5.3, nuevo) para las opciones
  booleanas (cancelar también en servidor, exportar en streaming, liberar resultados al
  cerrar pestaña).
- **Atajos**: lista de fila etiqueta-mono (chip `radius: 6px`, `min-width: 96px`, texto
  centrado) + descripción — de solo lectura, no editable.

### 6.14 Toast
Ver animación #5 del catálogo. Caja oscura fija en los dos temas (`background: #0F172A`,
`color: #F8FAFC` — mismo criterio que `.tooltip` en `app.css`, no tokenizado a propósito),
centrada horizontalmente, `bottom: 46px` (sobre la barra de estado), `radius: 10px`,
`box-shadow: var(--shadow-md)`, `padding: 10px 16px`.

---

## 7. Notas de implementación en Java (nivel de detalle general — no línea por línea)

Esto complementa, no reemplaza, la sección 10 de `rediseno-visual-handoff.md` (que sí va
línea por línea para Consulta). Acá va un mapa de dónde vive cada cosa, para que el equipo
sepa por dónde empezar a buscar antes de construir de cero:

| Área | Dónde ya existe algo | Qué falta (según este documento) |
| --- | --- | --- |
| Rotación de flecha de árbol | `ConnectionTreeCell` ya escala la flecha (`applyDisclosureScale`) pero no la rota al expandir | Agregar `RotateTransition` de 0→90° en el mismo lugar |
| Spinner de exportación / carga de esquema | Ya existe (`.export-spinner` + `RotateTransition`; `ProgressIndicator` en `SchemaTreeNode.Loading`) | Nada — ya coincide con el prototipo |
| Pulso "en uso" | Ya existe, `ConnectionTreeCell#inUsePulse`, valores exactos | Nada |
| Toast de confirmación | No se encontró un mecanismo genérico de toast en la revisión de este documento | Confirmar con el equipo si existe (quizás como `Alert`/`Tooltip` temporal) antes de construir uno nuevo |
| Interruptor (toggle switch) | No hay clase `.toggle`/equivalente en `app.css` | Confirmar si Preferencias → Rendimiento usa `CheckBox` nativo hoy — cambiar de componente es más que un ajuste de CSS |
| Segmentado (pill de opciones) | No hay clase `.segmented` en `app.css` | Revisar cómo están hechos hoy Individual/Masiva, el picker de motor y Claro/Oscuro — probablemente `ToggleButton`/`RadioButton` sin el tratamiento visual de pista+pill |
| Campo de texto píldora vs rectangular | `.text-field`/`.combo-box` ya existen con radio 8px | Ver §8 — decisión de diseño antes de tocar nada |
| Menú contextual con el mismo estilo que `.context-menu` | `.context-menu` ya existe y ya se usa para los menús de la barra superior | Confirmar que el menú contextual de clic derecho (árbol/esquema) reutiliza la misma clase y no una propia |

---

## 8. Decisiones abiertas para el equipo

Estos son los puntos donde el prototipo, el código real, y este documento no coinciden —
alguien tiene que decidir, no son autoexplicativos:

1. **Tamaño del checkbox del árbol**: el prototipo usa 14×14px con radio 4; la app real
   usa 18×18px con radio 3, **a pedido explícito del usuario** ("los checkbox de las bd
   están muy chicos", con captura). **Mantener 18×18/radio 3** — es una decisión ya
   tomada con el usuario real, no un descuido a corregir contra el prototipo.
2. **Candado de modo vs. texto "SIN RESTRICCIONES"**: el prototipo todavía muestra el
   badge de texto (`§6.3`, fila "Tienda Polanco"); la app real ya lo reemplazó por un
   ícono de candado con color semántico, también a pedido explícito del usuario. **Es una
   mejora real sobre el prototipo — no revertir.**
3. **Campos de texto — píldora (999px) vs. rectangular (8px)**: el prototipo es
   consistentemente píldora en todos los diálogos; la app real usa rectangular hoy. Ningún
   comentario en el código indica que fue una decisión deliberada — probablemente nunca se
   implementó, no que se haya rechazado. Si el equipo quiere fidelidad completa al
   prototipo, es un cambio de una sola clase (`.text-field`/`.combo-box` en `app.css`)
   pero afecta a TODOS los diálogos a la vez — vale la pena decidirlo antes de tocarlo,
   no dialogo por diálogo.
4. **Animaciones 8, 9 y 10** (apertura de diálogo, transición de tema, fade de
   resultados): son recomendación basada en la intención del handoff original del
   proyecto, no algo verificado en el `faro-java-prototipo.html` vivo. Tratarlas como
   mejoras opcionales de "pulido", no como parte obligatoria de este rediseño.
5. **Botones de Favoritos** ("Usar"/eliminar): el handoff original los describía, el
   prototipo vivo no los dibuja. Confirmar si siguen siendo parte del diseño.
