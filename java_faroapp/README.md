# Faro — Java

Faro es una herramienta de escritorio que corre una consulta SQL contra varias bases de datos distribuidas a la vez (agrupadas libremente por "servidor" — una cadena de bodegas, un host central con varias bases de compañía, o cualquier otro agrupamiento útil), en vez de conectarse a cada una por separado. Esta es la reescritura completa en **Java + JavaFX**, reemplazo de la versión anterior en Flutter (`flutter_faroapp/`) — el driver de SQL Server en el ecosistema Dart/Flutter no tenía un cliente TDS puro maduro; Java sí tiene el driver JDBC oficial de Microsoft.

Este documento describe **qué hace la app hoy**, como referencia — no es un historial de cambios. Para el registro completo de cómo se construyó cada función, qué se intentó y no funcionó, y las decisiones de diseño con su razonamiento, ver `CONTEXTO_SESIONES.md` en la raíz del repo.

## Requisitos y cómo correr

- JDK 21+ (probado con JDK 25) y Maven.
- Desarrollo y ejecución en **Windows nativo** — WSLg no renderiza ventana JavaFX en algunos entornos; no es un problema de Faro, pero el camino recomendado es Windows directo.

```
mvn javafx:run          # arranca la app
mvn clean test           # corre los tests (JUnit 5, lógica pura sin JavaFX/BD)
mvn -Ppackage clean package   # arma un JAR único ejecutable (target/faro-app.jar)
```

El perfil `package` no corre en el build normal — `mvn compile`/`mvn test`/`mvn javafx:run` no lo tocan. Para un `.exe`/carpeta portable de Windows con el runtime embebido:

```
mkdir target\dist-input
copy target\faro-app.jar target\dist-input\
jpackage --type app-image --input target\dist-input --dest target\dist ^
  --name Faro --main-jar faro-app.jar --main-class com.faro.app.Launcher ^
  --app-version 0.1.0 --icon ..\flutter_faroapp\windows\runner\resources\app_icon.ico ^
  --java-options "-Xmx4g"
```

`--main-class com.faro.app.Launcher`, **no** `com.faro.app.Main` — `Main` extiende `javafx.application.Application`; un JAR sin módulos con esa clase como punto de entrada hace que la JVM rechace arrancar ("JavaFX runtime components are missing"). `Launcher` es una clase intermedia sin esa herencia que solo delega a `Main.main(...)` (ver su javadoc). `mvn javafx:run` no necesita esto — ese plugin arma su propio module-path.

`--type app-image` produce una carpeta (`target\dist\Faro\`, con `Faro.exe` + runtime embebido) copiable a cualquier máquina Windows sin instalar Java — **verificado corriendo el `.exe` real**, ventana y conexión a PostgreSQL/SQL Server confirmadas. Un instalador con asistente (`--type exe`/`--type msi`) necesita **WiX Toolset v3** instalado y en el `PATH`, que no viene con este repo.

**Al transportar la carpeta a otra máquina, comprímela con 7-Zip (o similar) en vez del compresor integrado de Windows ("Enviar a → Carpeta comprimida")** — este último puede dejar el zip incompleto sin ningún error visible cuando la carpeta tiene muchos archivos chicos anidados, como `runtime\` (~123 MB, 300+ archivos). Síntoma si pasa: `Faro.exe` en la máquina destino truena con `"Failed to find JVM in '...\runtime' directory."`. Verifica antes de transferir que el comprimido pese cerca de los ~50 MB esperados (el runtime comprime bien), no ~17 MB (eso significa que `runtime\` se quedó afuera).

## Arquitectura

- **UI**: JavaFX 21 + FXML + CSS. Una sola ventana principal (`main-view.fxml`/`MainController`) más 5 diálogos modales (Agregar/editar base, Credenciales, Descubrir bases, Importar CSV, Preferencias), cada uno con su propio FXML/controlador/`Stage`.
- **Datos/conexión**: JDBC directo (drivers oficiales pgJDBC y mssql-jdbc), pool `HikariCP` por base de datos (`ConnectionPoolManager`).
- **Editor**: `CodeArea` de RichTextFX (resaltado de sintaxis, números de línea, multi-pestaña).
- **Persistencia**: JSON (Gson) en `~/.faro/connections.json` para conexiones/preferencias/favoritos; credenciales aparte, cifradas con DPAPI, en `~/.faro/credentials.dat`.
- **Logging**: SLF4J + Logback, archivo rotativo en `logs/faro-app.log`.
- **Tests**: JUnit 5, solo lógica pura (nada que dependa de JavaFX o de una base real).

## Ventana y navegación

Barra de menú completa (Archivo/Editar/Consulta/Conexiones/Ver/Herramientas/Ayuda) con todos sus ítems conectados a una acción real, más una barra de herramientas con los atajos más usados (Ejecutar F5, Abrir, Guardar, Formatear, Favorito). Panel izquierdo con un riel de íconos fijo (Conexiones/Historial/Favoritos/Preferencias) que alterna qué panel se muestra en el mismo espacio, sin duplicar la lista de conexiones en varias pantallas.

## Árbol de conexiones y explorador de esquema

Árbol de servidores ("grupos", libres y opcionales) → bases de datos (`ConnectionTreeBuilder`/`ConnectionTreeCell`/`ConnectionRegistry`). Cada fila de base muestra: casilla de selección, punto de estado de conexión (con tooltip), alias (clic sencillo marca/desmarca la casilla, doble clic abre Editar) + `host:puerto` como segunda línea, candado de modo (clicable — alterna Solo lectura ↔ Sin restricciones directo, sin abrir ningún diálogo), badge de motor (PG/MSSQL), e ícono de editar siempre visible. Buscador de bases arriba del árbol, junto con "Todas"/"Ninguna" y "+" agregar, todo en una sola fila.

**Grupos** — "Conexiones → Nuevo grupo de conexiones…" crea un grupo vacío; clic derecho en una base → "Mover a grupo…" la mueve a un grupo existente, a "(Sin grupo)", o a uno nuevo (pide el nombre aparte).

**Estado de conexión, sincronizado con conexiones reales, no un botón aparte** — el punto de color se actualiza solo (es una propiedad reactiva de `DatabaseEntry`) cada vez que la carga de esquema o una ejecución de consulta prueban esa base de verdad: verde si conecta, rojo si falla (sin confundir un fallo de conexión con un error de SQL sobre una conexión que sí abrió bien). Persiste entre sesiones (solo verde/rojo, nunca el estado transitorio "Probando…") — al abrir la app se re-verifica solo en cuanto la carga de esquema toca esa base, así que un color guardado desactualizado (ej. contraseña que cambió) se corrige solo en segundos. Mientras una base tiene una consulta corriendo, su punto pulsa (fundido de opacidad en bucle).

**Explorador de esquema por base** (`SchemaIntrospector`, `DatabaseTreeItem`, `CategoryTreeItem`) — expandir una base en el árbol carga su estructura real vía JDBC:

- **Tablas y Vistas** se cargan de inmediato al expandir la base.
- **Funciones, Procedimientos, Triggers y Tipos** se cargan perezosos, uno por categoría — solo al expandir esa categoría específica, no de una sola vez. Cada categoría muestra su conteo real una vez cargada.
- Expandir una tabla/vista/tipo trae sus columnas (nombre + tipo) bajo demanda.
- Clic derecho sobre cualquier objeto → "Generar script CREATE" trae la definición real (`CREATE TABLE`/`CREATE VIEW`/`CREATE FUNCTION`/etc.) en una pestaña de consulta nueva.
- **Desambiguación de nombres repetidos**: PostgreSQL permite triggers con el mismo nombre en tablas distintas y funciones/procedimientos sobrecargados por firma — ambos casos se detectan y se muestran calificados (`tabla.trigger`, `función(tipo_arg)`) en vez de aparecer como filas idénticas indistinguibles.
- **Tipos personalizados** filtra correctamente los tipos compuestos reales de PostgreSQL (`CREATE TYPE ... AS (...)`) sin mezclar el tipo-fila automático que cada tabla/vista tiene internamente.
- Un mismo fetch de esquema alimenta también el autocompletado de tablas/columnas del editor SQL — no son dos sistemas separados.
- Un contador de generación por base invalida en segundo plano cualquier resultado en camino cuando se pide "Recargar esquema" a mitad de una carga, para no pintar datos obsoletos.

**Límite conocido**: solo el esquema por defecto de cada motor (`public` en PostgreSQL, `dbo` en SQL Server) — bases con tablas repartidas en varios esquemas custom no las muestra todas.

## Editor SQL

`CodeArea` con resaltado de sintaxis (palabras clave/cadenas/números/comentarios) y números de línea, en pestañas independientes (`Ctrl+T` nueva pestaña, "Abrir archivo .sql…" abre cada archivo en su propia pestaña, "Guardar"/"Guardar como…" sobre la pestaña activa). Cerrar una pestaña, la ventana, o "Archivo → Salir" con cambios sin guardar pregunta antes de descartarlos.

- **Buscar en el script** (`Ctrl+F`) — barra de búsqueda insensible a mayúsculas, circular (da la vuelta al llegar al final).
- **Formatear SQL** (`Ctrl+L`) — mayúsculas en palabras clave + salto de línea antes de las cláusulas principales; nunca toca el contenido de literales de texto, identificadores entre comillas/corchetes ni comentarios (tokenizador dedicado, no un reemplazo de texto ingenuo).
- **Autocompletado** (`Ctrl+Espacio`) — sugiere palabras clave SQL que empiecen con lo escrito antes del cursor. Limitado a palabras clave, no nombres reales de tabla/columna.
- **Zoom del editor** — `Ctrl +`/`Ctrl -`/`Ctrl 0` y `Ctrl` + rueda del mouse/trackpad, controla el tamaño de fuente SOLO del editor (`AppPreferences#editorFontSize`, también ajustable como spinner en Preferencias → Apariencia). Independiente del tamaño de fuente del resto de la interfaz (ver "Apariencia" más abajo).
- Clic derecho en una base del árbol → "Nueva consulta para esta base" marca esa base y abre una pestaña ya asociada a ella, sin tener que ir a buscarla después.
- **Cada pestaña recuerda su propia selección de bases** — las casillas marcadas en el árbol son POR PESTAÑA, no un estado global compartido: cambiar de pestaña cambia solas las casillas marcadas para reflejar la selección de esa pestaña. Una pestaña nueva (Ctrl+T/"+") hereda la selección de la que estaba activa; "Nueva consulta para esta base" y "Generar…" del explorador de esquema asocian la pestaña nueva a una sola base específica.
- **Las pestañas abiertas persisten entre sesiones** — texto del editor (incluyendo cambios sin guardar), archivo asociado si tiene, y su selección de bases se guardan al cerrar la app y se restauran al abrirla. Nunca se incluyen en "Importar/Exportar configuración" (mismo criterio que las credenciales).

## Ejecución de consultas

"Ejecutar" (F5) corre el SQL de la pestaña activa contra las bases marcadas EN ESA PESTAÑA (ver arriba), en paralelo (`QueryExecutionService`, hasta N bases a la vez — configurable en Preferencias), con un pool `HikariCP` propio por base. Si una base falla, no aborta a las demás — el error se acumula y se muestra.

- **Pestaña "Ejecución"** — lista plana (sin bordes ni formato de tabla), una fila por base marcada, estado en vivo (Ejecutando/Éxito/Error/Cancelado) + filas + tiempo, actualizada a medida que cada base termina, no todas al final. Alias/host tienen ancho fijo (para que las columnas queden alineadas entre filas) con tooltip mostrando el texto completo si se corta.
- **Cancelación real** — botón por fila o "Consulta → Cancelar ejecución" (menú), vía `Statement.cancel()`, con respaldo real `KILL <spid>` (SQL Server) / `pg_cancel_backend(pid)` (PostgreSQL) para cuando `cancel()` no alcanza a interrumpir la consulta en el servidor. El respaldo necesita una conexión libre en el pool de esa base — se recomienda `poolSize >= 2` si se depende de él.
- **Modo solo lectura** — una base marcada como tal rechaza cualquier sentencia que no empiece con SELECT/WITH/SHOW/EXPLAIN/DESCRIBE, antes de tocar la base. Es una heurística por primera palabra clave, no un parser SQL completo.
- **Explicar plan de ejecución** ("Consulta → Explicar plan…") corre solo contra la primera base marcada — un plan es específico de una base/motor. `EXPLAIN` en PostgreSQL, `SET SHOWPLAN_ALL` en SQL Server.
- **Fetch size configurable** (Preferencias → Rendimiento) — cuántas filas se traen por bloque al leer resultados grandes. Solo tiene efecto real en SQL Server por ahora; PostgreSQL lo ignora en autocommit (comportamiento del driver, no un bug de Faro).

## Resultados

`TableView` de columnas dinámicas (no se conocen hasta que corre la consulta) — con una columna inicial "Base de datos" cuando se consultó más de una a la vez, para poder rastrear el origen de cada fila. "Exportar CSV" corre en segundo plano (no bloquea la ventana), con el mismo criterio de escape de comillas/comas al leer un CSV con "Importar CSV a una tabla". La altura de fila sigue el tamaño de fuente efectivo de la interfaz (ver "Apariencia").

## Diálogos

- **Agregar/editar base de datos** — un formulario para las dos operaciones, prueba de conexión inline (con respaldo a credenciales por defecto si el campo de usuario está vacío), motor/modo/tamaño de pool/timeout.
- **Credenciales por defecto** — usuario/contraseña de sesión, usado cuando una base no tiene su propio override guardado. Resolución: override por base → default de sesión → vacío.
- **Descubrir bases de datos** — dado un host + usuario/contraseña, prueba conexión TCP a los puertos 5432/1433 y, si responden, hace login JDBC real para listar las bases visibles con ese usuario. Un host por búsqueda, no un rango de IPs.
- **Importar CSV a una tabla** — parser real (maneja comillas y comas dentro de campos), `INSERT` por lotes de 500 en una sola transacción. Sin inferencia de tipo propia (todo va como texto, apoyado en la conversión implícita del driver) ni soporte de saltos de línea dentro de un campo entre comillas.
- **"Probar todas las conexiones"** (menú Conexiones) — prueba cada base registrada (no solo las marcadas) con sus credenciales resueltas y actualiza el punto de estado de cada una en el árbol, mostrando cuáles fallaron y por qué.
- **Preferencias** — ver el detalle completo abajo.

## Preferencias

Tres pestañas, todas aplican y guardan de inmediato — no hay botones "Guardar"/"Cancelar", solo "Cerrar".

- **Rendimiento** — bases en paralelo al ejecutar, tamaño de pool y timeout por defecto de una base nueva, fetch size. Cada campo se guarda al perder el foco (Tab/clic afuera) o con Enter.
- **Atajos** — referencia estática de los atajos reales del menú (no editable).
- **Apariencia** — tema (claro/oscuro), color de acento (6 opciones), tamaño de fuente del editor SQL (spinner, 10–24px), y tamaño de fuente del resto de la interfaz (slider, -5..+5, aplica sobre los tamaños base de cada elemento). Los cuatro aplican en vivo apenas se interactúa con el control — tema y tamaño también se reflejan en la ventana de Preferencias mientras sigue abierta, no solo en la ventana principal.

**Nota técnica sobre el tamaño de fuente de la interfaz**: a diferencia de los colores (que sí usan variables CSS nativas de JavaFX, "looked-up values"), `-fx-font-size` no admite ese mecanismo — es una limitación real del parser CSS de JavaFX, no una limitación de diseño. El tamaño en vivo se resuelve regenerando en memoria una copia de la hoja de estilos con los tamaños ya desplazados, en vez de con variables.

## Persistencia

- **Conexiones + preferencias + favoritos + pestañas de consulta abiertas + estado de conexión (verde/rojo)** — `~/.faro/connections.json` (JSON plano vía Gson), se carga al abrir y se guarda al cerrar, con autoguardado cada 2 minutos por si la app se cierra de forma anormal.
- **Credenciales** — `~/.faro/credentials.dat`, cifradas con DPAPI (Windows Data Protection API, atadas a la cuenta de Windows del usuario — no portables a otra cuenta/máquina). Nunca en JSON plano, nunca incluidas en "Importar/Exportar configuración".
- **Historial de consultas** — en memoria únicamente, se pierde al cerrar la app (tope de 50 entradas, sin duplicados consecutivos).
- **Favoritos** — sí persisten, junto con el resto de `connections.json`.
- **Importar/Exportar configuración** (menú Conexiones) — mismo formato que el archivo por defecto, pero a una ruta elegida por el usuario; importar reemplaza el árbol completo (no hace merge).

## Diagnóstico y logging

Pestaña "Diagnóstico" en la ventana principal — log visual de sesión (ejecuciones, cancelaciones, credenciales guardadas, resultados de escaneo, pruebas de conexión), se pierde al cerrar la app. Aparte, `logs/faro-app.log` (SLF4J + Logback, rotación diaria + por tamaño, 14 días o 500MB) con trazabilidad completa a nivel DEBUG de toda la app — nunca contraseñas, solo eventos/conteos; el texto de cada sentencia SQL se loguea recortado a 500 caracteres.

## Tests automatizados

`mvn test` — JUnit 5 sobre la lógica pura que no depende de JavaFX ni de una conexión real: parser CSV, formateador SQL, resolución de credenciales, `jdbcUrl()` por motor, aplanado del registro de conexiones, desambiguación de triggers/funciones sobrecargados, contador de generación de caché del explorador de esquema, generación de scripts SQL. No hay tests de controladores JavaFX (`TableView`/`TreeView`/diálogos) ni de nada que necesite una base de datos real — necesitarían TestFX o una base embebida/mocks de JDBC, no se agregaron.

## Limitaciones conocidas

- Solo el esquema por defecto de cada motor en el explorador (`public`/`dbo`).
- Autocompletado limitado a palabras clave SQL, no nombres reales de tabla/columna.
- El modo "solo lectura" y el formateador SQL son heurísticas por patrón, no parsers SQL completos.
- "Explicar plan de ejecución" en SQL Server (`SHOWPLAN_ALL`) no se ha corrido contra un servidor SQL Server real — solo contra el comportamiento documentado del driver.
- Historial de consultas no persiste entre sesiones (a propósito, ver "Persistencia").
- Sin inferencia de tipo en Importar CSV; sin soporte de campos multilínea entre comillas.
- Fetch size configurable solo tiene efecto real en SQL Server, no en PostgreSQL todavía.
- Ningún test cubre el mecanismo de tamaño de fuente en vivo ni el layout de JavaFX en general — es comportamiento visual, verificado a mano en la app real.

## Estructura relevante

```
src/main/java/com/faro/app/
  Main.java, MainController.java       — arranque y controlador principal
  data/        — persistencia (registro de conexiones, credenciales, preferencias)
  model/       — DatabaseEntry, DbEngine, ColumnMetadata, etc.
  query/       — ejecución, pools de conexión, introspección de esquema, formateador SQL
  ui/          — diálogos, fábricas de árbol/tabla, tema/CSS en vivo
src/main/resources/com/faro/app/
  *.fxml       — cada ventana/diálogo
  app.css, theme-light.css, theme-dark.css, fonts/
src/test/java/  — tests JUnit 5
```

## Más contexto

- `CONTEXTO_SESIONES.md` (raíz del repo) — historial completo de cómo se construyó/depuró cada función, decisiones de diseño con su razonamiento, y bugs encontrados con su causa real.
- `bodegas-test/` — entorno Docker Compose con 6 bases de prueba (3 PostgreSQL, 3 SQL Server) precargadas con ~500K filas cada una y un objeto real de cada categoría de esquema (vista/función/procedimiento/trigger/tipo), para probar el explorador de esquema y la ejecución masiva contra datos reales.
- `Migración_Flutter_Java/entrega/` — el diseño visual/técnico original que guió esta reescritura (mapa de cada zona de la interfaz a su control JavaFX, tabla de tokens de diseño).
