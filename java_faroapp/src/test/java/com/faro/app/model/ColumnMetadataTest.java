package com.faro.app.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * {@link ColumnMetadata#typeString()} — cubre específicamente el bug real
 * encontrado por revisión de código (2026-08-25): {@code information_schema}
 * rellena precisión/escala para CUALQUIER tipo numérico, no solo
 * {@code numeric}/{@code decimal}, así que los casos de "entero con
 * precisión/escala poblada" (exactamente lo que {@code SchemaIntrospector
 * #fetchColumns} recibe de verdad para una columna {@code integer}/
 * {@code bigint}) son los que importan probar — no el caso ya obvio de
 * {@code numeric}.
 */
class ColumnMetadataTest {

    @Test
    void decimalTypeGetsPrecisionAndScaleSuffix() {
        ColumnMetadata col = new ColumnMetadata("precio", "numeric", true, false, null, 10, 2);

        assertEquals("numeric(10,2)", col.typeString());
    }

    @Test
    void integerWithPrecisionAndScalePopulatedDoesNotGetSuffix() {
        // Así llega de verdad information_schema para una columna "integer": precisión=32,
        // escala=0 — no es un caso de borde inventado, es el shape real de la fila.
        ColumnMetadata col = new ColumnMetadata("id", "integer", false, true, null, 32, 0);

        assertEquals("integer", col.typeString());
    }

    @Test
    void bigintWithPrecisionAndScalePopulatedDoesNotGetSuffix() {
        ColumnMetadata col = new ColumnMetadata("id", "bigint", false, true, null, 64, 0);

        assertEquals("bigint", col.typeString());
    }

    @Test
    void characterVaryingUsesCharacterMaxLength() {
        ColumnMetadata col = new ColumnMetadata("nombre", "character varying", false, false, 50, null, null);

        assertEquals("character varying(50)", col.typeString());
    }

    @Test
    void typeWithNoLengthOrPrecisionIsReturnedAsIs() {
        ColumnMetadata col = new ColumnMetadata("activo", "boolean", false, false, null, null, null);

        assertEquals("boolean", col.typeString());
    }

    /**
     * Bug real encontrado por revisión de código (2026-08-25): SQL Server
     * reporta {@code CHARACTER_MAXIMUM_LENGTH = -1} (no {@code NULL}) para
     * columnas {@code nvarchar(max)}/{@code varchar(max)}/etc. — sin este
     * caso especial, "Generar script CREATE" producía sintaxis inválida
     * como {@code descripcion nvarchar(-1) NOT NULL}.
     */
    @Test
    void characterMaxLengthMinusOneBecomesMax() {
        ColumnMetadata col = new ColumnMetadata("descripcion", "nvarchar", true, false, -1, null, null);

        assertEquals("nvarchar(MAX)", col.typeString());
    }
}
