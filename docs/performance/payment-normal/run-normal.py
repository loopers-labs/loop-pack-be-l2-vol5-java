#!/usr/bin/env python3
"""Run the normal payment workload against disposable MySQL/Redis and two local JVMs."""

import argparse
import concurrent.futures
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
import socket
import subprocess
import tempfile
import time
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parents[3]
HERE = Path(__file__).resolve().parent


def command(args, **kwargs):
    return subprocess.check_output(args, cwd=ROOT, text=True, **kwargs).strip()


def fetch(url, provider=False, timeout=3):
    request = urllib.request.Request(url, headers={"X-USER-ID": "135135"} if provider else {})
    with urllib.request.urlopen(request, timeout=timeout) as response:
        return response.read().decode()


def wait_until(probe, label, timeout=120):
    end = time.monotonic() + timeout
    while time.monotonic() < end:
        try:
            if probe():
                return
        except (OSError, subprocess.SubprocessError, urllib.error.URLError):
            pass
        time.sleep(1)
    raise RuntimeError(f"Timed out waiting for {label}")


def ensure_ports():
    for port in (18180, 18181, 18182):
        with socket.socket() as test:
            test.bind(("127.0.0.1", port))


def write_json(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + "\n")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--rate", type=int, required=True)
    parser.add_argument("--vus", type=int, required=True)
    parser.add_argument("--duration", required=True)
    parser.add_argument("--warmup", default="30s")
    args = parser.parse_args()
    if args.rate < 1 or args.vus < 1 or not all(re.fullmatch(r"[1-9]\d*[smh]", x)
                                              for x in (args.duration, args.warmup)):
        parser.error("Positive rate/VUs and duration such as 5m are required")
    ensure_ports()
    stamp = dt.datetime.now(dt.timezone.utc).strftime("%Y%m%dT%H%M%SZ")
    out = HERE / "results" / f"normal-{stamp}"
    out.mkdir(parents=True, exist_ok=False)
    temp = Path(tempfile.mkdtemp(prefix="pg-normal-"))
    prefix = "pg-normal-" + secrets.token_hex(4)
    password = secrets.token_hex(24)
    credentials = temp / "mysql.env"
    credentials.write_text("MYSQL_ALLOW_EMPTY_PASSWORD=yes\nMYSQL_DATABASE=loopers\n"
                           "MYSQL_USER=application\nMYSQL_PASSWORD=" + password + "\n")
    credentials.chmod(0o600)
    containers = []
    processes = []
    handles = []
    mysql = prefix + "-mysql"
    redis = prefix + "-redis"
    pg_url = "http://127.0.0.1:18182"
    commerce_url = "http://127.0.0.1:18180"

    def sql(query):
        return command(["docker", "exec", mysql, "mysql", "--user=root", "--batch",
                        "--skip-column-names", "--database=loopers", "--execute=" + query])

    def snapshot():
        queries = {
            "orders": "SELECT JSON_OBJECT('total',COUNT(*),'confirmed',COALESCE(SUM(status='CONFIRMED'),0),"
                      "'pending',COALESCE(SUM(status='PAYMENT_PENDING'),0),'failed',COALESCE(SUM(status='PAYMENT_FAILED'),0),"
                      "'draft',COALESCE(SUM(status='DRAFT'),0)) FROM orders",
            "attempts": "SELECT JSON_OBJECT('total',COUNT(*),'succeeded',COALESCE(SUM(status='SUCCEEDED'),0),"
                        "'unterminated',COALESCE(SUM(status IN ('REQUESTED','PENDING','UNKNOWN')),0),"
                        "'conflicts',COALESCE(SUM(conflict_reason IS NOT NULL),0),'maxId',COALESCE(MAX(id),0)) FROM payment_attempts",
            "stock": "SELECT JSON_OBJECT('available',stock,'reserved',"
                     "(SELECT COALESCE(SUM(i.quantity),0) FROM order_items i JOIN orders o ON o.id=i.order_id "
                     "WHERE i.product_id=p.id AND o.status='PAYMENT_PENDING' AND o.payment_method='PG')) "
                     "FROM products p WHERE id=1",
            "points": "SELECT JSON_OBJECT('rows',COUNT(*),'balance',COALESCE(SUM(balance),0)) FROM points",
        }
        return {name: json.loads(sql(query)) for name, query in queries.items()}

    def collect_stats(after_id):
        ids = sql(f"SELECT provider_order_id FROM payment_attempts WHERE id>{after_id} ORDER BY id").splitlines()

        def one(provider_id):
            return {"providerOrderId": provider_id, **json.loads(fetch(
                pg_url + "/test-control/v1/orders/" + provider_id + "/stats", True))}

        with concurrent.futures.ThreadPoolExecutor(max_workers=8) as pool:
            rows = list(pool.map(one, ids))
        totals = {key: sum(row[key] for row in rows)
                  for key in ("createRequests", "lookupRequests", "callbackAttempts", "transactions")}
        return {"totals": totals, "orders": rows}

    def sample(handle, phase):
        record = {"at": dt.datetime.now(dt.timezone.utc).isoformat(), "phase": phase}
        try:
            text = fetch("http://127.0.0.1:18181/actuator/prometheus")
            wanted = ("process_cpu_usage", "system_cpu_usage", "jvm_threads_live_threads",
                      "jvm_memory_used_bytes", "jvm_memory_max_bytes", "hikaricp_connections",
                      "tomcat_threads", "process_files_open_files")
            record["commerceMetrics"] = [line for line in text.splitlines()
                                         if line.startswith(wanted)]
        except (OSError, urllib.error.URLError) as error:
            record["metricsError"] = type(error).__name__
        record["processPs"] = command(["ps", "-p", ",".join(str(p.pid) for p in processes),
                                        "-o", "pid=,%cpu=,rss=,etime="])
        handle.write(json.dumps(record) + "\n")
        handle.flush()

    def phase(name, mode, duration=None):
        before = snapshot()
        env = {**os.environ, "K6_NO_USAGE_REPORT": "true", "PG_BENCH_BASE_URL": commerce_url,
               "PG_BENCH_USER_IDS": ",".join(str(n) for n in range(1, 51)),
               "PG_BENCH_PRODUCT_ID": "1", "PG_BENCH_MODE": mode, "PG_BENCH_PHASE": name,
               "PG_BENCH_OUTPUT": str(out), "PG_BENCH_RATE": str(args.rate),
               "PG_BENCH_VUS": str(args.vus), "PG_BENCH_DURATION": duration or "1s"}
        cmd = ["k6", "run", "--quiet", "--log-format", "raw", "--log-output", "stdout",
               str(HERE / "k6/normal-payment.k6.js")]
        print(f"Starting {name}: mode={mode}, duration={duration}, rate={args.rate}, VUs={args.vus}", flush=True)
        start = time.monotonic()
        with (out / f"{name}-events.log").open("w") as log, (out / f"{name}-resources.jsonl").open("w") as resources:
            child = subprocess.Popen(cmd, cwd=ROOT, env=env, stdout=log, stderr=subprocess.STDOUT)
            last_report = -30
            while child.poll() is None:
                sample(resources, name)
                elapsed = time.monotonic() - start
                if elapsed - last_report >= 30:
                    print(f"{name}: {elapsed:.0f}s elapsed", flush=True)
                    last_report = elapsed
                time.sleep(5)
            code = child.returncode
        after = snapshot()
        stats = collect_stats(before["attempts"]["maxId"])
        write_json(out / f"{name}-state.json", {"before": before, "after": after,
                   "pg": stats, "k6ExitCode": code, "elapsedSeconds": time.monotonic() - start})
        print(f"Finished {name}: k6={code}, PG={stats['totals']}, states={after['orders']}", flush=True)
        if code:
            raise RuntimeError(f"{name}: k6 failed; inspect {out / (name + '-events.log')}")

    try:
        command(["docker", "run", "--detach", "--name", mysql, "--cpus=2", "--memory=1g",
                 "--env-file", str(credentials), "--publish", "127.0.0.1::3306", "mysql:8.0"])
        containers.append(mysql)
        command(["docker", "run", "--detach", "--name", redis, "--cpus=1", "--memory=256m",
                 "--publish", "127.0.0.1::6379", "redis:7.0"])
        containers.append(redis)
        mysql_port = int(command(["docker", "port", mysql, "3306/tcp"]).split(":")[-1])
        redis_port = int(command(["docker", "port", redis, "6379/tcp"]).split(":")[-1])
        wait_until(lambda: command(["docker", "exec", mysql, "mysqladmin", "--protocol=tcp",
                                    "--host=127.0.0.1", "ping", "--silent"], stderr=subprocess.DEVNULL), "MySQL")
        # init.sql supplies the same schema, including the stock reservation index.
        subprocess.run(["docker", "exec", "--interactive", mysql, "mysql", "--user=root"],
                       input=(ROOT / "docker/mysql/init.sql").read_text(), text=True, check=True)
        sql("INSERT INTO brands(id,name,created_at,updated_at) VALUES(1,'PG baseline',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));"
            "INSERT INTO products(id,brand_id,name,price,stock,created_at,updated_at) "
            "VALUES(1,1,'PG baseline product',1000,100000,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));"
            + "".join(f"INSERT INTO users(id,created_at,updated_at) VALUES({n},UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));"
                      f"INSERT INTO points(user_id,balance,created_at,updated_at) VALUES({n},100000,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));"
                      for n in range(1, 51)))
        jars = {"pg": ROOT / "apps/test-pg/build/libs/test-pg.jar",
                "commerce": ROOT / f"apps/commerce-api/build/libs/commerce-api-{command(['git','rev-parse','--short','HEAD'])}.jar"}
        env = {**os.environ, "MYSQL_HOST": "127.0.0.1", "MYSQL_PORT": str(mysql_port),
               "MYSQL_USER": "application", "MYSQL_PWD": password,
               "SPRING_SECURITY_USER_PASSWORD": secrets.token_hex(24),
               "REDIS_MASTER_HOST": "127.0.0.1", "REDIS_MASTER_PORT": str(redis_port),
               "REDIS_REPLICA_1_HOST": "127.0.0.1", "REDIS_REPLICA_1_PORT": str(redis_port)}
        settings = {
            "pg": ["--server.address=127.0.0.1", "--server.port=18182", "--test-pg.control.enabled=true",
                   "--test-pg.processing-mode=AUTO", "--test-pg.processing-delay=3s"],
            "commerce": ["--spring.profiles.active=bench", "--server.port=18180", "--management.server.port=18181",
                         f"--datasource.mysql-jpa.main.jdbc-url=jdbc:mysql://127.0.0.1:{mysql_port}/loopers?useSSL=false&allowPublicKeyRetrieval=true",
                         "--spring.jpa.hibernate.ddl-auto=validate", "--spring.jpa.show-sql=false",
                         "--payment.pg.base-url=" + pg_url,
                         "--payment.pg.callback-url=" + commerce_url + "/api/v1/payments/callback",
                         "--payment.pg.lookup-circuit-breaker.enabled=false", "--payment.periodic-check.enabled=true",
                         "--logging.level.org.hibernate.SQL=OFF"],
        }
        metadata = {"recordedAt": stamp, "gitCommit": command(["git", "rev-parse", "HEAD"]),
                    "gitStatus": command(["git", "status", "--short"]), "java": command(["java", "-version"], stderr=subprocess.STDOUT),
                    "k6": command(["k6", "version"]), "hostLogicalCpus": os.cpu_count(), "workload": vars(args),
                    "settings": settings, "containerLimits": {"mysql": "2 CPU / 1 GiB", "redis": "1 CPU / 256 MiB"},
                    "redisTopology": "one isolated Redis used for both master and replica endpoints",
                    "jvmOptions": ["-XX:ActiveProcessorCount=2", "-Xms128m", "-Xmx512m"],
                    "nativeJvmCpuLimit": "no OS CPU quota; JVM active processor count is 2",
                    "sourceSha256": {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest()
                                     for p in [*jars.values(), Path(__file__).resolve(), HERE / "k6/normal-payment.k6.js", ROOT / "docker/mysql/init.sql"]}}
        write_json(out / "environment.json", metadata)
        for name in ("pg", "commerce"):
            log = (out / f"{name}.log").open("w")
            handles.append(log)
            processes.append(subprocess.Popen(["java", *metadata["jvmOptions"], "-jar", str(jars[name]),
                                               *settings[name]], cwd=ROOT, env=env, stdout=log, stderr=subprocess.STDOUT))
        wait_until(lambda: json.loads(fetch(pg_url + "/test-control/v1/lookup", True))["delayMillis"] == 0, "PG")
        wait_until(lambda: json.loads(fetch("http://127.0.0.1:18181/actuator/health"))["status"] == "UP", "Commerce")
        write_json(out / "initial-state.json", snapshot())
        phase("smoke", "smoke")
        phase("warmup", "load", args.warmup)
        phase("measurement", "load", args.duration)
        print(f"Completed. Results: {out}", flush=True)
    except BaseException as error:
        write_json(out / "run-error.json", {"type": type(error).__name__, "message": str(error)})
        raise
    finally:
        for process in reversed(processes):
            process.terminate()
            try:
                process.wait(timeout=20)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()
        for handle in handles:
            handle.close()
        for name in reversed(containers):
            subprocess.run(["docker", "rm", "--force", "--volumes", name], stdout=subprocess.DEVNULL,
                           stderr=subprocess.DEVNULL, check=False)
        credentials.unlink(missing_ok=True)


if __name__ == "__main__":
    main()
