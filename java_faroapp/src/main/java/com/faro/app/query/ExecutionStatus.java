package com.faro.app.query;

import java.sql.SQLException;
import java.sql.Statement;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javafx.beans.property.IntegerProperty;
import javafx.beans.property.LongProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.SimpleIntegerProperty;
import javafx.beans.property.SimpleLongProperty;
import javafx.beans.property.SimpleObjectProperty;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;

/**
 * Estado en vivo de la ejecución de una base dentro de una corrida —
 * respaldo de datos de la pestaña "Ejecución" (estado en vivo por base,
 * uno de los 4 problemas que este diseño resuelve, ver README). Todas las
 * propiedades son {@code javafx.beans.property.*} para que la
 * {@code TableView} se actualice sola cuando
 * {@link QueryExecutionService} las cambia — mutarlas siempre desde el
 * hilo de JavaFX ({@code Platform.runLater}), nunca directo desde el hilo
 * de la base que se está consultando.
 *
 * <p>{@link #attachStatement}/{@link #cancelQuery} son la cancelación real
 * — {@code Statement.cancel()} es seguro de llamar desde otro hilo
 * mientras la consulta sigue viva, que es justo lo que hace el botón
 * "Cancelar" de la fila. {@code statement} es {@code volatile} porque se
 * escribe desde el hilo que consulta esa base y se lee desde el hilo de
 * JavaFX (el clic del botón).
 *
 * <p>{@link #attachKillFallback} agrega el respaldo real
 * ({@code KILL <spid>}/{@code pg_cancel_backend(pid)}) para cuando
 * {@code Statement.cancel()} no alcanza a interrumpir la consulta en el
 * servidor (pasa en algunas condiciones de red/driver) — ver
 * {@code QueryExecutionService#attachKillFallback} para cómo se arma. Se
 * dispara en un hilo aparte para no bloquear el hilo de JavaFX si el
 * clic viene del botón "Cancelar".
 */
public class ExecutionStatus {

    private static final Logger log = LoggerFactory.getLogger(ExecutionStatus.class);

    /** Cuánto esperar tras {@code Statement.cancel()} antes de disparar el respaldo KILL/pg_cancel_backend — ver el comentario en {@link #cancelQuery()}. */
    private static final long KILL_FALLBACK_GRACE_MILLIS = 1500;

    public enum State {
        RUNNING, SUCCEEDED, FAILED, CANCELLED
    }

    private final StringProperty databaseAlias = new SimpleStringProperty();
    private final StringProperty host = new SimpleStringProperty("");
    private final ObjectProperty<State> state = new SimpleObjectProperty<>(State.RUNNING);
    private final IntegerProperty rowCount = new SimpleIntegerProperty();
    private final LongProperty elapsedMillis = new SimpleLongProperty();
    private final StringProperty message = new SimpleStringProperty("");

    private volatile Statement statement;
    private volatile boolean cancelRequested;
    private volatile Runnable killFallback;

    public ExecutionStatus(String databaseAlias, String host) {
        this.databaseAlias.set(databaseAlias);
        this.host.set(host);
    }

    public StringProperty databaseAliasProperty() {
        return databaseAlias;
    }

    public StringProperty hostProperty() {
        return host;
    }

    public ObjectProperty<State> stateProperty() {
        return state;
    }

    public IntegerProperty rowCountProperty() {
        return rowCount;
    }

    public LongProperty elapsedMillisProperty() {
        return elapsedMillis;
    }

    public StringProperty messageProperty() {
        return message;
    }

    /**
     * Llamado por {@link QueryExecutionService} apenas se crea el
     * {@code Statement} de esta base. Si ya se había pedido cancelar
     * ANTES de que el {@code Statement} existiera (clic justo en la
     * ventana entre abrir la conexión y llegar acá — hallazgo real de
     * /code-review), se cancela de inmediato acá — si no, ese clic se
     * hubiera perdido silenciosamente porque {@link #cancelQuery} no
     * tenía todavía nada que cancelar.
     */
    public void attachStatement(Statement statement) {
        this.statement = statement;
        if (statement != null && cancelRequested) {
            log.info("[{}] Cancelación pedida ANTES de que el Statement existiera — disparando ahora que ya está listo.",
                    databaseAlias.get());
            try {
                statement.cancel();
                log.info("[{}] Statement.cancel() (tardío) OK.", databaseAlias.get());
            } catch (SQLException e) {
                // Mismo motivo que en cancelQuery() — no es un error real, la consulta ya
                // pudo haber terminado sola. DEBUG (no WARN) a propósito: es el camino
                // esperado con más frecuencia, no una falla real.
                log.debug("[{}] Statement.cancel() (tardío) lanzó SQLException (probablemente ya había terminado): {}",
                        databaseAlias.get(), e.getMessage());
            }
        }
    }

    public boolean wasCancelRequested() {
        return cancelRequested;
    }

    /**
     * Llamado por {@code QueryExecutionService} apenas se conoce el pid/spid
     * real del backend que está corriendo la consulta de esta base (una
     * conexión nueva del mismo pool, no la que ejecuta la consulta). Puede
     * no llamarse nunca si no se pudo leer ese pid — en ese caso
     * {@link #cancelQuery} sigue funcionando igual, solo sin el respaldo.
     */
    public void attachKillFallback(Runnable killFallback) {
        this.killFallback = killFallback;
    }

    /** Cancela la consulta en curso de esta base — no hace nada si ya terminó o nunca hubo un {@code Statement} activo. */
    public void cancelQuery() {
        cancelRequested = true;
        Statement current = statement;
        log.info("[{}] Cancelación pedida por el usuario — Statement activo={}, respaldo KILL disponible={}.",
                databaseAlias.get(), current != null, killFallback != null);
        if (current != null) {
            try {
                current.cancel();
                log.info("[{}] Statement.cancel() enviado al driver correctamente.", databaseAlias.get());
            } catch (SQLException e) {
                // El Statement ya pudo haberse cerrado si la consulta terminó justo antes del
                // cancel — no es un error real, DEBUG a propósito (mismo criterio que
                // attachStatement() arriba).
                log.debug("[{}] Statement.cancel() lanzó SQLException (probablemente ya había terminado): {}",
                        databaseAlias.get(), e.getMessage());
            }
        }
        Runnable fallback = killFallback;
        if (fallback != null) {
            // Corre en su propio hilo — killBackend() abre una conexión nueva del pool y
            // puede bloquearse esperando una si el pool está saturado (ver README); no
            // queremos trabar el hilo de JavaFX cuando el clic viene del botón "Cancelar".
            //
            // Margen antes de disparar KILL (agregado 2026-08-25, hallazgo real en log de
            // producción del usuario): KILL <spid>/pg_cancel_backend antes se disparaba
            // SIEMPRE, al mismo tiempo que Statement.cancel(), sin esperar a ver si
            // cancel() ya bastaba solo. Para PostgreSQL no importaba (pg_cancel_backend
            // solo interrumpe la consulta, la conexión sigue viva) — pero para SQL Server,
            // KILL cierra la sesión completa a nivel de servidor, así que mataba conexiones
            // que Statement.cancel() ya había cancelado bien solo, forzando una reconexión
            // (TCP+TLS+login, ~600-700ms) la próxima vez que esa base se usara, para nada.
            // Ahora este hilo espera {@link #KILL_FALLBACK_GRACE_MILLIS} y solo dispara KILL
            // si la consulta SIGUE corriendo para entonces (`statement` es volatile y
            // QueryExecutionService#runOne lo pone en null en su `finally`, sin importar el
            // desenlace — leerlo acá es seguro y no necesita un campo nuevo).
            Thread thread = new Thread(() -> {
                try {
                    Thread.sleep(KILL_FALLBACK_GRACE_MILLIS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (statement == null) {
                    log.debug("[{}] Statement.cancel() ya bastó ({} ms) — respaldo KILL no hace falta, conexión intacta.",
                            databaseAlias.get(), KILL_FALLBACK_GRACE_MILLIS);
                    return;
                }
                log.info("[{}] Statement.cancel() no bastó tras {} ms — disparando respaldo KILL/pg_cancel_backend.",
                        databaseAlias.get(), KILL_FALLBACK_GRACE_MILLIS);
                fallback.run();
            }, "faro-kill-fallback");
            thread.setDaemon(true);
            thread.start();
        }
    }
}
