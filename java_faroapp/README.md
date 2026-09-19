# Faro — Java

Faro es una herramienta de escritorio que corre una consulta SQL contra varias bases de datos distribuidas a la vez (agrupadas libremente por "servidor" — una cadena de bodegas, un host central con varias bases de compañía, o cualquier otro agrupamiento útil), en vez de conectarse a cada una por separado. Esta es la reescritura completa en **Java + JavaFX**, reemplazo de la versión anterior en Flutter (`flutter_faroapp/`) — el driver de SQL Server en el ecosistema Dart/Flutter no tenía un cliente TDS puro maduro; Java sí tiene el driver JDBC oficial de Microsoft.

Este documento describe **qué hace la app hoy**, como referencia — no es un historial de cambios. Para el registro completo de cómo se construyó cada función, qué se intentó y no funcionó, y las decisiones de diseño con su razonamiento, ver `CONTEXTO_SESIONES.md` en la raíz del repo.

## Requisitos y cómo correr

- **JDK 25 o superior** y Maven. No es "21+": `pom.xml` fija `maven.compiler.release=25`, así que con un JDK 21 el build **falla** (corregido el 2026-09-14 — el README decía 21+ y era incorrecto). El runtime de JavaFX sí es 21 (LTS), que corre sin problema sobre un JDK 25; subirlo a JavaFX 25 tendría que revisarse contra el CSS, porque esta app depende bastante de qué hereda de Modena.
- Los usuarios finales **no necesitan Java**: el `.exe` portable de `jpackage` lleva su propio runtime embebido (ver "Empaquetado").
- Desarrollo y ejecución en **Windows nativo** — WSLg no renderiza ventana JavaFX en algunos entornos; no es un problema de Faro, pero el camino recomendado es Windows directo.

Los comandos de abajo son iguales en cmd y en PowerShell **salvo** los que arman
el ejecutable, donde cambia la continuación de línea y la copia de archivos — van
las dos variantes.

```
mvn compile javafx:run        # arranca la app
mvn test                      # corre los tests (JUnit 5, lógica pura sin JavaFX/BD)
mvn -Ppackage clean package   # arma un JAR único ejecutable (target/faro-app.jar)
```

**`compile` antes de `javafx:run` no es de adorno:** el goal `javafx:run` arranca
con lo que haya en `target/classes` y **no dispara la fase de compilación** — sin
el `compile` adelante se corre la versión anterior del código sin ningún aviso.

El perfil `package` no corre en el build normal — `mvn compile`/`mvn test`/`mvn javafx:run` no lo tocan. Para un `.exe`/carpeta portable de Windows con el runtime embebido:

**cmd:**

```
mkdir target\dist-input
copy target\faro-app.jar target\dist-input\
jpackage --type app-image --input target\dist-input --dest target\dist ^
  --name Faro --main-jar faro-app.jar --main-class com.faro.app.Launcher ^
  --app-version 0.1.0 --icon ..\flutter_faroapp\windows\runner\resources\app_icon.ico ^
  --java-options "-Xmx4g" --java-options "-XX:+UseG1GC" --java-options "-XX:+UseStringDeduplication"
```

**PowerShell** — la continuación de línea es el acento invertido (`` ` ``), no el
`^` de cmd, y no admite ni un espacio después; ante la duda, pega el `jpackage`
completo en una sola línea:

```powershell
New-Item -ItemType Directory -Force target\dist-input
Copy-Item target\faro-app.jar target\dist-input\
jpackage --type app-image --input target\dist-input --dest target\dist `
  --name Faro --main-jar faro-app.jar --main-class com.faro.app.Launcher `
  --app-version 0.1.0 --icon ..\flutter_faroapp\windows\runner\resources\app_icon.ico `
  --java-options "-Xmx4g" --java-options "-XX:+UseG1GC" --java-options "-XX:+UseStringDeduplication"
```

### Dos tropiezos reales del build, con su salida

- **`mvn clean` falla con `Failed to delete ...\target\dist\Faro\Faro.exe`.** Pasa
  siempre que ya se armó el portable antes: Maven no puede borrar esa carpeta (más
  todavía si el `.exe` está corriendo). Cierra el `.exe` si está abierto y borra
  `target\dist` a mano antes del clean — `rmdir /s /q target\dist` en cmd,
  `Remove-Item -Recurse -Force target\dist` en PowerShell.
- **El compilado incremental puede ocultar errores de compilación reales.**
  Encontrado dos veces el 2026-09-07: `mvn compile` decía `BUILD SUCCESS`
  reusando clases viejas, y el error (un import faltante) solo apareció al
  forzar la recompilación. Si el comportamiento de la app no cuadra con el
  código, este es el primer sospechoso — borra las clases y recompila:
  `rmdir /s /q target\classes\com\faro\app` (cmd) o
  `Remove-Item -Recurse -Force target\classes\com\faro\app` (PowerShell), y de
  nuevo `mvn compile`.

`--main-class com.faro.app.Launcher`, **no** `com.faro.app.Main` — `Main` extiende `javafx.application.Application`; un JAR sin módulos con esa clase como punto de entrada hace que la JVM rechace arrancar ("JavaFX runtime components are missing"). `Launcher` es una clase intermedia sin esa herencia que solo delega a `Main.main(...)` (ver su javadoc). `mvn javafx:run` no necesita esto — ese plugin arma su propio module-path.

`--type app-image` produce una carpeta (`target\dist\Faro\`, con `Faro.exe` + runtime embebido) copiable a cualquier máquina Windows sin instalar Java — **verificado corriendo el `.exe` real**, ventana y conexión a PostgreSQL/SQL Server confirmadas. Un instalador con asistente (`--type exe`/`--type msi`) necesita **WiX Toolset v3** instalado y en el `PATH`, que no viene con este repo.

**Al transportar la carpeta a otra máquina, comprímela con 7-Zip o con `tar.exe`, en vez del compresor integrado de Windows ("Enviar a → Carpeta comprimida")** — este último puede dejar el zip incompleto sin ningún error visible cuando la carpeta tiene muchos archivos chicos anidados, como `runtime\` (~123 MB, 300+ archivos). Síntoma si pasa: `Faro.exe` en la máquina destino truena con `"Failed to find JVM in '...\runtime' directory."`.

`tar.exe` (bsdtar) **viene con Windows 10/11**, así que no hace falta instalar nada — es lo que se usó para armar el zip verificado:

```powershell
cd target\dist
tar.exe -a -c -f Faro-0.1.0-portable.zip Faro
```

Verifica antes de transferir que el comprimido pese cerca de los **~61 MB** esperados, no ~17 MB (eso significa que `runtime\` se quedó afuera). Contar entradas es más seguro que mirar el tamaño: `tar.exe -tf Faro-0.1.0-portable.zip` tiene que dar **386** (380 de ellas bajo `Faro/runtime/`).

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

**Clic derecho sobre la fila de un grupo** (2026-09-11) — "Marcar/Desmarcar todas las de este grupo" (el botón "Todas" de la barra es global, esto es por grupo), "Renombrar grupo…", "Subir"/"Bajar" y "Ordenar sus bases A-Z". El encabezado "Sin grupo" trae una versión reducida (marcar/desmarcar y ordenar): no es un grupo real sino la ausencia de uno, así que no se puede renombrar ni mover.

**Orden del árbol** — grupos y bases se mueven con "Subir"/"Bajar" del menú contextual, o con **Alt+↑ / Alt+↓** sobre la fila seleccionada. Mover una base la reordena **dentro de su propio grupo**; cambiar de grupo sigue siendo "Mover a grupo…". El orden se guarda en `connections.json` (es el orden de las listas del registro, no hay campo de posición aparte).

**Se eligió menú contextual y no arrastrar-y-soltar** a propósito: `ConnectionTreeCell` tiene 14 manejadores de mouse, varios agregados para cerrar bugs reales de gestos (el doble clic que abría "Editar BD" desde cualquier parte de la fila, el candado, las filas de esquema), y la fila de base consume todo clic primario como red de seguridad. El arrastre queda como posible paso aparte, con su propia verificación en vivo.

**Estado de conexión, sincronizado con conexiones reales, no un botón aparte** — el punto de color se actualiza solo (es una propiedad reactiva de `DatabaseEntry`) cada vez que la carga de esquema o una ejecución de consulta prueban esa base de verdad: verde si conecta, rojo si falla (sin confundir un fallo de conexión con un error de SQL sobre una conexión que sí abrió bien). Persiste entre sesiones (solo verde/rojo, nunca el estado transitorio "Probando…"). Mientras una base tiene una consulta corriendo, su punto pulsa (fundido de opacidad en bucle).

**Nada se conecta al arrancar** (2026-09-07, a pedido explícito del usuario: "cuando inicia la app no quiero que cargue todas las BD, ya que veo que se llena de pool de conexiones si tengo muchas BD ya en la lista"). Abrir Faro solo dibuja el árbol — cero conexiones, cero pools, sin importar cuántas bases haya registradas. Una base solo se conecta cuando de verdad la usas: al expandirla en el árbol (carga de esquema), al correr una consulta contra ella, o con "Conexiones → Probar todas las conexiones". Consecuencia a tener presente: el punto de color arranca mostrando el estado guardado de la última sesión, y se confirma o se corrige recién cuando esa base se toca de verdad — un verde desactualizado (ej. contraseña que cambió desde entonces) sigue en verde hasta ese momento, no se corrige solo en segundos como antes.

**Explorador de esquema por base** (`SchemaIntrospector`, `DatabaseTreeItem`, `CategoryTreeItem`) — todo perezoso, nada se consulta antes de que lo pidas:

- **Expandir una base** dibuja al instante sus 6 categorías y abre **una** conexión para confirmar su punto de estado — no consulta ningún esquema todavía.
- **Las 6 categorías se cargan una por una**, solo al expandir esa categoría específica (2026-09-07: antes Tablas y Vistas eran la excepción, se traían de un jalón al abrir la base). Cada una muestra su conteo real una vez cargada. Tablas y Vistas comparten un solo fetch, así que expandir una deja la otra instantánea.
- Mientras una categoría carga, su fila muestra un **indicador de carga girando**, no un texto quieto — para distinguir "está trabajando" de "se trabó".
- Expandir una tabla/vista/tipo trae sus columnas (nombre + tipo) bajo demanda.
- Clic derecho sobre cualquier objeto → "Generar script CREATE" trae la definición real (`CREATE TABLE`/`CREATE VIEW`/`CREATE FUNCTION`/etc.) en una pestaña de consulta nueva.

**Comparar un objeto entre bodegas** (clic derecho sobre cualquier objeto → "Comparar en las bases marcadas…", `SchemaComparisonService`) — extrae el script real de ese objeto en cada base marcada, le saca MD5, y marca cuáles difieren. Sirve para lo que motivó la función: verificar que la misma función/tabla/trigger sea idéntica en todas las bodegas y que ninguna se haya quedado con una versión vieja, sin escribir ninguna consulta ni revisarlas a mano.

- Resultado: una fila por base con `Base de datos · Motor · Objeto · Tipo · Estado · Coincide · MD5 · Caracteres · Definición`. Cae en la pestaña Resultados como cualquier corrida, así que **"Exportar CSV" funciona igual** — incluido el script completo de cada versión.
- **La comparación se hace por motor, no entre todos.** PostgreSQL y SQL Server nombran los tipos distinto (`character varying` contra `nvarchar`), así que el DDL del mismo objeto nunca coincide entre motores; mezclarlos daría falsa alarma siempre. Cada base se compara solo contra las de su mismo motor.
- El MD5 normaliza finales de línea (CRLF/LF) y espacio al principio/final — ruido que no cambia nada. Todo lo demás (sangría interna, mayúsculas, comentarios) sí cuenta como diferencia: es texto realmente distinto en el servidor.
- Para **tablas** compara el `CREATE TABLE` reconstruido desde columnas reales (ningún motor devuelve DDL de tabla listo), así que detecta columnas de más/de menos o con otro tipo — no índices ni constraints.
- Necesita al menos 2 bases marcadas; la base del objeto sobre el que hiciste clic derecho se incluye sola aunque no esté marcada.
- **Desambiguación de nombres repetidos**: PostgreSQL permite triggers con el mismo nombre en tablas distintas y funciones/procedimientos sobrecargados por firma — ambos casos se detectan y se muestran calificados (`tabla.trigger`, `función(tipo_arg)`) en vez de aparecer como filas idénticas indistinguibles.
- **Tipos personalizados** filtra correctamente los tipos compuestos reales de PostgreSQL (`CREATE TYPE ... AS (...)`) sin mezclar el tipo-fila automático que cada tabla/vista tiene internamente.
- Un mismo fetch de esquema alimenta también el autocompletado de tablas/columnas del editor SQL — no son dos sistemas separados.
- Un contador de generación por base invalida en segundo plano cualquier resultado en camino cuando se pide "Recargar esquema" a mitad de una carga, para no pintar datos obsoletos.

**Límite conocido**: solo el esquema por defecto de cada motor (`public` en PostgreSQL, `dbo` en SQL Server) — bases con tablas repartidas en varios esquemas custom no las muestra todas.

**Codificación del cliente, por base** (2026-09-11, solo PostgreSQL) — Agregar/editar base → "Codificación". Déjala en **Automática (UTF-8)** salvo que el esquema falle con `invalid byte sequence for encoding "UTF8"`. Ese error lo tira el **servidor**, no Faro: la base guarda texto que no es UTF-8 válido, lo típico es una creada con codificación `SQL_ASCII` —que PostgreSQL acepta sin validar— con contenido en LATIN1/Windows-1252 adentro. Poniendo la codificación REAL de los datos, el servidor deja de intentar una conversión imposible y el driver decodifica con esa misma codificación, así que los acentos salen bien en vez de romper la consulta. Internamente pone `client_encoding` **más `allowEncodingChanges=true`**, que es obligatorio: sin esa bandera pgJDBC **aborta la conexión** al detectar que `client_encoding` dejó de ser UTF8. El mensaje de error del árbol apunta directo a esta opción. **Sin verificar todavía contra una base con este problema.**

**Descubrir bases** (menú Conexiones, o clic derecho en una base → "Descubrir bases en esta IP…"):

- Las bases **ya registradas** salen en la lista pero **apagadas y con "— ya agregada"**: se muestran para que sepas que el escaneo sí las encontró, y no se pueden volver a agregar. Antes el diálogo no recibía el registro, así que listaba todo como nuevo y agregarlas otra vez creaba duplicados.
- Botones **Todas / Ninguna** sobre los resultados; solo tocan las que de verdad se pueden agregar.
- **`postgres` queda fuera** del escaneo, igual que SQL Server ya saltaba `master`/`tempdb`/`model`/`msdb`. También se omiten las bases que no aceptan conexión y las de SQL Server que no están ONLINE.
- **"Descubrir bases en esta IP…" deja las nuevas en el MISMO grupo** que la base desde la que escaneaste (2026-09-11) — son bases del mismo servidor. Si la de origen está suelta, las nuevas también. El del menú Conexiones no parte de ninguna base, así que sigue dejándolas en "Sin grupo".

## Editor SQL

`CodeArea` con resaltado de sintaxis (palabras clave/cadenas/números/comentarios de línea y de bloque) y números de línea, en pestañas independientes (`Ctrl+T` nueva pestaña, "Abrir archivo .sql…" abre cada archivo en su propia pestaña, "Guardar"/"Guardar como…" sobre la pestaña activa). Cerrar una pestaña, la ventana, o "Archivo → Salir" con cambios sin guardar pregunta antes de descartarlos.

**Cada pestaña dice contra qué base va a correr** (2026-09-11) — encabezado de dos líneas: el nombre arriba (o el del archivo si se guardó) y la base debajo, en letra chica monoespaciada. Una base marcada muestra su alias, varias muestran "N bases", ninguna muestra "sin base seleccionada" — que antes solo se descubría al presionar Ejecutar. En la pestaña **activa** la segunda línea sigue las casillas del árbol en vivo, no la selección guardada.

**El resaltado se calcula fuera del hilo de la interfaz** — con un script grande (un dump pegado, o un `INSERT` generado de miles de líneas, algo que esta misma app produce) recorrer todo el documento con regex en cada pausa del tecleo se sentía como que el editor se trababa. Si sigues escribiendo mientras un cálculo está en vuelo, el resultado viejo se descarta en vez de pintar colores corridos respecto del texto actual.

**Una sola lista de palabras reservadas** — el resaltado, el autocompletado y "Formatear SQL" comparten `SqlFormatter.KEYWORDS`. Antes el resaltado tenía su propia lista paralela y las dos ya habían divergido: 26 palabras (`EXEC`, `PROCEDURE`, `DECLARE`, `BEGIN`, `COMMIT`, `TRIGGER`…) se autocompletaban pero nunca se resaltaban.

- **Buscar en el script** (`Ctrl+F`) — barra de búsqueda insensible a mayúsculas, circular (da la vuelta al llegar al final).
- **Formatear SQL** (`Ctrl+L`) — mayúsculas en palabras clave + salto de línea antes de las cláusulas principales; nunca toca el contenido de literales de texto, identificadores entre comillas/corchetes ni comentarios (tokenizador dedicado, no un reemplazo de texto ingenuo).
- **Autocompletado** (`Ctrl+Espacio`) — palabras clave SQL **y** nombres reales de tabla/vista/columna de la primera base marcada, con lo que ya esté en caché de esa base (comparte el mismo fetch que el explorador de esquema; expandir una categoría ahí también alimenta esto). Corta en 50 sugerencias y avisa cuántas quedaron fuera — con una base de miles de tablas y un prefijo de una letra, una lista completa no sirve para elegir nada.
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
- **Fetch size configurable** (Preferencias → Rendimiento) — cuántas filas se traen por bloque al leer resultados grandes. **SQL Server** siempre lo respetó. **PostgreSQL** lo respeta desde el 2026-09-14, pero **solo en scripts de solo lectura**: el driver únicamente abre cursor con el autocommit desactivado, y desactivarlo en un script que escribe lo convertiría en una transacción todo-o-nada (ver "Limitaciones conocidas" para el detalle).

## Resultados

`TableView` de columnas dinámicas (no se conocen hasta que corre la consulta) — con una columna inicial "Base de datos" cuando se consultó más de una a la vez, para poder rastrear el origen de cada fila. "Exportar CSV" corre en segundo plano (no bloquea la ventana), con el mismo criterio de escape de comillas/comas al leer un CSV con "Importar CSV a una tabla". La altura de fila sigue el tamaño de fuente efectivo de la interfaz (ver "Apariencia").

## Diálogos

- **Agregar/editar base de datos** — un formulario para las dos operaciones, prueba de conexión inline (con respaldo a credenciales por defecto si el campo de usuario está vacío; corre en segundo plano, la ventana nunca se congela esperando a un host caído), motor/modo/tamaño de pool/timeout, y la casilla **"Confiar en el certificado del servidor"** (ver abajo).

**Certificado del servidor (solo SQL Server)** — el tráfico a SQL Server siempre va cifrado (`encrypt=true`, sin opción de apagarlo). Lo que la casilla controla es si además se **verifica** que el servidor sea realmente quien dice ser: marcada (el default, y lo que hacían todas las conexiones antes de que la casilla existiera) acepta cualquier certificado; desmarcada exige uno que la máquina reconozca como válido — más seguro, pero la conexión falla si el servidor usa un certificado autofirmado, que es lo normal en servidores internos. Se guarda por base en `connections.json`; una base de una configuración anterior (sin ese campo en el archivo) se comporta igual que siempre, marcada. En PostgreSQL la casilla queda deshabilitada: esa URL no negocia TLS por su cuenta (pgJDBC usa su propio `sslmode`, todavía no expuesto en Faro).
- **Credenciales por defecto** — usuario/contraseña de sesión, usado cuando una base no tiene su propio override guardado. Resolución: override por base → default de sesión → vacío.
- **Descubrir bases de datos** — dado un host + usuario/contraseña, prueba conexión TCP a los puertos 5432/1433 y, si responden, hace login JDBC real para listar las bases visibles con ese usuario. Un host por búsqueda, no un rango de IPs.
- **Importar CSV a una tabla** — parser real (maneja comillas y comas dentro de campos), `INSERT` por lotes de 500 en una sola transacción. **La codificación se detecta sola**: se intenta UTF-8 y, si el archivo no lo es, se relee con la del sistema (la que produce Excel en Windows) y el diálogo lo dice junto al conteo de filas, para que te enteres antes de ver acentos rotos dentro de la tabla. Sin inferencia de tipo propia (todo va como texto, apoyado en la conversión implícita del driver) ni soporte de saltos de línea dentro de un campo entre comillas.
- **"Probar todas las conexiones"** (menú Conexiones) — prueba cada base registrada (no solo las marcadas) con sus credenciales resueltas y actualiza el punto de estado de cada una en el árbol, mostrando cuáles fallaron y por qué.
- **Preferencias** — ver el detalle completo abajo.

## Preferencias

Tres pestañas, todas aplican y guardan de inmediato — no hay botones "Guardar"/"Cancelar", solo "Cerrar".

- **Rendimiento** — bases en paralelo al ejecutar, tamaño de pool y timeout por defecto de una base nueva, fetch size. Cada campo se guarda al perder el foco (Tab/clic afuera) o con Enter.
- **Atajos** — referencia estática de los atajos reales del menú (no editable).
- **Apariencia** — tema (claro/oscuro), color de acento (**7 opciones**), tamaño de fuente del editor SQL (spinner, 10–24px), y tamaño de fuente del resto de la interfaz (slider, -5..+5, aplica sobre los tamaños base de cada elemento). Los cuatro aplican en vivo apenas se interactúa con el control — tema y tamaño también se reflejan en la ventana de Preferencias mientras sigue abierta, no solo en la ventana principal.

**Acento "negro"** (2026-09-11) — es **monocromático**, no negro literal: negro en tema claro, blanco en oscuro. No es una excepción al diseño sino su caso extremo — los otros 6 acentos ya usan una versión más clara en tema oscuro, porque el acento no solo pinta fondos de botón: también es color de texto, el subrayado de la pestaña activa y el trazo de varios íconos, y un negro literal sobre el fondo `#09090B` del tema oscuro dejaría todo eso invisible.

Agregarlo destapó dos cosas que ya estaban mal para todos los acentos:

- **El texto sobre el acento estaba fijo en blanco**, lo que asume que todo acento es oscuro. Ahora cada acento declara su propio color de texto legible (`-token-accent-on`). Midiendo el contraste real, **ningún acento de tema oscuro llegaba al mínimo de 3:1** para texto en negrita: `amber` daba **1.7:1** y `teal` **1.9:1** (prácticamente ilegibles), y los otros cuatro entre 2.3 y 2.8. Se corrigieron **solo los dos por debajo de 2:1**; los demás se dejaron como estaban para no cambiar el aspecto del acento por defecto sin que nadie lo pidiera. Hay un test que calcula ese contraste y que impide volver a los casos ilegibles.
- **Las palabras reservadas del editor eran el único color de sintaxis atado al acento** (cadenas, números y comentarios ya tenían tokens propios). Ahora tienen el suyo: para los 6 acentos de color vale exactamente lo mismo que antes, y "negro" usa el índigo de siempre. Que la interfaz sea monocromática no obliga al editor a serlo — es como funcionan los temas monocromáticos de los editores reales.

**Nota técnica sobre el tamaño de fuente de la interfaz**: a diferencia de los colores (que sí usan variables CSS nativas de JavaFX, "looked-up values"), `-fx-font-size` no admite ese mecanismo — es una limitación real del parser CSS de JavaFX, no una limitación de diseño. El tamaño en vivo se resuelve regenerando en memoria una copia de la hoja de estilos con los tamaños ya desplazados, en vez de con variables.

## Persistencia

- **Conexiones + preferencias + favoritos + pestañas de consulta abiertas + estado de conexión (verde/rojo) + orden del árbol** — `~/.faro/connections.json` (JSON plano vía Gson), se carga al abrir y se guarda al cerrar, con autoguardado cada 2 minutos por si la app se cierra de forma anormal.
  - **Escritura atómica** (2026-09-10) — se escribe a un temporal y recién entonces se mueve encima del definitivo. Antes era una escritura directa, que **vacía el archivo y después escribe**: si se cortaba a la mitad (el autoguardado en segundo plano y el cierre escribiendo a la vez, o el proceso matado a mitad de un autoguardado), quedaba un JSON truncado y la app arrancaba con el registro **vacío** — o sea, se perdía toda la configuración en silencio. Ahora el archivo solo existe en dos estados: el contenido viejo completo, o el nuevo completo. Lo mismo para `credentials.dat`, donde pesa más (perderlo obliga a recapturar todas las contraseñas). Al cerrar, la app además espera a que termine el autoguardado que estuviera en curso.
- **Credenciales** — `~/.faro/credentials.dat`, cifradas con DPAPI (Windows Data Protection API, atadas a la cuenta de Windows del usuario — no portables a otra cuenta/máquina). Nunca en JSON plano en el archivo de trabajo, y el autoguardado de `connections.json` NUNCA las incluye.
  - **Excepción, solo bajo pedido explícito (2026-09-10):** "Exportar configuración…" ofrece una casilla **"Incluir usuarios y contraseñas"**, desmarcada por defecto. Marcarla escribe usuario y contraseña **en texto legible** dentro del `.json` exportado, e importarlo los devuelve a la sesión. Es una decisión del usuario, tomada tras ver la alternativa con frase maestra y descartarla: el caso de uso es montar Faro en un equipo nuevo sin recapturar decenas de contraseñas a mano, y DPAPI no sirve para eso (cifra atado a la cuenta de Windows de origen, así que el archivo no se podría descifrar en el equipo destino). **Un archivo exportado con esa casilla marcada es un archivo con secretos:** cualquiera que lo abra ve las contraseñas. El diálogo lo advierte antes de escribir, el nombre sugerido del archivo lo dice (`faro-config-con-credenciales.json`), y el log de Diagnóstico deja la línea como advertencia — pero nada de eso lo protege si el archivo se manda por correo o se deja en una carpeta compartida. La casilla nace desmarcada en cada exportación a propósito: nunca se hereda de la vez anterior.
- **Historial de consultas** — en memoria únicamente, se pierde al cerrar la app (tope de 50 entradas, sin duplicados consecutivos).
- **Favoritos** — sí persisten, junto con el resto de `connections.json`.
- **Importar/Exportar configuración** (menú Conexiones) — mismo formato que el archivo por defecto, pero a una ruta elegida por el usuario; importar reemplaza el árbol completo (no hace merge).

## Diagnóstico y logging

Pestaña "Diagnóstico" en la ventana principal — log visual de sesión (ejecuciones, cancelaciones, credenciales guardadas, resultados de escaneo, pruebas de conexión), se pierde al cerrar la app. Aparte, `logs/faro-app.log` (SLF4J + Logback, rotación diaria + por tamaño, 14 días o 500MB) con trazabilidad completa a nivel DEBUG de toda la app — nunca contraseñas, solo eventos/conteos; el texto de cada sentencia SQL se loguea recortado a 500 caracteres.

## Tests automatizados

`mvn test` — JUnit 5 sobre la lógica pura que no depende de JavaFX ni de una conexión real: parser CSV (contenido **y** codificación detectada), formateador SQL, resolución de credenciales, `jdbcUrl()` por motor (incluidos certificado y codificación), aplanado y **orden** del registro de conexiones, desambiguación de triggers/funciones sobrecargados, contador de generación de caché del explorador de esquema, generación de scripts SQL, la heurística de **Solo lectura** (incluida la condición que activa el cursor de PostgreSQL), el escapado de CSV y la búsqueda del editor, y el contraste de la paleta de acentos. Uno de ellos no mira código sino archivos: `StyleClassCoverageTest` cruza las clases de estilo usadas contra `app.css` (ver más abajo).

No hay tests de controladores JavaFX (`TableView`/`TreeView`/diálogos) ni de nada que necesite una base de datos real — necesitarían TestFX o una base embebida/mocks de JDBC, no se agregaron. **La suite permanente nunca arranca el toolkit de JavaFX**, a propósito.

**Para lo visual y lo de hilos se usan sondas temporales**, no tests permanentes: un archivo de test que arranca el toolkit, **mide** lo que haga falta (el color efectivo de un control en los dos temas, la posición real de un nodo, que cada `@FXML` de un FXML quedó inyectado) y **se borra después de correrlo**. La evidencia que queda es el número medido, anotado en `CONTEXTO_SESIONES.md` — no la sonda. Vale la pena porque atrapa cosas que no se ven leyendo el CSS: un `fx:id` mal escrito no rompe la carga del FXML, deja el campo en `null` y truena en vivo; y una regla que pierde por especificidad deja dos estados del mismo color sin que nada proteste.

**El hueco de las clases de estilo ya está cerrado** (2026-09-14). Una clase usada en un FXML o en Java **sin regla que la respalde en `app.css`** no la detectaba nada, y había pasado dos veces (`.trust-cert-check`, `.discover-results-scroll`) — en ambas el síntoma solo apareció en tema oscuro, porque sin regla el control se queda con los colores por defecto de Modena, que son claros. Ahora `StyleClassCoverageTest` cruza cada clase usada contra los selectores de `app.css` y falla nombrando la clase y el archivo. Se comprobó quitando esas dos reglas del CSS: las atrapa a las dos.

El compilador además corre con `-Xlint:all` (`-serial` y `-this-escape` excluidas, con el motivo comentado en `pom.xml`), y el build está en **cero advertencias**.

## Limitaciones conocidas

- Solo el esquema por defecto de cada motor en el explorador (`public`/`dbo`).
- Autocompletado: sugiere palabras clave siempre, y nombres reales de tabla/vista/columna **solo de lo que ya esté en caché** de esa base (las categorías que hayas expandido, y las columnas de las tablas sobre las que hayas usado "Generar…"). Se va llenando con el uso, no está completo desde el primer Ctrl+Espacio. Corta en 50 sugerencias, avisando cuántas quedaron fuera. Solo dispara con `Ctrl+Espacio`, no mientras escribes.
- El modo "solo lectura" y el formateador SQL son heurísticas por patrón, no parsers SQL completos. **Agujero conocido y cubierto por un test que lo documenta:** un CTE que escribe (`WITH x AS (DELETE … RETURNING *) SELECT …`, válido en PostgreSQL) pasa el filtro de solo lectura, porque la heurística mira la primera palabra y no el contenido.
- "Explicar plan de ejecución" en SQL Server (`SHOWPLAN_ALL`) no se ha corrido contra un servidor SQL Server real — solo contra el comportamiento documentado del driver.
- Historial de consultas no persiste entre sesiones (a propósito, ver "Persistencia").
- Sin inferencia de tipo en Importar CSV; sin soporte de campos multilínea entre comillas. La **codificación sí se detecta sola** (2026-09-14): se intenta UTF-8 y, si el archivo no lo es, se relee con la codificación del sistema —la que usa Excel en Windows, normalmente `windows-1252`— y el diálogo dice con cuál lo leyó. Antes fallaba con `MalformedInputException` ante cualquier acento o `ñ`.
- **Fetch size en PostgreSQL solo aplica a scripts de solo lectura.** Hasta el 2026-09-14 no tenía **ningún** efecto ahí: el driver solo usa cursor si el autocommit está desactivado, y la app nunca lo desactivaba, así que en una consulta de 500,000 filas el driver materializaba el resultado completo antes de retornar y al terminar el bucle el resultado vivía dos veces en memoria. Ahora los scripts de solo lectura contra PostgreSQL sí abren cursor. **Los que escriben siguen en autocommit a propósito**: desactivarlo convertiría un script de varias sentencias en una transacción todo-o-nada, y un fallo en la tercera desharía las dos primeras — un cambio de comportamiento que nadie pidió. SQL Server siempre respetó `fetchSize` y no necesitó nada.
- **El CSV exportado va en UTF-8 sin BOM**, así que Excel en español lo abre mostrando `Ã±` donde va `ñ` (abrirlo con "Datos → Desde texto" eligiendo UTF-8 sí funciona). Es el lado inverso de la detección al importar, y sigue abierto a propósito: agregar el BOM arregla Excel pero cambia los bytes de **todos** los archivos exportados, y hay herramientas que no lo toleran.
- El **grid de resultados** no se puede ordenar por columna ni filtrar; el resultado completo se carga en memoria (el `TableView` solo virtualiza qué se dibuja, no qué se guarda). Ver §5.1 de `OPTIMIZACION_RENDIMIENTO.md` para las tres salidas posibles, todas con decisión de producto de por medio.
- Al editor le faltan atajos habituales: comentar/descomentar selección, duplicar línea, ir a línea.
- ~~El alto de fila del árbol es fijo y no escala con el tamaño de fuente.~~ **Corregido el 2026-09-14**: ahora se calcula igual que el del grid de resultados, a partir del tamaño de fuente efectivo. La fórmula está calibrada para que en el tamaño por defecto siga dando **exactamente los 44px** de antes — ese alto ya se había ajustado a mano tres veces y no debía cambiar; lo que cambia es que en el extremo del slider (+5) las dos líneas de una fila de base ya no se cortan.
- Ningún test cubre el mecanismo de tamaño de fuente en vivo ni el layout de JavaFX en general — es comportamiento visual, verificado a mano en la app real (o con sondas, ver "Tests automatizados").

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

**`MainController` delega en cuatro coordinadores** (2026-09-19, hallazgo C1 del análisis) en vez de hacerlo todo él:

| Clase | De qué se encarga |
|---|---|
| `data/SessionPersistence` | Cargar la sesión anterior, el autoguardado cada 2 minutos y el guardado al cerrar. |
| `ui/QueryTabManager` | Las pestañas de consulta: crearlas, su encabezado de dos líneas, guardar, buscar y formatear. |
| `ui/ConnectionTreeCoordinator` | El estado del árbol que sobrevive a cada reconstrucción: qué bases están marcadas, qué filas quedaron abiertas, el scroll y el buscador. |
| `ui/ScriptGeneratorCoordinator` | Las seis acciones "Generar…" del explorador de esquema. |

El controlador se queda con lo que el FXML enlaza por nombre (los `@FXML`), los diálogos, y la ejecución de consultas con su exportación — esos dos últimos son los candidatos naturales para seguir dividiendo.

## Más contexto

- `CONTEXTO_SESIONES.md` (raíz del repo) — historial completo de cómo se construyó/depuró cada función, decisiones de diseño con su razonamiento, y bugs encontrados con su causa real.
- `bodegas-test/` — entorno Docker Compose con 6 bases de prueba (3 PostgreSQL, 3 SQL Server) precargadas con ~500K filas cada una y un objeto real de cada categoría de esquema (vista/función/procedimiento/trigger/tipo), para probar el explorador de esquema y la ejecución masiva contra datos reales.
- `Migración_Flutter_Java/entrega/` — el diseño visual/técnico original que guió esta reescritura (mapa de cada zona de la interfaz a su control JavaFX, tabla de tokens de diseño).
