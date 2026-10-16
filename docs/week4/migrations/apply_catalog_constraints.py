"""Apply the reviewed catalog constraints without rewriting existing data (MySQL 8)."""
import argparse
import os
from pathlib import Path
import re
import subprocess

HERE = Path(__file__).resolve().parent


class MySQL:
    def __init__(self, database):
        if not re.fullmatch(r'[a-zA-Z][a-zA-Z0-9_]{0,63}', database):
            raise ValueError('Invalid database identifier')
        self.database = database
        self.command = ['mysql', '--protocol=TCP', '--batch', '--raw', '--skip-column-names',
                        '--default-character-set=utf8mb4', '--local-infile=1',
                        '-h', os.environ.get('MYSQL_HOST', '127.0.0.1'),
                        '-P', os.environ.get('MYSQL_PORT', '3306'),
                        '-u', os.environ.get('MYSQL_USER', 'application'), '-D', database]

    def query(self, sql):
        setup = "SET SESSION time_zone = '+00:00'; SET SESSION sql_mode = 'STRICT_ALL_TABLES,NO_ZERO_DATE,NO_ZERO_IN_DATE,ERROR_FOR_DIVISION_BY_ZERO,NO_ENGINE_SUBSTITUTION';\n"
        result = subprocess.run(self.command, input=setup + sql, text=True, capture_output=True)
        if result.returncode:
            raise RuntimeError(result.stderr.strip())
        if result.stderr.strip():
            raise RuntimeError('Unexpected MySQL diagnostic: ' + result.stderr.strip())
        return [line.split('\t') for line in result.stdout.splitlines()]

    def file(self, path):
        return self.query(Path(path).read_text())


def normalized(expression):
    value = expression.lower().replace('cast(`name` as char charset binary)', 'convert(name using binary)')
    value = value.replace('cast(name as char charset binary)', 'convert(name using binary)')
    value = re.sub(r'\blength\(', 'octet_length(', value)
    return re.sub(r'[\s`()]+', '', value)


def statements(path):
    sql = re.sub(r'(?m)^--.*$', '', path.read_text())
    return [part.strip() for part in sql.split(';') if part.strip()]


def pending_change(db, sql):
    table = re.match(r'ALTER TABLE (\w+)', sql).group(1)
    modify = re.search(r'MODIFY (\w+) (VARCHAR\(\d+\)|BIGINT) NOT NULL', sql)
    if modify:
        column, target = modify.groups()
        rows = db.query(f"SELECT column_type,is_nullable FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='{table}' AND column_name='{column}'")
        if len(rows) != 1:
            raise RuntimeError(f'Missing column {table}.{column}')
        actual, nullable = rows[0]
        if actual.lower() == target.lower() and nullable == 'NO':
            return False
        if actual.lower() not in (target.lower(), 'varchar(255)' if target.startswith('VARCHAR') else 'bigint'):
            raise RuntimeError(f'Unexpected column type {table}.{column}: {actual}')
        return True
    check = re.search(r'ADD CONSTRAINT (\w+) CHECK \((.*)\)$', sql)
    if check:
        name, expression = check.groups()
        rows = db.query(f"SELECT c.check_clause,t.enforced FROM information_schema.check_constraints c JOIN information_schema.table_constraints t ON c.constraint_schema=t.constraint_schema AND c.constraint_name=t.constraint_name WHERE c.constraint_schema=DATABASE() AND c.constraint_name='{name}' AND t.table_name='{table}'")
        if not rows:
            return True
        if len(rows) != 1 or normalized(rows[0][0]) != normalized(expression) or rows[0][1] != 'YES':
            raise RuntimeError(f'Existing CHECK differs: {name}: {rows}')
        return False
    generated = re.search(r'ADD COLUMN (\w+) (VARBINARY\(\d+\)) GENERATED ALWAYS AS \((.*)\) VIRTUAL', sql)
    if generated:
        column, kind, expression = generated.groups()
        rows = db.query(f"SELECT column_type,generation_expression,extra FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name='{table}' AND column_name='{column}'")
        if not rows:
            return True
        if len(rows) != 1 or rows[0][0].lower() != kind.lower() or normalized(rows[0][1]) != normalized(expression) or 'VIRTUAL GENERATED' not in rows[0][2]:
            raise RuntimeError(f'Existing generated column differs: {table}.{column}: {rows}')
        return False
    unique = re.search(r'ADD CONSTRAINT (\w+) UNIQUE \((.*)\)', sql)
    if unique:
        name, columns = unique.groups()
        rows = db.query(f"SELECT column_name,non_unique,coalesce(sub_part,0),is_visible FROM information_schema.statistics WHERE table_schema=DATABASE() AND table_name='{table}' AND index_name='{name}' ORDER BY seq_in_index")
        if not rows:
            return True
        expected = [[c.strip(), '0', '0', 'YES'] for c in columns.split(',')]
        if rows != expected:
            raise RuntimeError(f'Existing UNIQUE differs: {name}: {rows}')
        return False
    raise RuntimeError('Unrecognized migration statement: ' + sql)


def apply(database):
    db = MySQL(database)
    violations = db.file(HERE / '02-check-catalog-data.sql')
    invalid = [(name, count) for name, count in violations if int(count)]
    if invalid:
        raise RuntimeError('Preflight failed; no data changed: ' + str(invalid))
    fixed = db.query("SELECT table_name,column_name,column_type,is_nullable FROM information_schema.columns WHERE table_schema=DATABASE() AND ((table_name='product' AND column_name='price') OR (table_name='stock' AND column_name IN ('quantity','product_id'))) ORDER BY table_name,column_name")
    if fixed != [['product', 'price', 'bigint', 'NO'], ['stock', 'product_id', 'bigint', 'NO'], ['stock', 'quantity', 'int', 'NO']]:
        raise RuntimeError('Unexpected baseline types: ' + str(fixed))
    sqls = statements(HERE / '03-add-catalog-column-constraints.sql') + statements(HERE / '04-add-active-name-uniqueness.sql')
    pending = [sql for sql in sqls if pending_change(db, sql)]
    # Inspect every existing definition before the first ALTER (DDL commits individually).
    for sql in pending:
        db.query(sql + ';')
    if any(pending_change(db, sql) for sql in sqls):
        raise RuntimeError('Post-migration definitions differ')
    print(f'{database}: preflight OK, {len(pending)} changes, definitions verified')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('database')
    args = parser.parse_args()
    apply(args.database)
