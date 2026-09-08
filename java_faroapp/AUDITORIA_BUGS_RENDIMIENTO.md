# Auditoría exhaustiva — bugs y fallas de rendimiento

Revisión archivo por archivo de todo `src/main/java` (~9,700 líneas, 51 clases),
buscando bugs reales y problemas de rendimiento. **Fecha:** 2026-09-05.

Cada hallazgo trae: dónde está, qué falla exactamente (escenario concreto, no
"podría"), y qué tan seguro estoy de que es un problema real. Ninguno de estos
está corregido todavía — este documento es el diagnóstico; corregirlos es el
paso siguiente y hay que decidir en qué orden.

Complementa a `OPTIMIZACION_RENDIMIENTO.md` (pase anterior, enfocado solo en el
consumo de RAM del pipeline de resultados, ya corregido).

---

## Resumen — 12 hallazgos, los 12 cerrados el 2026-09-07

| # | Severidad | Qué | Dónde | Estado |
|---|---|---|---|---|
| 1 | **Alta** | El recorrido del árbol fuerza la carga de esquema de TODAS las bases y TODAS sus categorías — anula el diseño perezoso | `ConnectionTreeBuilder:128` | **Corregido** |
| 2 | **Alta** | "Probar conexión" congela la ventana entera hasta el timeout | `AddDatabaseDialogController:170` | **Corregido** |
| 3 | Media-alta | Importar configuración deja pools viejos vivos — se puede consultar el servidor equivocado | `MainController:1962` | **Corregido** |
| 4 | Media | Autoguardado escribe a disco + cifra DPAPI en el hilo de la UI cada 2 min | `MainController:2376` | **Corregido** |
| 5 | Media | El flag "cambios sin guardar" materializa el documento completo en cada tecla | `MainController:743` | **Corregido** |
| 6 | Media-baja | Log de Diagnóstico sin tope, con inserción O(n) al frente | `MainController:570` | **Corregido** |
| 7 | Baja | `CredentialStore` no es thread-safe (carrera latente) | `CredentialStore:21` | **Corregido** |
| 8 | Baja | Autocompletado O(n²) con esquemas grandes | `SqlAutocomplete:96` | **Corregido** |
| 9 | Baja | Buscar en el script copia todo el documento en cada F3 | `MainController:898` | **Corregido** |
| 10 | Baja | El pulso "en uso" sigue animando en celdas ya recicladas/vacías | `ConnectionTreeCell:727` | **Corregido** |
| 11 | Baja | Cambio de tema reconstruye todas las celdas del grid sin necesidad demostrada | `MainController:554` | **Corregido** |
| 12 | Info | `trustServerCertificate=true` fijo en toda conexión SQL Server | `DatabaseEntry:209` | **Implementado** |

### #11 y #12 — cerrados el 2026-09-07, a pedido explícito del usuario

- **#11 — bloque eliminado, y confirmado en vivo por el usuario ("todo bien con
  el punto 11", 2026-09-07).** Se quitó el `setItems(vacía)` + `setItems(la
  misma lista)` de `applyCurrentTheme()`. Era un intento fallido contra el bug
  del grid (texto de filas cortado al bajar el zoom) que el propio usuario
  había probado y descartado en su momento ("sigue igual, mismo
  comportamiento"); lo que cerró ese bug fue `ResultsTableFactory
  .SAFETY_MARGIN_PX`, que sigue intacto. Queda cerrada, entonces, la duda que
  había quedado abierta desde el 2026-08-26: **ese bloque no aportaba nada** —
  el margen fijo solo alcanza, verificado ahora con la app real sin él.
- **#12 — casilla por base implementada.** `DatabaseEntry
  .trustServerCertificate` (nuevo campo, default `true`), usado por
  `jdbcUrl()`; casilla "Confiar en el certificado del servidor" en el diálogo
  de Agregar/editar, deshabilitada cuando el motor es PostgreSQL (ahí la URL no
  negocia TLS); persistida en `connections.json`. `encrypt=true` sigue fijo y
  sin opción de apagarlo — desmarcar la casilla aprieta la validación del
  certificado, nunca quita el cifrado. La prueba de conexión del diálogo usa el
  valor de la casilla, para que no diga "Conectado" con una configuración
  distinta de la que se va a guardar.
  - **Compatibilidad:** un `connections.json` de antes de este campo no trae la
    llave y cae al default `true` — o sea, el mismo comportamiento que tenía
    cuando se guardó. Ninguna conexión existente cambia de conducta al
    actualizar. Cubierto por
    `ConnectionRegistryStoreTest#unArchivoViejoSinLaLlaveCaeAConfiarComoAntes`.
  - **Lo que sigue igual:** `DiscoveryService` (el escaneo de "Descubrir bases
    en esta IP…") sigue con `trustServerCertificate=true` fijo — ahí todavía no
    existe ninguna base registrada a la que asociarle la casilla; es un login
    de exploración contra `master` para listar nombres, no una conexión de
    trabajo. Las bases que ese escaneo agrega nacen con el default `true` y ya
    se pueden apretar una por una desde Editar.
  - **Bug de legibilidad encontrado al probarlo, y un bug real de fondo
    destapado en el camino** (2026-09-07, reportado por el usuario con captura,
    **confirmado arreglado por él: "ya se ve ok"**). El texto de la casilla no
    se leía en tema oscuro. Midiendo el color efectivo con una sonda (no a
    ojo) salieron DOS causas distintas:
    1. **Habilitada, el texto quedaba en `#333333`** — gris oscuro sobre fondo
       oscuro, invisible. `app.css` nunca había estilizado un `.check-box`
       fuera del árbol de conexiones, así que caía al color derivado de Modena,
       pensado para fondo claro. Nadie lo había visto porque la casilla nace
       deshabilitada (PostgreSQL es el motor por defecto del formulario), pero
       el texto habría desaparecido justo al elegir SQL Server, que es cuando
       la casilla sirve.
    2. **Deshabilitada**, el `-fx-opacity: 0.4` de Modena se multiplica con la
       del nodo de texto interno, dejándola más tenue que la nota de al lado
       pese a tener el mismo color.

    Arreglado en `app.css` (clases `trust-cert-check`/`trust-cert-hint`): color
    explícito por token del tema, y opacidad 1 en el estado deshabilitado —
    que sea el COLOR y no un desvanecido el que comunique "inactivo".
    Verificado con la sonda en los dos temas: oscuro `#F4F4F5` (habilitada) /
    `#A1A1AA` (deshabilitada); claro `#0F172A` / `#475569`.

---

## Cambio de comportamiento pedido explícitamente: nada se conecta al arrancar

Junto con el arreglo del #1, y **a pedido explícito tuyo** ("cuando inicia la
app no quiero que cargue todas las BD, ya que veo que se llena de pool de
conexiones si tengo muchas BD ya en la lista"), se eliminó por completo la
carga automática al arrancar — no se reemplazó por una versión "más liviana".

Abrir Faro ahora solo dibuja el árbol: **cero conexiones, cero pools**, sin
importar cuántas bases haya registradas. Una base se conecta solo cuando de
verdad se usa: expandirla en el árbol, correr una consulta contra ella, o
"Conexiones → Probar todas las conexiones".

**Lo que se pierde con esto, para que lo tengas presente:** el punto de color
de conexión ya no se auto-verifica al abrir la app. Arranca mostrando el estado
guardado de la sesión anterior y se confirma o corrige recién cuando esa base
se toca. Un verde guardado de una base cuya contraseña cambió desde entonces
seguirá en verde hasta que la expandas o la consultes — antes se corregía solo
a los pocos segundos, justo porque se conectaba a todas. Es el precio directo
de no abrir pools al arrancar; "Probar todas las conexiones" sigue siendo el
camino para refrescarlos todos a la vez, cuando tú lo pidas.

---

## Verificación de los arreglos

- **`mvn test` — 83/83 en verde** (los 73 de siempre + 10 nuevos, ver abajo).
- **3 tests nuevos** (`ConnectionTreeBuilderTest`) que fijan el contrato del
  hallazgo #1: que el recorrido del árbol no pida los hijos de una fila de
  base, y que una casilla independiente tampoco los toque al marcarse. El
  segundo test documenta el comportamiento real de JavaFX (un
  `CheckBoxTreeItem` NO independiente sí recorre a sus hijos) — verificado
  ejecutándolo, no supuesto.
- **7 tests nuevos para el #12** — 4 en `DatabaseEntryTest` (default en
  `true`, la URL con la casilla desmarcada, que `encrypt=true` nunca se apaga,
  y que en PostgreSQL la casilla no toca la URL) y 3 en
  `ConnectionRegistryStoreTest` (ida y vuelta marcada/desmarcada, y el archivo
  viejo sin la llave).
- **Carga real del FXML del diálogo verificada** — se corrió una vez un test
  temporal que arranca el toolkit de JavaFX y hace `FXMLLoader.load()` de
  `add-database-dialog.fxml` con la casilla nueva puesta (pasó; un `fx:id` mal
  escrito o un import faltante habrían tronado ahí y no en ningún otro test).
  Se borró después de correrlo: la suite permanente no arranca JavaFX, mismo
  criterio de todo el proyecto.
- **Corrida real de la app, con el log como evidencia** (2026-09-07 23:09) —
  con las 6 bases de `bodegas-test` registradas:
  - Arranque completo (`initialize()` → "Ventana principal mostrada"):
    **0 pools creados, 0 fetches de esquema**. Antes de estos arreglos, ese
    mismo arranque abría un pool por cada base registrada.
  - Los 6 pools aparecieron recién a los ~7 s, disparados por una ejecución
    real de consulta (`onRunQuery: 6 base(s) seleccionadas`) — o sea, por una
    acción explícita, que es justo el comportamiento buscado.
  - El único fetch de esquema de toda la sesión fue de **una sola** base
    (`[PostgreSQL 14] Estructura leída`), al expandirla en el árbol — no de
    las 6, y sin arrastrar las 4 categorías perezosas detrás.
- **Confirmado en vivo por el usuario:** el **#1** (con el log de la corrida
  real como evidencia, ver arriba), el **#11** ("todo bien con el punto 11",
  2026-09-07 — el grid se ve correcto sin el bloque que se quitó) y la
  legibilidad de la casilla del **#12** ("ya se ve ok", tras el arreglo de CSS
  descrito arriba).
- **Lo que sigue SIN verificar en vivo:** los arreglos #2 (prueba de conexión en
  hilo de fondo), #4 (autoguardado en segundo plano), #5, #6, #9, #10 y #12
  compilan y no rompen ningún test, pero su comportamiento es de UI/hilos y
  solo se confirma usándolos. Lo concreto a mirar cuando uses la app:
  1. **#2** — poner un host inexistente y darle "Probar conexión": la ventana
     tiene que seguir respondiendo, mostrar "Conectando…" y el botón quedar
     deshabilitado hasta que responda con el error.
  2. **#12** — desmarcar la casilla en una base SQL Server real y probar la
     conexión: si ese servidor usa certificado autofirmado, tiene que fallar
     con un error de certificado (eso confirma que la casilla hace efecto);
     volver a marcarla lo devuelve a como estaba.
  3. **#4/#5/#6** — dejar la app abierta más de 2 minutos con pestañas grandes,
     escribir en un script largo, y una sesión de trabajo normal.

---

## 1. [ALTA] El recorrido del árbol dispara la carga de esquema de todas las bases — y de sus 4 categorías perezosas

**Dónde:** `ui/ConnectionTreeBuilder.java:124-131`, junto con
`ui/DatabaseTreeItem.java:67-73` y `ui/CategoryTreeItem.java:53-59`.

**El problema.** `DatabaseTreeItem` y `CategoryTreeItem` implementan carga
perezosa sobrescribiendo `getChildren()`: la primera vez que alguien pide los
hijos, disparan el fetch JDBC. El javadoc de `DatabaseTreeItem` dice
explícitamente que ese diseño existe para que reconstruir el árbol
*no* dispare fetches:

> "así construir el árbol entero (que pasa seguido, ver
> `MainController#refreshTree`, cada tecla del buscador lo reconstruye
> completo) **nunca dispara un fetch JDBC por cada base que tenga**, solo las
> que el usuario de verdad expande."

Pero `collectDatabaseItems` recorre el árbol llamando `getChildren()` sobre
**todos** los nodos, incluidos los perezosos:

```java
private static void collectDatabaseItems(TreeItem<Object> item, List<CheckBoxTreeItem<Object>> out) {
    if (item instanceof CheckBoxTreeItem<Object> checkItem) {
        out.add(checkItem);
    }
    for (TreeItem<Object> child : item.getChildren()) {   // ← dispara requestSchema()/requestCategory()
        collectDatabaseItems(child, out);
    }
}
```

Cadena real: `collectDatabaseItems` → `DatabaseTreeItem.getChildren()` →
`requestSchema()`. Y una vez que la estructura ya está en caché, ese mismo
recorrido construye las 6 categorías y sigue bajando: `CategoryTreeItem
.getChildren()` → `requestCategory()` → **un fetch JDBC más por cada una de
Funciones, Procedimientos, Triggers y Tipos, de cada base**.

**Por qué importa tanto:** `collectDatabaseItems` no se llama una vez, se llama
todo el tiempo — `refreshTree()` (dos veces por llamada), `bindSelectedCount()`,
`bindSelectAllButtonText()`, `capturedSelectedDatabaseIds()`,
`applySelectedDatabaseIds()`, `onRunQuery()`, `onExplainPlan()`,
`onAutocomplete()`, `onSelectAllDatabases()`.

**Escenario concreto:** 20 bases registradas. Arranca la app → 20 fetches de
estructura. El usuario cambia de pestaña de consulta una vez (el listener de
`initialize()` llama `capturedSelectedDatabaseIds()`) → el recorrido encuentra
las estructuras ya cacheadas, arma las categorías y dispara **80 fetches JDBC
más** (4 categorías × 20 bases), ninguno pedido por el usuario. Cada tecla que
escriba en el buscador de bases vuelve a recorrer el árbol completo dos veces.

Esto anula por completo el trabajo de "esquema progresivo" del 2026-08-25 —
cuyo motivo documentado fue justamente que el árbol se quedaba pegado en
"Cargando esquema…" contra bases DEV grandes de cliente.

**Atenuantes reales** (por eso no es catastrófico hoy): `SchemaIntrospector`
dedupe por base (`loading`) y por base+categoría (`categoryLoading`), cachea
resultados, y su `schemaExecutor` limita a 3 fetches en paralelo. Así que no
son 80 conexiones simultáneas — son 80 consultas encoladas de a 3, una sola
vez cada una. Aun así: es tráfico JDBC y carga en los servidores del cliente
que nadie pidió, y la razón por la que expandir bases se siente lento la
primera vez.

**Cuidado al corregir:** la carga de esquema al arrancar es lo que sincroniza
el punto verde/rojo de conexión (feature pedida explícitamente el 2026-08-28).
Si simplemente se deja de recorrer los hijos, ese punto deja de auto-verificarse
al abrir la app. El arreglo correcto son dos piezas: (a) que
`collectDatabaseItems` no baje más allá de las filas de base (los nodos de
esquema nunca son `CheckBoxTreeItem`, así que cortar ahí no pierde nada), y
(b) disparar explícitamente al arrancar solo `SchemaIntrospector
.loadInBackground(...)` por base — la estructura, no las 4 categorías — que es
exactamente lo que la verificación de conexión necesita.

---

## 2. [ALTA] "Probar conexión" bloquea el hilo de la UI

**Dónde:** `ui/AddDatabaseDialogController.java:141-178`.

`onTestConnection()` es un handler `@FXML` — corre en el hilo de JavaFX — y
llama `DriverManager.getConnection(...)` **directo, sin `Task` ni hilo de
fondo**. Todo lo demás en la app que abre conexiones sí usa hilo de fondo
(`QueryExecutionService`, `DiscoveryService`, `onTestAllConnections`,
`SchemaIntrospector`); este es el único punto que no.

**Escenario concreto:** el usuario escribe una IP mal (o el servidor está caído
/ hay un firewall que traga los paquetes en vez de rechazarlos) y presiona
"Probar conexión". La ventana queda **completamente congelada** —sin repintar,
sin responder al mouse— hasta que el driver se rinda: 30 s con mssql-jdbc por
defecto, y con pgJDBC puede ser el timeout del sistema operativo (más de un
minuto en algunos casos de red). Windows la marca como "no responde".

Detalle extra que confirma el diagnóstico: la línea de arriba,
`setTestStatus("Conectando…", null)`, **nunca llega a verse** — cambia el texto
de la etiqueta pero el hilo se bloquea antes del siguiente pulso de render, así
que el usuario no recibe ninguna señal de que algo está pasando. El síntoma
visible es exactamente "le di clic y se congeló".

**Arreglo:** mismo patrón que ya usan los otros 4 caminos — envolver la prueba
en un `Task`, deshabilitar el botón mientras corre, y pintar el resultado en
`setOnSucceeded`/`setOnFailed`.

---

## 3. [MEDIA-ALTA] Importar configuración no descarta los pools de conexión viejos

**Dónde:** `MainController.java:1949-1971` (`onImportConfig`).

El método reemplaza el registro completo (`registry = ConnectionRegistryStore
.load(...).registry()`) pero nunca toca `ConnectionPoolManager`. Los pools
están indexados **por id de base** y se crean una sola vez con el host/puerto/
credenciales del momento (límite documentado en el javadoc de
`ConnectionPoolManager`, que por eso obliga a llamar `evict()` al editar una
base — `openEditDialog` y `confirmAndDeleteDatabase` sí lo hacen; importar, no).

**Dos fallas concretas:**

1. **Se consulta el servidor equivocado, en silencio.** Un archivo de
   configuración exportado conserva los ids. Si el usuario exporta en la
   máquina A, cambia el host de una base (o la reapunta a otro servidor) y
   vuelve a importar ese archivo en una sesión donde esa base **ya se usó**,
   el pool viejo sigue vivo con el host/credenciales anteriores — y como
   `getConnection` hace `computeIfAbsent(db.id(), ...)`, la próxima consulta
   reusa el pool viejo. El árbol muestra el host nuevo; la consulta corre
   contra el viejo. Sin ningún error visible.
2. **Fuga de conexiones.** Los pools de bases que ya no existen en la
   configuración importada quedan abiertos (con sus conexiones TCP a los
   servidores) hasta que se cierre la app — nadie los puede `evict` porque ya
   no hay ningún `DatabaseEntry` con ese id en el árbol.

**Arreglo:** llamar `pool.closeAll()` antes de reemplazar el registro (es una
operación explícita del usuario, poco frecuente — cerrar todo y dejar que se
reabra bajo demanda es lo más simple y correcto).

---

## 4. [MEDIA] El autoguardado escribe a disco y cifra en el hilo de la UI

**Dónde:** `MainController.java:2371-2378` + `2402-2412`.

`startAutosave()` programa un `TimerTask` que hace
`Platform.runLater(this::autosave)` — es decir, **todo el guardado corre en el
hilo de JavaFX**, cada 2 minutos: serializar el JSON completo (conexiones +
preferencias + favoritos + el **texto completo de cada pestaña de consulta
abierta**), escribirlo a `~/.faro/connections.json`, y después cifrar las
credenciales con DPAPI y escribir `credentials.dat`.

`Platform.runLater` es correcto para la **captura** (leer `codeArea.getText()`
de cada pestaña y las casillas del árbol solo se puede hacer en el hilo de la
UI) — pero la parte de I/O no tiene por qué estar ahí.

**Escenario concreto:** 6 pestañas abiertas con scripts largos, en un disco
ocupado o de red (perfil de usuario redirigido a un recurso de red, común en
entornos corporativos): la ventana da un tirón perceptible cada 2 minutos, en
un momento arbitrario, posiblemente a mitad de un tecleo. La app además ya
llama al mismo camino desde `shutdown()`, donde el bloqueo sí es aceptable.

**Arreglo:** capturar en el hilo de la UI (como ahora) y hacer la escritura en
un hilo demonio — un solo hilo serializado para no pisar dos guardados.

---

## 5. [MEDIA] El flag "cambios sin guardar" reconstruye el documento completo en cada tecla

**Dónde:** `MainController.java:743-748`.

```java
codeArea.textProperty().addListener((obs, oldText, newText) -> {
    if (!state.dirty) {
        state.dirty = true;
        tab.setText("● " + tab.getText());
    }
});
```

En RichTextFX, `textProperty()` no es un campo — es un valor derivado del
documento. Tener un listener registrado obliga a **materializar el texto
completo como un `String` nuevo en cada cambio del documento**, es decir en
cada pulsación de tecla. La documentación de RichTextFX lo advierte
explícitamente y recomienda `plainTextChanges()` para este caso.

**Escenario concreto:** una pestaña con un script grande (un dump de 2 MB
pegado, o un `INSERT` generado de miles de líneas): cada tecla asigna 2 MB de
`String` nuevo (más el `oldText` que también se materializa para pasarlo al
listener) que muere de inmediato. Se siente como latencia al escribir y hace
trabajar al GC sin ninguna razón.

Agravante: el listener **solo sirve la primera vez** (después `state.dirty` ya
es `true` y no hace nada), pero se queda registrado para siempre.

**Arreglo:** usar `codeArea.plainTextChanges().subscribe(...)` (no materializa
el documento, entrega solo el cambio) y cancelar la suscripción en cuanto
`dirty` pase a `true`.

---

## 6. [MEDIA-BAJA] El log de Diagnóstico crece sin tope y se inserta en O(n)

**Dónde:** `MainController.java:569-578`.

```java
diagnosticLog.add(0, new DiagnosticEntry(...));
```

Dos cosas: (a) **no hay tope** — a diferencia del historial de consultas, que
sí se corta en `MAX_HISTORY = 50`; (b) insertar en la posición 0 de una lista
respaldada por `ArrayList` desplaza todo el arreglo en cada entrada, y además
dispara un evento de cambio que hace al `ListView` recalcular.

**Escenario concreto:** una sesión larga de trabajo real (correr contra 20
bodegas, varias veces por hora, cada error de base genera su línea; más
credenciales guardadas, escaneos, pruebas de conexión) acumula miles de
entradas que nunca se liberan y hacen cada inserción progresivamente más cara.
No es un cuelgue, es degradación lenta y memoria que solo crece.

**Arreglo:** el mismo tope que ya usa el historial (recortar por la cola al
pasar de N), o invertir el orden de la lista y mostrar el `ListView` al revés.

---

## 7. [BAJA] `CredentialStore` no es thread-safe

**Dónde:** `data/CredentialStore.java:21-22`.

`private final Map<String, Credentials> byDatabaseId = new HashMap<>();` y
`private Credentials defaultCredentials;` (sin `volatile`). Se escribe desde el
hilo de la UI (diálogos de credenciales/editar base) y se **lee desde hilos de
fondo**: `credentials.resolve(db.id())` en `QueryExecutionService#runOne`, y en
cada `Task` de `SchemaIntrospector`.

**Honestidad sobre la severidad:** en la práctica casi todos los caminos reales
tienen una barrera de memoria implícita que salva el día — arrancar un
`Thread` nuevo (`new Thread(task, "faro-query-exec").start()`) o encolar en un
`ExecutorService` establece un *happens-before* con todo lo escrito antes en el
hilo de la UI. Por eso no tengo un escenario de fallo reproducible que ofrecer,
y por eso está en "baja". Sigue siendo una carrera de datos real por contrato
(el resto del proyecto sí es cuidadoso con esto — ver los `volatile` explícitos
y comentados de `DatabaseEntry`), y el arreglo es de una línea:
`ConcurrentHashMap` + `volatile` en `defaultCredentials`.

---

## 8. [BAJA] Autocompletado O(n²) con esquemas grandes

**Dónde:** `ui/SqlAutocomplete.java:95-110`.

```java
for (String name : schema.get().queryableNames()) {
    if (name.toUpperCase(...).startsWith(prefixUpper) && !matches.contains(name)) {
```

`matches` es un `ArrayList`; `contains` es lineal. Con una base de miles de
tablas/vistas y un prefijo corto (una o dos letras, que es justo cuando el
autocompletado sirve más), esto es cuadrático — más el `toUpperCase()` que
crea un `String` nuevo por cada nombre en cada invocación.

**Escenario concreto:** base DEV de cliente con ~3,000 tablas, el usuario
presiona Ctrl+Espacio tras escribir "c": ~3,000 `toUpperCase` + hasta ~3,000²/2
comparaciones en el hilo de la UI antes de que aparezca el popup. Perceptible.

**Arreglo:** un `LinkedHashSet` en vez de `ArrayList` (mantiene el orden de
inserción y hace `contains` O(1)).

---

## 9. [BAJA] Buscar en el script copia el documento entero en cada búsqueda

**Dónde:** `MainController.java:898`.

`String haystackLower = codeArea.getText().toLowerCase(Locale.ROOT);` — en cada
"buscar siguiente/anterior" se materializa el documento **dos veces** (el
`getText()` y la copia en minúsculas). Presionar F3 repetidamente sobre un
script grande asigna decenas de MB de basura.

**Arreglo:** cachear el texto en minúsculas mientras el documento no cambie, o
buscar con `String.regionMatches(true, ...)` sin copiar.

---

## 10. [BAJA] El pulso "en uso" sigue corriendo en celdas vacías

**Dónde:** `ui/ConnectionTreeCell.java:727-737` y la rama de celda vacía de
`updateItem`.

`inUsePulse` es un `FadeTransition` con `setCycleCount(INDEFINITE)`. Se detiene
correctamente cuando `inUse` pasa a `false`, pero cuando la celda se recicla a
vacía (scroll, o el árbol se reconstruye con menos filas) `updateItem` no llama
`refreshInUseAnimation(null)` — la animación sigue viva, ejecutándose en cada
frame sobre un nodo que ya no se muestra.

Impacto real: bajo (unas pocas animaciones huérfanas, cada una es una
interpolación trivial por frame), pero es trabajo por frame que no sirve para
nada y se acumula si pasa varias veces.

---

## 11. [BAJA] El cambio de tema/tamaño reconstruye todas las celdas del grid

**Dónde:** `MainController.java:554-556`.

```java
ObservableList<Object[]> currentItems = resultsTable.getItems();
resultsTable.setItems(FXCollections.observableArrayList());
resultsTable.setItems(currentItems);
```

El comentario del propio código es explícito en que **nunca se demostró que
esto sirva** ("el usuario confirmó 'sigue igual, mismo comportamiento' con
exactamente este código puesto"; lo que arregló el bug fue
`SAFETY_MARGIN_PX`). Se dejó "por si aporta algo marginal".

Costo real: dos eventos de cambio de lista sobre la tabla de resultados y una
reconstrucción completa de las celdas visibles, cada vez que se toca el tema,
el acento, el tamaño del editor o el slider de tamaño de interfaz. Con el
slider de Preferencias eso ocurre en cada paso del arrastre.

No es grave (los datos no se copian, solo se reconstruyen las celdas visibles),
pero es la clase de código que conviene quitar y confirmar en vivo — el propio
comentario ya lo propone como el primer candidato a eliminar.

---

## 12. [INFO / seguridad] `trustServerCertificate=true` fijo en SQL Server

**Dónde:** `model/DatabaseEntry.java:208-209` y `query/DiscoveryService.java:96`.

```java
case SQL_SERVER -> "jdbc:sqlserver://" + host + ":" + port + ";databaseName=" + databaseName
        + ";encrypt=true;trustServerCertificate=true";
```

`encrypt=true` cifra el tráfico, pero `trustServerCertificate=true` **desactiva
la validación del certificado** — es decir, acepta cualquier certificado,
incluido el de un intermediario. En una red de bodegas/sucursales confiable el
riesgo práctico es bajo, y sin esto habría que instalar el certificado del
servidor en cada equipo (por eso es una decisión razonable para una app
interna). Lo anoto porque no está documentado como decisión consciente en
ningún lado: hoy no hay forma de desactivarlo por base aunque el servidor sí
tenga un certificado válido.

**Sugerencia:** una casilla por base ("confiar en el certificado del servidor",
por defecto activada para no romper nada) lo dejaría explícito y permitiría
apretarlo donde sí se pueda.

---

## Lo que se revisó y está correcto

Para que quede claro qué NO es un problema (se auditó y salió limpio):

- **Manejo de recursos JDBC** — `SchemaIntrospector` (~20 consultas distintas),
  `QueryExecutionService`, `DiscoveryService`, `CsvImportService`: todos los
  `Connection`/`Statement`/`ResultSet` van en try-with-resources. Sin fugas.
- **Inyección SQL** — `CsvImportService` valida identificadores contra
  `SAFE_IDENTIFIER` antes de armar el `INSERT` (JDBC no permite parametrizar
  nombres de tabla/columna); `SchemaIntrospector` usa `PreparedStatement` con
  `?` para el esquema; el `KILL <spid>`/`pg_cancel_backend(pid)` usa un entero
  leído de la propia base, nunca texto del usuario. Correcto.
- **Credenciales** — nunca se escriben en `connections.json`, nunca se loguean
  (solo conteos), nunca se incluyen en importar/exportar configuración; en
  disco van cifradas con DPAPI. Consistente en todos los caminos.
- **Cancelación de consultas** — el margen de gracia antes del `KILL` (para no
  matar sesiones de SQL Server que `Statement.cancel()` ya resolvió) y el caso
  de "cancelar antes de que exista el `Statement`" están ambos cubiertos.
- **Reciclado de celdas** (`ExecutionTableFactory`, `ConnectionTreeCell`) —
  bindings y listeners se desatan antes de reutilizar la celda con otro ítem;
  no quedan bindings colgados de la corrida anterior.
- **Contador de generación del caché de esquema** — invalidar a mitad de un
  fetch en curso descarta correctamente el resultado viejo en vez de repoblar
  el caché recién limpiado.
- **Hilos** — todos los de fondo son demonio; el `ExecutorService` por corrida
  se cierra en `finally`; el pool de esquema está acotado a 3.

---

## Orden sugerido para corregir

1. **#2 (Probar conexión congela la ventana)** — el más visible para quien usa
   la app, y el arreglo es contenido a un método.
2. **#1 (carga de esquema forzada)** — el de mayor impacto en rendimiento
   real, pero hay que hacerlo junto con la carga explícita al arrancar para no
   romper el punto de estado de conexión.
3. **#3 (pools tras importar)** — una línea, y evita el peor escenario de
   todos: consultar el servidor equivocado sin darte cuenta.
4. **#4, #5, #6** — mejoras de fluidez, cada una acotada a un método.
5. **#7 a #11** — limpieza; ninguna urge.
6. **#12** — decisión tuya, no un arreglo mecánico.

**Verificación disponible:** los 73 tests de JUnit siguen cubriendo solo lógica
pura (parser CSV, formateador SQL, credenciales, esquema) — ninguno de estos 12
hallazgos está cubierto por un test, y varios (1, 2, 4, 10, 11) solo se pueden
confirmar corriendo la app real, porque son de comportamiento de UI/hilos. Los
hallazgos 1, 3, 5, 6, 8, 9 sí se pueden verificar leyendo el código y siguiendo
la cadena de llamadas, que es como se encontraron.
