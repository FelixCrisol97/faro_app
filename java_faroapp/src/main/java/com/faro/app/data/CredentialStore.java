package com.faro.app.data;

import java.util.Collections;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

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

    // ConcurrentHashMap + volatile (2026-09-07, hallazgo #7 de
    // AUDITORIA_BUGS_RENDIMIENTO.md) — este almacén se ESCRIBE desde el hilo de JavaFX
    // (diálogos de credenciales/editar base) y se LEE desde hilos de fondo:
    // credentials.resolve(...) dentro de QueryExecutionService#runOne (uno por base, en
    // paralelo) y en cada Task de SchemaIntrospector. Un HashMap sin sincronizar leído
    // así es una carrera de datos por contrato, aunque en la práctica arrancar el hilo/
    // encolar en el executor suele dar la barrera de memoria que la salva — el resto del
    // proyecto sí es explícito con esto (ver los volatile comentados de DatabaseEntry),
    // no había razón para que este fuera la excepción.
    private final Map<String, Credentials> byDatabaseId = new ConcurrentHashMap<>();
    private volatile Credentials defaultCredentials;

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
