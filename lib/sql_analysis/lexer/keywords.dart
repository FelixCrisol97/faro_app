/// Canonical PostgreSQL keyword set for `sql_lexer.dart`'s
/// identifier→[SqlTokenType.keyword] reclassification — not shared with
/// `sql_syntax_highlighter.dart`'s own (smaller, coloring-only) keyword list
/// deliberately (see the module plan's "the lexer lives apart" decision:
/// unifying the two is a separate future change, gated on its own
/// performance benchmark, not part of this module).
///
/// Deliberately pragmatic, not a literal transcription of Postgres's full
/// ~450-word reserved/unreserved keyword table — v1's parser only ever
/// checks a handful of these by exact text (`FROM`, `JOIN`, `WHERE`, ...);
/// the rest exist so [SqlTokenType.keyword] classification (and anything
/// built on it later, e.g. highlighting) reads as complete for everyday
/// SQL. Comparison is always case-insensitive: the lexer uppercases before
/// checking membership.
const kSqlKeywords = <String>{
  // DML
  'SELECT', 'INSERT', 'UPDATE', 'DELETE', 'INTO', 'VALUES', 'SET', 'MERGE',
  'RETURNING',

  // Clause / query structure
  'FROM', 'WHERE', 'GROUP', 'BY', 'HAVING', 'ORDER', 'LIMIT', 'OFFSET',
  'DISTINCT', 'ON', 'AS', 'WITH', 'RECURSIVE', 'USING', 'FILTER', 'OVER',
  'WINDOW', 'FETCH', 'FIRST', 'NEXT', 'ROWS', 'ONLY', 'TIES',

  // Joins
  'JOIN', 'INNER', 'LEFT', 'RIGHT', 'FULL', 'CROSS', 'OUTER', 'LATERAL',
  'NATURAL',

  // Set operations
  'UNION', 'INTERSECT', 'EXCEPT', 'ALL',

  // Logical / comparison / predicates
  'AND', 'OR', 'NOT', 'IN', 'IS', 'NULL', 'LIKE', 'ILIKE', 'BETWEEN',
  'EXISTS', 'ANY', 'SOME', 'TRUE', 'FALSE', 'UNKNOWN', 'SIMILAR', 'ISNULL',
  'NOTNULL',

  // Case expression
  'CASE', 'WHEN', 'THEN', 'ELSE', 'END',

  // Ordering
  'ASC', 'DESC', 'NULLS', 'LAST',

  // DDL
  'CREATE', 'ALTER', 'DROP', 'TRUNCATE', 'TABLE', 'VIEW', 'INDEX',
  'SEQUENCE', 'FUNCTION', 'PROCEDURE', 'TRIGGER', 'SCHEMA', 'DATABASE',
  'COLUMN', 'CONSTRAINT', 'PRIMARY', 'FOREIGN', 'KEY', 'REFERENCES',
  'UNIQUE', 'CHECK', 'DEFAULT', 'CASCADE', 'RESTRICT', 'IF', 'EXTENSION',
  'MATERIALIZED', 'REPLACE', 'CONCURRENTLY', 'TYPE', 'DOMAIN', 'RULE',

  // DCL / transactions
  'GRANT', 'REVOKE', 'BEGIN', 'COMMIT', 'ROLLBACK', 'TRANSACTION',
  'SAVEPOINT', 'EXPLAIN', 'ANALYZE', 'VACUUM', 'COPY', 'DO', 'DECLARE',
  'EXECUTE', 'CALL',

  // Common data types (recognized, not deeply used — see UnknownStatement
  // for DDL; still worth classifying as keyword rather than identifier for
  // consistency with anything reading the token stream later)
  'INT', 'INTEGER', 'BIGINT', 'SMALLINT', 'NUMERIC', 'DECIMAL', 'REAL',
  'DOUBLE', 'PRECISION', 'SERIAL', 'BIGSERIAL', 'VARCHAR', 'CHAR',
  'CHARACTER', 'VARYING', 'TEXT', 'BOOLEAN', 'DATE', 'TIME', 'TIMESTAMP',
  'TIMESTAMPTZ', 'INTERVAL', 'UUID', 'JSON', 'JSONB', 'ARRAY', 'BYTEA',
  'ZONE', 'WITHOUT',

  // Misc
  'CAST', 'BOTH', 'LEADING', 'TRAILING', 'COLLATE', 'FOR', 'CONFLICT',
  'EXCLUDED', 'GENERATED', 'ALWAYS', 'IDENTITY',
};
