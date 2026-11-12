#!/usr/bin/env python3
"""One-time ApplyWell MariaDB -> PostgreSQL data cutover.

The PostgreSQL schema must already have been created by Flyway V1-V36.
The MariaDB source should be restored from a verified backup and kept immutable.
"""

from __future__ import annotations

import argparse
import hashlib
import json
import os
import re
import sys
from dataclasses import dataclass
from datetime import date, datetime, time
from decimal import Decimal
from uuid import UUID

# The dependencies are loaded only when connecting; pure helpers can be unit-tested
# without a running MariaDB or PostgreSQL instance.

EXCLUDED = {"flyway_schema_history"}
SEED_STATUSES = (
    (1, "RH", 1),
    (2, "Pending HR Response", 2),
    (3, "Pending Hiring Manager Response", 3),
    (4, "Technical Test", 4),
    (5, "Pending Technical Test Response", 5),
    (6, "Offer Negotiation", 6),
    (7, "Ghosting", 7),
    (8, "Rejected", 8),
    (9, "Approved", 9),
)
NAME = re.compile(r"[a-z][a-z0-9_]*\Z")


@dataclass(frozen=True)
class Column:
    name: str
    pg_type: str


def safe_name(name: str) -> str:
    if not NAME.fullmatch(name):
        raise ValueError(f"Unexpected identifier {name!r}")
    return name


def convert(value: object, pg_type: str) -> object:
    """Convert values using the *target* type, never a BINARY(16) guess."""
    if value is None:
        return None
    if isinstance(value, memoryview):
        value = value.tobytes()
    if pg_type == "uuid":
        if isinstance(value, UUID):
            return value
        if isinstance(value, (bytes, bytearray)):
            if len(value) != 16:
                raise ValueError(f"Expected 16 bytes for UUID, got {len(value)}")
            return UUID(bytes=bytes(value))
        return UUID(str(value))
    if pg_type == "bool":
        if isinstance(value, (bytes, bytearray)):
            value = int.from_bytes(value, "big")
        if value not in (0, 1, False, True):
            raise ValueError(f"Invalid boolean value {value!r}")
        return bool(value)
    if pg_type in {"text", "varchar", "bpchar"} and isinstance(value, (bytes, bytearray)):
        # Never silently corrupt OAuth payloads (MariaDB BLOB -> PostgreSQL TEXT).
        return bytes(value).decode("utf-8", errors="strict")
    return value


def canonical(value: object) -> object:
    if value is None:
        return None
    if isinstance(value, UUID):
        return ["uuid", str(value)]
    if isinstance(value, (datetime, date, time)):
        return ["temporal", value.isoformat(timespec="microseconds") if isinstance(value, (datetime, time)) else value.isoformat()]
    if isinstance(value, Decimal):
        return ["decimal", str(value)]
    if isinstance(value, (bytes, bytearray, memoryview)):
        return ["binary", bytes(value).hex()]
    if isinstance(value, (int, bool, float, str)):
        return value
    raise TypeError(f"Cannot fingerprint value of type {type(value).__name__}")


class Fingerprint:
    """Order-independent content check, including every non-generated column."""

    def __init__(self) -> None:
        self.count = 0
        self.total = 0
        self.xor = 0

    def add(self, row: tuple[object, ...]) -> None:
        encoded = json.dumps([canonical(v) for v in row], ensure_ascii=False, separators=(",", ":"))
        digest = int.from_bytes(hashlib.sha256(encoded.encode("utf-8")).digest(), "big")
        self.total = (self.total + digest) % (1 << 256)
        self.xor ^= digest
        self.count += 1

    def summary(self) -> tuple[int, int, int]:
        return self.count, self.total, self.xor


def dependency_order(tables: set[str], foreign_keys: list[tuple[str, str]]) -> list[str]:
    parents = {name: set() for name in tables}
    for child, parent in foreign_keys:
        if child in tables and parent in tables and child != parent:
            parents[child].add(parent)
    result: list[str] = []
    while parents:
        ready = sorted(name for name, deps in parents.items() if not deps)
        if not ready:
            raise ValueError(f"Cyclic foreign keys: {sorted(parents)}")
        for name in ready:
            result.append(name)
            del parents[name]
        for deps in parents.values():
            deps.difference_update(ready)
    return result


def query_rows(conn: object, statement: str, params: tuple = ()) -> list[tuple]:
    with conn.cursor() as cursor:
        cursor.execute(statement, params)
        return list(cursor.fetchall())


def source_schema(conn: object, db: str) -> tuple[set[str], dict[str, set[str]]]:
    rows = query_rows(
        conn,
        "SELECT table_name FROM information_schema.tables "
        "WHERE table_schema = %s AND table_type = 'BASE TABLE'",
        (db,),
    )
    tables = {safe_name(row[0]) for row in rows} - EXCLUDED
    rows = query_rows(
        conn,
        "SELECT table_name, column_name FROM information_schema.columns "
        "WHERE table_schema = %s ORDER BY ordinal_position",
        (db,),
    )
    columns: dict[str, set[str]] = {table: set() for table in tables}
    for table, name in rows:
        if table in tables:
            columns[table].add(safe_name(name))
    return tables, columns


def target_schema(conn: object) -> tuple[set[str], dict[str, list[Column]]]:
    rows = query_rows(
        conn,
        "SELECT table_name, column_name, udt_name, is_generated "
        "FROM information_schema.columns WHERE table_schema = 'public' "
        "ORDER BY table_name, ordinal_position",
    )
    columns: dict[str, list[Column]] = {}
    for table, name, pg_type, generated in rows:
        safe_name(table)
        safe_name(name)
        columns.setdefault(table, [])
        if generated != "ALWAYS":
            columns[table].append(Column(name, pg_type))
    return set(columns) - EXCLUDED, columns


def ensure_schema(source_tables: set[str], source_cols: dict[str, set[str]],
                  target_tables: set[str], target_cols: dict[str, list[Column]]) -> None:
    if source_tables != target_tables:
        raise ValueError(
            f"Schema table mismatch: source only={sorted(source_tables - target_tables)}, "
            f"target only={sorted(target_tables - source_tables)}"
        )
    for table in sorted(source_tables):
        source_names = source_cols[table]
        target_names = {col.name for col in target_cols[table]}
        if source_names != target_names:
            raise ValueError(
                f"{table}: column mismatch: source only={sorted(source_names - target_names)}, "
                f"target only={sorted(target_names - source_names)}"
            )


def inspect_target(conn: object, tables: set[str], sql: object) -> None:
    """Allow only the 9 status rows inserted by Flyway V29."""
    history = query_rows(
        conn,
        "SELECT version, success FROM public.flyway_schema_history "
        "ORDER BY installed_rank DESC LIMIT 1",
    )
    if not history or history[0] != ("36", True):
        raise ValueError("Target must be a fresh database already migrated by Flyway through V36")
    for table in sorted(tables):
        count_query = sql.SQL("SELECT COUNT(*) FROM public.{}").format(sql.Identifier(table))
        with conn.cursor() as cursor:
            cursor.execute(count_query)
            count = cursor.fetchone()[0]
        if table == "application_statuses":
            if count != len(SEED_STATUSES):
                raise ValueError("Target application_statuses differs from Flyway V29 seed")
            actual = query_rows(conn, "SELECT id, name, display_order FROM public.application_statuses ORDER BY id")
            if tuple(actual) != SEED_STATUSES:
                raise ValueError("Target application_statuses has been modified; refusing overwrite")
        elif count:
            raise ValueError(f"Target {table} already contains {count} records; refusing to overwrite")


def fix_sequences(conn: object, tables: list[str], sql: object) -> None:
    """Repair identity/serial values after importing explicit IDs."""
    rows = query_rows(
        conn,
        "SELECT table_name, column_name FROM information_schema.columns "
        "WHERE table_schema = 'public' AND "
        "(is_identity = 'YES' OR column_default LIKE 'nextval(%')",
    )
    with conn.cursor() as cursor:
        for table, column in rows:
            if table not in tables:
                continue
            cursor.execute("SELECT pg_get_serial_sequence(%s, %s)", (f"public.{table}", column))
            seq = cursor.fetchone()[0]
            if seq:
                cursor.execute(
                    sql.SQL("SELECT MAX({}) FROM public.{}").format(sql.Identifier(column), sql.Identifier(table))
                )
                highest = cursor.fetchone()[0]
                # setval is not rolled back. Called only after all data checks pass.
                cursor.execute("SELECT setval(%s::regclass, %s, %s)", (seq, highest or 1, highest is not None))


def migrate(source: object, target: object, source_db: str, execute: bool, batch_size: int) -> None:
    from psycopg import sql
    import pymysql

    # MariaDB remains read-only, with one consistent InnoDB snapshot.
    with source.cursor() as cursor:
        cursor.execute("SET TRANSACTION ISOLATION LEVEL REPEATABLE READ")
        cursor.execute("START TRANSACTION WITH CONSISTENT SNAPSHOT, READ ONLY")
    try:
        source_tables, source_cols = source_schema(source, source_db)
        target_tables, target_cols = target_schema(target)
        ensure_schema(source_tables, source_cols, target_tables, target_cols)
        inspect_target(target, target_tables, sql)
        foreign_keys = query_rows(
            target,
            "SELECT child.relname, parent.relname FROM pg_constraint fk "
            "JOIN pg_class child ON child.oid = fk.conrelid "
            "JOIN pg_class parent ON parent.oid = fk.confrelid "
            "JOIN pg_namespace ns ON ns.oid = child.relnamespace "
            "WHERE fk.contype = 'f' AND ns.nspname = 'public'",
        )
        order = dependency_order(target_tables, foreign_keys)
        print(f"Source tables: {len(order)} | destination: Flyway V36 | mode: {'EXECUTE' if execute else 'CHECK ONLY'}")
        if not execute:
            for table in order:
                print(f"  {table}")
            print("No data changed. Re-run with --execute after backup validation.")
            return

        # Statuses are the sole Flyway-seeded dataset and must be replaced atomically.
        with target.cursor() as cursor:
            cursor.execute("DELETE FROM public.application_statuses")
        total_rows = 0
        for table in order:
            cols = target_cols[table]
            names = [col.name for col in cols]
            tick = chr(96)  # MariaDB identifier quote
            select = "SELECT " + ", ".join(f"{tick}{name}{tick}" for name in names) + f" FROM {tick}{table}{tick}"
            copy_sql = sql.SQL("COPY public.{} ({}) FROM STDIN").format(
                sql.Identifier(table), sql.SQL(", ").join(map(sql.Identifier, names))
            )
            before = Fingerprint()
            with source.cursor(pymysql.cursors.SSCursor) as src_cursor:
                src_cursor.execute(select)
                with target.cursor() as dst_cursor, dst_cursor.copy(copy_sql) as stream:
                    while batch := src_cursor.fetchmany(batch_size):
                        for record in batch:
                            normalized = tuple(convert(value, col.pg_type) for value, col in zip(record, cols, strict=True))
                            stream.write_row(normalized)
                            before.add(normalized)
            after = Fingerprint()
            verify_sql = sql.SQL("SELECT {} FROM public.{}").format(
                sql.SQL(", ").join(map(sql.Identifier, names)), sql.Identifier(table)
            )
            with target.cursor(name=f"verify_{table}") as verify_cursor:
                verify_cursor.execute(verify_sql)
                while batch := verify_cursor.fetchmany(batch_size):
                    for record in batch:
                        after.add(tuple(record))
            if before.summary() != after.summary():
                raise ValueError(f"Data verification failed for {table}: {before.count} vs {after.count} rows or contents")
            total_rows += before.count
            print(f"  {table}: {before.count} rows, SHA-256 fingerprints match")

        fix_sequences(target, order, sql)
        target.commit()
        print(f"COMMITTED: {total_rows} records copied and verified across {len(order)} tables")
        print("Keep the MariaDB backup; validate login, OAuth, MCP and exports before switching traffic.")
    except BaseException:
        target.rollback()
        raise
    finally:
        source.rollback()


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--execute", action="store_true", help="Actually write to the empty Flyway-prepared PostgreSQL")
    parser.add_argument("--batch-size", type=int, default=500)
    args = parser.parse_args()
    if args.batch_size <= 0:
        parser.error("--batch-size must be positive")

    import pymysql
    import psycopg

    mysql_db = os.environ.get("MIGRATE_MYSQL_DATABASE", "jobtracker")
    mysql_password = os.environ.get("MIGRATE_MYSQL_PASSWORD")
    pg_password = os.environ.get("MIGRATE_PG_PASSWORD")
    if not mysql_password or not pg_password:
        parser.error("MIGRATE_MYSQL_PASSWORD and MIGRATE_PG_PASSWORD environment variables are required")
    # Connection details are never printed or embedded in the repository.
    with pymysql.connect(
        host=os.environ.get("MIGRATE_MYSQL_HOST", "127.0.0.1"),
        port=int(os.environ.get("MIGRATE_MYSQL_PORT", "3306")),
        user=os.environ.get("MIGRATE_MYSQL_USER", "jobtracker"),
        password=mysql_password,
        database=mysql_db,
        charset="utf8mb4",
        autocommit=False,
    ) as source, psycopg.connect(
        host=os.environ.get("MIGRATE_PG_HOST", "127.0.0.1"),
        port=int(os.environ.get("MIGRATE_PG_PORT", "5432")),
        user=os.environ.get("MIGRATE_PG_USER", "jobtracker"),
        password=pg_password,
        dbname=os.environ.get("MIGRATE_PG_DATABASE", "jobtracker"),
        autocommit=False,
    ) as target:
        migrate(source, target, mysql_db, args.execute, args.batch_size)
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except Exception as exc:
        print(f"Migration aborted: {exc}", file=sys.stderr)
        sys.exit(1)
