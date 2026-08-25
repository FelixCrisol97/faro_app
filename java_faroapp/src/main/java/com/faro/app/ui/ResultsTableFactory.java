package com.faro.app.ui;

import java.util.List;

import javafx.beans.property.SimpleObjectProperty;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;

/**
 * Arma y repuebla la tabla de resultados. Usa
 * {@code TableView<ObservableList<Object>>} en vez de una clase de fila
 * fija porque un resultado real de SQL tiene columnas que cambian según la
 * consulta — no se pueden conocer en tiempo de compilación. {@link #create()}
 * arma una tabla vacía (sin datos de ejemplo, se quitaron a pedido del
 * usuario, 2026-08-22 — ver {@link com.faro.app.data.ConnectionRegistry}
 * para el mismo pedido aplicado al árbol de conexiones); {@link #populate}
 * reemplaza columnas y filas de una tabla ya existente con un resultado
 * real — ver {@code MainController#onRunQuery}.
 *
 * <p>Si se marcan bases de motores distintos con formas de resultado
 * distintas en una misma corrida, las columnas de la tabla salen de la
 * que termine primero (ver {@code QueryExecutionService}) y otras filas
 * pueden traer más o menos valores de los que hay columnas — el
 * {@code cellValueFactory} de abajo es a prueba de eso (devuelve
 * {@code null} en vez de lanzar `IndexOutOfBoundsException`, hallazgo
 * real de /code-review). El resultado visual puede quedar desalineado en
 * ese caso — no se reconstruyó la reconciliación completa de columnas
 * entre motores distintos, solo se evitó el choque.
 */
public final class ResultsTableFactory {

    private ResultsTableFactory() {
    }

    public static TableView<ObservableList<Object>> create() {
        TableView<ObservableList<Object>> table = new TableView<>();
        table.getStyleClass().add("results-table");
        return table;
    }

    public static void populate(
            TableView<ObservableList<Object>> table, List<String> columnNames, List<List<Object>> rows) {
        table.getColumns().clear();

        for (int i = 0; i < columnNames.size(); i++) {
            final int columnIndex = i;
            TableColumn<ObservableList<Object>, Object> column = new TableColumn<>(columnNames.get(i));
            column.setCellValueFactory(data -> {
                List<Object> row = data.getValue();
                Object value = columnIndex < row.size() ? row.get(columnIndex) : null;
                return new SimpleObjectProperty<>(value);
            });
            table.getColumns().add(column);
        }

        ObservableList<ObservableList<Object>> items = FXCollections.observableArrayList();
        for (List<Object> row : rows) {
            items.add(FXCollections.observableArrayList(row));
        }
        table.setItems(items);
    }
}
