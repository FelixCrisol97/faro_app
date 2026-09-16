package com.faro.app.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.faro.app.model.DatabaseEntry;
import com.faro.app.model.DbEngine;
import com.faro.app.model.ServerMode;

/**
 * {@link SessionPersistence} — carga, autoguardado y guardado final (2026-09-15).
 *
 * <p><b>Estos tests no existían y no podían existir.</b> Toda esta lógica vivía dentro
 * de {@code MainController}, en métodos privados de una clase que necesita un FXML
 * cargado y el toolkit de JavaFX arrancado — y la suite permanente no arranca JavaFX a
 * propósito. Separarla fue justamente para poder escribirlos: acá vive el hallazgo
 * <b>A3</b>, el de peor consecuencia de todo el análisis (perder TODA la configuración
 * del usuario), y hasta ahora su arreglo no tenía ni una sola prueba automática.
 *
 * <p>Escriben en un {@code @TempDir}, nunca en {@code ~/.faro/} — para eso existe el
 * constructor con rutas explícitas. Un test que usara las rutas por defecto le borraría
 * las conexiones al usuario.
 *
 * <p>El {@code uiThread} se pasa como ejecución directa ({@code Runnable::run}) en vez
 * de {@code Platform.runLater}: la clase no depende de JavaFX justamente porque ese
 * salto de hilo entra como parámetro.
 */
class SessionPersistenceTest {

    private static final Consumer<Runnable> DIRECTO = Runnable::run;

    private static DatabaseEntry db(String alias) {
        return new DatabaseEntry(alias, "10.0.0.1", 5432, "bodega", DbEngine.POSTGRES, ServerMode.READ_ONLY);
    }

    /** Arma una instancia con todo apuntando al directorio temporal del test. */
    private static Fixture fixture(Path dir, ConnectionRegistry registry) {
        return new Fixture(dir, registry);
    }

    /**
     * Junta lo que cada test necesita — el registro (mutable, para poder reemplazarlo),
     * los archivos temporales, y lo que la clase reportó por sus dos funciones de salida.
     */
    private static final class Fixture {
        final Path registryFile;
        final Path credentialsFile;
        final AppPreferences preferences = new AppPreferences();
        final FavoritesStore favorites = new FavoritesStore();
        final CredentialStore credentials = new CredentialStore();
        final List<String> erroresReportados = new ArrayList<>();
        final AtomicInteger capturasDePestanas = new AtomicInteger();
        List<SavedQueryTab> tabs = List.of();
        ConnectionRegistry registry;
        final SessionPersistence session;

        Fixture(Path dir, ConnectionRegistry registry) {
            this.registryFile = dir.resolve("connections.json");
            this.credentialsFile = dir.resolve("credentials.dat");
            this.registry = registry;
            this.session = new SessionPersistence(
                    () -> this.registry,
                    preferences, favorites, credentials,
                    () -> {
                        capturasDePestanas.incrementAndGet();
                        return tabs;
                    },
                    erroresReportados::add,
                    registryFile, credentialsFile);
        }
    }

    // ------------------------------------------------------------------
    // Guardado final
    // ------------------------------------------------------------------

    @Test
    void elGuardadoFinalEscribeLosDosArchivos(@TempDir Path dir) {
        ConnectionRegistry registry = new ConnectionRegistry();
        registry.ungroupedDatabases().add(db("bodega-01"));
        Fixture f = fixture(dir, registry);
        f.credentials.setDefault("faro", "secreto");

        f.session.saveNow();

        assertTrue(Files.exists(f.registryFile), "no se escribió connections.json");
        assertTrue(Files.exists(f.credentialsFile), "no se escribió credentials.dat");
    }

    /** Ida y vuelta completa: lo guardado se vuelve a leer igual. */
    @Test
    void loGuardadoSeVuelveALeerIgual(@TempDir Path dir) {
        ConnectionRegistry registry = new ConnectionRegistry();
        registry.ungroupedDatabases().add(db("bodega-01"));
        registry.ungroupedDatabases().add(db("bodega-02"));
        Fixture f = fixture(dir, registry);
        f.tabs = List.of(new SavedQueryTab("SELECT 1", null, List.of()));

        f.session.saveNow();
        SessionPersistence.LoadedSession recargado =
                SessionPersistence.load(new AppPreferences(), new FavoritesStore(), f.registryFile);

        assertEquals(2, recargado.registry().allDatabases().size());
        assertEquals(1, recargado.queryTabs().size());
        assertEquals("SELECT 1", recargado.queryTabs().get(0).sql());
    }

    /**
     * El caso que motivó el {@link java.util.function.Supplier}: "Importar
     * configuración…" <b>reemplaza</b> el registro por otro objeto. Con una referencia
     * guardada en el constructor, esta clase habría seguido escribiendo el registro
     * viejo después de cada importación — en silencio, y para siempre.
     */
    @Test
    void guardaElRegistroVigenteAunqueLoHayanReemplazado(@TempDir Path dir) {
        ConnectionRegistry original = new ConnectionRegistry();
        original.ungroupedDatabases().add(db("vieja"));
        Fixture f = fixture(dir, original);

        ConnectionRegistry importado = new ConnectionRegistry();
        importado.ungroupedDatabases().add(db("nueva-1"));
        importado.ungroupedDatabases().add(db("nueva-2"));
        f.registry = importado;   // lo que hace onImportConfig
        f.session.saveNow();

        SessionPersistence.LoadedSession recargado =
                SessionPersistence.load(new AppPreferences(), new FavoritesStore(), f.registryFile);
        List<String> alias = recargado.registry().allDatabases().stream().map(DatabaseEntry::alias).toList();

        assertEquals(List.of("nueva-1", "nueva-2"), alias,
                "guardó el registro viejo — el Supplier no se está releyendo");
    }

    // ------------------------------------------------------------------
    // Carga
    // ------------------------------------------------------------------

    /** Primer arranque: no hay archivo y no debe tronar. */
    @Test
    void sinArchivoCargaUnRegistroVacio(@TempDir Path dir) {
        SessionPersistence.LoadedSession cargado = SessionPersistence.load(
                new AppPreferences(), new FavoritesStore(), dir.resolve("no-existe.json"));

        assertTrue(cargado.registry().allDatabases().isEmpty());
        assertTrue(cargado.queryTabs().isEmpty());
    }

    /**
     * Un JSON corrupto tampoco puede tronar el arranque — es exactamente el escenario
     * que dejaba A3 (archivo truncado a la mitad de una escritura no atómica). Antes de
     * la escritura atómica esto pasaba de verdad; el arreglo lo previene, pero un
     * archivo dañado por cualquier otra causa tiene que seguir dando registro vacío en
     * vez de una ventana que no abre.
     */
    @Test
    void unArchivoCorruptoCaeARegistroVacioEnVezDeTronar(@TempDir Path dir) throws IOException {
        Path corrupto = dir.resolve("connections.json");
        Files.writeString(corrupto, "{\"servers\": [{\"name\": \"a mitad de escrib");

        SessionPersistence.LoadedSession cargado =
                SessionPersistence.load(new AppPreferences(), new FavoritesStore(), corrupto);

        assertTrue(cargado.registry().allDatabases().isEmpty());
    }

    @Test
    void sinArchivoDeCredencialesNoTruena(@TempDir Path dir) {
        CredentialStore credentials = new CredentialStore();

        SessionPersistence.loadCredentials(credentials, dir.resolve("no-existe.dat"));

        assertTrue(credentials.entries().isEmpty());
    }

    // ------------------------------------------------------------------
    // Autoguardado — el candado y la espera del cierre (hallazgo A3)
    // ------------------------------------------------------------------

    /**
     * La captura de pestañas tiene que ocurrir en el hilo que llama (el de la UI), no
     * dentro del hilo de escritura: leer el texto de cada {@code CodeArea} solo es
     * seguro en el de JavaFX. Se comprueba que al volver de {@code autosave()} la
     * captura ya ocurrió — si estuviera dentro del hilo de fondo, todavía no.
     */
    @Test
    void laCapturaDePestanasOcurreAntesDeSoltarElHiloDeFondo(@TempDir Path dir) {
        Fixture f = fixture(dir, new ConnectionRegistry());

        f.session.autosave(DIRECTO);

        assertEquals(1, f.capturasDePestanas.get(),
                "la captura no ocurrió en el hilo que llama");
        f.session.awaitAutosave();
    }

    /**
     * Dos autoguardados solapados escribirían el mismo archivo al mismo tiempo. El
     * segundo tiene que saltarse, no encolarse: el siguiente tick llega en 2 minutos y
     * no se pierde nada.
     */
    @Test
    void unSegundoAutoguardadoSeSaltaSiElPrimeroSigueEnCurso(@TempDir Path dir) {
        Fixture f = fixture(dir, new ConnectionRegistry());
        f.session.autosave(DIRECTO);
        int capturasTrasElPrimero = f.capturasDePestanas.get();

        // Si el primero sigue en vuelo, el segundo ni siquiera captura.
        if (f.session.autosaveInProgress()) {
            f.session.autosave(DIRECTO);
            assertEquals(capturasTrasElPrimero, f.capturasDePestanas.get(),
                    "el segundo autoguardado no se saltó");
        }

        f.session.awaitAutosave();
        assertFalse(f.session.autosaveInProgress(), "el candado quedó trabado");
    }

    /**
     * {@code awaitAutosave} es la mitad del arreglo de A3: el cierre tiene que esperar
     * al autoguardado en vuelo, porque si no los dos escriben los mismos archivos a la
     * vez y gana cualquiera — y el del cierre es el que tiene el estado bueno.
     */
    @Test
    void laEsperaDelCierreDejaElCandadoLibre(@TempDir Path dir) {
        Fixture f = fixture(dir, new ConnectionRegistry());
        f.session.autosave(DIRECTO);

        f.session.awaitAutosave();

        assertFalse(f.session.autosaveInProgress());
        assertTrue(Files.exists(f.registryFile), "el autoguardado no llegó a escribir");
    }

    /**
     * Si la escritura falla, el usuario tiene que enterarse — y sobre todo, el candado
     * tiene que soltarse igual. Un candado trabado dejaría la app sin autoguardar el
     * resto de la sesión, en silencio.
     */
    @Test
    void siLaEscrituraFallaAvisaYSueltaElCandado(@TempDir Path dir) throws IOException {
        // Un directorio donde va el archivo: escribir ahí falla con IOException.
        Path ocupado = dir.resolve("connections.json");
        Files.createDirectory(ocupado);
        Fixture f = fixture(dir, new ConnectionRegistry());

        f.session.autosave(DIRECTO);
        f.session.awaitAutosave();

        assertFalse(f.session.autosaveInProgress(), "el candado quedó trabado tras el fallo");
        assertEquals(1, f.erroresReportados.size(), "no se avisó del fallo");
        assertTrue(f.erroresReportados.get(0).startsWith("Autoguardado falló"),
                "mensaje inesperado: " + f.erroresReportados.get(0));
    }
}
