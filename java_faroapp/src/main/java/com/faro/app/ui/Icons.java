package com.faro.app.ui;

import javafx.scene.shape.SVGPath;

/**
 * Rutas SVG Lucide (viewBox 0 0 24 24) — mismo set que {@code demo_html/icons.js},
 * para que los íconos de la versión Java se vean igual que la demo y la
 * app Flutter, no inventados de nuevo. La mayoría son de trazo
 * (stroke=currentColor, sin relleno); {@link #PLAY} es la excepción — un
 * triángulo relleno, igual que el prototipo.
 */
public final class Icons {

    private Icons() {
    }

    public static final String PLAY = "M7 4.5v15l13-7.5Z";
    public static final String FOLDER =
            "M3 6.5A1.5 1.5 0 0 1 4.5 5H9l2 2.5h8.5A1.5 1.5 0 0 1 21 9v9a1.5 1.5 0 0 1-1.5 1.5h-15A1.5 1.5 0 0 1 3 18Z";
    public static final String SAVE = "M5 3h11l4 4v13a1 1 0 0 1-1 1H5a1 1 0 0 1-1-1V4a1 1 0 0 1 1-1Z M8 3v6h7V3 M8 21v-7h8v7";
    public static final String ALIGN_LEFT = "M4 5h16M4 10h10M4 15h16M4 20h10";
    public static final String STAR = "m12 2.5 3 6.4 6.9.8-5 4.9 1.2 7-6.1-3.4-6.1 3.4 1.2-7-5-4.9 6.9-.8Z";
    public static final String PENCIL = "M17 3a2.85 2.83 0 1 1 4 4L7.5 20.5 2 22l1.5-5.5Z M15 5l4 4";
    public static final String PLUS = "M12 5v14M5 12h14";
    public static final String LOCK = "M4 11h16v10H4Z M8 11V7a4 4 0 0 1 8 0v4";
    public static final String LOCK_OPEN = "M4 11h16v10H4Z M8 11V7a4 4 0 0 1 7.6-1.8";
    /** Círculo (r=4, centro 12,12) expresado como dos arcos, porque SVGPath no soporta &lt;circle&gt; — + los 8 rayos. */
    public static final String SUN = "M8 12a4 4 0 1 0 8 0a4 4 0 1 0 -8 0"
            + "M12 2v2M12 20v2M4.9 4.9l1.4 1.4M17.7 17.7l1.4 1.4M2 12h2M20 12h2M4.9 19.1l1.4-1.4M17.7 6.3l1.4-1.4";
    public static final String MOON = "M20 14.9A9 9 0 1 1 9.1 4a7 7 0 0 0 10.9 10.9Z";
    public static final String X = "M18 6 6 18M6 6l12 12";
    /** Elipse (rx=8, ry=3, centro 12,5) expresada como dos arcos + los 2 trazos del cilindro — igual que Lucide `database`. */
    public static final String DATABASE = "M4 5a8 3 0 1 0 16 0a8 3 0 1 0 -16 0"
            + "M4 5v14c0 1.7 3.6 3 8 3s8-1.3 8-3V5"
            + "M4 12c0 1.7 3.6 3 8 3s8-1.3 8-3";
    /** Círculo (r=9, centro 12,12) + la manecilla — igual que Lucide `clock`. */
    public static final String CLOCK = "M3 12a9 9 0 1 0 18 0a9 9 0 1 0 -18 0" + "M12 7v5l3.5 2";
    /** Círculo (r=3, centro 12,12) + el cuerpo del engrane — igual que Lucide `settings`. */
    public static final String SETTINGS = "M9 12a3 3 0 1 0 6 0a3 3 0 1 0 -6 0"
            + "M19.4 15a1.7 1.7 0 0 0 .3 1.9l.1.1a2 2 0 1 1-2.8 2.8l-.1-.1a1.7 1.7 0 0 0-1.9-.3 1.7 1.7 0 0 0-1 1.5V21a2 2 0 1 1-4 0v-.1a1.7 1.7 0 0 0-1-1.6 1.7 1.7 0 0 0-1.9.3l-.1.1a2 2 0 1 1-2.8-2.8l.1-.1a1.7 1.7 0 0 0 .3-1.9 1.7 1.7 0 0 0-1.5-1H3a2 2 0 1 1 0-4h.1a1.7 1.7 0 0 0 1.6-1 1.7 1.7 0 0 0-.3-1.9l-.1-.1a2 2 0 1 1 2.8-2.8l.1.1a1.7 1.7 0 0 0 1.9.3H9a1.7 1.7 0 0 0 1-1.5V3a2 2 0 1 1 4 0v.1a1.7 1.7 0 0 0 1 1.5 1.7 1.7 0 0 0 1.9-.3l.1-.1a2 2 0 1 1 2.8 2.8l-.1.1a1.7 1.7 0 0 0-.3 1.9V9a1.7 1.7 0 0 0 1.5 1H21a2 2 0 1 1 0 4h-.1a1.7 1.7 0 0 0-1.5 1z";

    /** Ícono de trazo (borde, sin relleno) — la mayoría de los íconos de Lucide. */
    public static SVGPath strokeIcon(String path) {
        SVGPath icon = new SVGPath();
        icon.setContent(path);
        icon.getStyleClass().add("icon-stroke");
        icon.setScaleX(0.6);
        icon.setScaleY(0.6);
        return icon;
    }

    /** Ícono de relleno sólido — solo {@link #PLAY}, igual que el prototipo. */
    public static SVGPath fillIcon(String path) {
        SVGPath icon = new SVGPath();
        icon.setContent(path);
        icon.getStyleClass().add("icon-fill");
        icon.setScaleX(0.6);
        icon.setScaleY(0.6);
        return icon;
    }
}
