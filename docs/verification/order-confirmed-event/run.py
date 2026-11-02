#!/usr/bin/env python3
"""격리한 로컬 앱으로 전송 실패·정상 소비·streamer 재시작을 관찰한다."""

import json
from datetime import datetime
from pathlib import Path
import re
import socket
import subprocess
import tempfile
import time
import urllib.request
import uuid


ROOT = Path(__file__).resolve().parents[3]


def run(command, *, stdin=None, timeout=30, check=True):
    return subprocess.run(command, input=stdin, text=True, capture_output=True,
                          timeout=timeout, check=check)


def await_result(description, action, predicate=bool, timeout=90):
    deadline = time.monotonic() + timeout
    last_error = None
    while time.monotonic() < deadline:
        try:
            result = action()
            if predicate(result):
                return result
        except (subprocess.SubprocessError, OSError, ValueError) as error:
            last_error = error
        time.sleep(0.25)
    raise AssertionError(f"{description}: deadline 초과 ({last_error})")


def free_ports(count):
    sockets = [socket.socket() for _ in range(count)]
    try:
        for item in sockets:
            item.bind(("127.0.0.1", 0))
        return [item.getsockname()[1] for item in sockets]
    finally:
        for item in sockets:
            item.close()


def http(port, path, body=None):
    encoded = None if body is None else json.dumps(body).encode()
    request = urllib.request.Request(f"http://127.0.0.1:{port}{path}", data=encoded,
                                     headers={"X-USER-ID": "1", "Content-Type": "application/json"},
                                     method="POST")
    with urllib.request.urlopen(request, timeout=15) as response:
        return json.load(response)


def main():
    subprocess.run(["./gradlew", ":apps:commerce-api:bootJar", ":apps:commerce-streamer:bootJar",
                    ":apps:test-pg:bootJar"], cwd=ROOT, check=True)
    output = Path(tempfile.mkdtemp(prefix="loopers-order-flow-"))
    print(f"관찰 파일: {output}", flush=True)
    suffix = uuid.uuid4().hex[:10]
    mysql, kafka, redis = [f"loopers-flow-{suffix}-{kind}" for kind in ("mysql", "kafka", "redis")]
    mysql_port, kafka_port, redis_port, pg_port, api_port, streamer_port = free_ports(6)
    password = uuid.uuid4().hex
    topic, group = f"order-events-flow-{suffix}", f"purchase-activity-flow-{suffix}"
    containers, processes, handles = [], [], []

    def docker_start(name, options):
        run(["docker", "run", "-d", "--name", name, *options], timeout=120)
        containers.append(name)

    def sql(statement):
        result = run(["docker", "exec", "-i", "-e", f"MYSQL_PWD={password}", mysql,
                      "mysql", "-uroot", "--batch", "--skip-column-names", "--raw"], stdin=statement)
        return result.stdout.strip()

    def cli(tool, *arguments, timeout=30):
        return run(["docker", "exec", kafka, f"/opt/kafka/bin/{tool}.sh",
                    "--bootstrap-server", f"localhost:{kafka_port}", *arguments], timeout=timeout).stdout

    def topic_limit(size):
        cli("kafka-configs", "--alter", "--entity-type", "topics", "--entity-name", topic,
            "--add-config", f"max.message.bytes={size}")

    def log_ends():
        text = cli("kafka-get-offsets", "--topic", topic, "--time", "-1")
        return {int(parts[1]): int(parts[2]) for line in text.splitlines()
                if len(parts := line.split(":")) == 3 and parts[0] == topic}

    def offsets():
        text = cli("kafka-consumer-groups", "--describe", "--group", group)
        return {int(parts[2]): int(parts[3]) for line in text.splitlines()
                if len(parts := line.split()) >= 6 and parts[:2] == [group, topic] and parts[3].isdigit()}

    def start_app(app, port, arguments):
        jars = [path for path in (ROOT / "apps" / app / "build/libs").glob("*.jar")
                if not path.name.endswith("-plain.jar")]
        jar = max(jars, key=lambda path: path.stat().st_mtime)
        path = output / f"{app}-{len(processes)}.log"
        handle = path.open("w")
        handles.append(handle)
        process = subprocess.Popen(["java", "-Xms64m", "-Xmx256m", "-jar", str(jar),
                                    f"--server.port={port}", f"--management.server.port={port}", *arguments],
                                   cwd=ROOT, stdout=handle, stderr=subprocess.STDOUT)
        processes.append(process)

        def ready():
            if process.poll() is not None:
                raise RuntimeError(f"{app} 기동 실패: {path}\n{path.read_text()[-4000:]}")
            return re.search(r"Started .* in [\d.]+ seconds", path.read_text())

        await_result(f"{app} 기동", ready)
        return process, path

    def stop_app(process):
        process.terminate()
        try:
            process.wait(timeout=20)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=5)

    def snapshot(order_id):
        value = json.loads(sql(f"""
            SET time_zone='+00:00';
            SELECT JSON_OBJECT('orderId', o.id, 'eventId', CONCAT('order-confirmed:v1:',o.id),
                'status', o.status, 'confirmedAt', DATE_FORMAT(o.confirmed_at,'%Y-%m-%dT%H:%i:%s.%fZ'),
                'providerOrderId', p.provider_order_id, 'transactionKey', p.transaction_key,
                'items', (SELECT JSON_ARRAYAGG(JSON_OBJECT('productId',product_id,'quantity',quantity))
                    FROM loopers.order_items WHERE order_id=o.id),
                'stocks', (SELECT JSON_ARRAYAGG(JSON_OBJECT('productId',id,'stock',stock))
                    FROM loopers.products WHERE id IN (7,8)),
                'completionRows', (SELECT COUNT(*) FROM loopers.processed_order_confirmation WHERE order_id=o.id),
                'activities', COALESCE((SELECT JSON_ARRAYAGG(JSON_OBJECT('id',id,'productId',product_id,
                    'quantity',quantity,'confirmedAt',DATE_FORMAT(confirmed_at,'%Y-%m-%dT%H:%i:%s.%fZ'),
                    'createdAt',CAST(created_at AS CHAR),'updatedAt',CAST(updated_at AS CHAR)))
                    FROM loopers.product_purchase_activity WHERE order_id=o.id), JSON_ARRAY()))
            FROM loopers.orders o JOIN loopers.payment_attempts p ON p.order_id=o.id WHERE o.id={order_id};
            """))
        for key in ("items", "stocks", "activities"):
            value[key].sort(key=lambda item: item["productId"])
        return value

    def confirm():
        created = http(api_port, "/api/v1/orders", {"items": [{"productId": 7, "quantity": 2},
                                                            {"productId": 8, "quantity": 1}]})
        order_id = created["data"]["id"]
        http(api_port, f"/api/v1/orders/{order_id}/confirm",
             {"paymentAttemptKey": str(uuid.uuid4()), "cardType": "TEST", "cardNo": "0000"})
        await_result("PG 성공과 주문 확정", lambda: snapshot(order_id),
                     lambda value: value["status"] == "CONFIRMED")
        return order_id

    try:
        print("격리 MySQL·Kafka·Redis 시작", flush=True)
        docker_start(mysql, ["-p", f"127.0.0.1:{mysql_port}:3306", "-e", f"MYSQL_ROOT_PASSWORD={password}",
                            "-e", "MYSQL_USER=application", "-e", f"MYSQL_PASSWORD={password}",
                            "-e", "MYSQL_DATABASE=loopers", "mysql:8.0"])
        docker_start(redis, ["-p", f"127.0.0.1:{redis_port}:6379", "redis:7.0"])
        kafka_env = {"KAFKA_NODE_ID": "1", "KAFKA_PROCESS_ROLES": "broker,controller",
                     "KAFKA_LISTENERS": f"PLAINTEXT://:{kafka_port},CONTROLLER://:9093",
                     "KAFKA_ADVERTISED_LISTENERS": f"PLAINTEXT://localhost:{kafka_port}",
                     "KAFKA_CONTROLLER_LISTENER_NAMES": "CONTROLLER",
                     "KAFKA_LISTENER_SECURITY_PROTOCOL_MAP": "CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT",
                     "KAFKA_CONTROLLER_QUORUM_VOTERS": "1@localhost:9093",
                     "KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR": "1", "KAFKA_AUTO_CREATE_TOPICS_ENABLE": "false"}
        docker_start(kafka, ["-p", f"127.0.0.1:{kafka_port}:{kafka_port}",
                            *[argument for key, value in kafka_env.items() for argument in ("-e", f"{key}={value}")],
                            "apache/kafka:3.8.1"])
        await_result("MySQL 준비", lambda: sql("SELECT 1;"), timeout=120)
        sql((ROOT / "docker/mysql/init.sql").read_text())
        # 기존 DB에 적용하는 SQL도 같은 스키마에서 재실행할 수 있어야 한다.
        sql((ROOT / "docker/mysql/purchase-activity-migration.sql").read_text())
        sql("""INSERT INTO loopers.users(id,created_at,updated_at) VALUES(1,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));
            INSERT INTO loopers.brands(id,name,created_at,updated_at) VALUES(1,'flow',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));
            INSERT INTO loopers.products(id,brand_id,name,price,stock,created_at,updated_at)
            VALUES(7,1,'first',3500,10,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)),
                  (8,1,'second',4800,10,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));""")
        await_result("Kafka 준비", lambda: cli("kafka-topics", "--list") or True, timeout=120)
        cli("kafka-topics", "--create", "--topic", topic, "--partitions", "3", "--replication-factor", "1")
        topic_limit(1)
        start_app("test-pg", pg_port, ["--test-pg.processing-delay=1s"])
        common = ["--spring.profiles.active=local", f"--spring.kafka.bootstrap-servers=localhost:{kafka_port}",
                  f"--order.confirmed-event.topic={topic}",
                  f"--datasource.mysql-jpa.main.jdbc-url=jdbc:mysql://localhost:{mysql_port}/loopers",
                  "--datasource.mysql-jpa.main.username=application", f"--datasource.mysql-jpa.main.password={password}",
                  "--datasource.mysql-jpa.main.maximum-pool-size=5", "--datasource.mysql-jpa.main.minimum-idle=1",
                  "--datasource.redis.master.host=localhost", f"--datasource.redis.master.port={redis_port}",
                  "--datasource.redis.replicas[0].host=localhost", f"--datasource.redis.replicas[0].port={redis_port}"]
        _, api_log = start_app("commerce-api", api_port, [*common, "--product.list-cache.warm-up.enabled=false",
            "--payment.periodic-check.enabled=false", f"--payment.pg.base-url=http://127.0.0.1:{pg_port}",
            f"--payment.pg.callback-url=http://127.0.0.1:{api_port}/api/v1/payments/callback"])
        streamer_args = [*common, f"--order.confirmed-event.group-id={group}"]
        streamer, _ = start_app("commerce-streamer", streamer_port, streamer_args)
        print("누락 A: topic 크기 제한으로 commit 뒤 전송 실패 주입", flush=True)
        missing_id = confirm()
        await_result("전송 실패 콜백", lambda: api_log.read_text(),
                     lambda text: f"eventId=order-confirmed:v1:{missing_id}" in text
                     and "전송에 실패했습니다" in text)
        missing = snapshot(missing_id)
        empty_ends = log_ends()
        assert len(empty_ends) == 3 and all(offset == 0 for offset in empty_ends.values()), empty_ends
        assert missing["completionRows"] == 0 and missing["activities"] == [], missing
        topic_limit(1048576)
        print("정상 B: 제한 복구 후 실제 PG 확정 → broker → streamer DB", flush=True)
        normal_id = confirm()
        normal = await_result("정상 구매 활동 저장", lambda: snapshot(normal_id),
                              lambda value: value["completionRows"] == 1 and len(value["activities"]) == 2)
        for activity, item in zip(normal["activities"], normal["items"]):
            assert {key: activity[key] for key in ("productId", "quantity")} == item
            assert activity["confirmedAt"] == normal["confirmedAt"]
        record_text = cli("kafka-console-consumer", "--topic", topic, "--from-beginning", "--max-messages", "1",
                          "--timeout-ms", "10000", "--property", "print.partition=true",
                          "--property", "print.offset=true", "--property", "print.key=true")
        record_line = next(line for line in record_text.splitlines() if "Partition:" in line)
        partition, offset = map(int, re.search(r"Partition:(\d+)\s+Offset:(\d+)", record_line).groups())
        payload = json.loads(record_line[record_line.index("{"):])
        assert payload["orderId"] == normal_id and payload["eventId"] == normal["eventId"]
        assert payload["items"] == normal["items"]
        assert datetime.fromisoformat(payload["confirmedAt"]) == datetime.fromisoformat(normal["confirmedAt"])
        await_result("offset commit", offsets, lambda value: value.get(partition) == offset + 1)
        before_restart = {"missing": snapshot(missing_id), "normal": snapshot(normal_id)}
        ends, committed = log_ends(), offsets()
        assert sum(ends.values()) == 1, ends
        print("streamer 재시작: 활동·완료 기록·offset 보존 확인", flush=True)
        stop_app(streamer)
        start_app("commerce-streamer", streamer_port, streamer_args)
        await_result("재시작 group offset", offsets, lambda value: value == committed)
        assert {"missing": snapshot(missing_id), "normal": snapshot(normal_id)} == before_restart
        for order_id in (missing_id, normal_id):
            http(api_port, f"/api/v1/orders/{order_id}/payment/reconcile")
        assert log_ends() == ends
        assert {"missing": snapshot(missing_id), "normal": snapshot(normal_id)} == before_restart
        evidence = {"observedAt": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()), "topic": topic,
                    "group": group, "bootstrapServers": f"localhost:{kafka_port}",
                    "failureInjection": "topic max.message.bytes=1; restored to 1048576 for normal order",
                    "missingImmediatelyAfterFailure": missing, **before_restart,
                    "record": {"partition": partition, "offset": offset, "key": str(normal_id), "payload": payload},
                    "logEnds": ends, "committedOffsets": committed,
                    "restartPreserved": True, "duplicateReconcilePreserved": True}
        (output / "observations.json").write_text(json.dumps(evidence, ensure_ascii=False, indent=2) + "\n")
        print(json.dumps(evidence, ensure_ascii=False, indent=2), flush=True)
        print(f"관찰 성공. 원본: {output / 'observations.json'}", flush=True)
    finally:
        for process in reversed(processes):
            if process.poll() is None:
                stop_app(process)
        for handle in handles:
            handle.close()
        for name in reversed(containers):
            run(["docker", "rm", "-f", "-v", name], timeout=30, check=False)


if __name__ == "__main__":
    main()
