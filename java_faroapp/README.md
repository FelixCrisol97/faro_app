# Faro — Java

Reemplazo completo de `flutter_faroapp/` en Java — decidido el 2026-08-19 por el
estado inmaduro del driver de SQL Server en el ecosistema Dart/Flutter (sin
cliente TDS puro, dependiendo de un wrapper FFI vendorizado sobre FreeTDS que
necesitó parches reales de memoria/charset/fugas — ver `AUDITORIA_CODIGO.md`
en la raíz del repo, y las entradas de `CONTEXTO_SESIONES.md` del 2026-08-02
al 2026-08-05). Java tiene un driver JDBC oficial de Microsoft para SQL
Server, maduro y sin esa clase de problema.

## Estado actual (2026-08-20) — leer esto primero

Sección única y detallada de qué es real y qué falta, a pedido explícito
del usuario ("documenta todo esto a detalle... lo que no se ha realizado
documentalo como pendiente"), verificada releyendo el código real de cada
archivo mencionado (no de memoria) antes de escribirla. Las secciones de
abajo (`Decisiones ya tomadas`, `Pasada de fidelidad`, `Historial de
bugs`, etc.) son el registro histórico/las razones — esta de acá arriba es
el resumen del estado real, para no tener que reconstruirlo leyendo todo
el archivo.

**Nada de `java_faroapp/` está comiteado todavía** salvo el commit de
reestructuración original (`9de47fe`, cuando la carpeta solo tenía el
esqueleto de 4 zonas) — todo lo que sigue (árbol, editor, ejecución,
diálogos) vive sin comitear. Recordatorio del bloqueo ya conocido:
`MainController.java` tiene una contraseña real hardcodeada
(`user = "postgres"; password = "crisol";` en `onTestConnection`) puesta
por el usuario a propósito para probar en vivo — **no comitear esta
carpeta mientras siga ahí**, revertir a las variables de entorno primero
si algún día se comitea.

### Ya funciona de verdad (no simulado, no maqueta)

**Ventana y navegación**
- `MenuBar` con los 7 menús completos (Archivo/Editar/Consulta/Conexiones/
  Ver/Herramientas/Ayuda), items y atajos calcados del prototipo real
  (`faro-java-prototipo.html`, decodificado línea por línea, no
  adivinados) — de los 27 ítems reales que tiene (ya no una aproximación
  "~25", contados de verdad en el FXML), **los 27 tienen acción
  conectada** (2026-08-20, completo — el último en subir fue Editar →
  Autocompletado). El detalle de qué hace cada uno vive repartido en las
  secciones de abajo (Edición rápida, Autocompletado, Favoritos/
  Historial/Panel Ver, Explicar plan, Importar/Exportar configuración).
- Barra de herramientas real con íconos Lucide (`Icons.java`, mismas
  rutas SVG que `demo_html/icons.js`): Ejecutar (real, ícono + "F5"),
  Abrir/Guardar (reales, mismos manejadores que el menú), Formatear
  (real desde 2026-08-20, ver "Edición rápida" más abajo), Favorito
  (real desde 2026-08-20 — guarda la pestaña activa como favorito, ver
  "Favoritos" más abajo), píldora "N bases seleccionadas" (real, ligada
  en vivo a las casillas del árbol), "Probar conexión de prueba" (real,
  pero apunta siempre a la base de pruebas hardcodeada, no a lo que esté
  seleccionado en el árbol), botón de tema claro/oscuro sol/luna (real —
  ver "Tema claro/oscuro" más abajo).
- ~~"Nueva consulta" (Ctrl+T) sin conectar a propósito~~ — **resuelto
  (2026-08-20)**: ya crea una pestaña de editor nueva de verdad, ver
  "Editor SQL" más abajo para el detalle completo del sistema de
  pestañas.
- Fuentes reales cargadas (Sora/Manrope/JetBrains Mono, variable fonts,
  peso 400 real + negrita sintética para 600/700 — JavaFX no soporta el
  eje de peso variable).
- Barra de estado inferior: texto fijo `"Faro Java — esqueleto v0"`, no
  refleja nada dinámico (el estado real de la última acción se muestra en
  el `statusLabel` de arriba, no acá abajo — dos lugares de estado
  distintos, ninguna razón fuerte para que sea así, solo así quedó).

**Árbol de conexiones** (`ConnectionTreeBuilder`, `ConnectionTreeCell`,
`ConnectionRegistry`, `ConnectionRegistryStore`, `model/*`)
- **Persistencia real (2026-08-20)** — `ConnectionRegistry` (servidores +
  bases, con motor/modo/pool/timeout) y `AppPreferences` se guardan a
  `~/.faro/connections.json` al cerrar la app y se cargan al abrirla
  (`ConnectionRegistryStore`). `ConnectionRegistry.withDemoData()` (mismo
  dataset ficticio que `demo_html/data.js`) solo se usa la primera vez
  que corre la app, antes de que exista ese archivo. **Las credenciales
  también se guardan, cifradas (2026-08-20)** — `CredentialStore` ya no
  se pierde al cerrar la app, ver "Persistencia de credenciales — DPAPI"
  más abajo para el detalle completo.
- Fila de base de datos: casilla de selección (bidireccional con
  `CheckBoxTreeItem`), punto de estado de conexión con tooltip, alias
  (clickeable — alterna la casilla, no solo la casilla diminuta),
  candado real de modo (Lucide `lock`/`lock-open`) con tooltip, badge de
  motor (PG/MSSQL), ícono de lápiz **siempre visible** que abre el
  editor — nunca solo un gesto escondido como doble clic (regla general
  para toda la UI de Faro de aquí en adelante, no solo para esta fila).
- Todos los nodos de fila se construyen una sola vez y se reusan (no se
  recrean en cada `updateItem`) — el fix real detrás de los bugs de doble
  clic, ver historial abajo.

**Riel de paneles del panel izquierdo — Conexiones/Historial/Favoritos
(2026-08-20, resuelto)** — `leftTabPane` reemplazó el panel fijo único
que solo mostraba el árbol; ahora es un `TabPane` de 3 pestañas
(Ver → Panel de conexiones/Historial/Favoritos, Alt+1/2/3 ya
conectados). Ver "Favoritos, historial y el panel Ver" más abajo para el
detalle completo — incluye por qué se implementó como pestañas y no como
el riel de íconos del diseño original.

**Editor SQL** (`SqlEditorFactory`) — `CodeArea` de RichTextFX real con
números de línea y resaltado de sintaxis (palabras clave/cadenas/
números/comentarios, colores verificados contra `demo_html/styles.css`
`.tok-*`). Buscar/formatear/autocompletado ya son reales — ver "Edición
rápida" y "Autocompletado" más abajo (el autocompletado, especialmente,
tiene una salvedad importante de que no se vio abrirse en pantalla
todavía — léela antes de confiar en que funciona tal cual).
**Pestañas de múltiples consultas (2026-08-20, resuelto)** —
`MainController` ya usa un `TabPane` real (`queryTabPane`) en vez de un
único editor: "Nueva consulta" (Ctrl+T o el botón "+" junto al título)
crea una pestaña con su propio `CodeArea`; "Abrir archivo .sql…" abre
cada archivo en una pestaña nueva (ya no pisa el contenido de la
pestaña que estaba activa); "Guardar"/"Guardar como…" operan sobre la
pestaña activa y actualizan su título al nombre del archivo guardado.
El estado de cada pestaña vive en `QueryTabState` (clase interna de
`MainController`, guardada vía `Tab#setUserData`) — `CodeArea` + `File`
asociado, nada compartido entre pestañas. `tabClosingPolicy="ALL_TABS"`
con un veto en `Tab#setOnCloseRequest`: no se puede cerrar la última
pestaña que queda, siempre debe haber al menos un editor abierto.
**Aviso de "¿guardar cambios?" (2026-08-20, resuelto)** — cerrar una
pestaña con cambios sin guardar, cerrar toda la ventana, o "Archivo →
Salir" ya preguntan primero en vez de descartarlos en silencio — ver
"Edición rápida" más abajo para el detalle completo.

**Resultados** (`ResultsTableFactory`) — `TableView` de columnas
dinámicas (no se conocen en tiempo de compilación, cada consulta puede
traer columnas distintas), poblada con datos de ejemplo al arrancar y
reemplazada con resultados reales después de ejecutar.

**Ejecución — pestaña en vivo** (`ExecutionStatus`, `ExecutionTableFactory`)
— una fila por base marcada, estado `RUNNING`/`SUCCEEDED`/`FAILED`/
`CANCELLED` + filas + tiempo en ms + mensaje, actualizada en vivo
(`Platform.runLater`) a medida que cada base termina, no todas juntas al
final. Cada fila tiene su propio botón "Cancelar" (habilitado solo
mientras esa base sigue `RUNNING`, vía un binding a su propio estado, no
un chequeo de una sola vez).

**Ejecución real de consultas** (`QueryExecutionService`, `QueryResult`,
`ConnectionPoolManager`, `CredentialStore`) — JDBC real (no simulado),
**concurrente** (hasta 8 bases a la vez, `ExecutorService` acotado), con
pool `HikariCP` por base (uno por `DatabaseEntry.id()`, armado la primera
vez que se usa, desalojado si se edita esa base, todos cerrados al salir
de la app). Resolución de credenciales override (por base) → default (de
sesión) → vacío. Si una base falla no aborta a las demás, el error se
acumula y se muestra en el `statusLabel`. **Cancelación real** —
`Statement.cancel()` sí está implementado: el botón por fila cancela esa
base, y "Consulta → Cancelar ejecución" en el menú cancela todas las que
sigan corriendo de la última corrida. `ExecutionStatus.cancelQuery()`
guarda el `Statement` activo (`volatile`, escrito desde el hilo que
consulta esa base, leído desde el hilo de JavaFX al hacer clic) y marca
`cancelRequested` para que el `SQLException` que `cancel()` provoca en el
hilo de ejecución se reporte como "Cancelado", no como un error real.
**Con respaldo real `KILL`/`pg_cancel_backend` (2026-08-20)** — para
cuando `Statement.cancel()` no alcanza a interrumpir la consulta en el
servidor (pasa en algunas condiciones de red/driver), ver "Respaldo de
cancelación — KILL/pg_cancel_backend" más abajo para el detalle completo.

**Explicar plan de ejecución (2026-08-20, resuelto)** —
`QueryExecutionService#explain`, a diferencia de "Ejecutar", corre solo
contra la primera base marcada (un plan es por naturaleza específico de
una base/motor). Ver la sección dedicada más abajo — incluye la
salvedad importante de que esto no se probó contra un servidor real
todavía.

**Los 5 diálogos ya son reales** (completado 2026-08-20):
- Agregar/editar base de datos (`AddDatabaseDialog`) — un solo formulario
  para las dos operaciones, prueba de conexión inline real, guarda
  credenciales, precarga pool/timeout desde `AppPreferences` (antes
  hardcodeado "4"/"30").
- Credenciales por defecto (`CredentialsDialog`).
- Descubrir bases de datos (`DiscoverDialog`) — escaneo TCP + login JDBC
  real contra un solo host (no un rango de IPs).
- **Importar CSV a una tabla** (`CsvImportDialog`, `CsvParser`,
  `CsvImportService`) — parser CSV real (no `split(",")` ingenuo, maneja
  comillas y comas dentro de campos), `INSERT` por lotes de 500 dentro de
  una sola transacción. Todos los valores van como texto
  (`setObject(i, String)`) apoyándose en la conversión implícita del
  driver — sin inferencia de tipo propia, una columna de tipo exótico
  puede fallar y aborta el import completo (una transacción, no fila por
  fila). No soporta saltos de línea dentro de un campo entre comillas.
- **Preferencias** (`PreferencesDialog`) — 3 pestañas, honestas sobre qué
  es real: **Rendimiento** (real — bases en paralelo al ejecutar, pool y
  timeout por defecto de una base nueva; antes eran una constante fija y
  dos valores hardcodeados en el formulario de Agregar, ahora viven en
  `AppPreferences` y de ahí los lee todo el código), **Atajos** (una
  referencia estática de los atajos reales del menú — "Ayuda → Atajos de
  teclado" abre este mismo diálogo en esta pestaña, en vez de duplicar la
  lista en dos lados), **Apariencia** (real desde 2026-08-20 — ver "Tema
  claro/oscuro" más abajo; el combo Claro/Oscuro de esta pestaña es el
  mismo `AppPreferences.darkTheme` que el botón de sol/luna de la barra
  de herramientas, cambiar uno actualiza el otro en caliente).

**Probar todas las conexiones** (`MainController#onTestAllConnections`,
menú Conexiones) — primer uso real de `DatabaseEntry.connectionStatus`,
que existía desde el principio (el punto de color del árbol ya lo leía)
pero nada lo cambiaba nunca fuera de `UNKNOWN`. Prueba cada base
registrada (no solo las marcadas) con sus credenciales resueltas y
actualiza su punto de estado en vivo. Usa `connectionTree.refresh()`, no
`refreshTree()` — `refreshTree()` reconstruye todo el árbol
(`CheckBoxTreeItem` nuevos) y hubiera borrado cualquier casilla ya
marcada por el usuario; `refresh()` solo repinta las celdas visibles con
los datos actuales sin tocar la estructura ni la selección.

**Pestaña "Diagnóstico"** — ya no es un placeholder: un log real de
sesión (`ListView<String>`, más reciente arriba) que registra ejecuciones
de consultas (inicio/fin/error), cancelaciones, credenciales guardadas,
resultados de escaneo, y "Probar todas las conexiones" — ver
`MainController#log`. No es exhaustivo (no registra cada intento de
conexión individual dentro de una ejecución, por ejemplo) pero sí cubre
las acciones que un usuario dispara a propósito.

**Exportar resultados a CSV** (`MainController#onExportResultsCsv`) —
exporta la tabla de Resultados actual (columnas + filas reales) a un
archivo, con el mismo criterio de escape de comillas/comas que
`CsvParser` usa para leer.

**Abrir/Guardar archivo .sql** (`MainController#onOpenFile`/`onSaveFile`/
`onSaveFileAs`) — lectura/escritura real de texto plano al editor, con
`currentScriptFile` recordando el último archivo para que "Guardar" no
vuelva a preguntar la ruta cada vez (solo "Guardar como…" sí). "Exportar
script SQL…" es exactamente el mismo flujo que "Guardar como…" — no se
construyó como una exportación conceptualmente distinta.

**Importar/Exportar configuración (2026-08-20, resuelto)** — Conexiones
→ Importar/Exportar configuración…, reusa `ConnectionRegistryStore` para
guardar/cargar servidores+bases+preferencias+favoritos hacia/desde un
archivo elegido por el usuario, en vez del archivo fijo de siempre. Ver
detalle en la sección dedicada más abajo (incluye por qué NO exporta
credenciales, a propósito).

**Tests automatizados (2026-08-20, resuelto)** — 18 tests JUnit 5 reales
sobre la lógica pura del proyecto (antes cero). Ver la sección dedicada
más abajo para la lista exacta y qué NO tiene test todavía (todo lo que
depende de JavaFX o de una base de datos real).

**Empaquetado (2026-08-20, resuelto)** — perfil Maven `package` nuevo
(`mvn -Ppackage clean package`) arma un JAR único ejecutable con todo
adentro. Ver la sección dedicada más abajo para la decisión de no usar
jlink y el comando exacto de `jpackage` para el instalador final —
**esa última parte, jpackage en sí, no se corrió, solo se dejó
documentada** (ver salvedad en esa sección).

### Pendiente (nada de esto está hecho)

1. ~~El parpadeo del árbol al hacer clic~~ — **resuelto (2026-08-20),
   confirmado por el usuario en vivo.** Dos teorías de altura de fila
   descartadas primero (ver historial de bugs abajo); la causa real
   era mutar sin condición las listas de `styleClass`/el binding del
   checkbox en `ConnectionTreeCell#updateDatabaseRow` en cada
   `updateItem()`, sin importar si el dato de esa fila había cambiado.
2. ~~Cancelación de consultas~~ — **resuelto (2026-08-20)**: botón
   "Cancelar" por fila en Ejecución + "Consulta → Cancelar ejecución" en
   el menú, ambos vía `Statement.cancel()` real (`ExecutionStatus`/
   `QueryExecutionService`), **con respaldo real `KILL`/
   `pg_cancel_backend` (2026-08-20)** — ver punto 13 y la sección
   dedicada más abajo.
3. ~~Importar CSV a una tabla~~ — **resuelto (2026-08-20)**: `CsvImportDialog`
   con parser real, `INSERT` por lotes en una transacción — ver detalle
   arriba (límite conocido: sin inferencia de tipo, sin campos
   multilínea).
4. ~~Preferencias~~ — **resuelto (2026-08-20)**: Rendimiento real,
   Atajos como referencia, Apariencia honesta sobre no tener theming
   todavía — ver detalle arriba.
5. ~~El `MenuBar` mejoró de 4 a 17 de ~25 ítems conectados~~ — **completo
   (2026-08-20)**: los 27 ítems reales que tiene el menú (conteo exacto,
   ya no aproximado) están conectados — el último, Editar →
   Autocompletado, se resolvió el mismo día, ver "Autocompletado" más
   abajo (límite conocido: solo palabras clave, no nombres de tabla/
   columna reales).
6. ~~Formatear/Favorito de la barra de herramientas~~ — **resuelto
   (2026-08-20)**, ambos con `onAction` real ahora — ver "Edición
   rápida" y "Favoritos, historial y el panel Ver" más abajo.
7. ~~Pestaña "Diagnóstico"~~ — **resuelto (2026-08-20)**: log real de
   sesión, ver detalle arriba.
8. ~~Persistencia~~ — **resuelto (2026-08-20, completo).**
   `ConnectionRegistry` (servidores + bases, con motor/modo/pool/timeout)
   y `AppPreferences` ya se guardan/cargan de
   `~/.faro/connections.json` (`ConnectionRegistryStore`) — el
   equivalente real a `servers_repository.dart`. Se carga al arrancar
   (si el archivo no existe todavía, o está corrupto/con un formato que
   ya no reconoce, cae de vuelta a los datos de ejemplo sin tronar) y se
   guarda al cerrar la ventana (`MainController#shutdown`, ya encadenado
   con el cierre de pools de HikariCP). **`CredentialStore` también
   persiste ahora, cifrada con DPAPI (2026-08-20)** — ver la sección
   dedicada "Persistencia de credenciales — DPAPI" más abajo para el
   detalle completo (`CredentialVaultStore`, `~/.faro/credentials.dat`).
   Sigue sin haber guardado incremental (autosave por cada cambio) para
   ninguno de los dos archivos — solo al cerrar la ventana, así que un
   cierre anormal (proceso matado, no por la ventana) pierde los cambios
   de esa sesión.
9. ~~Pestañas de múltiples consultas~~ — **resuelto (2026-08-20)**:
   `TabPane` real (`queryTabPane`), "Nueva consulta" (Ctrl+T) ya
   conectada — ver detalle en "Editor SQL" arriba. El aviso de
   "¿guardar cambios?" que faltaba cuando se escribió este punto por
   primera vez también se resolvió el mismo día, ver "Edición rápida"
   más abajo (encontrado desactualizado al releer esta sección durante
   la doble validación de esa documentación).
10. ~~Sin tests automatizados~~ — **resuelto (2026-08-20)**: 18 tests
    JUnit 5 reales — ver la sección dedicada "Tests automatizados" más
    abajo para la lista exacta y lo que sigue sin cubrir (todo lo que
    depende de JavaFX o de una base de datos real).
11. ~~Empaquetado~~ (`jlink`+`jpackage` para instalador Windows) —
    **resuelto (2026-08-20), pero solo hasta donde se pudo verificar sin
    correr la app.** Perfil Maven `package` nuevo produce un JAR único
    ejecutable — ver la sección dedicada "Empaquetado" más abajo para la
    decisión de NO usar jlink (documentada como "problemático" para apps
    no modulares con dependencias no modulares, que es exactamente este
    caso) y el comando exacto de `jpackage` para el instalador final,
    que quedó documentado pero sin correr.
12. **Estrategia de migración** (reescritura de una vez vs. por etapas)
    — nunca se decidió explícitamente; en la práctica se ha ido
    construyendo todo en paralelo sobre el mismo esqueleto sesión tras
    sesión, lo cual de facto es "big-bang gradual", pero nadie lo eligió
    a propósito como estrategia.
13. ~~Respaldo de cancelación~~ (`KILL <spid>`/`pg_cancel_backend(pid)`)
    — **resuelto (2026-08-20)**, ver la sección dedicada "Respaldo de
    cancelación — KILL/pg_cancel_backend" más abajo. Límite conocido: si
    el pool de esa base está saturado (ej. `poolSize=1`), el respaldo
    puede no conseguir una conexión nueva a tiempo para mandar el
    comando — recomendado `poolSize >= 2` si se depende de él.

### Hallazgos de revisión de código (2026-08-20)

Corrida con el skill `/code-review` sobre todo `java_faroapp/src` (todo el
árbol, nunca comiteado, así que "el diff" fue el árbol completo). 13
hallazgos reales — **11 ya arreglados el mismo día**, quedan 2 (uno ya
documentado desde antes como bloqueo de commit, uno de deuda técnica
menor sin urgencia). Cada arreglo se releyó completo contra el archivo
real después de escribirlo (doble validación) antes de marcarlo acá.

**Seguridad — los dos que más importaban, ambos resueltos (2026-08-20):**
1. ~~Inyección SQL en Importar CSV~~ — **resuelto.**
   (`query/CsvImportService.java`) — el `INSERT` armaba el nombre de
   tabla y los encabezados del CSV sin escapar. Arreglado con
   `validateIdentifier()`: tabla y cada encabezado se validan contra
   `^[A-Za-z_][A-Za-z0-9_]*$` antes de tocar la base — cualquier nombre
   fuera de ese patrón aborta el import completo con un mensaje claro de
   cuál lo rechazó, antes de armar el SQL.
2. ~~El modo "Solo lectura" no se aplica nunca al ejecutar~~ —
   **resuelto.** (`query/QueryExecutionService.java`) — `runOne()` ahora
   valida `db.mode() == ServerMode.READ_ONLY` antes de ejecutar; si es
   así, `isReadOnlyStatement(sql)` exige que la consulta empiece con
   SELECT/WITH/SHOW/EXPLAIN/DESCRIBE (ignorando comentarios/espacios al
   inicio) o la rechaza sin tocar la base. **Es una heurística por
   primera palabra clave, no un parser SQL completo** — cubre el caso
   real (evitar un DELETE/UPDATE/DROP por accidente contra una base
   marcada protegida), no pretende ser a prueba de alguien que
   deliberadamente intente evadirla.

**Concurrencia — expuestos por la ejecución concurrente que se armó esta
sesión, todos resueltos (2026-08-20):**
3. ~~`DatabaseEntry` mutable sin sincronización~~ — **resuelto.**
   (`model/DatabaseEntry.java`) — los 9 campos mutables ahora son
   `volatile` (alias, host, port, databaseName, engine, mode,
   connectionStatus, poolSize, queryTimeoutSeconds). Garantiza que cada
   lectura vea la última escritura de cada campo individual — **no**
   garantiza atomicidad entre varios campos a la vez (editar host+puerto
   justo a mitad de una ejecución podría leer una combinación a medias);
   ese caso más angosto, documentado en el propio código, en el peor
   caso falla la conexión de esa corrida (no corrompe datos) y se
   autocorrige en la siguiente.
4. ~~Carrera entre crear el `Statement` y registrarlo para cancelar~~ —
   **resuelto.** (`query/ExecutionStatus.java`) —
   `attachStatement(statement)` ahora revisa si ya había un
   `cancelRequested` pendiente de ANTES de que el `Statement` existiera,
   y lo cancela ahí mismo en cuanto llega — ya no se pierde el clic que
   cayó en esa ventana.
5. ~~Columnas desalineadas al mezclar motores en una corrida~~ —
   **resuelto (parcialmente, a propósito).** (`ui/ResultsTableFactory.java`)
   — el `cellValueFactory` ya no lanza `IndexOutOfBoundsException` si una
   fila tiene menos valores que columnas (devuelve `null` en vez de
   tronar). **No se reconstruyó la reconciliación completa de columnas**
   entre motores con formas de resultado distintas — eso sigue siendo un
   caso raro donde el resultado visual puede quedar desalineado, pero ya
   no hace crashear la tabla.
6. ~~Excepción no-SQL dentro de una ejecución se pierde en silencio~~ —
   **resuelto.** (`query/QueryExecutionService.java`) — `runOne()` ahora
   también atrapa `RuntimeException` (ej. `HikariPool.PoolInitializationException`,
   no checked, si el pool no arranca) junto al `catch (SQLException)` que
   ya existía — esa base ya reporta error y `FAILED` en vez de quedarse
   en "Ejecutando…" para siempre.
7. ~~Descubrir bases: una búsqueda lenta contamina la siguiente~~ —
   **resuelto.** (`ui/DiscoverDialogController.java`) — un contador
   `searchGeneration` se incrementa en cada búsqueda nueva; si los
   resultados de una búsqueda vieja llegan después de que ya arrancó una
   más nueva, se descartan en vez de mezclarse bajo el host equivocado.

**Menores — corrección de datos, robustez, higiene, todos resueltos
(2026-08-20):**
8. ~~CSV con filas de largo distinto se corrompe en silencio~~ —
   **resuelto.** (`query/CsvImportService.java`) — antes de abrir
   siquiera la conexión, se valida que TODAS las filas tengan el mismo
   número de campos que el encabezado; si alguna no coincide, aborta con
   un mensaje que dice exactamente qué fila y cuántos campos tenía, sin
   tocar la base.
9. ~~Vaciar el usuario al editar no borra las credenciales guardadas~~ —
   **resuelto.** (`data/CredentialStore.java` + `ui/AddDatabaseDialogController.java`)
   — nuevo método `CredentialStore#remove`; `onSave()` ahora lo llama
   cuando el campo Usuario queda vacío, en vez de simplemente no hacer
   nada.
10. ~~Carga de fuentes: NPE antes de su propio chequeo de null~~ —
    **resuelto.** (`Main.java`) — `getResource(file)` se guarda primero
    y se valida por separado antes de llamar `.toExternalForm()`, así
    que un recurso faltante ya cae en el mensaje de advertencia por
    consola que el código ya preveía, no en un `NullPointerException` que
    tronaba el arranque.
11. ~~Binding del botón Cancelar no se libera en celdas vacías~~ —
    **resuelto.** (`ui/ExecutionTableFactory.java`) — `unbind()` ahora se
    llama siempre al principio de `updateItem`, antes de decidir si la
    celda va a quedar vacía o no, en vez de solo en la rama donde se
    rebindea.

**Quedan 2, sin resolver a propósito:**
12. **Credenciales hardcodeadas anulan las variables de entorno** — ya
    documentado como bloqueo de commit al principio de este archivo
    (`MainController.java`, `onTestConnection`) — el usuario pidió
    explícitamente dejarlo así por ahora para probar en vivo, así que no
    se toca hasta que lo pida.
13. **Lógica de "probar conexión" duplicada** — el mismo patrón
    "conectar, mostrar versión, atrapar `SQLException`" está casi
    calcado entre `MainController#onTestConnection` y
    `AddDatabaseDialogController#onTestConnection` en vez de compartir
    un helper; no es un bug, es deuda de mantenimiento de baja prioridad
    frente al resto — sin resolver, no urgente.

## Diseño — ya definido, ver `Migración_Flutter_Java/entrega/`

El diseño real ya está hecho (dos artefactos exportados por el usuario, ábrelos
en un navegador):
- **`faro-java-handoff.html`** — la guía técnica escrita: por qué JavaFX, mapa
  de cada zona de la interfaz a su control JavaFX, tabla de tokens de diseño
  (mismos valores hex que la app actual, mapeados a propiedades `-fx-*`),
  notas de implementación, y los 5 diálogos.
- **`faro-java-prototipo.html`** — la ventana completa navegable e interactiva
  (React-like, con estado real: Ejecutar/Cancelar simulados, export CSV con
  progreso, árbol expandible, tema/acento intercambiables). Tiene un toggle
  "Mapa JavaFX" que superpone el nombre del control real (`MenuBar`,
  `TreeView<ConnNode>`, `CodeArea (RichTextFX)`, `TableView`, etc.) sobre cada
  zona de la pantalla.

### Decisiones ya tomadas en el diseño (no las repitas de memoria)

- **Tecnología: JavaFX 21 (LTS) + FXML + CSS.** Evaluada contra Swing+FlatLaf,
  Compose Desktop (Kotlin) y SWT/Eclipse RCP — JavaFX gana porque tiene hoja
  de estilos propia (los tokens de color/radio/sombra se traducen casi 1:1),
  `TableView` virtualizado nativo, y `RichTextFX` cubre el editor SQL con
  resaltado.
- **Estructura de ventana nueva** — pasa del shell actual (árbol único +
  panel flotante que tapa la pantalla) a un layout de escritorio clásico:
  barra de menú (`MenuBar`) con comando completo, barra de herramientas,
  riel de paneles fijo a la izquierda (Conexiones/Historial/Favoritos dejan
  de ser overlay), pestañas de consulta (`TabPane`), y un panel inferior con
  **tres pestañas: Resultados / Ejecución / Diagnóstico** (las dos últimas
  son nuevas, no existen en la app Flutter).
- **Los 4 problemas reales que este diseño resuelve** (todos ya documentados
  como limitaciones conocidas de la versión Flutter):
  1. Cancelar de verdad — `Statement.cancel()` + `KILL <spid>` (SQL Server) /
     `pg_cancel_backend(pid)` (Postgres) como respaldo si la sesión sigue
     viva. Esto es justo la primitiva de cancelación que quedó sin resolver
     en `AUDITORIA_CODIGO.md` (Prioridad 2) por ser una limitación
     arquitectónica del driver FFI de Dart.
  2. Exportar CSV sin bloquear la ventana — tarea en segundo plano
     (`Task`), escritura por bloques con `BufferedWriter`, progreso en la
     barra de estado.
  3. Grid de resultados lento al hacer scroll — `TableView` virtualiza de
     forma nativa.
  4. Consulta masiva en muchas bases lenta/opaca — la pestaña Ejecución
     muestra estado en vivo por base de datos (punto de color, host,
     filas, ms, botón de cancelar individual), en vez de una sola espera
     sin información.
- **5 diálogos**: Agregar/editar base de datos (motor, credenciales, modo,
  tamaño de pool, timeout, probar conexión inline), Credenciales, Importar
  CSV, Descubrir bases de datos, Preferencias (pestañas Apariencia /
  Rendimiento / Atajos).
- **Notas de implementación ya decididas**: `Task<QueryResult>` +
  `Platform.runLater` (nunca JDBC en el hilo de la UI), HikariCP (un pool
  por servidor), Postgres requiere `autoCommit=false` + `setFetchSize` para
  cursor por bloques, SQL Server usa `selectMethod=cursor` +
  `queryTimeout` por sentencia, credenciales en el almacén de claves del
  sistema (nunca en JSON plano), empaquetado con `jlink`+`jpackage` para un
  instalador Windows sin requerir JDK en las máquinas de bodega.

### Ya resuelto — entorno de desarrollo

- **Build tool: Maven** (confirmado con el usuario sobre Gradle).
- **Desarrollar en Windows nativo, no en WSL.** Se intentó primero usar el
  JDK 25 (Temurin, vía SDKMAN) que el usuario ya tenía instalado dentro de
  su distro Ubuntu de WSL2 — Maven se instaló ahí también (`sdk install
  maven`) y `mvn compile`/`mvn javafx:run` corrían sin ningún error. El
  problema real: **WSLg no estaba renderizando ninguna ventana gráfica en
  esta máquina** — ni la de Faro ni una prueba mínima de X11 (`xclock`,
  instalado con `apt install x11-apps`) mostraban nada, solo un ícono de
  bandeja de "app corriendo" sin ventana real. `wsl --update` (el arreglo
  estándar) no cambió nada — ya estaba en la versión más reciente. Se
  descartó seguir investigando WSLg específicamente (no es un problema de
  Faro) y el usuario instaló JDK 25 + Maven **directo en Windows** —
  funcionó de inmediato, sin ningún ajuste adicional (la ventana JavaFX se
  ve con el pipeline de render nativo de Windows, sin necesitar forzar
  modo software como se probó sin éxito en WSLg). **Desarrollo y
  ejecución quedan en Windows nativo de aquí en adelante** — WSL/SDKMAN
  quedan instalados por si hacen falta después (ej. para algo sin GUI),
  pero no son el camino recomendado para correr la app con ventana.
- **Esqueleto Maven/JavaFX inicial (4 zonas, placeholders) ya superado** —
  ver la sección "Estado actual" al principio de este archivo para lo que
  hay hoy en `pom.xml`/`src/main/java/com/faro/app/`, que es mucho más
  que esto.

### Pasada de fidelidad de diseño (2026-08-20)

El usuario marcó, con razón, que `styles.css` se había ido alejando del
diseño real ("no veo que sea el mismo diseño... fuentes, fondos, iconos,
diseño de tablas, bordes, colores, hovers... el diseño es muy diferente") —
varios valores se habían escrito de memoria en vez de compararse contra las
fuentes reales. Se hizo una pasada completa comparando cada regla contra
`flutter_faroapp/lib/core/theme/*.dart` (colores/radios/sombras/tipografía)
y `demo_html/styles.css` (réplica verificada pixel a pixel de la app
Flutter — más confiable que re-derivar de los `.dart` para spacing/hover
exactos). Cambios reales, no cosméticos:

- **Fuentes reales cargadas** — copiadas de `flutter_faroapp/assets/fonts/`
  a `src/main/resources/com/faro/app/fonts/`, cargadas vía
  `Font.loadFont()` en `Main.java`. Sora en títulos (`.app-title`,
  `.dialog-title`), Manrope en todo lo demás, JetBrains Mono en el editor
  SQL y el badge de motor. Son variable fonts — JavaFX solo carga la
  instancia de peso 400, los pesos 600/700 son negrita sintética vía
  `-fx-font-weight`, no el eje de peso real (no hay forma de evitar esto
  sin una librería de fuentes variables que JavaFX no trae).
- **Íconos reales** (`Icons.java`, mismas rutas SVG que
  `demo_html/icons.js`) en vez de texto plano o formas geométricas: la
  barra de herramientas (Ejecutar/Abrir/Guardar/Formatear/Favorito), el
  botón "+" de agregar base de datos, y el lápiz de editar (que además le
  faltaba el trazo de la punta).
- **Tabla de resultados** corregida contra `table.results-grid`: encabezado
  con fondo `--surface-alt` y texto `--text` (no muted), sin cebra, sin
  resaltado de "seleccionado" (es una vista de solo lectura), padding
  8×14, resaltado solo en hover.
- **Pestañas Resultados/Ejecución/Diagnóstico** corregidas contra
  `.tab-chip`: subrayado de 2px con el acento en la pestaña activa, sin
  fondo relleno tipo "pill" (así es como se ven las pestañas de consulta en
  la app real).
- **Editor SQL** corregido contra `.sql-editor-wrap`/`.tok-*`: fondo
  `--surface-alt` (no blanco), radio 10px, número al que resalta la
  sintaxis con el color de warning (`--warn-base`) no azul, comentarios con
  `--text-muted` no un gris inventado.
- **Menús desplegables** (`.context-menu`) con la sombra `--shadow-md` y
  radio 10px del prototipo — antes no tenían sombra.
- Radio de chip (badge de motor) corregido a 6px (`AppRadii.chip`), no 4.

Lo que **no** se intentó arreglar, a propósito: el checkmark de las
casillas sigue siendo un bloque sólido, no la marca de verificación SVG
real (`M4 12.5l5 5L20 6.5`) — `-fx-shape` en JavaFX solo rellena áreas
cerradas, un trazo abierto como ese no tiene área que rellenar, así que
intentarlo se habría visto peor (invisible), no mejor. Las esquinas
redondeadas del diálogo modal tampoco son iguales a la tarjeta flotante del
prototipo web — un `Stage` nativo decorado usa el chrome de ventana del
sistema operativo, no un `div` con `border-radius`; lograr esquinas
redondeadas ahí requeriría una ventana sin decoración con chrome dibujado a
mano, un cambio bastante más grande que no se hizo sin pedirlo primero.

### Todavía sin decidir

- Estrategia de migración: ¿reescritura completa de una sola vez, o por
  etapas (ej. capa de conexión/consulta primero, interfaz después)? En la
  práctica se ha ido construyendo todo en paralelo sesión tras sesión sin
  elegir esto a propósito — ver la nota en "Estado actual" arriba.
- El resto del trabajo pendiente (diálogos que faltan, menú sin conectar,
  parpadeo del árbol, cancelación, persistencia, etc.) ya no vive acá —
  ver la lista numerada en "Estado actual" al principio del archivo, para
  no tener el mismo pendiente escrito en dos lugares que se puedan
  desincronizar.

### Credenciales por defecto y Descubrir bases de datos (2026-08-20)

**Credenciales por defecto** (`CredentialsDialog`/`CredentialsDialogController`,
menú Conexiones → "Credenciales por defecto…") — un usuario/contraseña de
sesión que `CredentialStore#resolve` usa cuando una base no tiene su
propio override guardado desde Agregar/editar. Mismo criterio de
resolución que `credentialsRepositoryProvider` en la versión Flutter:
override por base → default de sesión → vacío. `QueryExecutionService` ya
usa `resolve()` en vez de `get()` — antes, sin este diálogo, una base sin
override guardado simplemente no podía ejecutar nada.

**Descubrir bases de datos** (`DiscoverDialog`/`DiscoverDialogController`,
menú Conexiones → "Descubrir bases en esta IP…") — escaneo real, no
simulado: dado un host + usuario/contraseña, prueba una conexión TCP corta
(800 ms) a los puertos 5432 y 1433, y si responden, un login JDBC real
contra la base de mantenimiento de cada motor (`postgres`/`master`) para
listar las bases que ese usuario puede ver de verdad
(`SELECT datname FROM pg_database WHERE datistemplate = false` /
`SELECT name FROM sys.databases WHERE database_id > 4`). Los resultados
salen como una lista de casillas; "Agregar seleccionadas" las suma a "Sin
grupo" y guarda esas credenciales en `CredentialStore` para que ya se
puedan ejecutar consultas contra ellas sin pasos extra. **Alcance
recortado a propósito:** un solo host por búsqueda, no un rango de IPs —
coincide con el texto real del ítem de menú ("esta IP", singular). Un
escaneo de rango sería un paso aparte bastante más grande (cientos de
intentos de conexión, necesitaría su propia UI de progreso).

### Ejecución real de consultas (2026-08-20)

El botón "Ejecutar" de la barra de herramientas y el ítem de menú
"Consulta → Ejecutar en las bases seleccionadas" ya corren el texto del
editor SQL de verdad, vía JDBC, contra todas las bases marcadas en el
árbol — ver `QueryExecutionService`/`QueryResult` (paquete
`com.faro.app.query`). Los resultados llenan la tabla de la pestaña
Resultados con una columna inicial "Base de datos" (para distinguir de
dónde vino cada fila cuando hay más de una marcada) seguida de las
columnas reales del `ResultSet`. Corre en un `Task` en un hilo aparte
(`onSucceeded`/`onFailed` ya vuelven al hilo de JavaFX automáticamente, no
hace falta `Platform.runLater` manual) — nunca bloquea la ventana.

**Actualizado el mismo día — HikariCP + concurrencia real:**
`ConnectionPoolManager` (paquete `com.faro.app.query`) arma un
`HikariDataSource` por base de datos (no por servidor — un "servidor" acá
es solo un agrupador libre, ver javadoc de la clase), creado la primera
vez que se pide y reusado después. `QueryExecutionService` ya no consulta
las bases marcadas una por una: cada una corre en su propio hilo (acotado
a `MAX_CONCURRENT_DATABASES = 8` a la vez), y `Main#stop()` cierra todos
los pools al salir para no dejar hilos de HikariCP colgados. Si el usuario
edita una base ya consultada (host/puerto/motor/credenciales),
`MainController#openEditDialog` desaloja su pool viejo
(`ConnectionPoolManager#evict`) para que la siguiente ejecución arme uno
nuevo con los datos actuales — sin esto, un pool ya armado con
credenciales/host viejos seguiría usándose después de editar. Costo
aceptado: el orden de las filas ya no es predecible entre bases (antes,
secuencial, salían en el orden del árbol) — normal al correr en paralelo.

**Simplificaciones que siguen pendientes** (no son bugs, alcance
recortado a propósito):
- Si una base falla (sin credenciales, SQL inválido, host caído) se
  anota el error y se sigue con las demás — no se aborta toda la
  ejecución por una base, pero tampoco hay una vista separada de errores
  todavía (van concatenados en el `statusLabel` de la barra de
  herramientas).
- ~~La pestaña "Ejecución" sigue siendo un placeholder inerte~~ — **ya no,
  resuelto el mismo día.** `ExecutionStatus` (paquete `com.faro.app.query`)
  guarda el estado en vivo de cada base (`RUNNING`/`SUCCEEDED`/`FAILED`,
  filas, tiempo en ms, mensaje) como propiedades JavaFX; `MainController`
  crea una fila por base marcada ANTES de arrancar el `Task` (así la tabla
  ya muestra "Ejecutando…" para todas apenas se presiona Ejecutar) y se
  las pasa a `QueryExecutionService`, que las actualiza vía
  `Platform.runLater` a medida que cada base termina — no al final de
  todo, en vivo, una por una según van completando (`ExecutionTableFactory`
  arma la `TableView`). Punto de color por texto (no un círculo real
  todavía — ver estilo de columna "Estado" en `ExecutionTableFactory`, es
  solo color de texto, más simple que el punto de color del árbol).
- ~~Falta la cancelación individual por base~~ — **ya no, resuelta en una
  sesión posterior.** Botón "Cancelar" por fila real, vía
  `Statement.cancel()` con respaldo `KILL`/`pg_cancel_backend` — ver la
  sección "Respaldo de cancelación — KILL/pg_cancel_backend" más abajo
  para el detalle completo (esta viñeta quedaba desactualizada de cuando
  se escribió este párrafo por primera vez — encontrado al releer esta
  sección durante la doble validación de la documentación del respaldo).

**Para probarlo con la base demo "crisol":** edítala (ícono de lápiz),
pon usuario `postgres` / contraseña `crisol` (las mismas que están
hardcodeadas en `onTestConnection`), Guardar, marca su casilla en el
árbol, escribe una consulta real contra esa base (la de ejemplo
precargada en el editor usa una tabla `ventas` que no existe en la base
de pruebas) y presiona Ejecutar.

### Respaldo de cancelación — KILL/pg_cancel_backend (2026-08-20)

`Statement.cancel()` (ver arriba) ya funcionaba, pero es una llamada JDBC
que puede no alcanzar a interrumpir la consulta en el servidor bajo
ciertas condiciones de red/driver — el respaldo real que el diseño
original ya tenía identificado como parte de "Cancelar de verdad" (uno de
los 4 problemas que este diseño resuelve, ver más abajo) es mandar el
comando administrativo del motor directamente: `pg_cancel_backend(pid)`
en Postgres, `KILL <spid>` en SQL Server.

- **Captura del pid/spid** (`QueryExecutionService#attachKillFallback`/
  `#fetchBackendId`) — apenas se abre la conexión que va a correr la
  consulta, antes de ejecutarla, se manda una consulta corta extra sobre
  esa MISMA conexión (`SELECT pg_backend_pid()` / `SELECT @@SPID`) para
  saber qué proceso del servidor la va a estar corriendo. Si esa consulta
  corta falla (motor raro, permiso denegado), simplemente no se arma el
  respaldo para esa base — `Statement.cancel()` sigue siendo el camino
  principal y no depende de esto.
- **Disparo** (`ExecutionStatus#attachKillFallback`/`#cancelQuery`) — el
  pid/spid capturado se guarda como un `Runnable` dentro del
  `ExecutionStatus` de esa base. Al cancelar (desde el botón por fila o
  "Consulta → Cancelar ejecución"), `cancelQuery()` ya no solo llama
  `Statement.cancel()`: también lanza ese `Runnable` en un hilo aparte
  (`faro-kill-fallback`, daemon) para no bloquear el hilo de JavaFX.
- **El comando en sí** (`QueryExecutionService#killBackend`) — abre una
  conexión NUEVA del mismo pool de esa base (la conexión original está
  ocupada corriendo la consulta larga) y manda
  `SELECT pg_cancel_backend(<pid>)` o `KILL <spid>`. El pid/spid es
  siempre un entero leído de la propia base (nunca texto de usuario), así
  que no hace falta escaparlo. Cualquier error acá (la sesión ya terminó
  sola, el spid ya no existe) se traga y se registra solo en la consola
  (`System.err`) — no es un error real para el usuario, `Statement.cancel()`
  ya hizo su trabajo o la consulta ya había terminado.

**Límite conocido, a propósito:** el comando de respaldo necesita una
conexión NUEVA del pool de esa base. Si el pool está saturado — por
ejemplo `poolSize=1` y esa única conexión es justo la que está corriendo
la consulta que se quiere matar — conseguir esa conexión nueva puede
tardar hasta el `connectionTimeout` de HikariCP (o no conseguirla nunca
mientras la consulta original siga viva). No se armó una conexión
administrativa aparte/siempre disponible por base para evitar este caso
— hubiera sido bastante más complejidad (ciclo de vida propio, duplicar
credenciales) para un caso límite; la recomendación práctica es dejar
`poolSize >= 2` en Preferencias si se depende de este respaldo.

### Persistencia de credenciales — DPAPI (2026-08-20)

`CredentialStore` (usuario/contraseña por base + default de sesión) vivía
solo en memoria — a propósito, porque el diseño original decía
"credenciales en el almacén de claves del sistema, nunca en JSON plano" y
meterlas en `connections.json` sin cifrar hubiera sido una regresión de
seguridad. Ahora sí persisten, cifradas, en un archivo aparte.

- **`CredentialVaultStore`** (paquete `com.faro.app.data`, mismo patrón
  que `ConnectionRegistryStore`: `save`/`load` estáticos, todo de una
  sola vez sin diffs incrementales) — serializa `CredentialStore` a JSON
  (vía Gson, igual que el resto del proyecto) y cifra esos bytes con
  DPAPI (Windows Data Protection API) antes de escribirlos a
  `~/.faro/credentials.dat`. Al cargar, hace lo inverso: descifra y
  parsea. `CredentialStore#entries()` (nuevo, vista de solo lectura del
  mapa interno) es lo único que se agregó a `CredentialStore` en sí —
  esa clase sigue sin saber nada de cifrado ni de disco, la persistencia
  vive aparte a propósito.
- **DPAPI vía `Crypt32Util`** (`jna-platform`, dependencia nueva en
  `pom.xml`) — `cryptProtectData()`/`cryptUnprotectData()` cifran/
  descifran atado a la cuenta de Windows del usuario actual. Copiar
  `credentials.dat` a otra máquina, o leerlo con otra cuenta de Windows
  en la misma máquina, no sirve para descifrarlo — Windows gestiona la
  clave, Faro no maneja ninguna clave propia.
- **Decisión confirmada con el usuario:** DPAPI en vez de integrar el
  Administrador de credenciales de Windows completo (`CredWrite`/
  `CredRead`) — ese camino necesita bindings JNA a mano de la struct
  nativa `CREDENTIALW` (marshaling de punteros/UTF-16), bastante más
  código y más superficie de error que `Crypt32Util`, que ya viene listo
  en `jna-platform` sin structs propios. El costo: las credenciales de
  Faro NO aparecen en Panel de Control → Administrador de credenciales,
  viven en su propio archivo cifrado, gestionadas solo desde Faro.
- **Carga/guardado** (`MainController#loadCredentials`/`#shutdown`) —
  mismo patrón que `ConnectionRegistryStore`: se carga al arrancar si el
  archivo existe (si no se pudo descifrar — ej. perfil de Windows
  distinto — sigue con `CredentialStore` vacío en vez de tronar el
  arranque) y se guarda al cerrar la ventana.

**Límite conocido:** sin guardado incremental — igual que
`connections.json`, solo se guarda al cerrar la ventana, así que un
cierre anormal (proceso matado) pierde las credenciales nuevas/editadas
de esa sesión.

### Edición rápida — buscar, formatear, aviso de cambios sin guardar (2026-08-20)

Tres mejoras del editor SQL, de bajo riesgo (ninguna toca el layout de la
ventana) — el primer bloque elegido explícitamente de lo que seguía
pendiente en el `MenuBar`.

**Buscar en el script (Ctrl+F)** — una sola barra de búsqueda compartida
(`findBar`/`findField`/`findStatusLabel` en `main-view.fxml`, oculta
—`visible`/`managed` en `false`— hasta que se abre) que busca siempre en
el `CodeArea` de la pestaña activa, insensible a mayúsculas.
"Siguiente"/"Anterior" (`MainController#findInCurrentTab`) buscan desde
la posición del cursor y dan la vuelta al principio/final del texto si
no hay más resultados en esa dirección — búsqueda circular, no un "sin
resultados" apenas se pasa del final. Enter en el campo es "Siguiente"
(`onAction` del propio `TextField`); Esc cierra la barra y devuelve el
foco al editor.

**Formatear SQL (Ctrl+L)** — `SqlFormatter` (paquete `com.faro.app.query`,
clase nueva) pone en mayúscula las palabras clave reconocidas y agrega
un salto de línea antes de las cláusulas principales (SELECT/FROM/
WHERE/GROUP BY/ORDER BY/HAVING/UNION/los JOIN calificados con LEFT/
RIGHT/INNER/OUTER/FULL/CROSS). **Es una heurística por patrón de
tokens, no un parser SQL completo** — mismo criterio que la heurística
de solo lectura de `QueryExecutionService`. Lo que sí importa de
verdad: **nunca toca el contenido de literales de texto, identificadores
entre comillas/corchetes, ni comentarios** — un tokenizador dedicado
(expresión regular con alternancia: comentario de bloque, comentario de
línea, cadena entre comillas simples con `''` como comilla escapada,
identificador entre comillas dobles, identificador entre corchetes, o
una palabra suelta) reconoce esos tramos y los copia tal cual, sin
mayúsculas ni saltos de línea adentro — cambiarlos silenciosamente
corrompería datos reales de la consulta (ej. un literal de texto que
dice "from"), que es peor que no formatear nada. Verificado a mano
(script de prueba aparte, no quedó como test automatizado) con una
consulta que mezcla un literal con `''` escapado y las palabras SELECT/
FROM/WHERE dentro de un comentario de línea y uno de bloque — ninguna de
las dos zonas protegidas se tocó, solo el SQL real de afuera. **Límite
conocido:** un `JOIN` sin calificar (sin LEFT/RIGHT/INNER/OUTER/FULL/
CROSS antes) no fuerza su propio salto de línea — evita a propósito el
caso contrario, más feo, de partir `LEFT JOIN` en dos líneas sin tener
que rastrear cuál fue el token anterior.

**Aviso de "¿guardar cambios?"** — antes, cerrar una pestaña con cambios
sin guardar (o la ventana entera) los perdía en silencio. Ahora
`QueryTabState` (clase interna de `MainController`) trackea un flag
`dirty` vía un listener en `codeArea.textProperty()` — agregado
DESPUÉS de cargar el texto inicial de la pestaña (demo, archivo abierto,
o resultado de Formatear SQL) para no marcarla como modificada por
error; el título de la pestaña se le antepone "● " la primera vez que
cambia. Tres caminos distintos disparan la confirmación
(Guardar/Descartar cambios/Cancelar —
`MainController#confirmSaveOrDiscard`) si hay cambios sin guardar:
- Cerrar una pestaña individual (`Tab#setOnCloseRequest`).
- Cerrar toda la ventana — la X del sistema operativo o Alt+F4 nativo
  (`Stage#setOnCloseRequest`, nuevo en `Main.java`;
  `MainController#confirmCloseAllTabs` pregunta una vez por cada
  pestaña con cambios pendientes, en orden, hasta que alguna se
  cancela).
- "Archivo → Salir" (`onExit`) — `Platform.exit()` **no** dispara
  `Stage#setOnCloseRequest` (eso solo pasa con la X nativa/Alt+F4), así
  que sin este chequeo aparte el menú se saltaba el aviso por
  completo — hallazgo real encontrado durante esta misma
  implementación, no algo que ya se supiera de antes.

### Autocompletado (2026-08-20)

Editar → Autocompletado (Ctrl+Espacio, `MainController#onAutocomplete`,
`SqlAutocomplete` nuevo en `com.faro.app.ui`) — sugiere palabras clave
SQL que empiecen con lo que se esté escribiendo justo antes del cursor,
en un `ContextMenu` posicionado ahí mismo. Navegación con flechas/Enter
la trae `ContextMenu` de fábrica, no hubo que programarla.

- **Reusa `SqlFormatter.KEYWORDS`** (expuesto público a propósito para
  esto — antes era `private`) en vez de mantener una segunda lista de
  palabras clave separada que se pudiera desincronizar de la que ya usa
  Formatear SQL.
- **Deliberadamente limitado a palabras clave** — no sugiere nombres de
  tabla/columna reales. Eso necesitaría inspeccionar el esquema de una
  base ya conectada en vivo (y decidir de cuál base, si hay varias
  marcadas a la vez) — una función bastante más grande, no se construyó
  en este paso.
- Posicionamiento vía `CodeArea#getCaretBounds()` (devuelve
  `Optional<Bounds>` en coordenadas de pantalla) y reemplazo de texto
  vía `CodeArea#replaceText(int, int, String)` — **API verificada
  leyendo el jar real de RichTextFX 0.11.3** (`javap` contra las clases
  compiladas, no de memoria — mismo criterio de "no adivinar" que el
  resto del proyecto), no documentación de terceros que pudiera estar
  desactualizada para esta versión exacta.

**Verificado en vivo (2026-08-20) — el usuario lo probó y encontró un
bug real, ya arreglado.** El popup se quedaba "pegado" en pantalla en la
posición vieja si el cursor se movía a otra palabra (ej. con las
flechas, para corregir algo escrito antes) sin haber elegido ninguna
sugerencia — `ContextMenu` solo se autocierra con un clic afuera o
pérdida de foco, no con el cursor moviéndose dentro del mismo
`CodeArea`. Arreglado con `SqlAutocomplete#dismissOnCaretMove`: apenas
se muestra un popup, se engancha un listener de una sola vez a
`codeArea.caretPositionProperty()` que lo cierra en cuanto el cursor se
mueve por cualquier motivo; además, pedir un autocompletado nuevo
mientras uno anterior sigue sin resolver (`activeMenu`) cierra el viejo
primero, para que nunca queden dos superpuestos. **Sigue sin
reconfirmar tras este arreglo específico** — el bug original sí se vio
y se reprodujo en vivo (screenshot real del usuario: "SELT * FRO" con
el popup de "FROM" abierto), pero el fix en sí todavía no se probó de
nuevo corriendo la app.

### Favoritos, historial y el panel Ver (2026-08-20)

El diseño original contempla un "riel de paneles" fijo a la izquierda
con Conexiones/Historial/Favoritos como íconos intercambiables (Ver →
Alt+1/2/3), no pestañas con texto.

**Primera versión: pestañas, no riel — corregido (2026-08-20).** La
primera implementación usó un `TabPane` de 3 pestañas con texto en vez
de un riel de íconos, documentado en su momento como "diferencia visual
aceptada a propósito" para reducir riesgo. El usuario probó la app,
dijo que el diseño oscuro "se veía culero", y mandó capturas de
`faro-java-prototipo.html` con el toggle **"Mapa JavaFX" activado** (el
overlay que rotula cada zona del prototipo con el control JavaFX real
que le corresponde) — ahí quedó claro que el riel de íconos SÍ importaba
para la fidelidad visual, no era un detalle menor. Se reconstruyó de
verdad: el panel izquierdo (`main-view.fxml`, `<left>`) ahora es un
`HBox` con dos partes — una franja angosta fija de 44px
(`.icon-rail`/`.rail-button`, `connectionsRailButton`/
`historyRailButton`/`favoritesRailButton`, tres `ToggleButton` en un
`ToggleGroup` compartido más `settingsRailButton` para Preferencias, un
`Button` normal fuera del grupo) y el panel de contenido ancho de
siempre (`panel-left`, 280px) con un `StackPane` de 3 `VBox`
(`connectionsPanel`/`historyPanel`/`favoritesPanel`) que se muestran/
ocultan con `visible`/`managed` — mismo patrón ya usado para la barra de
búsqueda del editor, no una técnica nueva. Íconos reales de Lucide
(`Icons.DATABASE`/`CLOCK`/`SETTINGS`, nuevos — construidos igual que
`SUN`/`MOON` antes: las elipses/círculos de `demo_html/icons.js` se
convirtieron a pares de arcos porque `SVGPath` no soporta
`&lt;circle&gt;`/`&lt;ellipse&gt;`, mismo problema ya resuelto una vez
este mismo día). El ícono activo se resalta con `--accent-soft`/
`--accent-base` (mismos valores reales verificados contra
`demo_html/styles.css` que el fix de `.toolbar-pill`, ver "Tema claro/
oscuro" más abajo) — clic en un ícono del riel, o Ver → Alt+1/2/3, dan
el mismo resultado y sincronizan cuál ícono queda resaltado
(`MainController#showLeftPanel`).

- **Conexiones** — el mismo árbol de siempre (`connectionTree`), ahora
  dentro de la primera pestaña en vez de ser el único contenido del
  panel. Nada de su comportamiento cambió, solo dónde vive.
- **Historial** — lista en memoria (`queryHistory`, `ObservableList<String>`,
  tope de `MAX_HISTORY = 50`, sin duplicados consecutivos) que se llena
  cada vez que "Ejecutar" corre una consulta de verdad
  (`MainController#addToHistory`, llamado desde `onRunQuery`). Cada
  fila se muestra resumida a una sola línea y máximo 80 caracteres
  (`MainController#summarize`) — el SQL guardado en la lista conserva
  sus saltos de línea reales, el resumen es solo para que la celda no se
  vea rota. Doble clic en una fila abre ese SQL en una pestaña nueva.
  **No persiste entre sesiones** — se pierde al cerrar la app, a
  propósito, para no sumar un tercer archivo/formato de persistencia
  bajo presión de tiempo en esta misma sesión; queda como límite
  conocido, no un descuido.
- **Favoritos** — `Favorite` (record: id/nombre/sql) + `FavoritesStore`
  (paquete `com.faro.app.data`, nuevos). "Consulta → Guardar como
  favorito" (también el botón "Favorito" de la barra de herramientas,
  ambos van a `MainController#onSaveFavorite`) piden un nombre
  (`TextInputDialog`) y guardan el SQL de la pestaña activa tal cual
  está en ese momento. La pestaña Favoritos del panel izquierdo lista
  los guardados con dos botones: "Abrir" (nueva pestaña con ese SQL) y
  "Eliminar". **Sí persiste entre sesiones** — a diferencia del
  historial, `FavoritesStore` se guarda/carga junto con
  `ConnectionRegistry`/`AppPreferences` en el mismo
  `~/.faro/connections.json` (`ConnectionRegistryStore`, señal actualizada
  para aceptar también `FavoritesStore` — ver su javadoc actualizado).
  No tiene nada sensible que proteger (son solo scripts con un nombre),
  así que no necesitó un archivo cifrado aparte como las credenciales.

### Explicar plan de ejecución (2026-08-20)

"Consulta → Explicar plan de ejecución" (`MainController#onExplainPlan`,
`QueryExecutionService#explain`) — a diferencia de "Ejecutar", que corre
en paralelo contra TODAS las bases marcadas, esto corre solo contra la
**primera** base marcada (si hay varias, se avisa por el log de
Diagnóstico cuál se usó) — un plan de ejecución es por naturaleza
específico de una base/instancia, mezclar planes de varias bases en una
sola tabla no tendría sentido. El resultado reemplaza el contenido de la
pestaña Resultados, igual que una ejecución normal.

- **Postgres**: manda `EXPLAIN <consulta>` tal cual — Postgres devuelve
  el plan como filas de texto normales, sin necesitar ningún modo
  especial de conexión.
- **SQL Server**: activa `SET SHOWPLAN_ALL ON`, manda la consulta (el
  motor devuelve el plan en vez de correrla de verdad mientras ese modo
  está activo), y desactiva `SHOWPLAN_ALL` después, en un `finally`.
- **No pasa por la validación de `ServerMode.READ_ONLY`** — a propósito,
  no es un descuido: `EXPLAIN` solo (sin `ANALYZE`) nunca ejecuta la
  sentencia de verdad en ningún motor, así que no hay nada que proteger
  ahí; una base "solo lectura" puede pedir el plan de cualquier consulta
  sin riesgo real.

**Salvedad importante, léela antes de confiar en esto:** esta función se
escribió con el comportamiento documentado de cada driver/motor, pero
**no se probó contra un servidor Postgres o SQL Server real** — a
diferencia de casi todo lo demás en este README, que si dice "resuelto"
es porque se verificó en vivo contra la base de pruebas "crisol" o se
compiló y se corrió. `SHOWPLAN_ALL` en particular tiene reglas estrictas
en SQL Server (por ejemplo, no puede combinarse con ciertos otros
comandos en el mismo batch) que solo se conocen por documentación, no
por haberlo ejecutado. Pruébalo con cautela la primera vez.

### Importar/Exportar configuración (2026-08-20)

Conexiones → Importar/Exportar configuración… (`MainController#onImportConfig`/
`onExportConfig`) — reusan `ConnectionRegistryStore.save`/`load` (los
mismos métodos que ya persistían `~/.faro/connections.json` al cerrar la
app), apuntando a un archivo elegido por el usuario en vez del archivo
fijo de siempre. Exportar guarda servidores + bases + preferencias +
favoritos; importar reemplaza el árbol de conexiones y los favoritos
actuales por completo (no hace merge). **Nunca incluye credenciales** —
mismo criterio que la persistencia normal, `CredentialVaultStore` es un
archivo aparte y cifrado que ningún camino de import/export toca; llevar
esta configuración a otra máquina significa volver a cargar usuario/
contraseña de cada base ahí, a propósito.

### Tests automatizados (2026-08-20)

18 tests JUnit 5 reales (antes: cero, `junit-jupiter` declarado en
`pom.xml` sin un solo archivo bajo `src/test/`) — corren con
`mvn test`, todos pasan (`mvn -q clean test`, verificado). Cubren la
lógica pura que no depende de JavaFX ni de una conexión a una base real:

- `CsvParserTest` (4) — campos simples, campo entre comillas con coma
  adentro, comilla escapada `""`, líneas en blanco ignoradas.
- `SqlFormatterTest` (5) — mayúsculas + saltos de línea, `LEFT JOIN` no
  se parte en dos líneas, literales de texto y comentarios nunca se
  tocan (los dos casos de riesgo real del formateador, ver "Edición
  rápida" arriba).
- `CredentialStoreTest` (4) — override→default→vacío y sus variantes
  (sin ninguno de los dos, solo default, override gana sobre default,
  quitar el override cae de vuelta al default).
- `DatabaseEntryTest` (3) — `jdbcUrl()` para Postgres y SQL Server, y
  que refleje ediciones en vivo (host/puerto nuevos).
- `ConnectionRegistryTest` (2) — `allDatabases()` aplana servidores +
  sin grupo, `withDemoData()` nunca vacío.

**Lo que sigue sin cobertura, a propósito** (necesitaría un entorno más
pesado que "lógica pura en aislamiento"): todo lo que toca JavaFX
directamente (controladores, `TableView`/`TreeView`, diálogos —
necesitaría TestFX o similar, no se agregó), y todo lo que necesita una
base de datos real (`QueryExecutionService`, `ConnectionPoolManager`,
`CsvImportService`, `DiscoveryService` — necesitaría una base de
pruebas embebida o mocks de JDBC, tampoco se agregó). Ninguna de las dos
cosas es una regresión de esta sesión — simplemente nunca hubo tests de
ningún tipo antes de hoy.

### Empaquetado (2026-08-20)

Perfil Maven nuevo `package` (`pom.xml`) — **no corre en el build
normal a propósito**, `mvn compile`/`mvn test`/`mvn javafx:run` quedan
exactamente igual de rápidos que siempre. Se activa explícitamente:
`mvn -Ppackage clean package` — verificado, produce `target/faro-app.jar`
(~17 MB, confirmado con `unzip -l`: contiene todas las clases de la app,
los 2 drivers JDBC, HikariCP, RichTextFX, Gson, jna-platform, las 6
hojas FXML, `styles.css`/`styles-dark.css`, las 3 fuentes, y las
librerías nativas de JavaFX/JNA para Windows — `glass.dll`,
`prism_*.dll`, `javafx_font.dll`, `javafx_iio.dll`,
`com/sun/jna/win32-x86-64/jnidispatch.dll`, etc.), con
`Main-Class: com.faro.app.Main` ya puesto en el manifest.

**Por qué NO se usó `jlink`** (el goal `javafx:jlink` que ya trae el
plugin `javafx-maven-plugin` declarado en `pom.xml`) — jlink necesita un
grafo de módulos completo para poder recortar un runtime a medida. Este
proyecto no tiene `module-info.java` (a propósito) y ninguna de sus
dependencias de runtime (HikariCP, los 2 drivers JDBC, RichTextFX, Gson,
jna-platform) está modularizada tampoco — la documentación pública sobre
jlink con apps no modulares con dependencias no modulares la describe
como "problemática", justo este caso. Convertir cada dependencia a
módulo automático solo para poder jlinkear, con el riesgo real de
paquetes divididos o nombres en conflicto, no se justificaba frente a la
alternativa: un JAR único + `jpackage` directo, la forma estándar
documentada para apps JavaFX no modulares.

**El JAR se arma agregando las variantes `classifier=win` de
`javafx-controls`/`javafx-fxml`** (dependencias nuevas, solo dentro del
perfil `package` — las versiones sin classifier de la sección normal de
dependencias siguen siendo las que usa `javafx:run` para armar su propio
module-path) — esas sí traen las librerías nativas de Windows utilizables
directo desde el classpath sin módulos, necesarias para que
`java -jar faro-app.jar` funcione sin flags de módulo. `maven-shade-plugin`
(nuevo, 3.6.0) arma el JAR final, excluye `module-info.class`/archivos de
firma de las dependencias (evita conflictos de shading) y pone
`createDependencyReducedPom=false` (si no, el plugin deja un
`dependency-reduced-pom.xml` suelto en la raíz del proyecto en cada
build — encontrado y corregido durante esta misma implementación).

**Para el instalador final (`.exe`/app-image de Windows) — comando
documentado, NO ejecutado todavía:**

```
mkdir target\dist-input
copy target\faro-app.jar target\dist-input\
jpackage --type app-image --input target\dist-input --dest target\dist ^
  --name Faro --main-jar faro-app.jar --main-class com.faro.app.Main ^
  --app-version 0.1.0 --icon ..\flutter_faroapp\windows\runner\resources\app_icon.ico
```

`--type app-image` produce una carpeta (`target\dist\Faro\`) con
`Faro.exe` + un runtime Java completo embebido — se puede copiar a
cualquier máquina Windows sin instalar Java ahí, sin necesitar ningún
prerrequisito extra. Para un instalador de verdad (`.exe` con asistente
de instalación, o `.msi`), cambiar `--type app-image` por `--type exe` o
`--type msi` — **esos dos sí necesitan el WiX Toolset v3 instalado y en
el `PATH`** (prerrequisito real de `jpackage` en Windows para esos dos
formatos específicos, no para `app-image`), que no se verificó que esté
instalado en esta máquina. El ícono apunta al `.ico` que ya existe en
`flutter_faroapp/` (mismo ícono de la versión anterior) — no se copió
una versión propia dentro de `java_faroapp/` todavía, es una decisión
pendiente si se quiere que la carpeta sea autocontenida.

**Por qué esto quedó sin correr:** `jpackage` produce un instalador/
ejecutable nativo real — correrlo y de ahí abrir `Faro.exe` para
probarlo cruza a "lanzar la app", que le corresponde al usuario, no al
asistente. Se verificó todo lo que sí se pudo sin cruzar esa línea: que
el JAR compila, que trae todo adentro (drivers, fuentes, FXML, DLLs
nativas), y que el comando de `jpackage` documentado usa la sintaxis
real de la herramienta (no inventada) — falta que el usuario lo corra
una vez y confirme que el `.exe` resultante abre la ventana.

### Árbol de conexiones — historial de bugs de clic/layout (2026-08-20)

**Doble clic necesario (flecha, botón de editar):** causa raíz real, no una
corrección de CSS a ciegas — `ConnectionTreeCell` creaba nodos
(`HBox`/`Button`/`CheckBox`) **nuevos** en cada `updateItem()`. Si la celda
se reconstruye a mitad de un gesto de clic, el nodo que recibe
`MOUSE_PRESSED` deja de ser el mismo que recibe `MOUSE_RELEASED`, y JavaFX
solo sintetiza `MOUSE_CLICKED` cuando ambos ocurren sobre el mismo nodo.
Arreglado construyendo los nodos de cada fila **una sola vez** (en el
constructor) y actualizando solo su contenido en `updateItem()` — ver el
javadoc de la clase. Confirmado por el usuario.

**Parpadeo de toda la lista al hacer clic — RESUELTO (2026-08-20),
confirmado por el usuario en vivo.** Los primeros dos intentos atacaban
la altura de fila (ver "Descartado" más abajo) y no lo arreglaron. El
tercer intento, el que sí funcionó, atacó algo distinto: en
`ConnectionTreeCell#updateDatabaseRow`, cada llamada a `updateItem()`
desataba y reataba el binding bidireccional del `CheckBox`, y reescribía
las listas de `styleClass` de `statusDot`/`modeIcon` (`removeIf` +
`add`) **sin condición**, aunque el dato de esa fila no hubiera cambiado
en absoluto. Mutar un `ObservableList` de estilos —incluso si el
resultado final es idéntico al que ya había— dispara una repasada de
CSS real en ese nodo; JavaFX puede llamar `updateItem()` para la misma
fila varias veces seguidas sin cambio de dato real (pasadas de layout
del `VirtualFlow`, comportamiento documentado de los controles
virtualizados, no un supuesto) — si eso le pasa a la vez a varias filas
visibles, se vería exactamente como "toda la lista parpadeando". Se
arregló haciendo esas tres mutaciones condicionales: el `CheckBox` solo
se desata/reata si la propiedad a la que apunta cambió de verdad
(comparación de referencia contra la propiedad ya atada, siempre
seguro, nunca deja ver un valor viejo), y las listas de estilo solo se
tocan si la clase deseada todavía no está puesta.

**Diagnóstico confirmado, no solo teoría** — este cambio se armó por
lectura de código y conocimiento de cómo funciona `Cell`/
`ObservableList`/CSS en JavaFX (a diferencia de las dos teorías de altura
anteriores, que se armaron a ciegas sin ese razonamiento), y el usuario
confirmó corriendo la app que el parpadeo ya no pasa. La causa real
era, entonces, la mutación incondicional de `styleClass` (y el rebind
del checkbox) en cada `updateItem()` — no la altura de fila, que era la
teoría equivocada de los dos primeros intentos.

**Flecha de expandir/colapsar blanca/invisible al seleccionar un
servidor:** el `.arrow` por defecto de JavaFX (Modena) cambia a blanco en
`:selected` asumiendo un fondo de selección oscuro, pero acá es gris claro
(`#F1F5F9`). Arreglado forzando su color en `styles.css`
(`.connection-tree .tree-cell .tree-disclosure-node .arrow` con
especificidad igual/mayor a la regla `:selected`/`:hover` de Modena).

**Clic en el nombre de la base no hacía nada:** el usuario esperaba que
clickear el alias marcara/desmarcara la casilla de esa base. Agregado
`aliasLabel.setOnMouseClicked(...)` — **a propósito solo en `aliasLabel`**
(hermano de `checkBox`, no ancestro de toda la fila) para no causar un
doble-toggle que se cancele al hacer clic directo sobre la casilla.

**Descartado, no lo repitas si algún día vuelve a aparecer parpadeo en
otra parte de la UI — dos intentos confirmados en vivo, ninguno arregló
ESTE bug:** (1) `fixedCellSize="28"` por sí solo, probado aislado. (2)
Fijar `prefHeight`/`minHeight`/`maxHeight` a juego con ese valor (ver
arriba), probado después — la teoría de "mismatch de altura" en
conjunto tampoco era la causa real. El tercer intento (mutaciones
condicionales de `styleClass`/checkbox en `updateItem()`, ver arriba)
**sí la era** — confirmado en vivo el 2026-08-20.

### Árbol de conexiones — indicadores sin explicación (resuelto, 2026-08-20)

El usuario preguntó, viendo la app corriendo, para qué servían el círculo
gris y el cuadrado verde/ámbar de cada fila — señal real de que eran
formas geométricas sin ninguna pista visual (mismo motivo que llevó al
ícono de lápiz de editar). Arreglado:
- El cuadrado de `modeIcon` (antes un `Region` de color sólido) es ahora
  un ícono real de candado (Lucide `lock`/`lock-open`, mismo lenguaje que
  `demo_html/icons.js`) — cerrado = solo lectura, abierto = sin
  restricciones, construido como `SVGPath` reusado (mismo patrón que
  `editIcon`, nunca recreado en `updateItem`).
- `Tooltip` instalado en `statusDot` y en el nuevo ícono de candado, con
  texto según el estado real (`"Conexión: nunca probada"`,
  `"Solo lectura"`, etc.) — ver `ConnectionTreeCell#statusTooltipText`.

### Barra de herramientas — F5 visible (resuelto, 2026-08-20)

El botón "Ejecutar" ahora muestra el atajo "F5" atenuado junto al ícono y
el texto (`MainController#runButtonGraphic`, `.run-button-shortcut` en
`styles.css`) — antes solo aparecía en el ítem del menú, no en el botón,
a diferencia del prototipo.

### Tema claro/oscuro (2026-08-20)

Sistema de theming real, no un mockup — `Theme.java` (paquete
`com.faro.app.ui`) es la única fuente de verdad de qué hoja de estilos
usar (`stylesheetResourcePath(boolean darkTheme)` devuelve
`/com/faro/app/styles.css` o `/com/faro/app/styles-dark.css`, rutas
absolutas de classpath). Las 6 ventanas de la app (principal + los 5
diálogos) la usan — ninguna trae ya su hoja fija por FXML
(`stylesheets="@styles.css"` se quitó de las 6 FXML; cada `Stage` la
agrega en Java según el tema activo al construir su `Scene`).

- **`styles-dark.css`** (archivo nuevo) — variante oscura generada a
  partir de `styles.css` con la paleta real de
  `flutter_faroapp/lib/core/theme/app_colors.dart`/`app_accent.dart`
  (mismo criterio de "no de memoria" que la pasada de fidelidad de
  diseño de arriba). Casi todos los valores vienen directo de ahí; una
  excepción queda documentada como inferida dentro del propio archivo
  (los grises personalizados `#94A3B8`/`#CBD5E1` sin entrada propia en
  `app_colors.dart` — no hay nada contra qué verificarlos, se mapearon a
  ojo a un gris intermedio).

**Bug real encontrado y arreglado (2026-08-20) — `.toolbar-pill` con
colores inventados.** El usuario probó el tema oscuro en vivo y reportó
que se veía mal; en vez de seguir adivinando, se comparó cada clase
visible en la captura contra `demo_html/styles.css`
(`[data-theme='dark']`, línea 76 en adelante — la copia 1:1 real de
`app_colors.dart`/`app_accent.dart` en CSS, no una interpretación). La
pastilla "N bases seleccionadas" (`.toolbar-pill`) usaba
`#242C4F`/`#6366F1` para fondo/texto — una aproximación sólida inventada
a mano en la primera versión de este archivo, escrita bajo el supuesto
(nunca verificado) de que JavaFX no podía pintar fondos
semitransparentes. Sí puede — `rgba()` ya se usaba en este mismo archivo
para la sombra de menús — así que no hacía falta aproximar nada. El
valor real es `rgba(129,140,248,.18)` de fondo y `#C7D2FE` de texto
(mucho más claro, mucho más contraste); la aproximación anterior tenía
fondo y texto casi del mismo tono de oscuro, ilegible. Se auditaron
también botones (primario/secundario/ícono, con sus estados hover/
pressed), resaltado de sintaxis del editor, tabla de resultados,
candados de modo del árbol, y pestañas de consulta — los cinco
coincidían con los valores reales, ese no era el problema.
- **`AppPreferences.darkTheme`** — persiste junto con el resto de
  preferencias en `~/.faro/connections.json`
  (`ConnectionRegistryStore`), así que el tema elegido sobrevive a
  cerrar la app.
- **Botón de la barra de herramientas** (`themeToggleButton`, ícono
  sol/luna de Lucide vía `Icons.SUN`/`Icons.MOON`) — cambia el tema en
  caliente (`MainController#onToggleTheme` → `applyCurrentTheme()`,
  que reemplaza la hoja de estilos de la `Scene` con `setAll(...)` y
  refresca el ícono para mostrar siempre la acción que va a pasar al
  hacer clic: luna en tema claro, sol en oscuro).
- **Preferencias → Apariencia** — el mismo interruptor, como combo
  Claro/Oscuro (`PreferencesDialogController`). Al presionar Guardar, si
  el valor cambió, corre un callback (`Runnable` que
  `MainController#onOpenPreferences`/`onShowShortcuts` pasan como
  `this::applyCurrentTheme`) para que la ventana principal se actualice
  en vivo sin tener que cerrar y reabrir nada — el toggle de la barra de
  herramientas y el de Preferencias son dos entradas al mismo estado, no
  dos sistemas separados.

**Limitación conocida, a propósito:** solo fondo/texto/bordes tienen
variante oscura — el acento de marca (índigo) es el mismo en los dos
temas (así lo define `app_accent.dart` también, no es un recorte del
lado Java). No hay selector de acento (rojo/verde/etc.) ni en Flutter ni
acá.

## Referencias que ya existen en el repo (no las repitas de memoria)

- `design_system/` (raíz del repo) — tokens de diseño originales de la
  primera versión (colores, tipografías, espaciados, radios); el prototipo
  Java ya migró estos mismos valores, así que esto es más que nada un
  respaldo histórico.
- `PROYECTO_DEFINICION.md` / `README.md` (raíz) — qué es Faro y su spec
  funcional original.
- `CONTEXTO_SESIONES.md` (raíz) — la bitácora completa de cómo se construyó
  y refinó cada función en Flutter — útil para no perder decisiones ya
  tomadas (ej. por qué el modo de una base de datos es independiente de su
  servidor, por qué las credenciales se resuelven como override→default→
  vacío, por qué "servidor" es solo un agrupador libre y opcional, etc.) al
  reimplementar el mismo comportamiento en Java.
- `AUDITORIA_CODIGO.md` (raíz) — bugs y limitaciones ya conocidas del driver
  de SQL Server en Flutter — el punto de partida de por qué existe esta
  carpeta, y la razón detrás de varias decisiones del diseño Java (ver
  arriba).
- `demo_html/` (raíz) — réplica visual/interactiva de la app **Flutter**
  actual, útil para comparar comportamiento exacto pantalla por pantalla
  contra el diseño Java nuevo.
