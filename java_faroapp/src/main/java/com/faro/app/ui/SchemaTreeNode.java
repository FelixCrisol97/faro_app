package com.faro.app.ui;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

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
     * Fila temporal de "cargando…" mientras un fetch de esquema está en curso
     * (2026-09-07, pedido explícito del usuario: "quiero ver una animación de
     * carga cuando se estén cargando"). Antes esto era un {@code TreeItem<String>}
     * con el texto pelado — {@code ConnectionTreeCell} lo pintaba con el mismo
     * camino que el encabezado "SIN GRUPO", sin ninguna señal de movimiento, así
     * que un fetch lento era indistinguible de un árbol trabado. Como tipo propio,
     * la celda le puede dar su propio {@code ProgressIndicator} girando.
     *
     * <p>{@link Error} es su contraparte para un fetch que falló — mismo motivo
     * (antes también era un String pelado) y así la celda puede distinguir
     * "sigue cargando" de "falló" sin adivinar por el texto.
     */
    public record Loading(String label) {
    }

    /** Ver {@link Loading} — fila de un fetch de esquema que falló, con la causa real ya recortada a una línea. */
    public record Error(String message) {
    }

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
    public static Map<Kind, List<String>> filterSchema(Map<Kind, List<String>> namesByKind, String filter) {
        String needle = filter == null ? "" : filter.trim();
        if (needle.isEmpty()) {
            return namesByKind;
        }
        Map<Kind, List<String>> filtered = new EnumMap<>(Kind.class);
        for (Kind kind : Kind.values()) {
            List<String> matching = new ArrayList<>();
            for (String name : namesByKind.getOrDefault(kind, List.of())) {
                if (containsIgnoreCase(name, needle)) {
                    matching.add(name);
                }
            }
            filtered.put(kind, matching);
        }
        return filtered;
    }

    /**
     * {@code true} si {@code filter} calza al menos un nombre de
     * {@code namesByKind} (cualquier categoría) — para decidir si una base entra a
     * la lista solo por su esquema, no por su alias.
     *
     * <p><b>Sale en la PRIMERA coincidencia</b> (2026-09-08, hallazgo B2 de
     * {@code ANALISIS_OPTIMIZACION_ESTRUCTURA.md}). Antes esto era
     * {@code filterSchema(...).values().stream().anyMatch(names -> !names.isEmpty())}:
     * construía el mapa filtrado COMPLETO —un {@code ArrayList} por categoría, más
     * un {@code toLowerCase} por cada nombre de cada categoría— y recién entonces
     * preguntaba si alguna lista había quedado no vacía. Como
     * {@code ConnectionTreeBuilder#matches} llama acá una vez por base y por cada
     * TECLA del buscador, con bases DEV grandes (~3,000 tablas) y varias decenas de
     * bodegas registradas eso eran cientos de miles de cadenas temporales por
     * palabra escrita, en el hilo de la UI.
     */
    public static boolean matchesAnyName(Map<Kind, List<String>> namesByKind, String filter) {
        String needle = filter == null ? "" : filter.trim();
        for (List<String> names : namesByKind.values()) {
            if (needle.isEmpty()) {
                // Filtro vacío: mismo criterio exacto que antes — no es "el mapa tiene
                // categorías", es "alguna categoría tiene al menos un nombre" (un mapa con
                // las 6 categorías vacías daba false y tiene que seguir dándolo).
                if (!names.isEmpty()) {
                    return true;
                }
                continue;
            }
            for (String name : names) {
                if (containsIgnoreCase(name, needle)) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * {@code contains} insensible a mayúsculas SIN copiar el texto —
     * {@code String#regionMatches(true, ...)} compara en el lugar en vez de
     * materializar una versión en minúsculas de cada nombre solo para descartarla
     * enseguida. Misma técnica que {@code MainController#indexOfIgnoreCase}, que
     * salió del hallazgo #9 de {@code AUDITORIA_BUGS_RENDIMIENTO.md} — es el mismo
     * problema ("no copies para comparar") en otro archivo.
     */
    static boolean containsIgnoreCase(String haystack, String needle) {
        int lastPossibleStart = haystack.length() - needle.length();
        for (int i = 0; i <= lastPossibleStart; i++) {
            if (haystack.regionMatches(true, i, needle, 0, needle.length())) {
                return true;
            }
        }
        return false;
    }
}
