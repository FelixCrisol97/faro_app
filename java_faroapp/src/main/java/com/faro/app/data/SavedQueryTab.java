package com.faro.app.data;

import java.util.List;

/**
 * Una pestaña de consulta guardada entre sesiones (2026-08-28, pedido
 * explícito del usuario: "que deje persistentes las ventanas que deje
 * abiertas... para la próxima vez que abra la app que sigan viéndose esas 2
 * ventanas con sus respectivos scripts"). Se persiste el TEXTO real del
 * editor, no solo la ruta del archivo — así una pestaña con cambios sin
 * guardar (todavía sin ningún archivo asociado, o con cambios más nuevos que
 * lo que hay en disco) no pierde nada al cerrar la app, igual que un editor
 * de código moderno restaura sus buffers.
 *
 * <p>Deliberadamente separado de {@link ConnectionRegistry}/{@code save()} —
 * ver {@code ConnectionRegistryStore}: "Importar/Exportar configuración"
 * (Conexiones → menú) nunca incluye pestañas abiertas, a propósito, mismo
 * criterio que ya excluye credenciales de esos dos flujos — llevar el script
 * a medio escribir de quien exporta a la máquina de quien importa sería una
 * sorpresa rara, no un dato de configuración real.
 */
public record SavedQueryTab(String sql, String filePath, List<String> selectedDatabaseIds) {
}
