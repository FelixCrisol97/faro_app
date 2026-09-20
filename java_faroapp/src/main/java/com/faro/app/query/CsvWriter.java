package com.faro.app.query;

/**
 * Escritura de campos CSV — la contraparte de {@link CsvParser} (2026-09-20).
 *
 * <p>Vivía dentro de {@code MainController} porque el único que exportaba era el botón
 * de la barra. Se movió acá al aparecer un segundo escritor, {@link CsvExportService},
 * que está en este paquete y no podía alcanzarlo: el escapado es una regla del formato
 * CSV, no del controlador de la ventana.
 */
public final class CsvWriter {

    private CsvWriter() {
    }

    /**
     * Escribe {@code value} escapado como campo CSV directo sobre {@code out}, sin crear
     * ninguna cadena intermedia.
     *
     * <p><b>Por qué no devuelve un {@code String}</b> (2026-09-10, hallazgo B6 de
     * {@code ANALISIS_OPTIMIZACION_ESTRUCTURA.md}): el {@code csvEscape} anterior sí lo
     * hacía, y en el caso que necesita comillas armaba DOS cadenas más por celda (el
     * {@code replace} y la concatenación). Para el resultado combinado que este proyecto
     * maneja de verdad —3 millones de filas × 10 columnas— eso son decenas de millones de
     * cadenas temporales que mueren de inmediato, y justo cuando el heap ya está ocupado.
     * Acá el caso común (sin caracteres especiales) no asigna NADA: se copia al buffer
     * que ya existe.
     *
     * <p>{@code null} se escribe como campo vacío. {@code \r} entra en la condición
     * (hallazgo A10) — antes solo se miraban {@code ,}, {@code "} y {@code \n}, así que un
     * valor con retorno de carro suelto (pasa con datos venidos de sistemas viejos) rompía
     * la fila sin comillas que la protegieran.
     */
    public static void appendEscaped(StringBuilder out, Object value) {
        if (value == null) {
            return;
        }
        String text = value.toString();
        if (text.indexOf(',') < 0 && text.indexOf('"') < 0
                && text.indexOf('\n') < 0 && text.indexOf('\r') < 0) {
            out.append(text);
            return;
        }
        out.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"') {
                out.append('"');
            }
            out.append(c);
        }
        out.append('"');
    }

    /**
     * Una fila completa, con sus campos separados por coma y un salto al final.
     *
     * <p>El salto va acá y no lo pone el llamador porque este formato lo escriben ahora
     * dos caminos distintos (el grid en memoria y el streaming desde la base) y el final
     * de línea es parte de la fila, no del bucle de quien la escribe.
     */
    public static void appendRow(StringBuilder out, Object[] values) {
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                out.append(',');
            }
            appendEscaped(out, values[i]);
        }
        out.append('\n');
    }
}
