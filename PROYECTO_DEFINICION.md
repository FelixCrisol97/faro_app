# Faro — Consulta multi-bodega

## ¿Qué es?

Faro es una herramienta de escritorio que permite ejecutar **una sola consulta SQL contra muchas bases de datos distribuidas al mismo tiempo**, en lugar de tener que conectarse una por una. Está pensada para un negocio con varias sucursales/bodegas repartidas geográficamente, cada una con su propia base de datos — el caso típico es que compartan la misma estructura de tablas (sucursales de una misma cadena), pero **no es un requisito**: las bases de datos agrupadas en un mismo servidor pueden ser completamente distintas entre sí (esquemas distintos, propósitos distintos), y es el usuario quien decide cómo agruparlas.

## ¿Para qué sirve?

El problema que resuelve: cuando alguien necesita revisar o analizar información que vive repartida en decenas de bases de datos independientes (una por sucursal/bodega), normalmente tendría que conectarse a cada una, ejecutar la misma consulta, y juntar los resultados a mano. Faro automatiza todo ese proceso: se escribe la consulta una sola vez, se eligen las bases de datos a consultar, y la aplicación las ejecuta todas en paralelo y junta los resultados en una sola tabla.

## Cómo está organizada la información

- Las bases de datos se agrupan por **servidor**, que es simplemente una agrupación libre definida por el usuario — no implica que las bases de datos que contiene compartan estructura. Puede representar una cadena de sucursales (varias bodegas con la misma estructura pero con diferente IP), un servidor central (misma IP) que aloja varias bases de datos distintas de una misma empresa, o cualquier otro criterio de agrupación que le sea útil al usuario (por función, por proyecto, etc.), incluyendo bases de datos con esquemas totalmente distintos entre sí.
- Dentro de cada servidor, el usuario elige exactamente qué bases de datos quiere incluir en la consulta (todas, ninguna, o una selección puntual).
- La aplicación soporta dos motores de base de datos (PostgreSQL y SQL Server) de forma transparente para el usuario — el tipo de motor se configura una vez por servidor y a partir de ahí todo funciona igual sin que el usuario tenga que pensar en la diferencia.

## Funciones principales

- **Consulta multi-bodega**: escribir una consulta y ejecutarla contra todas las bases de datos seleccionadas de un servidor al mismo tiempo, con los resultados combinados en una sola tabla (indicando de qué base de datos vino cada fila cuando hay más de una).
- **Modo de solo lectura por seguridad**: por defecto, cualquier servidor solo permite consultas de lectura (SELECT). Cualquier intento de modificar datos (insertar, actualizar, borrar, cambiar estructura, etc.) se bloquea automáticamente con un mensaje explicando por qué, para proteger los datos reales del negocio de errores accidentales.
- **Modo de desarrollo por servidor**: un servidor puntual (por ejemplo, uno de pruebas) se puede marcar explícitamente como "Desarrollo", lo que le quita esa restricción y permite ejecutar cualquier tipo de consulta sin excepción. Este cambio pide confirmación antes de aplicarse y, mientras el servidor está en ese modo, la aplicación muestra una advertencia visual permanente para que no se olvide que se está trabajando sin restricciones.
- **Selección flexible del texto a ejecutar**: si se tiene más de una consulta escrita a la vez (por ejemplo, para ir probando variantes), se puede seleccionar manualmente cuál ejecutar, o la aplicación ejecuta automáticamente la última si hay varias separadas por punto y coma.
- **Autocompletado de nombres de tabla**: al escribir después de la palabra `FROM`, sugiere los nombres de tablas disponibles en las bases de datos del servidor activo.
- **Formatear consulta**: reordena y limpia el texto de la consulta SQL para que sea más legible, sin cambiar su significado.
- **Historial de ejecuciones**: guarda, mientras la aplicación está abierta, cada consulta que se ejecutó, la hora, cuántas filas devolvió o afectó cada base de datos, y el detalle de éxito/error por base de datos. Se puede revisar en cualquier momento durante la sesión.
- **Consultas favoritas**: permite guardar consultas usadas con frecuencia, con un nombre, para reutilizarlas rápidamente durante la sesión sin tener que reescribirlas.
- **Cargar y exportar**: se puede cargar una consulta desde un archivo de texto, y exportar los resultados obtenidos a un archivo CSV para usarlos en otra herramienta (como Excel).
- **Cancelar una ejecución en curso**: si una consulta está tardando o se lanzó por error, se puede detener sin tener que cerrar la aplicación.
- **Administración de servidores y bases de datos**: agregar, editar o quitar servidores y las bases de datos que contiene cada uno, además de probar la conexión a una base de datos específica antes de usarla — directamente desde el mismo árbol lateral que se usa para elegir qué consultar (2026-08-12: dejó de ser una pantalla aparte). También se pueden crear bases de datos "sin grupo" (sin asignar a ningún servidor todavía) y reorganizar todo — servidores y bases de datos, agrupadas o sueltas — arrastrando con el mouse.
- **Apariencia personalizable**: tema claro u oscuro, y elección de color de acento, para adaptarse a la preferencia del usuario.

## A quién está dirigido

A alguien que necesita hacer análisis, auditorías o revisiones puntuales de datos que están repartidos en múltiples sucursales o bases de datos independientes, y que hoy tendría que hacerlo conectándose una por una — típicamente perfiles de soporte técnico, analistas de datos o administradores de un negocio con operación distribuida.
