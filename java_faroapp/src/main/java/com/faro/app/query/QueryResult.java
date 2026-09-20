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
public record QueryResult(List<String> columns, List<Object[]> rows, List<String> errors, boolean truncated) {

    /**
     * {@code truncated} — la lectura se cortó al llegar al tope de filas en memoria
     * ({@code AppPreferences#maxDisplayRows}), así que {@code rows} <b>no</b> es el
     * resultado completo de la consulta (2026-09-20).
     *
     * <p>Deliberadamente <b>no</b> se acompaña de un "total real": saberlo exigiría
     * seguir leyendo todas las filas restantes, que es justo el trabajo —y la memoria—
     * que el tope existe para no hacer. El aviso dice "hay más", no un número inventado.
     *
     * <p>Quien muestre esto <b>tiene que avisarlo</b>: recortar en silencio es peor que
     * no recortar. Y "Exportar CSV" no debe leer de acá cuando está en {@code true} —
     * exporta el resultado completo releyéndolo de la base, ver {@link CsvExportService}.
     */
    public QueryResult {
    }

    /** Resultado completo, sin recorte — el caso normal. */
    public QueryResult(List<String> columns, List<Object[]> rows, List<String> errors) {
        this(columns, rows, errors, false);
    }
}
