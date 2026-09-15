package com.faro.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Lógica pura de {@link MainController} — sin JavaFX de por medio (2026-09-14, cierra
 * el hallazgo C8 de {@code ANALISIS_OPTIMIZACION_ESTRUCTURA.md}).
 *
 * <p>Los tres métodos son estáticos y no tocan ningún nodo: se podían testear desde
 * siempre, simplemente nadie lo había hecho. Son package-private a propósito para
 * poder ejercitarlos directo, mismo criterio que
 * {@code SchemaIntrospector#sqlServerTypeWithLength} y
 * {@code QueryExecutionService#isReadOnlyStatement}.
 *
 * <p>La clase NO se instancia — {@code MainController} necesita un FXML cargado y
 * el toolkit arrancado, y la suite permanente no arranca JavaFX a propósito.
 */
class MainControllerLogicTest {

    // ---- appendCsvEscaped ----
    //
    // Escribe sobre un StringBuilder en vez de devolver un String justamente para no
    // asignar nada en el caso común: exportar 3M filas × 10 columnas pasaba por acá
    // decenas de millones de veces.

    private static String escaped(Object value) {
        StringBuilder out = new StringBuilder();
        MainController.appendCsvEscaped(out, value);
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
        MainController.appendCsvEscaped(out, "nuevo");

        assertEquals("previo,nuevo", out.toString());
    }

    // ---- indexOfIgnoreCase ----
    //
    // Reemplazó a getText().toLowerCase() en "Buscar en el script" (hallazgo #9): compara
    // en el lugar en vez de copiar el documento dos veces por cada F3.

    @Test
    void buscaHaciaAdelanteSinDistinguirMayusculas() {
        assertEquals(0, MainController.indexOfIgnoreCase("SELECT * FROM t", "select", 0, true));
        assertEquals(9, MainController.indexOfIgnoreCase("SELECT * FROM t", "from", 0, true));
        assertEquals(-1, MainController.indexOfIgnoreCase("SELECT * FROM t", "where", 0, true));
    }

    /** Desde una posición dada encuentra la SIGUIENTE, no la primera — es lo que hace funcionar F3 repetido. */
    @Test
    void buscaDesdeLaPosicionDada() {
        String texto = "select a, select b";

        assertEquals(0, MainController.indexOfIgnoreCase(texto, "select", 0, true));
        assertEquals(10, MainController.indexOfIgnoreCase(texto, "select", 1, true));
    }

    @Test
    void buscaHaciaAtras() {
        String texto = "select a, select b";

        assertEquals(10, MainController.indexOfIgnoreCase(texto, "select", 17, false));
        assertEquals(0, MainController.indexOfIgnoreCase(texto, "select", 9, false));
    }

    /**
     * Los bordes que rompen una búsqueda escrita a mano: aguja más larga que el texto,
     * aguja vacía, y un {@code from} fuera de rango (pasa al dar la vuelta circular
     * desde el final del documento).
     */
    @Test
    void casosDeBorde() {
        assertEquals(-1, MainController.indexOfIgnoreCase("ab", "abcdef", 0, true));
        assertEquals(-1, MainController.indexOfIgnoreCase("", "a", 0, true));
        assertEquals(-1, MainController.indexOfIgnoreCase("abc", "", 0, true));
        assertEquals(-1, MainController.indexOfIgnoreCase("abc", "z", 99, true));
        assertEquals(0, MainController.indexOfIgnoreCase("abc", "a", 99, false));
    }

    // ---- summarize ----

    @Test
    void summarizeDejaUnaSolaLinea() {
        assertEquals("SELECT * FROM t WHERE x = 1",
                MainController.summarize("SELECT *\nFROM t\r\nWHERE x = 1"));
    }

    @Test
    void summarizeRecortaLoLargoConPuntosSuspensivos() {
        String largo = "SELECT " + "x".repeat(200);

        String resumen = MainController.summarize(largo);

        assertEquals(81, resumen.length(), "80 caracteres más el carácter de elipsis");
        assertTrue(resumen.endsWith("…"));
    }

    @Test
    void summarizeNoTocaLoQueYaEsCorto() {
        assertEquals("SELECT 1", MainController.summarize("  SELECT 1  "));
    }
}
