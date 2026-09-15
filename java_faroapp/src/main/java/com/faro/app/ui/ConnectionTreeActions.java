package com.faro.app.ui;

import java.util.function.BiConsumer;
import java.util.function.Consumer;

import com.faro.app.model.DatabaseEntry;
import com.faro.app.model.Server;
import com.faro.app.ui.SchemaTreeNode.GenerateAction;

/**
 * Todo lo que una fila del árbol de conexiones le puede pedir a
 * {@code MainController} — un solo objeto en vez de una lista de parámetros
 * posicionales en el constructor de {@link ConnectionTreeCell}.
 *
 * <p><b>Por qué existe</b> (2026-09-11): ese constructor ya iba por 9 parámetros, y
 * las acciones de grupo (marcar/desmarcar, renombrar, subir/bajar, ordenar) lo
 * habrían llevado a 14. Con esa cantidad de {@code Consumer} del mismo tipo seguidos,
 * equivocarse de orden al llamar compila sin protestar y falla en vivo: {@code onEdit}
 * y {@code onDelete} son los dos {@code Consumer<DatabaseEntry>}, así que
 * intercambiarlos habría hecho que el lápiz borrara la base. Los componentes con
 * nombre de un record quitan esa clase de error de raíz.
 *
 * <p><b>{@code Server} nulo = "Sin grupo".</b> Las bases sueltas no son un grupo
 * especial, son la ausencia de grupo (ver el javadoc de {@link Server}), y el árbol
 * las muestra bajo un encabezado de texto plano. Las acciones que también aplican ahí
 * ({@code onSetGroupSelection}, {@code onSortGroup}) reciben {@code null} para
 * referirse a ellas — mismo criterio que
 * {@code ConnectionRegistry#sortDatabasesByAlias}.
 *
 * <p>{@code delta} en {@code onMoveGroup}/{@code onMoveDatabase} es el desplazamiento
 * en posiciones: -1 sube una, +1 baja una.
 */
public record ConnectionTreeActions(
        Consumer<DatabaseEntry> onEdit,
        Consumer<DatabaseEntry> onNewQuery,
        Consumer<DatabaseEntry> onDelete,
        Consumer<DatabaseEntry> onDiscover,
        Consumer<DatabaseEntry> onToggleMode,
        Consumer<DatabaseEntry> onMoveToGroup,
        BiConsumer<DatabaseEntry, Integer> onMoveDatabase,
        BiConsumer<SchemaTreeNode.Item, GenerateAction> onGenerate,
        Consumer<SchemaTreeNode.Item> onCompare,
        Consumer<Server> onRenameGroup,
        BiConsumer<Server, Boolean> onSetGroupSelection,
        BiConsumer<Server, Integer> onMoveGroup,
        Consumer<Server> onSortGroup) {
}
