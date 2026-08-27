package com.faro.app.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import com.faro.app.model.DatabaseEntry;

/**
 * Nodos del explorador de esquema dentro del árbol de conexiones —
 * {@link Category} son las 6 filas "Tablas"/"Vistas"/"Funciones"/
 * "Procedimientos"/"Triggers"/"Tipos" bajo una base expandida, {@link Item} es
 * cada nombre real dentro de una categoría. Deliberadamente {@code record}
 * normales, NUNCA {@code CheckBoxTreeItem} — {@code MainController#
 * collectDatabaseItems} recorre el árbol completo asumiendo que CUALQUIER
 * {@code CheckBoxTreeItem} que encuentra es una {@link DatabaseEntry} (cast
 * directo, sin chequeo); si estos nodos fueran {@code CheckBoxTreeItem} ese
 * cast tronaría apenas se expandiera una base.
 */
public final class SchemaTreeNode {

    public enum Kind {
        TABLES("Tablas"), VIEWS("Vistas"), FUNCTIONS("Funciones"), PROCEDURES("Procedimientos"), TRIGGERS("Triggers"),
        /** {@code CREATE TYPE}/{@code CREATE DOMAIN} — enums/dominios/tipos compuestos en PostgreSQL, tipos alias/de tabla en SQL Server. Pedido explícito del usuario (2026-08-25), 6ª categoría. */
        TYPES("Tipos");

        private final String label;

        Kind(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** {@code count} = {@link #UNKNOWN_COUNT} = "todavía no se sabe" — esquema progresivo (2026-08-25): Funciones/Procedimientos/Triggers/Tipos son categorías perezosas ({@code CategoryTreeItem}), su conteo real no se conoce hasta que el usuario la expande. Tablas/Vistas siempre traen un conteo real, nunca el sentinela. */
    public record Category(DatabaseEntry database, Kind kind, int count) {
    }

    /** Sentinela de {@link Category#count()} — "esta categoría todavía no se cargó, no se sabe cuántos nombres tiene". Ver {@code ConnectionTreeCell}, que lo trata como "sin conteo visible" en vez de imprimir "-1". */
    public static final int UNKNOWN_COUNT = -1;

    /**
     * {@code parentTable} solo aplica a {@link Kind#TRIGGERS} (no nulo ahí,
     * {@code null} en los otros 4 tipos) — en PostgreSQL, un nombre de
     * trigger es único por tabla, no global, así que
     * {@code pg_get_triggerdef} necesita la tabla dueña para no ambigüar
     * (ver {@code SchemaIntrospector#fetchTriggers}). En SQL Server no hace
     * falta (un trigger sí es addressable por su propio nombre calificado),
     * pero se pasa igual por simplicidad de la firma.
     */
    public record Item(DatabaseEntry database, Kind kind, String name, String parentTable) {
    }

    /** Las 6 acciones del menú "Generar…" de una fila de objeto de esquema — ver {@code ConnectionTreeCell}/{@code MainController#onGenerateScript}. */
    public enum GenerateAction {
        SELECT, INSERT, UPDATE, DELETE, CREATE_TABLE, CREATE_SCRIPT
    }

    private SchemaTreeNode() {
    }

    /**
     * Qué categorías/nombres de {@code namesByKind} calzan con {@code filter}
     * (sin distinguir mayúsculas) — lógica pura, sin JDBC ni JavaFX, para
     * poder testearla sin una base real (mismo criterio que
     * {@code CsvFileNamer}/{@code SqlStatementSplitter}). {@code filter}
     * vacío devuelve todo sin filtrar. Usado por {@link ConnectionTreeBuilder}
     * para la búsqueda dentro del esquema — ver su javadoc para el límite
     * conocido (solo busca en categorías ya expandidas al menos una vez, con
     * esquema en caché — ver {@code SchemaIntrospector#cachedNamesByKind}).
     */
    public static java.util.Map<Kind, List<String>> filterSchema(java.util.Map<Kind, List<String>> namesByKind, String filter) {
        String needle = filter == null ? "" : filter.trim().toLowerCase(Locale.ROOT);
        if (needle.isEmpty()) {
            return namesByKind;
        }
        java.util.Map<Kind, List<String>> filtered = new java.util.EnumMap<>(Kind.class);
        for (Kind kind : Kind.values()) {
            List<String> matching = new ArrayList<>();
            for (String name : namesByKind.getOrDefault(kind, List.of())) {
                if (name.toLowerCase(Locale.ROOT).contains(needle)) {
                    matching.add(name);
                }
            }
            filtered.put(kind, matching);
        }
        return filtered;
    }

    /** {@code true} si {@code filter} calza al menos un nombre de {@code namesByKind} (cualquier categoría) — para decidir si una base entra a la lista solo por su esquema, no por su alias. */
    public static boolean matchesAnyName(java.util.Map<Kind, List<String>> namesByKind, String filter) {
        return filterSchema(namesByKind, filter).values().stream().anyMatch(names -> !names.isEmpty());
    }
}
