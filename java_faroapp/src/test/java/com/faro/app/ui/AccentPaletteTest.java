package com.faro.app.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

class AccentPaletteTest {

    @Test
    void everyNameHasATokenSetForBothThemes() {
        for (String name : AccentPalette.NAMES) {
            assertEquals(6, AccentPalette.NAMES.size());
            AccentPalette.Tokens light = AccentPalette.tokens(name, false);
            AccentPalette.Tokens dark = AccentPalette.tokens(name, true);
            assertNotEquals(light.base(), dark.base(), name + ": claro/oscuro no deberían compartir el mismo valor base");
        }
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
}
