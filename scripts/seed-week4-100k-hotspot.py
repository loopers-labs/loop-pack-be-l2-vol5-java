#!/usr/bin/env python3
"""Add a like hotspot on top of the loopers_benchmark_100k_hotspot database.

This DB is a clone of loopers_benchmark_100k (see seed-week4-100k.py) made specifically
for the "선택 심화: 데이터 쏠림" (data skew) scenario from the week4 assignment. It is
NOT the main benchmark DB used for the 05/13 plan comparisons, so this script never
touches loopers_benchmark or loopers_benchmark_100k.

It adds NEW_USERS distinct users (ids starting right after the existing 200) and makes
every one of them like TARGET_PRODUCT, so that one product goes from a handful of likes
to NEW_USERS likes while every other product is untouched. The UNIQUE(user_id, product_id)
constraint on product_like means a product can have at most as many likes as there are
distinct users, so new users are required to push one product's like count far past 200.
"""

import argparse
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
DATABASE = "loopers_benchmark_100k_hotspot"
TARGET_PRODUCT = 97407
NEW_USERS = 50_000
EXISTING_USERS = 200
EXISTING_LIKES = 628_186
BATCH_SIZE = 1000


def mysql(sql):
    command = [
        "docker", "compose", "-f", str(ROOT / "docker/infra-compose.yml"),
        "--profile", "benchmark", "exec", "-T", "mysql-benchmark", "sh", "-c",
        'MYSQL_PWD="$MYSQL_PASSWORD" mysql --batch --skip-column-names '
        f'--default-character-set=utf8mb4 -u "$MYSQL_USER" -D {DATABASE}',
    ]
    result = subprocess.run(command, input=sql, text=True, capture_output=True, check=True)
    return result.stdout.strip()


def batches(table, columns, rows):
    statements = []
    for start in range(0, len(rows), BATCH_SIZE):
        values = ",\n".join("(" + ",".join(row) + ")" for row in rows[start:start + BATCH_SIZE])
        statements.append(f"INSERT INTO {table} ({columns}) VALUES\n{values};")
    return statements


def build_sql():
    first_user = EXISTING_USERS + 1
    user_ids = range(first_user, first_user + NEW_USERS)
    statements = ["SET SESSION time_zone = '+00:00';", "START TRANSACTION;"]
    users = [(str(uid), "'2026-01-01 00:00:00'", "'2026-01-01 00:00:00'", "NULL") for uid in user_ids]
    likes = [(str(EXISTING_LIKES + i + 1), "'2026-01-01 00:00:00'", "'2026-01-01 00:00:00'", "NULL",
              str(TARGET_PRODUCT), str(uid)) for i, uid in enumerate(user_ids)]
    statements += batches("users", "id,created_at,updated_at,deleted_at", users)
    statements += batches("product_like", "id,created_at,updated_at,deleted_at,product_id,user_id", likes)
    statements.append("COMMIT;")
    return "\n".join(statements)


def verify():
    actual = mysql(f"""
SELECT COUNT(*) FROM users;
SELECT COUNT(*) FROM product_like WHERE product_id = {TARGET_PRODUCT};
SELECT COUNT(*) FROM product_like;
""").splitlines()
    expected = [str(EXISTING_USERS + NEW_USERS), str(NEW_USERS + 2), str(EXISTING_LIKES + NEW_USERS)]
    if actual != expected:
        raise RuntimeError(f"Hotspot seed verification failed. expected={expected}, actual={actual}")
    print(f"Verified: users={actual[0]}, product {TARGET_PRODUCT} likes={actual[1]}, "
          f"total product_like={actual[2]}")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--verify-only", action="store_true")
    args = parser.parse_args()
    if mysql("SELECT DATABASE();") != DATABASE:
        raise RuntimeError("Unexpected database; refusing to seed")
    if not args.verify_only:
        existing = int(mysql(f"SELECT COUNT(*) FROM product_like WHERE product_id = {TARGET_PRODUCT} "
                              f"AND user_id >= {EXISTING_USERS + 1};"))
        if existing != 0:
            raise RuntimeError("Hotspot likes already present. No data changed. Use --verify-only to check.")
        print(f"Inserting {NEW_USERS} users and the same number of likes on product "
              f"{TARGET_PRODUCT} into {DATABASE}...", flush=True)
        mysql(build_sql())
    verify()


if __name__ == "__main__":
    try:
        main()
    except subprocess.CalledProcessError as error:
        print(error.stderr, file=sys.stderr)
        sys.exit(1)
    except RuntimeError as error:
        print(error, file=sys.stderr)
        sys.exit(1)
