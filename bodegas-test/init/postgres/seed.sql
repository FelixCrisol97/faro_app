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
