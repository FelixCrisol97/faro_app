package com.faro.app.data;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.jna.platform.win32.Crypt32Util;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Guarda/carga {@link CredentialStore} cifrado en disco con DPAPI (Windows
 * Data Protection API, vía {@code Crypt32Util} de jna-platform) — el
 * almacén de claves real que {@link ConnectionRegistryStore} dejaba
 * pendiente a propósito (ver su javadoc: ese archivo sigue sin guardar
 * credenciales, esta clase es el archivo aparte que sí lo hace). DPAPI
 * cifra atado a la cuenta de Windows del usuario actual — copiar
 * {@code credentials.dat} a otra máquina, o leerlo con otra cuenta de
 * Windows en la misma máquina, no sirve para descifrarlo. No hace falta
 * gestionar una clave propia, Windows ya la gestiona internamente.
 *
 * <p><b>Por qué DPAPI y no el Administrador de credenciales de Windows
 * completo</b> (decisión confirmada con el usuario, 2026-08-20): el
 * Administrador de credenciales (funciones nativas
 * {@code CredWrite}/{@code CredRead}) necesita bindings JNA a mano de la
 * struct {@code CREDENTIALW} — bastante más código y más superficie de
 * error (marshaling de punteros/UTF-16) que {@code Crypt32Util}, que ya
 * viene listo en {@code jna-platform} sin structs propios. A cambio, las
 * credenciales de Faro NO aparecen en Panel de Control → Administrador de
 * credenciales — viven cifradas en su propio archivo, gestionadas solo
 * desde Faro.
 *
 * <p>Igual que {@link ConnectionRegistryStore}: se escribe/lee entero de
 * una sola vez, sin diffs incrementales — el archivo es chico.
 */
public final class CredentialVaultStore {

    private static final Logger log = LoggerFactory.getLogger(CredentialVaultStore.class);

    public static final Path DEFAULT_FILE =
            Path.of(System.getProperty("user.home"), ".faro", "credentials.dat");

    private CredentialVaultStore() {
    }

    public static void save(CredentialStore credentials, Path file) throws IOException {
        JsonObject root = new JsonObject();

        JsonObject byDatabaseId = new JsonObject();
        for (Map.Entry<String, CredentialStore.Credentials> entry : credentials.entries().entrySet()) {
            byDatabaseId.add(entry.getKey(), toJson(entry.getValue()));
        }
        root.add("byDatabaseId", byDatabaseId);

        credentials.getDefault().ifPresent(def -> root.add("default", toJson(def)));

        byte[] plain = root.toString().getBytes(StandardCharsets.UTF_8);
        byte[] encrypted = Crypt32Util.cryptProtectData(plain);

        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        Files.write(file, encrypted);
        // Nunca se loguea el usuario/contraseña en sí — solo cuántas entradas se
        // guardaron, para trazabilidad sin filtrar secretos al archivo de log.
        log.info("Credenciales guardadas (cifradas, DPAPI) en {} — {} entrada(s), default={}",
                file, credentials.entries().size(), credentials.getDefault().isPresent());
    }

    public static void load(CredentialStore credentials, Path file) throws IOException {
        byte[] encrypted = Files.readAllBytes(file);
        byte[] plain = Crypt32Util.cryptUnprotectData(encrypted);
        JsonObject root = JsonParser.parseString(new String(plain, StandardCharsets.UTF_8)).getAsJsonObject();

        if (root.has("byDatabaseId")) {
            JsonObject byDatabaseId = root.getAsJsonObject("byDatabaseId");
            for (String databaseId : byDatabaseId.keySet()) {
                CredentialStore.Credentials creds = fromJson(byDatabaseId.getAsJsonObject(databaseId));
                credentials.put(databaseId, creds.user(), creds.password());
            }
        }
        if (root.has("default")) {
            CredentialStore.Credentials def = fromJson(root.getAsJsonObject("default"));
            credentials.setDefault(def.user(), def.password());
        }
        log.info("Credenciales cargadas (descifradas, DPAPI) desde {} — {} entrada(s), default={}",
                file, credentials.entries().size(), credentials.getDefault().isPresent());
    }

    private static JsonObject toJson(CredentialStore.Credentials creds) {
        JsonObject json = new JsonObject();
        json.addProperty("user", creds.user());
        json.addProperty("password", creds.password());
        return json;
    }

    private static CredentialStore.Credentials fromJson(JsonObject json) {
        return new CredentialStore.Credentials(json.get("user").getAsString(), json.get("password").getAsString());
    }
}
