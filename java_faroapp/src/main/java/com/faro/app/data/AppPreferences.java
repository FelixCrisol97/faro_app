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
    /**
     * Filas por bloque pedidas al driver JDBC ({@code Statement#setFetchSize}) — ver
     * {@code QueryExecutionService#runOne}.
     *
     * <p><b>Ya tiene efecto en los dos motores</b> (2026-09-14). SQL Server siempre lo
     * respetó. PostgreSQL lo ignoraba en silencio porque su driver solo usa cursor con
     * el autocommit desactivado, cosa que la app no hacía — así que el resultado
     * completo se materializaba dentro del driver antes de empezar a leerlo. Desde
     * {@code QueryExecutionService#shouldUseCursor}, los scripts de SOLO LECTURA contra
     * PostgreSQL sí abren cursor; los que escriben siguen en autocommit, para no
     * convertir un script de varias sentencias en una transacción todo-o-nada que
     * nadie pidió.
     */
    private int fetchSize = 500;
    /**
     * Tope de filas que se CARGAN EN MEMORIA para mostrar en el grid de Resultados
     * (2026-09-20). No es un tope de la consulta: es hasta dónde lee la app.
     *
     * <p><b>Por qué existe.</b> {@code TableView} virtualiza qué se <i>renderiza</i>, no
     * qué se <i>guarda</i>: el resultado completo vive en el heap. Una corrida contra 6
     * bodegas × 500.000 filas son 3 millones de {@code Object[]} vivos a la vez, que es
     * el {@code OutOfMemoryError} que el usuario reportó de verdad. Es el techo
     * estructural que documentaba {@code OPTIMIZACION_RENDIMIENTO.md} §5.1.
     *
     * <p><b>Y por qué cortar es además más rápido:</b> al llegar al tope la app deja de
     * leer el {@code ResultSet}, así que el resto de las filas ni siquiera viajan por la
     * red. No se cuenta cuántas quedaron fuera a propósito — contarlas exigiría traerlas
     * igual, que es justo lo que se quiere evitar; el aviso dice "hay más", no un total
     * inventado.
     *
     * <p><b>Nunca se recorta en silencio.</b> El grid avisa cuando pasó, y "Exportar CSV"
     * no depende de este tope: exporta el resultado COMPLETO leyéndolo de nuevo de la
     * base y escribiéndolo directo a disco (ver {@code CsvExportService}).
     *
     * <p>Los 200.000 por defecto salen de la cuenta de memoria real: ~250 bytes por fila
     * de 10 columnas de texto corto son unos 50 MB, holgado dentro del {@code -Xmx4g} del
     * empaquetado. Es configurable en Preferencias → Rendimiento.
     */
    private int maxDisplayRows = 200_000;
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
        defaultPoolSize = Math.max(DatabaseEntry.MIN_POOL_SIZE, value);
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

    public int maxDisplayRows() {
        return maxDisplayRows;
    }

    /**
     * Piso de 1.000 filas: un tope más chico haría el grid inútil sin que el usuario
     * entienda por qué, y el riesgo de memoria que esto cubre no empieza hasta cientos
     * de miles de filas.
     */
    public void setMaxDisplayRows(int value) {
        maxDisplayRows = Math.max(1_000, value);
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
