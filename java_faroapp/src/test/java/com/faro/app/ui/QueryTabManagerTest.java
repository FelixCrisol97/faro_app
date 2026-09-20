package com.faro.app.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.faro.app.model.DatabaseEntry;
import com.faro.app.model.DbEngine;
import com.faro.app.model.ServerMode;

/**
 * La lógica pura de {@link QueryTabManager} (2026-09-18).
 *
 * <p>Casi toda esa clase es UI de verdad —crea {@code Tab}, {@code CodeArea}, diálogos—
 * y la suite permanente no arranca JavaFX a propósito. Lo que sí se puede ejercitar sin
 * toolkit son estos tres métodos estáticos, y los tres importan:
 *
 * <ul>
 *   <li>{@code indexOfIgnoreCase} es el corazón de "Buscar en el script". Sus tests
 *       vivían en {@code MainControllerLogicTest} y se mudaron con él.</li>
 *   <li>{@code describeSelection} y {@code describeSelectionByIds} producen la segunda
 *       línea del encabezado de cada pestaña — la función que el usuario pidió el
 *       2026-09-11 porque la pestaña no decía contra qué base iba a correr. <b>No tenían
 *       ni un test</b>: estaban en métodos de instancia privados del controlador, y
 *       moverlos a estáticos de esta clase es lo que permitió escribirlos.</li>
 * </ul>
 */
class QueryTabManagerTest {

    private static DatabaseEntry db(String alias) {
        return new DatabaseEntry(alias, "10.0.0.1", 5432, alias, DbEngine.POSTGRES, ServerMode.READ_ONLY);
    }

    // ------------------------------------------------------------------
    // indexOfIgnoreCase — mudados desde MainControllerLogicTest
    // ------------------------------------------------------------------
    //
    // Reemplazó a getText().toLowerCase() en "Buscar en el script" (hallazgo #9): compara
    // en el lugar en vez de copiar el documento dos veces por cada F3.

    @Test
    void buscaHaciaAdelanteSinDistinguirMayusculas() {
        assertEquals(0, QueryTabManager.indexOfIgnoreCase("SELECT * FROM t", "select", 0, true));
        assertEquals(9, QueryTabManager.indexOfIgnoreCase("SELECT * FROM t", "from", 0, true));
        assertEquals(-1, QueryTabManager.indexOfIgnoreCase("SELECT * FROM t", "where", 0, true));
    }

    /** Desde una posición dada encuentra la SIGUIENTE, no la primera — es lo que hace funcionar F3 repetido. */
    @Test
    void buscaDesdeLaPosicionDada() {
        String texto = "select a, select b";

        assertEquals(0, QueryTabManager.indexOfIgnoreCase(texto, "select", 0, true));
        assertEquals(10, QueryTabManager.indexOfIgnoreCase(texto, "select", 1, true));
    }

    @Test
    void buscaHaciaAtras() {
        String texto = "select a, select b";

        assertEquals(10, QueryTabManager.indexOfIgnoreCase(texto, "select", 17, false));
        assertEquals(0, QueryTabManager.indexOfIgnoreCase(texto, "select", 9, false));
    }

    /**
     * Los bordes que rompen una búsqueda escrita a mano: aguja más larga que el texto,
     * aguja vacía, y un {@code from} fuera de rango (pasa al dar la vuelta circular
     * desde el final del documento).
     */
    @Test
    void casosDeBorde() {
        assertEquals(-1, QueryTabManager.indexOfIgnoreCase("ab", "abcdef", 0, true));
        assertEquals(-1, QueryTabManager.indexOfIgnoreCase("", "a", 0, true));
        assertEquals(-1, QueryTabManager.indexOfIgnoreCase("abc", "", 0, true));
        assertEquals(-1, QueryTabManager.indexOfIgnoreCase("abc", "z", 99, true));
        assertEquals(0, QueryTabManager.indexOfIgnoreCase("abc", "a", 99, false));
    }

    // ------------------------------------------------------------------
    // Segunda línea del encabezado — pestaña activa (desde el árbol)
    // ------------------------------------------------------------------

    /** El caso que motivó la función: antes esto solo se descubría al presionar "Ejecutar". */
    @Test
    void sinNingunaBaseLoDiceExplicitamente() {
        assertEquals("sin base seleccionada", QueryTabManager.describeSelection(List.of()));
    }

    @Test
    void conUnaSolaBaseMuestraSuAlias() {
        assertEquals("bodegamuebles.30001", QueryTabManager.describeSelection(List.of(db("bodegamuebles.30001"))));
    }

    /**
     * Con varias muestra el conteo y no los nombres: los alias reales del usuario son
     * largos, y listarlos alargaría la pestaña hasta necesitar las flechas de la barra.
     */
    @Test
    void conVariasMuestraElConteo() {
        assertEquals("3 bases", QueryTabManager.describeSelection(List.of(db("a"), db("b"), db("c"))));
    }

    // ------------------------------------------------------------------
    // Segunda línea del encabezado — pestaña NO activa (desde ids guardados)
    // ------------------------------------------------------------------

    @Test
    void desdeIdsGuardadosTraduceElIdASuAlias() {
        DatabaseEntry norte = db("bodega-norte");
        DatabaseEntry sur = db("bodega-sur");

        assertEquals("bodega-sur",
                QueryTabManager.describeSelectionByIds(Set.of(sur.id()), List.of(norte, sur)));
    }

    @Test
    void desdeIdsGuardadosSinNingunoLoDiceExplicitamente() {
        assertEquals("sin base seleccionada",
                QueryTabManager.describeSelectionByIds(Set.of(), List.of(db("a"))));
    }

    @Test
    void desdeIdsGuardadosConVariosMuestraElConteo() {
        DatabaseEntry a = db("a");
        DatabaseEntry b = db("b");

        assertEquals("2 bases", QueryTabManager.describeSelectionByIds(Set.of(a.id(), b.id()), List.of(a, b)));
    }

    /**
     * La pestaña guardó una base que después se borró. No se inventa un nombre ni se deja
     * la línea en blanco — una línea vacía se leería como "no hay nada que avisar".
     */
    @Test
    void unaBaseQueYaNoExisteNoDejaLaLineaEnBlanco() {
        DatabaseEntry borrada = db("borrada");

        assertEquals("base no encontrada",
                QueryTabManager.describeSelectionByIds(Set.of(borrada.id()), List.of(db("otra"))));
    }
}
