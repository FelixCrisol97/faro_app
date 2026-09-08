package com.faro.app.query;

import java.util.List;

/**
 * Resultado combinado de correr una consulta contra una o más bases —
 * {@code columns} incluye una columna inicial "Base de datos" (de dónde
 * vino cada fila) seguida de las columnas reales del {@code ResultSet}.
 * {@code errors} son mensajes por base que falló (sin credenciales, SQL
 * inválido, conexión rechazada, etc.) — no detienen la ejecución en las
 * demás bases.
 *
 * <p><b>{@code rows} es {@code List<Object[]>}, no {@code List<List<Object>>}</b>
 * (optimización de memoria, ver {@code OPTIMIZACION_RENDIMIENTO.md} —
 * hallazgo real: una corrida masiva contra varias bodegas a la vez, con
 * cientos de miles de filas por base, es justo el caso de uso que motiva
 * este proyecto). Un {@code Object[]} no carga el overhead por-instancia de
 * {@code ArrayList} (tamaño, modCount, capacidad separada de la longitud) —
 * con millones de filas combinadas ese overhead solo, multiplicado, ya es
 * varias decenas de MB que no cumplen ningún propósito real.
 */
public record QueryResult(List<String> columns, List<Object[]> rows, List<String> errors) {
}
