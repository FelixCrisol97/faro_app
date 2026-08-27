package com.faro.app.query;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

/**
 * {@code sys.types}/{@code sys.columns} de SQL Server no arman una longitud
 * legible sola como {@code format_type} hace en PostgreSQL — este helper
 * respalda "Generar script CREATE" de un tipo alias/de tabla
 * ({@link SchemaIntrospector#fetchSqlServerAliasTypeDefinition}/
 * {@code #fetchSqlServerTableTypeDefinition}, ver el javadoc real del
 * método bajo prueba para el porqué de la división entre 2).
 */
class SchemaIntrospectorTest {

    @Test
    void decimalUsesPrecisionAndScale() {
        assertEquals("decimal(10,2)", SchemaIntrospector.sqlServerTypeWithLength("decimal", 9, 10, 2));
    }

    @Test
    void nvarcharDividesByteLengthByTwoForCharacterCount() {
        assertEquals("nvarchar(50)", SchemaIntrospector.sqlServerTypeWithLength("nvarchar", 100, 0, 0));
    }

    @Test
    void nvarcharMaxLengthMinusOneBecomesMax() {
        assertEquals("nvarchar(MAX)", SchemaIntrospector.sqlServerTypeWithLength("nvarchar", -1, 0, 0));
    }

    @Test
    void varcharUsesByteLengthDirectly() {
        assertEquals("varchar(50)", SchemaIntrospector.sqlServerTypeWithLength("varchar", 50, 0, 0));
    }

    @Test
    void varcharMaxLengthMinusOneBecomesMax() {
        assertEquals("varchar(MAX)", SchemaIntrospector.sqlServerTypeWithLength("varchar", -1, 0, 0));
    }

    @Test
    void typeWithoutLengthIsReturnedAsIs() {
        assertEquals("int", SchemaIntrospector.sqlServerTypeWithLength("int", 4, 0, 0));
    }

    /**
     * {@link SchemaIntrospector#disambiguateByTable}/{@code #bareTriggerName}
     * — cubren el bug real encontrado por revisión de código (2026-08-25):
     * dos tablas del mismo esquema PostgreSQL con un trigger del mismo
     * nombre se colapsaban en uno solo (el segundo pisaba al primero en un
     * {@code Map} keyeado solo por nombre), perdiendo uno de los dos triggers
     * sin ningún aviso.
     */
    @Test
    void disambiguateByTableLeavesUniqueNamesUnqualified() {
        LinkedHashMap<String, String> result = SchemaIntrospector.disambiguateByTable(
                List.<String[]>of(new String[] {"trg_auditoria", "productos"}));

        assertEquals("productos", result.get("trg_auditoria"));
    }

    @Test
    void disambiguateByTableQualifiesCollidingNamesWithTheirTable() {
        LinkedHashMap<String, String> result = SchemaIntrospector.disambiguateByTable(List.of(
                new String[] {"trg_audit", "productos"},
                new String[] {"trg_audit", "clientes"}));

        assertEquals(2, result.size(), "las 2 triggers deben quedar visibles, no colapsadas en una");
        assertEquals("productos", result.get("productos.trg_audit"));
        assertEquals("clientes", result.get("clientes.trg_audit"));
    }

    @Test
    void bareTriggerNameStripsKnownTablePrefix() {
        assertEquals("trg_audit", SchemaIntrospector.bareTriggerName("productos.trg_audit", "productos"));
    }

    @Test
    void bareTriggerNameLeavesUnqualifiedNameUnchanged() {
        assertEquals("trg_auditoria", SchemaIntrospector.bareTriggerName("trg_auditoria", "productos"));
    }

    /**
     * {@link SchemaIntrospector#disambiguateBySignature}/{@code #bareRoutineName}/
     * {@code #routineSignature} — cierran el límite conocido de sobrecarga
     * real en PostgreSQL (mismo nombre de función/procedimiento, distintos
     * tipos de parámetro), corregido 2026-08-26 con el mismo criterio que
     * ya se usó para triggers duplicados (arriba).
     */
    @Test
    void disambiguateBySignatureLeavesUniqueNamesUnqualified() {
        LinkedHashMap<String, String> result = SchemaIntrospector.disambiguateBySignature(
                List.<String[]>of(new String[] {"fn_total", "integer"}));

        assertEquals("integer", result.get("fn_total"));
    }

    @Test
    void disambiguateBySignatureQualifiesOverloadedNamesWithTheirSignature() {
        LinkedHashMap<String, String> result = SchemaIntrospector.disambiguateBySignature(List.of(
                new String[] {"calcular", "integer"},
                new String[] {"calcular", "text"}));

        assertEquals(2, result.size(), "las 2 sobrecargas deben quedar visibles, no colapsadas en una");
        assertEquals("integer", result.get("calcular(integer)"));
        assertEquals("text", result.get("calcular(text)"));
    }

    @Test
    void bareRoutineNameStripsSignatureSuffix() {
        assertEquals("calcular", SchemaIntrospector.bareRoutineName("calcular(integer, text)"));
    }

    @Test
    void bareRoutineNameLeavesUnqualifiedNameUnchanged() {
        assertEquals("fn_total", SchemaIntrospector.bareRoutineName("fn_total"));
    }

    @Test
    void routineSignatureExtractsArgsFromQualifiedName() {
        assertEquals(Optional.of("integer, text"), SchemaIntrospector.routineSignature("calcular(integer, text)"));
    }

    @Test
    void routineSignatureEmptyForUnqualifiedName() {
        assertEquals(Optional.empty(), SchemaIntrospector.routineSignature("fn_total"));
    }

    /**
     * {@link SchemaIntrospector#invalidate}/{@code #testGeneration} — cierra
     * el límite conocido de condición de carrera (2026-08-26): cada
     * "Recargar esquema" sube la generación de esa base, para que un fetch
     * ya en curso pueda darse cuenta (al comparar contra la generación
     * capturada antes de arrancar) de que ya no debe escribir su resultado
     * en la caché compartida. IDs con {@link UUID#randomUUID()} — evita que
     * los 2 tests colisionen entre sí o con cualquier otro test que use
     * este mismo mapa estático dentro de la misma JVM.
     */
    @Test
    void invalidateIncrementsGenerationPerDatabase() {
        String dbId = "test-db-" + UUID.randomUUID();

        assertEquals(0L, SchemaIntrospector.testGeneration(dbId));
        SchemaIntrospector.invalidate(dbId);
        assertEquals(1L, SchemaIntrospector.testGeneration(dbId));
        SchemaIntrospector.invalidate(dbId);
        assertEquals(2L, SchemaIntrospector.testGeneration(dbId));
    }

    @Test
    void invalidateGenerationIsIndependentPerDatabase() {
        String dbA = "test-db-a-" + UUID.randomUUID();
        String dbB = "test-db-b-" + UUID.randomUUID();

        SchemaIntrospector.invalidate(dbA);

        assertEquals(1L, SchemaIntrospector.testGeneration(dbA));
        assertEquals(0L, SchemaIntrospector.testGeneration(dbB));
        assertTrue(SchemaIntrospector.testGeneration(dbA) != SchemaIntrospector.testGeneration(dbB));
    }
}
