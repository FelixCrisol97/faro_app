package com.faro.app.query;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * El escapado de campos CSV de {@link CsvWriter}.
 *
 * <p>Estos casos vivían en {@code MainControllerLogicTest} y se mudaron acá el
 * 2026-09-20, cuando el escapado salió de {@code MainController} al aparecer un segundo
 * exportador ({@link CsvExportService}) que está en este paquete. Son los mismos casos:
 * lo que se movió es el código, no la cobertura.
 */
class CsvWriterTest {

    // ---- appendEscaped ----
    //
    // Escribe sobre un StringBuilder en vez de devolver un String justamente para no
    // asignar nada en el caso común: exportar 3M filas × 10 columnas pasaba por acá
    // decenas de millones de veces.

    private static String escaped(Object value) {
        StringBuilder out = new StringBuilder();
        CsvWriter.appendEscaped(out, value);
        return out.toString();
    }

    @Test
    void elCasoComunNoSeEntrecomilla() {
        assertEquals("martillo", escaped("martillo"));
        assertEquals("123", escaped(123));
        assertEquals("3.5", escaped(3.5));
        assertEquals("", escaped(""));
    }

    /** {@code null} se escribe como campo vacío, no como el texto "null". */
    @Test
    void nullEsUnCampoVacio() {
        assertEquals("", escaped(null));
    }

    @Test
    void entrecomillaLoQueRompeElFormato() {
        assertEquals("\"Calle 5, Centro\"", escaped("Calle 5, Centro"));
        assertEquals("\"linea1\nlinea2\"", escaped("linea1\nlinea2"));
    }

    /** Las comillas internas se duplican, que es como se escapan en CSV. */
    @Test
    void duplicaLasComillasInternas() {
        assertEquals("\"El \"\"Jefe\"\"\"", escaped("El \"Jefe\""));
    }

    /**
     * El retorno de carro SOLO (sin salto de línea) — pasa con datos venidos de
     * sistemas viejos. Antes no entraba en la condición, así que rompía la fila sin
     * comillas que la protegieran (hallazgo A10).
     */
    @Test
    void elRetornoDeCarroSoloTambienSeEntrecomilla() {
        assertEquals("\"con\rretorno\"", escaped("con\rretorno"));
    }

    /** Escribe sobre lo que ya había en el buffer, no lo reemplaza — así lo usa el bucle de exportación. */
    @Test
    void escribeAlFinalDelBufferExistente() {
        StringBuilder out = new StringBuilder("previo,");
        CsvWriter.appendEscaped(out, "nuevo");

        assertEquals("previo,nuevo", out.toString());
    }
    // ---- appendRow ----

    /**
     * La fila completa, que es lo que usa el exportador en streaming: separadores entre
     * campos y salto al final. El salto lo pone esta función y no el bucle de quien
     * escribe, porque ahora hay dos caminos distintos escribiendo el mismo formato.
     */
    @Test
    void laFilaLlevaSeparadoresYSaltoFinal() {
        StringBuilder out = new StringBuilder();

        CsvWriter.appendRow(out, new Object[] {"bodega-01", 42, "Calle 5, Centro"});

        assertEquals("bodega-01,42,\"Calle 5, Centro\"\n", out.toString());
    }

    /** Una fila de un solo campo no lleva separador de más. */
    @Test
    void unaFilaDeUnSoloCampoNoLlevaComa() {
        StringBuilder out = new StringBuilder();

        CsvWriter.appendRow(out, new Object[] {"solo"});

        assertEquals("solo\n", out.toString());
    }
}
