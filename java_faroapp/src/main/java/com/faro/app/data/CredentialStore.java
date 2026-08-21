package com.faro.app.data;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Usuario/contraseña por base de datos — vive en memoria durante la sesión;
 * {@code CredentialVaultStore} la persiste cifrada con DPAPI en
 * {@code ~/.faro/credentials.dat} al cerrar la app y la recarga al abrirla
 * (esta clase en sí no sabe nada de cifrado ni de disco, solo guarda el
 * mapa en memoria — la persistencia vive aparte a propósito, ver
 * {@code CredentialVaultStore}). {@link #resolve} sigue el mismo criterio
 * de {@code credentialsRepositoryProvider} en la versión Flutter: override
 * (por base, capturado en Agregar/editar base de datos) → default (por
 * sesión, capturado en el diálogo "Credenciales por defecto…") → vacío.
 */
public final class CredentialStore {

    private final Map<String, Credentials> byDatabaseId = new HashMap<>();
    private Credentials defaultCredentials;

    public void put(String databaseId, String user, String password) {
        byDatabaseId.put(databaseId, new Credentials(user, password));
    }

    /** Quita el override de esa base (cae de vuelta al default de sesión, si hay uno). */
    public void remove(String databaseId) {
        byDatabaseId.remove(databaseId);
    }

    /** El override de esa base, si tiene uno propio — sin caer al default. Usado para precargar el diálogo de editar. */
    public Optional<Credentials> get(String databaseId) {
        return Optional.ofNullable(byDatabaseId.get(databaseId));
    }

    public void setDefault(String user, String password) {
        defaultCredentials = new Credentials(user, password);
    }

    /** Vista de solo lectura de todos los overrides por base — usado por {@code CredentialVaultStore} para persistir. */
    public Map<String, Credentials> entries() {
        return Collections.unmodifiableMap(byDatabaseId);
    }

    public Optional<Credentials> getDefault() {
        return Optional.ofNullable(defaultCredentials);
    }

    /** Override de esa base si existe; si no, el default de la sesión; si tampoco hay, vacío. Usado al ejecutar. */
    public Optional<Credentials> resolve(String databaseId) {
        Credentials override = byDatabaseId.get(databaseId);
        return Optional.ofNullable(override != null ? override : defaultCredentials);
    }

    public record Credentials(String user, String password) {
    }
}
