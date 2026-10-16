#!/usr/bin/env python3
"""Populate the empty Compose benchmark database with deterministic query data."""

import argparse
from datetime import datetime, timedelta
from pathlib import Path
import subprocess
import sys

ROOT = Path(__file__).resolve().parents[1]
DATABASE = "loopers_benchmark"
TABLES = ("brand", "users", "product", "product_like")
PRODUCTS = 10_000
BRANDS = 100
USERS = 200
BATCH_SIZE = 500
DELETED_AT = "'2026-09-01 00:00:00'"


def mysql(sql):
    command = [
        "docker", "compose", "-f", str(ROOT / "docker/infra-compose.yml"),
        "--profile", "benchmark", "exec", "-T", "mysql-benchmark", "sh", "-c",
        'MYSQL_PWD="$MYSQL_PASSWORD" mysql --batch --skip-column-names '
        '--default-character-set=utf8mb4 -u "$MYSQL_USER" -D loopers_benchmark',
    ]
    result = subprocess.run(command, input=sql, text=True, capture_output=True, check=True)
    return result.stdout.strip()


def timestamp(i):
    return "'" + (datetime(2026, 1, 1) + timedelta(days=i % 30)).strftime("%Y-%m-%d %H:%M:%S") + "'"


def brand_id(i):
    return (i - 1) % BRANDS + 1


def price(i):
    return ((i * 37) % 1000) * 100 + 100


def like_count(i):
    return 150 if i % 113 == 0 else i % 11


def deleted(i):
    return brand_id(i) >= 99 or i % 97 == 0


def batches(table, columns, rows):
    statements = []
    for start in range(0, len(rows), BATCH_SIZE):
        values = ",\n".join("(" + ",".join(row) + ")" for row in rows[start:start + BATCH_SIZE])
        statements.append(f"INSERT INTO {table} ({columns}) VALUES\n{values};")
    return statements


def build_sql():
    statements = ["SET SESSION time_zone = '+00:00';", "START TRANSACTION;"]
    brands = []
    users = []
    products = []
    likes = []
    for i in range(1, BRANDS + 1):
        brands.append((str(i), timestamp(0), timestamp(0), DELETED_AT if i >= 99 else "NULL",
                       f"'W4 Brand {i:03d}'", "'Deterministic benchmark seed'"))
    for i in range(1, USERS + 1):
        users.append((str(i), timestamp(0), timestamp(0), "NULL"))
    for i in range(1, PRODUCTS + 1):
        products.append((str(i), timestamp(i), timestamp(i), DELETED_AT if deleted(i) else "NULL",
                         str(brand_id(i)), f"'W4 Product {i:05d}'", str(price(i)), str(i % 101)))
        for k in range(like_count(i)):
            # Each product has at most 150 distinct users; the relationship is unique.
            likes.append((str(len(likes) + 1), timestamp(i), timestamp(i), "NULL",
                          str(i), str((i * 13 + k) % USERS + 1)))
    statements += batches("brand", "id,created_at,updated_at,deleted_at,name,description", brands)
    statements += batches("users", "id,created_at,updated_at,deleted_at", users)
    statements += batches("product", "id,created_at,updated_at,deleted_at,brand_id,name,price,quantity", products)
    statements += batches("product_like", "id,created_at,updated_at,deleted_at,product_id,user_id", likes)
    statements.append("COMMIT;")
    return "\n".join(statements), len(likes)


def verify(expected_likes):
    actual = mysql("""
SELECT COUNT(*), SUM(deleted_at IS NULL), SUM(price) FROM product;
SELECT COUNT(*), SUM(deleted_at IS NULL) FROM brand;
SELECT COUNT(*) FROM users;
SELECT COUNT(*) FROM product_like;
SELECT COUNT(*) FROM product_like l
LEFT JOIN product p ON p.id=l.product_id LEFT JOIN users u ON u.id=l.user_id
WHERE p.id IS NULL OR u.id IS NULL;
SELECT COUNT(*) FROM product p LEFT JOIN brand b ON b.id=p.brand_id
WHERE b.id IS NULL OR (p.deleted_at IS NULL AND b.deleted_at IS NOT NULL);
""").splitlines()
    expected = [
        f"{PRODUCTS}\t{sum(not deleted(i) for i in range(1, PRODUCTS + 1))}\t{sum(price(i) for i in range(1, PRODUCTS + 1))}",
        f"{BRANDS}\t98", str(USERS), str(expected_likes), "0", "0",
    ]
    if actual != expected:
        raise RuntimeError(f"Seed verification failed. expected={expected}, actual={actual}")
    print(f"Verified: products={PRODUCTS}, visible={sum(not deleted(i) for i in range(1, PRODUCTS + 1))}, "
          f"brands={BRANDS}, users={USERS}, likes={expected_likes}")
    print("Brand 7 / price_asc / first 20: id, price, likes")
    print(mysql("""
SELECT p.id, p.price, (SELECT COUNT(*) FROM product_like l WHERE l.product_id=p.id)
FROM product p JOIN brand b ON b.id=p.brand_id
WHERE p.brand_id=7 AND p.deleted_at IS NULL AND b.deleted_at IS NULL
ORDER BY p.price ASC, p.created_at DESC, p.id DESC LIMIT 20;
"""))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--verify-only", action="store_true", help="check existing seed without inserting")
    args = parser.parse_args()
    if mysql("SELECT DATABASE();") != DATABASE:
        raise RuntimeError("Unexpected database; refusing to seed")
    sql, expected_likes = build_sql()
    if not args.verify_only:
        counts = mysql("SELECT " + ",".join(f"(SELECT COUNT(*) FROM {table})" for table in TABLES) + ";")
        if counts != "0\t0\t0\t0":
            raise RuntimeError("Seed tables are not empty. No data changed. Use --verify-only to check the existing seed.")
        print("Inserting deterministic seed into Compose mysql-benchmark in one transaction...", flush=True)
        mysql(sql)
    verify(expected_likes)


if __name__ == "__main__":
    try:
        main()
    except subprocess.CalledProcessError as error:
        print(error.stderr, file=sys.stderr)
        sys.exit(1)
    except RuntimeError as error:
        print(error, file=sys.stderr)
        sys.exit(1)
