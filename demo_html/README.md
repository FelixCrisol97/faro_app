# Faro — Demo HTML para presentación a cliente

Réplica visual e interactiva de la aplicación Faro real, hecha en HTML/CSS/JS puro —
sin frameworks, sin dependencias externas, sin conexión a internet requerida.
Pensada para **presentar la propuesta a un cliente**, no es la app real (no se
conecta a ninguna base de datos — todos los datos que ves son ficticios).

## Cómo abrirla

Doble clic en `index.html` (o ábrelo con cualquier navegador moderno: Chrome, Edge,
Firefox). No necesita servidor, no necesita instalar nada.

## Qué incluye

- **Árbol único** de servidores / bases de datos "Sin grupo" (buscador, arrastrar
  y soltar para reordenar/agrupar/desagrupar, menús contextuales, credenciales,
  modo Solo lectura / Sin restricciones, probar conexión).
- **Explorador de esquema** por base de datos (Tablas/Vistas/Funciones/
  Procedimientos/Triggers, carga bajo demanda, menú contextual con generación de
  scripts SELECT/UPDATE/CREATE e importar CSV).
- **Editor SQL** con resaltado de sintaxis real (tokenizador propio en JS, mismo
  criterio que el editor real), números de línea, buscador (Ctrl+F), y
  autocompletado de tablas/columnas con navegación por teclado.
- **Resultados**: pills por base de datos, exportar CSV (descarga real del
  navegador), paginar "Cargar más", liberar resultados de memoria.
- **Pestañas de consulta** y **"Abrir en nueva ventana"** (abre una ventana del
  navegador aparte, réplica de `query-window.html` — mismo principio que la app
  real: sin estado compartido en vivo entre ventanas).
- **Historial y Favoritos** (paneles laterales).
- **Apariencia**: tema claro/oscuro y los 6 colores de acento reales de la app,
  con los valores de color exactos tomados de `lib/core/theme/`. Persisten entre
  visitas (localStorage).
- Tipografías reales de la app (Sora, Manrope, JetBrains Mono), incluidas como
  archivos en `fonts/` — la demo se ve idéntica sin depender de Google Fonts ni
  de conexión a internet.

## Estructura

```
demo_html/
├── index.html          # Shell principal (árbol + editor + resultados)
├── query-window.html    # "Abrir en nueva ventana" (página aparte)
├── query-window.js
├── styles.css            # Todos los tokens de diseño + componentes
├── app.js                 # Toda la interactividad
├── icons.js                # Set de íconos SVG inline (estilo Lucide)
├── data.js                  # Datos ficticios de ejemplo
└── fonts/                    # Sora, Manrope, JetBrains Mono (reales)
```

## Notas para quien la presente

- Todo el dataset (bodegas, muebles, SKUs) es ficticio, generado para esta demo.
- Los botones de "Probar conexión", "Ejecutar", "Importar CSV" etc. simulan el
  comportamiento real (con su pequeño delay/spinner) pero no tocan ninguna base
  de datos de verdad.
- Exportar CSV / Exportar SQL sí generan un archivo real descargable — útil para
  mostrar en vivo que "esto realmente funciona así".
