package com.faro.app.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.faro.app.query.SchemaIntrospector.SchemaInfo;
import com.faro.app.ui.SchemaTreeNode.Kind;

class SchemaTreeNodeTest {

    private static SchemaInfo sampleInfo() {
        return new SchemaInfo(
                List.of("productos", "existencias"),
                List.of("vista_ventas"),
                List.of("fn_total"),
                List.of("sp_reindexar"),
                List.of("trg_auditoria"),
                Map.of("productos", List.of("id", "nombre"), "existencias", List.of("id", "cantidad"),
                        "vista_ventas", List.of("total")));
    }

    @Test
    void filterSchemaWithEmptyFilterReturnsEverything() {
        Map<Kind, List<String>> result = SchemaTreeNode.filterSchema(sampleInfo(), "");

        assertEquals(List.of("productos", "existencias"), result.get(Kind.TABLES));
        assertEquals(List.of("vista_ventas"), result.get(Kind.VIEWS));
        assertEquals(List.of("fn_total"), result.get(Kind.FUNCTIONS));
        assertEquals(List.of("sp_reindexar"), result.get(Kind.PROCEDURES));
        assertEquals(List.of("trg_auditoria"), result.get(Kind.TRIGGERS));
    }

    @Test
    void filterSchemaMatchesOneTableButNotOtherCategories() {
        Map<Kind, List<String>> result = SchemaTreeNode.filterSchema(sampleInfo(), "produc");

        assertEquals(List.of("productos"), result.get(Kind.TABLES));
        assertTrue(result.get(Kind.VIEWS).isEmpty());
        assertTrue(result.get(Kind.FUNCTIONS).isEmpty());
        assertTrue(result.get(Kind.PROCEDURES).isEmpty());
        assertTrue(result.get(Kind.TRIGGERS).isEmpty());
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
        assertFalse(SchemaTreeNode.matchesAnyName(sampleInfo(), "zzz_no_existe"));
    }
}
