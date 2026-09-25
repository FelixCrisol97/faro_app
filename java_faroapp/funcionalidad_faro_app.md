# Funcionalidad de Faro — versión Java

> **Qué es este documento.** Describe, a nivel de negocio y con el mayor detalle posible, **todo lo que hace Faro**: cada pantalla, cada botón, cada regla, cada mensaje, cada límite y cada comportamiento inesperado. No explica cómo está programado por dentro.
>
> **De dónde sale.** Se armó leyendo el código fuente completo de `java_faroapp/` (la carpeta Flutter es obsoleta y no se tomó en cuenta), sus pruebas automáticas y sus registros, en dos revisiones, la última el 21 de septiembre de 2026. Cuando el README u otro documento dice algo distinto de lo que hace el código, aquí manda el código.
>
> **Convenciones.**
> - Los textos *"entre comillas y en cursiva"* son mensajes, botones o títulos **literales** de la aplicación.
> - Cuando un comportamiento no depende de Faro sino del controlador de base de datos, de la librería de conexiones o del propio motor (PostgreSQL / SQL Server), se indica como **(depende del motor/controlador)**: está razonado a partir de su funcionamiento conocido, no probado en vivo.
> - En la sección 34 (observaciones), cada punto dice si está **Confirmado en el código** (se siguió el flujo completo) o es **Probable** (depende de una pieza externa).
>
> **Qué se ha usado de verdad.** Los registros de uso van del 2 al 21 de septiembre de 2026 y vienen del equipo de desarrollo, con 6 bases de prueba: PostgreSQL 12, 14 y 16, y SQL Server 2017, 2019 y 2022.
> - **Usadas en ese entorno:** ejecutar, comparar, generar CREATE, editar, probar conexión, probar todas, mover a grupo, cambiar tema y Todas/Ninguna.
> - **Sin rastro de uso:** Descubrir, Exportar CSV, Importar CSV, Explicar plan, Cancelar, Favoritos, Exportar/Importar configuración, Abrir/Guardar `.sql`, Formatear y el tope de filas.
>
> Que una función esté descrita aquí significa que el código la implementa, no que se haya probado a fondo.

---

## Índice

1. [Resumen en una página](#1-resumen-en-una-página)
2. [Qué es Faro y qué problema resuelve](#2-qué-es-faro-y-qué-problema-resuelve)
3. [Conceptos básicos](#3-conceptos-básicos)
4. [Abrir y cerrar la aplicación](#4-abrir-y-cerrar-la-aplicación)
5. [La pantalla principal](#5-la-pantalla-principal)
6. [Catálogo de bases de datos](#6-catálogo-de-bases-de-datos)
7. [Grupos y orden del árbol](#7-grupos-y-orden-del-árbol)
8. [El árbol de conexiones, fila por fila](#8-el-árbol-de-conexiones-fila-por-fila)
9. [Elegir contra qué bases se consulta](#9-elegir-contra-qué-bases-se-consulta)
10. [Buscador de bases](#10-buscador-de-bases)
11. [Estado de conexión y conexiones abiertas](#11-estado-de-conexión-y-conexiones-abiertas)
12. [Explorador de esquema](#12-explorador-de-esquema)
13. [Generar scripts desde el esquema](#13-generar-scripts-desde-el-esquema)
14. [Comparar un objeto entre varias bases (MD5)](#14-comparar-un-objeto-entre-varias-bases-md5)
15. [Editor SQL](#15-editor-sql)
16. [Ejecución masiva de consultas](#16-ejecución-masiva-de-consultas)
17. [Pestaña Resultados](#17-pestaña-resultados)
18. [Pestaña Ejecución](#18-pestaña-ejecución)
19. [Pestaña Diagnóstico](#19-pestaña-diagnóstico)
20. [Explicar plan de ejecución](#20-explicar-plan-de-ejecución)
21. [Exportar resultados a CSV](#21-exportar-resultados-a-csv)
22. [Importar un CSV a una tabla](#22-importar-un-csv-a-una-tabla)
23. [Historial de consultas](#23-historial-de-consultas)
24. [Favoritos](#24-favoritos)
25. [Credenciales (usuarios y contraseñas)](#25-credenciales-usuarios-y-contraseñas)
26. [Exportar e importar la configuración (JSON)](#26-exportar-e-importar-la-configuración-json)
27. [Preferencias](#27-preferencias)
28. [Barra de estado](#28-barra-de-estado)
29. [Qué se guarda, dónde y cuándo](#29-qué-se-guarda-dónde-y-cuándo)
30. [Atajos de teclado y gestos del ratón](#30-atajos-de-teclado-y-gestos-del-ratón)
31. [Flujos de trabajo típicos, paso a paso](#31-flujos-de-trabajo-típicos-paso-a-paso)
32. [Preguntas frecuentes](#32-preguntas-frecuentes)
33. [Lo que Faro no hace (límites)](#33-lo-que-faro-no-hace-límites)
34. [Observaciones encontradas al revisar el código](#34-observaciones-encontradas-al-revisar-el-código)
- [Anexo A — Palabras SQL que reconoce el editor](#anexo-a--palabras-sql-que-reconoce-el-editor)
- [Anexo B — Catálogo de mensajes](#anexo-b--catálogo-de-mensajes)

---

## 1. Resumen en una página

**Qué hace.** Ejecuta una misma consulta SQL en paralelo contra muchas bases de datos (PostgreSQL y SQL Server, mezcladas si se quiere) y junta todo en una tabla con una columna que dice de qué base vino cada fila.

**Con qué se trabaja:**

- **Catálogo de bases.** Agregar, editar y eliminar bases; descubrir las que hay en un servidor; probar conexiones; organizar en grupos.
- **Selección por casillas.** Cada pestaña de consulta recuerda sus propias bases marcadas.
- **Editor SQL.** Pestañas, archivos `.sql`, resaltado de sintaxis, buscar, formatear, autocompletar.
- **Ejecución masiva.**
  - Avance en vivo por base.
  - Cancelación.
  - Protección *Solo lectura* por base.
  - Tope de filas en pantalla.
- **Resultados.**
  - Exportar a CSV, con nombre de archivo sugerido.
  - Exportación completa sin límite de tamaño, aunque la pantalla esté recortada.
- **Estructura de cada base.** Tablas, vistas, funciones, procedimientos, triggers y tipos.
  - Genera SELECT, INSERT, UPDATE, DELETE y CREATE.
  - Compara un objeto entre bodegas por MD5.
- **Otras herramientas.** Importar CSV a una tabla, favoritos e historial.
- **Configuración.**
  - Se guarda sola cada 2 minutos.
  - Las contraseñas se guardan cifradas con la cuenta de Windows.
  - Se puede exportar e importar en JSON, con o sin contraseñas, para mover Faro a otro equipo.

**Reglas que conviene saber desde el primer día:**

1. Se ejecuta **la selección** del editor si la hay; si no, todo el script de la pestaña activa.
2. Se ejecuta contra **las bases marcadas** en la pestaña activa.
3. **No hay confirmación antes de ejecutar.** En bases *Sin restricciones*, un `UPDATE` o `DELETE` se aplica de inmediato en todas las marcadas.
4. Cada sentencia se confirma sola. Si una falla, esa base se detiene ahí y lo anterior queda aplicado.
5. De cada base se muestra solo el resultado de la **última** sentencia de consulta (la última que devolvió una tabla, aunque venga vacía).
6. La pantalla carga hasta 200,000 filas. **Exportar CSV** trae el resultado completo.
7. Abrir Faro **no conecta a nada**. Conecta cuando se despliega una base, se ejecuta o se prueba.
8. Hay **una sola ejecución a la vez**, y Faro **no traduce** SQL entre motores.

---

## 2. Qué es Faro y qué problema resuelve

**Faro es una aplicación de escritorio para Windows que ejecuta una misma consulta SQL, al mismo tiempo, contra muchas bases de datos**, y junta todos los resultados en una sola tabla que indica de qué base vino cada fila.

**El problema de negocio.** Una empresa con muchas bodegas o sucursales suele tener una base de datos por cada una. Revisar un dato en todas implica conectarse a cada base, correr la consulta, copiar el resultado y repetir. Con 20 bodegas son 20 veces el mismo trabajo, más juntar todo a mano.

**Cómo lo resuelve.** Se marcan las bodegas, se escribe la consulta una vez y se presiona **Ejecutar (F5)**. Faro consulta todas en paralelo, muestra el avance de cada una y entrega una tabla combinada lista para exportar a CSV.

Además, funciona como cliente SQL de uso diario:

- **catálogo de conexiones** con grupos y credenciales cifradas;
- **descubrimiento** de las bases de un servidor;
- **explorador de estructura**;
- **generación de scripts**;
- **comparación** de objetos entre bodegas;
- **importación de CSV**;
- **favoritos** e **historial**;
- **exportación e importación** de toda la configuración.

**Motores soportados:** PostgreSQL y SQL Server. En una misma ejecución se pueden mezclar bases de los dos.

**Para quién es:** soporte técnico, analistas y administradores que revisan, auditan o corrigen datos repartidos en muchas bases independientes.

**Instalación:**

- Se distribuye como una **carpeta portable** con `Faro.exe`, que ya incluye Java. No se instala nada y no requiere permisos de administrador.
- Solo funciona en **Windows**: el cifrado de contraseñas depende de Windows.
- La versión empaquetada puede usar hasta 4 GB de memoria.

---

## 3. Conceptos básicos

### 3.1 Base de datos registrada

Es cada conexión que el usuario da de alta. Es la unidad con la que trabaja todo lo demás: se marca, se consulta, se explora, se exporta.

| Dato | Para qué sirve | Valor inicial y reglas |
|---|---|---|
| **Alias** | Nombre que aparece en el árbol, en la columna *"Base de datos"* de los resultados, en la pestaña Ejecución y en el nombre sugerido del CSV. | Obligatorio. **Se permiten alias repetidos** (ver 32). |
| **Motor** | PostgreSQL o SQL Server. | PostgreSQL al agregar una nueva. |
| **Host** | IP o nombre del servidor. | Obligatorio. Se le quitan los espacios de los extremos. |
| **Puerto** | Puerto del servidor. | 5432 para PostgreSQL, 1433 para SQL Server. Al **agregar**, cambiar el motor cambia el puerto solo; al **editar**, no. Debe ser un número entero (no se valida el rango). |
| **Base de datos** | Nombre real de la base dentro del servidor. | Obligatorio. |
| **Usuario y Contraseña** | Credenciales propias de esa base. | Opcionales. Si se dejan vacías, se usan las **credenciales por defecto** (3.5). |
| **Modo** | *"Solo lectura"* o *"Sin restricciones"* (3.4). | *Solo lectura* al agregar. |
| **Conexiones del pool** | Cuántas conexiones simultáneas mantiene Faro abiertas contra esa base (ver 11.3). | El valor de Preferencias (4 de fábrica). **Mínimo 2**: Faro no deja guardar menos, porque cancelar una consulta necesita una conexión libre extra. |
| **Timeout de consulta (s)** | Cuántos segundos puede tardar **cada sentencia** antes de que se corte. | El valor de Preferencias (30 de fábrica). Acepta cualquier entero; 0 = sin límite. **Un número negativo también se acepta y hace fallar todas las ejecuciones de esa base** (34.38). |
| **Certificado** (*"Confiar en el certificado del servidor"*) | Solo SQL Server. Marcado: acepta el certificado que presente el servidor. Desmarcado: exige un certificado válido; es más seguro, pero falla si el servidor usa uno autofirmado (lo común en servidores internos). El tráfico a SQL Server **siempre va cifrado**. | Marcado. Visible pero deshabilitado si el motor es PostgreSQL. Texto de ayuda: *"Solo SQL Server. Desmarcado exige un certificado válido: más seguro, pero falla si el servidor usa uno autofirmado."* |
| **Codificación** | Solo PostgreSQL. Opciones: *"Automática (UTF-8)"*, LATIN1, WIN1252, SQL_ASCII, LATIN9, UTF8. Es para bases viejas que guardan acentos en otra codificación y fallan con `invalid byte sequence for encoding "UTF8"`. | *Automática*. Deshabilitado si el motor es SQL Server. Texto de ayuda: *"Solo PostgreSQL. Déjala en automática salvo que el esquema falle con "invalid byte sequence for encoding UTF8": ahí la base guarda texto en otra codificación (normalmente LATIN1 o WIN1252)."* Según el propio código, **no se ha probado** contra una base real con ese problema. |
| Identificador interno | Código único que el usuario no ve. Amarra las credenciales, la selección de cada pestaña y la configuración exportada. | Se genera solo; se conserva al exportar e importar. |

**Cómo se conecta:**

- **Autenticación.** Solo con usuario y contraseña del motor; **no hay autenticación de Windows**.
- **Instancias con nombre de SQL Server** (`SERVIDOR\INSTANCIA`). Faro siempre usa host y puerto, así que hay que capturar el puerto real de la instancia.
- **Cifrado en PostgreSQL.** Faro no ofrece opciones de cifrado. El controlador intenta cifrar si el servidor lo acepta, sin verificar el certificado, así que un servidor que exija un certificado verificado no es compatible **(depende del controlador)**.
- **Cómo te ve el administrador de base de datos.** Las sesiones aparecen con el nombre genérico del controlador, el usuario y la IP del equipo. En cada ejecución, Faro lanza además por cada base una consulta mínima (`SELECT pg_backend_pid()` o `SELECT @@SPID`) que usa para poder cancelar.

### 3.2 Grupo

Es **solo una carpeta para organizar** bases en el árbol (por ejemplo "Bodegas Norte" o "Servidor central"). No comparte host, motor ni credenciales: en un mismo grupo puede haber bases de PostgreSQL y de SQL Server, en servidores distintos.

### 3.3 Sin grupo

Las bases que no están en ningún grupo aparecen al final del árbol, bajo el encabezado **SIN GRUPO**. No es un grupo real: no se puede renombrar ni mover.

### 3.4 Modo de la base: Solo lectura / Sin restricciones

- **Solo lectura** (candado cerrado). Faro **rechaza el script completo** en esa base si **alguna** de sus sentencias no empieza con `SELECT`, `WITH`, `SHOW`, `EXPLAIN`, `DESCRIBE` o `DESC`. Los espacios y comentarios iniciales se ignoran y no importan mayúsculas o minúsculas. El rechazo ocurre antes de conectarse, así que en esa base no se ejecuta nada.
- **Sin restricciones** (candado abierto). Se ejecuta cualquier sentencia **sin pedir confirmación**.

Es una **protección contra accidentes**, no una barrera de seguridad: mira la primera palabra de cada sentencia, no entiende el SQL completo. Los huecos conocidos están en 33.2. No sustituye los permisos del usuario de base de datos: para una protección real, conviene conectar con un usuario que solo tenga permisos de lectura.

### 3.5 Credenciales propias y credenciales por defecto

Para conectarse a una base, Faro busca credenciales en este orden:

1. **Las propias de esa base**: capturadas al agregarla, editarla o descubrirla.
2. Si no tiene propias, **las credenciales por defecto** (*Conexiones → Credenciales por defecto…*).
3. Si tampoco hay, la base no se puede usar: *"Sin usuario/contraseña guardados"*.

Ojo: si alguna vez se guardaron credenciales por defecto **vacías**, el paso 3 ya no ocurre. La base intenta entrar con usuario vacío y recibe un error del servidor (34.43).

### 3.6 Selección de bases

Cada base del árbol tiene una **casilla**. **Las marcadas son las que se consultan.** La selección es **por pestaña de consulta** (9.4).

### 3.7 Pestaña de consulta

Cada pestaña del editor es un script independiente, con su propio texto, su propio archivo (si se guardó o abrió uno) y su propia selección de bases. Los paneles de abajo (Resultados, Ejecución, Diagnóstico) **son compartidos**: muestran la última ejecución, venga de la pestaña que venga.

### 3.8 Estado de conexión

Es el punto de color de cada base: dice si la última conexión real a esa base funcionó. Ver sección 11.

---

## 4. Abrir y cerrar la aplicación

### 4.1 Al abrir

1. Se abre la ventana *"Faro"*: 1280 × 800 píxeles, con un mínimo de 960 × 600.
2. Carga la configuración de `C:\Users\<usuario>\.faro\connections.json`: grupos, bases, favoritos, preferencias y pestañas abiertas.
3. Carga y descifra las credenciales de `C:\Users\<usuario>\.faro\credentials.dat`.
4. **Primera vez** (sin archivo): arranca vacía, sin datos de ejemplo.
5. **Si la configuración está dañada** o no se puede leer, arranca vacía en vez de fallar (ver el riesgo en 34.7). Si las credenciales no se pueden descifrar (por ejemplo, otra cuenta de Windows), arranca sin credenciales.
6. **Restaura las pestañas de consulta** de la sesión anterior: su texto (incluido lo que no se había guardado en archivo), su archivo asociado y sus bases marcadas.
   - El texto sale de la configuración de Faro, no del archivo en disco. Si el `.sql` cambió por fuera, no se nota.
   - Queda activa la última pestaña.
   - Si no había ninguna, abre una vacía, *"Consulta 1"*.
7. **No se conecta a ninguna base.** Los puntos de color muestran el estado guardado de la sesión anterior hasta que cada base se vuelva a usar.
8. Todos los grupos aparecen desplegados.
9. Aplica el tema, el color de acento y los tamaños de letra guardados.

### 4.2 Mientras está abierta

- **Autoguardado cada 2 minutos** de configuración y credenciales. El primero ocurre 2 minutos después de abrir.
- La barra de estado se actualiza cada 2.5 segundos.
- Si el equipo se suspende, no hay autoguardado mientras duerme. Al despertar, Faro guarda de inmediato.
- Faro **no impide abrirse dos veces**. Si la misma persona abre dos ventanas, las dos guardan sobre los mismos archivos y gana la última en guardar (34.35).

### 4.3 Al cerrar

Se cierra con la X de la ventana, con **Alt+F4** o con **Archivo → Salir**.

1. Por **cada pestaña con cambios sin guardar** (marcada con ●), abre la ventana *"Cambios sin guardar"*: *"La pestaña "X" tiene cambios sin guardar."* / *"¿Qué quieres hacer antes de cerrarla?"*.
   - Opciones: **Guardar**, **Descartar cambios**, **Cancelar**.
   - *Cancelar* en cualquiera **cancela el cierre** completo.
   - Si se elige *Guardar* en una pestaña sin archivo, pide dónde guardarla. Si se cancela ese paso, también se cancela el cierre.
2. Espera hasta 5 segundos a que termine un autoguardado en curso.
3. Cierra todas las conexiones abiertas contra las bases.
4. Guarda la configuración (incluidas las pestañas abiertas) y las credenciales cifradas. Si una de las dos falla, la otra se guarda igual.

> *"Descartar cambios"* solo se refiere al archivo `.sql`. El texto de esa pestaña igual queda en la configuración de Faro y reaparece al abrir.

**Faro no avisa si hay trabajo en curso** al cerrar; solo pregunta por las pestañas con cambios sin guardar. Al cerrar corta todas las conexiones:

- **Ejecución en curso:** se interrumpe. Lo ya confirmado queda aplicado; lo que estaba a medias lo deshace el servidor.
- **Exportación a CSV en curso:** el archivo queda incompleto, sin aviso.
- **Importación de CSV en curso:** no queda nada insertado.

Si Faro se cierra de golpe (proceso terminado, apagón), se pierde como máximo lo hecho desde el último autoguardado. Los archivos nunca quedan a medio escribir (29.2).

---

## 5. La pantalla principal

De arriba abajo:

1. **Barra de menús**: Archivo, Editar, Consulta, Conexiones, Ver, Herramientas, Ayuda (5.1).
2. **Barra de herramientas**:
   - **Ejecutar F5**: se vuelve **Cancelar**, en rojo, mientras hay una ejecución en curso.
   - **Nueva consulta**, **Abrir**, **Guardar**, **Formatear**, **Favorito**.
   - Contador *"N bases seleccionadas"*.
   - Botón de tema: luna para pasar a oscuro, sol para pasar a claro.
3. **Zona izquierda** (unos dos tercios de ancho para el centro y un tercio para esta zona; mínimo 220 px):
   - **Riel de íconos**: Conexiones, Historial, Favoritos y, abajo, el engrane de Preferencias.
   - **Panel** que cambia según el ícono. El de Conexiones tiene el buscador *"Buscar base…"*, el botón **Todas/Ninguna** y el botón **+** (agregar base), y debajo el árbol.
4. **Zona central**, en dos tarjetas:
   - Arriba, el **editor SQL** con sus pestañas y, cuando está abierta, la barra de búsqueda. Ocupa inicialmente la cuarta parte del alto.
   - Abajo, las pestañas **Resultados**, **Ejecución** y **Diagnóstico**, cada una con su contador. A la derecha, el botón **Exportar CSV**.
5. **Barra de estado** (sección 28).

Los separadores entre zonas se pueden arrastrar. El panel izquierdo conserva su ancho al cambiar el tamaño de la ventana.

### 5.1 Menús completos

| Menú | Opción | Atajo | Qué hace |
|---|---|---|---|
| Archivo | Nueva consulta | Ctrl+T | Pestaña vacía (15.1). |
| | Abrir archivo .sql… | Ctrl+O | Abre un `.sql` en pestaña nueva (15.2). |
| | Guardar | **Ctrl+G** | Guarda el script de la pestaña activa. |
| | Guardar como… | — | Guarda en un archivo nuevo. |
| | Exportar resultados a CSV… | — | Igual que el botón Exportar CSV (sección 21). |
| | Exportar script SQL… | — | Es lo mismo que *Guardar como…* |
| | Salir | Alt+F4 | Cierra la aplicación (4.3). |
| Editar | Buscar en el script | Ctrl+F | Barra de búsqueda (15.4). |
| | Formatear SQL | Ctrl+L | Da formato al script (15.5). |
| | Autocompletado | Ctrl+Espacio | Sugerencias (15.6). |
| Consulta | Ejecutar en las bases seleccionadas | F5 | Ejecución masiva (sección 16). |
| | Cancelar ejecución | — | Cancela las bases que sigan corriendo (16.12). |
| | Guardar como favorito | — | Sección 24. |
| | Explicar plan de ejecución | — | Sección 20. |
| Conexiones | Agregar base de datos… | — | 6.1. |
| | Nuevo grupo de conexiones… | — | 7.1. |
| | Descubrir bases en esta IP… | — | 6.6. |
| | Probar todas las conexiones | — | 6.7. |
| | Credenciales por defecto… | — | Sección 25. |
| | Importar configuración… | — | 26.3. |
| | Exportar configuración… | — | 26.1. |
| Ver | Panel de conexiones | Alt+1 | Muestra el árbol. |
| | Historial | Alt+2 | Muestra el historial. |
| | Favoritos | Alt+3 | Muestra los favoritos. |
| Herramientas | Importar CSV a una tabla… | — | Sección 22. |
| | Preferencias… | — | Sección 27. |
| Ayuda | Atajos de teclado | — | Abre Preferencias en la pestaña *Atajos*. |
| | Acerca de Faro | — | Ventana *"Acerca de Faro"*, título *"Faro — cliente SQL multi-base"*, texto *"Reemplazo en JavaFX del cliente Flutter original. Consulta ligera y masiva contra muchas bodegas/sucursales a la vez."* y la versión de Java. |

---

## 6. Catálogo de bases de datos

### 6.1 Agregar una base

Se abre con el botón **+** del panel o con **Conexiones → Agregar base de datos…**. Es una ventana *"Agregar base de datos"* con todos los campos de 3.1. Se puede agrandar arrastrando el borde.

**Valores iniciales:**

- motor PostgreSQL, puerto 5432, modo Solo lectura;
- pool y timeout tomados de Preferencias;
- certificado marcado, codificación automática;
- usuario y contraseña vacíos.

**Al presionar Guardar**, Faro valida:

| Problema | Mensaje |
|---|---|
| Alias, host o nombre de base vacíos, o puerto, pool o timeout que no son números | *"Completa alias, host, puerto y base de datos."* (el mismo aviso aunque el problema sea el pool o el timeout, ver 34.22) |
| Pool menor que 2 | *"El tamaño de pool mínimo es 2 — con menos, cancelar una consulta puede no funcionar bien."* |

**Si todo es válido:**

- La base se agrega **al final de SIN GRUPO**.
- Si se escribió usuario, se guardan usuario y contraseña como credenciales propias. Si el usuario quedó vacío, la base usará las credenciales por defecto.
- El árbol baja hasta la base nueva y la deja resaltada. **Su casilla no se marca.**
- Diagnóstico: *"Base agregada: <alias>"*.
- No se abre ninguna conexión.

**Cancelar** o cerrar la ventana no guarda nada.

### 6.2 Probar conexión (dentro del formulario)

El botón **Probar conexión** intenta conectarse **con lo que está escrito en el formulario en ese momento**, incluidos el certificado y la codificación, sin guardar nada.

- Si el campo usuario está vacío, prueba con las credenciales que Faro usaría de verdad: las propias ya guardadas o, si no hay, las por defecto. **Ojo al editar:** si se vacía el usuario para que la base pase a usar las por defecto, la prueba sigue usando las propias anteriores hasta que se guarda.
- Mientras prueba: *"Conectando…"*, con el botón deshabilitado. La ventana no se congela, aunque la prueba puede tardar varios segundos si el servidor no responde **(depende del motor/controlador)**.
- Si conecta: *"Conectado — <versión del motor>"*, en verde.
- Si falla: *"Error de conexión: <motivo del servidor o del controlador>"*, en rojo.
- Si faltan datos: *"Completa alias, host, puerto y base de datos primero."*
- La prueba **no cambia** el punto de color del árbol y no deja conexiones abiertas.

### 6.3 Editar una base

Se abre con el **lápiz** de la fila o con **doble clic sobre el alias**. Es el mismo formulario, con el título *"Editar base de datos"* y los datos actuales.

- Usuario y contraseña muestran solo las credenciales **propias**. Si la base usa las por defecto, aparecen vacíos.
- Cambiar el motor al editar **no** cambia el puerto.
- **Dejar el usuario vacío y guardar borra las credenciales propias**: la base pasa a usar las por defecto.
- Al guardar, Faro cierra las conexiones abiertas contra esa base (11.3), para que la siguiente operación use los datos nuevos. Lo hace **aunque no se haya cambiado nada**. Si esa base estaba ejecutando algo en ese momento, probablemente la consulta se corta y termina en ERROR (34.40).
- **No** descarta la estructura de esa base que ya estaba cargada en memoria (34.12).
- No deja línea en Diagnóstico.

### 6.4 Eliminar una base

Con el **bote de basura** de la fila o con clic derecho → **Eliminar esta base**.

- Pide confirmación: título *"Eliminar base de datos"*, texto *"¿Eliminar "<alias>"?"* / *"Esta acción no se puede deshacer. Las credenciales guardadas para esta base también se van a borrar."*
- Botones **Eliminar** y **Cancelar**. Ninguno se activa con Enter por accidente.
- Al confirmar:
  - la base sale del árbol;
  - se borran sus credenciales propias;
  - se cierran sus conexiones (si estaba ejecutando algo, probablemente se corta);
  - se olvida su estructura en memoria.
- Diagnóstico: *"Base eliminada: <alias>"*.
- Una pestaña que la tenía marcada, cuando no está activa, muestra *"base no encontrada"* en su encabezado.
- **No hay papelera ni forma de deshacer.** La única forma de recuperarla es volver a darla de alta o importar una configuración que la contenga.

### 6.5 Cambiar el modo con el candado

Un clic en el **candado** alterna entre Solo lectura y Sin restricciones.

- Inmediato y **sin confirmación**, aunque se pase a *Sin restricciones*.
- Barra de estado: *"<alias> ahora es Sin restricciones."* (o *"… Solo lectura."*). Diagnóstico: *"<alias>: modo cambiado a <modo> desde el árbol."*
- También se cambia desde el formulario de edición.
- Al pasar el ratón por el candado aparece el nombre del modo.
- Se guarda con la configuración.

### 6.6 Descubrir bases en un servidor

Sirve para dar de alta de golpe todas las bases de un servidor. Hay dos entradas:

- **Conexiones → Descubrir bases en esta IP…**: el host empieza vacío.
- Clic derecho sobre una base → **Descubrir bases en esta IP…**: el host viene con el de esa base.

La ventana *"Descubrir bases de datos"* dice *"Busca bases PostgreSQL/SQL Server accesibles en un host, con el usuario/contraseña dados."*

**Pasos:**

1. Usuario y contraseña vienen con las credenciales por defecto; se pueden cambiar.
2. **Buscar**.
   - Sin host: *"Escribe un host."*
   - Si hay host: *"Buscando…"*.
   - Faro revisa los puertos 5432 (PostgreSQL) y 1433 (SQL Server), con 0.8 segundos de espera por puerto.
   - Solo si el puerto responde, inicia sesión en la base de mantenimiento (`postgres` o `master`) y lista las bases del servidor, en orden alfabético.
   - **Ojo:** el catálogo del servidor normalmente muestra **todas** las bases a cualquier usuario, no solo las que puede abrir. Las que no pueda abrir se agregan igual y quedan en rojo al usarlas **(depende del motor)**.
3. **Qué excluye**:
   - PostgreSQL: las plantillas, las que no aceptan conexiones y la base `postgres`.
   - SQL Server: `master`, `tempdb`, `model`, `msdb` y las que no están en línea.
4. **Resultado**: una casilla por base, con texto como `PG  bodega_norte` o `MSSQL  ventas`. Estado: *"N base(s) encontradas · M nueva(s)."*
5. **Las que ya están registradas** aparecen **apagadas** con *"— ya agregada"* y no se pueden volver a agregar. Cuentan como registradas si coinciden el motor, el host tal como se escribió (sin distinguir mayúsculas), el puerto por defecto y el nombre (tampoco distingue mayúsculas). Una base registrada con otro puerto o con otro nombre de host (por ejemplo, un nombre DNS en vez de la IP) no se reconoce como repetida.
6. Botones **Todas** y **Ninguna**. Solo tocan las que se pueden agregar, y se deshabilitan si no hay ninguna nueva. Texto de ayuda: *"Las que ya tienes agregadas salen apagadas."*
7. **Agregar seleccionadas** cierra la ventana y da de alta cada base marcada con:
   - alias = nombre de la base;
   - host tal como se escribió y puerto por defecto del motor;
   - modo **Solo lectura**;
   - pool **4** y timeout **30** fijos (no los de Preferencias, 34.21);
   - certificado marcado y codificación automática;
   - **el usuario y la contraseña de la ventana, guardados como credenciales propias** de cada base.
8. **Dónde quedan**:
   - desde el menú: en **SIN GRUPO**;
   - desde el clic derecho: **en el mismo grupo que la base de origen**, o en SIN GRUPO si esa base estaba suelta.
9. El árbol baja hasta la última agregada.
   - Mensaje desde el menú: *"N base(s) agregada(s) desde el escaneo."*
   - Mensaje desde el clic derecho: *"N base(s) agregada(s) en <grupo>."*

**Otros detalles:**

- Un servidor por búsqueda; no escanea rangos de IP.
- El alias que se asigna es el nombre de la base. Dos servidores con una base "ventas" dan dos alias "ventas" idénticos; conviene renombrarlos (32).
- Como cada base descubierta guarda su **propia copia** de usuario y contraseña, cambiar después las credenciales por defecto **no** les afecta (25.1).
- Solo busca en los puertos por defecto.
- Si el usuario o la contraseña están mal, **el mensaje es el mismo que si no hubiera bases**: *"No se encontró ninguna base accesible en ese host."*
- Si se vuelve a presionar Buscar antes de que termine, los resultados de la búsqueda anterior se descartan.
- En SQL Server, el descubrimiento siempre acepta el certificado del servidor.
- **Cerrar** sale sin agregar nada.

### 6.7 Probar todas las conexiones

**Conexiones → Probar todas las conexiones** prueba **todas las bases registradas**, marcadas o no, incluso las ocultas por el buscador.

- Van **una por una**, no en paralelo. El orden es primero SIN GRUPO y luego grupo por grupo, así que no es exactamente el del árbol.
- Una base que no responde puede tardar de 10 a 30 segundos, y el total se acumula **(depende del motor/controlador)**.
- **No se puede cancelar.**
- Si no hay bases: *"No hay bases configuradas."*
- Durante la prueba: *"Probando N conexión(es)…"*; el punto de cada base se pone **amarillo** mientras se prueba y luego queda verde o rojo.
- Al final: *"M/N conexión(es) exitosa(s)."*, más *" Ver Diagnóstico."* si hubo fallas.
- Diagnóstico registra *"Probar todas las conexiones: M/N exitosas."* y una advertencia por cada falla: *"Conexión falló — <alias>: <motivo real>"*.
- Una base sin credenciales cuenta como falla (*"sin usuario/contraseña guardados"*), pero su punto no cambia.
- Esta prueba usa conexiones sueltas: no deja conexiones abiertas y **no renueva** las que ya estaban abiertas (ver 34.8).

---

## 7. Grupos y orden del árbol

### 7.1 Crear un grupo

**Conexiones → Nuevo grupo de conexiones…** pide *"Nombre del grupo:"* y crea el grupo vacío. Mensaje: *"Grupo creado: <nombre>"*. Se aceptan nombres repetidos. Si hay dos grupos con el mismo nombre, *Mover a grupo…* muestra el nombre dos veces y **siempre mueve al primero** (34.39).

> **Importante:** un grupo **sin bases no se ve en el árbol**. Aparece en cuanto se le mueve la primera base (34.11).

### 7.2 Mover una base a un grupo

Clic derecho sobre la base → **Mover a grupo…**. Se abre una lista (*"Mover "<alias>" a:"*) con:

- *"(Sin grupo)"*;
- todos los grupos existentes, incluidos los vacíos e invisibles;
- *"(Nuevo grupo…)"*, que pide el nombre del grupo nuevo en un segundo paso.

Reglas:

- La lista viene en el grupo actual; confirmarlo sin cambiar no hace nada.
- Si el nombre escrito en *"(Nuevo grupo…)"* ya existe, la base va a ese grupo en vez de crear otro.
- La base queda **al final** del grupo destino.
- Mensaje: *"<alias> movida a <grupo>."*

### 7.3 Renombrar un grupo

Clic derecho sobre el grupo → **Renombrar grupo…**. Si el nombre queda vacío o igual, no pasa nada. Se permite repetir el nombre de otro grupo. Mensaje: *"Grupo renombrado: <nombre>"*.

### 7.4 Cambiar el orden

- Clic derecho sobre un grupo → **Subir** / **Bajar**: lo mueve una posición entre los grupos.
- Clic derecho sobre una base → **Subir** / **Bajar**: la mueve una posición **dentro de su grupo**. Para cambiarla de grupo se usa *Mover a grupo…*
- Atajo: **Alt+↑ / Alt+↓** sobre la fila resaltada (grupo o base).
- En el primer o último lugar no hace nada.
- **Con texto en el buscador no se permite reordenar**: *"Limpia el buscador para cambiar el orden — con un filtro puesto, mover afectaría filas que no estás viendo."*
- El orden se guarda y se conserva entre sesiones.

### 7.5 Ordenar alfabéticamente

- Grupo → **Ordenar sus bases A-Z**.
- Encabezado SIN GRUPO → **Ordenar A-Z**.

Ordena por alias sin distinguir mayúsculas. Mensaje: *"Bases ordenadas A-Z en <grupo>."* No existe una opción para ordenar los grupos entre sí.

### 7.6 Lo que no existe para grupos

- **No se puede eliminar un grupo** (34.11).
- No se puede arrastrar y soltar; el orden se cambia con menús o con Alt+↑/↓.

---

## 8. El árbol de conexiones, fila por fila

### 8.1 Tipos de fila

| Fila | Qué muestra |
|---|---|
| **Grupo** | Nombre y cantidad **total** de bases del grupo (aunque el buscador oculte algunas). Flecha para desplegar o plegar. |
| **SIN GRUPO** | Encabezado en mayúsculas de las bases sueltas. |
| **Base** | Casilla, punto de estado, alias y debajo `host:puerto`, candado, insignia de motor (**PG** en azul, **MSSQL** en rojo), lápiz y bote de basura. Todos siempre visibles. |
| **Categoría** | Tablas, Vistas, Funciones, Procedimientos, Triggers o Tipos, con su cantidad una vez cargada. |
| **Objeto** | Nombre de la tabla, vista, función, etc. Tablas y vistas llevan ícono de tabla; el resto, de engrane. |
| **Cargando** | Indicador girando y *"Cargando tablas…"* (o la categoría que sea). |
| **Error** | *"Error al cargar: <motivo>"*. El texto completo aparece al pasar el ratón. |

Las flechas de desplegar y el alto de las filas crecen o se achican con el tamaño de letra de la interfaz.

### 8.2 Qué hace cada clic sobre una fila de base

| Acción | Resultado |
|---|---|
| Clic en la casilla | Marca o desmarca. |
| **Clic en el alias** | Marca o desmarca la casilla. |
| **Doble clic en el alias** | Abre *Editar base de datos*. |
| Clic en el candado | Alterna el modo (6.5). |
| Clic en el lápiz | Editar. |
| Clic en el bote de basura | Eliminar, con confirmación. |
| Flecha de la fila | Despliega el explorador de esquema (sección 12) y **abre una conexión** (11.2). |
| Ratón sobre el punto | *"Conexión: exitosa"*, *"Conexión: falló"*, *"Probando conexión…"* o *"Conexión: nunca probada"*. |
| Ratón sobre el candado | *"Solo lectura"* o *"Sin restricciones"*. |

El doble clic abre la edición **solo** si se hace sobre el texto del alias; en el resto de la fila no.

### 8.3 Menús de clic derecho

**Sobre una base:**

1. Nueva consulta para esta base (9.3)
2. Descubrir bases en esta IP… (6.6)
3. Recargar esquema (12.6)
4. Subir / Bajar (7.4)
5. Mover a grupo… (7.2)
6. Eliminar esta base (6.4)

**Sobre un grupo:**

1. Marcar todas las de este grupo
2. Desmarcar todas las de este grupo
3. Renombrar grupo…
4. Subir / Bajar
5. Ordenar sus bases A-Z

**Sobre SIN GRUPO:** Marcar todas las de aquí, Desmarcar todas las de aquí, Ordenar A-Z.

**Sobre un objeto de esquema:** las opciones de "Generar…" de su tipo (sección 13) y **Comparar en las bases marcadas…** (sección 14).

### 8.4 El árbol conserva su estado

Cada cambio (agregar, editar, eliminar, mover, descubrir, importar, buscar) redibuja el árbol. Aun así:

- conserva las casillas marcadas, salvo con el buscador (34.5);
- conserva qué grupos estaban plegados;
- vuelve a abrir las bases que tenían su esquema desplegado, excepto cuando el cambio viene del buscador;
- conserva la posición del scroll.

Qué grupos estaban plegados **no se recuerda entre sesiones**: al abrir, todos aparecen desplegados.

---

## 9. Elegir contra qué bases se consulta

### 9.1 Formas de marcar

- La casilla, o un clic en el alias.
- **Todas / Ninguna** del panel: si todas las bases visibles están marcadas, las desmarca; si no, las marca todas. El texto del botón cambia solo. Con el buscador activo, **actúa solo sobre las visibles**. "Visibles" quiere decir las que pasan el buscador: las de grupos plegados **sí** cuentan.
- Por grupo: clic derecho → *Marcar* / *Desmarcar todas las de este grupo* (o *de aquí* en SIN GRUPO). Mensaje: *"Marcadas N base(s) de <grupo>."* También actúa solo sobre las visibles.

### 9.2 Dónde se ve la selección

- En la barra de herramientas: *"1 base seleccionada"* / *"N bases seleccionadas"* (cuenta solo las visibles).
- En la **segunda línea del encabezado de cada pestaña**, en letra pequeña:

  | Selección | Texto |
  |---|---|
  | Una base | El alias |
  | Varias | *"N bases"* |
  | Ninguna | *"sin base seleccionada"* |
  | La base guardada ya no existe | *"base no encontrada"* |

### 9.3 Abrir una consulta ya asociada a una base

Clic derecho sobre una base → **Nueva consulta para esta base**:

- abre una pestaña nueva donde **solo esa base** queda marcada;
- las casillas de la pestaña anterior quedan guardadas en ella, sin cambios;
- mensaje: *"Nueva consulta para <alias> — su casilla ya quedó marcada."*

Las opciones "Generar…" del explorador (sección 13) hacen lo mismo con la base dueña del objeto.

### 9.4 Cada pestaña recuerda sus bases

- Al cambiar de pestaña, Faro guarda las bases marcadas en la que se deja y **marca en el árbol exactamente las de la pestaña que se activa**.
- Una pestaña nueva **hereda una copia** de las bases marcadas en ese momento. Aplica a Ctrl+T, *Nueva consulta*, *Abrir archivo* y abrir desde Historial o Favoritos.
- En la pestaña activa, el encabezado sigue las casillas en vivo.
- La selección de cada pestaña se guarda al cerrar y se restaura al abrir.

---

## 10. Buscador de bases

La caja *"Buscar base…"* sobre el árbol:

- Filtra unos 200 ms después de dejar de escribir y no distingue mayúsculas.
- Muestra una base si **su alias contiene el texto**, o si **alguno de los nombres de su estructura ya cargada** (tablas, vistas, funciones, procedimientos, triggers, tipos) lo contiene.
- **No busca por host, IP ni nombre real de la base.**
- La búsqueda por estructura solo ve lo que ya se cargó en esta sesión (al desplegar categorías). No consulta ninguna base.
- Una base que aparece por su estructura, y no por su alias, se muestra **desplegada**, con solo las categorías y objetos que coinciden:
  - el número junto a cada categoría es **la cantidad de coincidencias**, no el total;
  - no se puede ver la lista completa hasta borrar la búsqueda;
  - cada vez que se aplica el filtro, esas bases **abren una conexión** para comprobar su punto (11.2).
- Los grupos sin coincidencias desaparecen mientras se busca.
- Mientras hay texto en el buscador no se puede reordenar (7.4).
- Al limpiar la búsqueda, **las bases que estuvieron ocultas pierden su marca** (34.5).

---

## 11. Estado de conexión y conexiones abiertas

### 11.1 Colores del punto

| Color | Significado |
|---|---|
| **Gris** | Nunca se probó o no hay dato. |
| **Amarillo** | Probándose ahora mismo (solo durante *Probar todas las conexiones*). |
| **Verde** | La última conexión real funcionó. |
| **Rojo** | La última conexión real falló. |

El punto verde o rojo lleva además un aro suave del mismo tono.

### 11.2 Cuándo cambia

Siempre por una conexión real, nunca por adivinar:

- **Al desplegar una base** en el árbol, o al *Recargar esquema*: se abre una conexión para comprobarla. Si la base no tiene credenciales, queda en rojo sin intentar.
- **Al ejecutar una consulta**:
  - Verde si se conectó.
  - Un error de SQL (sintaxis, permisos sobre una tabla) **no** lo pone en rojo: la conexión sí funcionó.
  - Se pone rojo cuando **no se pudo abrir la primera conexión de la sesión** con esa base (servidor caído, contraseña incorrecta).
  - Si esa base ya tenía conexiones abiertas de antes y el servidor se cae después, el error aparece en la pestaña Ejecución pero **el punto sigue verde** (34.9).
- **Con *Probar todas las conexiones*.**
- **Nunca al abrir la aplicación.**

**Se guarda entre sesiones**, solo verde o rojo. Por eso un verde puede estar desactualizado (por ejemplo, si cambió la contraseña) hasta que la base se vuelva a usar.

**Mientras una base ejecuta una consulta, su punto parpadea.** Deja de parpadear cuando esa base termina, falla o se cancela.

### 11.3 Conexiones que Faro mantiene abiertas

- La primera vez en la sesión que Faro usa una base, le abre un **grupo de conexiones** (el "pool").
  - Esto pasa al desplegarla, ejecutar, explicar un plan, generar un script, comparar, hacer una exportación completa, importar CSV o autocompletar.
  - Faro **llena ese grupo hasta el número de *Conexiones del pool*** (4 de fábrica) y **lo mantiene abierto el resto de la sesión** **(comportamiento de la librería de conexiones)**.
  - Ejemplo: usar 50 bodegas con pool 4 puede dejar hasta 200 conexiones abiertas contra los servidores mientras Faro esté abierto. Esas conexiones se renuevan solas cada 30 minutos **(comportamiento de la librería)**.
  - **Riesgo para los servidores:** si muchas bases viven en el mismo servidor, sus conexiones se suman contra el límite de ese servidor (en PostgreSQL, 100 de fábrica). Por ejemplo, 30 bodegas × 4 = 120 conexiones pueden provocar errores *"too many clients"* **para Faro y para el sistema de la empresa**. Con varias personas usando Faro, se multiplica. Recomendación: pool 2 en bases que comparten servidor (34.44).
- Esas conexiones se cierran solo al **editar** esa base, **eliminarla**, **importar configuración** o **cerrar Faro**.
- **Qué pasa si están todas ocupadas:** una nueva petición a esa base espera hasta 30 segundos y luego falla **(comportamiento de la librería)**.
- **Quiénes no dejan conexiones abiertas:** *Probar conexión*, *Probar todas las conexiones* y *Descubrir* usan conexiones sueltas y las cierran al terminar.
- La barra de estado muestra cuántas bases tienen su grupo abierto y cuántas conexiones hay ocupadas y abiertas (sección 28).
- Las conexiones de cada grupo se crean con **las credenciales vigentes al abrirlo**. Si después cambian las credenciales por defecto, esas bases siguen usando las anteriores hasta cerrar Faro (34.8).

---

## 12. Explorador de esquema

Al desplegar una base se ve su estructura. Todo se carga **bajo demanda**.

### 12.1 Desplegar una base

1. Aparecen al instante sus **6 categorías**: Tablas, Vistas, Funciones, Procedimientos, Triggers y Tipos, todavía sin cantidad.
2. Se abre la conexión de comprobación (11.2).
3. **No se consulta ninguna estructura todavía.**

### 12.2 Desplegar una categoría

1. Aparece un indicador girando: *"Cargando tablas…"*, *"Cargando vistas…"*, *"Cargando funciones…"*, *"Cargando procedimientos…"*, *"Cargando triggers…"* o *"Cargando tipos…"*.
2. Faro consulta esa categoría.
   - Como máximo **3 cargas corren a la vez en toda la aplicación**, sean de la misma base o de bases distintas; las demás esperan turno.
   - Si hay 3 bases que no responden, todas las demás cargas esperan hasta que esas fallen por tiempo (34.30).
3. Se muestran los nombres y la **cantidad** junto a la categoría. Una categoría vacía muestra 0 y conserva su flecha.
4. **Tablas y Vistas se cargan juntas**: desplegar una deja la otra lista al instante, **siempre que la primera ya haya terminado**. Si se despliega la segunda mientras la primera todavía carga, la segunda se queda en "Cargando…" indefinidamente (34.26).
5. Si falla: *"Error al cargar: <primera línea del motivo>"*, con el motivo completo al pasar el ratón.
   - Sin credenciales: *"Error al cargar: Sin usuario/contraseña guardados para <alias>"*.
   - Si es el error de codificación de PostgreSQL, se agrega: *"— el texto de esta base no es UTF-8 válido. Editar base → Codificación (prueba LATIN1 o WIN1252)."*
   - Estos errores **no** se registran en Diagnóstico.
   - **Plegar y volver a desplegar no reintenta.** Hay que usar *Recargar esquema*, o esperar a que el árbol se redibuje por otra acción.
6. Cada vez que el árbol se redibuja (agregar, editar, mover, importar…), las bases que estaban desplegadas se reabren, pero **con sus categorías plegadas**, y cada una **vuelve a abrir una conexión** para comprobar su punto. Lo ya cargado aparece al instante al desplegar.

### 12.3 Qué entra en cada categoría

Solo el **esquema por defecto**: `public` en PostgreSQL y `dbo` en SQL Server. Lo que esté en otros esquemas no aparece.

| Categoría | PostgreSQL | SQL Server | Orden |
|---|---|---|---|
| Tablas | Tablas normales. Cada partición de una tabla particionada aparece como tabla aparte. **No aparecen:** la tabla particionada "madre", las tablas foráneas ni las secuencias **(depende del controlador)**. | Tablas de usuario. No aparecen sinónimos ni secuencias. | Alfabético |
| Vistas | Vistas normales. **No aparecen las vistas materializadas** **(depende del controlador)**. | Vistas. | Alfabético |
| Funciones | Funciones normales, **incluidas las funciones de trigger** y las que instalen extensiones en `public`. No aparecen las de agregado ni las de ventana. | Funciones escalares, en línea y de tabla escritas en T-SQL. No aparecen las CLR. | PG alfabético; SQL Server **sin orden garantizado** |
| Procedimientos | Procedimientos. | Procedimientos T-SQL. No aparecen los CLR ni los extendidos. | PG alfabético; SQL Server **sin orden garantizado** |
| Triggers | Triggers de tablas y vistas. No aparecen los internos (los que el motor crea para las llaves foráneas) ni los que solo reaccionan a TRUNCATE. **Ojo:** PostgreSQL solo informa los triggers de tablas donde el usuario es dueño o tiene algún permiso **distinto de SELECT**, así que con un usuario de solo lectura la lista puede salir vacía **(regla del motor; no probado en vivo)**. Los deshabilitados aparecen igual, sin marca. | **Solo triggers de tablas**: no aparecen los de vistas ni los de base de datos o servidor. Los deshabilitados aparecen igual. | **Sin orden garantizado** |
| Tipos | Enumeraciones, dominios y tipos compuestos creados por el usuario o por extensiones. No aparecen el tipo interno de cada tabla, los tipos rango ni los tipos base. | Todos los tipos definidos por el usuario (alias, de tabla y, si hubiera, CLR). | Alfabético |

Más detalles:

- **Orden alfabético en PostgreSQL:** es por código de carácter, así que las mayúsculas van antes que las minúsculas (`Zona` antes que `almacen`) y las letras acentuadas y la ñ van al final. En SQL Server el orden sigue la intercalación de la base, normalmente sin distinguir mayúsculas.
- **Extensiones de PostgreSQL** (uuid-ossp, pg_trgm, PostGIS…) instaladas en `public`: sus objetos salen **mezclados con los propios**.
- **PostgreSQL 10 o anterior:** Funciones y Procedimientos probablemente fallan al cargar (34.31).
- **Nombres con mayúsculas, espacios o símbolos:** se muestran tal como están guardados; los scripts generados no los encierran entre comillas (34.24).

**Nombres repetidos (PostgreSQL):**

- Dos triggers con el mismo nombre en tablas distintas aparecen como `tabla.trigger`.
- Funciones o procedimientos con el mismo nombre y distintos parámetros aparecen con su firma, tal como la da el servidor e incluidos los nombres de los parámetros; por ejemplo `calcular(p_id integer, p_texto text)`.
- Los nombres únicos se muestran tal cual.

**Los objetos no se despliegan:** una tabla no muestra sus columnas en el árbol (34.33).

### 12.4 Doble clic sobre un objeto

- Tabla o vista → **Generar SELECT** (sección 13).
- Otros objetos → nada; se usa el clic derecho.

### 12.5 Memoria de la estructura

Lo cargado queda en memoria **mientras Faro está abierto** y volver a desplegar es instantáneo. Quién lo usa:

- **Buscador:** los nombres de las 6 categorías.
- **Autocompletado:** solo tablas, vistas y las columnas traídas con *Generar…*.
- **Generar…:** las columnas y los scripts CREATE ya generados.

**No se actualiza sola** si alguien cambia la base desde otro lado. Por ejemplo, si se modifica una función en el servidor, *Generar script CREATE* sigue mostrando la versión vieja hasta *Recargar esquema*; *Comparar*, en cambio, siempre lee lo actual.

Se olvida con:

- *Recargar esquema* (esa base);
- *Eliminar* la base;
- *Importar configuración* (todas).

**No** se olvida al *Editar* la base (34.12).

### 12.6 Recargar esquema

Clic derecho sobre la base → **Recargar esquema**:

- olvida todo lo cargado de esa base: tablas, columnas, definiciones y categorías;
- redibuja las 6 categorías vacías y vuelve a comprobar la conexión;
- cada categoría se recarga al desplegarla;
- si había una carga en curso, su resultado se descarta al llegar. Si se despliega esa misma categoría antes de que la carga vieja termine, se queda en "Cargando…" (34.26);
- funciona también con la base plegada: no la despliega, pero sí comprueba la conexión;
- no hay forma de recargar una sola categoría, y las filas de categoría no tienen menú de clic derecho;
- si se usa mientras el buscador muestra la base por su estructura, la base queda **sin categorías** hasta borrar la búsqueda (34.27).

---

## 13. Generar scripts desde el esquema

Clic derecho sobre un objeto:

| Tipo | Opciones |
|---|---|
| Tabla | Generar SELECT, INSERT, UPDATE, DELETE, script CREATE, y Comparar en las bases marcadas… |
| Vista | Generar SELECT, script CREATE, y Comparar… |
| Función, Procedimiento, Trigger, Tipo | Generar script CREATE, y Comparar… |

### 13.1 Qué produce cada opción

Reglas comunes:

- **Prefijo de esquema.** Los scripts de tabla que arma Faro (SELECT, INSERT, UPDATE, DELETE, CREATE TABLE) **no lo llevan**: `FROM productos`, no `FROM dbo.productos`. Los de vistas y tipos **sí** (`public.` / `dbo.`). En funciones, procedimientos y triggers depende de lo que dé el servidor (13.1, script CREATE).
- **Los nombres no se ponen entre comillas ni corchetes** (34.24). Ejemplos:
  - En PostgreSQL, una tabla creada como `"Clientes"` genera `FROM Clientes`, que falla porque el motor busca `clientes`.
  - En SQL Server, una columna `Fecha Alta` genera `SELECT id, Fecha Alta …`, que falla.
  - En PostgreSQL, una columna llamada `user` genera `SELECT id, user …`, que **devuelve el usuario conectado en vez de la columna, sin error**.

**SELECT.** Todas las columnas reales en su orden, **en una sola línea**, sin punto y coma y sin límite de filas. `SELECT *` solo sale si la consulta de columnas funcionó pero no trajo ninguna (tabla borrada o sin columnas visibles para ese usuario, ver 34.29). Si la consulta **falla**, no se genera nada.

```sql
SELECT id, nombre, precio FROM productos
```

**INSERT.** Los `<...>` son marcadores para reemplazar a mano.

```sql
INSERT INTO productos (id, nombre, precio)
VALUES (<id>, <nombre>, <precio>);
```

**UPDATE.** Las columnas que no son llave primaria van en `SET` y la llave primaria en `WHERE` (unida con `AND` si es compuesta).

```sql
UPDATE productos
SET
    nombre = <nombre>,
    precio = <precio>
WHERE
    id = <id>;
```

Sin llave primaria, el WHERE queda como `-- TODO: agrega condición WHERE (no se encontró llave primaria);`, para no generar nunca un UPDATE sin condición. El `;` queda dentro del comentario, así que ejecutarlo tal cual da **error de sintaxis y no modifica nada**. Si todas las columnas son llave, el SET queda como `-- (sin columnas que actualizar)`.

**INSERT y UPDATE incluyen todas las columnas**, también las autonuméricas (`serial` / IDENTITY) y las calculadas. En SQL Server, un INSERT con la columna IDENTITY falla si no se quita a mano.

**DELETE.** `DELETE FROM tabla` / `WHERE` / `<llave primaria>;` en tres líneas, o el comentario TODO si no hay llave.

**Script CREATE de tabla.** Se reconstruye con cada columna, su tipo, `NOT NULL` cuando aplica y `PRIMARY KEY (...)`.

- **Longitud y precisión:** solo se agrega longitud a los tipos de texto y binarios, y precisión y escala a `numeric`/`decimal`. Ejemplos: `nvarchar(200)`, `nvarchar(MAX)`, `decimal(10,2)`.
- **PostgreSQL usa los nombres largos** de los tipos: `character varying(200)`, `timestamp without time zone`.
- **Se pierde** la precisión de `timestamp(n)`, `time(n)`, `datetime2(n)`, `datetimeoffset(n)` y `float(n)`.
- **No incluye:** llaves foráneas, índices, UNIQUE, valores por defecto, CHECK, intercalación, columnas calculadas ni autonumérico (`serial` pasa a `integer`, IDENTITY a `int`).
- **Llave primaria:** lista las columnas en el orden de la tabla, no en el orden de la llave.
- **Tipos que salen inválidos:**
  - PostgreSQL: las columnas de arreglo o de tipos del usuario salen como `ARRAY` o `USER-DEFINED` (34.24).
  - SQL Server: `xml`, `text`, `ntext` e `image` probablemente salgan con un largo que no es sintaxis válida, como `text(2147483647)`.
- Es un punto de partida, no una copia exacta.

```sql
CREATE TABLE productos (
    id integer NOT NULL,
    nombre character varying(100),
    precio numeric(10,2),
    PRIMARY KEY (id)
);
```

**Script CREATE de vista, función, procedimiento o trigger.** Es **la definición real guardada en el servidor**.

- **PostgreSQL reescribe el texto a su manera**, así que no conserva el formato ni los comentarios originales:
  - **Vista:** `CREATE OR REPLACE VIEW public.<nombre> AS` (esa línea la pone Faro) seguido del SELECT redactado por el servidor, con `;` al final.
  - **Función o procedimiento:** `CREATE OR REPLACE FUNCTION public.<nombre>(…) …` completo, con su cuerpo, **sin punto y coma final**.
  - **Trigger:** una sola línea `CREATE TRIGGER … EXECUTE FUNCTION …`, sin punto y coma. **No incluye el código de la función que ejecuta el trigger**; esa función está en la categoría Funciones.
- **SQL Server** entrega el **texto original** tal como se escribió, con comentarios, formato y la palabra `CREATE`.
  - No se puede volver a ejecutar si el objeto ya existe.
  - Si su cuerpo tiene `;`, Faro lo parte al ejecutarlo (33.2).
  - Los objetos cifrados (`WITH ENCRYPTION`) o sin permiso de ver definición no se pueden generar ni comparar.

**Script CREATE de tipo.** Se reconstruye:

| Tipo | Script |
|---|---|
| Enumeración (PG) | `CREATE TYPE public.x AS ENUM ('a', 'b');` |
| Dominio (PG) | `CREATE DOMAIN public.x AS <tipo> [NOT NULL] [DEFAULT …];` (sin CHECK) |
| Compuesto (PG) | `CREATE TYPE public.x AS (col tipo, …);` |
| Alias (SQL Server) | `CREATE TYPE dbo.x FROM <tipo> NULL \| NOT NULL;`, por ejemplo `CREATE TYPE dbo.codigo_postal FROM varchar(5) NOT NULL;` |
| Tabla (SQL Server) | `CREATE TYPE dbo.x AS TABLE (…);`, que solo escribe `NOT NULL` cuando aplica (sin llave primaria ni índices) |

- En PostgreSQL, los tipos de un dominio o compuesto conservan longitud y precisión, y el DEFAULT sale como lo guarda el motor (por ejemplo `'x'::character varying`).
- En los tipos de SQL Server se pierde la precisión de `datetime2(n)`, `time(n)` y `datetimeoffset(n)`.

### 13.2 Cómo se entrega

1. Si los datos ya estaban en memoria es instantáneo. Si no, la barra de estado muestra *"Generando SELECT para <objeto>…"* mientras consulta. La acción puede ser SELECT, INSERT, UPDATE, DELETE, CREATE TABLE o script CREATE.
2. Se abre una **pestaña nueva** (*"Consulta N"*) con el script, donde queda marcada **solo la base dueña** del objeto. La pestaña no queda marcada como "sin guardar".
3. Mensaje: *"SELECT generado para <objeto> — su casilla ya quedó marcada."* Para el script de una tabla, *"CREATE TABLE generado para …"*; para los demás objetos, *"script CREATE generado para …"*.
4. Si falla por **cualquier** motivo (sin credenciales, el objeto ya no existe, sin permiso): *"No se pudo generar SELECT de <objeto> — revisa Diagnóstico."* El motivo real **no se muestra en pantalla** ni en Diagnóstico; solo queda en el archivo de log (34.13).
5. Repetir la misma acción sobre el mismo objeto mientras la primera sigue en curso **se ignora sin aviso**. Acciones distintas sobre la misma tabla sí corren a la vez.
6. Cada "Generar…" abre **otra pestaña nueva**, aunque se repita sobre el mismo objeto.
7. Las columnas y los scripts CREATE consultados quedan en memoria para la sesión. Los nombres de columna alimentan el autocompletado.
8. Diagnóstico no registra nada de "Generar…", ni los éxitos. El punto de estado no cambia.
9. **Llave primaria en PostgreSQL:** el motor solo informa las restricciones de tablas en las que el usuario tiene algún permiso distinto de SELECT. Con un usuario de solo lectura, UPDATE y DELETE pueden salir con el WHERE en TODO y el CREATE TABLE sin `PRIMARY KEY` **(regla del motor; no probado en vivo)**.

---

## 14. Comparar un objeto entre varias bases (MD5)

**Para qué:** verificar que una misma función, tabla, vista, procedimiento, trigger o tipo sea **idéntica en todas las bodegas**, y encontrar cuál se quedó con otra versión, sin escribir consultas.

### 14.1 Cómo se usa

1. Marcar las bases a comparar. Solo entran las marcadas **y visibles**: con el buscador activo, las ocultas no cuentan.
2. En el explorador de cualquiera de ellas, clic derecho sobre el objeto → **Comparar en las bases marcadas…**
3. La base de donde salió el objeto se agrega sola aunque no esté marcada.
4. Hacen falta **al menos 2 bases**. Si no: *"Marca al menos 2 bases para comparar <objeto>."*, y en Diagnóstico *"Comparar '<objeto>': hace falta marcar al menos 2 bases."*
5. Barra de estado: *"Comparando <objeto> en N base(s)…"*. N ya incluye la base de origen.
6. **Para que Faro pueda señalar cuál es la distinta hacen falta al menos 3 bases del mismo motor.** Con solo 2 que difieren, las dos salen *"sin mayoría"*.

### 14.2 Qué hace

- En cada base, en paralelo y con el mismo límite que una ejecución, obtiene **la definición actual en el servidor**, sin usar lo que haya en memoria.
- **Tablas:** compara el `CREATE TABLE` reconstruido (13.1). Detecta columnas de más, de menos, con otro tipo, otra nulabilidad, otra llave primaria u otro orden. **No** compara índices, llaves foráneas ni valores por defecto.
- **Triggers de PostgreSQL:** compara solo la sentencia `CREATE TRIGGER`, **no el código de su función**. Para comparar la lógica hay que comparar la función.
- **MD5 de cada definición**, después de unificar saltos de línea (Windows/Linux) y quitar espacios al principio y al final. Qué más cuenta como diferencia depende del objeto:
  - **SQL Server** (todo) y el **cuerpo** de funciones y procedimientos de PostgreSQL: cuenta todo lo demás (mayúsculas, sangría, comentarios, espacios internos), porque se compara el texto original.
  - **Vistas y triggers de PostgreSQL** (y el encabezado de sus funciones): el servidor reescribe el texto, así que el formato original no cuenta. En cambio, dos versiones distintas de PostgreSQL podrían reescribir igual una vista idéntica de forma diferente y marcarla como distinta (34.32).
  - **Tablas:** solo cuentan nombre, tipo, nulabilidad, orden de columnas y llave primaria, porque se compara la reconstrucción.
- **Compara por motor:** PostgreSQL contra PostgreSQL y SQL Server contra SQL Server, porque cada motor escribe los tipos distinto y siempre darían diferencia.
- Dentro de cada motor gana la **versión más repetida**, aunque no llegue a la mitad, siempre que ninguna otra empate con ella. Ejemplo con 5 bases A, A, B, C, D: A sale *"Sí"*; B, C y D salen *"NO — difiere"*.
- **El objeto se busca con el mismo nombre** que tiene en el árbol de origen:
  - en PostgreSQL, respetando mayúsculas y minúsculas;
  - los triggers de PostgreSQL, sobre la tabla del mismo nombre;
  - las funciones con firma, con esa firma exacta.

  Si el nombre es único en la base de origen pero en otra está sobrecargado, allí se toma una versión cualquiera (34.28).
- **Una base que no tiene el objeto** aparece con Estado de error, no como "difiere":

  | Caso | Estado |
  |---|---|
  | Tabla | *"Error: La tabla '<x>' no existe en esta base (o no hay permiso para verla)."* |
  | Vista de PostgreSQL | El error crudo del servidor (*"relation … does not exist"*). |
  | Resto | *"Error: No se encontró la definición de '<x>' — puede que ya no exista, o que la conexión no tenga permiso para verla."* |
  | Sin credenciales | *"Error: Sin credenciales"* |

- Tiene su propio cupo de bases simultáneas, **no se puede cancelar** y no muestra avance: espera a la base más lenta. Puede correr al mismo tiempo que una ejecución, y lo último que termine queda en Resultados.

### 14.3 Resultado

Cae en **Resultados**, que se activa sola, con una fila por base **ordenada por alias**:

| Columna | Contenido |
|---|---|
| Base de datos | Alias. |
| Motor | *"PostgreSQL"* o *"SQL Server"*. |
| Objeto | Nombre del objeto. |
| Tipo | *"Tablas"*, *"Vistas"*, *"Funciones"*, *"Procedimientos"*, *"Triggers"* o *"Tipos"*. |
| Estado | *"OK"* o *"Error: <motivo>"*. |
| Coincide | *"Sí"* (igual a la versión ganadora de su motor), *"NO — difiere"* (lo que se busca), *"sin mayoría"* (empate o todas distintas), *"única de su motor"* (no hay otra base **con Estado OK** del mismo motor para comparar, aunque sí hubiera otras que fallaron), o vacío si hubo error. |
| MD5 | Huella de 32 caracteres hexadecimales en minúsculas. |
| Caracteres | Longitud del texto **original**, antes de unificar saltos de línea. Dos filas "Sí" pueden mostrar números distintos. |
| Definición | El script completo. En el grid solo se ve la primera parte (filas de una línea, sin copiar); para leerlo entero hay que exportar a CSV. |

- Los errores por base se registran en Diagnóstico: *"Comparar <objeto> — <alias>: <motivo>"*. Quedan por encima del resumen, porque lo más reciente va arriba.
- Resumen en la barra de estado y Diagnóstico. **Hoy siempre dice que todas coinciden, aunque todas hayan fallado** (34.6); hay que fiarse de la columna *Coincide*.
- El contador de Resultados muestra el número de bases, se habilita *Exportar CSV* y desaparece el aviso de recorte.
- Se puede **exportar a CSV** con la definición completa de cada base. El nombre sugerido es `N_bases_<fecha>.csv`, sin el nombre del objeto.
- No usa la pestaña Ejecución, no se agrega al Historial, no cambia ni hace parpadear los puntos de estado y no actualiza la estructura en memoria.

---

## 15. Editor SQL

### 15.1 Pestañas

- **Crear**: Ctrl+T, *Nueva consulta*, *Abrir archivo*, doble clic en el Historial, *Abrir* en Favoritos, *Nueva consulta para esta base*, o *Generar…*
- **Nombre**: *"Consulta 1"*, *"Consulta 2"*… (la numeración reinicia en cada sesión), o el nombre del archivo si se abrió o guardó uno.
- **Encabezado de dos líneas**: arriba el nombre, con **●** delante si hay cambios sin guardar; abajo, en letra pequeña, contra qué base(s) va a correr (9.2).
- **Cerrar**: X de la pestaña.
  - **No se puede cerrar la última**: su X no hace nada.
  - Con cambios sin guardar pregunta *Guardar / Descartar cambios / Cancelar* (4.3). Si se elige Guardar y la pestaña no tiene archivo, pide dónde guardarla; si se cancela ese paso, la pestaña no se cierra.
  - Al cerrar una, se activa la vecina y el árbol toma su selección.
- **No se pueden reordenar** arrastrando.
- **Se conservan entre sesiones** (4.1), aunque ver 34.14.

### 15.2 Archivos .sql

- **Abrir (Ctrl+O)**:
  - elige un `.sql` y lo abre en una **pestaña nueva** con el nombre del archivo;
  - mensaje *"Abierto: <archivo>"*;
  - **el archivo debe estar en UTF-8** (34.15);
  - abrir el mismo archivo dos veces crea dos pestañas independientes, y guardar una sobrescribe lo que haya guardado la otra.
- **Guardar (Ctrl+G)**: si la pestaña ya tiene archivo, lo **sobrescribe sin preguntar**; si no, funciona como *Guardar como…*
- **Guardar como… / Exportar script SQL…**:
  - pide el archivo (título *"Guardar script SQL"*); Windows avisa si ya existe;
  - la pestaña pasa a llamarse como el archivo y se quita el ●;
  - mensaje *"Guardado: <archivo>"*; Diagnóstico: *"Script guardado en <archivo>"*.
- Se guarda **todo el texto** de la pestaña, en UTF-8 sin BOM.
- Errores: *"Error al guardar: …"* o *"Error al abrir el archivo: …"*.
- Faro no detecta si el archivo cambió en disco por fuera.

### 15.3 Resaltado y números de línea

- Números de línea a la izquierda.
- Colores:

  | Elemento | Color |
  |---|---|
  | Palabras reservadas (Anexo A) | Color de acento (con el acento "negro", índigo) |
  | Textos entre comillas simples | Verde |
  | Números | Ámbar |
  | Comentarios (`-- …` y `/* … */`) | Gris |

- Las palabras reservadas se resaltan aunque estén dentro de un nombre entre comillas dobles.
- El coloreado se recalcula poco después de dejar de escribir, sin trabar el editor aunque el script sea muy grande.

### 15.4 Buscar (Ctrl+F)

- Abre una barra con el campo *"Buscar en el script…"*, botones **Anterior** y **Siguiente**, y una X.
- **Enter** = Siguiente. **Esc** o la X cierran la barra y devuelven el cursor al editor.
- No distingue mayúsculas; busca desde la posición del cursor.
- Es **circular**: al llegar al final vuelve al principio, y al revés.
- Selecciona la coincidencia y desplaza el editor hasta ella. Si no hay: *"Sin resultados"*.
- Busca en la pestaña activa. **No hay reemplazar.**

### 15.5 Formatear SQL (Ctrl+L)

Reescribe **todo el texto de la pestaña**, no solo la selección:

- pone en **MAYÚSCULAS** las palabras reservadas del Anexo A; los demás nombres conservan su forma;
- agrega un **salto de línea antes de** SELECT, FROM, WHERE, GROUP, ORDER, HAVING, LEFT, RIGHT, INNER, OUTER, FULL, CROSS, UNION, SET, VALUES, INSERT, UPDATE, DELETE, LIMIT y OFFSET. Aplica también dentro de subconsultas y paréntesis. Un `JOIN` solo, sin LEFT/INNER/etc., no provoca salto;
- **no toca** textos entre comillas simples, nombres entre comillas dobles o corchetes, ni comentarios;
- no agrega sangrías; el significado del SQL no cambia;
- marca la pestaña con cambios sin guardar. Diagnóstico: *"SQL formateado."*

### 15.6 Autocompletado (Ctrl+Espacio)

- Necesita al menos una letra antes del cursor y completa **esa palabra** (letras, números y guion bajo).
- Sugiere:
  1. **Palabras reservadas** que empiezan con lo escrito, sin repetir la que ya está completa.
  2. **Tablas y vistas** de la **primera base marcada**, si su estructura ya se cargó en esta sesión.
  3. **Columnas** de las tablas cuyas columnas ya se consultaron (por ejemplo, con *Generar…*).
- Si la primera base marcada aún no tiene su estructura cargada, esa vez solo sugiere palabras reservadas y **empieza a cargar tablas y vistas en segundo plano** (abre conexiones con esa base). La siguiente vez ya aparecen.
- Orden alfabético, **máximo 50** sugerencias. Si hay más, la última línea dice *"… y N más — escribe otra letra para afinar"*.
- Se elige con el ratón o con flechas y Enter; reemplaza la palabra a medio escribir.
- La lista se cierra sola si el cursor se mueve. Solo aparece con Ctrl+Espacio, no mientras se escribe.

### 15.7 Tamaño de letra del editor

- **Ctrl +** agranda, **Ctrl −** achica, **Ctrl 0** vuelve a 14 px, **Ctrl + rueda** agranda o achica.
- Rango de 10 a 24 px; se aplica a todas las pestañas y se guarda.
- Es independiente del tamaño de letra del resto de la interfaz (27.3).

### 15.8 Funciones propias del componente de texto

Además de lo anterior, el editor tiene las funciones normales de un área de texto: deshacer (**Ctrl+Z**), rehacer (**Ctrl+Y**), copiar, cortar y pegar, seleccionar todo (**Ctrl+A**) y moverse con Inicio/Fin. Las provee la librería del editor, no Faro, así que se describen **sin probar en vivo**. Ver 34.25 sobre Ctrl+Z en pestañas recién abiertas.

---

## 16. Ejecución masiva de consultas

Es la función central de Faro.

### 16.1 Cómo se lanza

Botón **Ejecutar**, tecla **F5** o **Consulta → Ejecutar en las bases seleccionadas**. **No pide confirmación** en ningún caso.

### 16.2 Qué texto se ejecuta

- Si hay texto **seleccionado** en el editor, **solo la selección**.
- Si no, **todo el script** de la pestaña activa.
- Nunca se ejecutan las otras pestañas.
- **El mismo texto exacto se envía a todas las bases.** No hay variables que cambien por base.
- **Faro no traduce SQL entre motores.** `TOP` falla en PostgreSQL y `LIMIT` en SQL Server; esas bases terminan en ERROR y las demás siguen. Para mezclar motores hay que usar SQL común a ambos, o una pestaña por motor.
- Se pueden consultar tablas de otros esquemas escribiendo `esquema.tabla` en el SQL, aunque el explorador no las muestre.

### 16.3 Contra qué bases

Contra las **bases marcadas** en la pestaña activa, en el orden del árbol. Con el buscador activo, solo cuentan las marcadas visibles.

### 16.4 Comprobaciones antes de empezar

| Situación | Resultado |
|---|---|
| Ya hay una ejecución en curso (en cualquier pestaña) | F5 no hace nada; el botón muestra Cancelar. **Solo puede haber una ejecución a la vez.** |
| Texto vacío | *"Escribe una consulta primero."* |
| Ninguna base marcada | *"Selecciona al menos una base de datos."* |

### 16.5 Al arrancar

1. El texto se agrega al **Historial** (sección 23).
2. Se activa la pestaña **Ejecución**, con una fila por base en **EJECUTANDO**.
3. El botón Ejecutar se vuelve **Cancelar**, en rojo.
4. El punto de cada base marcada empieza a parpadear.

### 16.6 Cómo corre

- **En paralelo**, hasta N bases a la vez (Preferencias → *Bases en paralelo al ejecutar*, 8 de fábrica). Las demás esperan turno y se ven en EJECUTANDO mientras esperan; su tiempo empieza a contar cuando de verdad arrancan. Esa preferencia **no tiene máximo**: con 100 son 100 consultas simultáneas.
- **Cada base es independiente**: si una falla (sin credenciales, servidor caído, SQL inválido), **las demás siguen**.
- Cada base usa su grupo de conexiones (11.3). La primera vez puede tardar más, y **conectarse a un servidor caído puede tomar decenas de segundos** antes del error **(depende del controlador)**.

### 16.7 Reglas en cada base

1. **Credenciales.** Si no tiene: *"Sin usuario/contraseña guardados (edítala y guarda unas propias, o define credenciales por defecto)"*. No se conecta.
2. **Solo lectura.** Si alguna sentencia no empieza con SELECT/WITH/SHOW/EXPLAIN/DESCRIBE/DESC, el script **entero** se rechaza en esa base: *"Base de solo lectura — la consulta debe empezar con SELECT/WITH/SHOW/EXPLAIN/DESCRIBE"*. No se conecta. Las bases *Sin restricciones* del mismo lote sí lo ejecutan.
   - Consecuencias que sorprenden:
     - **un comentario escrito después del último `;`** cuenta como una sentencia aparte que no empieza con SELECT, así que la base de Solo lectura rechaza todo (34.10);
     - una consulta que empieza con paréntesis, como `(SELECT …) UNION (SELECT …)`, también se rechaza.
3. **División en sentencias.** El script se parte en cada `;`, **salvo** los que están dentro de:
   - comentarios `-- …` y `/* … */`;
   - textos `'…'`;
   - nombres `"…"` o `[…]`;
   - bloques `$$ … $$` o `$etiqueta$ … $etiqueta$` de PostgreSQL.

   Las sentencias vacías (`;;`) se ignoran. **`GO` de SQL Server no se reconoce**, y el cuerpo de un procedimiento, función o trigger de SQL Server con `;` adentro **se parte en pedazos** (33.2).

   En SQL Server, además, cada pedazo se envía por separado:
   - `DECLARE @x int = 5; SELECT @x` falla con *"Must declare the scalar variable"*. Sin el `;` entre las dos, funciona.
   - Los bloques `IF … BEGIN … END`, `WHILE` o `TRY … CATCH` con `;` adentro se rompen.
4. **Orden y corte.** Las sentencias corren **una por una y en orden**. **Si una falla, esa base se detiene**: las siguientes no se ejecutan y **las anteriores quedan aplicadas**, porque cada sentencia se confirma sola. No hay "todo o nada".
5. **Qué se muestra.** De cada base, solo la tabla de la **última sentencia de consulta**, aunque venga vacía. Ejemplos:
   - Con `SELECT …; UPDATE …;` se ve el SELECT: INSERT, UPDATE y DELETE no reemplazan lo mostrado.
   - Con `SELECT * FROM a; SELECT * FROM b WHERE 1=0` se ven 0 filas de esa base.

   Procedimientos almacenados: de cada sentencia se toma solo la **primera** tabla que devuelve. En SQL Server, si el procedimiento modifica filas antes de su SELECT y no tiene `SET NOCOUNT ON`, no se ve ninguna tabla (34.45). Si la base falló a mitad del script, **no aporta filas**, aunque un SELECT anterior sí hubiera funcionado.
6. **Tiempo límite.** Cada sentencia tiene como límite el *Timeout* de esa base; no es un límite para el script completo. Al vencerse, la base queda en ERROR y las sentencias anteriores quedan aplicadas **(textos del controlador)**:
   - SQL Server: *"The query has timed out"*.
   - PostgreSQL: *"canceling statement due to user request"*, aunque nadie haya cancelado.
7. **Lectura por bloques.** Las filas se piden en bloques de *Fetch size* (500 de fábrica). En PostgreSQL esto solo aplica si **todo** el script es de lectura; en ese caso se lee dentro de una transacción que se cierra al terminar.
8. **Punto de estado.** Ver 11.2.
9. **Transacciones explícitas.** No conviene usar `BEGIN TRAN` / `COMMIT` en Faro. Si algo falla a mitad, la transacción puede quedar abierta en una conexión que Faro reutiliza después (34.37). Para medir el efecto de un cambio, correr antes un `SELECT COUNT(*)` con el mismo `WHERE`.
10. **Mezcla de motores.** Faro no traduce el SQL (16.2).

### 16.8 Cómo se arma el resultado combinado

- La primera columna es siempre **Base de datos** (alias de origen), **aunque se consulte una sola base**.
- Las demás columnas son las de la **primera base que terminó con resultado**. Si las bases devuelven columnas distintas, el grid queda desalineado: los valores de más se ocultan y los que faltan quedan vacíos.
- Los nombres de columna son los que da la consulta, incluidos alias y nombres repetidos. Una columna sin nombre (por ejemplo `COUNT(*)` sin alias en SQL Server) sale con el encabezado vacío; conviene escribir `AS total`.
- Las filas de cada base van **juntas, en bloque**. Los bloques aparecen en el orden en que **terminó** cada base, que no es predecible. Dentro de cada bloque se respeta el orden de la consulta (`ORDER BY`).
- **Dos bases con el mismo alias** no se distinguen en el resultado.

### 16.9 Tope de filas en pantalla

- Lo que se carga para mostrar tiene un tope **combinado entre todas las bases**: 200,000 filas de fábrica (Preferencias → *Tope de filas*).
- Al llegar al tope, Faro **deja de leer**. Qué pasa con el resto de las filas depende del caso **(depende del controlador)**:
  - PostgreSQL con scripts de solo lectura: ni siquiera viajan por la red.
  - PostgreSQL con scripts que escriben: el controlador ya trajo el resultado completo a memoria antes del recorte.
  - SQL Server: no está garantizado.
- Las bases que terminan primero ocupan el cupo, así que alguna puede mostrar pocas o cero filas aunque tenga datos. La cantidad de la pestaña Ejecución es la cargada, no la real.
- Aviso arriba del grid: *"Se muestran las primeras 200,000 filas y hay más — se alcanzó el tope de filas en memoria (Preferencias → Rendimiento). "Exportar CSV" sí baja el resultado COMPLETO, no solo lo que ves acá."*
- Diagnóstico registra una advertencia: *"Resultado recortado en N fila(s) — hay más. Exportar CSV trae el resultado completo."*
- No se informa cuántas filas quedaron fuera: saberlo exigiría traerlas.

### 16.10 Mensajes de error

- El error de cada base aparece **completo** debajo de su fila en la pestaña Ejecución, tal como lo da el servidor o el controlador.
- **Pista para PostgreSQL**: si el error es `syntax error at or near "<palabra>"` y esa palabra empieza una sentencia (SELECT, INSERT, UPDATE, DELETE, CREATE, DROP, ALTER, WITH, etc.), se agrega: *"— ¿Falta un punto y coma (;) antes de esto? PostgreSQL necesita ';' entre cada sentencia de un script (SQL Server es más permisivo con esto)."*

### 16.11 Al terminar

- **Si al menos una base devolvió una tabla de resultados, aunque vacía:**
  - el grid se reemplaza;
  - se activa **Resultados** y su contador muestra el total;
  - se habilita *Exportar CSV*.
- **Si ninguna devolvió una tabla** (todas fallaron, o el script solo tenía INSERT/UPDATE/DELETE):
  - el contador de Resultados queda en 0 y *Exportar CSV* se deshabilita;
  - se queda en Ejecución;
  - **el grid conserva lo de la ejecución anterior** (34.16).
- **No se informan filas afectadas** por INSERT/UPDATE/DELETE: esas bases muestran *"0 fila(s)"* (34.17).
- El botón vuelve a **Ejecutar** y los puntos dejan de parpadear.
- Si la ejecución completa fallara de forma inesperada: Diagnóstico *"Error al ejecutar: …"*, y se queda en Ejecución.
- Si **una** base sufre un error grave (por ejemplo, se agota la memoria), la ejecución termina, pero esa base se queda en EJECUTANDO para siempre, sin mensaje y con el punto parpadeando (34.36).

### 16.12 Cancelar

- **Tres formas**:
  - el botón **Cancelar** (el mismo de Ejecutar), que cancela todas las bases en EJECUTANDO;
  - **Consulta → Cancelar ejecución**, igual que el anterior;
  - la **X de una fila** en Ejecución, que cancela solo esa base (activa mientras corre).
- **Cómo funciona**:
  1. Pide al servidor que detenga la consulta.
  2. Si después de **1.5 segundos** sigue corriendo, manda una orden de fuerza desde otra conexión del grupo: `KILL` en SQL Server (cierra la sesión) o `pg_cancel_backend` en PostgreSQL (detiene la consulta). Necesita un pool de al menos 2. En SQL Server, además, necesita el permiso ALTER ANY CONNECTION; sin él, falla sin avisar y solo queda en el log (34.41).
- La base queda en **CANCELADO**. En la fila solo se ve la insignia; el texto interno *"Cancelado por el usuario"* no se muestra. Las sentencias ya terminadas en esa base quedan aplicadas.
- Si se cortó la red y el servidor no se entera, cancelar puede no servir. La ejecución se queda esperando esa base, y la única salida es cerrar Faro.
- Diagnóstico: *"Cancelando N base(s)."*
- **Límite importante**: cancelar solo detiene lo que **ya estaba ejecutándose**. Las bases que todavía **esperaban su turno** o se estaban **conectando** probablemente ejecutan el script completo cuando les toca (34.4).

---

## 17. Pestaña Resultados

- Tabla con columnas según la consulta; la primera siempre es **Base de datos**.
- Cada valor se muestra como texto, tal como lo entrega el servidor:

  | Tipo de dato | Cómo se ve |
  |---|---|
  | NULL | Celda vacía, igual que un texto vacío |
  | Fechas y horas | `2026-09-21 10:15:00.0` |
  | Booleanos | `true` / `false` |
  | Decimales | Con punto; los muy pequeños pueden salir en notación científica (`1E-7`) |
  | Binarios | Texto sin sentido del tipo `[B@1a2b3c` (34.23) |

- El contador de la pestaña muestra cuántas filas hay cargadas.
- Muestra el aviso de resultado recortado cuando corresponde (16.9).
- La altura de las filas sigue el tamaño de letra de la interfaz.
- **No permite ordenar**: al hacer clic en un encabezado aparece una flecha, pero el orden no cambia. Tampoco permite filtrar ni copiar celdas.
- Las columnas sí se pueden ensanchar y **arrastrar a otra posición**, pero arrastrarlas **desalinea el CSV exportado** (34.34).
- **Es un solo panel para todas las pestañas**: muestra lo último que terminó, sea una ejecución, un plan o una comparación, de cualquier pestaña. Cambiar de pestaña no cambia lo que muestra.
- Botón **Exportar CSV** a la derecha de las pestañas (sección 21).

---

## 18. Pestaña Ejecución

Muestra la **última ejecución**, con una fila por base marcada, actualizada en vivo:

| Elemento | Detalle |
|---|---|
| Punto | Amarillo corriendo (o esperando turno), verde listo, rojo error, gris cancelado. |
| Alias | Ancho fijo; si se corta, el texto completo aparece al pasar el ratón. |
| host:puerto | Igual. |
| Insignia | **EJECUTANDO**, **LISTO**, **ERROR** o **CANCELADO**. |
| Barra | En movimiento mientras corre; llena al terminar. |
| Filas | *"N fila(s)"* cargadas de esa base. |
| Tiempo | *"N ms"*, contados desde que esa base arrancó de verdad (no incluye la espera de turno). |
| X | Cancela solo esa base. |
| Error | Si falló (ERROR), el mensaje completo debajo de la fila, siempre visible. En CANCELADO no se muestra texto. |

- **Encabezado**: *"<pestaña> · <hora de inicio> · N base(s) · M correcta(s) · K con error(es) · J cancelada(s)"*. Las dos últimas partes solo aparecen si son mayores que cero.
- Antes de la primera ejecución: *"Sin ejecuciones todavía."*
- El contador de la pestaña muestra cuántas bases tuvo la última ejecución.
- También es un panel compartido entre pestañas.

---

## 19. Pestaña Diagnóstico

Es la bitácora visible de la sesión.

- Cada línea tiene hora (HH:mm:ss), nivel en color (**INFO**, **WARN**, **ERROR**) y mensaje. **Lo más reciente va arriba.**
- Guarda como máximo 500 líneas; las viejas se descartan.
- Se borra al cerrar Faro. El archivo de log, en cambio, conserva todo (29.1).
- El contador de la pestaña muestra cuántas líneas hay.

**Qué queda registrado:**

- tema cambiado; SQL formateado; script guardado;
- base agregada o eliminada; modo cambiado; base movida de grupo; grupo creado o renombrado;
- credenciales por defecto guardadas; bases agregadas por descubrimiento;
- *Probar todas las conexiones* (total y cada falla);
- nueva consulta para una base; favoritos guardados, abiertos o eliminados;
- plan pedido o su error, y el aviso de "solo la primera base";
- comparación (resumen, errores, falta de bases);
- exportaciones a CSV, sus errores y los errores por base de la exportación completa;
- resultado recortado; cancelaciones;
- configuración exportada (como advertencia si lleva credenciales) o importada;
- errores del autoguardado; fallas inesperadas de una ejecución.

**Qué no queda registrado aquí:**

- el inicio y fin de cada ejecución y los errores por base (se ven en Ejecución);
- editar una base;
- las fallas de *Generar…* (34.13).

---

## 20. Explicar plan de ejecución

- Solo desde **Consulta → Explicar plan de ejecución**; no tiene atajo ni botón.
- Usa la selección o, si no hay, todo el texto. Hace las mismas comprobaciones que Ejecutar: *"Escribe una consulta primero."* / *"Selecciona al menos una base de datos."*
- Corre **solo contra la primera base marcada** (en el orden del árbol). Si hay varias, Diagnóstico avisa: *"Explicar plan: usando solo <alias> (la primera base marcada) — el plan es por base, no se mezclan varias."*
- PostgreSQL usa `EXPLAIN` y SQL Server `SHOWPLAN_ALL`. En ambos se obtiene el **plan estimado sin ejecutar la sentencia**, por eso **no aplica** la regla de Solo lectura.
- Debe ser una sola sentencia; con varias, el motor da error.
- El plan reemplaza el contenido de **Resultados** con la columna *Base de datos* delante, pero **la pestaña no se activa sola**. Diagnóstico: *"Plan de ejecución pedido para <alias>."*
- No se agrega al Historial, no usa la pestaña Ejecución y **no espera** a que termine una ejecución en curso (34.18).
- Error: Diagnóstico *"Error al pedir el plan de <alias>: …"*.
- Según el propio código, la variante de SQL Server no se ha probado contra un servidor real.

---

## 21. Exportar resultados a CSV

### 21.1 Cuándo se puede

- El botón **Exportar CSV** se habilita cuando la última operación dejó al menos una fila.
- **Archivo → Exportar resultados a CSV…** revisa si el grid tiene filas. Si no: *"No hay resultados para exportar."*
- Sirve para cualquier contenido del grid: ejecución, plan o comparación.
- Se puede exportar mientras corre otra ejecución, y se pueden lanzar varias exportaciones a la vez.

### 21.2 Nombre de archivo sugerido

Se arma con el SQL que produjo el resultado (no con lo que haya ahora en el editor):

1. **Base**: el alias si fue una sola, o `N-bases` si fueron varias.
2. **Tabla**: la primera que aparece después de un `FROM`, sin prefijo de esquema.
3. **Condiciones del WHERE**: cada condición simple `columna operador valor` separada por AND u OR, con el operador en palabras:

   | Operador | Palabra |
   |---|---|
   | `>=` | mayor_igual |
   | `<=` | menor_igual |
   | `<>`, `!=` | distinto |
   | `=` | igual |
   | `>` | mayor |
   | `<` | menor |

4. **Fecha y hora**: `AAAAMMDD_HHMMSS`.

Todo lo que no sea letra sin acento o número se convierte en `_`.

**Ejemplos:**

| SQL y base | Nombre sugerido |
|---|---|
| `SELECT * FROM productos WHERE id > 200 AND id < 300` en "Bodega Norte" | `Bodega_Norte_productos_id_mayor_200_id_menor_300_20260921_143012.csv` |
| `SELECT * FROM dbo.ventas WHERE fecha >= '2026-01-01'` en 3 bases | `3_bases_ventas_fecha_mayor_igual_2026_01_01_20260921_143012.csv` |
| `… WHERE estado = 'activo'` | `…_estado_igual_activo_…` |
| Alias "Mérida" | `M_rida_…` (los acentos se vuelven `_`) |

Reglas adicionales:

- Condiciones con IN, LIKE, BETWEEN, funciones o subconsultas no aportan al nombre.
- Un valor con espacios aporta solo su primera palabra.
- Si no hay `FROM` reconocible, el nombre queda solo con base y fecha.
- En una comparación: `N_bases_<fecha>`.
- El nombre no tiene límite de largo.

### 21.3 Dos formas de exportar (Faro elige sola)

**A. Resultado completo en pantalla (caso normal).** Escribe exactamente lo que muestra el grid, con las mismas columnas y filas, en segundo plano.

- Mientras avanza: *"Exportando: N de M fila(s)…"* cada 500 filas, más un indicador girando en la barra de estado.
- Al terminar: *"Exportado: <archivo>"*; Diagnóstico *"Resultados exportados a <archivo> (N fila(s))."*

**B. Resultado recortado por el tope (16.9).** Faro **vuelve a ejecutar el mismo SQL contra las mismas bases marcadas** y escribe fila por fila directo al archivo, sin cargar el resultado en memoria. Por eso sirve para millones de filas.

- Al empezar: *"Exportando el resultado completo a <archivo>…"* / *"Exportando todo…"*.
- Corre en paralelo, con el mismo límite de bases a la vez.
- De cada base toma el resultado de la última sentencia que devuelve filas.
- Las filas de distintas bases quedan **intercaladas en bloques de unas 500**; la columna *Base de datos* dice el origen de cada una.
- El archivo refleja **los datos de ese momento**, que pueden diferir de lo que se veía.
- Si una base falla, el archivo se genera con las demás y cada falla queda en Diagnóstico: *"Exportar — <alias>: <motivo>"*.
- Si **todas** fallan o no devuelven filas, el archivo queda **vacío, sin encabezado**.
- Si algo falla a mitad, lo ya escrito queda en el archivo.
- Al terminar: *"Exportado completo: <archivo> (1,234,567 fila(s))"* (como advertencia si hubo errores).
- **No hay botón para cancelarla.**
- Ver el riesgo en **34.1**: vuelve a ejecutar también las sentencias que modifican datos.

### 21.4 Formato del archivo CSV

- **UTF-8 sin BOM**, separador **coma**, fin de línea tipo Unix (LF), también en Windows.
- Primera fila: encabezados, empezando por *Base de datos*.
- Un valor va entre comillas dobles si contiene coma, comillas, salto de línea o retorno de carro. Las comillas internas se duplican: `"texto ""citado"""`.
- NULL se escribe como campo vacío.
- Los valores se escriben con el mismo formato de texto que en pantalla (17), incluido el problema de los binarios.
- Si el archivo existe, se sobrescribe; Windows pide confirmación al elegirlo.
- **Excel en español** muestra mal los acentos al abrir el archivo con doble clic (`Ã±` en vez de `ñ`). Se ve bien con *Datos → Desde texto/CSV*, eligiendo UTF-8.

---

## 22. Importar un CSV a una tabla

**Herramientas → Importar CSV a una tabla…** abre una ventana con el texto *"La primera fila del archivo debe traer los nombres de columna — deben coincidir con columnas reales de la tabla destino."*

### 22.1 La ventana

| Campo | Detalle |
|---|---|
| Base de datos | Lista con **todas** las bases registradas, como *"Alias (PG)"* / *"Alias (MSSQL)"*. Primero van las de SIN GRUPO y luego grupo por grupo, así que no es el orden del árbol. Viene elegida la primera. Se importa a **una sola base** a la vez. |
| Tabla destino | Nombre de una tabla **que ya existe**. |
| Archivo CSV | Botón *"Elegir archivo…"* (solo `.csv`); muestra el nombre elegido o *"(ninguno)"*. |

Botones **Importar** y **Cerrar**. La ventana queda abierta después de importar y, mientras esté abierta, no se puede usar la ventana principal. Si se cierra mientras importa, la importación sigue en segundo plano, pero el resultado ya no se ve.

### 22.2 Validaciones, en este orden

1. Falta la base: *"Elige una base de datos."*
2. Falta la tabla: *"Escribe el nombre de la tabla destino."*
3. Falta el archivo: *"Elige un archivo CSV primero."*
4. La base no tiene credenciales: *"Esa base no tiene credenciales guardadas — edítala o define credenciales por defecto."*
5. **El nombre de la tabla** solo puede tener letras sin acento, números y guion bajo, y no puede empezar con número. Si no: *"Nombre inválido: "<nombre>" — solo letras, números y guion bajo, sin empezar con número."* Esto impide usar esquema (`dbo.tabla`), espacios, guiones o acentos.
6. Se lee el archivo (22.3).
7. Si tiene menos de 2 líneas con contenido, no inserta nada: *"0 fila(s) importada(s) a <tabla>."*
8. **Cada encabezado** pasa la misma regla del punto 5. Un espacio después de la coma en la primera fila basta para rechazarlo.
9. **Todas las filas deben tener tantos campos como el encabezado.** Si no: *"La fila N del CSV tiene X campo(s), se esperaban Y según el encabezado."* Una coma de más al final de una línea cuenta como un campo extra.

Hasta aquí no se ha tocado la base.

### 22.3 Cómo lee el archivo

- **Primera fila = nombres de columna.** Deben existir en la tabla.
- **Separador: solo coma.** Un CSV con punto y coma (común en Excel en español) no sirve: toda la línea se lee como un campo y falla la validación de nombres.
- Respeta comillas dobles: una coma dentro de comillas no separa, y `""` es una comilla literal.
- **Los espacios alrededor de las comas se conservan** como parte del valor.
- **No admite saltos de línea dentro de un campo**: cada línea es una fila.
- Ignora líneas en blanco y quita la marca BOM que pone Excel al guardar "CSV UTF-8".
- **Detecta la codificación**: intenta UTF-8 y, si el archivo no lo es, lo relee con la de Windows (la que usa Excel en español). Lo avisa junto al resultado: *"… · leído como windows-1252, no era UTF-8"*.
- El archivo se carga completo en memoria antes de insertar; uno muy grande puede agotarla.

### 22.4 Cómo inserta

- Un `INSERT INTO tabla (columnas del encabezado) VALUES (…)` por fila, en lotes de 500.
- **Todo en una sola transacción: entran todas las filas o ninguna.** Si una falla (tipo incompatible, llave duplicada, columna inexistente, restricción), no queda nada insertado: *"Error al importar: <motivo del servidor>"*.
- **Un campo vacío se inserta como NULL.** No hay forma de insertar un texto vacío.
- Todos los valores viajan como texto y el servidor los convierte al tipo de la columna.
  - **En SQL Server** esto funciona para números, fechas en formato ISO (`2026-09-21`) y texto.
  - **En PostgreSQL es muy probable que falle** para columnas numéricas, de fecha o booleanas, con un error del tipo *"column "x" is of type integer but expression is of type character varying"* (34.2).
- **Mayúsculas en nombres (PostgreSQL)**: los nombres se envían sin comillas, así que el motor los pasa a minúsculas; una columna creada con mayúsculas no se puede usar como destino.
- Una barra de progreso avanza fila por fila; el botón Importar se deshabilita mientras dura. Mensaje: *"Importando…"*.
- Al terminar: *"N fila(s) importada(s) a <tabla>."*
- **No respeta el modo Solo lectura** (34.3) y no cambia el punto de estado.

---

## 23. Historial de consultas

- Ícono de reloj del riel o **Alt+2**. Texto: *"Doble clic para abrir en una pestaña nueva. No se guarda entre sesiones."*
- Guarda cada texto **ejecutado** con Ejecutar/F5 (la selección, si la había), aunque la ejecución falle. No guarda planes ni comparaciones.
- Hasta **50** entradas, la más reciente arriba. Si se vuelve a ejecutar algo idéntico, **sube al primer lugar** en vez de repetirse.
- Cada entrada se ve en una línea de hasta 80 caracteres, con los espacios y saltos compactados y "…" si es más larga. Al abrirla se recupera el texto exacto.
- **Doble clic** abre ese SQL en una pestaña nueva, que hereda la selección actual de bases.
- **Se pierde al cerrar Faro.** No se puede borrar ni editar.
- Es **uno solo para todas las pestañas**.

---

## 24. Favoritos

- **Guardar**: botón **Favorito** o **Consulta → Guardar como favorito**.
  - Si el editor está vacío: *"Escribe una consulta primero."*
  - Pide *"Nombre del favorito:"* y guarda **todo el texto de la pestaña activa**, aunque haya selección.
  - Se permiten nombres repetidos. Cancelar o dejar el nombre vacío no guarda.
  - Mensaje: *"Favorito guardado: <nombre>"*.
- **Ver**: ícono de estrella o **Alt+3**. Lista por nombre, en el orden en que se guardaron.
- **Abrir**: seleccionar uno y presionar **Abrir** (el doble clic no funciona aquí). Se abre en una pestaña nueva, que hereda las bases marcadas en ese momento. Un favorito guarda solo el nombre y el SQL, **no las bases**.
- **Eliminar**: seleccionar uno y presionar **Eliminar**. Se borra **sin confirmación**.
- No se pueden renombrar ni editar; para cambiar uno, se guarda de nuevo y se elimina el viejo.
- **Se guardan entre sesiones** y viajan en la exportación de configuración.

---

## 25. Credenciales (usuarios y contraseñas)

### 25.1 Dos niveles

- **Propias de cada base**: se capturan al agregar, editar o descubrir la base.
- **Por defecto**: **Conexiones → Credenciales por defecto…**
  - Ventana con Usuario y Contraseña, precargados si ya había, y el texto *"Se usan para conectarse a cualquier base que no tenga su propio usuario/contraseña guardado desde Agregar/editar base de datos."*
  - Botones Guardar y Cancelar. Mensaje: *"Credenciales por defecto guardadas."*
  - **No se pueden borrar.** Guardar con los campos vacíos deja un usuario vacío. Desde entonces, las bases sin credenciales propias intentan conectarse con usuario vacío y reciben un error del servidor, en vez del aviso *"Sin usuario/contraseña guardados"* (34.43).

Orden de uso: propias → por defecto → ninguna (3.5). Las bases agregadas con *Descubrir* tienen credenciales **propias** (una copia de las de esa ventana), así que no usan las por defecto. Se usan en **todas** las conexiones: ejecutar, explorar, generar, comparar, plan, exportar, importar CSV, probar conexión, probar todas, autocompletar y descubrir (este último como valor inicial).

**Cambiar las credenciales por defecto no afecta a las bases que ya tienen conexiones abiertas en la sesión**: siguen usando las anteriores hasta cerrar Faro o editar cada base (34.8).

### 25.2 Cómo se protegen

- Se guardan en `C:\Users\<usuario>\.faro\credentials.dat`, **cifradas con la protección de datos de Windows (DPAPI)**. Solo esa misma cuenta de Windows, en ese mismo equipo, puede descifrarlas. Copiar el archivo a otro equipo o a otra cuenta no sirve.
- **Nunca** se guardan en `connections.json`.
- No aparecen en el Administrador de credenciales de Windows.
- El archivo de log no contiene las contraseñas de conexión. Sí contiene:
  - nombres de usuario y hosts;
  - el texto de cada sentencia, recortado;
  - los scripts generados, **completos**.

  Una contraseña o dato sensible escrito **dentro del SQL** queda en el log (29.1).
- Para llevarlas a otro equipo existe la exportación con credenciales (26.1), que las deja **legibles**.

### 25.3 Cuándo se borran

- Al eliminar una base se borran sus credenciales propias.
- Al editarla y dejar el usuario vacío se borran sus credenciales propias.
- Importar una configuración no borra credenciales de bases que ya no existen: quedan guardadas, sin uso (34.20).

---

## 26. Exportar e importar la configuración (JSON)

Sirve para **respaldar**, **llevar Faro a otro equipo** o **compartir el catálogo de bases** con otra persona.

### 26.1 Exportar

**Conexiones → Exportar configuración…**

1. Ventana *"Exportar configuración"*, pregunta *"¿Qué incluye el archivo exportado?"*, con la casilla **"Incluir usuarios y contraseñas"**. La casilla está **desmarcada cada vez**. Esta advertencia se muestra siempre:
   > *Si la marcas, las contraseñas quedan LEGIBLES dentro del archivo .json: cualquiera que lo abra las puede leer. Guárdalo como guardarías una contraseña — no por correo ni en una carpeta compartida. Sirve para no volver a capturarlas al montar Faro en otro equipo. Tu connections.json de siempre no cambia: ahí las credenciales siguen cifradas y aparte.*
2. **Exportar** o **Cancelar**.
3. Se elige dónde guardar (filtro `*.json`). Nombre sugerido: `faro-config.json`, o `faro-config-con-credenciales.json` si se marcó la casilla.
4. Mensaje: *"Configuración exportada: <archivo>"*. Diagnóstico lo registra, **como advertencia** si lleva credenciales: *"… — CON usuarios y contraseñas en texto legible. Trátalo como un archivo con secretos."*
5. Si falla: *"Error al exportar configuración: <motivo>"*.

### 26.2 Qué contiene el archivo

| Sección | Contenido | ¿Va? |
|---|---|---|
| Preferencias | Bases en paralelo, pool y timeout por defecto, tema, fetch size, tope de filas, acento, letra del editor, letra de la interfaz. | Sí |
| Favoritos | Identificador, nombre y SQL. | Sí |
| Grupos | Identificador, nombre y sus bases, en orden. | Sí (también los vacíos) |
| Bases sin grupo | En orden. | Sí |
| Cada base | Identificador, alias, host, puerto, nombre de base, motor, modo, pool, timeout, certificado, codificación (si no es automática) y último estado (solo si era verde o rojo). | Sí |
| Pestañas abiertas | — | **No** (sección vacía) |
| Credenciales | Por identificador de base, más las por defecto, **en texto legible**. Incluye también las de bases que ya no existen (34.20). | **Solo con la casilla** |
| Historial, Diagnóstico | — | No (no se guardan nunca) |

Ejemplo simplificado con credenciales (valores inventados):

```json
{
  "preferences": { "maxConcurrentDatabases": 8, "defaultPoolSize": 4, "defaultQueryTimeoutSeconds": 30,
                   "darkTheme": false, "fetchSize": 500, "maxDisplayRows": 200000,
                   "accentName": "indigo", "editorFontSize": 14, "fontScaleDelta": -1 },
  "favorites": [ { "id": "…", "name": "Existencias por bodega", "sql": "SELECT …" } ],
  "servers": [
    { "id": "…", "name": "Bodegas Norte",
      "databases": [ { "id": "a1b2…", "alias": "Bodega Monterrey", "host": "10.0.0.15", "port": 5432,
                       "databaseName": "bodega_mty", "engine": "POSTGRES", "mode": "READ_ONLY",
                       "poolSize": 4, "queryTimeoutSeconds": 30, "trustServerCertificate": true,
                       "connectionStatus": "CONNECTED" } ] }
  ],
  "ungroupedDatabases": [],
  "queryTabs": [],
  "credentials": {
    "byDatabaseId": { "a1b2…": { "user": "consulta", "password": "Clave2026" } },
    "default": { "user": "consulta", "password": "Clave2026" }
  }
}
```

Valores internos:

- `engine`: `POSTGRES` o `SQL_SERVER`.
- `mode`: `READ_ONLY` (Solo lectura) o `UNRESTRICTED` (Sin restricciones).
- `connectionStatus`: `CONNECTED` o `FAILED`.

El archivo es texto UTF-8 legible y editable a mano. Si se edita:

- **cada base debe tener** `id`, `alias`, `host`, `port`, `databaseName`, `engine` y `mode`;
- **cada grupo debe tener** `name` y `databases`;
- **cada favorito debe tener** `id`, `name` y `sql`;
- si falta uno de esos campos o el motor no es uno de los dos válidos, **falla la importación completa**;
- un pool menor que 2 se corrige solo a 2.

### 26.3 Importar

**Conexiones → Importar configuración…** → elegir un `.json`. **No pide confirmación**: el cambio se aplica al elegir el archivo.

| Qué | Qué pasa |
|---|---|
| **Grupos y bases** | **Se reemplazan por completo** con los del archivo. No se combinan: lo que no esté en el archivo desaparece del árbol. |
| Conexiones abiertas | Se cierran todas. |
| Estructura en memoria | Se olvida la de todas las bases. |
| Preferencias | Cada una que venga en el archivo reemplaza la actual. Un archivo exportado por Faro las trae todas, así que en la práctica **se reemplazan todas** (tema, tamaños, tope de filas, bases en paralelo…). El tema y los tamaños **no se ven al instante** (34.19). |
| Favoritos | Si el archivo trae la sección, reemplazan a los actuales. Un archivo exportado por Faro **siempre** la trae, aunque sea vacía, así que importar **borra los favoritos propios** y deja los del archivo. |
| Credenciales | Si vienen: se **agregan o reemplazan** las de cada base incluida y se reemplazan las por defecto. Las de otras bases no se borran. Si no vienen, las actuales no se tocan. |
| Pestañas abiertas | Se quedan igual. Su selección se conserva si el archivo mantiene los identificadores de base (lo normal al exportar desde Faro). |
| Pestañas del archivo | Se ignoran. |

- Mensaje: *"Configuración importada: <archivo>"*. Diagnóstico: *"Configuración importada de <archivo> — reemplazó conexiones y favoritos actuales."*
- Lo importado se vuelve la configuración de trabajo: se escribe en `connections.json`, y las credenciales se **vuelven a cifrar** en `credentials.dat` en el siguiente autoguardado (menos de 2 minutos) o al cerrar.
- Si el archivo tiene un error: *"Error al importar configuración: <motivo>"*. El árbol no se reemplaza, pero las conexiones abiertas ya se cerraron y alguna preferencia o favorito pudo haber cambiado.
- También se puede importar un `connections.json` copiado de otro equipo (mismo formato), aunque ese archivo nunca trae credenciales.
- Si una ejecución estaba corriendo, importar corta sus conexiones y probablemente termina en ERROR (34.40).

### 26.4 Cambiar muchas bases a la vez (por ejemplo, si migran un servidor)

No hay edición masiva, y un grupo no comparte host. El camino es el archivo JSON:

1. *Exportar configuración…* sin credenciales.
2. Abrir el `.json` con un editor de texto y reemplazar el valor (host, timeout, pool…) en las bases que correspondan.
3. *Importar configuración…* con ese archivo.

Como el archivo conserva los identificadores internos, las contraseñas guardadas en el equipo siguen funcionando. Hay que recordar que importar reemplaza también favoritos y preferencias con los del archivo, que en este caso son los mismos.

---

## 27. Preferencias

Se abre con **Herramientas → Preferencias…** o con el **engrane** del riel. **Ayuda → Atajos de teclado** la abre en la pestaña Atajos. Tiene tres pestañas y un solo botón, **Cerrar**: todo se guarda y aplica al momento.

### 27.1 Rendimiento

Texto: *"Estos valores afectan de verdad cómo corre la app — no son solo texto. Se guardan solos al salir del campo (Tab/clic afuera) o con Enter, sin botón aparte."* Errores al pie: *"<campo>: debe ser un número entero."*

| Campo | Qué controla | Fábrica | Mínimo |
|---|---|---|---|
| Bases en paralelo al ejecutar | Cuántas bases se consultan a la vez; también en la exportación completa y en Comparar. | 8 | 1 (un 0 o negativo se guarda como 1) |
| Tamaño de pool por defecto (base nueva) | Valor inicial al **agregar a mano** una base. No cambia las existentes ni las descubiertas. | 4 | 2, con el aviso de pool mínimo |
| Timeout de consulta por defecto (s) | Valor inicial al **agregar a mano**. No cambia las existentes. También es el que aparece en la barra de estado. | 30 | 1 (0 o negativo se guarda como 1) |
| Fetch size (filas por bloque al leer resultados) | 16.7, punto 7. | 500 | 1 |
| Tope de filas a mostrar en Resultados | 16.9. No limita la consulta ni la exportación. | 200,000 | 1,000 (*"el mínimo es 1.000 filas"*) |

Los mínimos de *Bases en paralelo*, *Timeout* y *Fetch size* se corrigen **en silencio**: si se escribe 0, se guarda 1, pero el campo sigue mostrando 0 hasta reabrir la ventana. **Ningún campo tiene máximo.**

Textos de ayuda de esta pestaña:

- *"Fetch size: cuántas filas se traen por bloque al leer resultados grandes, en vez de esperar a que llegue todo junto. Funciona en los dos motores; en PostgreSQL aplica a los scripts de solo lectura."*
- *"Tope de filas: hasta cuántas filas se cargan en memoria para mostrarlas en Resultados. Si una corrida lo alcanza, el grid lo avisa arriba. No limita la consulta ni la exportación: Exportar CSV siempre baja el resultado completo, leyéndolo de la base y escribiéndolo directo al archivo."*

### 27.2 Atajos

Lista de consulta, no editable: Ctrl+T, Ctrl+O, Ctrl+G, Ctrl+F, Ctrl+L, Ctrl+Espacio, F5, Alt+1/2/3 y Alt+F4 (sección 30).

### 27.3 Apariencia

Todo se ve al instante, en la ventana principal y en la propia ventana de Preferencias.

| Opción | Valores | Fábrica |
|---|---|---|
| Tema | Claro u Oscuro. También con el botón luna/sol de la barra. | Claro |
| Color de acento | 7 círculos: índigo, violeta, azul, verde azulado, rosa, ámbar y **negro** (monocromático: negro en tema claro, blanco en oscuro). El elegido lleva un anillo. | Índigo |
| Tamaño de fuente del editor SQL | 10 a 24 px, igual que Ctrl +/−. | 14 |
| Tamaño de fuente de la interfaz | Deslizador de −5 a +5 que suma o resta píxeles a todo el texto menos el editor. También ajusta la altura de las filas del árbol y del grid, y las flechas del árbol. Nunca baja de 8 px. | −1 |

El acento pinta botones principales, la pestaña activa, algunos íconos y las palabras reservadas del editor. En tema oscuro cada acento usa un tono más claro para que se lea.

Tipografías: Sora en títulos, Manrope en el texto y JetBrains Mono en el editor.

---

## 28. Barra de estado

De izquierda a derecha:

| Elemento | Qué muestra |
|---|---|
| Punto + *"N conexiones · pool A/T"* | Cuántas **bases** tienen su grupo de conexiones abierto (11.3), cuántas conexiones están **ocupadas** (A) y cuántas hay **abiertas** en total (T). Punto verde si hay alguna, gris si no. |
| Mensaje | Confirmaciones y avisos puntuales (Anexo B). |
| *"Timeout X s · fetch Y"* | El timeout **por defecto** de Preferencias (no el de cada base) y el fetch size. |
| Indicador *"Exportando…"* | Solo durante una exportación. |
| *"Memoria N MB"* | Memoria que usa Faro. |
| Motores y Java | Versión de cada motor con el que ya se **ejecutó** una consulta en la sesión (por ejemplo, *"PostgreSQL 15.4 · SQL Server 15.00…"*) y *"JDK …"*. |

Se actualiza cada 2.5 segundos y al terminar cada ejecución o exportación.

---

## 29. Qué se guarda, dónde y cuándo

### 29.1 Archivos

| Archivo | Contenido | Cuándo se escribe |
|---|---|---|
| `C:\Users\<usuario>\.faro\connections.json` | Grupos, bases (con su orden y último estado verde/rojo), preferencias, favoritos y pestañas abiertas (texto, archivo y bases marcadas). **Sin contraseñas.** | Cada 2 minutos y al cerrar. |
| `C:\Users\<usuario>\.faro\credentials.dat` | Credenciales propias y por defecto, **cifradas con DPAPI**. | Cada 2 minutos y al cerrar. |
| `logs\faro-app.log` | Registro técnico detallado. Queda en una carpeta `logs` dentro de la **carpeta desde la que se arranca Faro**; con un acceso directo, la carpeta "Iniciar en". | Continuamente. Un archivo por día, partido si pasa de 20 MB; se conservan 14 días o 500 MB en total. |

Qué registra el log, por cada ejecución y base:

- hora, alias, host y usuario;
- el texto de cada sentencia, recortado a 500 caracteres;
- filas y tiempo, o el error.

Además:

- Los scripts de *Generar…* quedan **completos**.
- **Nunca** registra las contraseñas de conexión, pero sí cualquier dato escrito dentro del SQL.
- No registra quién ejecutó; solo la ruta del perfil de Windows al arrancar.
- Hay que tratarlo como un archivo interno.
- Con doble clic en `Faro.exe`, la carpeta `logs` queda junto a Faro; con un acceso directo, en la carpeta "Iniciar en". Si esa carpeta no permite escribir, Faro funciona igual pero probablemente no genera log.
- Las pruebas automáticas del proyecto, cuando se corren en el equipo de desarrollo, escriben en la misma carpeta.

Cada usuario de Windows tiene su propia carpeta `.faro`, así que dos personas en la misma PC no comparten configuración, y no hay forma de apuntar Faro a un archivo compartido. Faro no sincroniza configuraciones entre equipos: para eso está la exportación e importación.

Si la **misma** persona abre Faro dos veces, las dos ventanas guardan sobre los mismos archivos y gana la última en guardar. Lo que se hizo en la otra ventana se pierde (34.35).

### 29.2 Cómo se escribe

- **Escritura segura**: primero se escribe un archivo `.tmp` que luego reemplaza al definitivo. Si Faro se corta a mitad, queda la versión anterior completa, nunca una a medias.
- El autoguardado corre en segundo plano sin trabar la ventana. Si falla: Diagnóstico *"Autoguardado falló: <motivo>"*. Si uno sigue en curso cuando toca el siguiente, el siguiente se salta.
- Se reescribe aunque no haya cambios.

### 29.3 Lo que no se guarda

- Historial de consultas.
- Diagnóstico (solo existe en el log).
- Grid de Resultados y pestaña Ejecución.
- Qué grupos estaban plegados.
- Estructura cargada de las bases.

---

## 30. Atajos de teclado y gestos del ratón

### 30.1 Teclado

| Atajo | Dónde | Acción |
|---|---|---|
| Ctrl+T | Global | Nueva pestaña de consulta |
| Ctrl+O | Global | Abrir archivo .sql |
| **Ctrl+G** | Global | Guardar (no es Ctrl+S) |
| Ctrl+F | Global | Buscar en el script |
| Ctrl+L | Global | Formatear SQL |
| Ctrl+Espacio | Global | Autocompletado |
| F5 | Global | Ejecutar (no hace nada si ya hay una ejecución) |
| Alt+1 / 2 / 3 | Global | Conexiones / Historial / Favoritos |
| Alt+F4 | Global | Salir, con aviso de cambios sin guardar |
| Ctrl + / Ctrl − / Ctrl 0 | Editor | Agrandar / achicar / restablecer la letra del editor |
| Ctrl + rueda | Editor | Agrandar o achicar la letra del editor |
| Ctrl+Z / Ctrl+Y / Ctrl+C / Ctrl+X / Ctrl+V / Ctrl+A | Editor | Deshacer, rehacer, copiar, cortar, pegar, seleccionar todo (propios del componente, 15.8) |
| Enter | Barra de búsqueda | Siguiente coincidencia |
| Esc | Barra de búsqueda | Cerrar |
| Alt+↑ / Alt+↓ | Árbol | Subir o bajar el grupo o la base resaltada |
| Enter | Preferencias → Rendimiento | Guardar el campo |

No hay atajo para Guardar como, Cancelar ejecución, Explicar plan, Favoritos ni Exportar CSV.

### 30.2 Ratón

| Gesto | Resultado |
|---|---|
| Clic en el alias de una base | Marca o desmarca su casilla |
| Doble clic en el alias | Editar base |
| Clic en el candado | Alternar Solo lectura / Sin restricciones |
| Doble clic en una tabla o vista del esquema | Generar SELECT |
| Clic derecho en base, grupo, SIN GRUPO u objeto | Menús de 8.3 |
| Doble clic en el Historial | Abrir en pestaña nueva |
| Arrastrar los separadores | Cambiar el tamaño de los paneles |

---

## 31. Flujos de trabajo típicos, paso a paso

### 31.1 Consultar un dato en todas las bodegas y llevarlo a Excel

1. En el árbol, marcar las bodegas: casillas, clic en el alias, **Todas**, o clic derecho en un grupo → *Marcar todas las de este grupo*.
2. Revisar la segunda línea de la pestaña: debe decir *"N bases"*.
3. Escribir la consulta, por ejemplo `SELECT codigo, existencia FROM inventario WHERE existencia < 10`.
4. **F5**. En la pestaña Ejecución se ve cada bodega pasar de EJECUTANDO a LISTO o ERROR.
5. Al terminar se abre Resultados con todas las filas y la columna *Base de datos*.
6. **Exportar CSV**. Aceptar el nombre sugerido (por ejemplo `12_bases_inventario_existencia_menor_10_<fecha>.csv`) o cambiarlo.
7. En Excel: *Datos → Desde texto/CSV*, codificación UTF-8, separador coma.
8. Si apareció el aviso de resultado recortado, el CSV igual trae **todo**: la exportación vuelve a consultar las bases.

### 31.2 Dar de alta un servidor nuevo con muchas bodegas

1. Definir las credenciales comunes en *Conexiones → Credenciales por defecto…* (opcional; facilita el paso 3).
2. *Conexiones → Nuevo grupo de conexiones…* → por ejemplo "Bodegas Sur". El grupo aún no se ve.
3. *Conexiones → Descubrir bases en esta IP…* → escribir la IP → **Buscar**.
4. **Todas** → **Agregar seleccionadas**. Las bases quedan en SIN GRUPO, en Solo lectura, con esas credenciales como propias.
5. Mover cada una al grupo (clic derecho → *Mover a grupo…* → "Bodegas Sur").
   - Atajo para próximas veces: si ya hay una base de ese servidor dentro del grupo, se hace clic derecho sobre ella → *Descubrir bases en esta IP…* y las nuevas caen directo en ese grupo.
6. *Conexiones → Probar todas las conexiones* para confirmar que todas conectan. Revisar Diagnóstico si alguna falló.

### 31.3 Verificar que un procedimiento sea igual en todas las bodegas

1. Marcar las bodegas a revisar; al menos dos del mismo motor.
2. Desplegar una bodega → *Procedimientos* → clic derecho sobre el procedimiento → **Comparar en las bases marcadas…**
3. En Resultados, revisar la columna **Coincide**: *"NO — difiere"* marca las bodegas con otra versión, y *"Error: …"* las que no lo tienen o no dejaron leerlo.
4. **No fiarse del mensaje resumen de la barra de estado** (34.6).
5. Para ver la diferencia, exportar a CSV (incluye la definición completa de cada una) o generar el script CREATE en la bodega que difiere.

### 31.4 Corregir un dato en varias bodegas

1. Asegurarse de que las bodegas destino estén en **Sin restricciones**: clic en el candado de cada una (queda abierto).
2. Marcarlas y escribir el cambio, idealmente probando antes con un `SELECT` que use el mismo `WHERE`.
3. Antes de ejecutar:
   - Si son más bodegas que *Bases en paralelo*, subir esa preferencia al número de bodegas; así, si hay que cancelar, se cancelan todas (34.4).
   - Escribir el UPDATE sin `BEGIN TRAN`/`COMMIT` (16.7, punto 9).
4. **F5. No hay confirmación**: el cambio se aplica al momento en cada bodega y cada sentencia se confirma sola.
5. En Ejecución, revisar qué bodegas quedaron en LISTO (aplicado y confirmado) y cuáles en ERROR (no aplicado). Faro no dice cuántas filas se modificaron: *"0 fila(s)"* es normal. Para verificar, correr después un `SELECT`.
6. Volver a poner las bodegas en **Solo lectura** con el candado.
7. Las bodegas que se quedaron en Solo lectura rechazan el script completo con *"Base de solo lectura…"*: es la protección funcionando. El log conserva qué sentencia corrió en cada bodega y a qué hora.

### 31.5 Cargar un catálogo desde Excel a una tabla

1. En Excel, dejar en la primera fila los nombres exactos de las columnas de la tabla, sin espacios ni acentos.
2. Guardar como **CSV separado por comas**. Si Excel usa punto y coma por la configuración regional, hay que cambiarlo.
3. *Herramientas → Importar CSV a una tabla…* → elegir la base, escribir la tabla (sin esquema) y elegir el archivo → **Importar**.
4. Si sale *"Error al importar…"*, no quedó nada insertado; corregir y repetir.
5. En PostgreSQL, las columnas no de texto probablemente fallen (34.2). En SQL Server funciona con números, fechas ISO y texto.

### 31.6 Llevar Faro a otro equipo

1. En el equipo actual: *Conexiones → Exportar configuración…* → marcar **"Incluir usuarios y contraseñas"** → guardar el archivo.
2. Copiar la carpeta portable de Faro al equipo nuevo. Si se comprime, usar 7-Zip o `tar`; el compresor integrado de Windows puede dejar el zip incompleto, según el README del proyecto.
3. Abrir Faro en el equipo nuevo → *Conexiones → Importar configuración…* → elegir el archivo.
4. Esperar 2 minutos o cerrar Faro, para que las contraseñas se guarden cifradas en el equipo nuevo.
5. **Borrar el archivo exportado**: tiene contraseñas legibles.

No viajan ni las pestañas abiertas ni el historial. Para conservar también las pestañas (aunque sin contraseñas), en vez de importar se puede copiar `%USERPROFILE%\.faro\connections.json` a la misma ruta del equipo nuevo **antes de abrir Faro por primera vez**, y después capturar las credenciales.

### 31.7 Cambiar la contraseña del usuario de consulta

1. El administrador cambia la contraseña en el servidor.
2. En Faro: *Conexiones → Credenciales por defecto…* → capturar la nueva.
3. Actualizar las bases con credenciales **propias**, que incluyen todas las agregadas con *Descubrir*. Dos formas:
   - una por una: editar, y poner la contraseña nueva o dejar el usuario vacío para que usen las por defecto;
   - en bloque: exportar con contraseñas, reemplazarla en el `.json`, importar y borrar el archivo.
4. **Cerrar y volver a abrir Faro.** Sin esto, las bases ya usadas en la sesión siguen entrando con la contraseña vieja (34.8).
5. *Probar todas las conexiones*.

### 31.8 Corregir un objeto que difiere en algunas bodegas

1. Hacer la comparación de 31.3 y exportar el CSV como evidencia.
2. En una bodega que dice *"Sí"*, clic derecho sobre el objeto → *Generar script CREATE*. Se abre una pestaña con solo esa base marcada.
3. Desmarcar esa base, marcar las que dicen *"NO — difiere"* y pasarlas a *Sin restricciones*.
4. Ajustar el script según el motor:
   - **PostgreSQL:** funciones, procedimientos y vistas ya vienen como `CREATE OR REPLACE`. Los triggers vienen como `CREATE TRIGGER` y fallan si ya existen.
   - **SQL Server:** cambiar `CREATE` por `ALTER`. Si el cuerpo tiene `;`, Faro lo partirá y fallará: quitarlos, o aplicar el cambio con otra herramienta.
5. F5, volver a comparar hasta ver *"Sí"* en todas, y regresar las bases a *Solo lectura*.

---

## 32. Preguntas frecuentes

**¿Cuántas bases puedo consultar a la vez?** No hay un máximo de bases marcadas. Se ejecutan de 8 en 8 (configurable, sin tope) y el resto espera turno. Cada base usada deja abierto su grupo de conexiones (4 de fábrica) mientras Faro esté abierto (11.3). El límite práctico lo ponen la memoria del equipo y cuántas conexiones acepta cada servidor: 30 bodegas en un mismo PostgreSQL × 4 conexiones ya superan el límite de fábrica del motor (100).

**¿Cuánto tarda?** Como referencia, estos son los tiempos medidos en el equipo de desarrollo, con bases locales y sin red de por medio:

| Operación | Tiempo |
|---|---|
| Primera conexión | PostgreSQL 0.3–0.5 s; SQL Server 0.6–1.1 s |
| 500,000 filas por base | PostgreSQL 3–16 s; SQL Server 12–20 s |
| Comparar una tabla en 6 bases | 0.5–1.4 s |
| Cargar Tablas y Vistas | unos 40 ms |

Con red real y bases grandes, será más.

**¿Pide confirmación antes de un UPDATE o DELETE?** No. La única protección es el modo *Solo lectura* por base.

**¿Qué pasa si se cae la red a la mitad?** Normalmente la base afectada termina en ERROR con el mensaje del controlador; las demás siguen. Lo que esa base ya había ejecutado queda aplicado, y la sentencia en curso la deshace el servidor.

Pero Faro no tiene un límite propio de espera de red: solo el *Timeout* de cada sentencia. Si ni el equipo ni el servidor detectan el corte, esa base puede quedarse mucho tiempo en EJECUTANDO. Como la ejecución espera a todas las bases, no se podrá ejecutar nada más, y cancelar puede no servir; la salida es cerrar Faro.

**¿Puedo ejecutar procedimientos almacenados?** Sí, en bases *Sin restricciones* (`EXEC …` en SQL Server, `CALL …` en PostgreSQL). En *Solo lectura* se rechazan porque no empiezan con SELECT. Detalles:

- De cada llamada se muestra solo la **primera** tabla que devuelve.
- En SQL Server, si el procedimiento modifica filas antes de su SELECT y no tiene `SET NOCOUNT ON`, no se ve ninguna (34.45).
- Para crear o modificar un procedimiento de SQL Server cuyo cuerpo tiene `;`, hay que quitar esos `;` internos (33.2).
- Las funciones llamadas desde un `SELECT` sí corren en *Solo lectura*, aunque modifiquen datos.

**¿Puedo usar variables o bloques en SQL Server?** Solo sin `;` entre las partes que se necesitan juntas. `DECLARE @x …; SELECT @x` falla porque cada pedazo se envía por separado (16.7).

**¿Hay límite de tamaño de script?** No en Faro. El editor maneja scripts grandes y el log solo guarda los primeros 500 caracteres de cada sentencia.

**¿Qué pasa si dos bases tienen el mismo alias?** Faro lo permite, pero en Resultados, Ejecución y el CSV no se distinguen. Conviene usar alias únicos.

**¿Qué permisos necesita el usuario de base de datos?** Por función (lo que exige cada motor, **no probado en vivo**):

| Función | Permiso |
|---|---|
| Conectarse | Iniciar sesión y acceso a esa base. |
| Ejecutar | Los permisos de los objetos que use el SQL. *Solo lectura* es una protección de Faro, no del servidor. |
| Explorar (PostgreSQL) | Con un usuario que solo tiene SELECT, los triggers y las llaves primarias pueden no aparecer (12.3, 13.2). |
| Script CREATE y Comparar (SQL Server) | VIEW DEFINITION. |
| Descubrir | Entrar a la base de mantenimiento (`postgres` / `master`). |
| Cancelación forzada (SQL Server) | ALTER ANY CONNECTION. Sin él, falla sin avisar. |
| Explicar plan (SQL Server) | SHOWPLAN. |
| Importar CSV | INSERT sobre la tabla destino. |

**¿Necesito ser administrador de Windows?** No. Todo se guarda en la carpeta del usuario.

**¿Dos personas pueden compartir la configuración?** No en vivo. Cada cuenta de Windows tiene la suya. Se comparte exportando e importando, e **importar reemplaza** el árbol completo del que importa.

**Si cierro una pestaña, ¿se pierden los resultados?** No. Resultados y Ejecución son compartidos y muestran la última operación hasta la siguiente.

**¿Qué pasa si el servidor tarda en responder al conectar?** Faro no fija un tiempo propio de espera al conectar **(tiempos del controlador y de la librería)**:

- Servidor encendido pero servicio abajo (conexión rechazada): error en 1–2 segundos.
- Servidor apagado o firewall que descarta paquetes: la base queda en EJECUTANDO unos 10–30 segundos y luego pasa a ERROR.
- Base ya usada en la sesión y servidor caído después: unos 30 segundos, y el punto sigue verde.

Las demás bases no esperan por ella, pero el botón Ejecutar no vuelve hasta que termina la última. Mensajes reales de ejemplo en el Anexo B.

**¿Por qué una base sigue en verde si la contraseña cambió?** Porque el punto muestra la última conexión real. Si esa base ya tenía conexiones abiertas en la sesión, sigue usándolas (11.2). Al cerrar y abrir Faro, la siguiente conexión lo corrige.

**¿Cambiar las credenciales por defecto aplica de inmediato?** Solo para bases que aún no tienen conexiones abiertas en esta sesión (34.8).

**¿Faro guarda qué consulté?** El historial no se guarda entre sesiones. El log técnico sí registra, durante 14 días, el texto de cada sentencia (recortado) por base y hora, y los scripts generados completos (29.1).

**¿Puedo abrir Faro dos veces?** Faro no lo impide, pero las dos ventanas se pisan la configuración al guardar (34.35). No conviene.

**¿Funciona con autenticación de Windows o instancias con nombre?** No hay autenticación de Windows: solo usuario y contraseña del motor. Para una instancia con nombre de SQL Server hay que capturar su puerto real. *Descubrir* solo prueba los puertos 1433 y 5432.

**¿Puedo consultar tablas de otros esquemas?** Sí, escribiendo `esquema.tabla` en el SQL. No aparecen en el explorador ni en el autocompletado.

**¿Puedo mezclar bases PostgreSQL y SQL Server en la misma consulta?** Sí, pero Faro no traduce el SQL. Las bases del otro motor fallan con su propio error y las demás siguen.

**¿Cómo cambio la IP o el timeout de muchas bases a la vez?** Con el truco del JSON (26.4).

**¿Un favorito recuerda contra qué bases corría?** No: guarda solo nombre y SQL (24).

**¿Qué pasa si cierro Faro con una consulta o una exportación corriendo?** No avisa; las corta (4.3).

**¿Qué pasa si edito una base mientras se ejecuta contra ella?** Guardar la edición cierra sus conexiones, aunque no se haya cambiado nada, y esa base probablemente termina en ERROR (34.40).

**¿Cómo veo un resultado de un `COUNT(*)` con encabezado?** Poniéndole alias: `SELECT COUNT(*) AS total …`. Sin alias, en SQL Server el encabezado sale vacío.

---

## 33. Lo que Faro no hace (límites)

### 33.1 Estructura

- Solo el esquema por defecto (`public` / `dbo`).
- No muestra columnas en el árbol.
- No lista tablas particionadas madre, tablas foráneas ni vistas materializadas de PostgreSQL, ni funciones/procedimientos CLR o triggers de vista de SQL Server.
- El CREATE de tablas no incluye llaves foráneas, índices, UNIQUE, valores por defecto, CHECK, identidad ni precisión de fechas.
- Los scripts generados no ponen nombres entre comillas.
- En SQL Server no se pueden generar ni comparar objetos cifrados o sin permiso de ver su definición.
- En PostgreSQL 10 o anterior, Funciones y Procedimientos probablemente no cargan.
- La estructura no se actualiza sola; no se puede recargar una sola categoría.
- Una categoría que falla no se reintenta al plegarla y desplegarla.

### 33.2 Ejecución

- **No hay confirmación** antes de ejecutar ni opción de transacción "todo o nada".
- **Solo lectura es una heurística** por primera palabra, con huecos que **sí modifican datos** en una base "protegida":
  - `SELECT … INTO nueva_tabla` (SQL Server; crea una tabla);
  - `WITH x AS (DELETE … RETURNING *) SELECT …` (PostgreSQL);
  - `EXPLAIN ANALYZE <sentencia que escribe>` (PostgreSQL; la ejecuta);
  - funciones invocadas desde un `SELECT` que modifican datos (en PostgreSQL incluye `nextval`, `setval` y `pg_terminate_backend`);
  - `WITH … DELETE` / `UPDATE` / `INSERT` también en **SQL Server** (por ejemplo, la forma habitual de borrar duplicados);
  - `SELECT … FOR UPDATE`, que no modifica pero bloquea filas.
- Y rechaza casos legítimos: un comentario después del último `;`, o una consulta que empieza con paréntesis.
- **SQL Server**: `GO` no es separador; el cuerpo de un `CREATE/ALTER PROCEDURE`, `FUNCTION` o `TRIGGER` con `;` adentro se parte y falla. En PostgreSQL los cuerpos entre `$$` sí se respetan. Las variables `DECLARE` no sobreviven a un `;`.
- Un procedimiento solo muestra su primera tabla, y en SQL Server ninguna si antes informa filas modificadas sin `SET NOCOUNT ON`.
- Sin conteo de filas afectadas.
- Solo se ve la última sentencia con filas de cada base.
- No hay variables por base.
- Una sola ejecución a la vez.
- Cancelar no detiene bases que esperaban turno.

### 33.3 Resultados y archivos

- El grid no ordena, no filtra ni copia; NULL y texto vacío se ven igual; los binarios salen ilegibles.
- El CSV exportado no lleva BOM.
- La importación CSV:
  - solo acepta coma;
  - no admite campos con saltos de línea;
  - no infiere tipos (y en PostgreSQL probablemente falla en columnas no de texto);
  - no acepta nombres con esquema, espacios o acentos;
  - importa a una base a la vez;
  - carga el archivo completo en memoria.
- Abrir un `.sql` solo funciona en UTF-8.

### 33.4 Descubrimiento

Un host a la vez, solo en los puertos 5432 y 1433; no distingue "contraseña incorrecta" de "no hay bases".

### 33.5 Editor

Sin reemplazar, comentar/descomentar, duplicar línea ni ir a línea. El autocompletado solo aparece con Ctrl+Espacio y conoce solo lo ya cargado de la primera base marcada.

### 33.6 Organización

No se eliminan grupos, no se ordenan alfabéticamente, no hay arrastrar y soltar, y no se pueden reordenar las pestañas.

### 33.7 Persistencia

El historial se pierde al cerrar; no se recuerda qué grupos estaban plegados; no hay sincronización entre equipos.

### 33.8 Plataforma

- Solo Windows.
- Solo usuario y contraseña del motor, sin autenticación de Windows.
- Sin opciones de cifrado para PostgreSQL.
- No impide abrir Faro dos veces.
- El plan de ejecución en SQL Server no se ha probado contra un servidor real.

---

## 34. Observaciones encontradas al revisar el código

Comportamientos que tiene hoy el código y que pueden no ser los esperados. **No se modificó ningún código**; se documentan para decidir qué hacer con ellos. Del 34.1 al 34.25 van aproximadamente por impacto. Del 34.26 al 34.32 son hallazgos de una auditoría específica del explorador de esquema, y el 34.33 reúne las diferencias con la documentación. Del 34.34 en adelante salen de leer el documento como usuario de negocio, contrastado con el código y con los registros de uso.

**34.1 Exportar CSV "completo" vuelve a ejecutar todo el script, incluidas las escrituras, sin respetar Solo lectura.** *Confirmado en el código.*
Cuando el resultado quedó recortado (21.3-B), la exportación vuelve a correr **todas** las sentencias del script contra **todas** las bases marcadas en esa ejecución, sin la comprobación de Solo lectura.

- **SQL Server:** un `UPDATE`, `INSERT` o `DELETE` del script se ejecuta y se confirma **otra vez**, incluso en bases de Solo lectura que lo habían rechazado.
- **PostgreSQL:** esos cambios se deshacen al final de la exportación.
- **Cuándo pasa:** si el script mezcla escrituras con un SELECT que superó el tope de filas.

**34.2 Importar CSV a PostgreSQL probablemente falla con columnas que no son texto.** *Probable (depende del controlador).*
Faro envía cada valor como texto. Con la configuración por defecto del controlador de PostgreSQL, el motor no convierte solo ese texto a número, fecha o booleano al insertar, y responde *"column "x" is of type integer but expression is of type character varying"*. En SQL Server la conversión sí ocurre. Conviene probarlo contra una base real antes de usarlo.

**34.3 Importar CSV no respeta Solo lectura.** *Confirmado.* Se puede insertar en una base marcada como protegida.

**34.4 "Cancelar" no detiene las bases que aún no empezaban.** *Probable (depende del controlador).*
Si hay más bases marcadas que el límite de *Bases en paralelo*, o alguna se estaba conectando, Faro solo marca que se pidió cancelar. Cuando a esa base le toca su turno, el pedido de cancelación llega antes de que empiece la consulta, así que el controlador lo ignora y **el script se ejecuta completo**. La base termina en LISTO y, en bases Sin restricciones, sus cambios quedan aplicados.

**34.5 El buscador desmarca las bases que oculta.** *Confirmado.* Si se marcan varias bases, se escribe algo que oculta alguna, y luego se borra la búsqueda, las que estuvieron ocultas **quedan desmarcadas**. Mientras el filtro está puesto, Ejecutar, *Todas*, Comparar y el contador solo consideran las visibles. Cambiar de pestaña con el filtro puesto también desmarca las ocultas, en las dos pestañas.

**34.6 El resumen de Comparar siempre dice que todo coincide.** *Confirmado.* La barra de estado y Diagnóstico (como información, no como advertencia) dicen *"Todas las bases tienen la misma versión de <objeto>."* aunque haya diferencias, aunque todas salgan *"sin mayoría"* e incluso **si todas las bases fallaron**. La causa es que cuentan en la columna *Estado* en vez de *Coincide*. La tabla sí marca bien *"NO — difiere"*.

**34.7 Un `connections.json` dañado se pierde.** *Confirmado.* Si al abrir no se puede leer, Faro arranca vacío (correcto), pero el autoguardado, a los 2 minutos o al cerrar, **escribe encima la configuración vacía**, sin respaldar el archivo dañado.

**34.8 Cambiar las credenciales por defecto no aplica a las bases ya conectadas.** *Confirmado.* Las bases que ya tienen conexiones abiertas en la sesión siguen usando el usuario anterior hasta cerrar Faro, editar esa base o importar configuración.

- *Probar todas las conexiones* sí usa las credenciales nuevas, así que puede mostrar verde mientras las consultas siguen entrando con las viejas.
- **Riesgo:** en SQL Server, si el usuario anterior tenía más permisos, las consultas siguen corriendo con esos permisos.

**34.9 Punto verde que no se pone rojo.** *Confirmado.* Si una base ya tenía conexiones abiertas en la sesión y después el servidor falla, el error aparece en Ejecución pero el punto **sigue verde**. Al ejecutar, solo se pone rojo cuando falla la primera conexión de la sesión; desplegar la base o *Probar todas las conexiones* sí lo corrigen.

**34.10 Un comentario después del último `;` hace que las bases de Solo lectura rechacen todo.** *Confirmado.* Por ejemplo `SELECT * FROM t; -- fin`: el comentario cuenta como una sentencia más que no empieza con SELECT, y la base protegida rechaza el script completo con *"Base de solo lectura…"*.

**34.11 Grupos vacíos invisibles y sin forma de borrarlos.** *Confirmado.*

- Un grupo nuevo no aparece en el árbol hasta que se le mueve una base.
- Un grupo que se queda sin bases desaparece de la vista, pero sigue en *Mover a grupo…* y en el archivo.
- No existe "eliminar grupo".

**34.12 Editar una base no descarta su estructura en memoria.** *Confirmado.* Si se cambia el host, el nombre de la base o incluso el motor, el árbol, el autocompletado, el buscador y *Generar…* siguen con las tablas del servidor anterior hasta *Recargar esquema* o reiniciar. Además, tras editar, el árbol comprueba la conexión contra el servidor **nuevo**, así que el punto refleja un servidor y las categorías otro. *Comparar* no se ve afectado, porque siempre lee en vivo.

**34.13 "Generar…" manda a revisar Diagnóstico, pero el error no está ahí.** *Confirmado.* El motivo concreto (*"Sin usuario/contraseña guardados…"*, *"No se encontró la definición…"*) solo queda en el archivo de log. Tampoco los éxitos se registran en Diagnóstico.

**34.14 Las pestañas restauradas pierden la marca de "sin guardar".** *Confirmado.* Al abrir Faro, una pestaña que tenía cambios sin guardar vuelve con su texto pero **sin el ●**. Si luego se cierra, no pregunta y ese texto se pierde.

**34.15 Abrir un .sql que no esté en UTF-8 falla.** *Confirmado.* Un archivo en ANSI/Windows-1252 con acentos o ñ da *"Error al abrir el archivo: Input length = 1"*. La importación de CSV sí detecta la codificación; abrir scripts no.

**34.16 Una ejecución sin filas deja el grid anterior, y el menú permite exportarlo.** *Confirmado.* Si se ejecuta algo que no devuelve ninguna tabla (solo UPDATE/INSERT/DELETE, o todas las bases fallan), el grid **sigue mostrando la ejecución anterior**. Un SELECT que devuelve 0 filas sí limpia el grid. El contador dice 0 y el botón Exportar se deshabilita, pero **Archivo → Exportar resultados a CSV…** sí exporta esas filas viejas, con un nombre sugerido armado con el SQL nuevo.

**34.17 No hay conteo de filas afectadas.** *Confirmado.* Un UPDATE que modifica 500 filas aparece como *"0 fila(s)"*.

**34.18 Explicar plan y Comparar no esperan a una ejecución en curso.** *Confirmado.* Si se usan mientras corre una ejecución, el grid muestra lo que termine último, y el nombre sugerido del CSV puede no corresponder a lo que se ve.

**34.19 Importar configuración.** *Confirmado.*

- No pide confirmación antes de reemplazar todo el árbol.
- El tema, el acento y los tamaños del archivo quedan guardados, pero **no se ven hasta reiniciar** Faro o cambiar el tema.

**34.20 Credenciales huérfanas.** *Confirmado.* Al importar, las credenciales de bases que ya no existen se quedan en `credentials.dat`. Una exportación posterior "con credenciales" las incluye, así que el archivo puede contener contraseñas de bases que ni siquiera aparecen en él.

**34.21 Las bases descubiertas ignoran Preferencias.** *Confirmado.* Se crean con pool 4 y timeout 30 fijos.

**34.22 Mensaje de validación incompleto.** *Confirmado.* Si el pool o el timeout no son números, el aviso dice *"Completa alias, host, puerto y base de datos."*, sin mencionarlos.

**34.23 Columnas binarias ilegibles.** *Confirmado.* Los valores `bytea` / `varbinary` se muestran y se exportan como `[B@1a2b3c`, un texto sin relación con el dato.

**34.24 Scripts generados con nombres sin comillas y tipos no válidos.** *Confirmado en el código; los tipos inválidos, probables.*

- **Nombres:** tablas o columnas con mayúsculas, espacios o palabras reservadas generan scripts que fallan, o que hacen otra cosa. En PostgreSQL, una columna `user` devuelve el usuario conectado; una vista con mayúsculas regenerada con `CREATE OR REPLACE VIEW public.MiVista` crearía otra vista en minúsculas. En SQL Server, nombres con punto o corchetes tampoco se encuentran al generar o comparar.
- **Tipos en PostgreSQL:** el CREATE de una tabla con columnas de arreglo o de tipos del usuario escribe `ARRAY` o `USER-DEFINED` como tipo. Como la comparación de tablas usa ese mismo CREATE, dos tablas con distinto tipo de arreglo se verían iguales.
- **Tipos en SQL Server:** columnas `xml`, `text`, `ntext` o `image` salen con un largo inválido, como `text(2147483647)`.

**34.25 Ctrl+Z puede vaciar una pestaña recién abierta.** *Probable (depende de la librería del editor).* Faro carga el texto de archivos, favoritos, historial, scripts generados y pestañas restauradas como si se hubiera escrito. Un Ctrl+Z inmediato puede deshacer esa carga y dejar la pestaña vacía (Ctrl+Y lo recupera).

**34.26 Una categoría se queda en "Cargando…" para siempre.** *Confirmado en el código.* Pasos que lo provocan:

- Desplegar **Tablas** y, mientras todavía gira, desplegar **Vistas** (o al revés).
- Pulsar Ctrl+Espacio, que carga en segundo plano las tablas de la primera base marcada, y desplegar Tablas mientras esa carga sigue.
- Usar *Recargar esquema* (o provocar que el árbol se redibuje) mientras una categoría carga, y volver a desplegarla antes de que la carga vieja termine.

La segunda petición se descarta sin quedarse esperando el resultado, así que la fila muestra *"Cargando…"* indefinidamente. Plegar y desplegar no lo arregla; solo *Recargar esquema* (con la carga vieja ya terminada) o reiniciar.

**34.27 Recargar esquema durante una búsqueda deja la base sin categorías.** *Confirmado.* Si la base se ve porque el buscador encontró un objeto suyo y se usa *Recargar esquema*, queda desplegada **vacía** hasta borrar la búsqueda.

**34.28 Comparar funciones sobrecargadas puede dar un resultado falso.** *Confirmado en el código; el resultado es arbitrario.* Si en la base de origen la función es única pero en otra hay dos versiones con el mismo nombre, en esa otra se toma una cualquiera, lo que puede dar un "difiere" o un "Sí" falsos. Si la firma incluye nombres de parámetros distintos entre bases, probablemente sale *"No se encontró la definición"*.

**34.29 Tabla sin columnas visibles genera scripts vacíos como si fueran un éxito.** *Confirmado.* Si la tabla se borró después de listarla, o el usuario no ve sus columnas, Faro genera:

- `SELECT * FROM t`;
- `INSERT INTO t ()` / `VALUES ();`;
- un `CREATE TABLE` vacío.

Muestra el mensaje de éxito y guarda ese resultado vacío hasta *Recargar esquema*.

**34.30 Tres bases que no responden frenan la carga de estructura de todas.** *Probable.* Las cargas de estructura comparten un cupo de 3 para toda la aplicación. Si se despliegan categorías de 3 bases caídas, todas las demás esperan en "Cargando…" hasta que esas fallen por tiempo.

**34.31 PostgreSQL 10 o anterior.** *Probable.* Funciones y Procedimientos muestran *"Error al cargar: … column p.prokind does not exist"*, porque Faro usa un dato del catálogo que existe desde PostgreSQL 11.

**34.32 Vistas de PostgreSQL comparadas entre versiones distintas del motor.** *Probable.* PostgreSQL reescribe el texto de cada vista, y esa reescritura puede cambiar entre versiones mayores (por ejemplo 12, 14 y 16). Una vista idéntica podría marcarse *"NO — difiere"* solo por la versión del servidor. Se puede verificar con el entorno de pruebas `bodegas-test`, que tiene esas tres versiones.

**34.33 Documentación desactualizada respecto del código.**

- El README dice que "expandir una tabla/vista/tipo trae sus columnas": en el árbol, los objetos no se expanden.
- El README dice que la columna *Base de datos* aparece "cuando se consultó más de una": aparece siempre.
- El README dice que el historial evita "duplicados consecutivos": evita cualquier duplicado.
- `PROYECTO_DEFINICION.md` (raíz del repositorio) describe la versión Flutter, con funciones que la versión Java **no tiene**: un modo "Desarrollo" con confirmación y advertencia permanente, historial con filas afectadas por base, y reorganizar arrastrando.

**34.34 Arrastrar una columna del grid desalinea el CSV exportado.** *Confirmado.*
- **Pasos:** con un resultado no recortado, arrastrar el encabezado de una columna a otra posición y presionar *Exportar CSV*.
- **Resultado:** los encabezados salen en el orden nuevo y los valores en el orden original, sin ningún aviso. El archivo queda con columnas cruzadas.

**34.35 Dos ventanas de Faro se pisan la configuración.** *Confirmado; ya ocurrió en los registros de uso.*
- **Pasos:** abrir Faro dos veces, agregar bases en una ventana, cerrarla y después cerrar la otra.
- **Resultado:** la segunda guarda encima su propio catálogo y **las bases agregadas se pierden**. Faro no impide abrir dos ventanas ni avisa.

**34.36 Una base se queda en EJECUTANDO para siempre tras un error grave.** *Confirmado en el código; coherente con un caso real del registro.*
- **Causa:** un error grave durante la lectura, como quedarse sin memoria, no se atrapa.
- **Resultado:** la ejecución termina, pero esa base no cuenta ni como correcta ni como error, no deja mensaje y su punto sigue parpadeando.

**34.37 Una transacción explícita que falla queda abierta.** *Probable.*
- **Pasos:** ejecutar `BEGIN TRAN; UPDATE …; UPDATE … (con error); COMMIT;`.
- **Resultado en SQL Server:** el primer UPDATE queda sin confirmar y bloqueando filas. Lo siguiente que caiga en esa conexión corre dentro de esa transacción, y al cerrarse la conexión todo se deshace.
- **Resultado en PostgreSQL:** esa conexión responde *"current transaction is aborted"* a lo siguiente que se ejecute en ella.

**34.38 Se acepta un timeout negativo.** *Confirmado.* El formulario lo guarda sin quejarse. Desde entonces, cada ejecución contra esa base falla, mientras el punto sigue verde porque la conexión sí funciona.

**34.39 Grupos con el mismo nombre.** *Confirmado.* *Mover a grupo…* muestra el nombre repetido y siempre mueve la base al primero de ellos.

**34.40 Editar, eliminar o importar durante una ejecución corta las consultas.** *Probable (comportamiento de la librería de conexiones).*
- **Qué la dispara:** guardar la edición de una base (aunque no se cambie nada) o eliminarla cierra sus conexiones; importar configuración cierra las de todas.
- **Resultado:** si una ejecución las está usando, termina en ERROR.

**34.41 La cancelación forzada en SQL Server falla en silencio si falta el permiso.** *Probable.* Sin ALTER ANY CONNECTION, la orden `KILL` falla y solo queda en el archivo de log; la consulta sigue corriendo.

**34.42 La exportación completa usa la lista de bases de la ejecución original.** *Probable; caso raro.*
- **Pasos:** eliminar una de esas bases, o importar una configuración que le cambia el host, y después exportar.
- **Resultado:** la exportación vuelve a consultar la base eliminada o el host anterior, y deja conexiones abiertas asociadas a ella.

**34.43 Las credenciales por defecto no se pueden borrar.** *Confirmado.* Guardarlas vacías deja un usuario vacío. Las bases sin credenciales propias intentan entrar con él y reciben un error del servidor, en lugar del aviso *"Sin usuario/contraseña guardados"*.

**34.44 Muchas conexiones permanentes pueden agotar un servidor.** *Probable (comportamiento de la librería de conexiones).* Cada base usada mantiene abiertas tantas conexiones como su pool (4 de fábrica) durante toda la sesión, por cada persona que tenga Faro abierto. Con muchas bodegas en el mismo servidor, eso puede superar su límite (en PostgreSQL, 100 de fábrica) y afectar también al sistema de la empresa (11.3).

**34.45 Procedimientos de SQL Server sin `SET NOCOUNT ON` no muestran su resultado.** *Confirmado en Faro; el orden de lo que devuelve el servidor depende del motor.* Si el procedimiento informa primero filas modificadas y después devuelve su tabla, Faro toma solo la primera respuesta, ve que no es una tabla y sigue de largo. La base queda en LISTO con *"0 fila(s)"*.

---

## Anexo A — Palabras SQL que reconoce el editor

La misma lista se usa para colorear, autocompletar y formatear:

SELECT, FROM, WHERE, INSERT, INTO, VALUES, UPDATE, SET, DELETE, JOIN, LEFT, RIGHT, INNER, OUTER, FULL, CROSS, ON, GROUP, BY, ORDER, HAVING, AND, OR, NOT, IN, AS, DISTINCT, LIMIT, OFFSET, UNION, ALL, CASE, WHEN, THEN, ELSE, END, NULL, IS, LIKE, BETWEEN, EXISTS, CREATE, TABLE, ALTER, DROP, WITH, TOP, ASC, DESC, EXEC, EXECUTE, CALL, PROCEDURE, FUNCTION, TRIGGER, VIEW, INDEX, DECLARE, BEGIN, COMMIT, ROLLBACK, TRANSACTION, PRIMARY, KEY, FOREIGN, REFERENCES, DEFAULT, CHECK, UNIQUE, CONSTRAINT, COLUMN, RENAME, TRUNCATE, MERGE, USING, RETURNS, GRANT, REVOKE, IDENTITY, OUTPUT, COUNT, SUM, AVG, MIN, MAX, COALESCE, CAST, CONVERT.

---

## Anexo B — Catálogo de mensajes

Mensajes que muestra la **barra de estado**, salvo que se indique otra ventana. `<…>` es un dato variable.

| Área | Mensaje |
|---|---|
| Ejecutar | *"Escribe una consulta primero."* · *"Selecciona al menos una base de datos."* |
| Bases | *"<alias> ahora es Solo lectura."* / *"… Sin restricciones."* · *"Nueva consulta para <alias> — su casilla ya quedó marcada."* |
| Grupos | *"Grupo creado: <nombre>"* · *"Grupo renombrado: <nombre>"* · *"<alias> movida a <grupo>."* · *"Marcadas N base(s) de <grupo>."* / *"Desmarcadas …"* · *"Bases ordenadas A-Z en <grupo>."* · *"Limpia el buscador para cambiar el orden — con un filtro puesto, mover afectaría filas que no estás viendo."* |
| Descubrir | *"N base(s) agregada(s) desde el escaneo."* · *"N base(s) agregada(s) en <grupo>."* · En la ventana: *"Escribe un host."* · *"Buscando…"* · *"N base(s) encontradas · M nueva(s)."* · *"No se encontró ninguna base accesible en ese host."* · *"Error al buscar: <motivo>"* |
| Probar todas | *"No hay bases configuradas."* · *"Probando N conexión(es)…"* · *"M/N conexión(es) exitosa(s)."* (+ *" Ver Diagnóstico."*) |
| Agregar/editar (ventana) | *"Completa alias, host, puerto y base de datos."* · *"Completa alias, host, puerto y base de datos primero."* · *"El tamaño de pool mínimo es 2 — con menos, cancelar una consulta puede no funcionar bien."* · *"Conectando…"* · *"Conectado — <versión>"* · *"Error de conexión: <motivo>"* |
| Credenciales | *"Credenciales por defecto guardadas."* |
| Archivos .sql | *"Abierto: <archivo>"* · *"Error al abrir el archivo: <motivo>"* · *"Guardado: <archivo>"* · *"Error al guardar: <motivo>"* |
| Buscar (barra) | *"Sin resultados"* |
| Exportar CSV | *"No hay resultados para exportar."* · *"Exportando a <archivo>…"* · *"Exportando: N de M fila(s)…"* · *"Exportado: <archivo>"* · *"Exportando el resultado completo a <archivo>…"* · *"Exportado completo: <archivo> (N fila(s))"* · *"Error al exportar: <motivo>"* |
| Importar CSV (ventana) | *"Elige una base de datos."* · *"Escribe el nombre de la tabla destino."* · *"Elige un archivo CSV primero."* · *"Esa base no tiene credenciales guardadas — edítala o define credenciales por defecto."* · *"Importando…"* · *"N fila(s) importada(s) a <tabla>."* (+ *" · leído como <codificación>, no era UTF-8"*) · *"Error al importar: <motivo>"* |
| Generar | *"Generando <acción> para <objeto>…"* · *"<acción> generado para <objeto> — su casilla ya quedó marcada."* · *"No se pudo generar <acción> de <objeto> — revisa Diagnóstico."* (acción: SELECT, INSERT, UPDATE, DELETE, CREATE TABLE o script CREATE) |
| Comparar | *"Marca al menos 2 bases para comparar <objeto>."* · *"Comparando <objeto> en N base(s)…"* · *"Todas las bases tienen la misma versión de <objeto>."* · *"N base(s) con una versión DISTINTA de <objeto>."* · *"No se pudo comparar <objeto> — revisa Diagnóstico."* |
| Favoritos | *"Favorito guardado: <nombre>"* |
| Configuración | *"Configuración exportada: <archivo>"* · *"Error al exportar configuración: <motivo>"* · *"Configuración importada: <archivo>"* · *"Error al importar configuración: <motivo>"* |
| Preferencias (ventana) | *"<campo>: debe ser un número entero."* · *"El tamaño de pool mínimo es 2 — …"* · *"el mínimo es 1.000 filas"* |
| Ejecución (errores por base) | *"Sin usuario/contraseña guardados (edítala y guarda unas propias, o define credenciales por defecto)"* · *"Base de solo lectura — la consulta debe empezar con SELECT/WITH/SHOW/EXPLAIN/DESCRIBE"* · *"Cancelado por el usuario"* · pista de *"¿Falta un punto y coma (;)…?"* |
| Árbol (filas) | *"Cargando <categoría>…"* · *"Error al cargar: <motivo>"* · pista de codificación *"— el texto de esta base no es UTF-8 válido. Editar base → Codificación (prueba LATIN1 o WIN1252)."* |
| Ventanas y títulos | *"Cambios sin guardar"* · *"Eliminar base de datos"* · *"Mover a grupo"* · *"Nuevo grupo de conexiones"* · *"Renombrar grupo"* · *"Guardar como favorito"* · *"Exportar configuración"* · *"Importar configuración"* · *"Abrir archivo .sql"* · *"Guardar script SQL"* · *"Exportar resultados a CSV"* · *"Elegir archivo CSV"* · *"Acerca de Faro"* |

### Mensajes de la pestaña Diagnóstico

| Nivel | Mensaje |
|---|---|
| INFO | *"Tema cambiado a oscuro."* / *"… claro."* (solo con el botón luna/sol) · *"SQL formateado."* · *"Script guardado en <archivo>"* |
| INFO | *"Base agregada: <alias>"* · *"Base eliminada: <alias>"* · *"<alias>: modo cambiado a <modo> desde el árbol."* · *"<alias>: movida al grupo "<grupo>"."* · *"Grupo de conexiones creado: <nombre>"* · *"Grupo "<viejo>" renombrado a "<nuevo>"."* |
| INFO | *"Credenciales por defecto guardadas."* · *"N base(s) agregada(s) desde el escaneo de bases de datos."* · *"N base(s) agregada(s) desde el escaneo de <host> — en <grupo>."* |
| INFO / WARN | *"Probar todas las conexiones: M/N exitosas."* · WARN *"Conexión falló — <alias>: <motivo>"* |
| INFO | *"Nueva consulta abierta para <alias> (casilla marcada automáticamente)."* · *"Favorito guardado: <nombre>"* · *"Favorito abierto: <nombre>"* · *"Favorito eliminado: <nombre>"* |
| INFO / ERROR | *"Explicar plan: usando solo <alias> (la primera base marcada) — el plan es por base, no se mezclan varias."* · *"Plan de ejecución pedido para <alias>."* · ERROR *"Error al pedir el plan de <alias>: <motivo>"* |
| WARN / ERROR | *"Comparar '<objeto>': hace falta marcar al menos 2 bases."* · *"Todas las bases tienen la misma versión de <objeto>."* · *"N base(s) con una versión DISTINTA de <objeto>."* · ERROR *"Comparar <objeto> — <alias>: <motivo>"* · ERROR *"Comparar <objeto> falló: <motivo>"* |
| INFO / WARN / ERROR | *"Resultados exportados a <archivo> (N fila(s))."* · *"Exportado completo: <archivo> (N fila(s))"* (WARN si hubo errores, con *" — N base(s) con error"*) · ERROR *"Exportar — <alias>: <motivo>"* · ERROR *"Error al exportar a <archivo>: <motivo>"* |
| WARN | *"Resultado recortado en N fila(s) — hay más. Exportar CSV trae el resultado completo."* |
| INFO | *"Cancelando N base(s)."* |
| INFO / WARN | *"Configuración exportada a <archivo> (sin credenciales)."* · WARN *"Configuración exportada a <archivo> — CON usuarios y contraseñas en texto legible. Trátalo como un archivo con secretos."* · *"Configuración importada de <archivo> — reemplazó conexiones y favoritos actuales."* |
| ERROR | *"Error al ejecutar: <motivo>"* · *"Autoguardado falló: <motivo>"* |

### Mensajes del servidor que se ven con frecuencia

Mensajes reales tomados de los registros de uso. Los escribe el controlador o el motor, no Faro, por eso vienen en inglés.

| Situación | Mensaje |
|---|---|
| PostgreSQL apagado (primera conexión de la sesión) | *"Failed to initialize pool: Connection to <host>:<puerto> refused. Check that the hostname and port are correct and that the postmaster is accepting TCP/IP connections."* |
| SQL Server apagado (primera conexión de la sesión) | *"Failed to initialize pool: The TCP/IP connection to the host <host>, port <puerto> has failed… Make sure that TCP connections to the port are not blocked by a firewall."* |
| SQL Server con certificado autofirmado y la casilla de certificado desmarcada | *"…the driver could not establish a secure connection to SQL Server… PKIX path building failed…"* |
| Base sin credenciales, al cargar el esquema | *"Sin usuario/contraseña guardados para <alias>"* |
| Timeout en PostgreSQL | *"canceling statement due to user request"* |
| Timeout en SQL Server | *"The query has timed out"* |
| PostgreSQL, falta un `;` entre sentencias | *"syntax error at or near "SELECT""* + la pista de Faro sobre el punto y coma |

El prefijo *"Failed to initialize pool:"* solo aparece cuando falla la **primera** conexión de la sesión con esa base.
