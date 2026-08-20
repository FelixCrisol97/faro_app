import 'package:faro/core/constants/db_engine.dart';
import 'package:faro/data/models/table_column.dart';
import 'package:faro/features/consulta/application/sql_script_generator.dart';
import 'package:flutter_test/flutter_test.dart';

void main() {
  group('generateSelectScript', () {
    test('uses LIMIT for Postgres', () {
      expect(generateSelectScript(DbEngine.postgres, 'public', 'productos'),
          'SELECT * FROM public.productos LIMIT 100;');
    });

    test('uses TOP for SQL Server', () {
      expect(generateSelectScript(DbEngine.sqlServer, 'dbo', 'productos'),
          'SELECT TOP 100 * FROM dbo.productos;');
    });
  });

  group('generateUpdateScript', () {
    const columns = [
      TableColumn(
          name: 'sku', dataType: 'text', nullable: false, isPrimaryKey: true),
      TableColumn(
          name: 'nombre',
          dataType: 'text',
          nullable: false,
          isPrimaryKey: false),
    ];

    test('puts the primary key in WHERE and the rest in SET', () {
      final sql = generateUpdateScript(
          DbEngine.postgres, 'public', 'productos', columns);
      expect(sql, contains('SET\n    nombre = <nombre>'));
      expect(sql, contains('WHERE\n    sku = <sku>;'));
    });

    test('leaves a TODO in WHERE when there is no primary key', () {
      const noPk = [
        TableColumn(
            name: 'nombre',
            dataType: 'text',
            nullable: false,
            isPrimaryKey: false),
      ];
      final sql =
          generateUpdateScript(DbEngine.postgres, 'public', 'productos', noPk);
      expect(sql, contains('TODO: agrega condición WHERE'));
    });
  });

  group('generateCreateTableScript', () {
    test('includes length/precision, NOT NULL, and a PRIMARY KEY clause', () {
      const columns = [
        TableColumn(
            name: 'sku',
            dataType: 'varchar',
            nullable: false,
            isPrimaryKey: true,
            characterMaxLength: 20),
        TableColumn(
            name: 'precio',
            dataType: 'numeric',
            nullable: true,
            isPrimaryKey: false,
            numericPrecision: 10,
            numericScale: 2),
      ];
      final sql = generateCreateTableScript(
          DbEngine.postgres, 'public', 'productos', columns);
      expect(sql, contains('sku varchar(20) NOT NULL'));
      expect(sql, contains('precio numeric(10,2)'));
      expect(sql, isNot(contains('precio numeric(10,2) NOT NULL')));
      expect(sql, contains('PRIMARY KEY (sku)'));
    });
  });
}
