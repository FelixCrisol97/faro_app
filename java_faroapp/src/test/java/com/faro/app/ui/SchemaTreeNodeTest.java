package com.faro.app.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.faro.app.ui.SchemaTreeNode.Kind;

class SchemaTreeNodeTest {

    private static Map<Kind, List<String>> sampleInfo() {
        return Map.of(
                Kind.TABLES, List.of("productos", "existencias"),
                Kind.VIEWS, List.of("vista_ventas"),
                Kind.FUNCTIONS, List.of("fn_total"),
                Kind.PROCEDURES, List.of("sp_reindexar"),
                Kind.TRIGGERS, List.of("trg_auditoria"),
                Kind.TYPES, List.of("estado_pedido"));
    }

    @Test
    void filterSchemaWithEmptyFilterReturnsEverything() {
        Map<Kind, List<String>> result = SchemaTreeNode.filterSchema(sampleInfo(), "");

        assertEquals(List.of("productos", "existencias"), result.get(Kind.TABLES));
        assertEquals(List.of("vista_ventas"), result.get(Kind.VIEWS));
        assertEquals(List.of("fn_total"), result.get(Kind.FUNCTIONS));
        assertEquals(List.of("sp_reindexar"), result.get(Kind.PROCEDURES));
        assertEquals(List.of("trg_auditoria"), result.get(Kind.TRIGGERS));
        assertEquals(List.of("estado_pedido"), result.get(Kind.TYPES));
    }

    @Test
    void filterSchemaMatchesOneTableButNotOtherCategories() {
        Map<Kind, List<String>> result = SchemaTreeNode.filterSchema(sampleInfo(), "produc");

        assertEquals(List.of("productos"), result.get(Kind.TABLES));
        assertTrue(result.get(Kind.VIEWS).isEmpty());
        assertTrue(result.get(Kind.FUNCTIONS).isEmpty());
        assertTrue(result.get(Kind.PROCEDURES).isEmpty());
        assertTrue(result.get(Kind.TRIGGERS).isEmpty());
        assertTrue(result.get(Kind.TYPES).isEmpty());
    }

    @Test
    void filterSchemaIsCaseInsensitive() {
        Map<Kind, List<String>> result = SchemaTreeNode.filterSchema(sampleInfo(), "TRG_AUD");

        assertEquals(List.of("trg_auditoria"), result.get(Kind.TRIGGERS));
    }

    @Test
    void filterSchemaWithNoMatchReturnsAllCategoriesEmpty() {
        Map<Kind, List<String>> result = SchemaTreeNode.filterSchema(sampleInfo(), "zzz_no_existe");

        for (Kind kind : Kind.values()) {
            assertTrue(result.get(kind).isEmpty(), "Categoría " + kind + " debería quedar vacía");
        }
    }

    @Test
    void matchesAnyNameTrueWhenAtLeastOneCategoryHasAMatch() {
        assertTrue(SchemaTreeNode.matchesAnyName(sampleInfo(), "sp_reindexar"));
        assertTrue(SchemaTreeNode.matchesAnyName(sampleInfo(), "estado_pedido"));
        assertFalse(SchemaTreeNode.matchesAnyName(sampleInfo(), "zzz_no_existe"));
    }

    // ---- Cortocircuito de matchesAnyName (2026-09-08, hallazgo B2 de
    // ANALISIS_OPTIMIZACION_ESTRUCTURA.md) ----
    //
    // El método pasó de "armar el mapa filtrado completo y después preguntar si algo
    // quedó" a "salir en la primera coincidencia". Estos tests fijan que la SEMÁNTICA
    // no cambió — que es lo único que hace segura esa reescritura.

    private static Map<Kind, List<String>> emptyInfo() {
        return Map.of(
                Kind.TABLES, List.of(),
                Kind.VIEWS, List.of(),
                Kind.FUNCTIONS, List.of(),
                Kind.PROCEDURES, List.of(),
                Kind.TRIGGERS, List.of(),
                Kind.TYPES, List.of());
    }

    /**
     * El caso que más fácil se rompía al reescribir: con filtro vacío la respuesta NO
     * es "el mapa tiene categorías" sino "alguna categoría tiene al menos un nombre".
     * Un mapa con las 6 categorías presentes pero vacías (una base expandida que
     * todavía no cargó nada) tiene que dar {@code false}, igual que antes.
     */
    @Test
    void matchesAnyNameWithEmptyFilterIsFalseWhenEveryCategoryIsEmpty() {
        assertFalse(SchemaTreeNode.matchesAnyName(emptyInfo(), ""));
        assertFalse(SchemaTreeNode.matchesAnyName(emptyInfo(), "   "));
        assertFalse(SchemaTreeNode.matchesAnyName(emptyInfo(), null));
        assertFalse(SchemaTreeNode.matchesAnyName(Map.of(), ""));
    }

    @Test
    void matchesAnyNameWithEmptyFilterIsTrueWhenSomeCategoryHasNames() {
        assertTrue(SchemaTreeNode.matchesAnyName(sampleInfo(), ""));
        assertTrue(SchemaTreeNode.matchesAnyName(Map.of(Kind.TABLES, List.of("productos")), ""));
    }

    @Test
    void matchesAnyNameIsCaseInsensitiveAndMatchesInTheMiddle() {
        assertTrue(SchemaTreeNode.matchesAnyName(sampleInfo(), "AUDITORIA"));
        assertTrue(SchemaTreeNode.matchesAnyName(sampleInfo(), "Ventas"));
        assertTrue(SchemaTreeNode.matchesAnyName(sampleInfo(), "tenc"));
    }

    /** Se recorta igual que antes — el buscador del árbol manda el texto crudo del campo. */
    @Test
    void matchesAnyNameTrimsTheFilter() {
        assertTrue(SchemaTreeNode.matchesAnyName(sampleInfo(), "  produc  "));
    }

    @Test
    void containsIgnoreCaseMatchesAnywhereWithoutCopying() {
        assertTrue(SchemaTreeNode.containsIgnoreCase("productos", "produc"));
        assertTrue(SchemaTreeNode.containsIgnoreCase("productos", "DUCT"));
        assertTrue(SchemaTreeNode.containsIgnoreCase("productos", "productos"));
        assertTrue(SchemaTreeNode.containsIgnoreCase("productos", ""));
        assertFalse(SchemaTreeNode.containsIgnoreCase("productos", "zzz"));
        // Aguja más larga que el texto — sin esto sería un IndexOutOfBounds, no un false.
        assertFalse(SchemaTreeNode.containsIgnoreCase("ab", "abcdef"));
        assertFalse(SchemaTreeNode.containsIgnoreCase("", "a"));
    }
}
