package com.faro.app.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalDateTime;

import org.junit.jupiter.api.Test;

class CsvFileNamerTest {

    private static final LocalDateTime WHEN = LocalDateTime.of(2026, 8, 22, 14, 30, 12);

    @Test
    void buildsNameFromDatabaseTableAndWhereConditions() {
        String name = CsvFileNamer.suggest(
                "Bodega Norte", "SELECT * FROM productos WHERE id > 200 AND id < 300", WHEN);

        assertEquals("Bodega_Norte_productos_id_mayor_200_id_menor_300_20260822_143012", name);
    }

    @Test
    void fallsBackToDatabaseAndTimestampWhenNoFromClause() {
        String name = CsvFileNamer.suggest("crisol", "SHOW TABLES", WHEN);

        assertEquals("crisol_20260822_143012", name);
    }

    @Test
    void neverProducesAnEmptyName() {
        String name = CsvFileNamer.suggest("", "", WHEN);

        assertFalse(name.isEmpty());
        assertEquals("20260822_143012", name);
    }

    @Test
    void ignoresFromAndWhereKeywordsInsideCommentsAndStringLiterals() {
        String name = CsvFileNamer.suggest(
                "Bodega Norte",
                "SELECT * FROM productos WHERE nombre = 'WHERE fake' -- FROM comentario falso",
                WHEN);

        assertTrue(name.startsWith("Bodega_Norte_productos_nombre_igual_"));
        assertFalse(name.contains("fake"));
        assertFalse(name.contains("comentario"));
    }

    @Test
    void mapsAllSupportedOperatorsToSpanishWords() {
        String name = CsvFileNamer.suggest(
                "db", "SELECT * FROM t WHERE a >= 1 AND b <= 2 AND c <> 3", WHEN);

        assertTrue(name.contains("a_mayor_igual_1"));
        assertTrue(name.contains("b_menor_igual_2"));
        assertTrue(name.contains("c_distinto_3"));
    }
}
