package com.faro.app.ui;

import com.faro.app.query.ExecutionStatus;

import javafx.beans.binding.Bindings;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ListCell;
import javafx.scene.control.ListView;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

/**
 * Arma la lista de la pestaña "Ejecución" — una fila tipo tarjeta por base
 * (punto de color + alias + host + badge de estado + barra + filas + tiempo
 * + cancelar), contra {@code faro-java-prototipo.html} (el diseño real de
 * este panel — no existe en {@code demo_html}, es exclusivo del rediseño
 * Java). Antes era una {@code TableView} con rejilla de columnas — el
 * usuario la marcó como "formato de tabla culero", sin nada que ver con el
 * prototipo real.
 */
public final class ExecutionTableFactory {

    private ExecutionTableFactory() {
    }

    public static ListView<ExecutionStatus> create() {
        ListView<ExecutionStatus> list = new ListView<>();
        list.getStyleClass().add("execution-list");
        list.setCellFactory(view -> new ExecutionCell());
        return list;
    }

    private static final class ExecutionCell extends ListCell<ExecutionStatus> {
        private final Region dot = new Region();
        private final Label alias = new Label();
        private final Label host = new Label();
        private final Label badge = new Label();
        private final ProgressBar bar = new ProgressBar();
        private final Label rows = new Label();
        private final Label elapsed = new Label();
        private final Button cancelButton = new Button();
        private final HBox row;
        /**
         * Mensaje de error completo, siempre visible (sin necesitar hover)
         * cuando la base falló — el usuario dejó explícito que no quiere
         * tener que pasar el mouse para leerlo. Con {@code wrapText}, ocupa
         * todo el ancho de la fila y crece la altura de la celda lo que
         * haga falta.
         */
        private final Label errorDetail = new Label();
        private final VBox cellRoot;

        ExecutionCell() {
            dot.getStyleClass().add("exec-dot");
            alias.getStyleClass().add("exec-alias");
            alias.setPrefWidth(150);
            alias.setMaxWidth(150);
            // Ancho fijo a propósito (layout de columnas, para que los valores queden
            // alineados entre filas como una tabla real) — un alias/host más largo que
            // esto SÍ se sigue cortando con "..." (comportamiento normal de Label), no
            // hay forma de "auto-ajustar" sin romper esa alineación. Tooltip con el
            // texto completo como salida real para ese caso (2026-08-28, pregunta
            // directa del usuario: "qué ocurre si una base de datos es demasiado
            // larga"), enlazado al propio textProperty (no al de ExecutionStatus
            // directo) para que siga lo que la celda tenga bindeado en cada momento,
            // sin importar el reciclado de celdas del ListView.
            Tooltip aliasTooltip = new Tooltip();
            aliasTooltip.textProperty().bind(alias.textProperty());
            Tooltip.install(alias, aliasTooltip);
            host.getStyleClass().add("exec-host");
            Tooltip hostTooltip = new Tooltip();
            hostTooltip.textProperty().bind(host.textProperty());
            Tooltip.install(host, hostTooltip);
            // 120px se cortaba ("localhost:5...", captura del usuario) — 170px le da
            // aire real, sobre todo con el tamaño de fuente de la interfaz subido
            // (Preferencias → Apariencia, que también escala esta etiqueta).
            host.setPrefWidth(170);
            host.setMaxWidth(170);
            badge.getStyleClass().add("exec-badge");
            bar.getStyleClass().add("exec-bar");
            // Antes crecía para llenar todo el espacio libre (hgrow=ALWAYS,
            // maxWidth=infinito) — "ocupa demasiado espacio", pedido explícito del
            // usuario, con captura: "reducela a una tercera parte de su tamaño".
            // Ancho fijo en vez de creciente — de paso deja más aire para que
            // alias/host/filas no se corten tan seguido.
            bar.setPrefWidth(150);
            bar.setMaxWidth(150);
            rows.getStyleClass().add("exec-rows");
            // 80px alcanzaba para un número solo — con el sufijo " fila(s)" agregado
            // (ver updateItem) un resultado grande (ej. "3000000 fila(s)") ya no cabía.
            // 120px tampoco alcanzó del todo ("500000 fil...", captura del usuario) —
            // subido a 160px, mismo motivo que host de arriba.
            rows.setPrefWidth(160);
            rows.setMaxWidth(160);
            elapsed.getStyleClass().add("exec-elapsed");
            elapsed.setPrefWidth(100);
            elapsed.setMaxWidth(100);
            cancelButton.setGraphic(Icons.strokeIcon(Icons.X));
            cancelButton.getStyleClass().add("button-icon");
            cancelButton.setOnAction(event -> {
                ExecutionStatus status = getItem();
                if (status != null) {
                    status.cancelQuery();
                }
            });

            row = new HBox(12, dot, alias, host, badge, bar, rows, elapsed, cancelButton);
            row.setAlignment(Pos.CENTER_LEFT);
            row.getStyleClass().add("exec-row");

            errorDetail.getStyleClass().add("exec-error-detail");
            errorDetail.setWrapText(true);
            errorDetail.setMaxWidth(Double.MAX_VALUE);

            cellRoot = new VBox(row, errorDetail);
            cellRoot.getStyleClass().add("exec-cell-root");
        }

        @Override
        protected void updateItem(ExecutionStatus status, boolean empty) {
            super.updateItem(status, empty);
            // Desatar SIEMPRE primero, incluso al quedar vacía — si no, una celda
            // reciclada que pasa a vacía se queda con bindings vivos al
            // ExecutionStatus de la corrida anterior (mismo criterio que ya
            // tenía la TableView vieja, hallazgo real de /code-review).
            alias.textProperty().unbind();
            host.textProperty().unbind();
            rows.textProperty().unbind();
            elapsed.textProperty().unbind();
            errorDetail.textProperty().unbind();
            errorDetail.visibleProperty().unbind();
            errorDetail.managedProperty().unbind();
            cancelButton.disableProperty().unbind();

            if (empty || status == null) {
                setGraphic(null);
                return;
            }

            alias.textProperty().bind(status.databaseAliasProperty());
            host.textProperty().bind(status.hostProperty());
            // "<número>" a secas (2026-08-28, hallazgo real del usuario: "veo un
            // detalle... hay un número 0, no sé qué indique ese número") — sin ninguna
            // unidad/etiqueta cerca, un 0 (consulta que de verdad no devolvió filas,
            // ej. un UPDATE) es indistinguible de "no sé qué es esto". "fila(s)" deja
            // claro qué mide sin necesitar una columna de encabezado aparte.
            rows.textProperty().bind(Bindings.createStringBinding(
                    () -> status.rowCountProperty().get() + " fila(s)", status.rowCountProperty()));
            cancelButton.disableProperty().bind(Bindings.createBooleanBinding(
                    () -> status.stateProperty().get() != ExecutionStatus.State.RUNNING,
                    status.stateProperty()));

            applyState(status.stateProperty().get());
            status.stateProperty().addListener((obs, oldState, newState) -> applyState(newState));

            // Bindeado, no `setText` de una sola vez — la última corrida
            // mostraba siempre "0 ms" en vez del mensaje de error real
            // porque esto se calculaba solo al crear la fila, antes de que
            // la consulta terminara y QueryExecutionService pusiera el
            // mensaje/tiempo real (hallazgo real del usuario probando).
            elapsed.textProperty().bind(Bindings.createStringBinding(
                    () -> status.elapsedMillisProperty().get() + " ms",
                    status.elapsedMillisProperty()));

            // El mensaje completo, SIEMPRE visible cuando la base falló —
            // el usuario dejó explícito que no quiere tener que pasar el
            // mouse para leerlo (un primer intento con tooltip no le
            // sirvió). Con wrapText la celda crece lo que haga falta, sin
            // cortar nada — a diferencia de la columna angosta de antes,
            // que partía mensajes largos de JDBC/Postgres en dos líneas
            // ilegibles.
            errorDetail.textProperty().bind(status.messageProperty());
            errorDetail.visibleProperty().bind(Bindings.createBooleanBinding(
                    () -> status.stateProperty().get() == ExecutionStatus.State.FAILED && !status.messageProperty().get().isBlank(),
                    status.stateProperty(), status.messageProperty()));
            errorDetail.managedProperty().bind(errorDetail.visibleProperty());

            setGraphic(cellRoot);
        }

        private void applyState(ExecutionStatus.State state) {
            dot.getStyleClass().removeAll("exec-dot-running", "exec-dot-succeeded", "exec-dot-failed", "exec-dot-cancelled");
            badge.getStyleClass().removeAll("exec-badge-running", "exec-badge-succeeded", "exec-badge-failed", "exec-badge-cancelled");
            bar.getStyleClass().removeAll("exec-bar-running", "exec-bar-succeeded", "exec-bar-failed", "exec-bar-cancelled");
            switch (state) {
                case RUNNING -> {
                    dot.getStyleClass().add("exec-dot-running");
                    badge.setText("EJECUTANDO");
                    badge.getStyleClass().add("exec-badge-running");
                    bar.getStyleClass().add("exec-bar-running");
                    bar.setProgress(ProgressBar.INDETERMINATE_PROGRESS);
                }
                case SUCCEEDED -> {
                    dot.getStyleClass().add("exec-dot-succeeded");
                    badge.setText("LISTO");
                    badge.getStyleClass().add("exec-badge-succeeded");
                    bar.getStyleClass().add("exec-bar-succeeded");
                    bar.setProgress(1);
                }
                case FAILED -> {
                    dot.getStyleClass().add("exec-dot-failed");
                    badge.setText("ERROR");
                    badge.getStyleClass().add("exec-badge-failed");
                    bar.getStyleClass().add("exec-bar-failed");
                    bar.setProgress(1);
                }
                case CANCELLED -> {
                    dot.getStyleClass().add("exec-dot-cancelled");
                    badge.setText("CANCELADO");
                    badge.getStyleClass().add("exec-badge-cancelled");
                    bar.getStyleClass().add("exec-bar-cancelled");
                    bar.setProgress(1);
                }
            }
        }
    }
}
