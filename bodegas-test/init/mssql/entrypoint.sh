#!/bin/bash
# SQL Server no trae el mecanismo de "correr .sql al inicializar" que sí
# trae la imagen oficial de Postgres (docker-entrypoint-initdb.d) — hay
# que arrancar el motor, esperar a que acepte conexiones, y correr el
# script a mano nosotros mismos.
set -e

/opt/mssql/bin/sqlservr &
SQLSERVR_PID=$!

if [ -x /opt/mssql-tools18/bin/sqlcmd ]; then
    SQLCMD=(/opt/mssql-tools18/bin/sqlcmd -C)
else
    SQLCMD=(/opt/mssql-tools/bin/sqlcmd)
fi

echo "Esperando a que el motor acepte conexiones..."
for i in $(seq 1 60); do
    "${SQLCMD[@]}" -S localhost -U sa -P "$MSSQL_SA_PASSWORD" -Q "SELECT 1" > /dev/null 2>&1 && break
    sleep 2
done

# Idempotente: solo siembra si la base 'bodega' todavía no existe — así
# un contenedor reiniciado (mismo volumen, datos ya sembrados antes) no
# reintenta insertar 20,000 filas de más cada vez que arranca.
DB_EXISTS=$("${SQLCMD[@]}" -S localhost -U sa -P "$MSSQL_SA_PASSWORD" -h -1 \
    -Q "SET NOCOUNT ON; SELECT COUNT(*) FROM sys.databases WHERE name='bodega'" | tr -d '[:space:]')

if [ "$DB_EXISTS" = "0" ]; then
    echo "Base 'bodega' no existe todavia — sembrando desde seed.sql..."
    "${SQLCMD[@]}" -S localhost -U sa -P "$MSSQL_SA_PASSWORD" -i /docker-entrypoint-initdb.d/seed.sql
else
    echo "Base 'bodega' ya existe — no se vuelve a sembrar."
fi

wait $SQLSERVR_PID
