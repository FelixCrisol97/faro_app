IF DB_ID('bodega') IS NULL
BEGIN
    CREATE DATABASE bodega;
END
GO

USE bodega;
GO

IF OBJECT_ID('dbo.productos', 'U') IS NOT NULL
    DROP TABLE dbo.productos;
GO

CREATE TABLE dbo.productos (
    id INT IDENTITY(1,1) PRIMARY KEY,
    nombre NVARCHAR(200) NOT NULL,
    categoria NVARCHAR(100) NOT NULL,
    descripcion NVARCHAR(MAX),
    precio DECIMAL(10,2) NOT NULL,
    stock INT NOT NULL,
    fecha_actualizacion DATE NOT NULL
);
GO

IF OBJECT_ID('tempdb..#nombres') IS NOT NULL DROP TABLE #nombres;
IF OBJECT_ID('tempdb..#categorias') IS NOT NULL DROP TABLE #categorias;
IF OBJECT_ID('tempdb..#tamanos') IS NOT NULL DROP TABLE #tamanos;

CREATE TABLE #nombres (idx INT, val NVARCHAR(100));
INSERT INTO #nombres VALUES
 (0,N'Jabón de baño'),(1,N'Café molido'),(2,N'Piña en almíbar'),(3,N'Frijol negro'),(4,N'Papel higiénico'),
 (5,N'Detergente líquido'),(6,N'Aceite de girasol'),(7,N'Azúcar refinada'),(8,N'Chile jalapeño'),(9,N'Yogur natural'),
 (10,N'Salsa botanera'),(11,N'Refresco de toronja'),(12,N'Galletas de avena'),(13,N'Puré de tomate'),(14,N'Agua mineral'),
 (15,N'Té de manzanilla'),(16,N'Pan de caja'),(17,N'Queso panela'),(18,N'Jamón de pavo'),(19,N'Cereal integral');

CREATE TABLE #categorias (idx INT, val NVARCHAR(100));
INSERT INTO #categorias VALUES
 (0,N'Limpieza'),(1,N'Abarrotes'),(2,N'Bebidas'),(3,N'Lácteos'),(4,N'Botanas'),(5,N'Higiene personal'),(6,N'Enlatados'),(7,N'Panadería');

CREATE TABLE #tamanos (idx INT, val NVARCHAR(50));
INSERT INTO #tamanos VALUES
 (0,N'500g'),(1,N'1kg'),(2,N'750ml'),(3,N'1L'),(4,N'2L'),(5,N'300g'),(6,N'#4'),(7,N'Familiar'),(8,N'Chico'),(9,N'Grande');
GO

;WITH Nums AS (
    SELECT TOP (500000) ROW_NUMBER() OVER (ORDER BY (SELECT NULL)) AS n
    FROM sys.all_objects a CROSS JOIN sys.all_objects b
)
INSERT INTO dbo.productos (nombre, categoria, descripcion, precio, stock, fecha_actualizacion)
SELECT
    nm.val + N' ' + tm.val,
    cat.val,
    N'Producto de prueba #' + CAST(n AS NVARCHAR(10)) + N' — descripción con acentos: ñoño, corazón, jalapeño, "comillas", café, niño, güero. Línea añadida para verificar codificación UTF-8 sin errores.',
    CAST((ABS(CHECKSUM(NEWID())) % 49000 + 1000) AS DECIMAL(10,2)) / 100.0,
    ABS(CHECKSUM(NEWID())) % 1000,
    DATEADD(DAY, ABS(CHECKSUM(NEWID())) % 600, '2024-01-01')
FROM Nums
CROSS APPLY (SELECT val FROM #nombres WHERE idx = (n % 20)) nm
CROSS APPLY (SELECT val FROM #categorias WHERE idx = (n % 8)) cat
CROSS APPLY (SELECT val FROM #tamanos WHERE idx = (n % 10)) tm;
GO

SELECT COUNT(*) AS total FROM dbo.productos;
GO
