// Faro demo — sample data. Fictional (mismo estilo de datos de prueba que
// ya usa el proyecto: bodegas/muebles, SKUs P-00x) — nada de esto es un
// cliente real.

const DEMO = {
  servers: [
    {
      id: 'srv-bodegas',
      name: 'Bodegas Centro',
      databases: [
        {
          id: 'db-norte', name: 'Bodega Norte', databaseName: 'bodega',
          host: '192.168.1.10:5432', engine: 'postgres', mode: 'readOnly',
          testStatus: 'connected',
        },
        {
          id: 'db-sur', name: 'Bodega Sur', databaseName: 'bodega',
          host: '192.168.1.11:5432', engine: 'postgres', mode: 'readOnly',
          testStatus: 'idle',
        },
      ],
    },
    {
      id: 'srv-sucursales',
      name: 'Sucursales Muebles TX',
      databases: [
        {
          id: 'db-reforma', name: 'Tienda Reforma', databaseName: 'tienda',
          host: '10.20.4.10:1433', engine: 'sqlServer', mode: 'readOnly',
          testStatus: 'idle',
        },
        {
          id: 'db-polanco', name: 'Tienda Polanco', databaseName: 'tienda',
          host: '10.20.4.11:1433', engine: 'sqlServer', mode: 'development',
          testStatus: 'idle',
        },
      ],
    },
  ],
  ungrouped: [
    {
      id: 'db-crisol', name: 'crisol', databaseName: 'crisol',
      host: 'localhost:5432', engine: 'postgres', mode: 'readOnly',
      testStatus: 'connected',
    },
  ],

  // Schema objects per database id (same 5 categories real Faro browses).
  schemas: {
    'db-norte': {
      tables: ['productos', 'existencias', 'movimientos', 'inventario_detalle'],
      views: ['v_resumen_productos'],
      functions: ['fn_resumen_producto'],
      procedures: ['sp_actualizar_existencia'],
      triggers: ['trg_movimientos_audit'],
    },
    'db-crisol': {
      tables: ['productos'],
      views: [],
      functions: [],
      procedures: [],
      triggers: [],
    },
  },

  tableColumns: {
    productos: [
      { name: 'id', type: 'integer', pk: true },
      { name: 'sku', type: 'text', pk: false },
      { name: 'nombre', type: 'text', pk: false },
      { name: 'precio', type: 'numeric(10,2)', pk: false },
      { name: 'categoria', type: 'text', pk: false },
    ],
    existencias: [
      { name: 'id', type: 'integer', pk: true },
      { name: 'producto_id', type: 'integer', pk: false },
      { name: 'cantidad', type: 'integer', pk: false },
      { name: 'actualizado', type: 'timestamp', pk: false },
    ],
  },

  // Canned query result — shown regardless of exact SQL typed, matching
  // the same fictional catalog used throughout the real project's own
  // Docker test data (P-001/P-002/... SKUs).
  sampleResult: {
    columns: ['id', 'sku', 'nombre', 'categoria', 'precio'],
    types: ['entero', 'texto', 'texto', 'texto', 'decimal'],
    rows: [
      [1, 'P-001', 'Silla de madera', 'Sillas', '899.00'],
      [2, 'P-002', 'Mesa comedor 6 plazas', 'Mesas', '3200.50'],
      [3, 'P-003', 'Buró 2 cajones', 'Recámara', '1450.00'],
      [4, 'P-004', 'Sillón reclinable', 'Salas', '5600.00'],
      [5, 'P-005', 'Librero 5 niveles', 'Oficina', '2100.00'],
      [6, 'P-006', 'Cabecera tapizada Queen', 'Recámara', '2750.00'],
      [7, 'P-007', 'Mesa centro cristal', 'Salas', '1890.00'],
      [8, 'P-008', 'Escritorio ejecutivo', 'Oficina', '3400.00'],
    ],
  },

  massResult: {
    columns: ['origen_bd', 'ip', 'id', 'sku', 'nombre', 'precio'],
    types: ['texto', 'texto', 'entero', 'texto', 'texto', 'decimal'],
    rows: [
      ['Bodega Norte', '192.168.1.10:5432', 1, 'P-001', 'Silla de madera', '899.00'],
      ['Bodega Norte', '192.168.1.10:5432', 2, 'P-002', 'Mesa comedor 6 plazas', '3200.50'],
      ['Bodega Sur', '192.168.1.11:5432', 1, 'P-001', 'Silla de madera', '950.00'],
      ['Bodega Sur', '192.168.1.11:5432', 2, 'P-002', 'Mesa comedor 6 plazas', '3100.00'],
    ],
  },

  history: [
    {
      id: 'h1', time: '11:42:03',
      query: 'SELECT * FROM productos WHERE categoria = \'Salas\'',
      server: 'Bodega Norte', dbCount: 1, rows: 8, status: 'success',
    },
    {
      id: 'h2', time: '11:20:47',
      query: 'SELECT * FROM productos',
      server: '2 servidores', dbCount: 2, rows: 16, status: 'success',
    },
    {
      id: 'h3', time: '10:58:12',
      query: 'UPDATE productos SET precio = precio * 1.05 WHERE categoria = \'Oficina\'',
      server: 'Tienda Reforma', dbCount: 1, rows: 0, status: 'partial',
    },
  ],

  favorites: [
    {
      id: 'f1', name: 'Productos por categoría',
      query: "SELECT * FROM productos\nWHERE categoria = 'Salas'\nORDER BY precio DESC;",
    },
    {
      id: 'f2', name: 'Resumen de existencias',
      query: 'SELECT p.sku, p.nombre, e.cantidad\nFROM productos p\nJOIN existencias e ON e.producto_id = p.id\nORDER BY e.cantidad ASC;',
    },
  ],

  tableNames: ['productos', 'existencias', 'movimientos', 'inventario_detalle', 'v_resumen_productos'],
  columnNames: ['id', 'sku', 'nombre', 'categoria', 'precio', 'producto_id', 'cantidad', 'actualizado'],
};
