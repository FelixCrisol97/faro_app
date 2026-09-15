package com.faro.app.data;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import com.faro.app.model.DatabaseEntry;
import com.faro.app.model.DbEngine;
import com.faro.app.model.ServerMode;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Ida y vuelta de {@link ConnectionRegistryStore} para el campo
 * {@code trustServerCertificate} (2026-09-07, hallazgo #12 de
 * {@code AUDITORIA_BUGS_RENDIMIENTO.md}) — con foco en el caso que de verdad
 * importa: que un {@code connections.json} guardado ANTES de que este campo
 * existiera siga comportándose exactamente igual al abrirlo con esta versión.
 */
class ConnectionRegistryStoreTest {

    @TempDir
    Path tempDir;

    private static DatabaseEntry sqlServerEntry() {
        return new DatabaseEntry(
                "id-1", "Bodega Centro", "10.20.4.10", 1433, "bodega",
                DbEngine.SQL_SERVER, ServerMode.READ_ONLY);
    }

    private static ConnectionRegistryStore.LoadResult saveAndLoad(ConnectionRegistry registry, Path file)
            throws IOException {
        ConnectionRegistryStore.save(registry, new AppPreferences(), new FavoritesStore(), List.of(), file);
        return ConnectionRegistryStore.load(file, new AppPreferences(), new FavoritesStore());
    }

    @Test
    void laCasillaDesmarcadaSobreviveGuardarYCargar() throws IOException {
        ConnectionRegistry registry = new ConnectionRegistry();
        DatabaseEntry entry = sqlServerEntry();
        entry.setTrustServerCertificate(false);
        registry.ungroupedDatabases().add(entry);

        ConnectionRegistryStore.LoadResult loaded = saveAndLoad(registry, tempDir.resolve("connections.json"));

        DatabaseEntry restored = loaded.registry().ungroupedDatabases().get(0);
        assertFalse(restored.trustServerCertificate(), "desmarcada tiene que seguir desmarcada al reabrir la app");
        assertTrue(restored.jdbcUrl().contains("trustServerCertificate=false"));
    }

    @Test
    void laCasillaMarcadaSobreviveGuardarYCargar() throws IOException {
        ConnectionRegistry registry = new ConnectionRegistry();
        registry.ungroupedDatabases().add(sqlServerEntry());

        ConnectionRegistryStore.LoadResult loaded = saveAndLoad(registry, tempDir.resolve("connections.json"));

        assertTrue(loaded.registry().ungroupedDatabases().get(0).trustServerCertificate());
    }

    /**
     * El caso de compatibilidad real: un archivo escrito por una versión anterior
     * no trae la llave {@code trustServerCertificate}. Tiene que caer al default
     * {@code true} — o sea, exactamente el comportamiento que ese archivo tenía
     * cuando se guardó, no un apagón silencioso que rompa la conexión.
     */
    @Test
    void unArchivoViejoSinLaLlaveCaeAConfiarComoAntes() throws IOException {
        Path file = tempDir.resolve("connections-viejo.json");
        Files.writeString(file, """
                {
                  "preferences": {},
                  "favorites": [],
                  "servers": [],
                  "ungroupedDatabases": [
                    {
                      "id": "id-1", "alias": "Bodega Centro", "host": "10.20.4.10", "port": 1433,
                      "databaseName": "bodega", "engine": "SQL_SERVER", "mode": "READ_ONLY",
                      "poolSize": 4, "queryTimeoutSeconds": 30
                    }
                  ]
                }
                """, StandardCharsets.UTF_8);

        ConnectionRegistryStore.LoadResult loaded =
                ConnectionRegistryStore.load(file, new AppPreferences(), new FavoritesStore());

        DatabaseEntry restored = loaded.registry().ungroupedDatabases().get(0);
        assertTrue(restored.trustServerCertificate(), "un archivo de antes de este campo no debe cambiar de conducta");
        assertEquals(
                "jdbc:sqlserver://10.20.4.10:1433;databaseName=bodega;encrypt=true;trustServerCertificate=true",
                restored.jdbcUrl());
        assertEquals("", restored.clientEncoding(), "sin la llave, la codificación queda en automática");
    }

    // ---- Codificación del cliente (2026-09-10) ----

    @Test
    void laCodificacionSobreviveLaIdaYVuelta() throws IOException {
        ConnectionRegistry registry = new ConnectionRegistry();
        DatabaseEntry entry = new DatabaseEntry(
                "id-pg", "bodega", "10.92.12.87", 5432, "bodega", DbEngine.POSTGRES, ServerMode.READ_ONLY);
        entry.setClientEncoding("LATIN1");
        registry.ungroupedDatabases().add(entry);

        ConnectionRegistryStore.LoadResult loaded = saveAndLoad(registry, tempDir.resolve("conexiones.json"));

        assertEquals("LATIN1", loaded.registry().ungroupedDatabases().get(0).clientEncoding());
    }

    /** Una base sin codificación forzada no debe ensuciar el JSON con una llave vacía. */
    @Test
    void sinCodificacionNoSeEscribeLaLlave() throws IOException {
        ConnectionRegistry registry = new ConnectionRegistry();
        registry.ungroupedDatabases().add(sqlServerEntry());
        Path file = tempDir.resolve("conexiones.json");

        ConnectionRegistryStore.save(registry, new AppPreferences(), new FavoritesStore(), List.of(), file);

        assertFalse(Files.readString(file, StandardCharsets.UTF_8).contains("clientEncoding"));
    }

    // ---- Credenciales en el archivo exportado (2026-09-10) ----
    //
    // Excepción DELIBERADA al diseño, pedida explícitamente por el usuario, y acotada
    // al archivo que él elige en "Exportar configuración…". Estos tests fijan las dos
    // mitades que hacen que esa excepción sea segura: que solo pasa cuando se pide, y
    // que el guardado normal NUNCA las incluye.

    private static CredentialStore credentialStoreWith(String databaseId) {
        CredentialStore credentials = new CredentialStore();
        credentials.put(databaseId, "usuario_bodega", "clave-secreta-123");
        credentials.setDefault("usuario_default", "clave-default-456");
        return credentials;
    }

    @Test
    void exportarConCredencialesLasEscribeYLasVuelveALeer() throws IOException {
        ConnectionRegistry registry = new ConnectionRegistry();
        registry.ungroupedDatabases().add(sqlServerEntry());
        Path file = tempDir.resolve("exportado.json");

        ConnectionRegistryStore.save(registry, new AppPreferences(), new FavoritesStore(), List.of(), file,
                credentialStoreWith("id-1"));

        CredentialStore reimported = new CredentialStore();
        ConnectionRegistryStore.load(file, new AppPreferences(), new FavoritesStore(), reimported);

        assertEquals("usuario_bodega", reimported.get("id-1").orElseThrow().user());
        assertEquals("clave-secreta-123", reimported.get("id-1").orElseThrow().password());
        assertEquals("usuario_default", reimported.getDefault().orElseThrow().user());
        assertEquals("clave-default-456", reimported.getDefault().orElseThrow().password());
    }

    /**
     * La mitad que de verdad protege: el guardado de siempre —el que usan el
     * autoguardado cada 2 minutos y el cierre de la app sobre
     * {@code ~/.faro/connections.json}— NO puede llevar credenciales. Si esto se
     * rompiera, cada usuario terminaría con sus contraseñas en claro en disco sin
     * haberlo pedido nunca.
     */
    @Test
    void elGuardadoNormalNuncaEscribeCredenciales() throws IOException {
        ConnectionRegistry registry = new ConnectionRegistry();
        registry.ungroupedDatabases().add(sqlServerEntry());
        Path file = tempDir.resolve("connections.json");

        ConnectionRegistryStore.save(registry, new AppPreferences(), new FavoritesStore(), List.of(), file);

        String json = Files.readString(file, StandardCharsets.UTF_8);
        assertFalse(json.contains("credentials"), "el archivo normal no debe traer la sección de credenciales");
        assertFalse(json.contains("clave-secreta-123"));
        assertFalse(json.contains("usuario_bodega"));
    }

    /** Importar un archivo SIN credenciales no debe pisar ni borrar las que ya tenga la sesión. */
    @Test
    void importarUnArchivoSinCredencialesNoTocaLasDeLaSesion() throws IOException {
        ConnectionRegistry registry = new ConnectionRegistry();
        registry.ungroupedDatabases().add(sqlServerEntry());
        Path file = tempDir.resolve("sin-credenciales.json");
        ConnectionRegistryStore.save(registry, new AppPreferences(), new FavoritesStore(), List.of(), file);

        CredentialStore existentes = credentialStoreWith("id-1");
        ConnectionRegistryStore.load(file, new AppPreferences(), new FavoritesStore(), existentes);

        assertEquals("clave-secreta-123", existentes.get("id-1").orElseThrow().password());
    }
}
