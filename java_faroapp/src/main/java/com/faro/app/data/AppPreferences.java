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
}
