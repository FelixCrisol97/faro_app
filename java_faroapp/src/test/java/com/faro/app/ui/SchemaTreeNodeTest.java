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
}
