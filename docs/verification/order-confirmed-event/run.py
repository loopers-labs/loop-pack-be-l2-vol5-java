#!/usr/bin/env python3
"""격리 환경에서 직접 발행 누락 복구와 broker 확인 뒤 Relay 재시작을 관찰한다."""

from collections import Counter
import argparse
import json
from datetime import datetime
from pathlib import Path
import re
import socket
import subprocess
import sys
import tarfile
import tempfile
import time
import urllib.request
import uuid


ROOT = Path(__file__).resolve().parents[3]
DIRECT_PUBLICATION_REVISION = "c0806007aca993de80c7e2c70a6ec2b87b3b8089"


def run(command, *, stdin=None, timeout=30, check=True, cwd=ROOT):
    return subprocess.run(command, input=stdin, text=True, capture_output=True,
                          timeout=timeout, check=check, cwd=cwd)


def build_direct_publication_api(output):
    source = output / "direct-publication-source"
    archive_path = output / "direct-publication-source.tar"
    run(["git", "archive", "--format=tar", f"--output={archive_path}", DIRECT_PUBLICATION_REVISION], timeout=60)
    source.mkdir()
    with tarfile.open(archive_path) as archive:
        archive.extractall(source)
    install_before_send_pause_hook(source)
    run([str(source / "gradlew"), ":apps:commerce-api:bootJar"], timeout=600, cwd=source)
    jars = [path for path in (source / "apps/commerce-api/build/libs").glob("*.jar")
            if not path.name.endswith("-plain.jar")]
    if not jars:
        raise AssertionError("직접 발행 기준 앱 bootJar를 찾지 못했습니다.")
    return max(jars, key=lambda path: path.stat().st_mtime)


def install_before_send_pause_hook(source):
    publishers = list(source.rglob("OrderConfirmedKafkaPublisher.java"))
    if len(publishers) != 1:
        raise AssertionError(f"기준 revision에서 Kafka publisher를 하나 찾지 못했습니다: {publishers}")
    publisher = publishers[0]
    contents = publisher.read_text()
    send = "            kafkaTemplate.send(topic, message.key(), message).whenComplete((result, failure) -> {"
    if contents.count(send) != 1:
        raise AssertionError("기준 publisher의 send 경계가 예상한 한 곳과 다릅니다.")
    contents = contents.replace(send, "            pauseBeforeSendForRecoveryExperiment();\n" + send, 1)
    method = """
    private void pauseBeforeSendForRecoveryExperiment() {
        String markerPath = System.getProperty("order-confirmed.test.pause-before-send.marker");
        String releasePath = System.getProperty("order-confirmed.test.pause-before-send.release");
        if (markerPath == null || releasePath == null) {
            return;
        }
        java.nio.file.Path marker = java.nio.file.Path.of(markerPath);
        java.nio.file.Path release = java.nio.file.Path.of(releasePath);
        try {
            java.nio.file.Files.writeString(marker, "paused-after-commit-before-send");
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(180);
            while (!java.nio.file.Files.exists(release) && System.nanoTime() < deadline) {
                Thread.sleep(25);
            }
            if (!java.nio.file.Files.exists(release)) {
                throw new IllegalStateException("테스트용 발행 전 중단 제한 시간을 넘겼습니다.");
            }
        } catch (java.io.IOException failure) {
            throw new IllegalStateException("테스트용 발행 전 중단 표식을 쓸 수 없습니다.", failure);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("테스트용 발행 전 대기가 중단됐습니다.", failure);
        }
    }
"""
    marker = "    private void logFailure(OrderConfirmedEvent event, Throwable failure) {"
    if contents.count(marker) != 1:
        raise AssertionError("기준 publisher의 실패 로그 경계를 찾지 못했습니다.")
    publisher.write_text(contents.replace(marker, method + "\n" + marker, 1))


def await_result(description, action, predicate=bool, timeout=90):
    deadline = time.monotonic() + timeout
    last_error = None
    last_result = None
    while time.monotonic() < deadline:
        try:
            last_result = action()
            if predicate(last_result):
                return last_result
        except (subprocess.SubprocessError, OSError, ValueError) as error:
            detail = (error.stderr or error.stdout) if isinstance(error, subprocess.CalledProcessError) else str(error)
            last_error = f"{type(error).__name__}: {detail}"
        time.sleep(0.25)
    state = json.dumps(last_result, ensure_ascii=False, default=str)
    raise AssertionError(f"{description}: deadline 초과; 마지막 오류={last_error}; 마지막 상태={state[-5000:]}")


def await_observed(description, action, predicate, timeout=90):
    try:
        return await_result(description, action, predicate, timeout)
    except AssertionError as failure:
        try:
            last_observation = action()
        except Exception as observation_failure:
            last_observation = f"마지막 상태 조회 실패: {observation_failure}"
        raise AssertionError(f"{failure}; 마지막 관찰={json.dumps(last_observation, ensure_ascii=False, default=str)}") \
            from None


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


def main(capture_ui=False, section7_captures_only=False):
    if capture_ui and not sys.stdin.isatty():
        raise SystemExit("Kafka UI 캡처 모드는 대화형 터미널에서 실행하고 각 화면 뒤 Enter를 눌러야 합니다.")
    output = Path(tempfile.mkdtemp(prefix="loopers-order-flow-"))
    print(f"관찰 파일: {output}", flush=True)
    subprocess.run(["./gradlew", ":apps:commerce-api:bootJar", ":apps:commerce-streamer:bootJar",
                    ":apps:test-pg:bootJar"], cwd=ROOT, check=True)
    direct_api_jar = build_direct_publication_api(output)
    suffix = uuid.uuid4().hex[:10]
    mysql, kafka, redis = [f"loopers-flow-{suffix}-{kind}" for kind in ("mysql", "kafka", "redis")]
    ports = free_ports(7 if capture_ui else 6)
    mysql_port, kafka_port, redis_port, pg_port, api_port, streamer_port = ports[:6]
    ui_port = ports[6] if capture_ui else None
    password = uuid.uuid4().hex
    topic, group = f"order-events-flow-{suffix}", f"purchase-activity-flow-{suffix}"
    network = f"loopers-flow-{suffix}"
    containers, processes, handles = [], [], []

    def docker_start(name, options):
        network_options = ["--network", network] if capture_ui else []
        run(["docker", "run", "-d", "--name", name, *network_options, *options], timeout=120)
        containers.append(name)

    def sql(statement):
        try:
            result = run(["docker", "exec", "-i", "-e", f"MYSQL_PWD={password}", mysql,
                          "mysql", "-uroot", "--batch", "--skip-column-names", "--raw"], stdin=statement)
        except subprocess.CalledProcessError as failure:
            message = (failure.stderr or failure.stdout or "MySQL command failed").strip()
            raise subprocess.SubprocessError(f"격리 MySQL 쿼리가 실패했습니다: {message}") from None
        return result.stdout.strip()

    def mysql_is_stable():
        for _ in range(5):
            if sql("SELECT 1;") != "1":
                return False
            time.sleep(0.5)
        return True

    def cli(tool, *arguments, timeout=30):
        return run(["docker", "exec", kafka, f"/opt/kafka/bin/{tool}.sh",
                    "--bootstrap-server", "localhost:9092" if capture_ui else f"localhost:{kafka_port}",
                    *arguments], timeout=timeout).stdout

    def log_ends():
        text = cli("kafka-get-offsets", "--topic", topic, "--time", "-1")
        return {int(parts[1]): int(parts[2]) for line in text.splitlines()
                if len(parts := line.split(":")) == 3 and parts[0] == topic}

    def offsets():
        text = cli("kafka-consumer-groups", "--describe", "--group", group)
        return {int(parts[2]): int(parts[3]) for line in text.splitlines()
                if len(parts := line.split()) >= 6 and parts[:2] == [group, topic] and parts[3].isdigit()}

    def await_consumer_assignment():
        def assignment():
            text = cli("kafka-consumer-groups", "--describe", "--group", group, "--offsets")
            partitions = {int(parts[2]) for line in text.splitlines()
                          if len(parts := line.split()) >= 9 and parts[:2] == [group, topic]
                          and parts[2].isdigit() and parts[6] != "-"}
            return {"assignedPartitions": sorted(partitions), "groupDescription": text.strip()}

        return await_result("Consumer partition 배정", assignment,
                            lambda value: value["assignedPartitions"] == [0, 1, 2], timeout=90)

    def start_app(app, port, arguments, jvm_arguments=()):
        jar = direct_api_jar if app == "direct-publication-api" else max(
            [path for path in (ROOT / "apps" / app / "build/libs").glob("*.jar")
             if not path.name.endswith("-plain.jar")], key=lambda path: path.stat().st_mtime)
        path = output / f"{app}-{len(processes)}.log"
        handle = path.open("w")
        handles.append(handle)
        process = subprocess.Popen(["java", "-Xms64m", "-Xmx256m", *jvm_arguments, "-jar", str(jar),
                                    f"--server.port={port}", f"--management.server.port={port}", *arguments],
                                   cwd=ROOT, stdout=handle, stderr=subprocess.STDOUT)
        processes.append(process)

        def ready():
            if process.poll() is not None:
                raise RuntimeError(f"{app} 기동 실패: {path}\n{path.read_text()[-4000:]}")
            return re.search(r"Started .* in [\d.]+ seconds", path.read_text())

        await_result(f"{app} 기동", ready)
        return process, path

    def stop_app(process, timeout=20):
        process.terminate()
        forced = False
        try:
            process.wait(timeout=timeout)
        except subprocess.TimeoutExpired:
            forced = True
            process.kill()
            process.wait(timeout=5)
        return forced

    def snapshot(order_id):
        value = json.loads(sql(f"""
            SET time_zone='+00:00';
            SELECT JSON_OBJECT('orderId', o.id, 'eventId', CONCAT('order-confirmed:v1:',o.id),
                'status', o.status, 'confirmedAt', DATE_FORMAT(o.confirmed_at,'%Y-%m-%dT%H:%i:%s.%fZ'),
                'providerOrderId', p.provider_order_id, 'transactionKey', p.transaction_key,
                'pointBalance', (SELECT balance FROM loopers.points WHERE user_id=o.user_id),
                'items', (SELECT JSON_ARRAYAGG(JSON_OBJECT('productId',product_id,'quantity',quantity))
                    FROM loopers.order_items WHERE order_id=o.id),
                'stocks', (SELECT JSON_ARRAYAGG(JSON_OBJECT('productId',id,'stock',stock))
                    FROM loopers.products WHERE id IN (7,8)),
                'completionRows', (SELECT COUNT(*) FROM loopers.processed_order_confirmation WHERE order_id=o.id),
                'inboxEventIds', (SELECT JSON_ARRAYAGG(event_id) FROM loopers.processed_order_confirmation
                    WHERE order_id=o.id),
                'outbox', (SELECT JSON_OBJECT('eventId',event_id,'messageKey',message_key,'payload',payload,
                    'status',status,'attemptCount',attempt_count,'cycleAttemptCount',cycle_attempt_count,
                    'publishedAt',DATE_FORMAT(published_at,'%Y-%m-%dT%H:%i:%s.%fZ'))
                    FROM loopers.order_confirmed_outbox WHERE order_id=o.id),
                'outboxRows', (SELECT COUNT(*) FROM loopers.order_confirmed_outbox WHERE order_id=o.id),
                'activities', COALESCE((SELECT JSON_ARRAYAGG(JSON_OBJECT('id',id,'productId',product_id,
                    'quantity',quantity,'confirmedAt',DATE_FORMAT(confirmed_at,'%Y-%m-%dT%H:%i:%s.%fZ'),
                    'createdAt',CAST(created_at AS CHAR),'updatedAt',CAST(updated_at AS CHAR)))
                    FROM loopers.product_purchase_activity WHERE order_id=o.id), JSON_ARRAY()))
            FROM loopers.orders o JOIN loopers.payment_attempts p ON p.order_id=o.id WHERE o.id={order_id};
            """))
        for key in ("items", "stocks", "activities"):
            value[key].sort(key=lambda item: item["productId"])
        value["inboxEventIds"] = sorted(value["inboxEventIds"] or [])
        value["outbox"] = value["outbox"] or {}
        return value

    def pg_stats(provider_order_id):
        request = urllib.request.Request(
            f"http://127.0.0.1:{pg_port}/test-control/v1/orders/{provider_order_id}/stats",
            headers={"X-USER-ID": "135135"})
        with urllib.request.urlopen(request, timeout=10) as response:
            return json.load(response)

    def read_records(count):
        text = cli("kafka-console-consumer", "--topic", topic, "--from-beginning", "--max-messages", str(count),
                    "--timeout-ms", "10000", "--property", "print.partition=true",
                    "--property", "print.offset=true", "--property", "print.key=true")
        records = []
        for line in text.splitlines():
            fields = line.split("\t", maxsplit=3)
            if len(fields) == 4 and fields[0].startswith("Partition:") and fields[1].startswith("Offset:"):
                records.append({"partition": int(fields[0].split(":", maxsplit=1)[1]),
                                "offset": int(fields[1].split(":", maxsplit=1)[1]),
                                "key": fields[2], "payload": json.loads(fields[3])})
        if not records:
            raise AssertionError(f"Kafka record 출력에서 레코드를 읽지 못했습니다: {text!r}")
        return records

    def group_caught_up(expected_ends):
        return await_result("Consumer group이 복구 record를 모두 처리", offsets,
                            lambda committed: all(committed.get(partition, 0) >= end
                                                  for partition, end in expected_ends.items()))

    def confirm():
        created = http(api_port, "/api/v1/orders", {"items": [{"productId": 7, "quantity": 2},
                                                            {"productId": 8, "quantity": 1}]})
        order_id = created["data"]["id"]
        http(api_port, f"/api/v1/orders/{order_id}/confirm",
             {"paymentAttemptKey": str(uuid.uuid4()), "cardType": "TEST", "cardNo": "0000"})
        await_result("PG 성공과 주문 확정", lambda: snapshot(order_id),
                     lambda value: value["status"] == "CONFIRMED")
        return order_id

    def pause_for_ui_capture(stage, event_ids):
        if not capture_ui:
            return
        if section7_captures_only and stage not in {
            "Outbox commit 후 Relay 실행 전",
            "첫 broker record 후 Outbox PENDING",
            "Relay 재시작 후 같은 eventId record 2건",
        }:
            return
        print(json.dumps({"captureStage": stage, "url": f"http://127.0.0.1:{ui_port}",
                          "topic": topic, "eventIds": event_ids}, ensure_ascii=False), flush=True)
        input(f"Kafka UI에서 {stage} 화면을 저장한 뒤 Enter를 눌러 계속하세요. ")

    try:
        print("격리 MySQL·Kafka·Redis 시작", flush=True)
        if capture_ui:
            run(["docker", "network", "create", network], timeout=30)
        docker_start(mysql, ["-p", f"127.0.0.1:{mysql_port}:3306", "-e", f"MYSQL_ROOT_PASSWORD={password}",
                            "-e", "MYSQL_USER=application", "-e", f"MYSQL_PASSWORD={password}",
                            "-e", "MYSQL_DATABASE=loopers", "mysql:8.0"])
        docker_start(redis, ["-p", f"127.0.0.1:{redis_port}:6379", "redis:7.0"])
        kafka_env = {"KAFKA_NODE_ID": "1", "KAFKA_PROCESS_ROLES": "broker,controller",
                     "KAFKA_LISTENERS": ("INTERNAL://:9092,EXTERNAL://:19092,CONTROLLER://:9093"
                                          if capture_ui else
                                          f"PLAINTEXT://:{kafka_port},CONTROLLER://:9093"),
                     "KAFKA_ADVERTISED_LISTENERS": (
                         f"INTERNAL://{kafka}:9092,EXTERNAL://localhost:{kafka_port}" if capture_ui else
                         f"PLAINTEXT://localhost:{kafka_port}"),
                     "KAFKA_CONTROLLER_LISTENER_NAMES": "CONTROLLER",
                     "KAFKA_LISTENER_SECURITY_PROTOCOL_MAP": (
                         "CONTROLLER:PLAINTEXT,INTERNAL:PLAINTEXT,EXTERNAL:PLAINTEXT" if capture_ui else
                         "CONTROLLER:PLAINTEXT,PLAINTEXT:PLAINTEXT"),
                     "KAFKA_INTER_BROKER_LISTENER_NAME": "INTERNAL" if capture_ui else "PLAINTEXT",
                     "KAFKA_CONTROLLER_QUORUM_VOTERS": "1@localhost:9093",
                     "KAFKA_OFFSETS_TOPIC_REPLICATION_FACTOR": "1", "KAFKA_AUTO_CREATE_TOPICS_ENABLE": "false"}
        kafka_mapping = f"127.0.0.1:{kafka_port}:19092" if capture_ui else f"127.0.0.1:{kafka_port}:{kafka_port}"
        docker_start(kafka, ["-p", kafka_mapping,
                            *[argument for key, value in kafka_env.items() for argument in ("-e", f"{key}={value}")],
                            "apache/kafka:3.8.1"])
        await_result("MySQL 준비", mysql_is_stable, timeout=120)
        sql((ROOT / "docker/mysql/init.sql").read_text())
        # 기존 DB에 적용하는 SQL도 같은 스키마에서 재실행할 수 있어야 한다.
        sql((ROOT / "docker/mysql/purchase-activity-migration.sql").read_text())
        sql("""INSERT INTO loopers.users(id,created_at,updated_at) VALUES(1,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));
            INSERT INTO loopers.points(user_id,balance,created_at,updated_at)
                VALUES(1,100000,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));
            INSERT INTO loopers.brands(id,name,created_at,updated_at) VALUES(1,'flow',UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));
            INSERT INTO loopers.products(id,brand_id,name,price,stock,created_at,updated_at)
            VALUES(7,1,'first',3500,10,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6)),
                  (8,1,'second',4800,10,UTC_TIMESTAMP(6),UTC_TIMESTAMP(6));""")
        await_result("Kafka 준비", lambda: cli("kafka-topics", "--list") or True, timeout=120)
        cli("kafka-topics", "--create", "--topic", topic, "--partitions", "3", "--replication-factor", "1")
        if capture_ui:
            ui = f"loopers-flow-{suffix}-kafka-ui"
            docker_start(ui, ["-p", f"127.0.0.1:{ui_port}:8080",
                              "-e", "KAFKA_CLUSTERS_0_NAME=isolated-order-flow",
                              "-e", f"KAFKA_CLUSTERS_0_BOOTSTRAPSERVERS={kafka}:9092",
                              "provectuslabs/kafka-ui:latest"])
            def ui_ready():
                with urllib.request.urlopen(f"http://127.0.0.1:{ui_port}/actuator/health", timeout=3) as response:
                    return response.status == 200
            await_result("Kafka UI 준비", ui_ready, timeout=120)
            print(f"Kafka UI: http://127.0.0.1:{ui_port} · topic={topic}", flush=True)
        start_app("test-pg", pg_port, ["--test-pg.processing-delay=1s", "--test-pg.control.enabled=true"])
        common = ["--spring.profiles.active=local", f"--spring.kafka.bootstrap-servers=localhost:{kafka_port}",
                  f"--order.confirmed-event.topic={topic}",
                  f"--datasource.mysql-jpa.main.jdbc-url=jdbc:mysql://localhost:{mysql_port}/loopers",
                  "--datasource.mysql-jpa.main.username=application", f"--datasource.mysql-jpa.main.password={password}",
                  "--datasource.mysql-jpa.main.maximum-pool-size=5", "--datasource.mysql-jpa.main.minimum-idle=1",
                  "--datasource.redis.master.host=localhost", f"--datasource.redis.master.port={redis_port}",
                  "--datasource.redis.replicas[0].host=localhost", f"--datasource.redis.replicas[0].port={redis_port}"]
        streamer_args = [*common, f"--order.confirmed-event.group-id={group}"]
        streamer, _ = start_app("commerce-streamer", streamer_port, streamer_args)
        initial_consumer_assignment = await_consumer_assignment()
        pause_marker = output / "before-send.ready"
        pause_release = output / "before-send.release"
        direct_api_args = [*common, "--product.list-cache.warm-up.enabled=false",
            "--payment.periodic-check.enabled=false", f"--payment.pg.base-url=http://127.0.0.1:{pg_port}",
            f"--payment.pg.callback-url=http://127.0.0.1:{api_port}/api/v1/payments/callback"]
        paused_api, api_log = start_app("direct-publication-api", api_port, direct_api_args,
            jvm_arguments=[f"-Dorder-confirmed.test.pause-before-send.marker={pause_marker}",
                           f"-Dorder-confirmed.test.pause-before-send.release={pause_release}"])
        print("주문 A: PG SUCCESS 확정 commit 뒤, Kafka send 직전의 테스트 경계에서 대기", flush=True)
        missing_id = confirm()
        await_result("주문 A의 발행 직전 중단 표식", lambda: pause_marker.exists())
        missing = snapshot(missing_id)
        empty_ends = log_ends()
        assert len(empty_ends) == 3 and all(offset == 0 for offset in empty_ends.values()), empty_ends
        assert missing["status"] == "CONFIRMED" and missing["outbox"] == {}, missing
        assert missing["completionRows"] == 0 and missing["activities"] == [], missing
        paused_api_forced_stop = stop_app(paused_api, timeout=5)
        assert paused_api.poll() is not None, "중단 지점에 남아 있는 기준 API 프로세스가 종료되지 않았습니다."
        _, api_log = start_app("direct-publication-api", api_port, direct_api_args)
        pause_for_ui_capture("before-recovery", [missing["eventId"]])
        print("주문 B: 기존 발행 경로로 실제 broker → Consumer Inbox·구매 활동까지 처리", flush=True)
        normal_id = confirm()
        normal = await_result("정상 구매 활동 저장", lambda: snapshot(normal_id),
                              lambda value: value["completionRows"] == 1 and len(value["activities"]) == 2)
        for activity, item in zip(normal["activities"], normal["items"]):
            assert {key: activity[key] for key in ("productId", "quantity")} == item
            assert activity["confirmedAt"] == normal["confirmedAt"]
        initial_records = read_records(1)
        assert len(initial_records) == 1, initial_records
        initial_payload = initial_records[0]["payload"]
        assert initial_payload["orderId"] == normal_id and initial_payload["eventId"] == normal["eventId"]
        assert initial_payload["items"] == normal["items"]
        assert datetime.fromisoformat(initial_payload["confirmedAt"]) == datetime.fromisoformat(normal["confirmedAt"])
        baseline_ends = log_ends()
        group_caught_up(baseline_ends)
        before_recovery = {"missing": snapshot(missing_id), "normal": snapshot(normal_id)}
        pg_before = {str(order_id): pg_stats(before_recovery[role]["providerOrderId"])
                     for role, order_id in (("missing", missing_id), ("normal", normal_id))}

        print("기준 API를 종료하고 저장된 주문 사실로 Outbox를 보충", flush=True)
        stop_app(processes[-1])
        recovery_api_args = [*common, "--product.list-cache.warm-up.enabled=false",
            "--payment.periodic-check.enabled=false", f"--payment.pg.base-url=http://127.0.0.1:{pg_port}",
            f"--payment.pg.callback-url=http://127.0.0.1:{api_port}/api/v1/payments/callback",
            f"--order.confirmed-event.outbox.backfill.order-ids={missing_id},{normal_id}"]
        recovery_api, api_log = start_app("commerce-api", api_port, recovery_api_args)
        after_recovery = await_result("두 Outbox가 발행되고 Consumer가 결과를 저장",
            lambda: {"missing": snapshot(missing_id), "normal": snapshot(normal_id)},
            lambda pair: all(pair[role]["outbox"].get("status") == "PUBLISHED"
                             for role in ("missing", "normal"))
            and pair["missing"]["completionRows"] == 1 and len(pair["missing"]["activities"]) == 2
            and pair["normal"]["completionRows"] == 1 and len(pair["normal"]["activities"]) == 2)
        recovered_ends = log_ends()
        assert sum(recovered_ends.values()) - sum(baseline_ends.values()) == 2, recovered_ends
        committed = group_caught_up(recovered_ends)
        assert after_recovery["normal"]["activities"] == before_recovery["normal"]["activities"]
        assert after_recovery["normal"]["inboxEventIds"] == before_recovery["normal"]["inboxEventIds"]
        assert after_recovery["missing"]["inboxEventIds"] == [f"order-confirmed:v1:{missing_id}"]
        assert after_recovery["normal"]["inboxEventIds"] == [f"order-confirmed:v1:{normal_id}"]
        for role in ("missing", "normal"):
            for field in ("orderId", "status", "confirmedAt", "providerOrderId", "transactionKey", "items", "stocks"):
                assert after_recovery[role][field] == before_recovery[role][field], {
                    "role": role, "field": field, "before": before_recovery[role][field],
                    "after": after_recovery[role][field]}
        assert [{key: activity[key] for key in ("productId", "quantity")}
                for activity in after_recovery["missing"]["activities"]] == after_recovery["missing"]["items"]
        assert all(activity["confirmedAt"] == after_recovery["missing"]["confirmedAt"]
                   for activity in after_recovery["missing"]["activities"])
        pause_for_ui_capture("after-recovery", [missing["eventId"], normal["eventId"]])
        pg_after = {str(order_id): pg_stats(before_recovery[role]["providerOrderId"])
                    for role, order_id in (("missing", missing_id), ("normal", normal_id))}
        assert pg_after == pg_before, {"before": pg_before, "after": pg_after}
        records = read_records(3)
        by_event_id = Counter(record["payload"]["eventId"] for record in records)
        assert by_event_id == Counter({f"order-confirmed:v1:{missing_id}": 1,
                                       f"order-confirmed:v1:{normal_id}": 2}), by_event_id
        assert all(record["payload"]["orderId"] == int(record["key"]) for record in records)
        for record in records:
            role = "missing" if record["payload"]["orderId"] == missing_id else "normal"
            saved = after_recovery[role]
            assert record["payload"]["eventId"] == saved["eventId"]
            assert record["payload"]["items"] == saved["items"]
            assert datetime.fromisoformat(record["payload"]["confirmedAt"]) == datetime.fromisoformat(
                saved["confirmedAt"])

        print("같은 대상의 Outbox 복구를 다시 실행해 기존 메시지와 비교", flush=True)
        stop_app(recovery_api)
        recovery_api, api_log = start_app("commerce-api", api_port, recovery_api_args)
        await_result("두 대상의 기존 Outbox 비교 완료", lambda: api_log.read_text(),
                     lambda text: text.count("주문 확정 Outbox 복구를 확인했습니다") >= 2)
        after_idempotent_backfill = {"missing": snapshot(missing_id), "normal": snapshot(normal_id)}
        assert after_idempotent_backfill == after_recovery
        assert all(after_idempotent_backfill[role]["outboxRows"] == 1 for role in ("missing", "normal"))
        assert log_ends() == recovered_ends
        pg_after_idempotent_backfill = {str(order_id): pg_stats(after_idempotent_backfill[role]["providerOrderId"])
                                        for role, order_id in (("missing", missing_id), ("normal", normal_id))}
        assert pg_after_idempotent_backfill == pg_after
        pg_totals = {
            phase: {field: sum(stats[field] for stats in values.values())
                    for field in ("createRequests", "lookupRequests", "callbackAttempts", "transactions")}
            for phase, values in (("beforeRecovery", pg_before), ("afterRecovery", pg_after),
                                  ("afterIdempotentBackfill", pg_after_idempotent_backfill))
        }
        assert pg_totals["beforeRecovery"] == {
            "createRequests": 2, "lookupRequests": 2, "callbackAttempts": 2, "transactions": 2}
        assert pg_totals["afterRecovery"] == pg_totals["beforeRecovery"]
        assert pg_totals["afterIdempotentBackfill"] == pg_totals["beforeRecovery"]

        print("streamer 재시작: 보충 이후 Inbox·구매 활동·offset 보존 확인", flush=True)
        stop_app(streamer)
        streamer, _ = start_app("commerce-streamer", streamer_port, streamer_args)
        restarted_consumer_assignment = await_consumer_assignment()
        await_result("재시작 group offset", offsets, lambda value: value == committed)
        assert {"missing": snapshot(missing_id), "normal": snapshot(normal_id)} == after_recovery

        print("새 주문으로 실제 API → Outbox → Relay → Consumer 중단·재시작 E2E", flush=True)
        stop_app(recovery_api)
        relay_api_base = [*common, "--product.list-cache.warm-up.enabled=false",
                          "--payment.periodic-check.enabled=false",
                          f"--payment.pg.base-url=http://127.0.0.1:{pg_port}",
                          f"--payment.pg.callback-url=http://127.0.0.1:{api_port}/api/v1/payments/callback",
                          "--order.confirmed-event.relay.interval=5s",
                          "--order.confirmed-event.relay.future-timeout=10s"]
        idle_api, _ = start_app("commerce-api", api_port,
                                [*relay_api_base, "--order.confirmed-event.relay.enabled=false"])
        section7_baseline_ends = log_ends()
        section7_baseline_record_count = sum(section7_baseline_ends.values())
        group_caught_up(section7_baseline_ends)
        section7_order_id = confirm()
        committed_pending = snapshot(section7_order_id)
        assert committed_pending["status"] == "CONFIRMED", committed_pending
        assert committed_pending["pointBalance"] == 100000, committed_pending
        assert committed_pending["outboxRows"] == 1, committed_pending
        assert committed_pending["outbox"]["status"] == "PENDING", committed_pending
        assert committed_pending["outbox"]["attemptCount"] == 0, committed_pending
        assert committed_pending["outbox"]["cycleAttemptCount"] == 0, committed_pending
        assert committed_pending["outbox"]["publishedAt"] is None, committed_pending
        assert committed_pending["completionRows"] == 0, committed_pending
        assert committed_pending["inboxEventIds"] == [], committed_pending
        assert committed_pending["activities"] == [], committed_pending
        assert log_ends() == section7_baseline_ends
        section7_pg_before = pg_stats(committed_pending["providerOrderId"])
        assert section7_pg_before["createRequests"] == 1, section7_pg_before
        assert section7_pg_before["transactions"] == 1, section7_pg_before
        section7_event_id = committed_pending["eventId"]
        section7_saved_payload = json.loads(committed_pending["outbox"]["payload"])
        section7_key = committed_pending["outbox"]["messageKey"]
        assert section7_saved_payload["eventId"] == section7_event_id
        assert section7_saved_payload["orderId"] == section7_order_id
        assert section7_key == str(section7_order_id)
        if capture_ui:
            pause_for_ui_capture("Outbox commit 후 Relay 실행 전", [section7_event_id])

        sql(f"""
            DROP TRIGGER IF EXISTS loopers.fail_first_order_confirmed_publish;
            DELIMITER $$
            CREATE TRIGGER loopers.fail_first_order_confirmed_publish
            BEFORE UPDATE ON loopers.order_confirmed_outbox FOR EACH ROW
            BEGIN
                IF NEW.order_id = {section7_order_id}
                   AND NEW.status = 'PUBLISHED'
                   AND NEW.attempt_count = 1 THEN
                    SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT = 'section7_test_published_write_failure';
                END IF;
            END$$
            DELIMITER ;
            """)
        stop_app(idle_api)

        relay_enabled_args = [*relay_api_base, "--order.confirmed-event.relay.enabled=true"]
        first_relay_api, first_relay_log = start_app("commerce-api", api_port, relay_enabled_args)
        first_ack_observation = await_observed(
            "첫 broker 확인 뒤 Outbox PENDING",
            lambda: {"order": snapshot(section7_order_id), "ends": log_ends()},
            lambda value: value["order"]["outbox"].get("status") == "PENDING"
            and value["order"]["outbox"].get("attemptCount") == 1
            and sum(value["ends"].values()) - section7_baseline_record_count == 1,
            timeout=90)
        first_record_candidates = [record for record in read_records(section7_baseline_record_count + 1)
                                   if record["payload"].get("eventId") == section7_event_id]
        assert len(first_record_candidates) == 1, first_record_candidates
        first_broker_record = first_record_candidates[0]
        assert first_broker_record["key"] == section7_key
        assert first_broker_record["payload"] == section7_saved_payload
        first_ends = first_ack_observation["ends"]
        assert sum(first_ends.values()) - section7_baseline_record_count == 1, first_ends
        assert first_broker_record["offset"] == section7_baseline_ends[first_broker_record["partition"]]
        first_relay_api_forced_stop = stop_app(first_relay_api)
        assert not first_relay_api_forced_stop, "첫 Relay 프로세스는 강제 종료 없이 정상 종료해야 합니다."
        section7_after_first_consume = await_observed(
            "첫 record 구매 활동 반영",
            lambda: {"order": snapshot(section7_order_id), "offsets": offsets()},
            lambda value: value["order"]["completionRows"] == 1
            and len(value["order"]["activities"]) == 2
            and value["offsets"].get(first_broker_record["partition"], -1) > first_broker_record["offset"],
            timeout=60)
        assert section7_after_first_consume["order"]["inboxEventIds"] == [section7_event_id]
        assert section7_after_first_consume["order"]["outbox"]["status"] == "PENDING"
        assert section7_after_first_consume["order"]["outbox"]["publishedAt"] is None
        assert first_relay_log.read_text().count("section7_test_published_write_failure") >= 1, \
            first_relay_log.read_text()[-4000:]
        if capture_ui:
            pause_for_ui_capture("첫 broker record 후 Outbox PENDING", [section7_event_id])

        restarted_relay_api, restarted_relay_log = start_app("commerce-api", api_port, relay_enabled_args)
        final_broker_observation = await_observed(
            "새 프로세스의 재발행과 PUBLISHED 저장",
            lambda: {"order": snapshot(section7_order_id), "ends": log_ends()},
            lambda value: value["order"]["outbox"].get("status") == "PUBLISHED"
            and value["order"]["outbox"].get("attemptCount") == 2
            and value["order"]["outbox"].get("publishedAt") is not None
            and sum(value["ends"].values()) - section7_baseline_record_count == 2,
            timeout=120)
        section7_records = [record for record in read_records(section7_baseline_record_count + 2)
                            if record["payload"].get("eventId") == section7_event_id]
        assert len(section7_records) == 2, section7_records
        section7_records.sort(key=lambda record: record["offset"])
        first_broker_record, second_broker_record = section7_records
        assert first_broker_record["key"] == second_broker_record["key"] == section7_key
        assert first_broker_record["payload"] == second_broker_record["payload"] == section7_saved_payload
        assert first_broker_record["partition"] == second_broker_record["partition"]
        assert first_broker_record["offset"] == section7_baseline_ends[first_broker_record["partition"]]
        assert second_broker_record["offset"] == first_broker_record["offset"] + 1
        assert second_broker_record["offset"] > first_broker_record["offset"]
        assert second_broker_record["offset"] + 1 == final_broker_observation["ends"][second_broker_record["partition"]]
        group_caught_up(final_broker_observation["ends"])
        section7_final = snapshot(section7_order_id)
        assert section7_final["outboxRows"] == 1, section7_final
        assert section7_final["outbox"]["eventId"] == section7_event_id
        assert section7_final["outbox"]["messageKey"] == section7_key
        assert json.loads(section7_final["outbox"]["payload"]) == section7_saved_payload
        assert section7_final["outbox"]["status"] == "PUBLISHED"
        assert section7_final["outbox"]["attemptCount"] == 2
        assert section7_final["outbox"]["publishedAt"] is not None
        assert section7_final["completionRows"] == 1
        assert section7_final["inboxEventIds"] == [section7_event_id]
        assert len(section7_final["activities"]) == 2
        assert [(item["productId"], item["quantity"]) for item in section7_final["activities"]] == [
            (7, 2), (8, 1)]
        assert all(item["confirmedAt"] == section7_final["confirmedAt"]
                   for item in section7_final["activities"])
        assert section7_final["activities"] == section7_after_first_consume["order"]["activities"]
        for field in ("orderId", "eventId", "status", "confirmedAt", "providerOrderId", "transactionKey",
                      "pointBalance", "items", "stocks"):
            assert section7_final[field] == committed_pending[field], {
                "field": field, "before": committed_pending[field], "after": section7_final[field]}
        section7_pg_after = pg_stats(section7_final["providerOrderId"])
        assert section7_pg_after == section7_pg_before, {"before": section7_pg_before, "after": section7_pg_after}
        if capture_ui:
            pause_for_ui_capture("Relay 재시작 후 같은 eventId record 2건", [section7_event_id])
        sql("DROP TRIGGER IF EXISTS loopers.fail_first_order_confirmed_publish;")
        stop_app(restarted_relay_api)
        relay_restart_e2e = {
            "orderBeforeRelay": committed_pending,
            "afterFirstBrokerAckAndConsumer": section7_after_first_consume,
            "afterRelayProcessRestart": section7_final,
            "pgStatsBefore": section7_pg_before,
            "pgStatsAfter": section7_pg_after,
            "brokerRecords": section7_records,
            "firstRelayApiForcedStop": first_relay_api_forced_stop,
            "firstApiProcessSawSaveFailure": True,
            "secondApiProcess": restarted_relay_api.pid,
            "relayRestartLog": restarted_relay_log.name,
            "firstRelayLog": first_relay_log.name,
            "groupOffsetsAfterSecondRecord": offsets(),
        }
        evidence = {"observedAt": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime()), "topic": topic,
                    "group": group, "bootstrapServers": f"localhost:{kafka_port}",
                    "baselineRevision": DIRECT_PUBLICATION_REVISION,
                    "failureInjection": "test-only latch paused the historical AFTER_COMMIT listener after event creation and before KafkaTemplate.send; that isolated baseline process was then stopped",
                    "baselineApiForcedStopAfterPause": paused_api_forced_stop,
                    "missingBeforeRecovery": missing, "beforeRecovery": before_recovery,
                    "afterRecovery": after_recovery, "afterIdempotentBackfill": after_idempotent_backfill,
                    "paymentGatewayStatsBefore": pg_before, "paymentGatewayStatsAfter": pg_after,
                    "paymentGatewayStatsAfterIdempotentBackfill": pg_after_idempotent_backfill,
                    "paymentGatewayTotals": pg_totals,
                    "brokerRecords": records,
                    "brokerRecordsByEventId": dict(by_event_id),
                    "logEndsBeforeRecovery": baseline_ends, "logEndsAfterRecovery": recovered_ends,
                    "committedOffsets": committed, "consumerRestartPreserved": True,
                    "consumerAssignments": {"initial": initial_consumer_assignment,
                                            "afterRestart": restarted_consumer_assignment},
                    "relayRestartE2E": relay_restart_e2e}
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
        if capture_ui:
            run(["docker", "network", "rm", network], timeout=30, check=False)


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("--capture-ui", action="store_true",
                        help="격리 Kafka UI를 실행하고 두 복구 흐름의 캡처 시점에서 대기한다")
    parser.add_argument("--section7-captures-only", action="store_true",
                        help="최종 Relay 재시작 E2E의 Kafka UI 화면만 캡처한다")
    arguments = parser.parse_args()
    main(capture_ui=arguments.capture_ui or arguments.section7_captures_only,
         section7_captures_only=arguments.section7_captures_only)
