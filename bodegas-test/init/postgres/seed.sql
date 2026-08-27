CREATE TABLE productos (
    id SERIAL PRIMARY KEY,
    nombre VARCHAR(200) NOT NULL,
    categoria VARCHAR(100) NOT NULL,
    descripcion TEXT,
    precio NUMERIC(10,2) NOT NULL,
    stock INTEGER NOT NULL,
    fecha_actualizacion DATE NOT NULL
);

INSERT INTO productos (nombre, categoria, descripcion, precio, stock, fecha_actualizacion)
SELECT
    (ARRAY['Jabón de baño','Café molido','Piña en almíbar','Frijol negro','Papel higiénico',
           'Detergente líquido','Aceite de girasol','Azúcar refinada','Chile jalapeño','Yogur natural',
           'Salsa botanera','Refresco de toronja','Galletas de avena','Puré de tomate','Agua mineral',
           'Té de manzanilla','Pan de caja','Queso panela','Jamón de pavo','Cereal integral'
          ])[1 + floor(random()*20)::int]
    || ' ' || (ARRAY['500g','1kg','750ml','1L','2L','300g','#4','Familiar','Chico','Grande'])[1 + floor(random()*10)::int] AS nombre,
    (ARRAY['Limpieza','Abarrotes','Bebidas','Lácteos','Botanas','Higiene personal','Enlatados','Panadería'])[1 + floor(random()*8)::int] AS categoria,
    'Producto de prueba #' || gs || ' — descripción con acentos: ñoño, corazón, jalapeño, "comillas", café, niño, güero. Línea añadida para verificar codificación UTF-8 sin errores.' AS descripcion,
    round((random()*490 + 10)::numeric, 2) AS precio,
    floor(random()*1000)::int AS stock,
    (DATE '2024-01-01' + (random()*600)::int) AS fecha_actualizacion
FROM generate_series(1, 500000) AS gs;

-- A partir de acá: un objeto de cada categoría del explorador de esquema
-- (2026-08-26, para poder probar en vivo las categorías que quedaron sin
-- confirmar del esquema progresivo — antes solo había tablas, nada de
-- Vistas/Funciones/Procedimientos/Triggers/Tipos que expandir de verdad).
-- DROP IF EXISTS antes de cada CREATE — no todos los objetos de Postgres
-- soportan CREATE OR REPLACE (PROCEDURE no lo soportaba antes de PG14, y
-- este mismo script corre igual contra bodega1-pg12), así que se deja
-- idempotente a mano para que también sirva si se vuelve a correr sobre
-- una base que ya tenía estos objetos.

-- Tipo personalizado — enum, mismo nombre ya usado como ejemplo en
-- SchemaTreeNodeTest.
DROP TYPE IF EXISTS estado_pedido CASCADE;
CREATE TYPE estado_pedido AS ENUM ('pendiente', 'procesando', 'enviado', 'entregado', 'cancelado');

-- Vista — subconjunto real de productos, sin inventar columnas nuevas.
DROP VIEW IF EXISTS vista_productos_caros;
CREATE VIEW vista_productos_caros AS
SELECT id, nombre, categoria, precio, stock
FROM productos
WHERE precio > 400;

-- Función — SQL simple, cuenta real de la tabla.
DROP FUNCTION IF EXISTS fn_total_productos();
CREATE FUNCTION fn_total_productos() RETURNS INTEGER AS $$
    SELECT COUNT(*)::INTEGER FROM productos;
$$ LANGUAGE SQL;

-- Procedimiento — mutación real (UPDATE), para probar CALL de verdad si
-- hace falta, no solo lectura.
DROP PROCEDURE IF EXISTS sp_actualizar_stock(INTEGER, INTEGER);
CREATE PROCEDURE sp_actualizar_stock(p_id INTEGER, p_cantidad INTEGER)
LANGUAGE SQL
AS $$
    UPDATE productos SET stock = p_cantidad WHERE id = p_id;
$$;

-- Trigger — necesita su propia función (plpgsql, a diferencia de la de
-- arriba que es SQL simple) — actualiza fecha_actualizacion sola en cada
-- UPDATE, sin que quien corra el UPDATE tenga que acordarse de hacerlo.
DROP TRIGGER IF EXISTS trg_actualizar_fecha ON productos;
DROP FUNCTION IF EXISTS fn_trg_actualizar_fecha();
CREATE FUNCTION fn_trg_actualizar_fecha() RETURNS TRIGGER AS $$
BEGIN
    NEW.fecha_actualizacion = CURRENT_DATE;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_actualizar_fecha
BEFORE UPDATE ON productos
FOR EACH ROW
EXECUTE FUNCTION fn_trg_actualizar_fecha();
