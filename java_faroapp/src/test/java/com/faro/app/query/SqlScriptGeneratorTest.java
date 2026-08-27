package com.faro.app.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.faro.app.model.ColumnMetadata;

class SqlScriptGeneratorTest {

    /**
     * Precisión/escala 32/0 a propósito, no {@code null} — así llega de
     * verdad {@code information_schema} para una columna {@code integer}
     * (ver {@code ColumnMetadataTest}, que encontró el bug real de esto).
     * Con {@code null} el test nunca ejercitaba la rama que decide si el
     * tipo lleva sufijo {@code (p,s)} o no.
     */
    private static ColumnMetadata pk(String name, String type) {
        return new ColumnMetadata(name, type, false, true, null, 32, 0);
    }

    private static ColumnMetadata col(String name, String type, boolean nullable) {
        return new ColumnMetadata(name, type, nullable, false, null, null, null);
    }

    @Test
    void generateInsertScriptListsColumnsAndPlaceholders() {
        String sql = SqlScriptGenerator.generateInsertScript("productos", List.of("id", "nombre"));

        assertEquals("INSERT INTO productos (id, nombre)\nVALUES (<id>, <nombre>);", sql);
    }

    @Test
    void generateUpdateScriptPutsPrimaryKeyInWhereAndRestInSet() {
        List<ColumnMetadata> columns = List.of(pk("id", "integer"), col("nombre", "varchar", true));

        String sql = SqlScriptGenerator.generateUpdateScript("productos", columns);

        assertTrue(sql.contains("SET\n    nombre = <nombre>"));
        assertTrue(sql.contains("WHERE\n    id = <id>;"));
        assertTrue(sql.startsWith("UPDATE productos"));
    }

    @Test
    void generateUpdateScriptWithoutPrimaryKeyLeavesTodoComment() {
        List<ColumnMetadata> columns = List.of(col("nombre", "varchar", true));

        String sql = SqlScriptGenerator.generateUpdateScript("productos", columns);

        assertTrue(sql.contains("-- TODO: agrega condición WHERE (no se encontró llave primaria)"));
    }

    @Test
    void generateDeleteScriptFiltersByPrimaryKey() {
        List<ColumnMetadata> columns = List.of(pk("id", "integer"), col("nombre", "varchar", true));

        String sql = SqlScriptGenerator.generateDeleteScript("productos", columns);

        assertEquals("DELETE FROM productos\nWHERE\n    id = <id>;", sql);
    }

    @Test
    void generateDeleteScriptWithoutPrimaryKeyLeavesTodoCommentInsteadOfUnconditionalDelete() {
        List<ColumnMetadata> columns = List.of(col("nombre", "varchar", true));

        String sql = SqlScriptGenerator.generateDeleteScript("productos", columns);

        assertTrue(sql.contains("-- TODO"));
        assertTrue(sql.contains("DELETE FROM productos"));
    }

    @Test
    void generateCreateTableScriptIncludesTypeNotNullAndPrimaryKey() {
        ColumnMetadata id = pk("id", "integer");
        ColumnMetadata nombre = new ColumnMetadata("nombre", "varchar", false, false, 50, null, null);
        ColumnMetadata precio = new ColumnMetadata("precio", "numeric", true, false, null, 10, 2);

        String sql = SqlScriptGenerator.generateCreateTableScript("productos", List.of(id, nombre, precio));

        assertTrue(sql.contains("id integer NOT NULL"));
        assertTrue(sql.contains("nombre varchar(50) NOT NULL"));
        assertTrue(sql.contains("precio numeric(10,2)"));
        assertTrue(sql.contains("PRIMARY KEY (id)"));
        assertTrue(sql.startsWith("CREATE TABLE productos ("));
    }

    /**
     * {@link SqlScriptGenerator#columnDefinitionLine} — hallazgo real de
     * revisión de código (2026-08-26): este patrón exacto se armaba por
     * separado en 3 sitios ({@link #generateCreateTableScriptIncludesTypeNotNullAndPrimaryKey}
     * arriba ya cubre el uso real dentro de esta clase; estos 2 casos
     * prueban el helper compartido directo, incluyendo el overload de 2
     * argumentos que usa {@code SchemaIntrospector#fetchPostgresCompositeDefinition}
     * — un tipo compuesto de Postgres no admite NOT NULL por columna en
     * absoluto, por eso ese overload no lo ofrece siquiera).
     */
    @Test
    void columnDefinitionLineTwoArgOverloadNeverAddsNotNull() {
        assertEquals("    id integer", SqlScriptGenerator.columnDefinitionLine("id", "integer"));
    }

    @Test
    void columnDefinitionLineThreeArgOverloadAddsNotNullOnlyWhenRequested() {
        assertEquals("    id integer NOT NULL", SqlScriptGenerator.columnDefinitionLine("id", "integer", true));
        assertEquals("    id integer", SqlScriptGenerator.columnDefinitionLine("id", "integer", false));
    }
}
