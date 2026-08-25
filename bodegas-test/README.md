# Bodegas de prueba para Faro

6 bases de datos desechables para probar Faro con volumen real (500,000 filas
por bodega, subido de 20,000 el 2026-08-22) y varias versiones de motor a la vez: 3 PostgreSQL (12/14/16) y
3 SQL Server (2017/2019/2022). Todas traen la misma tabla `productos`, con
texto en español (acentos/ñ/comillas) a propósito para forzar el tema de
codificación en los grids.

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
