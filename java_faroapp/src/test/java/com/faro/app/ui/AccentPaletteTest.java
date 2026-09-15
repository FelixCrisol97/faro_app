package com.faro.app.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

class AccentPaletteTest {

    @Test
    void everyNameHasATokenSetForBothThemes() {
        for (String name : AccentPalette.NAMES) {
            AccentPalette.Tokens light = AccentPalette.tokens(name, false);
            AccentPalette.Tokens dark = AccentPalette.tokens(name, true);
            assertNotEquals(light.base(), dark.base(), name + ": claro/oscuro no deberían compartir el mismo valor base");
        }
    }

    /**
     * Antes esto era un {@code assertEquals(6, NAMES.size())} DENTRO del bucle de
     * arriba — o sea, un número mágico que había que subir a mano con cada acento
     * nuevo y que no comprobaba nada del contenido. Lo que de verdad importa es que no
     * haya nombres repetidos (un duplicado haría que el swatch seleccionado en
     * Preferencias fuera ambiguo) y que la lista no esté vacía.
     */
    @Test
    void theNamesAreUniqueAndNotEmpty() {
        assertFalse(AccentPalette.NAMES.isEmpty());
        Set<String> unique = new HashSet<>(AccentPalette.NAMES);
        assertEquals(AccentPalette.NAMES.size(), unique.size(), "hay nombres de acento repetidos");
    }

    @Test
    void unknownNameFallsBackToIndigo() {
        AccentPalette.Tokens fallback = AccentPalette.tokens("no-existe", false);
        AccentPalette.Tokens indigo = AccentPalette.tokens("indigo", false);

        assertEquals(indigo, fallback);
    }

    @Test
    void everyNameHasASwatchHex() {
        for (String name : AccentPalette.NAMES) {
            String hex = AccentPalette.swatchHex(name);
            assertEquals(7, hex.length(), name + ": se esperaba un hex tipo #RRGGBB");
            assertEquals('#', hex.charAt(0));
        }
    }

    @Test
    void unknownNameSwatchFallsBackToIndigo() {
        assertEquals(AccentPalette.swatchHex("indigo"), AccentPalette.swatchHex("no-existe"));
    }

    // ---- Contraste del texto sobre el acento (2026-09-10) ----

    /**
     * Piso de contraste entre {@code base} y {@code onAccent}.
     *
     * <p><b>Es un guardia contra regresiones, NO un certificado de accesibilidad</b> —
     * importa que quede claro. El umbral habitual (WCAG AA) es 4.5:1 para texto
     * normal y 3:1 para texto grande o en negrita, que es el caso de estos botones.
     * Los acentos de tema oscuro de este proyecto, heredados del diseño original, se
     * quedan por debajo de ese 3:1 incluso con el color de texto correcto: con blanco
     * dan indigo 2.8, rose 2.7, violet 2.5 y blue 2.3.
     *
     * <p>El piso está justo por debajo del peor de esos (blue) a propósito: lo que
     * este test impide es volver a los casos que eran directamente ilegibles —amber en
     * oscuro daba <b>1.7</b> con texto blanco y teal <b>1.9</b>, ambos corregidos el
     * 2026-09-10 poniéndoles texto oscuro. Subir el piso a 3.0 haría fallar a los
     * cuatro de arriba, que es una decisión de diseño pendiente, no un defecto que
     * este test deba forzar.
     */
    private static final double MIN_CONTRAST = 2.2;

    @Test
    void everyAccentHasReadableTextOnTopOfIt() {
        for (String name : AccentPalette.NAMES) {
            for (boolean darkTheme : new boolean[] {false, true}) {
                AccentPalette.Tokens t = AccentPalette.tokens(name, darkTheme);
                double ratio = contrastRatio(t.base(), t.onAccent());
                assertTrue(ratio >= MIN_CONTRAST, String.format(
                        "%s (%s): el texto %s sobre el acento %s da %.2f:1, por debajo del piso de %.1f:1",
                        name, darkTheme ? "oscuro" : "claro", t.onAccent(), t.base(), ratio, MIN_CONTRAST));
            }
        }
    }

    /**
     * Fondo del editor SQL y color de su texto normal, por tema — copiados de
     * {@code theme-light.css}/{@code theme-dark.css} ({@code -token-surface-alt} y
     * {@code -token-text}). Duplicarlos acá es el precio de que vivan en CSS y no en
     * Java; si alguna vez cambian allá, este test empieza a medir contra valores
     * viejos, así que van con esta nota.
     */
    private static final String EDITOR_BG_LIGHT = "#F1F5F9";
    private static final String EDITOR_BG_DARK = "#27272A";
    private static final String EDITOR_TEXT_LIGHT = "#0F172A";
    private static final String EDITOR_TEXT_DARK = "#F4F4F5";

    /**
     * Las palabras reservadas tienen que distinguirse de DOS cosas a la vez: del fondo
     * del editor (o no se leen) y del texto normal (o no se nota que están resaltadas).
     *
     * <p>Lo segundo es lo que se rompió de verdad con el acento negro: el resaltado
     * funcionaba, pero pintaba las palabras reservadas casi del mismo color que el
     * resto del texto, y como todo el editor va en negrita tampoco había diferencia de
     * peso. Un test que solo mirara el contraste contra el fondo lo habría dado por
     * bueno.
     */
    @Test
    void editorKeywordsStandOutFromBothTheBackgroundAndTheNormalText() {
        for (String name : AccentPalette.NAMES) {
            for (boolean darkTheme : new boolean[] {false, true}) {
                String keyword = AccentPalette.tokens(name, darkTheme).editorKeyword();
                String background = darkTheme ? EDITOR_BG_DARK : EDITOR_BG_LIGHT;
                String normalText = darkTheme ? EDITOR_TEXT_DARK : EDITOR_TEXT_LIGHT;
                String where = name + " (" + (darkTheme ? "oscuro" : "claro") + ")";

                // 2.8 y no 3.0 (el umbral WCAG para texto en negrita, que es lo que son
                // estas palabras): amber en tema claro da 2.91:1 y quedaría fuera. Es una
                // condición que ya existía antes de que este test se escribiera, no un
                // defecto nuevo, y corregirla significaría cambiarle el color a un acento
                // que nadie reportó como problemático. Mismo criterio que MIN_CONTRAST:
                // esto atrapa colapsos, no certifica accesibilidad.
                double vsBackground = contrastRatio(keyword, background);
                assertTrue(vsBackground >= 2.8, String.format(
                        "%s: la palabra reservada %s sobre el fondo del editor %s da %.2f:1 — no se lee",
                        where, keyword, background, vsBackground));

                double vsText = contrastRatio(keyword, normalText);
                assertTrue(vsText >= 1.5, String.format(
                        "%s: la palabra reservada %s es casi igual al texto normal %s (%.2f:1) — "
                                + "se resalta pero no se nota",
                        where, keyword, normalText, vsText));
            }
        }
    }

    /** El acento "negro" es el caso extremo del diseño "una versión por tema": casi negro en claro, casi blanco en oscuro. */
    @Test
    void blackAccentInvertsBetweenThemes() {
        AccentPalette.Tokens light = AccentPalette.tokens("negro", false);
        AccentPalette.Tokens dark = AccentPalette.tokens("negro", true);

        assertTrue(relativeLuminance(light.base()) < 0.05, "en tema claro el acento negro tiene que ser oscuro de verdad");
        assertTrue(relativeLuminance(dark.base()) > 0.85, "en tema oscuro tiene que ser claro, o sería invisible sobre #09090B");
        // Y el texto encima acompaña la inversión, que es justo lo que el token nuevo resuelve.
        assertTrue(relativeLuminance(light.onAccent()) > 0.85);
        assertTrue(relativeLuminance(dark.onAccent()) < 0.05);
    }

    /** WCAG 2.x: (L1 + 0.05) / (L2 + 0.05), con L1 el más claro de los dos. */
    private static double contrastRatio(String hexA, String hexB) {
        double a = relativeLuminance(hexA);
        double b = relativeLuminance(hexB);
        return (Math.max(a, b) + 0.05) / (Math.min(a, b) + 0.05);
    }

    private static double relativeLuminance(String hex) {
        int rgb = Integer.parseInt(hex.substring(1), 16);
        double r = channel((rgb >> 16) & 0xFF);
        double g = channel((rgb >> 8) & 0xFF);
        double b = channel(rgb & 0xFF);
        return 0.2126 * r + 0.7152 * g + 0.0722 * b;
    }

    private static double channel(int value) {
        double c = value / 255.0;
        return c <= 0.04045 ? c / 12.92 : Math.pow((c + 0.055) / 1.055, 2.4);
    }
}
