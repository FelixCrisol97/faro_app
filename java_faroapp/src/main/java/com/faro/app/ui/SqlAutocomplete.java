package com.faro.app.ui;

import java.util.List;
import java.util.Locale;

import org.fxmisc.richtext.CodeArea;

import com.faro.app.query.SqlFormatter;

import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;

/**
 * Editar → Autocompletado (Ctrl+Espacio) — sugiere palabras clave SQL que
 * empiecen con lo que se esté escribiendo justo antes del cursor, en un
 * {@code ContextMenu} posicionado ahí mismo (navegable con flechas/Enter,
 * eso ya lo trae {@code ContextMenu} de fábrica, no hay que programarlo).
 *
 * <p><b>Deliberadamente limitado a palabras clave</b> — reusa
 * {@link SqlFormatter#KEYWORDS}, el mismo set que ya usa Formatear SQL,
 * para no mantener dos listas separadas que se puedan desincronizar.
 * <b>No sugiere nombres de tabla/columna reales</b> — eso necesitaría
 * inspeccionar el esquema de una base ya conectada en vivo (y decidir
 * de cuál base, si hay varias marcadas), una función bastante más grande
 * que no se construyó acá; ver README para el detalle de este límite.
 *
 * <p><b>Verificado en vivo (2026-08-20)</b> — el usuario lo probó y
 * encontró un bug real: el popup se quedaba "pegado" en pantalla si el
 * cursor se movía a otra palabra (ej. con las flechas) sin elegir
 * ninguna sugerencia — {@code ContextMenu#autoHide} solo cierra el menú
 * con un clic afuera o pérdida de foco, no con movimiento del cursor
 * dentro del mismo {@code CodeArea}. Arreglado con
 * {@link #dismissOnCaretMove}: en cuanto el cursor se mueve por
 * cualquier motivo después de mostrarse el popup, se cierra solo.
 */
public final class SqlAutocomplete {

    /** El popup actual, si hay uno mostrándose — para poder cerrarlo si se pide otro antes de que el usuario elija algo. */
    private static ContextMenu activeMenu;

    private SqlAutocomplete() {
    }

    public static void show(CodeArea codeArea) {
        hideActiveMenu();

        int caret = codeArea.getCaretPosition();
        String text = codeArea.getText();

        int start = caret;
        while (start > 0 && isWordChar(text.charAt(start - 1))) {
            start--;
        }
        String prefix = text.substring(start, caret);
        if (prefix.isEmpty()) {
            return;
        }
        String prefixUpper = prefix.toUpperCase(Locale.ROOT);
        int replaceStart = start;
        int replaceEnd = caret;

        List<String> matches = SqlFormatter.KEYWORDS.stream()
                .filter(keyword -> keyword.startsWith(prefixUpper) && !keyword.equals(prefixUpper))
                .sorted()
                .toList();
        if (matches.isEmpty()) {
            return;
        }

        ContextMenu menu = new ContextMenu();
        for (String keyword : matches) {
            MenuItem item = new MenuItem(keyword);
            item.setOnAction(event -> {
                codeArea.replaceText(replaceStart, replaceEnd, keyword);
                codeArea.moveTo(replaceStart + keyword.length());
            });
            menu.getItems().add(item);
        }

        activeMenu = menu;
        dismissOnCaretMove(codeArea, menu);
        codeArea.getCaretBounds().ifPresent(bounds -> menu.show(codeArea, bounds.getMaxX(), bounds.getMaxY()));
    }

    /** Si hay un popup de una invocación anterior sin resolver (el usuario no eligió nada), lo cierra antes de mostrar uno nuevo. */
    private static void hideActiveMenu() {
        if (activeMenu != null) {
            activeMenu.hide();
            activeMenu = null;
        }
    }

    /**
     * Cierra {@code menu} en cuanto el cursor de {@code codeArea} se mueva
     * por cualquier motivo (flechas, clic en otra palabra, escribir más
     * texto) — hallazgo real probando esto en vivo: sin esto, el popup se
     * quedaba pegado en la posición vieja si el usuario no elegía ninguna
     * sugerencia y simplemente seguía editando en otro lado. El listener
     * se quita solo (de sí mismo, y también si el menú se cierra por otro
     * camino — clic en una sugerencia, clic afuera) para no dejar
     * listeners colgados en {@code caretPositionProperty()}.
     */
    private static void dismissOnCaretMove(CodeArea codeArea, ContextMenu menu) {
        ChangeListener<Integer> listener = new ChangeListener<>() {
            @Override
            public void changed(ObservableValue<? extends Integer> obs, Integer oldPos, Integer newPos) {
                codeArea.caretPositionProperty().removeListener(this);
                menu.hide();
            }
        };
        codeArea.caretPositionProperty().addListener(listener);
        menu.setOnHidden(event -> {
            codeArea.caretPositionProperty().removeListener(listener);
            if (activeMenu == menu) {
                activeMenu = null;
            }
        });
    }

    private static boolean isWordChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }
}
