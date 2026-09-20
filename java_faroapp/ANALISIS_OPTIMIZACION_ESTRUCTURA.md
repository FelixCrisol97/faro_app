# Análisis de optimización, estructura y buenas prácticas

Revisión completa de `java_faroapp` — todo `src/main/java` (51 clases, ~8,400
líneas), los recursos (`app.css`, los 6 FXML) y `pom.xml`, con foco en
rendimiento, refactorización, estructura y *code smells*, pensando en el
proyecto como algo que va a seguir creciendo durante años.

**Fecha:** 2026-09-08.
**Estado del build al empezar:** `mvn compile` limpio, `mvn test` **89/89 en
verde** (el README todavía dice 83 — desactualizado, ver §C11).

**Qué NO repite este documento.** Los dos pases anteriores ya están cerrados y
no se vuelven a tocar acá:

- `OPTIMIZACION_RENDIMIENTO.md` (2026-09-03) — la doble copia del resultado
  entre `QueryExecutionService` y `ResultsTableFactory`. Verificado: el arreglo
  sigue en su lugar y es correcto.
- `AUDITORIA_BUGS_RENDIMIENTO.md` (2026-09-05, cerrado el 09-07) — los 12
  hallazgos. Verificado uno por uno contra el código actual: **los 12 siguen
  arreglados**. Dos de ellos (#7 `CredentialStore`, #10 el pulso en celdas
  vacías) resultaron estar arreglados **a medias** — el diagnóstico era
  correcto pero el arreglo no cubrió todo el caso; eso sí entra acá, en A2.

Todo lo demás de este documento es nuevo.

---

## Estado de implementación

**2026-09-09 — primer bloque implementado y verificado.** `mvn test`
**96/96 en verde** (los 89 de partida + 7 nuevos que fijan el contrato de lo que
se tocó). Lo cerrado:

| # | Qué se hizo | Archivos |
|---|---|---|
| **A1** | Listener de estado guardado como campo y quitado en el mismo bloque que ya desataba las otras 7 propiedades | `ExecutionTableFactory` |
| **A2** | `detachRowState()`/`detachStatusListeners()` — llamado por las 6 ramas de `updateItem` que no son fila de base. Cierra de verdad el #10 y elimina las 5 líneas repetidas 6 veces de C4.1 | `ConnectionTreeCell` |
| **A4** | `SchemaIntrospector.invalidateAll()` (análogo de `pool.closeAll()`) + `invalidate(id)` al eliminar una base | `SchemaIntrospector`, `MainController` |
| **A5** | `schemaErrorTooltip` creado e instalado una vez; `updateItem` solo hace `setText` | `ConnectionTreeCell` |
| **B2.1** | `matchesAnyName` sale en la primera coincidencia; `containsIgnoreCase` compara sin copiar | `SchemaTreeNode` |
| **C5 (parcial)** | Imports cualificados en línea normalizados en los 2 archivos que se tocaron | `SchemaIntrospector`, `SchemaTreeNode` |

Tests nuevos: 5 en `SchemaTreeNodeTest` (semántica de `matchesAnyName`
preservada — sobre todo el caso de filtro vacío con todas las categorías
vacías, que es el que más fácil se rompía al reescribir — y `containsIgnoreCase`),
2 en `SchemaIntrospectorTest` (`invalidateAll` sube la generación de toda base
conocida, y no registra bases desconocidas).

**Lo que queda pendiente de verificar en vivo:** A1, A2 y A5 son de
comportamiento de UI — compilan y no rompen ningún test, pero confirmarlos
requiere usar la app. Lo concreto a mirar:

1. **A1** — correr contra 6+ bodegas y hacer scroll en la pestaña Ejecución
   mientras unas terminan y otras siguen: ningún badge debe mostrar el estado de
   una base distinta de la de su fila.
2. **A2** — con una consulta larga corriendo contra una base, expandir su
   esquema y hacer scroll hasta que esa fila salga de pantalla: el pulso no debe
   quedar animando en ninguna otra fila.
3. **A4** — eliminar una base y volver a agregarla con el mismo host: su árbol
   de esquema debe pedirse de cero, no aparecer instantáneo con lo de antes.

**2026-09-10 — segundo bloque: todas las optimizaciones de §B salvo dos.**
`mvn test` **117/117 en verde** (96 + 11 de `QueryExecutionServiceTest` + 10 de
los pases anteriores), con recompilación desde cero. Lo cerrado:

| # | Qué se hizo |
|---|---|
| **B1** | El resaltado de sintaxis salió del hilo de la UI — se calcula en `faro-sql-highlight` y se aplica de vuelta con un contador de generación que descarta los resultados que llegan tarde |
| **A6** | Una sola lista de palabras clave: el regex del editor se arma desde `SqlFormatter.KEYWORDS`. De paso el editor resalta comentarios de bloque `/* */`, que no reconocía |
| **A3** | Escritura atómica (temporal + `ATOMIC_MOVE`) en `connections.json` **y** `credentials.dat`, más espera acotada del autoguardado en `shutdown()` |
| **B10** | El JSON se escribe en streaming con Gson, sin materializarlo entero (con `disableHtmlEscaping` para no cambiar los bytes que producía `toString()`) |
| **B4** | El script se parte y se valida una vez por corrida, no una por base — con un `RunPlan` que de paso baja `runOne` de 10 parámetros a 9 |
| **B3** | El grid lee el valor desde el `cellFactory`: cero `SimpleObjectProperty` por celda y por repintado. Columnas con `setAll` en vez de N `add` |
| **B6 + A10** | `appendCsvEscaped` escribe sobre el buffer sin asignar nada en el caso común; encabezados escapados y `\r` cubierto |
| **B7 + A7** | El pool se construye fuera de `computeIfAbsent`; `evict`/`closeAll` cierran en segundo plano, con `closeAllAndWait()` aparte para el cierre de la app |
| **B2** | Completado: cortocircuito (B2.1), recorrido único vía `bindSelectionDependentUi` (B2.2) y debounce de 200 ms en el buscador (B2.3) |
| **B5** | `setTabBadge` reusa sus nodos; solo cambia el texto del contador |
| **B8** | `updateExecutionSummary` cuenta en una pasada, no tres |
| **B9** | Autocompletado sin `toUpperCase` por nombre, con tope de 50 sugerencias y aviso de cuántas quedaron fuera |
| **B12** | Quitadas las dos capas redundantes de `Platform.runLater` |
| **A11 / A12** | Red de seguridad de `inUse` en `setOnFailed`; `Main.stop()` tolera un arranque fallido |
| **C6** | Borrados `styles.css` y `styles-dark.css` (51 KB muertos en el JAR), `legacyStylesheetResourcePath()`, el constructor de 3 args de `DatabaseTreeItem` y la rama inalcanzable de `SqlEditorFactory` |
| **C8 (parcial)** | 11 tests para `isReadOnlyStatement`/`enrichErrorMessage` — incluido uno que **documenta** el agujero del CTE que escribe |

**Los dos de §B que quedan abiertos, y por qué:** *(cerrados como decisión el
2026-09-14, ver más abajo)*

- **B11** (un viaje de red extra por base para leer el pid/spid) — es una
  consulta de microsegundos del lado del servidor; cachearla por conexión física
  es más complejidad que la que justifica hoy.
- **B13** (`minimumIdle` sin fijar) — sigue siendo una decisión de producto, no
  un arreglo: menos conexiones ociosas contra los servidores a cambio de que el
  primer `Ejecutar` de cada base sea más lento.

**A9 (alto de fila del árbol) se dejó abierto a propósito.** El arreglo es
mecánico —la misma fórmula que ya usa el grid— pero toca el alto de una fila
cuyo aspecto el usuario ya ajustó a mano tres veces (28→36→44 px), y solo se
manifiesta en el extremo del slider. Cambiarlo sin poder verlo es más riesgo que
el bug latente que cierra. *(Cerrado el 2026-09-14 calibrando la fórmula para
que el tamaño por defecto siga dando 44 px exactos — medido, no estimado.)*

**2026-09-11 — lo que movieron los pedidos de uso real.** Varias funciones nuevas
(encabezado de pestaña de 2 líneas, acento negro, acciones de grupo, herencia de
grupo al descubrir) cerraron de paso hallazgos de §C. `mvn test` **131/131**.

| # | Qué se movió |
|---|---|
| **C4.3** | `bindSelectedCount` y `bindSelectAllButtonText` eran métodos gemelos con un recorrido del árbol cada uno; unificados en `bindSelectionDependentUi`, que además colgó ahí el tercer consumidor (la segunda línea de la pestaña) sin sumar un recorrido más |
| **C4 / C1 (parcial)** | `ConnectionTreeCell` iba a quedar con **14 parámetros posicionales**, varios del mismo tipo — `onEdit` y `onDelete` son los dos `Consumer<DatabaseEntry>`, así que intercambiarlos compilaba y hacía que el lápiz borrara la base. Agrupados en `ConnectionTreeActions` |
| **C7 (parcial)** | Corregidos los comentarios desactualizados de `SqlAutocomplete` (el orden de inserción que el `sort` posterior descartaba) y de `ConnectionTreeBuilder` |

**Dos cosas que este documento no había encontrado y salieron del uso real** —
las dos ya están cerradas (2026-09-14), pero conviene tenerlas presentes al leer
el resto porque ninguna estaba numerada:

- **`fetchSize` no hacía nada en PostgreSQL.** Confirmado contra la documentación
  oficial de pgJDBC: el cursor exige `autoCommit = false`, y
  `QueryExecutionService` nunca lo desactivaba. El driver materializaba el
  resultado COMPLETO antes de retornar, así que al terminar el bucle el resultado
  vivía dos veces. Era la causa de memoria que quedaba sin tocar tras haber
  quitado las otras dos copias, y **superaba en impacto a cualquier hallazgo de
  §B**. **Corregido:** el autocommit se desactiva **solo** si el motor es
  PostgreSQL **y** todas las sentencias son de solo lectura
  (`QueryExecutionService.shouldUseCursor`), que es la condición bajo la cual no
  hay nada que confirmar — así el cursor se activa sin cambiarle la semántica
  transaccional a ningún script que escriba. Ver A13 más abajo.
- **Una clase de estilo usada en el FXML sin regla que la respalde no la atrapaba
  nada.** Pasó dos veces (`.discover-results-scroll` sin fondo; antes
  `.trust-cert-check`). **Corregido** con `StyleClassCoverageTest` (§C9), que
  cruza cada clase usada en Java y FXML contra los selectores de `app.css`.
  Verificado quitando del CSS esas dos reglas exactas: el test falla y nombra la
  clase y el archivo.

**2026-09-14 — cierre de los pendientes.** `mvn test` **158/158 en verde**, con
`target/classes` borrado antes de correr (el compilado incremental ya escondió
errores reales dos veces en este proyecto) y **cero advertencias** del compilador
con `-Xlint:all` puesto. Lo cerrado en esta ronda:

| # | Qué se hizo |
|---|---|
| **A13** | El cursor de PostgreSQL: `autoCommit = false` **solo** cuando el motor es PostgreSQL y todas las sentencias son de solo lectura, que es cuando no hay nada que confirmar. Un script con escrituras sigue confirmando sentencia por sentencia, igual que antes. `setAutoCommit(true)` se restaura a mano antes de devolver la conexión al pool, y en ese orden: cambiar `autoCommit` con una transacción abierta la confirma implícitamente |
| **A8** | `CsvParser` intenta UTF-8 y, si truena con `MalformedInputException`, relee con la codificación real del sistema operativo (`native.encoding`) y **reporta cuál usó**, para que el diálogo lo diga en vez de dejarlo invisible |
| **A9** | `rowHeight(fontScaleDelta)` en `ConnectionTreeCell`, mismo patrón que el grid, calibrado para dar exactamente 44 px en el tamaño por defecto |
| **C7** | Los 2 comentarios que faltaban: el de `updateDatabaseRow` (decía "mutaciones condicionales" sobre `setContent`/`setText` incondicionales) y el de `AppPreferences.fetchSize`, que quedó obsoleto con A13 |
| **C8** | `MainControllerLogicTest` — 13 tests de `appendCsvEscaped`, `indexOfIgnoreCase` y `summarize`. Los tres eran `private static` y no tocaban un solo nodo: se podían testear desde siempre |
| **C9** | `-Xlint:all` en el compilador **y** `StyleClassCoverageTest` |
| **C10** | El requisito de JDK 25 escrito en el README — `release=25` hacía fallar un JDK 21 sin que nada lo dijera |
| **B11 / B13** | Descartados con su razón escrita, no olvidados |

**Sobre `-Xlint:all`:** sacó 9 advertencias de "documentation comment is not
attached to any declaration" — todas javadocs huérfanos, y **varios de ellos
desplazados por mis propias inserciones** de rondas anteriores (el de
`ROW_HEIGHT`, que A9 dejó colgando; el de `shutdown()`, empujado al insertar
`awaitAutosave()`; el de `selectedDatabaseIds`, que terminó encima de la clase en
vez del campo; y los dos componentes de `AccentPalette.Tokens`, que un record no
puede documentar por separado). Las 9 corregidas. Se excluyen `-serial` (ninguna
excepción del proyecto se serializa) y `-this-escape` (los constructores de
JavaFX que se subclasean lo disparan por diseño).

**Sobre `StyleClassCoverageTest`:** cruza cada clase de estilo usada en Java
(`getStyleClass().*("…")`) y en FXML (`styleClass="…"` y los bloques
`<String fx:value="…"/>`) contra los selectores de `app.css`. **Verificado de
verdad:** quitando del CSS las reglas de `.trust-cert-check` y
`.discover-results-scroll` —los dos bugs que solo se vieron en tema oscuro y solo
cuando el usuario los reportó— el test falla y nombra la clase y el archivo. En
su primera corrida encontró además un tercer caso, `.exec-cell-root`, que resultó
ser un gancho a propósito; está declarado como excepción, con un test aparte que
falla si la excepción deja de hacer falta.

El resto del documento queda como diagnóstico; los hallazgos sin marca de
implementado siguen abiertos.

---

## Resumen — 39 hallazgos

Contados contra las tablas de abajo, no de memoria: **15 de §A + 13 de §B + 11 de
§C**. De los 39, **32 corregidos** (C1 y A15 se sumaron el 2026-09-19, en la rama
`refactor/dividir-main-controller`); C10 documentado; B13 cerrado como decisión
consciente; B11 descartado; C11 sin acción (era un error de este mismo documento);
y **3 abiertos** — C2 y C3 a propósito (ver el final del documento) y A14, que
apareció al revisar la documentación el 2026-09-15. §D se cuenta aparte, son otros
7.

### Correctitud (§A)

| # | Sev. | Qué | Dónde | Estado |
|---|---|---|---|---|
| A1 | **Alta** | Se agrega un listener a `stateProperty()` en **cada** `updateItem` y nunca se quita — la celda repinta el estado de la base equivocada | `ExecutionTableFactory:159` | **Corregido** |
| A2 | **Alta** | Los listeners de `connectionStatus`/`inUse` nunca se desenganchan cuando la celda deja de mostrar esa base — cierra de verdad el #10 | `ConnectionTreeCell:762-770` | **Corregido** |
| A3 | **Alta** | `connections.json` se escribe sin atomicidad, y el autoguardado en segundo plano puede escribirlo a la vez que `shutdown()` — pérdida silenciosa de TODA la configuración | `ConnectionRegistryStore:132`, `MainController:2573,2602` | **Corregido** |
| A4 | Media-alta | Los cachés de esquema no se invalidan al eliminar una base ni al importar configuración — mismo bug que el #3 de pools, en otra capa | `MainController:2112,2039` | **Corregido** |
| A5 | Media | `Tooltip` nuevo creado e instalado en cada repintado de una fila de error | `ConnectionTreeCell:706` | **Corregido** |
| A6 | Media | 26 palabras clave se autocompletan pero nunca se resaltan: dos listas duplicadas y desincronizadas | `SqlEditorFactory:29` vs `SqlFormatter:43` | **Corregido** |
| A7 | Media | `closeAll()`/`evict()` cierran pools de HikariCP en el hilo de la UI | `MainController:2039,2083,2114` | **Corregido** |
| A8 | Media | Importar un CSV de Excel en Windows truena con `MalformedInputException` | `CsvParser:26` | **Corregido** — detección automática, y el diálogo dice con qué codificación lo leyó |
| A9 | Baja | La fila del árbol está fija en 44px y no escala con el tamaño de fuente — mismo bug ya arreglado en el grid | `main-view.fxml:153` | **Corregido** — `rowHeight(delta)`, calibrado para dar 44 exactos en el tamaño por defecto |
| A10 | Baja | Encabezados del CSV exportado sin escapar; `csvEscape` no cubre `\r` | `MainController:1474,1537` | **Corregido** |
| A11 | Baja | `inUse` puede quedar prendido para siempre si el `Task` muere antes de que las bases reporten | `MainController:1673` | **Corregido** |
| A12 | Baja | `Main.stop()` truena con NPE si `start()` falló | `Main:78` | **Corregido** |
| A13 | **Alta** | `fetchSize` no tiene efecto en PostgreSQL: sin `autoCommit = false` el driver materializa el resultado completo — salió del uso real, no de este análisis | `QueryExecutionService:runOne` | **Corregido** — cursor solo en scripts de solo lectura |
| A14 | Baja | El CSV **exportado** va en UTF-8 **sin BOM**, así que Excel en español lo abre mostrando `Ã±` en vez de `ñ` — es el otro lado de A8, y estaba escrito solo en prosa dentro de ese hallazgo, sin figurar en ninguna tabla | `MainController:1390` (era :1832 antes de dividir la clase el 2026-09-19) | **Abierto** — decisión de producto, ver abajo |
| A15 | Media | El candado del autoguardado quedaba trabado **para siempre** si la captura de pestañas lanzaba antes de arrancar el hilo de fondo: la app dejaba de autoguardar el resto de la sesión avisándolo solo en `DEBUG`. Preexistente (idéntico en `62bbe09`); encontrado al revisar el código del refactor de C1 | `SessionPersistence:autosave` | **Corregido** — `try/finally` y un test con sonda |

### Rendimiento (§B)

| # | Impacto | Qué | Dónde | Estado |
|---|---|---|---|---|
| B1 | **Alto** | El resaltado de sintaxis recorre el documento COMPLETO en el hilo de la UI cada 150 ms de tecleo | `SqlEditorFactory:55` | **Corregido** |
| B2 | **Alto** | Cada tecla del buscador reconstruye el árbol y lo recorre 4 veces, más un mapa filtrado completo por base | `MainController:2415`, `SchemaTreeNode:114` | **Corregido** (cortocircuito + recorrido único + debounce) |
| B3 | Medio | `new SimpleObjectProperty` por celda visible en cada repintado + N eventos al armar las columnas | `ResultsTableFactory:106` | **Corregido** |
| B4 | Medio | El script se parte en sentencias una vez POR BASE en vez de una vez por corrida | `QueryExecutionService:247` | **Corregido** |
| B5 | Medio | `setTabBadge` reconstruye 3 nodos en cada línea de log y cada cambio de estado | `MainController:1793` | **Corregido** |
| B6 | Medio | Una cadena temporal por celda al exportar CSV (30M para 3M filas × 10 columnas) | `MainController:1496` | **Corregido** |
| B7 | Medio | El pool de HikariCP se construye (con viaje de red) dentro de `computeIfAbsent` | `ConnectionPoolManager:38` | **Corregido** |
| B8 | Bajo | `updateExecutionSummary` hace 3 pasadas por cada cambio de estado de cada base | `MainController:1768` | **Corregido** |
| B9 | Bajo | Autocompletado: `toUpperCase` por nombre y sin tope de sugerencias | `SqlAutocomplete:106` | **Corregido** |
| B10 | Bajo | El JSON completo se materializa como una sola cadena antes de escribirlo | `ConnectionRegistryStore:132` | **Corregido** |
| B11 | Bajo | Un viaje de red extra por base por corrida para leer el pid/spid | `QueryExecutionService:421` | **Descartado** — microsegundos del lado del servidor; cachearlo por conexión física no se paga |
| B12 | Bajo | `Platform.runLater` anidado sobre callbacks que ya corren en el hilo de la UI | `CategoryTreeItem:87`, `SchemaIntrospector:299` | **Corregido** |
| B13 | Bajo | `minimumIdle` sin fijar — sigue abierto del pase anterior (§5.4) | `ConnectionPoolManager:86` | **Decisión consciente** — se deja igual a `maximumPoolSize`, ver §B13 |

### Estructura y buenas prácticas (§C)

| # | Qué | Estado |
|---|---|---|
| C1 | `MainController`: **3,344** líneas (eran 2,621 al abrir el análisis), 14 responsabilidades — plan de división concreto | **Hecho, los 4 pasos del plan** (rama `refactor/dividir-main-controller`, 2026-09-15/19) — 3,344 → **2,309** líneas. No llega a las ~1,650 que prometía el plan: ver §C1, "Cómo quedó" |
| C2 | `SchemaIntrospector`: 7 mapas estáticos mutables como estado global de la app | **Abierto a propósito** — ídem |
| C3 | 12 `new Thread(...)` sueltos, sin un punto común | **Abierto a propósito** — hoy son **15**; 2 se mudaron con C1 y en `MainController` quedan 5 |
| C4 | Duplicación real: 5 líneas repetidas 6 veces, 5 copias del mismo `stream`, 2 métodos gemelos | **Corregido** — C4.1 (con A2), C4.2 (`selectedDatabases()`) y C4.3 (`bindSelectionDependentUi`) |
| C5 | Imports totalmente cualificados en línea, inconsistente con el resto | **Corregido** — verificado: no queda ninguno en todo `src/main` |
| C6 | Código y recursos muertos: 51 KB de CSS sin usar + 3 métodos/constructores sin llamador | **Corregido** |
| C7 | Comentarios que ya no describen lo que hace el código — 4 casos | **Corregido** — los 4 |
| C8 | Lógica pura sin tests, incluida la que hace cumplir el modo Solo lectura | **Corregido** — `isReadOnlyStatement`/`enrichErrorMessage` (15 tests) y `MainControllerLogicTest` para `appendCsvEscaped`/`indexOfIgnoreCase`/`summarize` (13 tests) |
| C9 | El build no tiene ninguna herramienta de análisis estático | **Corregido** — `-Xlint:all` en el compilador (build limpio, 0 advertencias) + `StyleClassCoverageTest` |
| C10 | `release=25` sobre JavaFX 21 | **Documentado** — requisito de JDK 25 explícito en el README |
| C11 | El conteo de tests como evidencia — corregido en §C11, no era lo que decía | Sin acción |

---

# §A — Correctitud

## A1. [ALTA] Un listener nuevo en cada repintado de la lista de Ejecución

**Dónde:** `ui/ExecutionTableFactory.java:159`.

```java
applyState(status.stateProperty().get());
status.stateProperty().addListener((obs, oldState, newState) -> applyState(newState));
```

El bloque de arriba de este método desata **siete** propiedades antes de hacer
nada — con un comentario que dice explícitamente *"Desatar SIEMPRE primero,
incluso al quedar vacía… hallazgo real de /code-review"*. Este listener quedó
fuera de esa lista: se **agrega** en cada `updateItem`, y no se quita nunca.

`updateItem` no se llama una vez por fila. Se llama en cada `setItems` (o sea,
en cada corrida), en cada pasada de layout del `ListView`, y al reciclar la
celda para otra base al hacer scroll.

**Dos fallas concretas, no una:**

1. **Repinta la base equivocada.** La celda muestra la bodega A, se recicla
   para mostrar la bodega B — pero el listener viejo, atado al
   `ExecutionStatus` de A, sigue vivo **en esta misma celda**. Cuando A termina,
   ese listener llama `applyState(...)` sobre la celda que ahora muestra a B: el
   punto, el badge ("EJECUTANDO"/"LISTO"/"ERROR") y la barra de progreso de B
   pasan a mostrar el estado de A. Con 20 bodegas y la lista con scroll, esto es
   visible.
2. **Acumulación.** Cada repintado suma un listener más al mismo
   `ExecutionStatus`, y cada uno hace el trabajo completo de `applyState`
   (3 `removeAll` sobre listas de estilo + 1 `add` cada una, o sea 4 pasadas de
   CSS por listener). Diez repintados = diez veces el trabajo por cada cambio de
   estado.

**Honestidad sobre el alcance:** los `ExecutionStatus` se crean nuevos en cada
corrida (`MainController:1663-1680`), así que los listeners de corridas
anteriores mueren con sus objetos — no es una fuga que crezca toda la sesión,
es acumulación **dentro de una corrida**. El repintado cruzado, en cambio, es
inmediato y visible.

**Arreglo.** Guardar el listener y el `ExecutionStatus` al que está atado como
campos de la celda, y quitarlo en el mismo bloque donde ya se desatan las otras
siete propiedades:

```java
private ExecutionStatus boundStatus;
private final ChangeListener<ExecutionStatus.State> stateListener =
        (obs, oldState, newState) -> applyState(newState);

// dentro de updateItem, junto a los unbind() de arriba:
if (boundStatus != null) {
    boundStatus.stateProperty().removeListener(stateListener);
    boundStatus = null;
}
// …y al atar:
boundStatus = status;
status.stateProperty().addListener(stateListener);
```

Es exactamente el patrón que `ConnectionTreeCell` ya usa con
`statusListenerTarget` — no una técnica nueva para el proyecto.

---

## A2. [ALTA] Los listeners del árbol tampoco se desenganchan — el #10 quedó a medias

**Dónde:** `ui/ConnectionTreeCell.java:762-770`, y las 7 ramas de
`updateItem` (`:642-726`).

`statusListenerTarget` existe justo para esto y su javadoc lo explica bien. Pero
solo se reasigna dentro de `updateDatabaseRow` — el único camino que la limpia
sería mostrar **otra base**. Ninguna de las otras 6 ramas de `updateItem`
(celda vacía, `Server`, `Category`, `Loading`, `Error`, `Item`, encabezado de
sección) la toca:

```java
if (empty || item == null) {
    unbindCheckbox();
    refreshInUseAnimation(null);   // ← se apaga la animación…
    editTarget = null;
    // …pero statusListenerTarget sigue apuntando a la base anterior,
    //   con sus 2 listeners todavía enganchados.
```

**Por qué esto reabre el hallazgo #10.** Ese hallazgo era "el pulso sigue
animando en celdas ya recicladas". El arreglo apagó la animación al quedar la
celda vacía — pero dejó vivo el listener que la vuelve a prender:

> Celda mostrando la bodega A (inactiva). El usuario hace scroll, la celda se
> recicla y ahora muestra una fila de esquema ("Tablas") de otra base.
> `statusListenerTarget` sigue siendo A. El usuario corre una consulta contra
> A → `inUseProperty` cambia → `inUseListener` dispara →
> `refreshInUseAnimation(A)` → **`inUsePulse.playFromStart()` sobre el
> `statusDot` de una celda que ya no muestra ninguna base**. Un `FadeTransition`
> `INDEFINITE` interpolando en cada frame sobre un nodo invisible, hasta que la
> consulta de A termine.

Lo mismo con `connectionStatusListener`: reescribe las clases de estilo de
`statusDot` en celdas que están pintando una categoría o un objeto de esquema.

**Arreglo.** Un solo método de limpieza, llamado al principio de `updateItem`
antes de decidir la rama (esto además resuelve C4, las 5 líneas repetidas 6
veces):

```java
private void detachRowState() {
    unbindCheckbox();
    if (statusListenerTarget != null) {
        statusListenerTarget.connectionStatusProperty().removeListener(connectionStatusListener);
        statusListenerTarget.inUseProperty().removeListener(inUseListener);
        statusListenerTarget = null;
    }
    refreshInUseAnimation(null);
    editTarget = null;
    editTreeItem = null;
    schemaItemTarget = null;
    setContextMenu(null);
}
```

Ojo con el orden: hay que llamarlo **antes** de `updateDatabaseRow(db)`, que es
quien vuelve a enganchar. Como `updateDatabaseRow` ya compara
`statusListenerTarget != db`, un `detachRowState()` incondicional al inicio hace
que siempre reenganche — cuesta 2 `removeListener` + 2 `addListener` por
repintado de fila de base. Si eso se quiere evitar, la alternativa es llamar
`detachRowState()` solo en las 6 ramas que NO son `DatabaseEntry`, y dejar la
comparación de `updateDatabaseRow` como está. Recomiendo la segunda: conserva la
optimización que el javadoc de la clase ya documenta y explica.

---

## A3. [ALTA] `connections.json` se puede corromper y perder todo

**Dónde:** `data/ConnectionRegistryStore.java:132`, más
`MainController.java:2573-2594` (`autosave`) y `:2602-2618` (`shutdown`).

```java
Files.writeString(file, root.toString(), StandardCharsets.UTF_8);
```

`Files.writeString` sin opciones abre con `CREATE, TRUNCATE_EXISTING, WRITE`:
**vacía el archivo primero** y después escribe. Ese archivo contiene todas las
conexiones, los grupos, los favoritos, las preferencias y el texto completo de
cada pestaña de consulta abierta. Si algo interrumpe la escritura a mitad de
camino, lo que queda en disco es un JSON truncado — y
`MainController#loadOrCreateRegistry` (`:2478-2490`) captura la excepción y
**arranca con un registro vacío**. El usuario abre Faro y no tiene ninguna base.

**Tres formas reales de que se interrumpa, no hipotéticas:**

1. **Carrera entre el autoguardado y el cierre.** `autosave()` escribe desde el
   hilo demonio `faro-autosave-write`. `shutdown()` hace
   `autosaveTimer.cancel()` — que impide *futuros* ticks pero **no espera** al
   que ya está corriendo — y acto seguido escribe el mismo archivo desde el hilo
   de JavaFX. Dos hilos truncando y escribiendo el mismo `Path` a la vez.
   `autosaveInProgress` protege dos autoguardados entre sí; a `shutdown()` no lo
   mira nadie.
2. **El hilo demonio muere a mitad de escritura.** `faro-autosave-write` es
   demonio (correcto para no bloquear el cierre). Cuando `stop()` termina, la
   JVM sale y mata los demonios donde estén — incluido en medio de un
   `Files.writeString`.
3. **El proceso se mata durante un autoguardado** (el escenario que el propio
   javadoc de `startAutosave` menciona como el motivo de existir).

**Arreglo — dos piezas, ninguna grande:**

```java
// 1) Escritura atómica: nadie ve nunca un archivo a medias.
Path temp = file.resolveSibling(file.getFileName() + ".tmp");
Files.writeString(temp, root.toString(), StandardCharsets.UTF_8);
try {
    Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
} catch (AtomicMoveNotSupportedException e) {
    Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);  // respaldo
}
```

```java
// 2) shutdown() espera al autoguardado en curso antes de escribir él mismo.
void shutdown() {
    autosaveTimer.cancel();
    statusBarTimer.cancel();
    // Espera acotada: si el autoguardado sigue en curso, dejarlo terminar en vez
    // de escribir el mismo archivo desde dos hilos a la vez.
    for (int i = 0; i < 50 && autosaveInProgress.get(); i++) {
        try { Thread.sleep(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
    }
    ...
}
```

La misma escritura atómica aplica a `CredentialVaultStore.save` — es el archivo
de credenciales cifradas, y perderlo obliga a retipear todas.

---

## A4. [MEDIA-ALTA] Los cachés de esquema sobreviven a la base que los generó

**Dónde:** `MainController.java:2112-2117` (`confirmAndDeleteDatabase`) y
`:2039-2043` (`onImportConfig`), contra `query/SchemaIntrospector.java:96-108`
y `:245-252`.

`SchemaIntrospector` tiene 7 mapas estáticos indexados por id de base: `cache`,
`columnDetailsCache`, `definitionCache`, `categoryCache`, `triggerCache`,
`generation`, más `loading`/`categoryLoading`. Solo hay una forma de limpiarlos:
`invalidate(databaseId)`. Y **la única llamada en todo el proyecto está en
`DatabaseTreeItem.reloadSchema()`** — el ítem "Recargar esquema" del menú
contextual (verificado con búsqueda en todo `src/`).

Eliminar una base hace las otras tres limpiezas y se olvida de esta:

```java
registry.removeDatabase(entry);
credentials.remove(entry.id());
pool.evict(entry.id());
// falta: SchemaIntrospector.invalidate(entry.id());
```

**Es el mismo bug que el hallazgo #3, una capa más arriba.** Aquel decía: los
pools se indexan por id, un archivo exportado conserva los ids, así que importar
puede dejarte consultando el servidor anterior. Se arregló con `pool.closeAll()`
antes de reemplazar el registro. El caché de esquema tiene **exactamente la
misma forma** y no se tocó:

> Se exporta la configuración. La base "Bodega Norte" (id `abc`) se reapunta a
> otro servidor. Se vuelve a importar el archivo en una sesión donde esa base ya
> se expandió una vez. `pool.closeAll()` descarta el pool viejo — bien — pero
> `SchemaIntrospector.cache.get("abc")` sigue devolviendo las tablas del servidor
> **anterior**. El árbol lista tablas que no existen en el servidor nuevo, el
> autocompletado las sugiere, y "Generar SELECT" arma un `SELECT` sobre columnas
> de otra base. Todo sin ningún error visible.

Aparte, es una fuga real de sesión: los cachés de las bases eliminadas
(estructura, columnas por tabla, definiciones DDL completas de funciones y
procedimientos — texto, puede ser grande) quedan en memoria hasta cerrar la app,
sin que nadie los pueda alcanzar ya.

**Arreglo.** Una línea en `confirmAndDeleteDatabase`, y un
`SchemaIntrospector.invalidateAll()` nuevo (analogía directa de
`pool.closeAll()`) en `onImportConfig`, con el mismo razonamiento y el mismo
comentario que ya está escrito ahí para los pools.

---

## A5. [MEDIA] Un `Tooltip` nuevo en cada repintado de una fila de error

**Dónde:** `ui/ConnectionTreeCell.java:706`.

```java
} else if (item instanceof SchemaTreeNode.Error error) {
    ...
    schemaErrorLabel.setText(error.message());
    Tooltip.install(schemaErrorLabel, new Tooltip(error.message()));   // ← cada updateItem
```

Contrasta con todo el resto de la clase, que crea sus tooltips **una vez** en el
constructor (`statusTooltip`, `modeTooltip`) y en `updateItem` solo hace
`setText`. Acá se construye un `Tooltip` nuevo y se reinstala en el mismo
`Label` en cada pasada — el objeto anterior queda para el GC y `Tooltip.install`
vuelve a registrar sus manejadores de mouse sobre el mismo nodo.

Severidad moderada porque las filas de error son poco frecuentes (una base que
falla al leer su esquema), pero se repinta en cada pasada de layout mientras esa
fila esté visible, y es trivialmente evitable:

```java
// campo, junto a statusTooltip/modeTooltip:
private final Tooltip schemaErrorTooltip = new Tooltip();
// constructor:
Tooltip.install(schemaErrorLabel, schemaErrorTooltip);
// updateItem:
schemaErrorTooltip.setText(error.message());
```

---

## A6. [MEDIA] 26 palabras clave se autocompletan pero nunca se resaltan

**Dónde:** `ui/SqlEditorFactory.java:29-38` contra
`query/SqlFormatter.java:43-54`.

`SqlFormatter.KEYWORDS` es `public` **a propósito** — su javadoc lo dice:
*"Público a propósito — `SqlAutocomplete` reusa este mismo set en vez de
mantener una segunda lista de palabras clave separada."* Pero `SqlEditorFactory`
(el resaltado de sintaxis) mantiene su propio `private static final String[]
KEYWORDS`, que es justo la segunda lista que ese javadoc dice que no debería
existir. Y las dos ya divergieron:

**En autocompletado y formateo pero SIN resaltar en el editor (26):**
`BEGIN`, `CALL`, `CHECK`, `COLUMN`, `COMMIT`, `CROSS`, `DECLARE`, `EXEC`,
`EXECUTE`, `FUNCTION`, `GRANT`, `IDENTITY`, `MERGE`, `OUTPUT`, `PROCEDURE`,
`RENAME`, `RETURNS`, `REVOKE`, `ROLLBACK`, `TRANSACTION`, `TRIGGER`,
`TRUNCATE`, `UNIQUE`, `USING`, `VIEW`.

**Resaltadas en el editor pero sin autocompletar (5):**
`AVG`, `COUNT`, `MAX`, `MIN`, `SUM`.

Lo que hace esto más que una curiosidad: las 26 de la primera lista son
exactamente las que se agregaron el **2026-08-22 a pedido explícito del
usuario** (*"más sugerencias de palabras reservadas de SQL, como exec,
procedure"*, según el javadoc de `SqlFormatter.KEYWORDS`). Se agregaron a la
lista del autocompletado y nadie tocó la del resaltado — así que hoy escribir
`EXEC` o `DECLARE` en el editor los ofrece como sugerencia pero los deja en
color de texto normal, no de palabra clave. El síntoma es sutil justamente por
eso: parece un descuido del tema, no una lista incompleta.

**Arreglo.** Borrar el arreglo de `SqlEditorFactory` y armar el `Pattern` desde
`SqlFormatter.KEYWORDS`. Como el orden de un `Set` no es estable y una alternancia
de regex sí lo necesita (`INT` antes que `INTO` dejaría `INTO` inalcanzable), hay
que ordenar por longitud descendente al construirlo — una sola vez, en el
`static final`:

```java
private static final String KEYWORD_ALTERNATION = SqlFormatter.KEYWORDS.stream()
        .sorted(Comparator.comparingInt(String::length).reversed())
        .collect(Collectors.joining("|"));
```

Y agregar `AVG/COUNT/MAX/MIN/SUM` (y el resto de agregados comunes) a
`SqlFormatter.KEYWORDS`, que es lo que las hace aparecer en los dos lados a la
vez. De paso el editor gana el resaltado de comentarios de bloque `/* … */`,
que hoy no tiene (`SqlFormatter` y `SqlStatementSplitter` sí los manejan).

---

## A7. [MEDIA] Cerrar pools de HikariCP en el hilo de la UI

**Dónde:** `MainController.java:2039` (`onImportConfig` → `pool.closeAll()`),
`:2083` (`openEditDialog` → `pool.evict`), `:2114`
(`confirmAndDeleteDatabase` → `pool.evict`).

`HikariDataSource.close()` no es instantáneo: dispara el apagado del pool, que
desaloja las conexiones ociosas, **espera** a que vuelvan las que estén en uso, y
apaga sus hilos internos de mantenimiento. Con una consulta larga corriendo
contra esa base, eso se puede ir a segundos.

Los tres llamadores corren en el hilo de JavaFX. El peor de los tres es
`onImportConfig`: `closeAll()` recorre **todos** los pools abiertos y cierra cada
uno en serie — con 20 bodegas registradas y consultas en vuelo, la ventana se
congela hasta que el último termine. Es la misma clase de bug que el hallazgo #2
("Probar conexión congela la ventana"), en un camino distinto.

`shutdown()` también llama `closeAll()`, pero ahí el bloqueo sí es aceptable
(la app se está cerrando) — no hace falta tocarlo.

**Arreglo.** `evict`/`closeAll` sacan el `HikariDataSource` del mapa de
inmediato (barato, es un `remove`) y hacen el `close()` real en un hilo demonio.
Sacarlo del mapa ya garantiza que nadie nuevo lo va a usar; el `close()` es
limpieza de fondo:

```java
public void evict(String databaseId) {
    HikariDataSource removed = pools.remove(databaseId);
    if (removed != null) {
        closeInBackground(removed, databaseId);
    }
}
```

---

## A8. [MEDIA] Importar un CSV de Excel en Windows truena

**Dónde:** `query/CsvParser.java:26`.

```java
try (BufferedReader reader = Files.newBufferedReader(file)) {
```

Sin `Charset`, `Files.newBufferedReader` usa **UTF-8 con `CodingErrorAction.REPORT`**:
un byte que no sea UTF-8 válido no se reemplaza por `?`, lanza
`MalformedInputException`.

Excel en Windows en español guarda "CSV (delimitado por comas)" en la página de
códigos ANSI del sistema (cp1252 en México/España), no en UTF-8. Cualquier
archivo con un acento, una `ñ` o un `°` — o sea, prácticamente cualquier CSV de
datos reales de bodega — hace fallar el import completo. El mensaje que ve el
usuario es `MalformedInputException: Input length = 1`, que no dice nada sobre
codificaciones.

**Arreglo.** Leer con un decodificador tolerante y, si se quiere ser más fino,
detectar el BOM. Lo mínimo útil:

```java
CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT);
// intentar UTF-8; si truena, releer con Charset.defaultCharset() (cp1252 en Windows es-MX)
```

O exponerlo en el diálogo de importar como un combo "Codificación:
UTF-8 / Windows (ANSI)", que es lo que hacen los clientes SQL de escritorio. Cuál
de los dos es una decisión de producto — pero el estado actual (fallar sin
explicar por qué) no debería quedarse.

Relacionado, del otro lado: `onExportResultsCsv` escribe con
`Files.newBufferedWriter` (UTF-8, sin BOM). Excel en español abre eso mostrando
`Ã±` en vez de `ñ`. Un BOM UTF-8 al principio del archivo lo resuelve sin
cambiar nada más.

---

## A9. [BAJA] La fila del árbol no escala con el tamaño de fuente

**Dónde:** `main-view.fxml:153` (`fixedCellSize="44"`) y
`ui/ConnectionTreeCell.java:84` (`ROW_HEIGHT = 44`).

Los dos son constantes fijas. `ResultsTableFactory.rowHeight(fontScaleDelta)`
resuelve exactamente este problema para el grid de resultados — se agregó el
2026-08-26 tras un bug real reportado con captura ("si doy mucho zoom el grid se
ve muy mal"). El árbol nunca recibió el mismo tratamiento, aunque la flecha de
expandir sí (`applyDisclosureScale`, 2026-09-07).

La cuenta con los valores reales de `app.css` (`.tree-db-name` 12.5px,
`.tree-db-host` 9.5px, apilados, con `databaseRow` en padding 4+4):

| `fontScaleDelta` | alto del contenido | `fixedCellSize` | ¿cabe? |
|---|---|---|---|
| 0 (default −1) | ~34 px | 44 px | sí, con aire |
| +3 | ~42 px | 44 px | justo |
| +5 | ~46 px | 44 px | **no, se corta** |

Es el mismo modo de falla que ya se arregló en el grid: el texto crece dentro de
una altura que dejó de alcanzarle. Baja porque solo aparece en el extremo del
slider, y porque no está reportado — pero está latente y el arreglo ya existe en
el proyecto, es aplicar la misma función.

**Arreglo.** Quitar `fixedCellSize` del FXML, calcularlo en Java igual que el
grid (`connectionTree.setFixedCellSize(...)` en `initialize()` y en
`applyCurrentTheme()`), y hacer que `ROW_HEIGHT` de la celda lea el mismo
`IntSupplier fontScaleDelta` que la celda ya recibe.

---

## A10. [BAJA] Dos huecos chicos del CSV exportado

**Dónde:** `MainController.java:1474-1484` y `:1537-1542`.

1. **Encabezados sin escapar.** Los valores pasan por `csvEscape`, los nombres
   de columna no: `writer.write(String.join(",", headers))`. Un alias con coma
   (`SELECT total AS "importe, IVA"`) parte la línea de encabezado en dos
   columnas y desalinea todo el archivo.
2. **`csvEscape` no cubre `\r`.** Comprueba `,`, `"` y `\n`, pero no el retorno
   de carro solo. Un valor `NVARCHAR` con `\r` sin `\n` (pasa con datos
   importados de sistemas viejos) rompe la fila sin comillas que la protejan.

Los dos son de una línea: usar `csvEscape` también para los encabezados, y
agregar `|| value.indexOf('\r') >= 0` a la condición.

---

## A11. [BAJA] `inUse` puede quedarse prendido

**Dónde:** `MainController.java:1673-1679` y `:1746-1755`.

`db.setInUse(true)` se pone para todas las bases al arrancar la corrida. Se
apaga desde un listener sobre `stateProperty()` — o sea, **solo si esa base
llega a reportar algo distinto de `RUNNING`**. `task.setOnFailed` restablece el
botón pero no toca `inUse`.

**Sé honesto sobre la severidad:** no tengo un escenario reproducible. `runOne`
captura `SQLException` **y** `RuntimeException`, así que casi cualquier falla de
una base termina en `reportFailure`. El hueco que queda es angosto:
`executor.invokeAll` interrumpido (las tareas pendientes se cancelan sin
ejecutarse, sus `ExecutionStatus` se quedan en `RUNNING` para siempre) o un
`Error` de la JVM. Hoy nada cancela ese `Task` — `onCancelQuery` cancela por
base, no el `Task` completo — así que no hay disparador real.

Lo anoto porque el costo de cerrarlo es una línea, y el síntoma sería
desconcertante y permanente (un punto pulsando en el árbol sin ninguna consulta
detrás, hasta reiniciar la app):

```java
task.setOnFailed(e -> {
    selected.forEach(db -> db.setInUse(false));   // ← red de seguridad
    resetRunButton();
    ...
```

---

## A12. [BAJA] `Main.stop()` con NPE si el arranque falló

**Dónde:** `Main.java:78-81`.

`controller` se asigna en `start()`. Si `start()` lanza (FXML corrupto, recurso
faltante en el empaquetado — justo lo que `loadFonts()` ya prevé unas líneas más
abajo), JavaFX llama `stop()` igual y `controller.shutdown()` truena con NPE.
El síntoma es que el error real del arranque queda tapado por el NPE en el log,
que es lo contrario de la "trazabilidad completa" que motivó todo el logging.
Un `if (controller != null)` lo cierra.

---

# §B — Rendimiento

## B1. [ALTO] El resaltado de sintaxis bloquea el hilo de la UI

**Dónde:** `ui/SqlEditorFactory.java:55-57`.

```java
codeArea.multiPlainChanges()
    .successionEnds(Duration.ofMillis(150))
    .subscribe(ignore -> codeArea.setStyleSpans(0, computeHighlighting(codeArea.getText())));
```

Cada vez que el usuario deja de teclear 150 ms, esto corre **en el hilo de
JavaFX**:

1. `codeArea.getText()` — materializa el documento COMPLETO como un `String`
   nuevo. Es exactamente el costo que el hallazgo #5 identificó y quitó del
   listener de "cambios sin guardar"… y que sigue intacto acá, solo que
   agrupado cada 150 ms en vez de por tecla.
2. Un `Matcher` con 4 grupos alternados sobre todo ese texto.
3. Un `StyleSpansBuilder` con 2 entradas por coincidencia, para todo el
   documento.
4. `setStyleSpans(0, …)` sobre el documento entero, que obliga a RichTextFX a
   reconciliar los estilos de todos los párrafos.

Este es el patrón del `JavaKeywordsDemo` de RichTextFX — la versión **síncrona**,
que la propia biblioteca publica junto a un `JavaKeywordsAsyncDemo` precisamente
porque la síncrona no aguanta documentos grandes.

**Escenario concreto, y es el caso de uso real del proyecto:** el `AUDITORIA_BUGS_RENDIMIENTO`
usa como ejemplo "una pestaña con un script grande (un dump de 2 MB pegado, o un
`INSERT` generado de miles de líneas)". Con eso abierto, cada pausa de 150 ms al
escribir asigna 2 MB de `String` + recorre 2 MB de regex + reconstruye los spans
de todo el documento, en el hilo que tiene que dibujar la ventana. Se siente como
que el editor "se traba" mientras se escribe.

**Y hay un multiplicador:** el proyecto **genera** scripts largos por diseño —
"Generar INSERT" sobre una tabla con muchas columnas, "Generar script CREATE" de
un procedimiento grande, y `applyGeneratedScript` los abre en una pestaña nueva.
No es un caso de borde, es una función del producto.

**Arreglo.** El patrón asíncrono que la propia RichTextFX documenta: calcular en
un `ExecutorService` de un hilo, descartar resultados obsoletos, aplicar en el
hilo de la UI.

```java
private static final ExecutorService HIGHLIGHTER = Executors.newSingleThreadExecutor(r -> {
    Thread t = new Thread(r, "faro-sql-highlight");
    t.setDaemon(true);
    return t;
});

codeArea.multiPlainChanges()
    .successionEnds(Duration.ofMillis(150))
    .retainLatestUntilLater(HIGHLIGHTER)
    .supplyTask(() -> {
        String text = codeArea.getText();                 // sigue en el hilo de la UI (obligatorio)
        Task<StyleSpans<Collection<String>>> task = new Task<>() {
            @Override protected StyleSpans<Collection<String>> call() {
                return computeHighlighting(text);          // el trabajo caro, fuera de la UI
            }
        };
        HIGHLIGHTER.execute(task);
        return task;
    })
    .awaitLatest(codeArea.multiPlainChanges())
    .filterMap(t -> t.isSuccess() ? Optional.of(t.get()) : Optional.empty())
    .subscribe(spans -> codeArea.setStyleSpans(0, spans));
```

`getText()` tiene que quedarse en el hilo de la UI (el documento de RichTextFX no
es thread-safe para leer desde afuera), así que ese costo no desaparece — pero es
el barato de los cuatro. El regex y la construcción de spans, que son el grueso,
sí salen.

**Techo adicional, si aun así no alcanza:** resaltar solo el rango visible en vez
de todo el documento (`codeArea.visibleParagraphs()`). Es un cambio más grande y
solo vale la pena si el asíncrono se queda corto — pero está bien saber que
existe antes de decidir que "el editor es lento con archivos grandes".

---

## B2. [ALTO] Cada tecla del buscador de bases hace mucho más trabajo del necesario

**Dónde:** `MainController.java:2415-2434` (`refreshTree`), `:2436-2468`
(los dos `bind*`), `ui/ConnectionTreeBuilder.java:84-89` (`matches`),
`ui/SchemaTreeNode.java:95-116`.

`connectionFilterField.textProperty()` llama `refreshTree()` **por cada tecla**,
sin debounce (`MainController:340-343`). Y `refreshTree()` hace, cada vez:

```java
collectDatabaseItems(root)          // recorrido #1 — capturar lo marcado
buildRoot(registry, filtro, ...)    // reconstruir TODO el árbol
bindSelectedCount()                 //   → collectDatabaseItems  (recorrido #2)
bindSelectAllButtonText()           //   → collectDatabaseItems  (recorrido #3)
collectDatabaseItems(root)          // recorrido #4 — reponer lo marcado
```

**Cuatro recorridos completos donde uno alcanza** — los tres últimos son sobre
el mismo árbol recién construido y el resultado es idéntico.

Dentro de `buildRoot`, cada base que **no** calce por alias pasa por
`SchemaTreeNode.matchesAnyName(SchemaIntrospector.cachedNamesByKind(db.id()), filtro)`.
Ese camino, por base y por tecla:

- `cachedNamesByKind` arma un `EnumMap` nuevo y, para TRIGGERS, hace
  `new ArrayList<>(byName.keySet())` — **una copia completa** de los nombres de
  todos los triggers cacheados de esa base (`SchemaIntrospector:174`).
- `matchesAnyName` llama a `filterSchema`, que construye el mapa filtrado
  **entero** (un `ArrayList` por categoría, un `toLowerCase` por cada nombre de
  cada categoría), y **recién entonces** pregunta si alguna lista quedó no
  vacía (`SchemaTreeNode:115`).

Con una base DEV de cliente de las que el propio proyecto documenta (~3,000
tablas) y 20 bodegas registradas, escribir una palabra de 8 letras en el
buscador significa: 8 × 20 × (copia del mapa + `toLowerCase` de ~3,000 nombres +
un `ArrayList` por categoría) ≈ **medio millón de `String` temporales**, más 32
recorridos completos del árbol y 8 reconstrucciones de todos los `TreeItem`.
Todo en el hilo de la UI, entre tecla y tecla.

**Tres arreglos, independientes entre sí, del más barato al más grande:**

1. **Cortocircuitar `matchesAnyName`** — no construir nada, salir en la primera
   coincidencia. Es el cambio de mejor relación esfuerzo/beneficio de todo este
   documento:

   ```java
   public static boolean matchesAnyName(Map<Kind, List<String>> namesByKind, String filter) {
       String needle = filter == null ? "" : filter.trim().toLowerCase(Locale.ROOT);
       if (needle.isEmpty()) {
           return !namesByKind.isEmpty();
       }
       for (List<String> names : namesByKind.values()) {
           for (String name : names) {
               if (containsIgnoreCase(name, needle)) {   // sin toLowerCase, ver abajo
                   return true;
               }
           }
       }
       return false;
   }
   ```

   Y comparar sin copiar, con la técnica que el proyecto **ya usa** en
   `MainController#indexOfIgnoreCase` (`:971-990`, del arreglo del hallazgo #9):
   `String.regionMatches(true, …)`. Es literalmente el mismo problema —
   "no materialices una copia en minúsculas solo para comparar" — en otro
   archivo.

2. **Un solo recorrido por `refreshTree()`.** Pasar la lista ya recolectada a
   `bindSelectedCount`/`bindSelectAllButtonText` en vez de que cada uno recorra
   por su cuenta. De paso resuelve C4: esos dos métodos son idénticos salvo la
   lambda del binding.

3. **Debounce del buscador** (~200 ms), con el mismo mecanismo que el editor ya
   usa para el resaltado. Escribir "bodega norte" pasa de 12 reconstrucciones
   del árbol a 1.

---

## B3. [MEDIO] El grid de resultados asigna una propiedad por celda visible

**Dónde:** `ui/ResultsTableFactory.java:106-115`.

```java
for (int i = 0; i < columnNames.size(); i++) {
    ...
    column.setCellValueFactory(data -> {
        Object[] row = data.getValue();
        Object value = columnIndex < row.length ? row[columnIndex] : null;
        return new SimpleObjectProperty<>(value);     // ← objeto nuevo por cada consulta de celda
    });
    table.getColumns().add(column);                   // ← un evento de cambio por columna
}
```

**Dos cosas distintas:**

1. **`new SimpleObjectProperty` por consulta de celda.** `TableView` pide el
   `ObservableValue` de una celda cada vez que la celda se actualiza — o sea, en
   cada pasada de scroll, en cada `refresh()`, en cada cambio de tamaño. Un
   `SimpleObjectProperty` no es solo el valor: trae su propio bloque de
   infraestructura de listeners que esta tabla nunca usa (ninguna celda escucha
   cambios de su propio valor — las filas de un resultado son inmutables, es el
   mismo razonamiento que ya justificó pasar de `ObservableList<Object>` a
   `Object[]` en `OPTIMIZACION_RENDIMIENTO.md` §1.2).

   Con 25 filas visibles × 20 columnas, un scroll sostenido asigna ~500 objetos
   por pulso de render. No tumba nada, pero es basura constante generada
   mientras el usuario mira los resultados — justo el momento en que el heap ya
   está lleno con millones de filas y no conviene darle más trabajo al GC.

   **Arreglo:** saltarse el `cellValueFactory` y leer directo en el
   `cellFactory`, que es donde la celda ya tiene la fila a mano:

   ```java
   column.setCellFactory(col -> new TableCell<Object[], Object>() {
       @Override protected void updateItem(Object ignored, boolean empty) {
           super.updateItem(ignored, empty);
           Object[] row = empty || getTableRow() == null ? null : getTableRow().getItem();
           setText(row == null || columnIndex >= row.length || row[columnIndex] == null
                   ? null : row[columnIndex].toString());
       }
   });
   ```

   Cero asignaciones de propiedad. Como efecto secundario también da el punto
   natural para formatear tipos (fechas, `BigDecimal`) sin depender de
   `toString()`, si algún día hace falta.

2. **`getColumns().add()` en un bucle.** Cada `add` dispara un evento de cambio
   sobre el `TableView`, que recalcula anchos y layout de columnas. Con 20
   columnas son 20 recálculos donde uno alcanza: armar la lista y
   `table.getColumns().setAll(columnas)` de una vez.

---

## B4. [MEDIO] El script se parte una vez por base en vez de una vez por corrida

**Dónde:** `query/QueryExecutionService.java:247`.

```java
private static void runOne(...) {          // corre en un hilo POR BASE
    ...
    List<String> statements = SqlStatementSplitter.split(sql);
```

`split` es un escáner carácter por carácter sobre todo el script, y produce un
`substring` por sentencia. El resultado **es idéntico para todas las bases** — el
`sql` es el mismo objeto para las N. Hoy se calcula N veces, en paralelo, cada
una asignando su propia lista de sentencias.

Con 20 bodegas y un script generado grande (el caso que el propio proyecto
produce con "Generar INSERT"), son 20 escaneos completos y 20 copias del script
partido, vivas a la vez mientras corre la consulta.

**Arreglo.** Subirlo a `execute`, que ya es el punto donde vive todo lo común a
la corrida, y pasar la lista ya partida a `runOne`. La validación de
`ServerMode.READ_ONLY` (`:249-260`) también se puede precalcular una sola vez
(es una función del script, no de la base — solo el *si aplica* depende de la
base):

```java
// en execute(), una sola vez:
List<String> statements = SqlStatementSplitter.split(sql);
boolean allReadOnly = statements.stream().allMatch(QueryExecutionService::isReadOnlyStatement);
```

Además de ahorrar trabajo, deja el log más limpio: hoy la línea
`"[{}] Script partido en {} sentencia(s)"` aparece N veces idénticas.

---

## B5. [MEDIO] `setTabBadge` reconstruye nodos en cada línea de log

**Dónde:** `MainController.java:1793-1807`, llamado desde `log()` (`:592`),
`updateExecutionSummary()` (`:1789`) y `onRunQuery` (`:1736`).

```java
private void setTabBadge(Tab tab, String name, int count) {
    Label nameLabel = new Label(name);              // nodo nuevo
    ...
    Label badge = new Label(String.valueOf(count)); // nodo nuevo
    graphic = new HBox(6, nameLabel, badge);        // nodo nuevo
    ...
    tab.setGraphic(graphic);                        // reemplaza el gráfico de la pestaña
}
```

`log()` lo llama con **cada línea** que entra a Diagnóstico. Una corrida contra
20 bodegas con errores deja 20 líneas de golpe → 20 reconstrucciones del gráfico
de la pestaña, cada una con 3 nodos nuevos, 3 clases de estilo que resolver y una
pasada de layout de la barra de pestañas.

Lo mismo desde `updateExecutionSummary`, que se dispara por cada cambio de
estado de cada base.

**Arreglo.** Construir los tres gráficos una vez en `initialize()` y guardar los
`Label` del contador; después solo `setText`:

```java
private record TabBadge(Label name, Label count, HBox graphic) { }
private final Map<Tab, TabBadge> tabBadges = new HashMap<>();

private void setTabBadge(Tab tab, String name, int count) {
    TabBadge badge = tabBadges.computeIfAbsent(tab, t -> createBadge(t, name));
    badge.count().setText(String.valueOf(count));
    badge.count().setVisible(count > 0);
    badge.count().setManaged(count > 0);
}
```

---

## B6. [MEDIO] Una cadena temporal por celda al exportar

**Dónde:** `MainController.java:1489-1504`.

El bucle de exportación ya está bien pensado (fila por fila, un solo
`StringBuilder` reusado con `setLength(0)`, sin segunda copia del resultado — es
el arreglo del 2026-08-22). Queda un detalle:

```java
line.append(csvEscape(value == null ? "" : value.toString()));
```

`csvEscape` **devuelve** un `String`: en el caso sin comillas devuelve el mismo
(gratis), pero en el caso con comillas construye una cadena nueva con
`"\"" + value.replace("\"", "\"\"") + "\""` — que son dos asignaciones más
(`replace` + concatenación) por celda que las necesite. Y `value.toString()` ya
asigna una por celda no-`String` (fechas, `BigDecimal`, números).

Para 3,000,000 de filas × 10 columnas son **30 millones de cadenas
temporales**, todas muertas al instante. Es exactamente el tipo de presión sobre
el GC que se paga justo cuando el heap ya está al límite con el resultado
cargado.

**Arreglo.** Escribir directo al `StringBuilder` en vez de devolver:

```java
private static void appendCsvEscaped(StringBuilder out, Object value) {
    if (value == null) {
        return;
    }
    String text = value.toString();
    if (text.indexOf(',') < 0 && text.indexOf('"') < 0
            && text.indexOf('\n') < 0 && text.indexOf('\r') < 0) {
        out.append(text);
        return;
    }
    out.append('"');
    for (int i = 0; i < text.length(); i++) {
        char c = text.charAt(i);
        if (c == '"') {
            out.append('"');
        }
        out.append(c);
    }
    out.append('"');
}
```

Cero asignaciones para el caso común (que es la gran mayoría de las celdas), y
de paso cierra el hueco de `\r` de A10.

---

## B7. [MEDIO] El pool se construye dentro de `computeIfAbsent`

**Dónde:** `query/ConnectionPoolManager.java:38`.

```java
HikariDataSource dataSource = pools.computeIfAbsent(db.id(), id -> buildDataSource(db, credentials));
```

`new HikariDataSource(config)` **no** es una construcción barata: inicializa el
pool de inmediato y hace su chequeo de arranque, que abre una conexión real
(TCP + TLS + login contra el servidor). Eso son cientos de milisegundos en red
local, y hasta el `connectionTimeout` completo (30 s por defecto) si el servidor
no responde.

El javadoc de `ConcurrentHashMap.computeIfAbsent` es explícito en que la función
de mapeo debe ser corta y no debe intentar actualizar el mapa. Mientras corre,
mantiene bloqueado el bin de la tabla — así que dos bases **distintas** cuyos ids
caigan en el mismo bin se bloquean entre sí. Con `maxConcurrentDatabases = 8`
(el default) arrancando 8 pools nuevos a la vez en la primera corrida de la
sesión, es una serialización real de algo que debería ser paralelo.

**Arreglo.** El patrón `get` → construir fuera → `putIfAbsent` → cerrar el
perdedor de la carrera:

```java
public Connection getConnection(DatabaseEntry db, CredentialStore.Credentials credentials) throws SQLException {
    HikariDataSource dataSource = pools.get(db.id());
    if (dataSource == null) {
        HikariDataSource created = buildDataSource(db, credentials);   // fuera del candado
        HikariDataSource existing = pools.putIfAbsent(db.id(), created);
        if (existing != null) {
            created.close();       // otro hilo ganó — descartar el nuestro
            dataSource = existing;
        } else {
            dataSource = created;
        }
    }
    return dataSource.getConnection();
}
```

El costo es que, en la carrera (rara), se puede construir un pool de más y
cerrarlo enseguida. A cambio, ninguna base bloquea a otra.

---

## B8. [BAJO] `updateExecutionSummary` recorre tres veces

**Dónde:** `MainController.java:1768-1790`.

```java
long succeeded = currentExecutionRows.stream().filter(...).count();
long failed    = currentExecutionRows.stream().filter(...).count();
long cancelled = currentExecutionRows.stream().filter(...).count();
```

Tres pasadas donde una alcanza. Y se llama desde el listener de estado de **cada**
base (`:1674-1679`), o sea 20 veces en una corrida contra 20 bodegas — cada una
haciendo 3 × 20 comparaciones más un `setTabBadge` (ver B5).

Un `EnumMap<State, Integer>` en una sola pasada, o simplemente tres contadores en
un `for`, deja todo en una. Es chico en absoluto, pero es trabajo puro de más en
un camino que se dispara N veces por corrida.

---

## B9. [BAJO] Autocompletado: copias por nombre y sin tope de sugerencias

**Dónde:** `ui/SqlAutocomplete.java:106-121` y `:134-142`.

1. **`name.toUpperCase(Locale.ROOT).startsWith(prefixUpper)`** asigna una cadena
   nueva por cada nombre de tabla/vista/columna, en cada invocación. Con 3,000
   tablas son 3,000 cadenas por Ctrl+Espacio. El proyecto ya tiene la solución
   para esto en `MainController#indexOfIgnoreCase`:
   `name.regionMatches(true, 0, prefix, 0, prefix.length())` — misma
   insensibilidad a mayúsculas, sin copiar. (Mismo arreglo que B2, mismo motivo.)

2. **Sin tope de sugerencias.** Se crea un `MenuItem` por coincidencia. Con
   prefijo de una letra sobre una base de 3,000 tablas, eso son cientos o miles
   de `MenuItem` en un `ContextMenu` — cada uno con su nodo, su estilo y su
   handler. El popup tarda en aparecer y es inservible para elegir. Cortar en
   ~50 (con el orden alfabético que ya se aplica) es lo que hace cualquier editor
   real.

3. **Comentario desactualizado, relacionado.** El comentario de `:90-98` justifica
   `LinkedHashSet` diciendo que *"mantiene el orden de inserción igual que la
   lista (importa: primero palabras clave, después tablas, después columnas)"* —
   pero tres líneas después, `:131-132` ordena todo alfabéticamente y descarta ese
   orden. La razón real y válida para el `LinkedHashSet` es el `contains` en O(1)
   (que el mismo comentario también explica, bien); la parte del orden es
   incorrecta. Ver §C7.

---

## B10. [BAJO] El JSON completo se materializa antes de escribirlo

**Dónde:** `data/ConnectionRegistryStore.java:132`.

```java
Files.writeString(file, root.toString(), StandardCharsets.UTF_8);
```

`root.toString()` construye **todo** el JSON como una sola cadena en memoria
antes de tocar el disco — incluido el texto completo de cada pestaña de consulta
abierta. Con 6 pestañas de scripts grandes, eso es un pico de varios MB de
`String` (y el `char[]`/`byte[]` interno detrás) cada 2 minutos.

`Gson` escribe directo a un `Writer` sin materializar nada:

```java
try (BufferedWriter writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
    new Gson().toJson(root, writer);
}
```

(Encaja con el arreglo de escritura atómica de A3 — es el mismo bloque de
código.)

---

## B11. [BAJO] Un viaje de red extra por base, en cada corrida

**Dónde:** `query/QueryExecutionService.java:418-441`.

`attachKillFallback` corre `SELECT pg_backend_pid()` / `SELECT @@SPID` sobre la
conexión, **antes** de la consulta real, para dejar listo el respaldo de
cancelación. Es una consulta trivial, pero es un round-trip completo por base
por corrida: con 20 bodegas, 20 viajes de red antes de que empiece el trabajo
real.

El pid es una propiedad de la **conexión**, no de la corrida — y las conexiones
vienen de un pool, o sea que se reusan. Cachearlo por conexión (un
`Map<Connection, Integer>` con claves débiles, o el `clientInfo` de la propia
conexión) lo dejaría en un viaje por conexión física, no por corrida.

No urge — es una consulta de microsegundos del lado del servidor. Lo anoto
porque es trabajo repetido con una solución limpia disponible, y porque en una
corrida contra muchas bodegas por WAN el round-trip sí se nota.

---

## B12. [BAJO] `Platform.runLater` anidado

**Dónde:** `query/SchemaIntrospector.java:299` y `:372`, contra
`ui/CategoryTreeItem.java:87,91,103,104` y `ui/DatabaseTreeItem.java`.

El javadoc de `loadInBackground` es explícito y correcto:

> *"`onLoaded`/`onError` corren en el hilo de JavaFX (la propia máquina de
> estados de `Task` ya invoca sus listeners vía `Platform.runLater`)"*

Pero el propio método envuelve la llamada **otra vez**:

```java
task.setOnSucceeded(e -> {      // ya está en el hilo de JavaFX
    ...
    Platform.runLater(() -> onLoaded.accept(structure));   // ← envoltura #2
});
```

y el llamador la envuelve **una tercera**:

```java
SchemaIntrospector.loadInBackground(db, credentials, pool,
        structure -> Platform.runLater(() -> applyNames(namesFrom(structure))),   // ← envoltura #3
        error -> Platform.runLater(() -> showError(error)));
```

Resultado: aplicar el esquema al árbol espera **dos pulsos de render extra**
después de que el fetch ya terminó (~32 ms a 60 fps). Nadie lo va a notar contra
un fetch JDBC de cientos de ms, así que el impacto es bajo — pero es confuso de
leer, contradice el javadoc que dice cuál es el contrato, e invita a que el
próximo llamador agregue una cuarta capa "por si acaso".

**Arreglo.** Quitar las envolturas #2 y #3, dejar solo la que `Task` ya hace, y
que el javadoc quede describiendo lo que de verdad pasa. Es limpieza, no
optimización.

---

## B13. [BAJO] `minimumIdle` sigue sin fijarse

**Dónde:** `query/ConnectionPoolManager.java:86-92`.

Sigue exactamente igual que cuando `OPTIMIZACION_RENDIMIENTO.md` §5.4 lo dejó
como recomendación abierta, y el razonamiento de entonces sigue valiendo: sin
`minimumIdle` explícito, HikariCP lo iguala a `maximumPoolSize`, o sea que cada
pool mantiene 4 conexiones TCP abiertas contra su servidor **todo el tiempo**,
haya o no consultas.

Lo actualizo con un dato nuevo de este pase: desde el arreglo del hallazgo #1,
**los pools ya no se abren al arrancar** — solo cuando la base se usa de verdad.
Eso cambia el cálculo: el escenario malo ya no es "abrir Faro con 40 bodegas",
es "una sesión de trabajo larga donde se tocaron 20 bodegas", que deja 80
conexiones ociosas hasta cerrar la app.

Sigue siendo una decisión tuya (menos ociosas = primer `Ejecutar` de cada base
más lento), pero con el arranque perezoso ya en su lugar, `minimumIdle(1)` es
bastante más defendible que antes: la primera conexión de cada base ya se paga
al expandirla en el árbol.

---

# §C — Estructura, refactorización y buenas prácticas

## C1. `MainController` — 2,621 líneas, 14 responsabilidades

`OPTIMIZACION_RENDIMIENTO.md` §5.3 ya lo señaló (a 2,432 líneas) y lo dejó
fuera *"porque el costo de verificarlo bien es alto"*. Ese argumento sigue
siendo válido y **no propongo hacerlo de golpe**. Lo que sí aporto es que en 5
días creció 189 líneas más, y que el propio archivo ya tiene marcadas sus
fronteras — los comentarios `// ---- … ----` son una descomposición ya hecha,
solo que sin materializar:

| Bloque | Líneas | ~Tamaño |
|---|---|---|
| Campos `@FXML` + `initialize()` | 136-646 | 510 |
| Pestañas de consulta | 648-883 | 235 |
| Buscar en el script | 885-1028 | 143 |
| Diálogos y menú | 1030-1430 | 400 |
| Exportar CSV + barra de estado | 1431-1594 | 163 |
| Ejecución de consultas | 1596-1841 | 245 |
| Favoritos | 1843-1896 | 53 |
| Riel izquierdo | 1898-1934 | 36 |
| Explicar plan | 1936-1990 | 54 |
| Importar/exportar configuración | 1992-2052 | 60 |
| Árbol y edición de bases | 2054-2243 | 189 |
| Generar scripts | 2245-2402 | 157 |
| `refreshTree` + bindings | 2404-2468 | 64 |
| Persistencia, autoguardado, cierre | 2470-2621 | 151 |

**Propuesta incremental, en orden de menor riesgo a mayor.** La clave es que
cada paso se puede hacer, verificar corriendo la app, y parar ahí:

1. **`SessionPersistence`** (bloque 14, ~151 líneas). Es el candidato más
   limpio: no toca ningún nodo de la UI salvo para *capturar* datos, ya tiene su
   propia frontera (`autosave`/`shutdown`/`loadOrCreateRegistry`/`loadCredentials`),
   y es donde vive A3 — separarlo hace que el arreglo atómico se pueda testear
   de verdad, sin JavaFX de por medio.
2. **`ScriptGeneratorCoordinator`** (bloque 12, ~157 líneas). Ya está casi
   aislado: `generateFromCacheOrFetch` es un armazón genérico y los 6
   `onGenerateXxx` son adaptadores de una línea. Su única dependencia hacia
   afuera es `addQueryTab`, que se puede pasar como `BiConsumer`.
3. **`QueryTabManager`** (bloques 2 y 3, ~378 líneas). Encapsula
   `QueryTabState`, la creación de pestañas, buscar/formatear/guardar y
   `capturedQueryTabsForSave`. Es el que más le quita al controlador.
4. **`ConnectionTreeCoordinator`** (bloques 11 y 13, ~253 líneas). El más
   riesgoso de los cuatro (toca selección, bindings y el propio `TreeView`), pero
   también donde vive B2 — y separarlo es lo que permitiría testear la lógica de
   selección sin arrancar JavaFX.

Con esos cuatro pasos `MainController` baja a ~1,650 líneas y pasa a ser lo que
su nombre dice: el punto donde el FXML se conecta con los coordinadores.

**Cómo verificarlo sin tests de UI.** El README es honesto en que no los hay. La
red de seguridad real de un refactor así es el log: la app ya escribe a
`logs/faro-app.log` con `TRACE`/`DEBUG` en todos los caminos importantes. Un
guion de humo — abrir la app, expandir una base, correr contra 2, exportar,
cambiar de pestaña, cerrar — y comparar el log de antes contra el de después
detecta una regresión estructural mucho mejor que revisar pantalla por pantalla.
Es la técnica que ya se usó para verificar el arreglo del #1 ("Corrida real de
la app, con el log como evidencia, 2026-09-07 23:09").

### Cómo quedó (2026-09-15/19, rama `refactor/dividir-main-controller`)

Los cuatro pasos, en el orden del plan y **un commit por paso**, para poder parar o
revertir cualquiera por separado:

| Paso | Clase nueva | Líneas | Tests nuevos | `MainController` |
|---|---|---|---|---|
| 1 | `data/SessionPersistence` | 337 | 10 | 3,344 → 3,209 |
| 2 | `ui/ScriptGeneratorCoordinator` | 231 | 4 | 3,209 → 3,054 |
| 3 | `ui/QueryTabManager` | 705 | 11 (4 mudados) | 3,054 → 2,593 |
| 4 | `ui/ConnectionTreeCoordinator` | 441 | 11 | 2,593 → **2,309** |

Suite: 158 → **190**, cero advertencias con `-Xlint:all`, recompilación desde cero
en cada paso.

**Lo que ganó la división, además de líneas.** Tres cosas que antes no se podían
testear sin arrancar JavaFX y ahora tienen tests: el arreglo de **A3** (el de peor
consecuencia de todo el análisis, que hasta ahora no tenía ni una prueba), la
segunda línea del encabezado de cada pestaña (el pedido del 2026-09-11), y la
invariante del hallazgo #1 de `AUDITORIA_BUGS_RENDIMIENTO.md` —ningún recorrido del
árbol le pide los hijos a una base—, verificada con una sonda que la rompe a
propósito.

**Por qué no llega a las ~1,650 líneas del plan.** El plan se escribió sobre un
archivo de 2,621 líneas; al ejecutarlo tenía 3,344. Las ~720 que creció en el medio
entraron casi todas a la sección `// ---- Diálogos ----`, que hoy tiene **993
líneas** y cuyo nombre ya no describe lo que tiene: además de diálogos, contiene la
ejecución de consultas (`onRunQuery`, cancelar, resúmenes, insignias, historial,
~286 líneas) y exportar CSV con la barra de estado (~220). Ninguno de los dos estaba
en los cuatro pasos. Son los candidatos naturales para seguir, en ese orden.

**Un corte más angosto que el del plan en el paso 4.** El plan juntaba los bloques
"Árbol y edición de bases" y "`refreshTree` + bindings". Se movió el estado del árbol
(selección, filas abiertas, scroll, buscador) y los bindings; **las acciones**
—agregar, editar, borrar, mover, renombrar— se quedaron en el controlador y le piden
al coordinador `refresh()` o `revealDatabase()`. Mover las acciones habría arrastrado
sus diálogos y convertido el coordinador en un segundo `MainController`.

**Tres decisiones de diseño que el código documenta:**

- `SessionPersistence` recibe el registro como `Supplier` y no como referencia:
  "Importar configuración…" lo **reemplaza** por otro objeto, y con una referencia
  fija habría seguido guardando el viejo para siempre. Hay un test que lo fija. Lo
  mismo en `ConnectionTreeCoordinator`.
- El cierre de `SessionPersistence` son dos llamadas y no una: fusionarlas invertía el
  orden original (esperar → cerrar pools → guardar), y eso es cambiar comportamiento,
  no refactorizar.
- `QueryTabManager` recibe sus nueve dependencias por una interfaz de **métodos con
  nombre** (`Host`) y no como lambdas posicionales: dos serían `Consumer<String>`, y
  cruzarlas compila y falla en vivo — el mismo riesgo que sigue abierto en
  `ConnectionTreeActions`.

**Cómo se verificó que no cambió lógica.** Pasos 3 y 4 (los de UI, casi sin tests)
con una comparación mecánica en dos direcciones: cada sentencia del bloque original
contra la clase nueva, y cada sentencia de la clase nueva contra el original tras
aplicar los renombres. Todas las diferencias resultaron ser renombres, andamiaje o
manejadores `@FXML` que se quedaron a propósito. Ni una línea de lógica inventada.

**Lo que falta, y es del usuario:** la prueba de humo del log que describe el párrafo
de arriba. El punto 4 de los "Puntos obligatorios" de `CONTEXTO_SESIONES.md` impide
que la corra el asistente. Un detalle para ese diff: las líneas de log que se mudaron
de clase ahora salen con otro nombre de logger (`SessionPersistence`,
`ScriptGeneratorCoordinator`, `QueryTabManager` en vez de `MainController`), así que
el diff las va a marcar aunque el comportamiento sea idéntico.

---

## C2. `SchemaIntrospector` — estado global de la aplicación en `static`

**Dónde:** `query/SchemaIntrospector.java:96-131` y `:225`.

Siete campos `static` mutables: `cache`, `loading`, `categoryLoading`,
`columnDetailsCache`, `definitionCache`, `categoryCache`, `triggerCache`,
`generation`, más un `schemaExecutor` estático.

Funciona, y la concurrencia está bien pensada (el comentario sobre por qué
`ConcurrentHashMap` y no `EnumMap` en `:429-436` es correcto y no obvio). El
problema no es de corrección hoy, es de estructura a largo plazo:

- **Es la causa raíz de A4.** Un caché estático no tiene dueño: nadie es
  responsable de limpiarlo cuando la base que lo generó desaparece. Con un
  `SchemaIntrospector` como instancia guardada en `MainController` (junto a
  `pool` y `credentials`, que ya son instancias por esa misma razón), el ciclo de
  vida sería obvio y A4 no habría existido.
- **Bloquea los tests.** `SchemaIntrospectorTest` tiene 18 tests y todos
  ejercitan métodos puros (`disambiguateByTable`, `sqlServerTypeWithLength`,
  `routineSignature`) — nada que toque el caché, porque un caché estático
  filtra estado entre tests y hay que recordar limpiarlo a mano en cada uno.
- **Impide dos ventanas.** Si algún día Faro abre una segunda ventana (o un
  "perfil" distinto), los cachés estáticos se comparten sin querer.

**No es urgente y es un cambio mecánico grande** (la clase se usa desde 5
llamadores). Lo anoto para que, si algún día se toca esta clase por otro motivo,
convertirla en instancia sea la primera opción — no algo que haya que redescubrir.
Si se hace, `evictDatabase(id)` y `evictAll()` caen solos como métodos de
instancia, y A4 se vuelve imposible de olvidar.

---

## C3. Doce `new Thread(...)` sueltos

**Dónde:** 12 sitios distintos (`MainController` × 7, `ExecutionStatus`,
`AddDatabaseDialogController`, `CsvImportDialogController`, `DatabaseTreeItem`,
`DiscoverDialogController`), todos con la misma receta de 3 líneas:

```java
Thread thread = new Thread(task, "faro-xxx");
thread.setDaemon(true);
thread.start();
```

Cada uno es correcto por separado (demonio, con nombre — buena disciplina). Lo
que falta es el punto común:

- No hay **ningún techo**. `SchemaIntrospector` sí lo tiene
  (`SCHEMA_LOAD_PARALLELISM = 3`, con un javadoc largo explicando por qué se
  agregó tras un hallazgo real), y `QueryExecutionService` también
  (`maxConcurrentDatabases`). Pero los otros 10 caminos pueden arrancar hilos sin
  límite: `DatabaseTreeItem#probeConnection` arranca uno cada vez que se expande
  una base, `ExecutionStatus#cancelQuery` uno por clic en Cancelar.
- **Se repite la misma receta 12 veces**, así que un cambio de política (ej.
  agregar un manejador de excepciones no capturadas por hilo) hay que hacerlo
  12 veces.

**Arreglo.** Una clase chica, `FaroExecutors`, con la fábrica de hilos demonio
con nombre y uno o dos executors compartidos (uno para I/O de red corta —
sondeos, cancelaciones—, otro para trabajo largo —exportar, importar). Los
`Task` de JavaFX se envían igual (`executor.execute(task)`), la máquina de
estados de `Task` no cambia en nada. Es un refactor mecánico, de bajo riesgo, y
deja el techo de recursos en un solo lugar donde se puede razonar sobre él.

---

## C4. Duplicación real (no "podría abstraerse", literalmente el mismo código)

**1. Cinco líneas repetidas seis veces** — `ConnectionTreeCell:642-726`. Las 6
ramas de `updateItem` que no son `DatabaseEntry` empiezan con exactamente:

```java
unbindCheckbox();
editTarget = null;
editTreeItem = null;
schemaItemTarget = null;
setContextMenu(null);
```

Se resuelve con el `detachRowState()` de A2 — que además **arregla el bug**, no
solo acorta el archivo. Es el mejor tipo de refactor: el que hace que el
descuido sea imposible en vez de solo menos probable.

**2. Cinco copias del mismo `stream`** — `MainController` :1021-1025, :1645-1649,
:1957-1961, :2135-2138, :2193-2197:

```java
ConnectionTreeBuilder.collectDatabaseItems(connectionTree.getRoot())
    .stream()
    .filter(CheckBoxTreeItem::isSelected)
    .map(item -> (DatabaseEntry) item.getValue())
    .toList();
```

Un solo `private List<DatabaseEntry> selectedDatabases()`. Cinco copias del mismo
*cast* sin chequear es también cinco sitios que hay que tocar si algún día el
árbol gana otro tipo de `CheckBoxTreeItem` (el javadoc de `SchemaTreeNode` ya
advierte que ese cast es una suposición del diseño).

**3. Dos métodos gemelos** — `bindSelectedCount()` (`:2436`) y
`bindSelectAllButtonText()` (`:2457`). Son idénticos salvo la propiedad destino
y la lambda del binding: los dos recolectan los ítems, los dos arman el mismo
`Observable[]`, los dos crean un `StringBinding`. Un solo método privado que
reciba la `StringProperty` y el `Supplier<String>` deja uno donde hay dos, y de
paso permite compartir el único recorrido del árbol que pide B2.

---

## C5. Imports totalmente cualificados en línea

**Dónde:** `SchemaIntrospector` (`java.util.Set`, `java.util.EnumMap`,
`java.util.stream.Collectors`, `java.util.Locale`), `DatabaseTreeItem`
(`java.util.ArrayList`, `java.util.function.Function`,
`javafx.collections.ObservableList`), `CategoryTreeItem`
(`javafx.collections.ObservableList`, `java.util.function.Function`),
`SchemaTreeNode` (`java.util.Map`, `java.util.EnumMap`), `AppPreferences`
(`com.faro.app.model.DatabaseEntry` — con el import ya presente arriba).

Ejemplo de `SchemaIntrospector:98`:

```java
private static final java.util.Set<String> loading = ConcurrentHashMap.newKeySet();
```

El resto del proyecto es consistente con imports arriba. Estos casos rompen ese
patrón y hacen las firmas más largas de leer sin ganar nada. El caso de
`AppPreferences:71` es el más claro:

```java
defaultPoolSize = Math.max(com.faro.app.model.DatabaseEntry.MIN_POOL_SIZE, value);
```

`DatabaseEntry` **ya está importado** en la línea 3 de ese mismo archivo.

Es cosmético, sí — pero en un proyecto de largo plazo la consistencia de estilo
es lo que hace que un archivo nuevo se lea igual que uno viejo. Es un
find-and-replace de 15 minutos y cero riesgo.

---

## C6. Código y recursos muertos

**1. 51 KB de CSS sin usar en el JAR.**
`src/main/resources/com/faro/app/styles.css` (25 KB) y `styles-dark.css`
(26 KB) son el sistema de temas anterior. `app.css` lo dice explícitamente en su
línea 3: *"esos dos archivos siguen ahí sin tocar"*. Lo único que los referencia
es `Theme.legacyStylesheetResourcePath` (`:214-217`), que **no tiene ningún
llamador** (verificado con búsqueda en todo `src/`).

Van al JAR empaquetado y al `.exe` de `jpackage`. Peor que el tamaño: son 1,445
líneas de CSS que parecen vigentes al abrirlas, y el javadoc de `Theme` cuenta
que el motivo real de haberlas reemplazado fue que *"el mismo bug de estilos ('me
olvidé de tocar el otro archivo') apareció doce veces seguidas en una sola
sesión"*. Dejarlas ahí conserva exactamente la trampa que se quiso cerrar.

Si se quieren guardar como referencia histórica, el lugar es el historial de git
(donde ya están) o una carpeta `docs/`, no `src/main/resources`.

**2. Tres miembros sin llamador:**
- `Theme.legacyStylesheetResourcePath(boolean)` — `Theme:215`.
- `ConnectionTreeBuilder.buildRoot(ConnectionRegistry, CredentialStore, ConnectionPoolManager)`
  (la sobrecarga de 3 argumentos) — `ConnectionTreeBuilder:31`. Solo la llama su
  propio javadoc.
- `DatabaseTreeItem(DatabaseEntry, CredentialStore, ConnectionPoolManager)` (el
  constructor de 3 argumentos) — `DatabaseTreeItem:57`.

Los tres son sobrecargas "por comodidad" que nadie terminó usando. Quitarlas
achica la superficie de API que hay que mantener coherente.

**3. Rama inalcanzable:** `SqlEditorFactory:72` — el `: null` final del
encadenado ternario no se puede alcanzar (si hubo `find()`, exactamente uno de
los 4 grupos con nombre es no nulo). Si se alcanzara,
`Collections.singleton(null)` tronaría más adelante. Es defensa contra algo
imposible que además no defiende. Un `throw new IllegalStateException(...)` o
simplemente `"comment"` como último caso deja más claro qué se está asumiendo.

---

## C7. Comentarios que ya no describen el código

El proyecto tiene una densidad de comentarios altísima y en general
**excelente** — explican el *porqué*, citan el hallazgo real que los originó y
la fecha. Eso es lo correcto y hay que mantenerlo. Justamente por eso los que se
desincronizaron importan: en un archivo donde todo comentario es confiable, uno
que miente cuesta más caro que en un archivo sin comentarios.

**1. `SqlAutocomplete:96-97`** — *"El Set mantiene el orden de inserción igual
que la lista (importa: primero palabras clave, después tablas, después
columnas)"*. Ese orden se descarta 33 líneas después
(`sortedMatches.sort(String.CASE_INSENSITIVE_ORDER)`, `:132`). La justificación
real y correcta del `LinkedHashSet` es el `contains` en O(1), que el mismo
comentario también da. **Arreglo:** borrar la media frase del orden. (Ya
mencionado en B9.)

**2. `ConnectionTreeCell:781-784`** — *"Mutaciones condicionales (solo si de
verdad cambió, no en cada updateItem)"*, justo encima de:

```java
modeIcon.setContent(unrestricted ? Icons.LOCK_OPEN : Icons.LOCK);   // incondicional
modeTooltip.setText(db.mode().label());                             // incondicional
```

Solo la mutación de la clase de estilo (las 7 líneas siguientes) es condicional.
En la práctica no hace daño — las propiedades de JavaFX ya descartan un `set`
con el mismo valor por `equals`, así que `setContent` con el mismo contenido no
invalida nada. Pero el comentario afirma algo que el código de abajo no hace, y
la próxima persona que lo lea va a asumir mal cómo funciona el resto de la
celda. **Arreglo:** acotar el comentario a lo que sí es condicional, y agregar
que el resto es seguro porque JavaFX ya deduplica.

**3. `ConnectionTreeCell` — javadoc de clase**: dice que los nodos *"se
construyen UNA sola vez, en el constructor, y `updateItem` solo actualiza su
contenido — nunca se crean HBox/Button/CheckBox nuevos en cada llamada"*. Es
cierto salvo por A5 (`new Tooltip(...)` en cada repintado de fila de error). Se
arregla arreglando A5 — pero mientras tanto el javadoc promete una invariante
que el archivo rompe.

**4. `AppPreferences:25`** — la nota sobre `fetchSize` dice que PgJDBC lo ignora
en autocommit *"(no es el caso hoy)"*, redactado de forma ambigua: parece decir
que el autocommit no está activo, cuando lo que quiere decir es que la app **no**
desactiva el autocommit, así que en PostgreSQL `fetchSize` **no tiene efecto**.
Vale reescribirlo sin ambigüedad, porque afecta una preferencia que el usuario
puede tocar en Preferencias → Rendimiento creyendo que hace algo en las dos
bases.

---

## C8. Lógica pura sin tests — incluida la que hace cumplir Solo lectura

Los 89 tests cubren bien lo que cubren (parser CSV, formateador, splitter,
nombres de archivo, comparación de esquemas, desambiguación, registro,
credenciales). Pero hay lógica pura, sin JDBC ni JavaFX de por medio, que quedó
sin ninguna:

| Método | Dónde | Por qué importa |
|---|---|---|
| `isReadOnlyStatement` | `QueryExecutionService:475` | **Es lo único que hace cumplir `ServerMode.READ_ONLY`.** El candado del árbol no protege nada más que esto. |
| `enrichErrorMessage` | `QueryExecutionService:396` | Regex sobre mensajes del driver — el caso exacto que más se rompe en silencio al refactorizar |
| `csvEscape` | `MainController:1537` | Estático, puro, y con un hueco real (A10) |
| `indexOfIgnoreCase` | `MainController:971` | Búsqueda hacia atrás con aritmética de índices — fácil de romper por uno |
| `summarize` | `MainController:1838` | Trivial, pero gratis de cubrir |

`isReadOnlyStatement` es el que de verdad urge. Es una función de seguridad
(evitar correr un `DELETE` contra una bodega marcada como protegida), su javadoc
reconoce que es una heurística, y no hay un solo test que fije qué acepta y qué
rechaza. Los casos que un test debería clavar hoy mismo:

```java
// acepta
"SELECT 1"  ·  "  \n select 1"  ·  "-- comentario\nSELECT 1"
"/* bloque */ WITH x AS (...) SELECT ..."  ·  "explain select 1"
// rechaza
"DELETE FROM t"  ·  "UPDATE t SET a=1"  ·  "DROP TABLE t"
"TRUNCATE t"  ·  "INSERT INTO x SELECT * FROM y"   // ← empieza con INSERT, no con SELECT
// el límite conocido, documentado como tal
"WITH x AS (DELETE FROM t RETURNING *) SELECT * FROM x"   // pasa; el javadoc ya lo admite
```

Ese último caso es valioso justamente porque **documenta el agujero**: un test
que afirma el comportamiento actual, con un comentario diciendo que es un límite
aceptado, es la forma de que nadie lo "arregle" sin querer ni lo descubra en
producción.

Los 5 son `private static`. El proyecto ya tiene el patrón para esto —
`SchemaIntrospector.sqlServerTypeWithLength` es package-private con un javadoc
que dice *"Package-private (no private) a propósito — `SchemaIntrospectorTest` la
ejercita directo, sin necesitar JDBC"*. Aplicar el mismo criterio a estos cinco.

---

## C9. El build no tiene análisis estático

`pom.xml` tiene el compilador, `javafx-maven-plugin`, surefire y shade. No hay
ninguna herramienta de calidad, ni siquiera `-Xlint`.

Esto importa concretamente: **A1 y A2 son exactamente el tipo de bug que un
analizador estático encuentra solo.** SpotBugs tiene detectores para listeners
registrados sin contraparte y para campos que escapan sin limpiar; Error Prone
avisa de `ConcurrentHashMap.computeIfAbsent` con funciones no triviales (B7) y de
comparaciones de referencia sospechosas.

**Lo mínimo, gratis y sin dependencias nuevas** — activar las advertencias que
`javac` ya sabe dar:

```xml
<plugin>
  <artifactId>maven-compiler-plugin</artifactId>
  <version>3.13.0</version>
  <configuration>
    <compilerArgs>
      <arg>-Xlint:all,-serial,-processing</arg>
    </compilerArgs>
  </configuration>
</plugin>
```

**El paso siguiente** (una dependencia de build, cero en runtime) sería SpotBugs
con `<failOnError>false</failOnError>` al principio: reporta sin romper el build,
y se va apretando a medida que se limpia lo que encuentre. Para un proyecto que
va a vivir años y donde ya se hicieron tres auditorías manuales, tener la
máquina cazando esta clase de bug entre auditoría y auditoría cambia bastante el
costo de mantenerlo.

### Cerrado el 2026-09-14 — las dos piezas

**1. `-Xlint:all,-serial,-this-escape`** en `maven-compiler-plugin`. Las
exclusiones cambiaron respecto de la propuesta de arriba: `-processing` no hacía
falta (el proyecto no usa procesadores de anotaciones) y en cambio
**`-this-escape`** sí, porque los constructores de JavaFX que el proyecto
subclasea (`TreeCell`, `ListCell`, `TableCell`) llaman a métodos sobrescribibles
por diseño y la advertencia se dispara en todos. Ambas exclusiones están
comentadas en `pom.xml`.

Sacó **9 advertencias**, todas de javadocs huérfanos, y varias eran obra de las
inserciones de las rondas anteriores de este mismo documento. Las 9 corregidas;
el build queda en cero advertencias.

**2. `StyleClassCoverageTest`** (`src/test/java/com/faro/app/ui/`) — el cruce que
pedía el segundo hallazgo de "salieron del uso real":

- **Java:** cualquier método de `getStyleClass()`, no solo `add`/`addAll`/`setAll`.
  Hace falta que sean todos porque las dos formas que un escaneo de texto no
  alcanzaría de otro modo son justamente `removeAll("status-success", …)` (la
  clase se pone desde un parámetro, así que en el `add` no hay literal) y
  `removeIf(c -> c.startsWith("tree-status-dot-"))` (que es donde el código
  declara que esa familia entera es suya).
- **FXML:** `styleClass="a b"` y los `<String fx:value="…"/>` **dentro de un
  bloque `<styleClass>`** — ese mismo elemento se usa en FXML para llenar listas,
  y ahí "UTF-8" no es una clase de estilo.
- **Clases compuestas en tiempo de ejecución** (`log-level-`,
  `tree-status-dot-`, `pool-dot-`): declaradas con sus variantes concretas, que
  **sí** se cruzan contra el CSS. Un prefijo nuevo sin declarar hace fallar el
  test en vez de escaparse.
- **Solo en esa dirección.** El cruce inverso no es práctico: `app.css` restiliza
  a propósito decenas de clases internas de JavaFX y de RichTextFX, y saldrían
  todas como falsos "muertos".
- **Red de seguridad del propio test:** si una expresión regular deja de
  encontrar lo que buscaba, los cruces pasarían con el conjunto vacío. Hay un
  test de pisos mínimos para eso, y otro que falla si una excepción de la
  allowlist deja de hacer falta.

**Verificado quitando las reglas de verdad** (sonda temporal, restaurada): sin
`.trust-cert-check` ni `.discover-results-scroll` en `app.css`, el test falla y
nombra la clase **y el archivo donde se usa**. Son los dos bugs que en su momento
solo se vieron en tema oscuro y solo cuando el usuario los reportó.

---

## C10. `release=25` sobre JavaFX 21

`pom.xml:21` fija `maven.compiler.release=25` con `javafx.version=21.0.8`.
Compila y corre — JavaFX 21 es LTS y funciona sobre JDK 25 — pero conviene que
sea una decisión escrita y no un accidente:

- El `.exe` de `jpackage` embebe el runtime, así que la versión de la máquina
  destino no importa. Pero **cualquiera que clone el repo necesita JDK 25 o
  superior** para compilar, y eso no está dicho en ningún lado.
- JavaFX 25 existe y también es LTS. Si se sube, hay que revisar el CSS: entre
  21 y 25 cambian algunos defaults de Modena, y esta app depende bastante de
  qué hereda de Modena (todo el trabajo de `.check-box` del hallazgo #12 es
  precisamente sobre eso).

Recomendación: dejarlo como está (funciona, y subir JavaFX tiene riesgo visual
real), pero anotar el requisito de JDK en el README junto a las instrucciones de
build.

---

## C11. El conteo de tests como evidencia de verificación

**Corrección de una versión anterior de esta sección:** decía que el README
estaba desactualizado con el conteo de tests. No es así — el README **no trae
ningún número**, solo describe qué cubre `mvn test`. La cifra vive únicamente en
`AUDITORIA_BUGS_RENDIMIENTO.md:113` ("83/83 en verde"), y ahí es un **registro
fechado** de lo que se verificó ese día: no hay que reescribirlo, sería falsear
la evidencia de ese pase.

Lo que sí es cierto es el dato de fondo: hoy `mvn test` da 89 (los 6 de
diferencia son `SchemaComparisonServiceTest`, agregados el 2026-09-07 junto con
la comparación entre bodegas, después de que se escribiera esa línea).

La observación útil, entonces, no es "arreglar un número" sino que **el conteo
solo sirve como evidencia si va acompañado de su fecha**. Los tres documentos ya
lo hacen bien; la convención vale la pena mantenerla explícita para los
siguientes.

---

# Orden sugerido

Ordenado por relación entre lo que cuesta y lo que resuelve, no por severidad
pura:

**Primero — barato y cierra bugs reales**
1. **A1** (listener de Ejecución) — un campo y dos líneas; quita un repintado
   cruzado visible.
2. **A2** (listeners del árbol) — cierra de verdad el #10 y elimina la
   duplicación de C4.1 en el mismo movimiento.
3. **A4** (invalidar cachés de esquema) — una línea + un método; es el gemelo
   directo del #3 que ya se arregló para pools.
4. **B2.1** (cortocircuitar `matchesAnyName`) — un método, mayor ganancia por
   línea tocada de todo el documento.
5. **A12**, **A10**, **A5** — de una línea cada uno.

**Segundo — el que más se va a sentir al usar la app**
6. **B1** (resaltado asíncrono). El único de la lista que cambia cómo se siente
   escribir en el editor, que es donde el usuario pasa el tiempo. Aislado a un
   archivo de 81 líneas.
7. **B2.2 y B2.3** (un recorrido + debounce del buscador).
8. **A6** (unificar las listas de palabras clave) — cierra una función que el
   usuario pidió y que hoy está a medias sin que nadie lo sepa.

**Tercero — protege datos**
9. **A3** (escritura atómica + carrera del autoguardado). Es el de peor
   consecuencia de todo el documento (perder toda la configuración) y el más
   difícil de reproducir a propósito. Va acá y no primero solo porque hace falta
   pensar bien la espera del `shutdown`.
10. **A8** (codificación del CSV) — necesita una decisión de producto
    (autodetectar vs. combo).

**Cuarto — rendimiento medible bajo carga**
11. **B4**, **B6**, **B3**, **B7** — todos acotados a un método, todos con
    beneficio proporcional al tamaño del resultado.
12. **B5**, **B8**, **B9**.

**Continuo — mantenibilidad**
13. **C9** (`-Xlint` primero, SpotBugs después). Antes que C1: es lo que evita
    que un refactor grande introduzca la próxima versión de A1.
14. **C8** (tests de la lógica pura, empezando por `isReadOnlyStatement`).
15. **C6**, **C5**, **C7** — limpieza mecánica, sin riesgo.
16. **C1** paso a paso, en el orden propuesto ahí, verificando con el log entre
    paso y paso.
17. **C2**, **C3** — solo cuando haya que tocar esas clases por otro motivo.

---

# Verificación de este análisis

**Lo que hice de verdad:**
- Leí completo todo `src/main/java` (51 clases), `pom.xml`, `main-view.fxml` y
  las partes relevantes de `app.css`.
- `mvn compile` limpio y `mvn test` **89/89 en verde** antes de escribir nada —
  el punto de partida está confirmado, no supuesto.
- Verifiqué con búsqueda en todo `src/` que `SchemaIntrospector.invalidate` tiene
  un solo llamador (A4), que `legacyStylesheetResourcePath` no tiene ninguno
  (C6), que hay exactamente 12 `new Thread(...)` (C3) y 18 usos de
  `collectDatabaseItems` (C4).
- Comparé las dos listas de palabras clave con un diff real, no a ojo — los 26
  y los 5 de A6 son la salida de esa comparación.
- Calculé la altura de fila de A9 con los valores reales de `app.css`
  (12.5px/9.5px) y el padding real del código, no de memoria.
- Revisé los 12 hallazgos del pase anterior uno por uno contra el código actual
  para no repetir ninguno y para poder afirmar que siguen arreglados.

**Lo que NO cubre esta verificación, y hay que decirlo:**
- **Ningún cambio está implementado.** Este documento es diagnóstico; los
  fragmentos de código son propuestas, no parches aplicados y probados.
- **No hay medición con profiler.** Los hallazgos de §B están razonados desde el
  código y desde el comportamiento documentado de JavaFX/RichTextFX/HikariCP, no
  desde un `.hprof` ni un muestreo de CPU contra tus bodegas reales. Las
  cantidades que cito (medio millón de cadenas por búsqueda, 30 millones al
  exportar) son aritmética sobre los tamaños que el propio proyecto documenta
  (~3,000 tablas, 3 millones de filas), no mediciones. El **razonamiento** es
  verificable leyendo el código; el **número de milisegundos** para tu carga real
  solo sale corriéndolo.
- **A1, A2 y A5 son de comportamiento de UI.** Se ven leyendo el código y
  siguiendo la cadena de llamadas (que es como los encontré), pero confirmarlos
  en vivo requiere usar la app: para A1, correr contra 6+ bodegas y hacer scroll
  en la pestaña Ejecución mientras algunas terminan y otras no, mirando si algún
  badge muestra el estado de la base equivocada.
- **Los dos hallazgos con menos certeza, marcados como tal:** A11 (no tengo un
  disparador reproducible hoy, lo incluyo porque el arreglo es una línea) y A5
  (estoy seguro del `Tooltip` nuevo por repintado y de que rompe el patrón de la
  clase; la acumulación de manejadores depende de internos de JavaFX que no
  ejecuté).
</content>
</invoke>

---

# §D — Auditoría de la 2026-09-12: revisión del código YA modificado

Los §A/§B/§C de arriba auditaron el código **antes** de tocarlo. Esta sección audita
el resultado: 38 archivos cambiados, +2,824 líneas, `src/main` de ~9,950 a **12,648**
líneas. La pregunta que contesta es la que importa después de un pase grande —
**¿qué se rompió al arreglar?**

**Punto de partida:** `mvn test` 131/131, recompilación desde cero.
**Los 7 hallazgos son nuevos y los 7 están corregidos**, con el mismo criterio de
siempre: dónde, qué falla, y cómo se verificó.

## Resumen

| # | Sev. | Qué | Origen |
|---|---|---|---|
| D1 | Media | El swatch del acento "negro" era **invisible** en tema oscuro — contraste 1.00:1 | Introducido el 2026-09-11 |
| D2 | Media | Reordenar con el buscador activo mueve filas que no se ven | Introducido el 2026-09-11 |
| D3 | Media | `truncateForLog` copiaba la sentencia COMPLETA antes de recortarla | Preexistente, no detectado en §B |
| D4 | Baja | `serverTarget` no se limpiaba en la rama de base — misma clase que A2 | Introducido el 2026-09-11 |
| D5 | Baja | La escritura atómica dejaba un `.tmp` huérfano si fallaba | Introducido el 2026-09-10 |
| D6 | Baja | Dos `toLowerCase()` sin `Locale` — uno construye una clase CSS | Preexistente |
| D7 | Baja | El encabezado de pestaña hacía dos recorridos del árbol por casilla | Introducido el 2026-09-10 |

---

## D1. [MEDIA] El swatch del acento "negro" era invisible en tema oscuro

**Dónde:** `PreferencesDialogController#buildAccentSwatches` + `app.css` `.dialog-root`.

`AccentPalette.swatchHex("negro")` devuelve `#18181B`. `.dialog-root` usa
`-fx-background-color: -token-surface`, y en `theme-dark.css`
**`-token-surface: #18181B`**. Es el mismo valor exacto: **contraste 1.00:1**, el
círculo desaparecía por completo. En Preferencias → Apariencia se veían 6 muestras y
un hueco.

La causa de fondo no es el negro sino una decisión anterior que dejó de valer: el
swatch usa **siempre** el valor de tema claro, a propósito, *"así el color de la
muestra no salta al cambiar de tema"*. Eso funcionaba porque los 6 acentos eran de
color y ninguno se acercaba al fondo de ninguno de los dos temas. El acento
monocromático rompió esa suposición sin que nada avisara.

**Arreglado** con un contorno (`-token-text-muted`) en **todos** los círculos, no
solo en el negro — cubre cualquier acento futuro que quede cerca del fondo de alguno
de los dos temas.

**Medido con sonda, en los dos temas.** El primer intento usó `-token-border`, que
parecía la opción natural: dio **1.70:1** contra el fondo oscuro, demasiado tenue
para un anillo de 1px, justo en el caso que venía a rescatar. Con
`-token-text-muted`: **6.91:1** en oscuro, **7.58:1** en claro, y sigue siendo
claramente más apagado que el anillo de SELECCIONADO (`-token-text`), así que los dos
estados no se confunden.

| Tema | Relleno vs fondo (peor caso) | Anillo vs fondo |
|---|---|---|
| Claro | amber 3.19:1 · negro 17.72:1 | 7.58:1 |
| Oscuro | blue 3.43:1 · **negro 1.00:1** | 6.91:1 |

El relleno del negro sigue en 1.00:1 y **así debe ser** — es su color por definición.
Lo que lo vuelve visible es el anillo.

---

## D2. [MEDIA] Reordenar con el buscador activo mueve filas invisibles

**Dónde:** `MainController#onMoveGroupInOrder`/`#onMoveDatabaseInOrder`.

"Subir"/"Bajar" operan sobre la lista **real** del registro, no sobre lo que el filtro
deja a la vista.

**Escenario concreto:** el registro tiene `[A, B, C]`; el buscador oculta `B`; el
árbol muestra `[A, C]`. El usuario selecciona `C` y presiona "Subir". El registro pasa
a `[A, C, B]` — cambió de verdad, y se guardará así — pero en pantalla **no pasa
nada**, porque las dos filas que se intercambiaron no están las dos visibles. El
usuario insiste, vuelve a "no pasar nada", y el orden guardado se desordena sin que lo
vea.

**Arreglado** bloqueando el reordenamiento con un aviso mientras haya filtro, en vez
de intentar "mover entre los visibles": eso significaría reordenar la lista real de
una forma que depende del filtro que hubiera puesto en ese momento, bastante menos
predecible que no dejar hacerlo.

**"Ordenar A-Z" NO lleva la guarda** y se revisó a propósito: ordena la lista completa
de forma determinista, así que el resultado es correcto y predecible sin importar qué
esté filtrado.

---

## D3. [MEDIA] `truncateForLog` copiaba la sentencia completa antes de recortarla

**Dónde:** `QueryExecutionService#truncateForLog`, llamado desde el bucle de sentencias
de `runOne`.

```java
String oneLine = statement.replace('\n', ' ').replace('\r', ' ').strip();
return oneLine.length() > 500 ? oneLine.substring(0, 500) + "…" : oneLine;
```

Limpiaba **primero** y recortaba **después**. Cada `replace` copia la cadena entera,
así que una sentencia de 2 MB producía ~4 MB de basura — para escribir 500 caracteres
al log. Y corre **por cada sentencia, por cada base, en cada corrida**: con 20 bodegas
y un `INSERT` generado grande —algo que esta misma app produce con "Generar INSERT"—
son decenas de MB de copias temporales por corrida.

Agravante: el argumento se evalúa **siempre**, incluso con DEBUG apagado. El formato
diferido de SLF4J difiere el `toString` de los argumentos, no la llamada al método que
los produce. Y acá DEBUG está encendido para `com.faro.app` (ver `logback.xml`), así
que corría de verdad en cada uso.

**Este hallazgo se le escapó a §B** aunque §B revisó `QueryExecutionService` entero:
buscaba asignaciones en el camino de los DATOS (filas, columnas, celdas) y no miró el
camino del logging. Anotado como límite del método de aquella revisión.

**Arreglado** recortando antes de limpiar: el costo pasa de proporcional al tamaño del
script a fijo (500 caracteres).

---

## D4. [BAJA] `serverTarget` no se limpiaba en la rama de fila de base

**Dónde:** `ConnectionTreeCell#updateItem`.

`serverTarget` se agregó el 2026-09-11 para los menús de grupo. Se asigna en la rama
de `Server` y se limpia en `detachRowState()` — pero la rama de `DatabaseEntry`
**no llama a `detachRowState()`** a propósito (`updateDatabaseRow` reengancha los
listeners de forma condicional, para no rehacer el trabajo en cada repintado). Así que
una celda reciclada de un grupo a una base quedaba apuntando al `Server` anterior.

**Hoy no es alcanzable:** el menú que se instala en esa rama es el de base, y sus
ítems leen `editTarget`, no `serverTarget`. Pero es **exactamente la clase de
referencia obsoleta que causó A2**, en una clase que ya tuvo ese bug dos veces
(hallazgo #10 y A2). Cerrado por eso, no por el síntoma.

---

## D5. [BAJA] La escritura atómica dejaba un `.tmp` huérfano al fallar

**Dónde:** `ConnectionRegistryStore#writeAtomically`.

El arreglo de A3 escribe a `connections.json.tmp` y después lo mueve. Si la escritura
falla a mitad (disco lleno, perfil de red que se cae), el temporal quedaba en
`~/.faro/` — inofensivo, pero confuso de encontrar, y se recreaba en cada intento
fallido. **Arreglado** borrándolo en el camino de error. El archivo definitivo no se
toca en ningún caso: ese sigue siendo el punto de escribir a un temporal.

---

## D6. [BAJA] Dos `toLowerCase()` sin `Locale`

**Dónde:** `MainController` (log de Diagnóstico) y `CategoryTreeItem` (texto
"Cargando …").

El primero importa de verdad: construye una **clase CSS**
(`"log-level-" + level.name().toLowerCase()`). En un sistema con locale turco,
`"INFO".toLowerCase()` da `"ınfo"` con i sin punto, así que la clase pasa a ser
`log-level-ınfo` y la regla de `app.css` deja de aplicar — las líneas de Diagnóstico
perderían su color sin ningún error. Bajo porque la app se usa en español, real de
todas formas, y el arreglo es una palabra.

Se aprovechó el mismo barrido para cerrar **C5**: ya no queda ningún
`java.util.X`/`javafx.x.y.Z` cualificado en línea en todo `src/main`.

---

## D7. [BAJA] El encabezado de pestaña hacía dos recorridos por casilla

**Dónde:** `MainController#refreshTabHeader`.

Para la pestaña activa hacía `capturedSelectedDatabaseIds()` (recorrido del árbol +
`LinkedHashSet`) y después `describeSelection` buscaba el alias recorriendo
`registry.allDatabases()`, que además **construye una lista nueva con todas las
bases**. Dos recorridos y dos colecciones por **cada casilla que se marca o
desmarca** — y "Marcar todas" sobre decenas de bases dispara el listener una vez por
cada una.

Es la misma clase de desperdicio que B2, introducida por el arreglo de otra cosa.
**Arreglado** leyendo directo del árbol: el alias ya viene en el objeto, no hay nada
que buscar. La versión por ids se conserva **solo** para las pestañas inactivas, que
es lo único que tienen (su selección vive como ids desde la última vez que se las
dejó) y que se repintan rara vez.

---

## Lo que se revisó y salió limpio

Para que quede claro qué NO es un problema:

- **Recursos JDBC** — todo `Connection`/`Statement`/`ResultSet` en try-with-resources,
  incluidos los caminos nuevos. Sin fugas.
- **Excepciones tragadas** — ningún `catch` vacío sin log en todo `src/main`.
- **`System.out`/`printStackTrace`** — ninguno.
- **Logging** — `logback.xml` rota correctamente: un archivo por día, partido a 20 MB,
  14 días o 500 MB de tope. La carpeta no crece sin límite.
- **El orden de los 13 componentes de `ConnectionTreeActions`** — verificado uno por
  uno contra las 13 referencias a método de `MainController`. **Correcto.** Ver el
  riesgo abajo.
- **El grid de resultados tras la reescritura de B3** — verificado con sonda, no
  razonando: renderiza en la primera corrida, **al repoblar** con otro resultado
  (segunda consulta) y con filas más cortas que las columnas. Era el cambio de mayor
  consecuencia de todo el pase: si `TableCell` no llamara a `updateItem` en algún
  camino, el grid saldría vacío y **ningún test de la suite se enteraría**.

## Riesgo estructural introducido, sin cerrar

**`ConnectionTreeActions` tiene 13 componentes posicionales**, varios del mismo tipo
(`onEdit`, `onNewQuery`, `onDelete`, `onDiscover`, `onToggleMode`, `onMoveToGroup` son
los seis `Consumer<DatabaseEntry>`). Intercambiar dos **compila sin protestar** y falla
en vivo — con `onEdit`/`onDelete` intercambiados, el lápiz borraría la base.

El record fue una mejora clara sobre los 14 parámetros sueltos del constructor
—componentes con nombre, un solo lugar que tocar— pero **no elimina el riesgo, lo
reduce**. Cerrarlo de verdad pide tipos distintos por acción o un constructor tipo
builder. Verificado correcto hoy; anotado para que no se dé por resuelto.

## Lo que esta auditoría confirma sobre §C1

`MainController` pasó de **2,621 a 3,324 líneas** durante este pase. El plan de
división de §C1 no se tocó y la clase creció un 27% mientras tanto. Cada función nueva
entra ahí porque es donde está todo lo demás — es el mecanismo por el que una clase así
crece, y va a seguir pasando mientras el plan siga sin ejecutarse.

---

# Lo que queda documentado, no hecho

Al cerrar la ronda del 2026-09-14 quedan cuatro cosas sin hacer. Están acá con su
razón, **no como olvido**: son las que faltan para que este documento esté
completo.

| # | Qué falta | Por qué no se hizo |
|---|---|---|
| ~~**C1**~~ | ~~Dividir `MainController`~~ — **hecho el 2026-09-15/19** en la rama `refactor/dividir-main-controller`, un commit por paso. **Queda pendiente la prueba de humo del log**, que es del usuario: ver §C1, "Cómo quedó" | Se hizo en una ronda propia, sin arreglos funcionales mezclados, justo por el motivo que figuraba acá |
| **C2** | Los 7 mapas estáticos de `SchemaIntrospector` | Es la causa raíz de A4 (los cachés que no se invalidaban) y lo que impide testear esos cachés. Conviene hacerlo **la próxima vez que haya que tocar esa clase por otro motivo**, no como cambio suelto |
| **C3** | Los `new Thread(...)` sueltos — **15** hoy, no 12 | Sin techo común de recursos. La predicción de que "se moverían solos al dividir C1" se cumplió a medias: 2 se mudaron (a `SessionPersistence` y `ScriptGeneratorCoordinator`), pero siguen igual de sueltos en su clase nueva, y en `MainController` quedan 5 |
| **Riesgo de `ConnectionTreeActions`** | 13 componentes posicionales, seis de ellos `Consumer<DatabaseEntry>` | Ver la sección de arriba. Verificado correcto hoy. **El patrón para cerrarlo ya existe en el código**: `QueryTabManager.Host` (paso 3 de C1) resolvió el mismo problema con una interfaz de métodos con nombre en vez de lambdas posicionales |
| **A14** | El BOM del CSV exportado | Apareció al revisar la documentación el 2026-09-15: estaba descrito dentro del cuerpo de A8 pero no figuraba en ninguna tabla, así que A8 marcado como corregido daba a entender que el tema del CSV estaba cerrado de los dos lados. **No lo está.** El arreglo es una línea, pero **cambia los bytes de todos los archivos exportados**: hay herramientas que no toleran el BOM, así que es decisión del usuario y no un arreglo que deba entrar solo |

Y dos que se cierran **descartándolos**, con su razón escrita arriba en las
tablas: **B11** (el viaje de red por el pid) y **B13** (`minimumIdle`).
