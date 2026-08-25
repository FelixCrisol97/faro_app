package com.faro.app.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.faro.app.model.DatabaseEntry;
import com.faro.app.query.SchemaIntrospector.SchemaInfo;

/**
 * Nodos del explorador de esquema dentro del árbol de conexiones —
 * {@link Category} son las 5 filas "Tablas"/"Vistas"/"Funciones"/
 * "Procedimientos"/"Triggers" bajo una base expandida, {@link Item} es
 * cada nombre real dentro de una categoría. Deliberadamente {@code record}
 * normales, NUNCA {@code CheckBoxTreeItem} — {@code MainController#
 * collectDatabaseItems} recorre el árbol completo asumiendo que CUALQUIER
 * {@code CheckBoxTreeItem} que encuentra es una {@link DatabaseEntry} (cast
 * directo, sin chequeo); si estos nodos fueran {@code CheckBoxTreeItem} ese
 * cast tronaría apenas se expandiera una base.
 */
public final class SchemaTreeNode {

    public enum Kind {
        TABLES("Tablas"), VIEWS("Vistas"), FUNCTIONS("Funciones"), PROCEDURES("Procedimientos"), TRIGGERS("Triggers");

        private final String label;

        Kind(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public record Category(DatabaseEntry database, Kind kind, int count) {
    }

    public record Item(DatabaseEntry database, Kind kind, String name) {
    }

    private SchemaTreeNode() {
    }

    /** Las 5 categorías de {@code info}, en el orden fijo de {@link Kind}, cada una con su lista de nombres real (vacía si esa categoría no tiene nada). */
    public static java.util.Map<Kind, List<String>> namesByKind(SchemaInfo info) {
        java.util.Map<Kind, List<String>> map = new java.util.EnumMap<>(Kind.class);
        map.put(Kind.TABLES, info.tableNames());
        map.put(Kind.VIEWS, info.viewNames());
        map.put(Kind.FUNCTIONS, info.functionNames());
        map.put(Kind.PROCEDURES, info.procedureNames());
        map.put(Kind.TRIGGERS, info.triggerNames());
        return map;
    }

    /**
     * Qué categorías/nombres de {@code info} calzan con {@code filter}
     * (sin distinguir mayúsculas) — lógica pura, sin JDBC ni JavaFX, para
     * poder testearla sin una base real (mismo criterio que
     * {@code CsvFileNamer}/{@code SqlStatementSplitter}). {@code filter}
     * vacío devuelve todo sin filtrar. Usado por {@link ConnectionTreeBuilder}
     * para la búsqueda dentro del esquema — ver su javadoc para el límite
     * conocido (solo busca en bases ya exploradas al menos una vez, con
     * esquema en caché).
     */
    public static java.util.Map<Kind, List<String>> filterSchema(SchemaInfo info, String filter) {
        java.util.Map<Kind, List<String>> all = namesByKind(info);
        String needle = filter == null ? "" : filter.trim().toLowerCase(Locale.ROOT);
        if (needle.isEmpty()) {
            return all;
        }
        java.util.Map<Kind, List<String>> filtered = new java.util.EnumMap<>(Kind.class);
        for (Kind kind : Kind.values()) {
            List<String> matching = new ArrayList<>();
            for (String name : all.get(kind)) {
                if (name.toLowerCase(Locale.ROOT).contains(needle)) {
                    matching.add(name);
                }
            }
            filtered.put(kind, matching);
        }
        return filtered;
    }

    /** {@code true} si {@code filter} calza al menos un nombre de {@code info} (cualquier categoría) — para decidir si una base entra a la lista solo por su esquema, no por su alias. */
    public static boolean matchesAnyName(SchemaInfo info, String filter) {
        return filterSchema(info, filter).values().stream().anyMatch(names -> !names.isEmpty());
    }
}
