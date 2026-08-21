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
 * arma una tabla con una consulta de ejemplo (la misma que trae precargada
 * {@link SqlEditorFactory}); {@link #populate} reemplaza columnas y filas
 * de una tabla ya existente con un resultado real — ver
 * {@code MainController#onRunQuery}.
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

    private static final String[] DEMO_COLUMN_NAMES = {"id", "nombre", "total"};

    private ResultsTableFactory() {
    }

    public static TableView<ObservableList<Object>> create() {
        TableView<ObservableList<Object>> table = new TableView<>();
        table.getStyleClass().add("results-table");

        populate(table, List.of(DEMO_COLUMN_NAMES), List.of(
                List.of(1, "Refresco de cola 600ml", 1284.50),
                List.of(2, "Detergente en polvo 1kg", 932.00),
                List.of(3, "Aceite vegetal 1L", 2110.75),
                List.of(4, "Papel higiénico 4pz", 1560.20)));

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
