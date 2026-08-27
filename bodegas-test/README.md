# Bodegas de prueba para Faro

6 bases de datos desechables para probar Faro con volumen real (500,000 filas
por bodega, subido de 20,000 el 2026-08-22) y varias versiones de motor a la vez: 3 PostgreSQL (12/14/16) y
3 SQL Server (2017/2019/2022). Todas traen la misma tabla `productos`, con
texto en español (acentos/ñ/comillas) a propósito para forzar el tema de
codificación en los grids.

**Un objeto real de cada categoría del explorador de esquema** (2026-08-26,
para poder probar en vivo Vistas/Funciones/Procedimientos/Triggers/Tipos —
antes solo había tablas, no había nada real que expandir en esas 4
categorías). Las 6 bodegas traen los mismos 5 objetos, con nombres iguales
entre motores donde el motor lo permite (`estado_pedido` es un enum solo en
Postgres — SQL Server no tiene enums, así que ahí el tipo se llama
`codigo_postal`, un alias type):

| Categoría | Nombre | Qué hace |
|---|---|---|
| Vista | `vista_productos_caros` | Productos con `precio > 400` |
| Función | `fn_total_productos()` | `COUNT(*)` real de `productos` |
| Procedimiento | `sp_actualizar_stock(id, cantidad)` | `UPDATE productos SET stock = ...` |
| Trigger | `trg_actualizar_fecha` | Al hacer `UPDATE` sobre `productos`, pone `fecha_actualizacion` en la fecha real de hoy sola |
| Tipo | `estado_pedido` (Postgres, enum) / `codigo_postal` (SQL Server, alias type) | Sin usar en ninguna columna de `productos` a propósito — solo para que la categoría "Tipos" tenga algo real que mostrar |

Verificado funcionando de verdad en los 6 contenedores (no solo que existan
sintácticamente) — `SELECT`/`CALL`/`EXEC` reales contra cada uno, incluyendo
confirmar que el trigger sí actualiza `fecha_actualizacion` sola.

No es parte de la app — es infraestructura de prueba, para correr en
cualquier máquina con Docker instalado (`compose.yml` es formato estándar
de Compose, sin nada específico de Docker — también corre con Podman si
alguna vez se vuelve a usar, aunque en este proyecto se probó y se dejó
Podman por un problema de red específico de esa herramienta en esta
máquina, ver `CONTEXTO_SESIONES.md` 2026-08-21 para el detalle completo).

## Requisitos

- Docker Desktop instalado, con `docker compose` funcionando (revisa con
  `docker compose version`).

## Levantar todo

Desde esta carpeta:

```
docker compose up -d
```

La primera vez que cada contenedor arranca (volumen vacío), se siembra
solo — Postgres lo hace con su mecanismo nativo
(`docker-entrypoint-initdb.d`), SQL Server con un script wrapper propio
(`init/mssql/entrypoint.sh`, porque esa imagen no trae ese mecanismo). Las
siguientes veces que arranques (mismo volumen, ya con datos) no vuelve a
sembrar — es idempotente.

**Nota real (2026-08-26):** por eso mismo, si ya tenías las 6 bodegas
corriendo desde antes (mismo volumen con datos) cuando se agregaron los 5
objetos nuevos de la tabla de arriba a `seed.sql`, el mecanismo de siembra
NO los crea solo — hay que aplicarlos a mano una sola vez contra los
contenedores ya corriendo (`docker exec ... psql`/`sqlcmd` con solo la
parte nueva del script, no todo `seed.sql` de nuevo — eso reinsertaría
500,000 filas de más). Un volumen nuevo (`docker compose down -v` y volver
a levantar) sí los trae solo, ya forman parte de `seed.sql`.

Verificar que las 6 ya sembraron (puede tardar 1-2 min la primera vez,
sobre todo SQL Server):

```
docker compose logs -f
```

## Credenciales (para pegar en Faro → Agregar base de datos)

| Motor | Host | Puerto | Base | Usuario | Contraseña |
|---|---|---|---|---|---|
| PostgreSQL 12 | localhost | 55432 | bodega | faro | `Faro_Test_2026!` |
| PostgreSQL 14 | localhost | 55433 | bodega | faro | `Faro_Test_2026!` |
| PostgreSQL 16 | localhost | 55434 | bodega | faro | `Faro_Test_2026!` |
| SQL Server 2017 | localhost | 55435 | bodega | sa | `Faro_Test_2026!` |
| SQL Server 2019 | localhost | 55436 | bodega | sa | `Faro_Test_2026!` |
| SQL Server 2022 | localhost | 55437 | bodega | sa | `Faro_Test_2026!` |

## Apagar / borrar

```
docker compose down          # apaga, conserva los volúmenes (datos)
docker compose down -v       # apaga y BORRA los volúmenes (reinicia todo desde cero)
```

## Por qué SQL Server tiene 2GB de límite y Postgres 512MB

No es un descuido — Microsoft documenta 2GB como el mínimo real para que
el contenedor de SQL Server arranque. Con menos, crashea o entra en
reinicio constante. Postgres, con este tamaño de datos (500K filas por
tabla — subido de 20K/256MB el 2026-08-22), necesita el margen extra:
256MB se quedaba muy justo entre los ~200MB reales de datos+índice y lo
que Postgres necesita para correr.

## Si los 3 contenedores SQL Server quedan en loop de reinicio

La memoria (arriba) no es la única causa posible — confirmado con un caso
real el 2026-08-25. `init/mssql/entrypoint.sh` corre como
`command: ["/bin/bash", "/entrypoint-init.sh"]`, o sea que reemplaza el
proceso principal del contenedor: si ese script tiene terminadores de
línea **CRLF** (Windows) en vez de LF, bash truena con `syntax error` de
inmediato, el contenedor entero muere, y `restart: unless-stopped` lo
reinicia en loop infinito sin que SQL Server llegue a arrancar nunca —
mismo síntoma visible en `docker ps` (`Restarting`) que un problema de
memoria, pero causa totalmente distinta. Diagnóstico rápido:
`docker logs faro-bodega4-mssql2017` (o el contenedor que sea) — si ves
`syntax error near unexpected token` o `$'\r': command not found`, es
esto, no memoria. Ya hay un `.gitattributes` en la raíz del repo
(`*.sh text eol=lf`) que debería evitar que un checkout futuro lo vuelva
a romper — si aun así pasa, correr `sed -i 's/\r$//' init/mssql/entrypoint.sh`
y `docker restart` a los 3 contenedores lo arregla sin perder datos (el
script vive en un bind mount, no en la imagen). Detalle completo en
`CONTEXTO_SESIONES.md`, 2026-08-25.
