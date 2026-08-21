package com.faro.app.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class SqlFormatterTest {

    @Test
    void uppercasesKeywordsAndBreaksClauses() {
        String result = SqlFormatter.format(
                "select id, nombre from ventas where fecha >= '2026-01-01' order by total desc");

        assertEquals(
                "SELECT id, nombre\nFROM ventas\nWHERE fecha >= '2026-01-01'\nORDER BY total DESC",
                result);
    }

    @Test
    void keepsQualifiedJoinTogether() {
        String result = SqlFormatter.format("select * from a left join b on b.a_id = a.id");

        assertTrue(result.contains("\nLEFT JOIN b ON"), result);
    }

    @Test
    void neverTouchesTextInsideStringLiteral() {
        // "FROM"/"WHERE" adentro del literal no deben tocarse — corromper
        // datos reales de la consulta sería peor que no formatear nada.
        String result = SqlFormatter.format("select * from t where note = 'it''s a FROM trick'");

        assertTrue(result.contains("'it''s a FROM trick'"), result);
    }

    @Test
    void neverTouchesTextInsideComments() {
        String result = SqlFormatter.format(
                "select * from t where 1=1 -- select trick\n/* another select trick */");

        assertTrue(result.contains("-- select trick"), result);
        assertTrue(result.contains("/* another select trick */"), result);
    }

    @Test
    void formatsInsertValues() {
        String result = SqlFormatter.format("insert into t (a, b) values (1, 2)");

        assertEquals("INSERT INTO t (a, b)\nVALUES (1, 2)", result);
    }
}
