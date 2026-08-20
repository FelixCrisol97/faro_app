# Faro — Java (en construcción)

Reemplazo completo de `flutter_faroapp/` en Java — decidido el 2026-08-19 por el
estado inmaduro del driver de SQL Server en el ecosistema Dart/Flutter (sin
cliente TDS puro, dependiendo de un wrapper FFI vendorizado sobre FreeTDS que
necesitó parches reales de memoria/charset/fugas — ver
`AUDITORIA_CODIGO.md` en la raíz del repo, y las entradas de
`CONTEXTO_SESIONES.md` del 2026-08-02 al 2026-08-05). Java tiene un driver
JDBC oficial de Microsoft para SQL Server, maduro y sin esa clase de problema.

**Todavía no hay código aquí.** Antes de empezar a escribir, valdría la pena
decidir (y documentar la decisión, mismo criterio que el resto del proyecto):

- Framework de UI de escritorio: JavaFX es la opción natural para reconstruir
  una interfaz con el mismo nivel de estilizado/temas que tiene hoy
  `flutter_faroapp/` (colores, tipografías, tema claro/oscuro — ver
  `design_system/` en la raíz del repo, la fuente de verdad de esos tokens).
- Herramienta de build: Maven o Gradle.
- Estrategia de migración: reescritura completa de una vez, o por etapas
  (ej. empezar por la capa de conexión/consulta, luego la interfaz).

## Referencias que ya existen en el repo (no las repitas de memoria)

- **`Migración_Flutter_Java/entrega/`** (esta misma carpeta) — el diseño real
  del aplicativo Java, ya preparado por el usuario aparte (mockup/handoff
  exportado como Artifact de Claude — `faro-java-handoff.html` y
  `faro-java-prototipo.html`, ábrelos en un navegador para verlos). Es la
  fuente de verdad visual para la reconstrucción — mismo rol que
  `design_system/design_handoff_faro/` cumplió para el rebuild original de
  Flutter (ver `CONTEXTO_SESIONES.md`, sesión 2026-07-17 "Reemplazo total del
  design system"). Revisar esto antes de tomar cualquier decisión de UI.
- `design_system/` (raíz del repo) — tokens de diseño originales (colores,
  tipografías, espaciados, radios) — la misma fuente que ya se usó una vez
  para construir el tema de Flutter.
- `PROYECTO_DEFINICION.md` / `README.md` (raíz) — qué es Faro y su spec
  funcional original.
- `CONTEXTO_SESIONES.md` (raíz) — la bitácora completa de cómo se construyó
  y refinó cada función en Flutter — útil para no perder decisiones ya
  tomadas (ej. por qué el modo de una base de datos es independiente de su
  servidor, por qué las credenciales se resuelven como override→default→
  vacío, etc.) al reimplementar el mismo comportamiento en Java.
- `AUDITORIA_CODIGO.md` (raíz) — bugs y limitaciones ya conocidas del driver
  de SQL Server en Flutter — el punto de partida de por qué existe esta
  carpeta.
- `demo_html/` (raíz) — réplica visual/interactiva de la app, útil como
  referencia exacta de cómo debe verse y comportarse cada pantalla.
