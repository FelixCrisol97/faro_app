package com.faro.app.query;

import java.sql.SQLException;
import java.sql.Statement;

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

    public enum State {
        RUNNING, SUCCEEDED, FAILED, CANCELLED
    }

    private final StringProperty databaseAlias = new SimpleStringProperty();
    private final ObjectProperty<State> state = new SimpleObjectProperty<>(State.RUNNING);
    private final IntegerProperty rowCount = new SimpleIntegerProperty();
    private final LongProperty elapsedMillis = new SimpleLongProperty();
    private final StringProperty message = new SimpleStringProperty("");

    private volatile Statement statement;
    private volatile boolean cancelRequested;
    private volatile Runnable killFallback;

    public ExecutionStatus(String databaseAlias) {
        this.databaseAlias.set(databaseAlias);
    }

    public StringProperty databaseAliasProperty() {
        return databaseAlias;
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
            try {
                statement.cancel();
            } catch (SQLException ignored) {
                // Mismo motivo que en cancelQuery() — no es un error real.
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
        if (current != null) {
            try {
                current.cancel();
            } catch (SQLException ignored) {
                // El Statement ya pudo haberse cerrado si la consulta terminó justo antes del cancel — no es un error real.
            }
        }
        Runnable fallback = killFallback;
        if (fallback != null) {
            // Corre en su propio hilo — killBackend() abre una conexión nueva del pool y
            // puede bloquearse esperando una si el pool está saturado (ver README); no
            // queremos trabar el hilo de JavaFX cuando el clic viene del botón "Cancelar".
            Thread thread = new Thread(fallback, "faro-kill-fallback");
            thread.setDaemon(true);
            thread.start();
        }
    }
}
