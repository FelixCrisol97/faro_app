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

    /** {@code -fx-font-size} literal de {@code .results-table} en {@code app.css} — base para {@link #rowHeight}. */
    private static final double BASE_FONT_SIZE_PX = 13;
    /** {@code -fx-padding: 8 14 8 14} de {@code .results-table .table-cell} en {@code app.css} — 8px arriba + 8px abajo. */
    private static final double CELL_VERTICAL_PADDING_PX = 16;
    /** Mismo piso que {@code Theme#scaledFontSize} — nunca calcular una fila para un tamaño de fuente ilegible. */
    private static final double MIN_FONT_SIZE_PX = 8;
    /**
     * Margen de seguridad, pedido explícito del usuario (2026-08-26) — dos
     * intentos de arreglar de raíz por qué el texto seguía cortándose SOLO
     * al bajar el zoom (`refresh()`, después vaciar/reponer los items para
     * forzar reconstrucción completa de celdas) no lo resolvieron; el
     * usuario confirmó "sigue igual, mismo comportamiento" con el segundo
     * intento todavía puesto. En vez de seguir adivinando causas de un
     * desfase de render que no se pudo diagnosticar con certeza sin ver la
     * ventana real, se le suma este colchón fijo a la altura calculada —
     * si el font real queda momentáneamente más grande que el nuevo tamaño
     * mientras el texto "alcanza" al slider, este margen absorbe la
     * diferencia en vez de cortar el texto. Probado primero en 10px
     * (confirmado por el usuario: "quedo excelente"), bajado a 8px a pedido
     * suyo — 8px ya alcanza, no hace falta más colchón del necesario.
     */
    private static final double SAFETY_MARGIN_PX = 8;

    /**
     * Altura de fila para el tamaño de fuente EFECTIVO de la tabla (base +
     * {@code fontScaleDelta}) — 2026-08-26, corrigiendo un bug real
     * reportado con captura ("si doy mucho zoom el grid de resultados se ve
     * muy mal", texto de filas contiguas superpuesto/cortado). La constante
     * fija de antes ({@code fixedCellSize} explícito, agregado el mismo día
     * para el bug de la barra de scroll invisible — ver el historial de
     * commits, no se repite acá) se calculó para los 13px de siempre y
     * nunca se volvió a tocar cuando se agregó {@code fontScaleDelta}: con
     * el slider nuevo de Preferencias en +5 (18px real) la fila seguía fija
     * en 32px, más chica que el texto que tenía que mostrar. Misma fórmula
     * que ya se usó para calcular el 32px original, pero ahora con el
     * tamaño real en vez de 13px fijo, más {@link #SAFETY_MARGIN_PX}.
     */
    public static double rowHeight(int fontScaleDelta) {
        double fontSize = Math.max(MIN_FONT_SIZE_PX, BASE_FONT_SIZE_PX + fontScaleDelta);
        return fontSize * 1.2 + CELL_VERTICAL_PADDING_PX + SAFETY_MARGIN_PX;
    }

    public static TableView<ObservableList<Object>> create(int fontScaleDelta) {
        TableView<ObservableList<Object>> table = new TableView<>();
        table.getStyleClass().add("results-table");
        table.setFixedCellSize(rowHeight(fontScaleDelta));
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
