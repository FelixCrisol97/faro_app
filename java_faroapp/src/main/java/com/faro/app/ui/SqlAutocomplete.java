package com.faro.app.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import org.fxmisc.richtext.CodeArea;

import com.faro.app.data.CredentialStore;
import com.faro.app.model.DatabaseEntry;
import com.faro.app.query.ConnectionPoolManager;
import com.faro.app.query.SchemaIntrospector;
import com.faro.app.query.SchemaIntrospector.SchemaInfo;
import com.faro.app.query.SqlFormatter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.MenuItem;

/**
 * Editar → Autocompletado (Ctrl+Espacio) — sugiere palabras clave SQL
 * (siempre) y, si hay una base marcada con esquema ya conocido, también
 * nombres reales de tabla/columna de esa base — todo junto en el mismo
 * {@code ContextMenu} (navegable con flechas/Enter, eso ya lo trae
 * {@code ContextMenu} de fábrica).
 *
 * <p><b>Esquema real agregado 2026-08-22</b> — antes solo sugería
 * {@link com.faro.app.query.SqlFormatter#KEYWORDS}; ahora también usa
 * {@link SchemaIntrospector}. Como leer el esquema es un viaje real a la
 * base (no algo instantáneo), no se bloquea el hilo de la UI esperándolo:
 * si todavía no hay nada en caché para esa base, esta invocación muestra
 * solo palabras clave (como antes) y de paso dispara una carga en
 * segundo plano — las siguientes veces que el usuario pida autocompletado
 * para esa misma base, ya salen tabla/columnas también.
 *
 * <p><b>Caché centralizado en {@link SchemaIntrospector} (2026-08-25)</b> —
 * antes esta clase tenía su propio {@code Map}/{@code Set} de caché; ahora
 * reusa {@link SchemaIntrospector#cached}/{@link SchemaIntrospector#loadInBackground},
 * compartido con el explorador de esquema del árbol de conexiones — expandir
 * una base ahí también deja su esquema listo para el autocompletado, y
 * viceversa, un solo fetch por base sirve a los dos. Sin invalidación
 * automática si el esquema cambia en caliente del lado del servidor — el
 * usuario preguntó cómo recargar (2026-08-25) y antes de esa fecha no
 * había forma real (marcar/desmarcar la base NO invalidaba nada, pese a
 * lo que decía una versión anterior de este comentario, nunca verificado
 * contra el código real). Ahora sí existe: "Recargar esquema" en el menú
 * contextual de una fila de base (ver {@code ConnectionTreeCell}), que
 * llama a {@link SchemaIntrospector#invalidate}.
 *
 * <p><b>Verificado en vivo (2026-08-20, reconfirmado 2026-08-21)</b> — el
 * usuario probó el popup y encontró un bug real: se quedaba "pegado" en
 * pantalla si el cursor se movía a otra palabra sin elegir ninguna
 * sugerencia. Arreglado con {@link #dismissOnCaretMove}.
 */
public final class SqlAutocomplete {

    private static final Logger log = LoggerFactory.getLogger(SqlAutocomplete.class);

    /** El popup actual, si hay uno mostrándose — para poder cerrarlo si se pide otro antes de que el usuario elija algo. */
    private static ContextMenu activeMenu;

    private SqlAutocomplete() {
    }

    public static void show(CodeArea codeArea, DatabaseEntry activeDb, CredentialStore credentials, ConnectionPoolManager pool) {
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

        List<String> matches = new ArrayList<>(SqlFormatter.KEYWORDS.stream()
                .filter(keyword -> keyword.startsWith(prefixUpper) && !keyword.equals(prefixUpper))
                .toList());

        if (activeDb != null) {
            Optional<SchemaInfo> schema = SchemaIntrospector.cached(activeDb.id());
            if (schema.isPresent()) {
                for (String name : schema.get().queryableNames()) {
                    if (name.toUpperCase(Locale.ROOT).startsWith(prefixUpper) && !matches.contains(name)) {
                        matches.add(name);
                    }
                }
                for (String name : schema.get().allColumnNames()) {
                    if (name.toUpperCase(Locale.ROOT).startsWith(prefixUpper) && !matches.contains(name)) {
                        matches.add(name);
                    }
                }
            } else {
                log.debug("[{}] Sin esquema en caché — disparando carga en segundo plano.", activeDb.alias());
                SchemaIntrospector.loadInBackground(activeDb, credentials, pool, info -> { });
            }
        }

        matches.sort(String.CASE_INSENSITIVE_ORDER);
        if (matches.isEmpty()) {
            return;
        }

        ContextMenu menu = new ContextMenu();
        for (String candidate : matches) {
            MenuItem item = new MenuItem(candidate);
            item.setOnAction(event -> {
                codeArea.replaceText(replaceStart, replaceEnd, candidate);
                codeArea.moveTo(replaceStart + candidate.length());
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
