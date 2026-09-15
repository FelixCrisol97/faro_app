package com.faro.app.ui;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

import org.fxmisc.richtext.CodeArea;

import com.faro.app.data.CredentialStore;
import com.faro.app.model.DatabaseEntry;
import com.faro.app.query.ConnectionPoolManager;
import com.faro.app.query.SchemaIntrospector;
import com.faro.app.query.SchemaIntrospector.SchemaStructure;
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

    /** Cuántas sugerencias como máximo — ver el comentario en {@link #show}. */
    private static final int MAX_SUGGESTIONS = 50;

    private SqlAutocomplete() {
    }

    /**
     * {@code name.startsWith(prefix)} insensible a mayúsculas SIN copiar el nombre
     * (2026-09-10, hallazgo B9). Antes era
     * {@code name.toUpperCase(Locale.ROOT).startsWith(prefixUpper)}, que asigna una
     * cadena nueva por cada nombre de tabla/vista/columna del esquema y en cada
     * invocación de Ctrl+Espacio — con miles de tablas, miles de cadenas que mueren de
     * inmediato, en el hilo de la UI y justo antes de mostrar el popup.
     *
     * <p>{@code regionMatches(true, ...)} compara en el lugar. Misma técnica que
     * {@code MainController#indexOfIgnoreCase} y {@code SchemaTreeNode#containsIgnoreCase}
     * — es el mismo problema ("no copies para comparar") en el tercer archivo.
     */
    private static boolean startsWithIgnoreCase(String name, String prefix) {
        return name.regionMatches(true, 0, prefix, 0, prefix.length());
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

        // LinkedHashSet, no ArrayList (2026-09-07, hallazgo #8 de
        // AUDITORIA_BUGS_RENDIMIENTO.md) — los bucles de abajo hacían
        // `!matches.contains(name)` sobre una lista, o sea una búsqueda lineal por cada
        // nombre del esquema: cuadrático. Contra una base DEV real de cliente (miles de
        // tablas) y un prefijo corto de una o dos letras —justo cuando el autocompletado
        // más sirve— eso es medio millón de comparaciones en el hilo de la UI antes de
        // que el popup aparezca. El Set deja el contains en O(1) y la deduplicación
        // queda implícita.
        //
        // (Corrección 2026-09-10, hallazgo B9: una versión anterior de este comentario
        // justificaba el LinkedHashSet además por conservar el orden de inserción
        // "primero palabras clave, después tablas, después columnas". Eso no es cierto
        // desde que existe el `sort` alfabético de más abajo, que descarta ese orden por
        // completo. La razón válida es solo el contains en O(1).)
        Set<String> matches = new LinkedHashSet<>(SqlFormatter.KEYWORDS.stream()
                .filter(keyword -> keyword.startsWith(prefixUpper) && !keyword.equals(prefixUpper))
                .toList());

        if (activeDb != null) {
            Optional<SchemaStructure> schema = SchemaIntrospector.cached(activeDb.id());
            if (schema.isPresent()) {
                for (String name : schema.get().queryableNames()) {
                    if (startsWithIgnoreCase(name, prefix)) {
                        matches.add(name);
                    }
                }
                // Esquema progresivo (2026-08-25) — a diferencia de tabla/vista (siempre
                // completo apenas se expande la base una vez), las columnas ya no se traen
                // todas de un jalón: esto solo trae nombres de tablas cuyas columnas ya se
                // pidieron esta sesión (SELECT/INSERT/UPDATE/DELETE/CREATE TABLE reales, o
                // autocompletado repetido sobre esa tabla) — se va llenando solo, no
                // completo desde el primer uso. Ver SchemaIntrospector#cachedColumnNames.
                for (String name : SchemaIntrospector.cachedColumnNames(activeDb.id())) {
                    if (startsWithIgnoreCase(name, prefix)) {
                        matches.add(name);
                    }
                }
            } else {
                log.debug("[{}] Sin esquema en caché — disparando carga en segundo plano.", activeDb.alias());
                SchemaIntrospector.loadInBackground(activeDb, credentials, pool, info -> { });
            }
        }

        if (matches.isEmpty()) {
            return;
        }
        List<String> sortedMatches = new ArrayList<>(matches);
        sortedMatches.sort(String.CASE_INSENSITIVE_ORDER);
        // Tope real de sugerencias (2026-09-10, hallazgo B9) — antes se creaba un
        // MenuItem por cada coincidencia, sin límite. Con una base DEV de cliente (miles
        // de tablas) y un prefijo de una letra, eso son miles de nodos con su estilo y su
        // handler: el popup tarda en aparecer y, aunque apareciera al instante, una lista
        // de 3,000 nombres no sirve para elegir nada. Cortado y ordenado alfabéticamente,
        // así que lo que se muestra es estable y predecible; para afinar, el usuario
        // escribe una letra más.
        int totalMatches = sortedMatches.size();
        if (totalMatches > MAX_SUGGESTIONS) {
            sortedMatches = sortedMatches.subList(0, MAX_SUGGESTIONS);
        }

        ContextMenu menu = new ContextMenu();
        for (String candidate : sortedMatches) {
            MenuItem item = new MenuItem(candidate);
            item.setOnAction(event -> {
                codeArea.replaceText(replaceStart, replaceEnd, candidate);
                codeArea.moveTo(replaceStart + candidate.length());
            });
            menu.getItems().add(item);
        }
        if (totalMatches > MAX_SUGGESTIONS) {
            // Deshabilitado a propósito: no es una sugerencia, es la explicación de por
            // qué la lista se corta. Sin esto, ver 50 nombres cuando hay 3,000 parecería
            // que el esquema está incompleto.
            MenuItem more = new MenuItem(
                    "… y " + (totalMatches - MAX_SUGGESTIONS) + " más — escribe otra letra para afinar");
            more.setDisable(true);
            menu.getItems().add(more);
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
