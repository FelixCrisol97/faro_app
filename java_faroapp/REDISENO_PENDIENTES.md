# Rediseño visual — lo implementado y lo que queda pendiente de tu decisión

Contraste entre `Migración_Flutter_Java/entrega/rediseno-visual-handoff.md` y el código
real de `java_faroapp`, hecho el 2026-09-20.

**El criterio fue explícito:** meter el diseño **sin modificar la funcionalidad que ya
existe**. Todo lo que cambiaría comportamiento, quitaría algo, o chocaría con una
decisión ya tomada, **no se implementó** — está acá abajo para que lo decidas tú.

---

## Ya implementado (solo estilo, cero funcionalidad tocada)

| # | Qué | Dónde |
|---|---|---|
| §10.1 | Tokens de identidad de motor (`-token-engine-pg-*` / `-token-engine-mssql-*`) | `theme-light.css`, `theme-dark.css` |
| §10.2 | La insignia de motor pasa de gris para los dos a color por motor | `app.css`, `ConnectionTreeCell.java` |
| §10.6 | Tarjetas con sombra para Consulta y Resultados | `main-view.fxml`, `app.css` |
| §4 | Halo (aro) en el punto de estado de conexión | `app.css` |

Tres matices de fidelidad, deliberados y documentados en el código:

- **El texto de la insignia NO cambió.** El handoff dibuja `"Pg"`/`"MS"` en un cuadrito
  fijo de 22×22; `DbEngine.badge()` devuelve `"PG"`/`"MSSQL"`. Cambiarlo sería producto,
  no estilo — el propio documento lo advierte en §10.2.
- **El aro del punto usa 2px, no 3.** En el mockup el punto mide 8px y acá mide 10 (se
  subió a mano el 2026-08-28 porque "se veía muy chico"). Con 3px el punto entero
  pasaría de 10 a 16px y empujaría una fila ya calibrada.
- **La sombra es una, no dos.** `--shadow-sm` son dos sombras apiladas; `-fx-effect`
  admite un solo efecto por regla y apilarlas exigiría anidar `DropShadow` en Java. Se
  aproximó con una sola del mismo peso visual, como recomienda el propio §10.6.

---

## 1. Choca con una regla del proyecto

### 1.1 Ocultar "editar/eliminar" hasta pasar el mouse (§10.5, §5.4, §6)

El handoff pide que los íconos de lápiz y bote de basura estén en `opacity:0` y solo
aparezcan al pasar el mouse sobre la fila.

**Contradice el punto 5 de los "Puntos obligatorios"** de `CONTEXTO_SESIONES.md`:

> *"Ninguna acción de la interfaz puede depender de un gesto escondido (doble clic,
> hover, sin ninguna pista visual) — todo control necesita ser visible."*

Y el README documenta la decisión contraria como una característica: *"e ícono de
editar siempre visible"*.

**No se implementó.** Es la decisión más clara de todo este contraste: la regla es tuya
y es explícita. Si quieres el efecto del mockup, hay que cambiar la regla primero — no
al revés.

---

## 2. Revertiría decisiones que ya tomaste

### 2.1 Los colores del tema oscuro (§1.1)

El handoff especifica `--background: #0F172A`, `--surface: #1E293B` — la familia
"slate", con tinte azul. El código tiene `#09090B` / `#18181B` — la familia "zinc", gris
neutro.

Eso no es una divergencia accidental: fue **un pedido tuyo explícito** el 2026-08-28,
registrado en el código y en la bitácora — *"quisiera que fuera más oscuro, tiene un
color medio azul oscuro"*. Aplicar el handoff **desharía ese cambio**.

El tema **claro** sí coincide exacto con el handoff (`#F8FAFC` / `#FFFFFF` / `#F1F5F9`),
así que no hay nada que hacer ahí.

### 2.2 "Texto sobre el acento: `#FFFFFF` fijo" (§1.2)

Rompería el acento **"negro"**. Ese acento es blanco en tema oscuro, así que con el
texto fijo en blanco el botón "Ejecutar" quedaría **blanco sobre blanco** — exactamente
el bug que el token `-token-accent-on` existe para evitar (arreglado el 2026-09-10).

El propio handoff lo reconoce: *"si el equipo lo mantiene como séptima opción, seguir
usando ese token en vez de `#FFFFFF` fijo"*. **Se mantuvo el token.**

### 2.3 El handoff contempla 6 acentos; la app tiene 7 (§1.2)

El séptimo es "negro", agregado a pedido tuyo. Seguir el documento al pie de la letra
lo quitaría. **No se tocó.**

---

## 3. Funcionalidad NUEVA que el handoff introduce

Nada de esto existe hoy. Todo es trabajo de producto, no de estilo:

| # | Qué pide el handoff | Qué hay hoy |
|---|---|---|
| §5.1 | Barra de título propia de 34px, con marca "Faro" y botones de minimizar/maximizar/cerrar | La app usa la decoración de ventana del sistema operativo. Implementarlo exige `StageStyle.UNDECORATED` y reimplementar arrastre, maximizar y cerrar a mano |
| §5.3 | Selector de acento (6 círculos) en la barra de herramientas | Los acentos se eligen en Preferencias → Apariencia. El propio §10.7 lo marca opcional |
| §5.5 | Barra de info bajo el editor: "Ln 3, Col 18" | No existe. Hay que calcular y mantener la posición del cursor |
| §5.6 | Píldoras de resultado por base dentro de la pestaña Resultados | Esa información vive hoy en la pestaña **Ejecución**, con estado en vivo por base. Duplicarla o moverla es decisión de producto |
| §5.4 | Link "+ Agregar conexión" al pie del panel izquierdo | No existe. Agregar bases se hace con el "+" de la fila del buscador |

---

## 4. Lo que el handoff QUITARÍA si se siguiera al pie de la letra

### 4.1 La tabla de resultados con 4 columnas fijas (§5.6)

El handoff especifica una grilla de 4 columnas con proporciones fijas
(`origen_bd / sku / nombre / existencia`, `1.3fr 1fr 1.6fr 0.8fr`).

**La app no puede tener columnas fijas**: son dinámicas, no se conocen hasta que la
consulta corre (`ResultsTableFactory` las arma con `getColumns().setAll(...)` en cada
resultado). Ese es el producto, no un detalle de estilo.

Lo que sí se puede tomar de ahí y **se tomó**: el encabezado con fondo `--surface-alt`,
el hover de fila, el padding de celda, y la ausencia de cebra — que ya coincidía.

### 4.2 Panel izquierdo de 288px fijo (§5.4)

Hoy es un `SplitPane` redimensionable con `minWidth=220`: puedes arrastrar el divisor.
Fijarlo en 288px **quita esa capacidad**.

---

## 5. Decisiones abiertas que el propio handoff deja al equipo

| # | Decisión | Qué se hizo |
|---|---|---|
| §10.3 | ¿La insignia de motor va antes del nombre (mockup) o después (código actual)? | **Se dejó donde está.** El handoff dice que decide el equipo, y moverla arriesga el alineado de la fila, calibrado a mano en varias rondas |
| §10.5 | El fundido de 120ms al revelar íconos | Irrelevante mientras no se adopte el punto 1.1. JavaFX no tiene `transition` en CSS: exigiría un `FadeTransition` en Java |
| §10.6 | La sombra doble exacta | Aproximada con una sola, como recomienda el documento |

---

## 6. Alcance: el handoff cubre UNA pantalla

El propio §9 lo dice: el mockup muestra solo la pantalla "Consulta" en un estado feliz.
**No cubre** el estado vacío, el estado "ejecutando", ninguno de los 5 diálogos
(agregar/editar base, credenciales, descubrir, importar CSV, preferencias), ni el
detalle de Ejecución/Diagnóstico.

Así que "implementar el diseño en toda la app" **no se puede hacer solo con este
documento**: para el resto haría falta otro handoff con el mismo nivel de detalle, o
decidir caso por caso extendiendo los tokens y las reglas que sí quedaron definidas
acá (que es lo que se hizo con las tarjetas y el halo, aplicables más allá de la
pantalla de Consulta).
