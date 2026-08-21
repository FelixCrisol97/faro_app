package com.faro.app.ui;

import com.faro.app.query.ExecutionStatus;

import javafx.beans.binding.Bindings;
import javafx.scene.control.Button;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.paint.Color;

/** Arma la tabla de la pestaña "Ejecución" — estado en vivo por base durante una corrida, ver {@link ExecutionStatus}. */
public final class ExecutionTableFactory {

    private ExecutionTableFactory() {
    }

    public static TableView<ExecutionStatus> create() {
        TableView<ExecutionStatus> table = new TableView<>();
        table.getStyleClass().add("results-table");

        TableColumn<ExecutionStatus, String> aliasColumn = new TableColumn<>("Base de datos");
        aliasColumn.setCellValueFactory(data -> data.getValue().databaseAliasProperty());

        TableColumn<ExecutionStatus, ExecutionStatus.State> stateColumn = new TableColumn<>("Estado");
        stateColumn.setCellValueFactory(data -> data.getValue().stateProperty());
        stateColumn.setCellFactory(column -> new TableCell<>() {
            @Override
            protected void updateItem(ExecutionStatus.State state, boolean empty) {
                super.updateItem(state, empty);
                if (empty || state == null) {
                    setText(null);
                    setTextFill(Color.BLACK);
                    return;
                }
                switch (state) {
                    case RUNNING -> {
                        setText("Ejecutando…");
                        setTextFill(Color.web("#B45309")); // --warn-base
                    }
                    case SUCCEEDED -> {
                        setText("Exitoso");
                        setTextFill(Color.web("#059669")); // --success-base
                    }
                    case FAILED -> {
                        setText("Error");
                        setTextFill(Color.web("#DC2626")); // --error-base
                    }
                    case CANCELLED -> {
                        setText("Cancelado");
                        setTextFill(Color.web("#475569")); // --text-muted
                    }
                }
            }
        });

        TableColumn<ExecutionStatus, Number> rowsColumn = new TableColumn<>("Filas");
        rowsColumn.setCellValueFactory(data -> data.getValue().rowCountProperty());

        TableColumn<ExecutionStatus, Number> elapsedColumn = new TableColumn<>("Tiempo (ms)");
        elapsedColumn.setCellValueFactory(data -> data.getValue().elapsedMillisProperty());

        TableColumn<ExecutionStatus, String> messageColumn = new TableColumn<>("Mensaje");
        messageColumn.setCellValueFactory(data -> data.getValue().messageProperty());

        TableColumn<ExecutionStatus, Void> cancelColumn = new TableColumn<>("");
        cancelColumn.setCellFactory(column -> new TableCell<>() {
            private final Button cancelButton = new Button("Cancelar");
            {
                cancelButton.getStyleClass().add("button-secondary");
                cancelButton.setOnAction(event -> {
                    ExecutionStatus status = getTableRow().getItem();
                    if (status != null) {
                        status.cancelQuery();
                    }
                });
            }

            @Override
            protected void updateItem(Void item, boolean empty) {
                super.updateItem(item, empty);
                // Desatar SIEMPRE primero, incluso al quedar vacía — si no, una celda
                // reciclada que pasa a vacía se queda con un binding vivo al
                // ExecutionStatus de la corrida anterior para siempre (hallazgo real
                // de /code-review, fuga de memoria menor).
                cancelButton.disableProperty().unbind();

                ExecutionStatus status = empty ? null : getTableRow().getItem();
                if (status == null) {
                    setGraphic(null);
                    return;
                }
                cancelButton.disableProperty().bind(Bindings.createBooleanBinding(
                        () -> status.stateProperty().get() != ExecutionStatus.State.RUNNING,
                        status.stateProperty()));
                setGraphic(cancelButton);
            }
        });

        table.getColumns().setAll(aliasColumn, stateColumn, rowsColumn, elapsedColumn, messageColumn, cancelColumn);
        return table;
    }
}
