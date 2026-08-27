package com.faro.app.data;

import com.faro.app.model.DatabaseEntry;
import com.faro.app.query.QueryExecutionService;
import com.faro.app.ui.AccentPalette;
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
    /** Uno de {@link AccentPalette#NAMES} — ver Preferencias → Apariencia. */
    private String accentName = "indigo";
    /** Tamaño de fuente del editor SQL, en px — SOLO el editor, aparte del resto de la interfaz (ver {@link #fontScaleDelta}). Mismo valor que estaba fijo en `.sql-editor` de app.css antes de esto. */
    private int editorFontSize = 14;
    /**
     * Delta de tamaño de fuente para el RESTO de la interfaz (todo menos el
     * editor SQL, ver {@link #editorFontSize}) — pedido explícito del
     * usuario (2026-08-26), Preferencias → Apariencia. Rango -5..5, sumado
     * a cada uno de los ~36 valores literales de {@code -fx-font-size} de
     * {@code app.css} (ver {@code Theme#scaledAppCssUri} — {@code -fx-font-size}
     * no admite variables {@code -token-*} como los colores, así que el
     * desplazamiento se resuelve regenerando una copia del archivo, no con
     * el mismo mecanismo de lookup), no un valor absoluto — un delta
     * negativo achica todos por igual, uno positivo los agranda. Default
     * -1: el usuario pidió "bajemos 1-2px para todo" como
     * punto de partida, antes incluso de tener el control nuevo para
     * ajustarlo más.
     *
     * <p><b>No es lo mismo que el "zoom global" que se probó 3 veces y se
     * quitó por completo el 2026-08-22</b> (ver `CONTEXTO_SESIONES.md`) —
     * aquel escalaba TODA la interfaz con una transformación
     * (`scaleX`/`scaleY` sobre la raíz de la escena: texto, íconos,
     * botones, espaciados, todo proporcional) y dejaba huecos en blanco
     * reales al redimensionar la ventana, un problema de fondo que nunca
     * se resolvió bien. Esto es distinto en su mecanismo: cambia el TEXTO
     * únicamente, vía los tokens de tamaño de fuente reales de la hoja de
     * estilos — íconos/botones/espaciados quedan exactamente igual.
     */
    private int fontScaleDelta = -1;

    public int maxConcurrentDatabases() {
        return maxConcurrentDatabases;
    }

    public void setMaxConcurrentDatabases(int value) {
        maxConcurrentDatabases = Math.max(1, value);
    }

    public int defaultPoolSize() {
        return defaultPoolSize;
    }

    /** Piso duro de {@link com.faro.app.model.DatabaseEntry#MIN_POOL_SIZE} — ver ese javadoc para el motivo real (el respaldo de cancelación necesita una segunda conexión libre). */
    public void setDefaultPoolSize(int value) {
        defaultPoolSize = Math.max(com.faro.app.model.DatabaseEntry.MIN_POOL_SIZE, value);
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

    public String accentName() {
        return accentName;
    }

    /** Nombre desconocido (ej. un JSON de preferencias viejo/corrupto con un valor que ya no existe) cae a "indigo" en vez de guardar basura — mismo criterio defensivo que {@link AccentPalette#tokens} ya aplica al leerlo. */
    public void setAccentName(String value) {
        accentName = value != null && AccentPalette.NAMES.contains(value) ? value : "indigo";
    }

    public int editorFontSize() {
        return editorFontSize;
    }

    public void setEditorFontSize(int value) {
        editorFontSize = Math.max(10, Math.min(24, value));
    }

    public int fontScaleDelta() {
        return fontScaleDelta;
    }

    public void setFontScaleDelta(int value) {
        fontScaleDelta = Math.max(-5, Math.min(5, value));
    }
}
