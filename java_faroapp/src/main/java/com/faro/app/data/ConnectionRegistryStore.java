package com.faro.app.data;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.faro.app.model.DatabaseEntry;
import com.faro.app.model.DbEngine;
import com.faro.app.model.Server;
import com.faro.app.model.ServerMode;
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

        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        Files.writeString(file, root.toString(), StandardCharsets.UTF_8);
        log.info("Registro guardado en {} — {} servidor(es), {} favorito(s).",
                file, registry.servers().size(), favorites.all().size());
    }

    public static LoadResult load(Path file, AppPreferences preferences, FavoritesStore favorites)
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

        log.info("Registro cargado — {} servidor(es), {} base(s) sin agrupar, {} favorito(s), {} pestaña(s) de consulta.",
                registry.servers().size(), registry.ungroupedDatabases().size(), favorites.all().size(), queryTabs.size());
        return new LoadResult(registry, queryTabs);
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
