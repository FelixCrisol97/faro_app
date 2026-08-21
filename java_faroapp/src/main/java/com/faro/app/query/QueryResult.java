package com.faro.app.query;

import java.util.List;

/**
 * Resultado combinado de correr una consulta contra una o más bases —
 * {@code columns} incluye una columna inicial "Base de datos" (de dónde
 * vino cada fila) seguida de las columnas reales del {@code ResultSet}.
 * {@code errors} son mensajes por base que falló (sin credenciales, SQL
 * inválido, conexión rechazada, etc.) — no detienen la ejecución en las
 * demás bases.
 */
public record QueryResult(List<String> columns, List<List<Object>> rows, List<String> errors) {
}
