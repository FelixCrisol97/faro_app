# Optimización de rendimiento y memoria — análisis completo

Auditoría de `java_faroapp` enfocada en el problema real reportado: **uso alto de
RAM al correr consultas masivas** (varias bodegas a la vez, cientos de miles de
filas por base — el caso de uso central de Faro, no un caso extremo). Este
documento describe qué se encontró, qué se corrigió ya en el código, qué se
revisó y está bien tal como está, y qué queda como recomendación para el
futuro (con la razón concreta de por qué no se tocó en este pase).

**Fecha:** 2026-09-03. **Alcance:** todo `src/main/java`, `pom.xml`, y el
comando de empaquetado del `README.md`. **Verificación:** `mvn compile` y
`mvn test` (73/73 tests) en verde después de cada cambio — ver la sección
"Verificación" al final para el detalle exacto y sus límites.

---

## 1. Causa raíz del consumo de RAM en consultas masivas

### 1.1 El camino real de una fila, de JDBC a la pantalla (antes de este pase)

Cuando corres una consulta contra N bases marcadas, cada fila del resultado
pasaba por **dos representaciones en memoria completamente separadas**, una
detrás de la otra:

1. **`QueryExecutionService`** (`query/QueryExecutionService.java`) leía cada
   fila del `ResultSet` de JDBC hacia un `List<Object>` (una `ArrayList` por
   fila), y todas las filas de todas las bases se acumulaban en un
   `List<List<Object>>` (`QueryResult.rows()`).
2. **`ResultsTableFactory.populate(...)`** (`ui/ResultsTableFactory.java`),
   para poder mostrar esas filas en el `TableView` de la pestaña Resultados,
   **volvía a copiar cada fila, una por una**, en una lista nueva:
   ```java
   ObservableList<ObservableList<Object>> items = FXCollections.observableArrayList();
   for (List<Object> row : rows) {
       items.add(FXCollections.observableArrayList(row));   // ← copia #2, fila por fila
   }
   table.setItems(items);
   ```

Esto significa que, durante el instante en que termina una corrida masiva y se
puebla la tabla, **el resultado completo vivía dos veces en memoria a la
vez**: la lista original de `QueryResult` (que la lambda de `onRunQuery`
todavía tiene en el ámbito local) y la copia nueva que se le entrega al
`TableView`. Con 6 bodegas × 500,000 filas (3 millones de filas combinadas),
eso es exactamente el escenario que ya había producido un
`OutOfMemoryError` real documentado en `CONTEXTO_SESIONES.md`
(2026-08-22, en `onExportResultsCsv` — ya arreglado en su momento evitando
una tercera copia ahí; el problema de fondo, esta doble copia entre
`QueryExecutionService` y `ResultsTableFactory`, seguía intacto).

Encima de la duplicación, cada fila individual pagaba overhead de colección
**dos veces por capa**:

- Una `ArrayList<Object>` no es solo el arreglo de valores — es un objeto
  (~24 bytes de cabecera/contadores en un JVM de 64 bits con punteros
  comprimidos) **más** un `Object[]` interno aparte (~16 bytes de cabecera +
  4 bytes por referencia). Contenedor sin ningún valor real: ~80 bytes por
  fila de 10 columnas.
- Un `ObservableList` (lo que arma `FXCollections.observableArrayList(row)`)
  es más caro todavía — por dentro es una `ArrayList` (mismo costo de
  arriba) **envuelta** en infraestructura de listeners que ninguna fila de
  esta tabla usa nunca (ninguna celda escucha cambios de su propia fila,
  eso no es como está diseñado este `TableView`). Ese envoltorio son varios
  campos de referencia adicionales por instancia, siempre presentes aunque
  valgan `null`.

Ninguna de las dos capas era necesaria: una fila de resultado, una vez que
sale de JDBC, nunca cambia — no hay ningún caso de uso real donde una celda
de una fila ya mostrada necesite notificar un cambio a nadie.

### 1.2 El arreglo — un solo contenedor por fila, cero copias entre capas

**`QueryExecutionService`/`QueryResult`**: cada fila ahora es un `Object[]`
llano en vez de un `List<Object>` — un solo objeto (el arreglo mismo, sin
envoltorio de `ArrayList` aparte).

**`ResultsTableFactory.populate(...)`**: ya no copia fila por fila. Envuelve
la lista de filas que ya trae `QueryResult` **directo**, con un solo
`FXCollections.observableList(rows)` — un wrapper para TODA la tabla, no uno
por fila:

```java
table.setItems(FXCollections.observableList(rows));
```

`TableView` pasó de `TableView<ObservableList<Object>>` a
`TableView<Object[]>` (`ui/ResultsTableFactory.java`,
`MainController.resultsTable`), y el `cellValueFactory` de cada columna lee
por índice de arreglo (`row[columnIndex]`) en vez de `row.get(columnIndex)` —
mismo comportamiento observable, sin ningún cambio visible para el usuario
(incluye el mismo caso ya cubierto de "más/menos valores que columnas si se
mezclan motores distintos en una corrida", ahora comprobado contra
`row.length` en vez de `row.size()`).

**Impacto estimado** (cálculo de overhead por objeto en un JVM de 64 bits con
punteros comprimidos — sin `.hprof` real de por medio, ver la nota de
verificación en la sección 6):

| | Antes | Después |
|---|---|---|
| Contenedores por fila | 2 (`ArrayList` en `QueryResult` + `ObservableList` en la tabla) | 1 (`Object[]` compartido por ambos) |
| Overhead de contenedor / fila (10 columnas) | ~80 B + ~112 B ≈ **192 B** | **~56 B** |
| Copias del resultado completo vivas a la vez durante `populate()` | 2 (original + copia) | 1 |
| Para 3,000,000 filas combinadas (6 bodegas × 500K) | ~576 MB solo de overhead de contenedores, en el pico | ~168 MB, sin pico adicional |

Esto **no** cambia cuántas filas caben en memoria antes de un
`OutOfMemoryError` — sigue siendo un `TableView` con todo el resultado
cargado (ver la sección 7, "techo estructural"), pero sí sube
considerablemente cuántas filas caben antes de llegar a ese techo, y quita el
pico transitorio de doble copia que era la causa directa del `OOM` ya
reportado una vez.

**Archivos tocados:** `query/QueryResult.java`,
`query/QueryExecutionService.java`, `ui/ResultsTableFactory.java`,
`MainController.java` (el campo `resultsTable`, el bloque de
`applyCurrentTheme()` que vacía/repone los items al cambiar el tamaño de
fuente, y `onExportResultsCsv`). Ningún test cubre este camino (documentado
así desde antes — "no hay tests de `TableView`/controladores JavaFX"), así
que la verificación real de que la tabla se ve y exporta igual que antes
queda pendiente de que la corras tú una vez contra `bodegas-test` — ver la
sección 6.

---

## 2. Bug de concurrencia real encontrado (no relacionado con memoria)

Revisando cada punto donde el código muta una propiedad de JavaFX desde un
hilo de fondo (ya hay un patrón establecido en el proyecto para esto — ver
`DatabaseEntry`, que documenta explícitamente "las propiedades de JavaFX NO
son thread-safe para escribir desde un hilo que no sea el de la UI"),
encontré uno que no seguía ese patrón: `MainController#onTestAllConnections`
(el menú "Conexiones → Probar todas las conexiones").

El estado `TESTING` sí se ponía correctamente dentro de `Platform.runLater`,
pero **`CONNECTED`/`FAILED` — el resultado real de la prueba — se escribían
directo desde el hilo de fondo (`faro-test-all`)**, sin pasar por
`Platform.runLater`:

```java
// Antes — bug real:
try (Connection conn = DriverManager.getConnection(...)) {
    db.setConnectionStatus(DatabaseEntry.ConnectionStatus.CONNECTED);  // fuera del hilo de UI
    connected++;
} catch (SQLException e) {
    db.setConnectionStatus(DatabaseEntry.ConnectionStatus.FAILED);     // fuera del hilo de UI
    failures.add(...);
}
```

`connectionStatus` es un `ObjectProperty` que `ConnectionTreeCell` escucha
para repintar el punto de color (ver `CONTEXTO_SESIONES.md`, 2026-08-28) —
mutarlo fuera del hilo de JavaFX puede lanzar
`IllegalStateException: Not on FX application thread` de forma intermitente
(depende de si algo más está escuchando esa propiedad en el momento exacto),
o corromper el estado interno de los listeners sin ninguna excepción visible.
Es el mismo tipo de bug que `QueryExecutionService#runOne` ya evita a
propósito (con un comentario explícito sobre por qué usa
`Platform.runLater`) — este método nunca había igualado ese cuidado.

**Arreglado** envolviendo las dos escrituras en `Platform.runLater`, sin
tocar el resto de la lógica (el conteo `connected++` y la lista `failures`
siguen acumulándose en el hilo de fondo, que es seguro — son variables
locales/una lista propia de ese `Task`, no propiedades de JavaFX).

---

## 3. Optimización de arranque de la JVM (`pom.xml`, `README.md`)

Se agregaron dos banderas a las opciones de `javafx-maven-plugin` (junto al
`-Xmx4g` que ya existía) y al comando de `jpackage` documentado en el
`README`:

```
-XX:+UseG1GC -XX:+UseStringDeduplication
```

**Por qué:** G1 ya es el recolector por defecto en JDK 21+ (se deja
explícito para no depender de la ergonomía de cada máquina destino — el
`.exe` portable corre en máquinas de terceros, no solo en la tuya). La
deduplicación de `String` **solo** funciona con G1 activo, por eso van
juntas.

El caso de uso real de Faro — la misma consulta contra varias bodegas —
casi siempre trae columnas con valores de texto muy repetidos entre filas
(un estado, una categoría, un código de sucursal, un nombre de producto que
se repite en miles de líneas de venta). Cada `rs.getObject()`/`rs.getString()`
del driver JDBC crea un objeto `String` **nuevo** por cada fila, aunque el
contenido sea idéntico al de otra fila ya leída — el driver no los
comparte. Con esta bandera, el recolector detecta esos duplicados en
segundo plano (durante una recolección normal, sin pausa dedicada extra) y
los hace apuntar al mismo arreglo de caracteres interno. No reduce cuántos
objetos `String` existen, pero sí el peso real que cada uno ocupa —
completamente transparente, sin cambiar ningún comportamiento observable de
la app.

**Con qué margen se corrige:** esto es una ganancia "gratis" adicional
encima del arreglo de la sección 1, no un reemplazo — ayuda más cuanto más
repetitivos sean los valores de las columnas reales de tus bodegas (algo que
no puedo medir sin datos reales).

---

## 4. Lo que se revisó y está bien — para no generar alarma donde no la hay

Antes de tocar nada se auditó el resto del código en busca de fugas de
recursos y patrones de memoria problemáticos. Esto **ya estaba bien** y no
se tocó:

- **`ConnectionPoolManager`** — un `HikariDataSource` por base, `evict()` se
  llama tanto al editar una base (pool con credenciales/host viejos
  descartado) como al borrarla (`MainController#confirmAndDeleteDatabase`).
  Sin fugas de pools huérfanos encontradas.
- **`SchemaIntrospector`** (1132 líneas, la introspección de esquema más
  grande del proyecto) — cada `Connection`/`Statement`/`ResultSet` pasa por
  try-with-resources de forma consistente en las ~20 consultas distintas que
  arma (PostgreSQL y SQL Server, columnas/tipos/triggers/definiciones). Sin
  fugas de recursos JDBC encontradas. Sus cachés (`cache`,
  `columnDetailsCache`, `definitionCache`, `categoryCache`, `triggerCache`)
  están acotadas por tamaño de esquema (tablas/columnas/rutinas de la base),
  no por filas de datos — no crecen con el tamaño de una consulta, así que
  no son parte del problema de "consultas masivas".
- **`onExportResultsCsv`** — ya optimizado desde el 2026-08-22 (ver su propio
  comentario en el código): exporta fila por fila directo desde
  `resultsTable.getItems()`, sin copiar el resultado completo a una lista
  aparte antes de escribir. Con el cambio de la sección 1 este camino ahora
  además itera `Object[]` en vez de `List<Object>` — mismo criterio, un
  layer menos.
- **`CsvImportService`** — inserta en lotes de 500 dentro de una sola
  transacción (no arma un solo `INSERT` gigante ni mantiene el resultado
  completo de la inserción en memoria). Ver la sección 5 para el único
  punto real de este archivo que sí queda como recomendación (parseo
  completo del CSV de entrada).
- **`QueryExecutionService.execute`** — el `ExecutorService` por corrida se
  cierra siempre en un bloque `finally` (`executor.shutdown()`), sin
  importar si terminó bien, con error, o fue cancelado.
- **Hilos de fondo** (`faro-query-exec`, `faro-export-csv`,
  `faro-schema-fetch`, etc.) — todos marcados `setDaemon(true)`, no
  bloquean el cierre de la app.

---

## 5. Recomendaciones para el futuro — no implementadas en este pase

Estas quedaron **fuera de alcance a propósito**, con la razón concreta de
cada una. Ninguna es un bug activo — son mejoras de mayor tamaño/riesgo que
el arreglo de la sección 1, o cambios que necesitan una decisión de producto
antes de tocarse.

### 5.1 Techo estructural: el `TableView` sigue cargando el resultado completo

El cambio de la sección 1 baja el costo por fila, pero **no cambia el
diseño de fondo**: seguir usando un `TableView` de JavaFX significa que las
3 millones de filas de un resultado combinado grande siguen viviendo TODAS
en el heap de la JVM a la vez — `TableView` solo virtualiza qué se
**renderiza** en pantalla (las filas visibles), no qué se **guarda** en
memoria. No hay forma de bajar esto más sin uno de estos cambios de diseño,
ninguno trivial:

- **Paginar los resultados** — traer y mostrar de a N filas (ej. 10,000) con
  "siguiente/anterior", en vez del resultado completo de una vez. Cambia la
  experiencia de uso (ya no se puede hacer scroll libre por 3 millones de
  filas), necesitaría decidir el tamaño de página y qué pasa con "Exportar
  CSV" (¿exporta solo la página visible, o sigue trayendo todo para el
  archivo?).
- **Límite configurable de filas antes de cargar en memoria** (ej.
  "advertir/truncar sobre 500,000 filas combinadas", con la opción de seguir
  de todas formas) — menos invasivo que paginar, pero sigue siendo una
  decisión de producto (¿qué pasa con las filas truncadas? ¿el usuario
  pierde datos sin darse cuenta?).
- **Exportar a CSV en streaming directo desde el `ResultSet`**, sin pasar
  por el `TableView` en absoluto, para el caso de "solo quiero el archivo,
  no necesito verlo en pantalla" — evitaría cargar en RAM lo que de todas
  formas se va a escribir a disco. Cambiaría el flujo actual (correr →
  ver en Resultados → exportar) a algo como un botón aparte "Ejecutar y
  exportar directo".

Cualquiera de las tres es un cambio de UX que te toca decidir a ti, no algo
que se pueda "optimizar" sin tu decisión — por eso quedan como
recomendación, no como código ya hecho.

### 5.2 `CsvImportService` carga el CSV completo en memoria antes de insertar

`CsvParser.parse(file)` lee el archivo entero a `List<List<String>>` antes de
insertar cualquier fila (`query/CsvImportService.java:58`) — para un CSV de
importación realmente grande, mismo patrón de fondo que el problema de la
sección 1, aunque en un camino que el proyecto usa menos que las consultas
masivas (importar datos HACIA una tabla, no consultarlos). No se tocó
porque el diseño actual valida TODAS las filas (mismo número de campos que
el encabezado) **antes** de abrir la conexión — un CSV mal formado falla
limpio, sin dejar una transacción a medias. Pasar a streaming (leer y
validar fila por fila, insertar por lotes según se lee) necesitaría decidir
qué hacer si una fila a la mitad del archivo resulta inválida después de ya
haber insertado miles de filas anteriores — mismo tipo de decisión de
producto que la sección 5.1, no un arreglo mecánico.

### 5.3 `MainController` — clase de 2,432 líneas con demasiadas responsabilidades

No es un problema de memoria, es de mantenibilidad ("mejores prácticas",
parte de lo que pediste). `MainController` concentra: manejo de menú
completo, árbol de conexiones, pestañas de consulta, ejecución, resultados,
diagnóstico, preferencias, autoguardado, barra de estado — todo en una sola
clase. Dividirla (ej. un coordinador por pestaña "Ejecución"/"Resultados",
otro para el árbol de conexiones, otro para autoguardado/barra de estado)
mejoraría qué tan fácil es encontrar y tocar código sin miedo a romper algo
en otra parte, pero es un refactor de alto riesgo sin red de seguridad real
— el propio README es explícito en que no hay tests de controladores
JavaFX, así que cualquier división de esta clase solo se puede verificar
corriendo la app a mano, pantalla por pantalla, no con `mvn test`. Se dejó
fuera de este pase por eso: el costo de verificarlo bien es alto y el
beneficio es de mantenibilidad, no de rendimiento — no es lo que reportaste
como el problema real.

### 5.4 HikariCP — `minimumIdle` sin configurar

Por defecto, cuando `minimumIdle` no se fija explícitamente, HikariCP lo
iguala a `maximumPoolSize` — es decir, cada pool intenta mantener
**siempre** tantas conexiones abiertas hacia la base como el tamaño de pool
configurado, incluso cuando no hay ninguna consulta corriendo. Con muchas
bases configuradas (un caso real para Faro: varias decenas de bodegas), eso
son varias decenas de conexiones TCP abiertas en reposo contra distintos
servidores, todo el tiempo que la app esté abierta — más carga en los
servidores de base de datos que en la RAM de Faro mismo. No se tocó porque
es un trade-off real, no un bug: menos conexiones idle = pools más lentos
para responder al primer `Ejecutar` de una sesión (tienen que abrir la
conexión en ese momento en vez de tenerla ya lista). Bajar
`minimumIdle` por debajo de `poolSize` es una opción real si el número de
bases configuradas es grande y el patrón de uso es "ráfagas separadas en el
tiempo" más que "consultas todo el día seguido" — depende de cómo lo usas
tú en la práctica, por eso queda como recomendación a decidir, no como
cambio ya hecho.

---

## 6. Verificación

- **`mvn compile`** — verde, sin advertencias nuevas.
- **`mvn test`** — **73/73 tests en verde**, mismos que documentaba el
  `README` antes de este pase (ninguno de los archivos tocados tiene tests
  que dependan de la forma exacta de `List<Object>` vs `Object[]` — se
  confirmó con una búsqueda en `src/test` antes de hacer el cambio).
- **Lo que esta verificación NO cubre — sé honesto sobre esto**: ningún test
  automatizado toca `TableView`/`ResultsTableFactory` (documentado así desde
  antes en el `README`, no es algo nuevo de este pase). El cambio de la
  sección 1 compila y no rompe ningún test existente, pero la confirmación
  real de que la tabla de Resultados se ve y exporta exactamente igual que
  antes — y de que el consumo de RAM de verdad bajó, no solo en la
  estimación de la tabla de arriba — necesita que la corras tú contra datos
  reales. Dos formas concretas de hacerlo:
  1. **Visual, rápida:** `mvn javafx:run` contra `bodegas-test/` (el entorno
     Docker con 6 bases de prueba a ~500K filas cada una que ya existe en
     este repo, ver el `README` principal) — correr una consulta contra las
     6 a la vez, confirmar que Resultados se ve bien y "Exportar CSV"
     produce el mismo archivo de siempre.
  2. **Memoria real, con evidencia:** abrir `jconsole`/VisualVM (vienen con
     el JDK) apuntando al proceso de `mvn javafx:run` mientras corres esa
     misma consulta masiva, y comparar el pico de heap usado contra lo que
     recuerdes de antes de este cambio (o contra el código de un commit
     anterior a este, si quieres un antes/después exacto en la misma
     máquina).

Los números de la sección 1 (~576 MB → ~168 MB de overhead de contenedores)
son una **estimación por overhead de objeto en la JVM**, no una medición con
profiler real contra datos de tus bodegas — no tuve una base de datos real
corriendo en esta sesión para tomar un `.hprof` de antes/después. El
razonamiento (menos capas de colección, cero copia duplicada) es sólido y
verificable leyendo el código; el número exacto en MB para TU carga de
trabajo real solo lo vas a saber corriendo el paso 2 de arriba.
