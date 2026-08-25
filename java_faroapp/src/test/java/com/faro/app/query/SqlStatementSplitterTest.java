package com.faro.app.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

class SqlStatementSplitterTest {

    @Test
    void splitsTwoSimpleStatements() {
        List<String> statements = SqlStatementSplitter.split("SELECT 1; SELECT 2;");

        assertEquals(List.of("SELECT 1", "SELECT 2"), statements);
    }

    @Test
    void keepsLastStatementWithoutTrailingSemicolon() {
        List<String> statements = SqlStatementSplitter.split("SELECT 1;\nSELECT 2");

        assertEquals(List.of("SELECT 1", "SELECT 2"), statements);
    }

    @Test
    void ignoresSemicolonInsideSingleQuotedString() {
        List<String> statements = SqlStatementSplitter.split(
                "SELECT 'a; b' AS x; SELECT 2;");

        assertEquals(List.of("SELECT 'a; b' AS x", "SELECT 2"), statements);
    }

    @Test
    void ignoresSemicolonInsideDoubleQuotedIdentifier() {
        List<String> statements = SqlStatementSplitter.split(
                "SELECT \"col;name\" FROM t; SELECT 2;");

        assertEquals(List.of("SELECT \"col;name\" FROM t", "SELECT 2"), statements);
    }

    @Test
    void ignoresSemicolonInsideLineComment() {
        List<String> statements = SqlStatementSplitter.split(
                "SELECT 1; -- comentario con ; adentro\nSELECT 2;");

        assertEquals(List.of("SELECT 1", "-- comentario con ; adentro\nSELECT 2"), statements);
    }

    @Test
    void ignoresSemicolonInsideBlockComment() {
        List<String> statements = SqlStatementSplitter.split(
                "SELECT 1; /* bloque con ; adentro */ SELECT 2;");

        assertEquals(List.of("SELECT 1", "/* bloque con ; adentro */ SELECT 2"), statements);
    }

    @Test
    void ignoresSemicolonInsideDollarQuotedFunctionBody() {
        String script = "CREATE FUNCTION f() RETURNS int AS $$\n"
                + "BEGIN\n"
                + "  UPDATE t SET x = 1;\n"
                + "  RETURN 1;\n"
                + "END;\n"
                + "$$ LANGUAGE plpgsql;\n"
                + "SELECT f();";

        List<String> statements = SqlStatementSplitter.split(script);

        assertEquals(2, statements.size());
        assertTrue(statements.get(0).startsWith("CREATE FUNCTION"));
        assertTrue(statements.get(0).contains("UPDATE t SET x = 1;"));
        assertEquals("SELECT f()", statements.get(1));
    }

    @Test
    void ignoresSemicolonInsideTaggedDollarQuote() {
        String script = "CREATE FUNCTION f() RETURNS int AS $tag$ SELECT 1; $tag$ LANGUAGE sql; SELECT 2;";

        List<String> statements = SqlStatementSplitter.split(script);

        assertEquals(2, statements.size());
        assertTrue(statements.get(0).contains("$tag$ SELECT 1; $tag$"));
        assertEquals("SELECT 2", statements.get(1));
    }

    @Test
    void blankScriptProducesNoStatements() {
        assertTrue(SqlStatementSplitter.split("").isEmpty());
        assertTrue(SqlStatementSplitter.split("   \n  ").isEmpty());
    }

    @Test
    void skipsEmptyStatementsBetweenSemicolons() {
        List<String> statements = SqlStatementSplitter.split("SELECT 1;;;SELECT 2;");

        assertEquals(List.of("SELECT 1", "SELECT 2"), statements);
    }
}
