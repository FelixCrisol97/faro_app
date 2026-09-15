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

    /**
     * Los 7 valores que define un acento. Los cinco primeros vienen de la demo web; los
     * dos últimos se agregaron el 2026-09-10 y su razón de ser está acá abajo, porque un
     * record no tiene dónde poner el javadoc de cada componente por separado.
     *
     * <p><b>{@code onAccent}</b> — el color de TEXTO que va encima de {@code base}.
     * Antes no existía: {@code app.css} tenía {@code -fx-text-fill: white} fijo en
     * {@code .button}/{@code .run-button-label}/{@code .run-button-shortcut}, lo que
     * asume que todo acento es lo bastante oscuro como para llevar texto blanco. Eso es
     * cierto para los 6 de tema claro y falso para varios de tema oscuro, donde los
     * acentos son a propósito versiones MÁS CLARAS.
     *
     * <p>Hizo falta al agregar "negro": en tema oscuro su acento es blanco (ver
     * {@link #NAMES}), así que con el texto fijo en blanco el botón "Ejecutar" habría
     * quedado blanco sobre blanco. Con el token, cada acento declara su propio color
     * de texto legible.
     *
     * <p><b>{@code editorKeyword}</b> — color de las palabras reservadas del
     * editor SQL. Para los 6 acentos de color es EXACTAMENTE {@code base}, o sea el
     * comportamiento de siempre ({@code .sql-editor .keyword} usaba
     * {@code -token-accent-base} directo).
     *
     * <p>Existe por el acento "negro": ahí {@code base} es casi blanco en tema oscuro
     * (y casi negro en claro), o sea prácticamente el mismo valor que
     * {@code -token-text}, el color del texto normal del editor — y como TODO el texto
     * del editor va en negrita (pedido del usuario, 2026-08-28), tampoco el peso las
     * distingue. Resultado: las palabras reservadas se resaltaban igual, pero eran
     * indistinguibles del resto (reportado en vivo: "el texto de la query no está
     * remarcado como antes").
     *
     * <p>De los 4 colores de sintaxis del editor, este era el ÚNICO atado al acento —
     * cadenas usan {@code -token-success-base}, números {@code -token-warn-base} y
     * comentarios {@code -token-text-muted}. Por eso esos tres seguían viéndose bien
     * con el acento negro. Darle un token propio lo alinea con los otros tres.
     */
    public record Tokens(String base, String hover, String active, String soft, String softText,
            String onAccent, String editorKeyword) {

        /** Los 6 acentos de color: la palabra reservada es el acento, como siempre. */
        Tokens(String base, String hover, String active, String soft, String softText, String onAccent) {
            this(base, hover, active, soft, softText, onAccent, base);
        }
    }

    public static final List<String> NAMES = List.of("indigo", "violet", "blue", "teal", "rose", "amber", "negro");

    private static final String DEFAULT_NAME = "indigo";

    /** Texto claro sobre un acento oscuro — el caso de los 6 acentos de tema claro y de "negro" en claro. */
    private static final String ON_DARK_ACCENT = "#FFFFFF";
    /** Texto oscuro sobre un acento claro — ver las notas de contraste en el bloque estático. */
    private static final String ON_LIGHT_ACCENT = "#18181B";

    /** Punto de color plano para el swatch de Preferencias — SIEMPRE el valor de tema claro, igual que el mapa {@code accentColors} de la demo, sin importar el tema activo (así el color de la muestra no "salta" al cambiar de tema). */
    private static final Map<String, String> SWATCH_HEX = Map.of(
            "indigo", "#6366F1",
            "violet", "#8B5CF6",
            "blue", "#2563EB",
            "teal", "#0D9488",
            "rose", "#E11D48",
            "amber", "#D97706",
            "negro", "#18181B");

    private static final Map<String, Tokens> LIGHT = new LinkedHashMap<>();
    private static final Map<String, Tokens> DARK = new LinkedHashMap<>();

    static {
        LIGHT.put("indigo", new Tokens("#6366F1", "#4F46E5", "#4338CA", "#EEF2FF", "#4338CA", ON_DARK_ACCENT));
        DARK.put("indigo", new Tokens("#818CF8", "#A5B4FC", "#6366F1", "rgba(129,140,248,.18)", "#C7D2FE", ON_DARK_ACCENT));

        LIGHT.put("violet", new Tokens("#8B5CF6", "#7C3AED", "#6D28D9", "#F5F3FF", "#6D28D9", ON_DARK_ACCENT));
        DARK.put("violet", new Tokens("#A78BFA", "#C4B5FD", "#8B5CF6", "rgba(167,139,250,.18)", "#DDD6FE", ON_DARK_ACCENT));

        LIGHT.put("blue", new Tokens("#2563EB", "#1D4ED8", "#1E40AF", "#EFF6FF", "#1D4ED8", ON_DARK_ACCENT));
        DARK.put("blue", new Tokens("#60A5FA", "#93C5FD", "#3B82F6", "rgba(96,165,250,.18)", "#BFDBFE", ON_DARK_ACCENT));

        LIGHT.put("teal", new Tokens("#0D9488", "#0F766E", "#115E59", "#F0FDFA", "#0F766E", ON_DARK_ACCENT));
        // Texto oscuro, no blanco: #2DD4BF con blanco encima da 1.9:1 de contraste —
        // por debajo de cualquier umbral legible (el mínimo habitual es 4.5:1, y 3:1
        // para texto grande/negrita). Con #18181B da 9.5:1. Era un defecto real que
        // existía desde antes de este cambio; el token nuevo permitió corregirlo sin
        // tocar el color del acento en sí.
        DARK.put("teal", new Tokens("#2DD4BF", "#5EEAD4", "#14B8A6", "rgba(45,212,191,.18)", "#99F6E4", ON_LIGHT_ACCENT));

        LIGHT.put("rose", new Tokens("#E11D48", "#BE123C", "#9F1239", "#FFF1F2", "#BE123C", ON_DARK_ACCENT));
        DARK.put("rose", new Tokens("#FB7185", "#FDA4AF", "#F43F5E", "rgba(251,113,133,.18)", "#FECDD3", ON_DARK_ACCENT));

        LIGHT.put("amber", new Tokens("#D97706", "#B45309", "#92400E", "#FFFBEB", "#B45309", ON_DARK_ACCENT));
        // El peor de todos con texto blanco: #FBBF24 da 1.7:1, prácticamente ilegible.
        // Con #18181B, 10.6:1. Mismo criterio que teal.
        DARK.put("amber", new Tokens("#FBBF24", "#FCD34D", "#F59E0B", "rgba(251,191,36,.18)", "#FDE68A", ON_LIGHT_ACCENT));

        // "negro" (2026-09-10, pedido del usuario) — monocromático: negro en tema
        // claro, blanco en tema oscuro. No es una excepción al diseño sino su caso
        // extremo: los 6 acentos anteriores ya usan una versión más clara en tema
        // oscuro justo porque el acento tiene que contrastar con SU fondo
        // (#09090B en oscuro), y -token-accent-base no solo pinta fondos de botón —
        // también es color de texto, de borde (el subrayado de la pestaña activa), de
        // trazo de íconos y de las palabras clave del editor SQL. Un negro literal en
        // tema oscuro dejaría todo eso invisible.
        //
        // La palabra reservada del editor NO sigue al acento acá (7º token, ver
        // Tokens#editorKeyword): usa el índigo de siempre. Que la interfaz sea
        // monocromática no obliga al EDITOR a serlo — sus cadenas ya son verdes y sus
        // números ámbar con cualquier acento, así que dejar las palabras reservadas en
        // índigo es coherente con eso y mantiene el código legible. Es además cómo se
        // comportan los temas monocromáticos de los editores reales: el cromo va en
        // blanco y negro, la sintaxis conserva sus colores.
        LIGHT.put("negro", new Tokens("#18181B", "#09090B", "#000000", "#F4F4F5", "#18181B",
                ON_DARK_ACCENT, "#6366F1"));
        DARK.put("negro", new Tokens("#FAFAFA", "#FFFFFF", "#D4D4D8", "rgba(250,250,250,.16)", "#E4E4E7",
                ON_LIGHT_ACCENT, "#818CF8"));
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
