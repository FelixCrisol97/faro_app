package com.faro.app.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

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
import javafx.concurrent.Task;
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
 * para esa misma base, ya salen tabla/columnas también. Caché en memoria,
 * por id de base, vive mientras la app esté abierta (sin invalidación si
 * el esquema cambia en caliente del lado del servidor — límite conocido,
 * aceptable para v0; si hace falta refrescar, alcanza con reiniciar la
 * app o marcar/desmarcar la base).
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

    /** Esquema ya leído, por id de base — ver el javadoc de la clase. */
    private static final Map<String, SchemaInfo> schemaCache = new ConcurrentHashMap<>();
    /** Bases con una carga de esquema en curso — evita pedir el mismo esquema dos veces si el usuario aprieta Ctrl+Espacio varias veces seguidas antes de que la primera termine. */
    private static final Set<String> schemaLoading = ConcurrentHashMap.newKeySet();

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
            SchemaInfo schema = schemaCache.get(activeDb.id());
            if (schema != null) {
                for (String name : schema.tableNames()) {
                    if (name.toUpperCase(Locale.ROOT).startsWith(prefixUpper) && !matches.contains(name)) {
                        matches.add(name);
                    }
                }
                for (String name : schema.allColumnNames()) {
                    if (name.toUpperCase(Locale.ROOT).startsWith(prefixUpper) && !matches.contains(name)) {
                        matches.add(name);
                    }
                }
            } else {
                loadSchemaInBackground(activeDb, credentials, pool);
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

    /**
     * Dispara la lectura del esquema en un hilo aparte y la deja en
     * {@link #schemaCache} para la próxima invocación — nunca actualiza un
     * popup ya abierto (esta misma invocación ya se mostró solo con
     * palabras clave). {@code schemaLoading} evita mandar la misma
     * consulta dos veces si el usuario pide autocompletado repetidas veces
     * mientras la primera carga sigue en curso.
     */
    private static void loadSchemaInBackground(DatabaseEntry db, CredentialStore credentials, ConnectionPoolManager pool) {
        if (!schemaLoading.add(db.id())) {
            return;
        }
        log.debug("[{}] Sin esquema en caché — disparando carga en segundo plano.", db.alias());
        Task<SchemaInfo> task = SchemaIntrospector.fetch(db, credentials, pool);
        task.setOnSucceeded(e -> {
            schemaCache.put(db.id(), task.getValue());
            schemaLoading.remove(db.id());
        });
        task.setOnFailed(e -> {
            // Antes se descartaba en silencio — si el autocompletado nunca mostraba
            // tabla/columnas reales para una base, no había ningún rastro de por qué.
            log.warn("[{}] No se pudo cargar el esquema para autocompletado", db.alias(), task.getException());
            schemaLoading.remove(db.id());
        });
        Thread thread = new Thread(task, "faro-schema-fetch");
        thread.setDaemon(true);
        thread.start();
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
