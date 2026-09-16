package com.faro.app.data;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Carga, autoguardado y guardado final de la sesión — todo lo que sabe cuándo y cómo
 * llegan los datos del usuario a disco (2026-09-15, primer paso del hallazgo C1 de
 * {@code ANALISIS_OPTIMIZACION_ESTRUCTURA.md}).
 *
 * <p><b>Por qué esta clase primero.</b> De los cuatro bloques que §C1 propone sacar de
 * {@code MainController}, este es el único que <b>no toca ningún nodo de JavaFX</b>:
 * lee objetos de datos y escribe archivos. Esa es justamente la razón de moverlo — acá
 * vive el arreglo del hallazgo A3 (la carrera entre el autoguardado y el cierre, que
 * podía dejar `connections.json` truncado y perder TODA la configuración), y mientras
 * estuvo enterrado en un controlador de 3,300 líneas no se podía testear sin arrancar
 * el toolkit. Ahora sí: ver {@code SessionPersistenceTest}.
 *
 * <p><b>Qué NO hace.</b> No captura el estado de las pestañas ni avisa al usuario: las
 * dos cosas necesitan el hilo de JavaFX, y entran como funciones
 * ({@code captureOpenTabs}, {@code onSaveError}). La frontera es deliberada — ver el
 * javadoc de {@link #autosave()}, que es donde importa.
 *
 * <h2>Por qué el registro entra como {@link Supplier} y no como referencia</h2>
 *
 * {@code preferences}, {@code favorites} y {@code credentials} son objetos mutables que
 * viven toda la sesión: guardar la referencia alcanza. El <b>registro no</b> —
 * "Importar configuración…" lo <b>reemplaza</b> por uno nuevo
 * ({@code registry = ConnectionRegistryStore.load(...).registry()}). Con una referencia
 * fija, esta clase habría seguido guardando el registro viejo después de cada
 * importación, en silencio y para siempre. Un {@code Supplier} lo lee cada vez que hace
 * falta, así que siempre guarda el que el controlador tiene <b>ahora</b>.
 */
public final class SessionPersistence {

    private static final Logger log = LoggerFactory.getLogger(SessionPersistence.class);

    /**
     * Cada cuánto reintenta el autoguardado. Reintenta sin importar si algo cambió de
     * verdad desde la última vez: es más simple y más seguro que rastrear un flag
     * "sucio" en cada punto que muta el registro, los favoritos, las credenciales o las
     * preferencias (son varios — agregar/editar/eliminar base, Descubrir bases,
     * Favoritos, Preferencias, Credenciales), y reescribir un JSON chico de más no
     * cuesta nada.
     */
    private static final long AUTOSAVE_INTERVAL_MILLIS = 120_000;

    /**
     * Tope de espera del cierre por el autoguardado en curso. Si se quedara trabado
     * (un disco de red que no responde), cerrar la app igual es mejor que dejarla
     * colgada — la escritura atómica de {@link ConnectionRegistryStore} garantiza que
     * el archivo en disco sigue siendo válido en cualquier caso.
     */
    private static final long AUTOSAVE_SHUTDOWN_WAIT_MILLIS = 5_000;

    /** Lo que se recuperó de la sesión anterior: el registro y las pestañas que estaban abiertas. */
    public record LoadedSession(ConnectionRegistry registry, List<SavedQueryTab> queryTabs) {}

    private final Supplier<ConnectionRegistry> registry;
    private final AppPreferences preferences;
    private final FavoritesStore favorites;
    private final CredentialStore credentials;
    private final Supplier<List<SavedQueryTab>> captureOpenTabs;
    private final Consumer<String> onSaveError;
    private final Path registryFile;
    private final Path credentialsFile;

    private Timer autosaveTimer;
    private final AtomicBoolean autosaveInProgress = new AtomicBoolean(false);

    /**
     * Constructor de la app: escribe en los archivos reales del usuario
     * ({@code ~/.faro/}).
     */
    public SessionPersistence(Supplier<ConnectionRegistry> registry,
            AppPreferences preferences,
            FavoritesStore favorites,
            CredentialStore credentials,
            Supplier<List<SavedQueryTab>> captureOpenTabs,
            Consumer<String> onSaveError) {
        this(registry, preferences, favorites, credentials, captureOpenTabs, onSaveError,
                ConnectionRegistryStore.DEFAULT_FILE, CredentialVaultStore.DEFAULT_FILE);
    }

    /**
     * Constructor con rutas explícitas — <b>existe para que esto se pueda testear</b>.
     *
     * <p>Las rutas por defecto son constantes que apuntan a {@code ~/.faro/}, o sea a la
     * configuración real del usuario: un test que ejercitara el autoguardado con ellas
     * le sobrescribiría sus conexiones y sus credenciales. Con las rutas como parámetro,
     * el test escribe en un directorio temporal y la lógica ejercitada es exactamente la
     * misma. Poder testear esto era la mitad del motivo de separar la clase — acá vive
     * el hallazgo A3.
     *
     * @param registry         de dónde leer el registro vigente en cada guardado — ver el
     *                         javadoc de la clase para por qué es un {@link Supplier}
     * @param captureOpenTabs  captura el estado de las pestañas abiertas; <b>se invoca
     *                         siempre en el hilo de JavaFX</b>
     * @param onSaveError      avisa al usuario que un autoguardado falló; se invoca en el
     *                         hilo de JavaFX vía el {@code uiThread} de {@link #autosave}
     */
    SessionPersistence(Supplier<ConnectionRegistry> registry,
            AppPreferences preferences,
            FavoritesStore favorites,
            CredentialStore credentials,
            Supplier<List<SavedQueryTab>> captureOpenTabs,
            Consumer<String> onSaveError,
            Path registryFile,
            Path credentialsFile) {
        this.registry = registry;
        this.preferences = preferences;
        this.favorites = favorites;
        this.credentials = credentials;
        this.captureOpenTabs = captureOpenTabs;
        this.onSaveError = onSaveError;
        this.registryFile = registryFile;
        this.credentialsFile = credentialsFile;
    }

    // ------------------------------------------------------------------
    // Carga
    // ------------------------------------------------------------------

    /**
     * Carga la sesión anterior — registro, preferencias, favoritos y pestañas abiertas.
     *
     * <p>Es estático a propósito: el registro es el <b>resultado</b> de cargar, así que
     * no puede existir antes de que exista la instancia que lo suministra. El
     * controlador carga primero y construye la instancia después, con el registro ya
     * en mano.
     *
     * <p>Si el archivo no existe (primer arranque) o está corrupto o con un formato que
     * ya no se reconoce, <b>no truena el arranque</b>: cae a un registro vacío. Sin
     * datos de ejemplo — se quitaron a pedido del usuario, ver
     * {@link ConnectionRegistry}.
     */
    public static LoadedSession load(AppPreferences preferences, FavoritesStore favorites) {
        return load(preferences, favorites, ConnectionRegistryStore.DEFAULT_FILE);
    }

    /** Variante con ruta explícita — mismo motivo que el constructor de paquete: poder testearlo. */
    static LoadedSession load(AppPreferences preferences, FavoritesStore favorites, Path file) {
        if (Files.exists(file)) {
            try {
                ConnectionRegistryStore.LoadResult result =
                        ConnectionRegistryStore.load(file, preferences, favorites);
                return new LoadedSession(result.registry(), result.queryTabs());
            } catch (IOException | RuntimeException e) {
                log.warn("No se pudo cargar {}, empezando con un registro vacío", file, e);
            }
        }
        return new LoadedSession(new ConnectionRegistry(), List.of());
    }

    /**
     * Carga las credenciales guardadas (cifradas con DPAPI, ver
     * {@link CredentialVaultStore}). Mismo criterio que {@link #load}: si el archivo no
     * existe todavía o no se pudo descifrar (por ejemplo, cambió el perfil de Windows),
     * sigue con el almacén vacío en vez de tronar el arranque.
     */
    public static void loadCredentials(CredentialStore credentials) {
        loadCredentials(credentials, CredentialVaultStore.DEFAULT_FILE);
    }

    /** Variante con ruta explícita — ver {@link #load(AppPreferences, FavoritesStore, Path)}. */
    static void loadCredentials(CredentialStore credentials, Path file) {
        if (Files.exists(file)) {
            try {
                CredentialVaultStore.load(credentials, file);
            } catch (IOException | RuntimeException e) {
                log.warn("No se pudieron cargar las credenciales guardadas", e);
            }
        }
    }

    // ------------------------------------------------------------------
    // Autoguardado
    // ------------------------------------------------------------------

    /**
     * Arranca el temporizador del autoguardado. Antes solo se guardaba al cerrar la
     * ventana, así que un cierre anormal (el proceso matado) perdía los cambios de toda
     * la sesión.
     *
     * @param uiThread cómo volver al hilo de JavaFX desde el hilo del {@code Timer} —
     *                 {@code Platform::runLater} en la app real, ejecución directa en un
     *                 test
     */
    public void startAutosave(Consumer<Runnable> uiThread) {
        autosaveTimer = new Timer("faro-autosave", true);
        autosaveTimer.scheduleAtFixedRate(new TimerTask() {
            @Override
            public void run() {
                uiThread.accept(() -> autosave(uiThread));
            }
        }, AUTOSAVE_INTERVAL_MILLIS, AUTOSAVE_INTERVAL_MILLIS);
    }

    /**
     * <b>Captura en el hilo de la UI, escritura en un hilo de fondo</b> (hallazgo #4 de
     * {@code AUDITORIA_BUGS_RENDIMIENTO.md}). Antes todo esto corría dentro del
     * {@code runLater} del temporizador — o sea que cada 2 minutos el hilo de JavaFX
     * serializaba el JSON completo (incluido el TEXTO de cada pestaña abierta), lo
     * escribía a disco, cifraba las credenciales con DPAPI y escribía un segundo
     * archivo. Con varias pestañas grandes y un perfil de usuario en disco de red, eso
     * es un tirón perceptible de la ventana en un momento arbitrario, quizá a mitad de
     * un tecleo.
     *
     * <p>La captura SÍ tiene que quedarse en el hilo de la UI: leer el texto de cada
     * pestaña y las casillas del árbol solo es seguro ahí. Lo que se movió es la parte
     * de I/O, con los datos ya capturados en mano. Por eso este método <b>debe</b>
     * invocarse en el hilo de JavaFX.
     *
     * <p>Un solo guardado a la vez: dos solapados escribirían el mismo archivo al mismo
     * tiempo; si el anterior no terminó, este ciclo simplemente se salta — el siguiente
     * tick llega en 2 minutos y no se pierde nada.
     */
    public void autosave(Consumer<Runnable> uiThread) {
        if (!autosaveInProgress.compareAndSet(false, true)) {
            log.debug("Autoguardado saltado — el anterior sigue en curso.");
            return;
        }
        List<SavedQueryTab> tabs = captureOpenTabs.get();
        Thread thread = new Thread(() -> {
            try {
                writeAll(tabs);
                log.debug("Autoguardado completo.");
            } catch (IOException | RuntimeException e) {
                log.error("Autoguardado falló", e);
                uiThread.accept(() -> onSaveError.accept("Autoguardado falló: " + e.getMessage()));
            } finally {
                autosaveInProgress.set(false);
            }
        }, "faro-autosave-write");
        thread.setDaemon(true);
        thread.start();
    }

    /**
     * Espera (acotado) a que termine el autoguardado en segundo plano antes de que el
     * cierre escriba los mismos archivos (hallazgo A3).
     *
     * <p>Cancelar el temporizador impide ticks FUTUROS pero no espera al que ya está
     * corriendo. Sin esta espera, ese hilo y el de JavaFX podían estar escribiendo
     * {@code connections.json} y {@code credentials.dat} <b>al mismo tiempo</b>. Con la
     * escritura atómica el archivo ya no queda a medias aunque pase, pero seguiría
     * siendo una carrera por cuál de los dos guardados gana — y el del cierre es el que
     * tiene el estado bueno (captura las pestañas justo antes de cerrar). Esperar lo
     * vuelve determinista.
     */
    public void awaitAutosave() {
        long deadline = System.currentTimeMillis() + AUTOSAVE_SHUTDOWN_WAIT_MILLIS;
        while (autosaveInProgress.get() && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        if (autosaveInProgress.get()) {
            log.warn("Cierre: el autoguardado en segundo plano no terminó en {} s — se guarda igual por encima.",
                    AUTOSAVE_SHUTDOWN_WAIT_MILLIS / 1000);
        }
    }

    /** Visible para tests — si hay un guardado de fondo en curso ahora mismo. */
    boolean autosaveInProgress() {
        return autosaveInProgress.get();
    }

    // ------------------------------------------------------------------
    // Cierre
    // ------------------------------------------------------------------

    /**
     * Primera mitad del cierre: deja de programar autoguardados y espera al que ya
     * estuviera en vuelo.
     *
     * <p><b>Está separada de {@link #saveNow()} a propósito</b>, aunque las dos juntas
     * serían un solo método más cómodo. El controlador cierra los pools de conexión
     * <b>entre</b> las dos, y ese orden es el que tenía el código antes de separar esta
     * clase: esperar → cerrar pools → guardar. Fusionarlas invertiría el orden (guardar
     * antes de cerrar pools) y eso es un cambio de comportamiento, no un refactor —
     * aunque se pueda argumentar que guardar antes es más seguro, esa decisión no es de
     * esta tarea.
     */
    public void stopAutosaveAndWait() {
        if (autosaveTimer != null) {
            autosaveTimer.cancel();
        }
        awaitAutosave();
    }

    /**
     * Segunda mitad del cierre: el guardado final, en el hilo que llama (el de JavaFX
     * al cerrar la ventana).
     *
     * <p>Los dos archivos se intentan por separado a propósito: que falle el de
     * credenciales no debe impedir que se guarden las conexiones, ni al revés.
     */
    public void saveNow() {
        List<SavedQueryTab> tabs = captureOpenTabs.get();
        try {
            ConnectionRegistryStore.save(
                    registry.get(), preferences, favorites, tabs, registryFile);
        } catch (IOException | RuntimeException e) {
            log.warn("No se pudieron guardar conexiones al cerrar", e);
        }
        try {
            CredentialVaultStore.save(credentials, credentialsFile);
        } catch (IOException | RuntimeException e) {
            log.warn("No se pudieron guardar las credenciales al cerrar", e);
        }
    }

    /**
     * Las dos escrituras del autoguardado, juntas y en orden. Acá NO se tragan los
     * errores por separado como en {@link #saveOnShutdown()}: el autoguardado sí quiere
     * enterarse y avisarle al usuario, que es lo que hace su {@code catch}.
     */
    private void writeAll(List<SavedQueryTab> tabs) throws IOException {
        ConnectionRegistryStore.save(
                registry.get(), preferences, favorites, tabs, registryFile);
        CredentialVaultStore.save(credentials, credentialsFile);
    }
}
