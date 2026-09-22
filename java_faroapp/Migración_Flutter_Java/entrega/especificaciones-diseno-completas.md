# Especificación de diseño — Faro

## 0. Cómo usar este documento

Esta es la especificación de diseño completa de **Faro** (cliente de escritorio para
correr una misma consulta SQL contra muchas bases de datos a la vez, agrupadas por
servidor). Está escrita para que **cualquier equipo, en cualquier lenguaje o framework**
—JavaFX, Flutter, Electron/React, Qt, SwiftUI, WinUI, lo que sea— pueda tomarla e
implementarla desde cero, como si fuera el documento de diseño de una aplicación nueva.
No asume ningún framework: los colores son valores, no variables CSS; las animaciones
están descritas por propiedad/duración/curva, no como código; los componentes están
descritos por estructura y comportamiento, no por clase o selector.

Hay **un único anexo al final (sección 12)** que sí es específico de este proyecto: dice
en qué se diferencia la implementación Java que ya existe (`java_faroapp`) de esta
especificación. Un equipo que empieza de cero en otro lenguaje puede ignorarlo por
completo; un equipo que sigue sobre el código Java existente lo va a necesitar.

**De dónde sale cada dato:** la base es el prototipo interactivo del propio proyecto
(`faro-java-prototipo.html`, en esta misma carpeta), verificado línea por línea. Se cruzó
además contra el código de producción real en la rama `main` del repositorio —no solo
contra una copia local desactualizada— para no arrastrar nada que el equipo ya haya
cambiado o decidido distinto en el camino. Donde ambas fuentes coinciden, no se aclara
nada especial; donde no coinciden, se dice explícitamente cuál manda y por qué (sección
11).

---

## 1. Filosofía de diseño

Faro es una herramienta de trabajo para desarrolladores/DBAs, usada varias horas seguidas
— no un producto de consumo. El lenguaje visual es **SaaS moderno, denso mas no
apretado**: fondo neutro con tarjetas blancas de elevación sutil, un único color de
acento (elegible por el usuario entre 7 opciones) que se usa con disciplina —nunca como
decoración—, y tipografía dividida en tres roles claros: una fuente de título, una de
cuerpo/controles y una monoespaciada para todo lo que es dato técnico (SQL, hosts, cifras,
atajos de teclado). La jerarquía se construye con **peso tipográfico y espaciado antes que
con color**: el color queda reservado para estado (éxito/error/advertencia) y para el
acento de marca.

Dos temas completos, claro y oscuro, con el mismo lenguaje en los dos — nunca "modo oscuro
como ocurrencia tardía": cada token de color tiene su propio valor calibrado para cada
tema, no una inversión automática.

---

## 2. Sistema de color

### 2.1 Tokens neutros y de superficie

| Token | Claro | Oscuro | Uso |
| --- | --- | --- | --- |
| `background` | `#F8FAFC` | `#0F172A` | Fondo de ventana, detrás de las tarjetas |
| `surface` | `#FFFFFF` | `#1E293B` | Tarjetas, diálogos, menús, barras |
| `surface-alt` | `#F1F5F9` | `#334155` | Relleno de inputs, filas al pasar el mouse, fondo del editor de código |
| `text` | `#0F172A` | `#F1F5F9` | Texto principal |
| `text-muted` | `#475569` | `#AEBACB` | Texto secundario, metadatos, placeholders |
| `border` | `#E2E8F0` | `#334155` | Bordes de 1px, divisores |
| `backdrop` | `rgba(15,23,42,.55)` | `rgba(2,6,23,.66)` | Velo detrás de un diálogo modal |

### 2.2 Tokens semánticos (iguales en los dos temas en su *intención*, distinto valor)

| Rol | Base claro | Suave claro | Texto-sobre-suave claro | Base oscuro | Suave oscuro | Texto-sobre-suave oscuro |
| --- | --- | --- | --- | --- | --- | --- |
| Éxito | `#059669` | `#ECFDF5` | `#047857` | `#34D399` | `rgba(52,211,153,.16)` | `#6EE7B7` |
| Error | `#DC2626` | `#FEF2F2` | `#B91C1C` | `#F87171` | `rgba(248,113,113,.16)` | `#FCA5A5` |
| Advertencia | `#B45309` | `#FEF3C7` | `#92400E` | `#FBBF24` | `rgba(180,83,9,.22)` | `#FCD34D` |

**Regla de uso, no solo de valor:** *Éxito* es exclusivamente para un resultado positivo
real (conexión probada con éxito, ejecución sin errores, fila cargada) — nunca decorativo.
*Advertencia* es para lo que necesita atención sin ser todavía un fallo: modo "sin
restricciones" activo en una conexión, una base respondiendo lento, una ejecución en
proceso de cancelarse. *Error* es solo fallo real: conexión caída, timeout, excepción SQL.
No intercambiar advertencia y error — comunican cosas distintas ("hay que mirarlo" vs.
"algo se rompió") y el usuario aprende a confiar en esa distinción con el tiempo.

Cada rol se usa en **tres capas**: el color *base* (puntos de estado, texto de énfasis),
el *suave* (fondo de una píldora/badge) y su *texto-sobre-suave* (el texto que va encima
de ese fondo suave — no es el mismo que el texto normal, está afinado para tener
suficiente contraste sobre ese fondo tintado).

### 2.3 Acento de marca — 7 opciones, elegibles por el usuario

| Acento | Base claro | Hover claro | Suave claro | Texto-sobre-suave claro | Base oscuro | Suave oscuro | Texto-sobre-suave oscuro |
| --- | --- | --- | --- | --- | --- | --- | --- |
| Índigo (por defecto) | `#6366F1` | `#4F46E5` | `#EEF2FF` | `#4338CA` | `#818CF8` | `rgba(129,140,248,.18)` | `#C7D2FE` |
| Violeta | `#8B5CF6` | `#7C3AED` | `#F5F3FF` | `#6D28D9` | `#A78BFA` | `rgba(167,139,250,.18)` | `#DDD6FE` |
| Azul | `#2563EB` | `#1D4ED8` | `#EFF6FF` | `#1D4ED8` | `#60A5FA` | `rgba(96,165,250,.18)` | `#BFDBFE` |
| Teal | `#0D9488` | `#0F766E` | `#F0FDFA` | `#0F766E` | `#2DD4BF` | `rgba(45,212,191,.18)` | `#99F6E4` |
| Rosa | `#E11D48` | `#BE123C` | `#FFF1F2` | `#BE123C` | `#FB7185` | `rgba(251,113,133,.18)` | `#FECDD3` |
| Ámbar | `#D97706` | `#B45309` | `#FFFBEB` | `#B45309` | `#FBBF24` | `rgba(251,191,36,.18)` | `#FDE68A` |
| Negro/monocromo | `#18181B` | `#09090B` | `#F4F4F5` | `#18181B` | `#FAFAFA` | `rgba(250,250,250,.16)` | `#E4E4E7` |

El acento re-tematiza toda la app al instante: botón primario, foco de teclado, subrayado
de pestaña activa, palabra reservada del editor SQL, punto/fondo de selección.

**Regla de contraste para texto sobre el acento sólido** (el botón "Ejecutar", por
ejemplo): no asumir blanco fijo. Para 5 de los 7 acentos, blanco funciona en los dos
temas. Pero **teal y ámbar en tema oscuro**, y **negro/monocromo en tema claro**, usan
texto **oscuro (`#18181B`)** porque su base es demasiado clara para texto blanco —medido:
teal oscuro con blanco encima da 1.9:1 de contraste (ilegible), con `#18181B` da 9.5:1.
Cada acento debe declarar su propio color de texto legible, no asumir uno global.

**La palabra reservada del editor SQL** normalmente usa el color base del acento activo
— excepto en el acento monocromo, donde eso la volvería indistinguible del texto normal
del editor (el acento base y el color de texto normal coinciden casi exacto ahí). En ese
caso específico, la palabra reservada se queda fija en índigo (`#6366F1` claro /
`#818CF8` oscuro) en vez de seguir al acento — la app deja de ser monocromática solo en
esta única excepción, deliberadamente, para no perder el resaltado de sintaxis.

### 2.4 Identidad de motor de base de datos

| Token | Claro | Oscuro | Uso |
| --- | --- | --- | --- |
| `engine-postgres-bg` / `engine-postgres-text` | `rgba(51,103,145,.12)` / `#2C5877` | `rgba(96,165,250,.18)` / `#93C5FD` | Insignia "PG" |
| `engine-mssql-bg` / `engine-mssql-text` | `rgba(204,41,39,.12)` / `#B91C1C` | `rgba(248,113,113,.18)` / `#FCA5A5` | Insignia "MSSQL" |

Colores de identidad de marca de cada motor (el azul de PostgreSQL, el rojo de SQL
Server), no acentos configurables — se mantienen fijos sin importar el acento activo, para
que el motor de una conexión se reconozca de un vistazo sin depender de leer el texto.

---

## 3. Tipografía

Tres roles, tres familias:

| Rol | Familia | Cuándo usarla |
| --- | --- | --- |
| Título (`heading`) | **Sora**, peso 600-700 | Títulos de pantalla, de tarjeta, de diálogo, marca de la app. Nunca para cuerpo de texto ni controles. |
| Cuerpo/controles (`body`) | **Manrope**, peso 500-700 según énfasis | Todo el texto de interfaz: labels, botones, listas, menús. |
| Monoespaciada (`mono`) | **JetBrains Mono**, peso 500-700 | SQL, hosts (`ip:puerto`), cifras tabulares, atajos de teclado, nombres de columna con su tipo de dato. |

### Escala de tamaños

| Contexto | Familia | Peso | Tamaño |
| --- | --- | --- | --- |
| Marca de la app (barra de título) | heading | 700 | 12.5px |
| Título de diálogo | heading | 600 | 17px |
| Título de tarjeta / sección | heading | 600-700 | 13.5-15px |
| Cuerpo / botones / labels de campo | body | 600 | 12-12.5px |
| Etiqueta de campo (sobre un input) | body | 600 | 11.5px |
| Texto secundario / metadatos | body | 500 | 11-11.5px |
| Texto muy pequeño (badges, chips) | body | 700 | 9.5-10.5px |
| Código SQL | mono | 500 (palabras clave 700) | 13-13.5px, interlineado 1.55-1.6 |
| Host, cifras tabulares | mono | 500 | 9.5-12.5px según contexto |
| Log de diagnóstico | mono | 500 (nivel 700) | 11.5px, interlineado 1.9 |
| Atajo de teclado | mono | 500 | 10.5-11px |

---

## 4. Espaciado, radios y elevación

### 4.1 Radios

| Nombre | Valor | Dónde |
| --- | --- | --- |
| `radius-control` | 9px | Botones, inputs, chips de un solo control |
| `radius-chip` | 6px | Badges pequeños (conteos, tipo de dato) |
| `radius-menu` | 10-12px | Menús desplegables, menú contextual |
| `radius-container` | 14px | Tarjetas, diálogos |
| `radius-pill` | 999px (redondeo completo) | Píldoras de estado, segmentos, campos de búsqueda, swatches |

### 4.2 Elevación (sombra)

Tres niveles, siempre tintados hacia el color de tinta del tema (nunca negro puro):

| Nivel | Claro | Oscuro | Uso |
| --- | --- | --- | --- |
| 1 — sutil | `0 1px 2px rgba(15,23,42,.06), 0 4px 12px rgba(15,23,42,.06)` | `0 1px 2px rgba(0,0,0,.3), 0 4px 14px rgba(0,0,0,.24)` | Tarjetas de contenido |
| 2 — media | `0 10px 30px rgba(15,23,42,.15)` | `0 10px 30px rgba(0,0,0,.45)` | Menús desplegables, menú contextual, lista de un combo abierto |
| 3 — alta | `0 20px 50px rgba(15,23,42,.25)` (algunas superficies calibran a `.22`) | `0 20px 50px rgba(15,23,42,.5)` (algunas calibran a `.55`) | Diálogos modales |

### 4.3 Espaciado
No hay una escala numerada rígida — los valores reales usados van de 2px (espacios
mínimos entre ícono y texto) a 20px (padding de página); los más comunes son 6, 8, 10, 12,
14, 16, 18px. Cada componente en la sección 7 trae su padding/gap exacto — úsense esos
valores literales, no se inventó una escala de por sí (ej. "×8") que los cubra a todos.

---

## 5. Movimiento (animaciones)

Cada animación se describe por **qué propiedad cambia, cuánto tarda y con qué curva** —
esto es traducible directo a cualquier motor de animación (CSS, JavaFX `Transition`,
Flutter `AnimationController`, SwiftUI `withAnimation`, etc.), no es código de ningún
framework en particular.

| # | Nombre | Disparador | Propiedad(es) | Duración | Curva |
| --- | --- | --- | --- | --- | --- |
| 1 | Giro de flecha de expandir/colapsar | Clic en un grupo/categoría del árbol | Rotación, 0°→90° | 120ms | ease |
| 2 | Avance de barra de progreso por conexión | Mientras una consulta corre, una barra por base | Ancho de relleno | 120ms por paso | linear |
| 3 | Avance de barra "N de M filas cargadas" | Carga incremental de resultados grandes | Ancho de relleno | 200ms | ease |
| 4 | Giro de spinner circular | Exportando CSV / cargando esquema de una base | Rotación, 360° continuo | 800ms por vuelta | linear, en bucle |
| 5 | Entrada de aviso flotante (toast) | Cualquier confirmación rápida ("Guardado", "CSV exportado") | Opacidad 0→1 + desplazamiento vertical 10px→0 | 180ms | ease |
| 6 | Pulso de "conexión en uso" | Mientras una base tiene una consulta corriendo, el punto de estado de esa fila | Opacidad 1↔0.35 | 600ms por medio ciclo | lineal, bucle indefinido, ida y vuelta |
| 7 | Revelado de acciones de fila al pasar el mouse | Hover sobre una fila de conexión | Opacidad 0→1 de los íconos de editar/eliminar | 120ms | ease |
| 8 | *(recomendado)* Apertura de diálogo | Cualquier diálogo modal | Opacidad 0→1 + escala 0.96→1 | ~180ms | ease-out |
| 9 | *(recomendado)* Transición de tema/acento | Cambiar tema o acento en Preferencias | Color de fondo/texto de toda la interfaz | ~250ms | ease |
| 10 | *(recomendado)* Aparición de resultados | Una ejecución termina y llena la tabla | Opacidad y/o desplazamiento breve | ~200-250ms | ease |

Las tres marcadas *(recomendado)* no están confirmadas contra una implementación viva —
son la intención de diseño documentada del proyecto, no algo ya construido y probado. El
resto sí están verificadas contra el comportamiento real ya construido.

---

## 6. Iconografía

Set de íconos de trazo (no relleno, salvo casos puntuales como el triángulo de "reproducir"
o una palomita de confirmación), estilo Lucide, grosor de trazo 2-2.4px, extremos y
uniones redondeados. Tamaños según contexto: 11-13px en badges/chips, 14-15px en botones
de toolbar, 17px en el riel de navegación lateral. Nunca emoji, nunca íconos con relleno
sólido decorativo — el trazo fino es parte del lenguaje visual "denso pero liviano" de
toda la app.

---

## 7. Componentes

### 7.1 Botón primario
Relleno del color de acento activo, texto legible sobre ese acento (§2.3), radio
`radius-control`, padding vertical ~9px / horizontal ~16px, peso de texto 700. Estados:
hover = acento-hover, presionado = acento-active. Uso: una sola acción principal por
pantalla ("Ejecutar", "Guardar", "Importar").

### 7.2 Botón secundario / fantasma
Transparente, borde 1px `border`, texto `text`, mismo radio y padding que el primario,
peso 600. Hover: fondo `surface-alt`. Uso: el resto de las acciones de una barra de
herramientas.

### 7.3 Botón destructivo
Igual estructura que el secundario, pero texto y — si aplica — ícono en color `error`.
Hover: fondo `error-suave`. Uso exclusivo para "Cancelar ejecución" y confirmaciones de
eliminar.

### 7.4 Campo de texto
Fondo `background` (más oscuro que la tarjeta que lo contiene, para que se note que es un
campo interactivo), borde 1px `border`. Foco: borde pasa a `accent-base`. Radio: **ver
nota de decisión en la sección 11** — el diseño de referencia lo dibuja como píldora
completa (`radius-pill`); confirmar antes de implementar si el equipo prefiere un radio
menor y más convencional.

### 7.5 Control segmentado (2-3 opciones exclusivas)
Una pista (`surface-alt`, radio 10px, padding 3px) que contiene 2-3 botones. El
seleccionado: fondo `surface` + elevación nivel 1 + texto `text`. Los no seleccionados:
transparentes, texto `text-muted`. Uso: alternar entre dos modos (Individual/Masiva,
motor de base de datos, Claro/Oscuro).

### 7.6 Selector de acento (swatch)
Círculo de ~34px con el color del acento en un círculo interior más chico (padding ~3px
entre ambos). Seleccionado: anillo de 2px en `text`. No seleccionado: anillo transparente.
Nota de accesibilidad: el acento monocromo en tema oscuro es casi del mismo color que el
fondo del panel donde vive el selector — agregar un contorno fino y permanente
(`text-muted`, 1px) alrededor de CADA swatch, no solo del seleccionado, para que ese caso
puntual no desaparezca contra el fondo.

### 7.7 Interruptor (toggle)
Pista de ~38×21px, radio completo. Encendido: fondo `accent-base`, círculo interior
(~15px, blanco) pegado al extremo derecho. Apagado: fondo `border`, círculo `surface`
pegado al extremo izquierdo. Animar el desplazamiento del círculo al cambiar de estado
(~150ms, ease) en vez de que salte de golpe.

### 7.8 Insignia / chip / píldora
Fondo del tono *suave* de su color semántico (§2.2) o del acento (§2.3), texto del tono
*texto-sobre-suave* correspondiente — nunca el color base sólido como fondo de un chip de
texto (el color base sólido es solo para puntos de estado y botones). Radio `radius-chip`
para conteos/metadatos, `radius-pill` para todo lo demás (estado, motor, etiquetas).

### 7.9 Punto de estado
Círculo pequeño (7-9px según contexto) de color semántico sólido. Verde = conectado/éxito,
ámbar = advertencia/en curso, rojo = error, gris (`border`/`text-muted`) = sin verificar
todavía. Cuando indica "en uso ahora mismo", aplica la animación #6 (pulso de opacidad).

### 7.10 Menú (desplegable o contextual)
Superficie `surface`, borde 1px `border`, radio `radius-menu`, elevación nivel 2, padding
interno 6px. Cada ítem: padding ~7-8px×10px, radio 7px, hover `surface-alt`. Atajo de
teclado (si lo tiene) alineado a la derecha, en fuente monoespaciada, `text-muted`.
Separador: línea de 1px `border` con margen vertical ~5px. El menú de la barra superior y
el menú contextual (clic derecho) usan exactamente el mismo tratamiento — no dos
lenguajes visuales distintos para el mismo tipo de control.

### 7.11 Diálogo modal
Velo de fondo (`backdrop`, §2.1) cubriendo toda la ventana. Caja centrada: `surface`,
radio `radius-container`, elevación nivel 3, ancho variable según contenido (rango
observado: 460-640px). Estructura fija en 3 bloques:
1. **Título** — heading 600 17px, sin borde inferior, separado del cuerpo por espacio, no
   por línea.
2. **Cuerpo** — con scroll propio si el contenido no entra, padding ~14-18px, gap ~12px
   entre campos.
3. **Pie** — borde superior 1px `border`, botones alineados a la derecha (Cancelar +
   acción principal); si existe una acción secundaria sin relación directa con
   guardar/cancelar (ej. "Probar conexión"), va sola a la izquierda del pie.

### 7.12 Tarjeta de contenido
Superficie `surface`, radio `radius-container`, elevación nivel 1, sin borde visible (la
sombra ya separa la tarjeta del fondo, un borde encima sería redundante). Uso: bloques de
Consulta (editor, resultados), tarjetas de Historial, tarjetas de Favoritos.

### 7.13 Aviso flotante (toast)
Caja de fondo oscuro fijo (no depende del tema — igual que un tooltip) con texto claro
fijo, radio 10px, elevación nivel 2, centrada horizontalmente cerca del borde inferior de
la ventana. Entra con la animación #5, permanece unos segundos, desaparece.

### 7.14 Fila de árbol jerárquico
Usado por el panel de Conexiones (servidor → base de datos → categoría de esquema →
objeto). Indentación progresiva (~18px por nivel). Un nivel "hoja" seleccionable
(individual) resalta con fondo del acento-suave. Casilla de selección (modo masivo):
cuadro pequeño, esquinas ligeramente redondeadas, relleno del acento + palomita blanca
cuando está marcada. Acciones de fila (editar/eliminar) reveladas solo al pasar el mouse
(animación #7) — no ocupan espacio visual permanente en una lista densa.

### 7.15 Banner de aviso no-bloqueante
Franja de ancho completo, fondo *advertencia-suave*, texto *advertencia-texto-sobre-suave*,
tamaño de texto pequeño (~11px), sin necesidad de que el usuario la cierre. Se usa para
avisos que no bloquean el flujo pero sí importa que se noten — por ejemplo, cuando una
tabla de resultados llegó a su tope de filas mostradas en memoria. Va **arriba** del
contenido que describe, nunca superpuesta encima de él.

---

## 8. Pantallas y flujos

### 8.1 Marco general de la ventana
De arriba hacia abajo: barra de título (marca + controles de ventana) → barra de menú
(Archivo/Editar/Consulta/Conexiones/Ver/Herramientas/Ayuda) → barra de herramientas →
cuerpo (riel de navegación + panel lateral + contenido principal) → barra de estado.

### 8.2 Barra de herramientas
De izquierda a derecha: botón primario **Ejecutar** (que se convierte en **Cancelar**,
tratamiento destructivo, mientras una consulta corre — mismo lugar, mismo tamaño, nunca
dos botones separados) → divisor → botones secundarios (Nueva consulta, Abrir, Guardar,
Formatear, Favorito) → divisor → píldora "N de M bases seleccionadas" → chip de timeout →
espacio flexible → selector rápido de acento (opcional) → toggle de tema.

### 8.3 Riel de navegación + panel lateral
Riel angosto (~46px) con 3-4 botones verticales (Conexiones/Historial/Favoritos +
Preferencias al fondo, separado por espacio flexible). El panel lateral (~280-310px)
muestra el contenido del ítem activo del riel, con un encabezado fijo (nombre del panel,
mayúsculas, texto muted) arriba de todo.

**Panel Conexiones:** buscador (campo píldora con ícono de lupa) → fila de filtro
(segmentado Individual/Masiva + botón "+" agregar) → árbol jerárquico (§7.14): grupo
(nombre + conteo) → base de datos (casilla, punto de estado, insignia de motor, nombre,
host) → categorías de esquema (Tablas/Vistas/Funciones/Procedimientos/Triggers, cada una
con su conteo) → objetos individuales.

**Panel Historial:** lista de tarjetas (§7.12), cada una con: hora (énfasis) + píldora de
resultado (éxito/cancelada/bloqueada) en la misma fila; debajo, la consulta previsualizada
en monoespaciada, truncada a una línea; al final, metadatos (filas, servidor, o motivo de
cancelación).

**Panel Favoritos:** mismas tarjetas, con: título (heading), consulta previsualizada
(mono, muted), fila de etiquetas (chip de acento para la categoría principal, chip neutro
para el resto).

### 8.4 Pestañas de consulta
Multi-pestaña, cada una independiente (su propia conexión activa y resultados). Cada
pestaña muestra: ícono de archivo + nombre + un indicador de cambios sin guardar cuando
aplica. Pestaña activa: subrayado de 2px en el acento, fondo ligeramente distinto al del
resto de la barra (para que se sienta "hundida" hacia el contenido de abajo). Botón "+"
al final para una pestaña nueva.

### 8.5 Editor de SQL
Bloque de texto monoespaciado con números de línea, fondo `surface-alt` (no blanco puro),
resaltado de sintaxis: palabras clave en el color del acento y negrita, literales
numéricos en color de advertencia, cadenas en color de éxito, comentarios en `text-muted`
e itálica. Barra de info debajo: posición del cursor, codificación, y qué motor(es)
recibirán la consulta si hay más de uno seleccionado.

### 8.6 Panel de resultados
Tres sub-vistas alternables por pestaña interna (Resultados / Ejecución / Diagnóstico),
cada una con un contador visible en su propia pestaña.

- **Resultados**: fila de píldoras, una por base consultada, con su conteo de filas
  (éxito) o su motivo de error; tabla de datos con encabezado de dos líneas (nombre de
  columna + su tipo de dato, más chico y tenue debajo); si el resultado tiene más de una
  base de origen, una columna adicional identifica de cuál viene cada fila. Cuando el
  resultado excede el tope configurado de filas en memoria, el **banner de aviso** (§7.15)
  aparece arriba de la tabla, con un control para seguir cargando más.
- **Ejecución**: una fila por base con punto de estado, nombre, host, insignia de estado
  (en cola/ejecutando/lista/cancelando/cancelada/error, cada una con su color semántico),
  barra de progreso individual, filas y tiempo transcurrido, y un botón para cancelar esa
  base sola sin afectar a las demás.
- **Diagnóstico**: log de texto corrido con marca de tiempo, nivel (INFO/WARN/ERROR/DEBUG,
  cada uno con su color semántico) y mensaje.

### 8.7 Diálogo "Agregar/editar base de datos"
Segmentado de motor arriba de todo. Grid de dos columnas para los campos de conexión
(nombre visible, base de datos, host, puerto, usuario, contraseña). Selector de modo
(Solo lectura / Sin restricciones) como dos opciones tipo radio en píldora, no un
segmentado — la opción activa lleva borde y fondo del acento-suave con un punto relleno;
la inactiva, solo un círculo vacío. Grid de dos columnas para configuración de pool y
timeout. Banner de confirmación (§7.15, tono éxito en vez de advertencia) al probar la
conexión con éxito.

### 8.8 Diálogo "Importar CSV"
Zona de arrastrar-y-soltar (borde punteado, texto centrado con un enlace de color de
acento como alternativa a arrastrar). Grid de dos columnas (separador, tamaño de lote).
Casilla "La primera fila contiene encabezados".

### 8.9 Diálogo "Preferencias"
Panel lateral fijo con 2-3 pestañas verticales (Apariencia / Rendimiento / Atajos), cada
una con el mismo tratamiento de "seleccionada" que un segmento (§7.5).
- **Apariencia**: segmentado Claro/Oscuro, selector de acento (§7.6), control deslizante
  de tamaño de fuente del editor.
- **Rendimiento**: filas etiqueta+campo para valores numéricos (timeouts, conexiones
  máximas, tamaño de bloque de lectura, tope de filas mostradas en memoria) y filas
  etiqueta+interruptor (§7.7) para opciones booleanas (cancelar también en el servidor,
  exportar en streaming, liberar resultados al cerrar una pestaña). Cada opción menos
  obvia lleva una línea de texto explicativo debajo, en `text-muted`.
- **Atajos**: lista de solo lectura, chip de atajo (mono, fondo `surface-alt`) + su
  descripción.

### 8.10 Menú contextual
Mismo tratamiento que un menú desplegable (§7.10), posicionado en el punto del clic
derecho. Contenido típico sobre un objeto de esquema: generar SELECT/INSERT/UPDATE/DELETE,
generar script CREATE, comparar en las bases marcadas, importar CSV a esa tabla.

---

## 9. Estados de interacción y accesibilidad

- **Foco de teclado**: nunca el anillo azul por defecto del sistema/framework — contorno
  de 2px en el acento activo, separado 2px del borde del control.
- **Hover**: cada control interactivo tiene su propio tinte de hover (normalmente
  `surface-alt` de fondo, o el tono *hover* del acento para elementos que ya llevan
  relleno de acento) — nunca depender del cursor del sistema como única señal.
  Transiciones de hover: instantáneas o casi (no necesitan animación dedicada), salvo
  donde la sección 5 dice explícitamente lo contrario (revelado de acciones de fila).
- **Seleccionado**: fondo del tono acento-suave (filas, ítems de lista), o subrayado de
  2px en el acento (pestañas), o anillo de 2px (swatches) — nunca solo un cambio de peso
  de texto, que es insuficiente para comunicar selección por sí solo.
- **Deshabilitado**: se comunica con **color atenuado** (texto/ícono a `text-muted` o más
  tenue), no con opacidad reducida de todo el nodo — opacidades que se multiplican entre
  un contenedor y su contenido interno terminan viéndose más tenues de lo previsto y
  rompen la consistencia visual entre controles deshabilitados vecinos.
- **Contraste**: el acento-sobre-fondo-neutro está calibrado para un mínimo de 3:1 —
  suficiente para íconos, texto grande y elementos de interfaz, insuficiente para texto de
  párrafo del tamaño de cuerpo. Para texto de tamaño normal en el color del acento, usar
  el tono *texto-sobre-suave* de ese acento (§2.3), no el acento base directo.

---

## 10. Assets

Sin imágenes externas — toda la interfaz se construye con color plano, tipografía y
trazo de ícono. Dos fuentes de Google Fonts (Sora, Manrope) más una fuente monoespaciada
de código abierto (JetBrains Mono), empaquetadas como archivo local en vez de cargadas en
vivo — la app es de escritorio y no debería depender de conexión a internet para
renderizar su propia tipografía. Set de íconos: Lucide (o equivalente de trazo con
licencia compatible), grosor 2-2.4px.

---

## 11. Decisiones de diseño — ya tomadas vs. pendientes

**Ya tomadas** (documentadas acá para que ningún equipo las "corrija" de vuelta sin
saber que fueron un pedido explícito, verificado con captura, contra una implementación
real ya en uso):

1. El tamaño de la casilla de selección en el árbol de conexiones es más grande que lo que
   dibuja el prototipo original — un usuario real la encontró "muy chica" en la primera
   versión construida. Mantener el tamaño más grande.
2. El modo de una conexión (solo lectura / sin restricciones) se comunica con un ícono de
   candado con color semántico (cerrado y neutro = solo lectura; abierto y en color de
   éxito = sin restricciones), no con un badge de texto como en la versión más temprana del
   diseño — la versión con ícono se probó más legible de un vistazo. Mantenerla.
3. El indicador de "cambios sin guardar" en una pestaña de consulta, en la implementación
   de referencia actual, es un carácter (`●`) al **inicio** del nombre de la pestaña, del
   mismo color que el resto del texto de la pestaña — más simple que un punto separado y
   coloreado en advertencia al final del nombre. Cualquier equipo puede optar por la
   versión más elaborada (punto propio, en color de advertencia, después del nombre) sin
   que eso sea "corregir un error" — es una decisión de pulido visual pendiente, no un
   bug.

**Pendientes** (el diseño de referencia y la implementación más reciente no coinciden, y
ninguna de las dos fuentes dice explícitamente por qué — hay que decidir, no adivinar):

1. **Campos de texto — píldora vs. rectangular.** El diseño de referencia dibuja todos los
   inputs como píldora de radio completo; la implementación más reciente los tiene
   rectangulares (radio bajo, ~8px). No hay ninguna nota que indique que fue un rechazo
   deliberado del estilo píldora — probablemente nunca se llegó a implementar. Es un
   cambio que afecta a todos los diálogos a la vez, así que conviene decidirlo una sola
   vez y no diálogo por diálogo.
2. Los botones de acción en la tarjeta de un favorito ("usar esta consulta", eliminar) que
   describía una versión más antigua del diseño no aparecen en la versión más reciente del
   prototipo interactivo — confirmar si la tarjeta completa hace de botón "usar" (clic en
   cualquier parte) o si los botones explícitos siguen siendo parte del diseño.
3. Las tres animaciones marcadas *(recomendado)* en la sección 5 (apertura de diálogo,
   transición de tema, aparición de resultados) no están confirmadas contra ninguna
   implementación viva — son intención documentada, no comportamiento ya construido y
   aprobado. Tratarlas como mejora opcional de pulido, no como requisito de un primer
   corte.

---

## 12. Anexo — solo para quien continúe sobre la base de código Java existente

*(Esta sección es release-specific de `java_faroapp`, el cliente JavaFX que ya existe para
este proyecto. Un equipo que reimplemente Faro en otro lenguaje/framework puede saltarla
por completo — nada de lo de arriba depende de ella.)*

Mapa rápido de dónde vive cada cosa en esa base de código, para no reconstruir lo que ya
existe y probado:

| Del documento | Ya existe en `java_faroapp` como… |
| --- | --- |
| Tokens de color (§2.1-2.2) | `theme-light.css` / `theme-dark.css`, variables `-token-*` sobre `.root` |
| Los 7 acentos + su texto-sobre-acento + excepción del editor (§2.3) | `AccentPalette.java` (`Tokens` record, mapas `LIGHT`/`DARK`) |
| Identidad de motor (§2.4) | **No existe todavía** — es la propuesta nueva de este documento |
| Reglas estructurales de toda la interfaz (radios, sombras, tipografía) | `app.css`, hoja única desde la migración a variables |
| Pulso de "en uso" (animación #6) | `ConnectionTreeCell#inUsePulse`, `FadeTransition` — **ya coincide exacto** con la especificación de la sección 5, no tocar |
| Spinner circular (animación #4) | Ya existe para exportación (`.export-spinner` + `RotateTransition`) y para carga de esquema (`ProgressIndicator` real) |
| Revelado de acciones de fila (animación #7) | No implementado — ver `rediseno-visual-handoff.md` §10.5 para el CSS exacto y la limitación real de JavaFX con transiciones declarativas |
| Giro de flecha de expandir/colapsar (animación #1) | La flecha ya cambia de forma en `ConnectionTreeCell`, pero no gira — falta agregar la rotación |
| Interruptor / toggle (§7.7) | No existe como componente propio — confirmar si Preferencias → Rendimiento usa hoy un control nativo tipo casilla en su lugar, antes de construir uno nuevo |
| Control segmentado (§7.5) | No existe como clase de estilo propia — revisar cómo están hechos hoy los que ya funcionan (motor, Individual/Masiva, tema) |
| Banner de aviso no-bloqueante (§7.15) | **Ya existe**, agregado recientemente: `.results-truncated-banner`, mostrado sobre la tabla de resultados cuando se alcanza el tope de filas configurado en Preferencias |
| Campo "tope de filas mostradas" (§8.9, Rendimiento) | **Ya existe**, agregado recientemente en el diálogo de Preferencias |
| Campo de texto píldora vs. rectangular (§11, pendiente 1) | Hoy rectangular (`.text-field`/`.combo-box`, radio 8px) |

Para el detalle línea-por-línea de implementación de la pantalla Consulta específicamente
(selectores CSS exactos a agregar, en qué método de qué clase Java, con estimación de
esfuerzo por cambio), ver `rediseno-visual-handoff.md` en esta misma carpeta — ese
documento no se duplicó acá para no arriesgar que las dos copias se desincronicen.
