package com.faro.app;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Lógica pura de {@link MainController} — sin JavaFX de por medio (2026-09-14, cierra
 * el hallazgo C8 de {@code ANALISIS_OPTIMIZACION_ESTRUCTURA.md}).
 *
 * <p>Eran tres métodos; queda uno ({@code summarize}) —
 * {@code indexOfIgnoreCase} se mudó a {@code QueryTabManager} con el resto de la
 * búsqueda (2026-09-18), y {@code appendCsvEscaped} a {@code CsvWriter} (2026-09-20)
 * al aparecer un segundo exportador; sus tests se mudaron con ellos.
 *
 * <p>Son estáticos y no tocan ningún nodo: se podían testear desde
 * siempre, simplemente nadie lo había hecho. Son package-private a propósito para
 * poder ejercitarlos directo, mismo criterio que
 * {@code SchemaIntrospector#sqlServerTypeWithLength} y
 * {@code QueryExecutionService#isReadOnlyStatement}.
 *
 * <p>La clase NO se instancia — {@code MainController} necesita un FXML cargado y
 * el toolkit arrancado, y la suite permanente no arranca JavaFX a propósito.
 */
class MainControllerLogicTest {

    // indexOfIgnoreCase se mudó a QueryTabManager el 2026-09-18 (paso 3 de C1), y sus
    // tests con él — ver QueryTabManagerTest.

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
