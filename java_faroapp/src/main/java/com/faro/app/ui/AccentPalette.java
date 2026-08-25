package com.faro.app.ui;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Los 6 colores de acento reales × 2 temas — valores copiados tal cual de
 * {@code demo_html/styles.css} líneas 97-114 (comentario propio de ese
 * archivo: "6 hues × 2 brightnesses", citando {@code app_accent.dart} como
 * origen), no inventados para esta clase. La demo web los aplica con un
 * atributo {@code data-accent} + selectores CSS {@code [data-accent='x']} —
 * mecanismo que no existe en JavaFX (no hay DOM/atributos); acá se aplican
 * como override de "looked-up colors" en caliente, ver {@link Theme#applyTo}.
 */
public final class AccentPalette {

    public record Tokens(String base, String hover, String active, String soft, String softText) {
    }

    public static final List<String> NAMES = List.of("indigo", "violet", "blue", "teal", "rose", "amber");

    private static final String DEFAULT_NAME = "indigo";

    /** Punto de color plano para el swatch de Preferencias — SIEMPRE el valor de tema claro, igual que el mapa {@code accentColors} de la demo, sin importar el tema activo (así el color de la muestra no "salta" al cambiar de tema). */
    private static final Map<String, String> SWATCH_HEX = Map.of(
            "indigo", "#6366F1",
            "violet", "#8B5CF6",
            "blue", "#2563EB",
            "teal", "#0D9488",
            "rose", "#E11D48",
            "amber", "#D97706");

    private static final Map<String, Tokens> LIGHT = new LinkedHashMap<>();
    private static final Map<String, Tokens> DARK = new LinkedHashMap<>();

    static {
        LIGHT.put("indigo", new Tokens("#6366F1", "#4F46E5", "#4338CA", "#EEF2FF", "#4338CA"));
        DARK.put("indigo", new Tokens("#818CF8", "#A5B4FC", "#6366F1", "rgba(129,140,248,.18)", "#C7D2FE"));

        LIGHT.put("violet", new Tokens("#8B5CF6", "#7C3AED", "#6D28D9", "#F5F3FF", "#6D28D9"));
        DARK.put("violet", new Tokens("#A78BFA", "#C4B5FD", "#8B5CF6", "rgba(167,139,250,.18)", "#DDD6FE"));

        LIGHT.put("blue", new Tokens("#2563EB", "#1D4ED8", "#1E40AF", "#EFF6FF", "#1D4ED8"));
        DARK.put("blue", new Tokens("#60A5FA", "#93C5FD", "#3B82F6", "rgba(96,165,250,.18)", "#BFDBFE"));

        LIGHT.put("teal", new Tokens("#0D9488", "#0F766E", "#115E59", "#F0FDFA", "#0F766E"));
        DARK.put("teal", new Tokens("#2DD4BF", "#5EEAD4", "#14B8A6", "rgba(45,212,191,.18)", "#99F6E4"));

        LIGHT.put("rose", new Tokens("#E11D48", "#BE123C", "#9F1239", "#FFF1F2", "#BE123C"));
        DARK.put("rose", new Tokens("#FB7185", "#FDA4AF", "#F43F5E", "rgba(251,113,133,.18)", "#FECDD3"));

        LIGHT.put("amber", new Tokens("#D97706", "#B45309", "#92400E", "#FFFBEB", "#B45309"));
        DARK.put("amber", new Tokens("#FBBF24", "#FCD34D", "#F59E0B", "rgba(251,191,36,.18)", "#FDE68A"));
    }

    private AccentPalette() {
    }

    /** {@code name} desconocido (ej. un JSON de preferencias viejo/corrupto) cae a "indigo" en vez de tronar. */
    public static Tokens tokens(String name, boolean darkTheme) {
        Map<String, Tokens> table = darkTheme ? DARK : LIGHT;
        return table.getOrDefault(name, table.get(DEFAULT_NAME));
    }

    public static String swatchHex(String name) {
        return SWATCH_HEX.getOrDefault(name, SWATCH_HEX.get(DEFAULT_NAME));
    }
}
