"""Load a verified CSV dataset into an EMPTY perf_* database; commit only after verification."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import uuid

from validate import TABLES, validate, require
sys.path.insert(0, str(Path(__file__).resolve().parents[2] / 'migrations'))
from apply_catalog_constraints import MySQL, apply


class Session:
    def __init__(self, db):
        self.errors = tempfile.TemporaryFile(mode='w+t')
        self.proc = subprocess.Popen(db.command + ['--unbuffered'], stdin=subprocess.PIPE,
                                     stdout=subprocess.PIPE, stderr=self.errors, text=True)

    def query(self, sql, consume=None):
        marker = 'END_' + uuid.uuid4().hex
        self.proc.stdin.write(sql + ";\nSELECT '" + marker + "';\n")
        self.proc.stdin.flush()
        rows = []
        while True:
            line = self.proc.stdout.readline()
            if not line:
                self.errors.seek(0)
                raise RuntimeError('MySQL session ended: ' + self.errors.read())
            line = line.rstrip('\n')
            if line == marker:
                return rows
            if consume:
                consume(line)
            else:
                rows.append(line.split('\t'))

    def close(self):
        self.proc.stdin.close()
        self.proc.stdout.close()
        self.proc.wait(timeout=30)
        self.errors.close()


def literal(value):
    return "'" + str(value).replace('\\', '\\\\').replace("'", "''") + "'"


def load(directory, database):
    require(database.startswith('perf_'), 'Only isolated perf_* databases may be loaded')
    manifest = validate(directory)
    db = MySQL(database)
    for table, _ in TABLES.values():
        require(db.query(f'SELECT COUNT(*) FROM {table}')[0][0] == '0', f'{table} is not empty; refusing to overwrite')
    apply(database)
    session = Session(db)
    report = {'database': database, 'dataset': Path(directory).name, 'verified': {}}
    try:
        session.query("SET SESSION time_zone='+00:00'")
        session.query("SET SESSION sql_mode='STRICT_ALL_TABLES,NO_ZERO_DATE,NO_ZERO_IN_DATE,ERROR_FOR_DIVISION_BY_ZERO,NO_ENGINE_SUBSTITUTION'")
        session.query('START TRANSACTION')
        for kind, (table, headers) in TABLES.items():
            path = (Path(directory) / (kind + '.csv')).resolve()
            columns = ','.join('@deleted_at' if c == 'deleted_at' else c for c in headers)
            sql = f'''LOAD DATA LOCAL INFILE {literal(path)} INTO TABLE {table}
                CHARACTER SET utf8mb4
                FIELDS TERMINATED BY ',' OPTIONALLY ENCLOSED BY '"' ESCAPED BY '"'
                LINES TERMINATED BY '\\n' IGNORE 1 LINES
                ({columns}) SET deleted_at=NULLIF(@deleted_at, '')'''
            warnings = session.query(sql + '; SHOW COUNT(*) WARNINGS')
            require(warnings == [['0']], f'{table}: LOAD DATA produced warnings: {warnings}')
            count = int(session.query(f'SELECT COUNT(*) FROM {table}')[0][0])
            require(count == manifest['files'][path.name]['rows'], f'{table}: row count differs')
            values = []
            for column in headers:
                if column.endswith('_at'):
                    expr = f"DATE_FORMAT({column}, '%Y-%m-%d %H:%i:%s.%f')"
                elif column == 'name':
                    expr = 'name'
                else:
                    expr = f'CAST({column} AS CHAR CHARACTER SET utf8mb4)'
                values.append(f"COALESCE(HEX({expr}), '')")
            digest = hashlib.sha256()
            session.query(f"SELECT SHA2(CONCAT_WS('|', {','.join(values)}), 256) FROM {table} ORDER BY id",
                          lambda line: digest.update((line + '\n').encode('ascii')))
            checksum = digest.hexdigest()
            require(checksum == manifest['files'][path.name]['ordered_row_sha256'], f'{table}: values differ from CSV')
            report['verified'][table] = {'rows': count, 'ordered_row_sha256': checksum, 'warnings': 0}
            print(f'{table}: {count} rows, every CSV field verified', flush=True)
        session.query('COMMIT')
    except BaseException:
        if session.proc.poll() is None:
            try:
                session.query('ROLLBACK')
            except (RuntimeError, BrokenPipeError):
                pass
        raise
    finally:
        session.close()
    report_path = Path(__file__).resolve().parents[1] / 'load-reports' / (database + '.json')
    report_path.parent.mkdir(exist_ok=True)
    report_path.write_text(json.dumps(report, indent=2) + '\n')
    print(f'Committed verified dataset to {database}; report: {report_path}')


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('directory', type=Path)
    parser.add_argument('database')
    args = parser.parse_args()
    load(args.directory, args.database)
