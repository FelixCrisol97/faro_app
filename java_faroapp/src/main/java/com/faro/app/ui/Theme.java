package com.faro.app.ui;

/**
 * Punto único donde vive la ruta de cada hoja de estilos — la ventana
 * principal (`Main.java`) y cada diálogo (que carga su propio FXML/Scene
 * por separado, ver {@code AddDatabaseDialog} y similares) la usan para no
 * quedar siempre en tema claro mientras la ventana principal ya cambió a
 * oscuro. Sin esto, un diálogo se ve con un tema distinto al de la
 * ventana que lo abrió — inconsistente, no solo feo.
 */
public final class Theme {

    private Theme() {
    }

    public static String stylesheetResourcePath(boolean darkTheme) {
        return darkTheme ? "/com/faro/app/styles-dark.css" : "/com/faro/app/styles.css";
    }
}
