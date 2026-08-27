package com.faro.app.model;

import java.util.Locale;
import java.util.Set;

/**
 * Una columna real de tabla/vista — nombre, tipo, nulabilidad y si es parte
 * de la llave primaria — se pide bajo demanda por tabla, nunca de todas las
 * tablas de una base de un jalón (esquema progresivo, 2026-08-25). Lo
 * necesitan las 5 acciones de tabla del explorador de esquema ("Generar
 * SELECT/INSERT/UPDATE/DELETE/script CREATE"); UPDATE/DELETE/CREATE TABLE
 * además necesitan el tipo/la PK para armar un SET/WHERE/definición de
 * columna reales. Ver {@code SchemaIntrospector#fetchColumns}.
 */
public record ColumnMetadata(
        String name,
        String dataType,
        boolean nullable,
        boolean isPrimaryKey,
        Integer characterMaxLength,
        Integer numericPrecision,
        Integer numericScale) {

    /**
     * {@code numeric}/{@code decimal} — los únicos tipos donde
     * {@code (precisión,escala)} es sintaxis válida en {@code CREATE TABLE}.
     * Bug real encontrado por revisión de código (2026-08-25): tanto
     * {@code information_schema.columns} de PostgreSQL como
     * {@code INFORMATION_SCHEMA.COLUMNS} de SQL Server rellenan
     * {@code numeric_precision}/{@code numeric_scale} para CUALQUIER tipo
     * numérico — {@code integer} reporta precisión 32/escala 0,
     * {@code bigint} 64/0, etc., no solo {@code numeric}/{@code decimal} —
     * así que sin este filtro, "Generar script CREATE" producía
     * {@code id integer(32,0)} (sintaxis inválida) en casi cualquier tabla
     * con una PK entera. Mismo criterio que ya usaba correctamente
     * {@code SchemaIntrospector#sqlServerTypeWithLength}.
     */
    private static final Set<String> DECIMAL_TYPES = Set.of("numeric", "decimal");

    /**
     * Tipo con longitud/precisión cuando aplica, ej.
     * {@code varchar(50)}/{@code numeric(10,2)} — para "Generar script
     * CREATE" (tabla).
     *
     * <p><b>Bug real encontrado por revisión de código (2026-08-25):</b> en
     * SQL Server, {@code INFORMATION_SCHEMA.COLUMNS.CHARACTER_MAXIMUM_LENGTH}
     * reporta {@code -1} (no {@code NULL}) para columnas
     * {@code nvarchar(max)}/{@code varchar(max)}/{@code nchar(max)}/
     * {@code varbinary(max)} — sin este caso especial, esto producía
     * {@code descripcion nvarchar(-1) NOT NULL} (sintaxis inválida) en
     * cualquier tabla con una columna {@code MAX}. Mismo sentinela que ya
     * manejaba correctamente {@code SchemaIntrospector#sqlServerTypeWithLength}
     * para "Generar CREATE TYPE"; a esta ruta (tablas) nunca se le había
     * portado el mismo caso especial.
     */
    public String typeString() {
        if (characterMaxLength != null) {
            return dataType + "(" + (characterMaxLength == -1 ? "MAX" : characterMaxLength) + ")";
        }
        if (numericPrecision != null && numericScale != null && DECIMAL_TYPES.contains(dataType.toLowerCase(Locale.ROOT))) {
            return dataType + "(" + numericPrecision + "," + numericScale + ")";
        }
        return dataType;
    }
}
