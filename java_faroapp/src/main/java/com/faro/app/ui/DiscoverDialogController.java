package com.faro.app.ui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.faro.app.data.CredentialStore;
import com.faro.app.model.DatabaseEntry;
import com.faro.app.model.ServerMode;
import com.faro.app.query.DiscoveredDatabase;
import com.faro.app.query.DiscoveryService;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javafx.concurrent.Task;
import javafx.fxml.FXML;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

/** Controlador del diálogo "Descubrir bases de datos" — ver {@link DiscoveryService}. */
public class DiscoverDialogController {

    private static final Logger log = LoggerFactory.getLogger(DiscoverDialogController.class);

    @FXML private TextField hostField;
    @FXML private TextField userField;
    @FXML private PasswordField passwordField;
    @FXML private Label searchStatusLabel;
    @FXML private VBox resultsBox;
    /** "Todas"/"Ninguna" sobre los resultados del escaneo (2026-09-10, pedido del usuario: "no tengo la opción de seleccionar todas con un solo botón o ninguna"). Nacen deshabilitados — no hay nada que marcar hasta que un escaneo devuelva algo. */
    @FXML private Button selectAllButton;
    @FXML private Button selectNoneButton;

    private final Map<CheckBox, DiscoveredDatabase> checkboxToResult = new LinkedHashMap<>();
    private final List<DatabaseEntry> added = new ArrayList<>();

    private Stage stage;
    private CredentialStore credentials;
    /**
     * Las bases YA registradas en la app — para no volver a ofrecer como "nueva" algo
     * que el usuario ya tiene (2026-09-10, reporte real: "al buscar bodegas en ese
     * mismo servidor me salen todas como si no tuviera ninguna ya existente en mi
     * json… y tengo que agregar todas y veo que están repetidas").
     *
     * <p>Antes este diálogo no recibía el registro en absoluto, así que no tenía forma
     * de saberlo: cada escaneo listaba TODAS las bases del host, y "Agregar
     * seleccionadas" creaba un {@code DatabaseEntry} nuevo —con id nuevo— por cada una
     * marcada, sin mirar si ya existía una igual. De ahí los duplicados.
     */
    private List<DatabaseEntry> existingDatabases = List.of();
    private String lastHost;
    /**
     * Generación de la búsqueda actual — si el usuario lanza una segunda
     * búsqueda antes de que termine la primera, la primera queda "vieja"
     * y sus resultados tardíos se descartan al llegar en vez de mezclarse
     * con los de la búsqueda nueva bajo el {@code lastHost} equivocado
     * (hallazgo real de /code-review).
     */
    private int searchGeneration;

    void attachStage(Stage stage) {
        this.stage = stage;
    }

    void attachCredentialStore(CredentialStore credentials) {
        this.credentials = credentials;
        credentials.getDefault().ifPresent(def -> {
            userField.setText(def.user());
            passwordField.setText(def.password());
        });
    }

    /** Ver {@link #existingDatabases}. */
    void attachExistingDatabases(List<DatabaseEntry> existing) {
        this.existingDatabases = existing == null ? List.of() : List.copyOf(existing);
    }

    /** Precarga el host — usado por "Descubrir bases en esta IP…" del menú contextual de una fila del árbol (ver ConnectionTreeCell), para no obligar a retipear un host que la app ya conoce. */
    void setInitialHost(String host) {
        hostField.setText(host);
    }

    /**
     * {@code true} si esa base ya está registrada en la app. Compara motor + host +
     * puerto + nombre de base: el escaneo solo mira el puerto por defecto de cada
     * motor, así que una base registrada a mano en OTRO puerto es otra instancia de
     * verdad y no debe contar como "ya la tengo".
     *
     * <p>El nombre se compara sin distinguir mayúsculas (SQL Server no las distingue
     * por defecto) y el host tal cual: "10.92.12.87" y un alias DNS del mismo
     * servidor no se reconocen como el mismo — límite aceptado, el escaneo siempre
     * parte del host que el usuario escribió.
     */
    private boolean alreadyRegistered(DiscoveredDatabase found) {
        return existingDatabases.stream().anyMatch(db ->
                db.engine() == found.engine()
                        && db.port() == found.engine().defaultPort()
                        && db.host().equalsIgnoreCase(lastHost)
                        && db.databaseName().equalsIgnoreCase(found.databaseName()));
    }

    List<DatabaseEntry> addedDatabases() {
        return added;
    }

    @FXML
    private void onSearch() {
        String host = hostField.getText();
        if (host == null || host.isBlank()) {
            searchStatusLabel.setText("Escribe un host.");
            return;
        }
        lastHost = host.trim();
        resultsBox.getChildren().clear();
        checkboxToResult.clear();
        selectAllButton.setDisable(true);
        selectNoneButton.setDisable(true);
        searchStatusLabel.setText("Buscando…");

        int myGeneration = ++searchGeneration;
        log.info("Descubriendo bases en '{}' (generación {})", lastHost, myGeneration);
        Task<List<DiscoveredDatabase>> task =
                DiscoveryService.discover(lastHost, userField.getText(), passwordField.getText());
        task.setOnSucceeded(e -> {
            if (myGeneration != searchGeneration) {
                // Una búsqueda más nueva ya empezó mientras esta corría — descartar, no mezclar.
                log.debug("Descarto resultados de la generación {} (vieja) — ya corre la {}.", myGeneration, searchGeneration);
                return;
            }
            List<DiscoveredDatabase> found = task.getValue();
            log.info("Descubrimiento en '{}' completo — {} base(s) encontradas.", lastHost, found.size());
            if (found.isEmpty()) {
                searchStatusLabel.setText("No se encontró ninguna base accesible en ese host.");
                return;
            }
            int nuevas = 0;
            for (DiscoveredDatabase db : found) {
                boolean yaEsta = alreadyRegistered(db);
                CheckBox checkBox = new CheckBox(db.engine().badge() + "  " + db.databaseName()
                        + (yaEsta ? "   — ya agregada" : ""));
                if (yaEsta) {
                    // Se muestra igual, pero apagada: esconderla dejaría al usuario sin
                    // saber si el escaneo no la encontró o si ya la tenía. Deshabilitada no
                    // se puede marcar, así que "Agregar seleccionadas" no la puede duplicar
                    // ni aunque se le dé a "Todas".
                    checkBox.setSelected(false);
                    checkBox.setDisable(true);
                    checkBox.getStyleClass().add("discover-already-added");
                } else {
                    nuevas++;
                }
                checkboxToResult.put(checkBox, db);
                resultsBox.getChildren().add(checkBox);
            }
            searchStatusLabel.setText(found.size() + " base(s) encontradas · " + nuevas + " nueva(s).");
            selectAllButton.setDisable(nuevas == 0);
            selectNoneButton.setDisable(nuevas == 0);
        });
        task.setOnFailed(e -> {
            log.warn("Descubrimiento en '{}' falló: {}", lastHost, task.getException().getMessage());
            searchStatusLabel.setText("Error al buscar: " + task.getException().getMessage());
        });

        Thread thread = new Thread(task, "faro-discover");
        thread.setDaemon(true);
        thread.start();
    }

    /** "Todas" — solo marca las que de verdad se pueden agregar; las ya registradas están deshabilitadas y se quedan como están. */
    @FXML
    private void onSelectAllResults() {
        setAllSelectable(true);
    }

    @FXML
    private void onSelectNoResults() {
        setAllSelectable(false);
    }

    private void setAllSelectable(boolean selected) {
        for (CheckBox checkBox : checkboxToResult.keySet()) {
            if (!checkBox.isDisabled()) {
                checkBox.setSelected(selected);
            }
        }
    }

    @FXML
    private void onAddSelected() {
        for (Map.Entry<CheckBox, DiscoveredDatabase> entry : checkboxToResult.entrySet()) {
            // isDisabled() además de isSelected(): una casilla deshabilitada no se puede
            // marcar desde la UI, pero este chequeo deja explícito que una base ya
            // registrada NUNCA se duplica, sin depender de un detalle de la vista.
            if (!entry.getKey().isSelected() || entry.getKey().isDisabled()) {
                continue;
            }
            DiscoveredDatabase found = entry.getValue();
            DatabaseEntry dbEntry = new DatabaseEntry(found.databaseName(), lastHost, found.engine().defaultPort(),
                    found.databaseName(), found.engine(), ServerMode.READ_ONLY);
            credentials.put(dbEntry.id(), userField.getText(), passwordField.getText());
            added.add(dbEntry);
        }
        log.info("{} base(s) agregadas desde el descubrimiento de '{}'.", added.size(), lastHost);
        stage.close();
    }

    @FXML
    private void onClose() {
        stage.close();
    }
}
