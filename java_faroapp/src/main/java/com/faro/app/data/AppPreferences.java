package com.faro.app.data;

import com.faro.app.query.QueryExecutionService;
import com.faro.app.ui.AddDatabaseDialogController;

/**
 * Preferencias de sesión — se guardan/cargan junto con
 * {@link ConnectionRegistry} en {@link ConnectionRegistryStore} (ya no es
 * "sin persistencia todavía", eso quedó resuelto el 2026-08-20). Valores
 * que antes estaban hardcodeados y ahora son reales, editables desde el
 * diálogo Preferencias → Rendimiento:
 * {@link QueryExecutionService} usa {@link #maxConcurrentDatabases()} en
 * vez de una constante fija, y {@link AddDatabaseDialogController} precarga
 * {@link #defaultPoolSize()}/{@link #defaultQueryTimeoutSeconds()} en el
 * formulario de Agregar base de datos en vez de "4"/"30" fijos.
 */
public final class AppPreferences {

    private int maxConcurrentDatabases = 8;
    private int defaultPoolSize = 4;
    private int defaultQueryTimeoutSeconds = 30;
    private boolean darkTheme;
    /** Filas por bloque pedidas al driver JDBC ({@code Statement#setFetchSize}) — ver {@code QueryExecutionService#runOne}. Nota real: PgJDBC solo lo respeta con autocommit desactivado (no es el caso hoy) y lo ignora en silencio en autocommit — el driver de SQL Server sí lo respeta siempre. Se deja igual (no truena, solo no ayuda en Postgres todavía) para no meter cambios de semántica de transacciones solo por esto. */
    private int fetchSize = 500;

    public int maxConcurrentDatabases() {
        return maxConcurrentDatabases;
    }

    public void setMaxConcurrentDatabases(int value) {
        maxConcurrentDatabases = Math.max(1, value);
    }

    public int defaultPoolSize() {
        return defaultPoolSize;
    }

    public void setDefaultPoolSize(int value) {
        defaultPoolSize = Math.max(1, value);
    }

    public int defaultQueryTimeoutSeconds() {
        return defaultQueryTimeoutSeconds;
    }

    public void setDefaultQueryTimeoutSeconds(int value) {
        defaultQueryTimeoutSeconds = Math.max(1, value);
    }

    public boolean isDarkTheme() {
        return darkTheme;
    }

    public void setDarkTheme(boolean darkTheme) {
        this.darkTheme = darkTheme;
    }

    public int fetchSize() {
        return fetchSize;
    }

    public void setFetchSize(int value) {
        fetchSize = Math.max(1, value);
    }
}
