package com.faro.app.query;

import java.util.List;
import java.util.stream.Collectors;

import com.faro.app.model.ColumnMetadata;

/**
 * Generadores puros de texto SQL para el menú "Generar…" del árbol de
 * esquema (clic derecho sobre una tabla/vista/función/procedimiento/
 * trigger) — sin JDBC/JavaFX, solo ensamblado de string a partir de datos ya
 * conocidos ({@link ColumnMetadata} o el texto de definición ya traído por
 * {@code SchemaIntrospector}). Mismo criterio de "lógica pura, testeable
 * sin una base real" que {@code SqlStatementSplitter}/{@code CsvFileNamer}.
 *
 * <p>Ningún script lleva el esquema como prefijo ({@code FROM tabla}, no
 * {@code FROM dbo.tabla}) — mismo criterio que ya usaba "Generar SELECT"
 * desde antes de esta clase: la base ya conectada implica su propio
 * esquema por defecto.
 */
public final class SqlScriptGenerator {

    private SqlScriptGenerator() {
    }

    /** Columnas conocidas (nombres, sin tipo) alcanzan para INSERT — a diferencia de UPDATE/DELETE/CREATE TABLE, no necesita saber cuál es la PK. */
    public static String generateInsertScript(String table, List<String> columnNames) {
        String columnList = String.join(", ", columnNames);
        String placeholders = columnNames.stream().map(c -> "<" + c + ">").collect(Collectors.joining(", "));
        return "INSERT INTO " + table + " (" + columnList + ")\nVALUES (" + placeholders + ");";
    }

    /** Columnas que NO son PK van en SET, las que sí en WHERE (unidas con AND). Sin llave primaria no hay nada sensato para filtrar — WHERE queda como comentario TODO en vez de adivinar. */
    public static String generateUpdateScript(String table, List<ColumnMetadata> columns) {
        List<ColumnMetadata> pk = columns.stream().filter(ColumnMetadata::isPrimaryKey).toList();
        List<ColumnMetadata> setColumns = columns.stream().filter(c -> !c.isPrimaryKey()).toList();
        String setClause = setColumns.isEmpty()
                ? "    -- (sin columnas que actualizar)"
                : setColumns.stream().map(c -> "    " + c.name() + " = <" + c.name() + ">").collect(Collectors.joining(",\n"));
        String whereClause = whereClauseFromPrimaryKey(pk);
        return "UPDATE " + table + "\nSET\n" + setClause + "\nWHERE\n" + whereClause + ";";
    }

    /** Mismo criterio que UPDATE: sin PK conocida, WHERE queda como TODO — nunca se genera un DELETE sin condición que alguien pudiera correr sin querer. */
    public static String generateDeleteScript(String table, List<ColumnMetadata> columns) {
        List<ColumnMetadata> pk = columns.stream().filter(ColumnMetadata::isPrimaryKey).toList();
        return "DELETE FROM " + table + "\nWHERE\n" + whereClauseFromPrimaryKey(pk) + ";";
    }

    private static String whereClauseFromPrimaryKey(List<ColumnMetadata> pk) {
        return pk.isEmpty()
                ? "    -- TODO: agrega condición WHERE (no se encontró llave primaria)"
                : "    " + pk.stream().map(c -> c.name() + " = <" + c.name() + ">").collect(Collectors.joining(" AND "));
    }

    /**
     * Mejor esfuerzo desde metadatos de columna — nombre, tipo (con
     * longitud/precisión), NOT NULL, y PRIMARY KEY si aplica. NO reconstruye
     * llaves foráneas, índices, checks, ni defaults no triviales — ningún
     * motor expone un equivalente simple de "dame el DDL completo" para
     * tablas como sí tienen para rutinas ({@code pg_get_functiondef}/
     * {@code OBJECT_DEFINITION}) — pensado como punto de partida real, no
     * una exportación fiel.
     */
    public static String generateCreateTableScript(String table, List<ColumnMetadata> columns) {
        List<String> lines = columns.stream()
                .map(c -> columnDefinitionLine(c.name(), c.typeString(), !c.nullable()))
                .collect(Collectors.toCollection(java.util.ArrayList::new));
        List<String> pkColumns = columns.stream().filter(ColumnMetadata::isPrimaryKey).map(ColumnMetadata::name).toList();
        if (!pkColumns.isEmpty()) {
            lines.add("    PRIMARY KEY (" + String.join(", ", pkColumns) + ")");
        }
        return "CREATE TABLE " + table + " (\n" + String.join(",\n", lines) + "\n);";
    }

    /**
     * Una línea de definición de columna dentro de un bloque {@code (...)} de
     * CREATE — {@code "    nombre tipo"}, sin NOT NULL. Compartida con
     * {@code SchemaIntrospector#fetchPostgresCompositeDefinition} (un tipo
     * compuesto de Postgres no admite NOT NULL por columna en absoluto —
     * {@code CREATE TYPE ... AS (...)} no tiene esa sintaxis, a diferencia de
     * una tabla o un tipo de tabla de SQL Server; no es un descuido que
     * nunca se le haya pedido nulabilidad a esa ruta).
     */
    public static String columnDefinitionLine(String name, String typeString) {
        return "    " + name + " " + typeString;
    }

    /**
     * Igual que {@link #columnDefinitionLine(String, String)}, con
     * {@code NOT NULL} cuando aplica — hallazgo real de revisión de código
     * (2026-08-26): este patrón exacto ("nombre tipo[ NOT NULL]") se armaba
     * por separado en 3 sitios ({@link #generateCreateTableScript} acá, y
     * {@code SchemaIntrospector#fetchPostgresCompositeDefinition}/
     * {@code #fetchSqlServerTableTypeDefinition}, aunque el primero de esos
     * dos nunca reconstruía NOT NULL — ver el overload de arriba). Ahora un
     * solo lugar decide el formato.
     */
    public static String columnDefinitionLine(String name, String typeString, boolean notNull) {
        return columnDefinitionLine(name, typeString) + (notNull ? " NOT NULL" : "");
    }
}
