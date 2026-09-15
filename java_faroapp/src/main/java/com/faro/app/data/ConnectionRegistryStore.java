package com.faro.app.data;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;

import com.faro.app.model.DatabaseEntry;
import com.faro.app.model.DbEngine;
import com.faro.app.model.Server;
import com.faro.app.model.ServerMode;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Guarda/carga {@link ConnectionRegistry} + {@link AppPreferences} a un
 * archivo JSON en disco — el equivalente a {@code servers_repository.dart}
 * en la versión Flutter, que antes de esto no existía en Java (todo se
 * perdía al cerrar la app).
 *
 * <p><b>Deliberadamente NO guarda credenciales</b> ({@link CredentialStore}
 * queda fuera de este archivo) — el diseño original ya documentaba
 * "credenciales en el almacén de claves del sistema, nunca en JSON plano"
 * (ver README, sección de decisiones de diseño). Meter contraseñas en
 * este mismo JSON hubiera sido una regresión de seguridad silenciosa, no
 * un atajo razonable. Esas sí se persisten, pero cifradas y en un archivo
 * aparte — ver {@link CredentialVaultStore}.
 *
 * <p>Tampoco guarda {@code DatabaseEntry#connectionStatus} — es estado de
 * sesión (si esa base respondió la última vez que se probó), no
 * configuración; cargar un estado "Conectado" de la sesión pasada sería
 * engañoso, cada arranque empieza en {@code UNKNOWN} para todas.
 *
 * <p>{@link FavoritesStore} sí se guarda acá (a diferencia de
 * credenciales) — son solo scripts SQL con un nombre, nada sensible que
 * proteger. También es lo que exporta/importa "Conexiones → Exportar/
 * Importar configuración…" (mismos métodos, apuntando a un archivo
 * elegido por el usuario en vez de {@link #DEFAULT_FILE}) — ninguno de
 * los dos incluye credenciales, a propósito.
 *
 * <p>Se escribe/lee entero de una sola vez (sin diffs incrementales) — el
 * archivo es chico (conexiones de un usuario, no miles de filas), no hace
 * falta más que eso.
 */
public final class ConnectionRegistryStore {

    private static final Logger log = LoggerFactory.getLogger(ConnectionRegistryStore.class);

    public static final Path DEFAULT_FILE =
            Path.of(System.getProperty("user.home"), ".faro", "connections.json");

    private ConnectionRegistryStore() {
    }

    /** Resultado de {@link #load} — el registro real más las pestañas de consulta guardadas (2026-08-28), separado del registro porque restaurar pestañas es tarea de {@code MainController}, no de {@link ConnectionRegistry}. Vacío para un archivo exportado (ver el javadoc de {@link SavedQueryTab}) o para uno viejo de antes de este campo. */
    public record LoadResult(ConnectionRegistry registry, List<SavedQueryTab> queryTabs) {
    }

    public static void save(
            ConnectionRegistry registry, AppPreferences preferences, FavoritesStore favorites,
            List<SavedQueryTab> queryTabs, Path file)
            throws IOException {
        save(registry, preferences, favorites, queryTabs, file, null);
    }

    /**
     * {@code credentialsToInclude} — si no es {@code null}, los usuarios y
     * contraseñas van DENTRO de este archivo, <b>en texto legible</b>.
     *
     * <p><b>Esto es una excepción deliberada al diseño</b>, pedida explícitamente por
     * el usuario (2026-09-10: "quisiera que guardara las contraseñas el json al
     * momento de exportar e importar, es muy tedioso tener que meter todas las
     * contraseñas de nuevo cuando estoy en un nuevo equipo"), y solo aplica al
     * archivo que el usuario elige en "Conexiones → Exportar configuración…". El
     * javadoc de esta clase sigue valiendo para todo lo demás: el
     * {@code connections.json} del autoguardado —el que se escribe solo cada 2
     * minutos y al cerrar— NUNCA lleva credenciales, porque se guarda con la
     * sobrecarga de 5 argumentos, que pasa {@code null} acá. Las credenciales de
     * trabajo siguen viviendo cifradas con DPAPI en {@code credentials.dat} (ver
     * {@link CredentialVaultStore}).
     *
     * <p><b>Por qué en texto legible y no cifradas:</b> DPAPI ata el cifrado a la
     * cuenta de Windows de la máquina, así que un archivo cifrado así no serviría en
     * el equipo nuevo — que es justo el caso de uso. Se le ofreció al usuario una
     * alternativa con frase maestra y eligió texto plano a cambio de no tener que
     * recordar una frase; la advertencia de que el archivo queda legible se muestra
     * en el diálogo de exportar, cada vez, antes de escribir nada.
     *
     * <p>Trátalo como un archivo con secretos: no mandarlo por correo ni dejarlo en
     * una carpeta compartida.
     */
    public static void save(
            ConnectionRegistry registry, AppPreferences preferences, FavoritesStore favorites,
            List<SavedQueryTab> queryTabs, Path file, CredentialStore credentialsToInclude)
            throws IOException {
        JsonObject root = new JsonObject();

        JsonObject prefs = new JsonObject();
        prefs.addProperty("maxConcurrentDatabases", preferences.maxConcurrentDatabases());
        prefs.addProperty("defaultPoolSize", preferences.defaultPoolSize());
        prefs.addProperty("defaultQueryTimeoutSeconds", preferences.defaultQueryTimeoutSeconds());
        prefs.addProperty("darkTheme", preferences.isDarkTheme());
        prefs.addProperty("fetchSize", preferences.fetchSize());
        prefs.addProperty("accentName", preferences.accentName());
        prefs.addProperty("editorFontSize", preferences.editorFontSize());
        prefs.addProperty("fontScaleDelta", preferences.fontScaleDelta());
        root.add("preferences", prefs);

        JsonArray favoritesJson = new JsonArray();
        for (Favorite favorite : favorites.all()) {
            JsonObject favoriteJson = new JsonObject();
            favoriteJson.addProperty("id", favorite.id());
            favoriteJson.addProperty("name", favorite.name());
            favoriteJson.addProperty("sql", favorite.sql());
            favoritesJson.add(favoriteJson);
        }
        root.add("favorites", favoritesJson);

        JsonArray servers = new JsonArray();
        for (Server server : registry.servers()) {
            JsonObject serverJson = new JsonObject();
            serverJson.addProperty("id", server.id());
            serverJson.addProperty("name", server.name());
            JsonArray databases = new JsonArray();
            for (DatabaseEntry db : server.databases()) {
                databases.add(toJson(db));
            }
            serverJson.add("databases", databases);
            servers.add(serverJson);
        }
        root.add("servers", servers);

        JsonArray ungrouped = new JsonArray();
        for (DatabaseEntry db : registry.ungroupedDatabases()) {
            ungrouped.add(toJson(db));
        }
        root.add("ungroupedDatabases", ungrouped);

        JsonArray queryTabsJson = new JsonArray();
        for (SavedQueryTab tab : queryTabs) {
            JsonObject tabJson = new JsonObject();
            tabJson.addProperty("sql", tab.sql());
            if (tab.filePath() != null) {
                tabJson.addProperty("filePath", tab.filePath());
            }
            JsonArray idsJson = new JsonArray();
            for (String id : tab.selectedDatabaseIds()) {
                idsJson.add(id);
            }
            tabJson.add("selectedDatabaseIds", idsJson);
            queryTabsJson.add(tabJson);
        }
        root.add("queryTabs", queryTabsJson);

        if (credentialsToInclude != null) {
            JsonObject credentialsJson = new JsonObject();
            for (var entry : credentialsToInclude.entries().entrySet()) {
                JsonObject creds = new JsonObject();
                creds.addProperty("user", entry.getValue().user());
                creds.addProperty("password", entry.getValue().password());
                credentialsJson.add(entry.getKey(), creds);
            }
            JsonObject credentialsRoot = new JsonObject();
            credentialsRoot.add("byDatabaseId", credentialsJson);
            credentialsToInclude.getDefault().ifPresent(def -> {
                JsonObject creds = new JsonObject();
                creds.addProperty("user", def.user());
                creds.addProperty("password", def.password());
                credentialsRoot.add("default", creds);
            });
            root.add("credentials", credentialsRoot);
        }

        writeAtomically(file, root);
        // Solo el CONTEO, nunca usuario/contraseña — mismo criterio de siempre en todo
        // el proyecto: el archivo de log no debe filtrar secretos ni cuando el usuario
        // pidió exportarlos.
        log.info("Registro guardado en {} — {} servidor(es), {} favorito(s), credenciales incluidas={}.",
                file, registry.servers().size(), favorites.all().size(),
                credentialsToInclude == null ? "no" : credentialsToInclude.entries().size() + " entrada(s)");
    }

    public static LoadResult load(Path file, AppPreferences preferences, FavoritesStore favorites)
            throws IOException {
        return load(file, preferences, favorites, null);
    }

    /**
     * {@code credentialsToFill} — si no es {@code null} y el archivo trae la sección
     * {@code "credentials"} (o sea, se exportó con la casilla marcada, ver
     * {@link #save(ConnectionRegistry, AppPreferences, FavoritesStore, List, Path,
     * CredentialStore)}), los usuarios y contraseñas se cargan ahí. Un archivo sin
     * esa sección —el caso de cualquier exportación anterior al 2026-09-10, y del
     * {@code connections.json} normal— se comporta exactamente igual que antes: no
     * toca las credenciales de la sesión.
     */
    public static LoadResult load(
            Path file, AppPreferences preferences, FavoritesStore favorites, CredentialStore credentialsToFill)
            throws IOException {
        log.info("Cargando registro desde {}", file);
        String content = Files.readString(file, StandardCharsets.UTF_8);
        JsonObject root = JsonParser.parseString(content).getAsJsonObject();

        if (root.has("preferences")) {
            JsonObject prefs = root.getAsJsonObject("preferences");
            if (prefs.has("maxConcurrentDatabases")) {
                preferences.setMaxConcurrentDatabases(prefs.get("maxConcurrentDatabases").getAsInt());
            }
            if (prefs.has("defaultPoolSize")) {
                preferences.setDefaultPoolSize(prefs.get("defaultPoolSize").getAsInt());
            }
            if (prefs.has("defaultQueryTimeoutSeconds")) {
                preferences.setDefaultQueryTimeoutSeconds(prefs.get("defaultQueryTimeoutSeconds").getAsInt());
            }
            if (prefs.has("darkTheme")) {
                preferences.setDarkTheme(prefs.get("darkTheme").getAsBoolean());
            }
            if (prefs.has("fetchSize")) {
                preferences.setFetchSize(prefs.get("fetchSize").getAsInt());
            }
            if (prefs.has("accentName")) {
                preferences.setAccentName(prefs.get("accentName").getAsString());
            }
            if (prefs.has("editorFontSize")) {
                preferences.setEditorFontSize(prefs.get("editorFontSize").getAsInt());
            }
            if (prefs.has("fontScaleDelta")) {
                preferences.setFontScaleDelta(prefs.get("fontScaleDelta").getAsInt());
            }
        }

        if (root.has("favorites")) {
            List<Favorite> loaded = new ArrayList<>();
            for (JsonElement element : root.getAsJsonArray("favorites")) {
                JsonObject favoriteJson = element.getAsJsonObject();
                loaded.add(new Favorite(
                        favoriteJson.get("id").getAsString(),
                        favoriteJson.get("name").getAsString(),
                        favoriteJson.get("sql").getAsString()));
            }
            favorites.replaceAll(loaded);
        }

        ConnectionRegistry registry = new ConnectionRegistry();
        if (root.has("servers")) {
            for (JsonElement element : root.getAsJsonArray("servers")) {
                JsonObject serverJson = element.getAsJsonObject();
                Server server = serverJson.has("id")
                        ? new Server(serverJson.get("id").getAsString(), serverJson.get("name").getAsString())
                        : new Server(serverJson.get("name").getAsString());
                for (JsonElement dbElement : serverJson.getAsJsonArray("databases")) {
                    server.databases().add(fromJson(dbElement.getAsJsonObject()));
                }
                registry.servers().add(server);
            }
        }
        if (root.has("ungroupedDatabases")) {
            for (JsonElement element : root.getAsJsonArray("ungroupedDatabases")) {
                registry.ungroupedDatabases().add(fromJson(element.getAsJsonObject()));
            }
        }

        List<SavedQueryTab> queryTabs = new ArrayList<>();
        if (root.has("queryTabs")) {
            for (JsonElement element : root.getAsJsonArray("queryTabs")) {
                JsonObject tabJson = element.getAsJsonObject();
                List<String> selectedIds = new ArrayList<>();
                if (tabJson.has("selectedDatabaseIds")) {
                    for (JsonElement idElement : tabJson.getAsJsonArray("selectedDatabaseIds")) {
                        selectedIds.add(idElement.getAsString());
                    }
                }
                queryTabs.add(new SavedQueryTab(
                        tabJson.get("sql").getAsString(),
                        tabJson.has("filePath") ? tabJson.get("filePath").getAsString() : null,
                        selectedIds));
            }
        }

        int loadedCredentials = 0;
        if (credentialsToFill != null && root.has("credentials")) {
            JsonObject credentialsRoot = root.getAsJsonObject("credentials");
            if (credentialsRoot.has("byDatabaseId")) {
                JsonObject byDatabaseId = credentialsRoot.getAsJsonObject("byDatabaseId");
                for (String databaseId : byDatabaseId.keySet()) {
                    JsonObject creds = byDatabaseId.getAsJsonObject(databaseId);
                    credentialsToFill.put(databaseId,
                            creds.get("user").getAsString(), creds.get("password").getAsString());
                    loadedCredentials++;
                }
            }
            if (credentialsRoot.has("default")) {
                JsonObject def = credentialsRoot.getAsJsonObject("default");
                credentialsToFill.setDefault(def.get("user").getAsString(), def.get("password").getAsString());
            }
        }

        log.info("Registro cargado — {} servidor(es), {} base(s) sin agrupar, {} favorito(s), "
                        + "{} pestaña(s) de consulta, {} credencial(es).",
                registry.servers().size(), registry.ungroupedDatabases().size(), favorites.all().size(),
                queryTabs.size(), loadedCredentials);
        return new LoadResult(registry, queryTabs);
    }

    /**
     * {@code disableHtmlEscaping()} para producir EXACTAMENTE los mismos bytes que el
     * {@code root.toString()} que este método reemplazó — el Gson de fábrica escapa
     * {@code <}, {@code >}, {@code &}, {@code =} y {@code '} como secuencias
     * {@code \\uXXXX}, lo que volvería ilegible cualquier SQL guardado en
     * {@code queryTabs} (un {@code WHERE x <= 10} pasaría a {@code \\u003c\\u003d}).
     * Se relee igual, pero el archivo deja de poder abrirse y entenderse a mano, que
     * es medio punto de tenerlo en JSON.
     */
    private static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    /**
     * Escribe el JSON a un archivo temporal y recién entonces lo mueve encima del
     * definitivo (2026-09-10, hallazgo A3 de
     * {@code ANALISIS_OPTIMIZACION_ESTRUCTURA.md}).
     *
     * <p><b>Qué evita.</b> Antes esto era {@code Files.writeString(file, ...)}, que
     * abre con {@code TRUNCATE_EXISTING}: <b>vacía el archivo y después escribe</b>.
     * Ese archivo tiene TODAS las conexiones, los grupos, los favoritos, las
     * preferencias y el texto de cada pestaña abierta; si la escritura se corta a la
     * mitad, lo que queda en disco es un JSON truncado y
     * {@code MainController#loadOrCreateRegistry} —que captura la excepción a
     * propósito, para no tronar el arranque— levanta la app con un registro VACÍO. O
     * sea: se pierde toda la configuración, en silencio. Formas reales de que se
     * corte: el autoguardado en segundo plano escribiendo a la vez que
     * {@code shutdown()}, el proceso matado a mitad de un autoguardado, o el hilo
     * demonio del autoguardado muriendo cuando la JVM sale.
     *
     * <p>Con el temporal + {@code ATOMIC_MOVE}, el archivo definitivo solo existe en
     * dos estados: el contenido viejo completo, o el nuevo completo. Nunca a medias.
     * {@code AtomicMoveNotSupportedException} solo puede pasar si el temporal y el
     * destino cayeran en volúmenes distintos — imposible acá porque el temporal se
     * crea como hermano del destino, pero el respaldo queda por si algún día se
     * escribe a una ruta rara (un recurso de red montado, por ejemplo).
     *
     * <p><b>Streaming, no {@code toString()}</b> (hallazgo B10): se escribe directo al
     * {@code Writer} en vez de armar todo el JSON como una sola cadena en memoria
     * primero. Con varias pestañas de scripts grandes eso era un pico de varios MB
     * cada 2 minutos, para nada.
     */
    private static void writeAtomically(Path file, JsonObject root) throws IOException {
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        Path temp = file.resolveSibling(file.getFileName() + ".tmp");
        try {
            try (BufferedWriter writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
                GSON.toJson(root, writer);
            }
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException | RuntimeException e) {
            // Sin esto, un fallo a mitad de la escritura (disco lleno, perfil de red que
            // se cae) dejaba un `connections.json.tmp` huérfano junto al archivo bueno —
            // inofensivo pero confuso de encontrar, y se vuelve a crear en cada intento
            // fallido. El archivo definitivo no se toca en ningún caso: ese es el punto
            // de escribir a un temporal (2026-09-12).
            try {
                Files.deleteIfExists(temp);
            } catch (IOException cleanupFailed) {
                log.debug("No se pudo borrar el temporal {} tras un guardado fallido", temp, cleanupFailed);
            }
            throw e;
        }
    }

    private static JsonObject toJson(DatabaseEntry db) {
        JsonObject json = new JsonObject();
        json.addProperty("id", db.id());
        json.addProperty("alias", db.alias());
        json.addProperty("host", db.host());
        json.addProperty("port", db.port());
        json.addProperty("databaseName", db.databaseName());
        json.addProperty("engine", db.engine().name());
        json.addProperty("mode", db.mode().name());
        json.addProperty("poolSize", db.poolSize());
        json.addProperty("queryTimeoutSeconds", db.queryTimeoutSeconds());
        json.addProperty("trustServerCertificate", db.trustServerCertificate());
        // Solo si está puesta — una base sin codificación forzada (el caso normal) no
        // ensucia el JSON con una llave vacía, y un archivo escrito por una versión
        // anterior sigue cargando igual (ver fromJson).
        if (!db.clientEncoding().isBlank()) {
            json.addProperty("clientEncoding", db.clientEncoding());
        }
        // Solo CONNECTED/FAILED (2026-08-28, pedido explícito del usuario: "ya se probó
        // que la conexión funciona... debería estar en verde siempre... cierro y abro
        // la app y debería estar en verde"). UNKNOWN (nunca se probó) y TESTING (estado
        // transitorio de "Probar todas las conexiones", si la app se cerrara justo a
        // mitad de esa prueba) no se guardan a propósito — cargar "Probando…" de una
        // sesión pasada se quedaría pegado ahí para siempre, sin ningún proceso real
        // detrás que lo resuelva. Sin este campo en el JSON, load() cae al default de
        // la propiedad (UNKNOWN) — mismo comportamiento de antes para bases nunca
        // probadas.
        if (db.connectionStatus() == DatabaseEntry.ConnectionStatus.CONNECTED
                || db.connectionStatus() == DatabaseEntry.ConnectionStatus.FAILED) {
            json.addProperty("connectionStatus", db.connectionStatus().name());
        }
        return json;
    }

    private static DatabaseEntry fromJson(JsonObject json) {
        DatabaseEntry db = new DatabaseEntry(
                json.get("id").getAsString(),
                json.get("alias").getAsString(),
                json.get("host").getAsString(),
                json.get("port").getAsInt(),
                json.get("databaseName").getAsString(),
                DbEngine.valueOf(json.get("engine").getAsString()),
                ServerMode.valueOf(json.get("mode").getAsString()));
        if (json.has("poolSize")) {
            db.setPoolSize(json.get("poolSize").getAsInt());
        }
        if (json.has("queryTimeoutSeconds")) {
            db.setQueryTimeoutSeconds(json.get("queryTimeoutSeconds").getAsInt());
        }
        // Sin este campo en el JSON (archivo guardado antes del 2026-09-07, cuando
        // TODAS las conexiones a SQL Server confiaban en el certificado sin verificarlo)
        // se queda el default del modelo, que es true — o sea, exactamente el
        // comportamiento que ese archivo tenía cuando se guardó. Un connections.json
        // viejo no cambia de conducta al abrirlo con esta versión.
        if (json.has("trustServerCertificate")) {
            db.setTrustServerCertificate(json.get("trustServerCertificate").getAsBoolean());
        }
        // Sin esta llave (archivo de antes del 2026-09-10, o base sin codificación
        // forzada) queda el default vacío = automática/UTF8, o sea exactamente el
        // comportamiento que ese archivo tenía cuando se guardó.
        if (json.has("clientEncoding")) {
            db.setClientEncoding(json.get("clientEncoding").getAsString());
        }
        // Un estado guardado es solo el PUNTO DE PARTIDA al abrir la app — no la
        // verdad final: en cuanto el árbol expande esta base (o corre una consulta
        // contra ella), DatabaseTreeItem/QueryExecutionService confirman o corrigen
        // este valor contra una conexión real de verdad (ver sus comentarios) — así
        // que un "verde" guardado de una contraseña que después cambió se corrige
        // solo, sin quedar engañando a nadie más que unos segundos.
        if (json.has("connectionStatus")) {
            db.setConnectionStatus(DatabaseEntry.ConnectionStatus.valueOf(json.get("connectionStatus").getAsString()));
        }
        return db;
    }
}
