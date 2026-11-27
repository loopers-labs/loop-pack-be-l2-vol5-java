"""Run and record the local, fault-free week 10 JMeter control."""
import csv
import json
import math
import subprocess
import sys
import time
from collections import Counter, deque
from datetime import datetime, timezone
from pathlib import Path
from urllib.parse import urlencode
from urllib.request import urlopen


HERE = Path(__file__).resolve().parent
REPO = HERE.parents[2]
PROM = "http://127.0.0.1:39091/api/v1/"
GRAFANA = "http://127.0.0.1:33311"
BOARD_UID = "w10-catalog-control"
PROJECT = "week10-catalog-stack"
JOB = '{job="commerce-control"}'
PHASE_SECONDS = 60
EXTRA_CONTROL_SECONDS = 120


def json_file(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2, allow_nan=False), encoding="utf-8")


def get_json(url):
    with urlopen(url, timeout=6) as response:
        return json.load(response)


def utc(epoch):
    return datetime.fromtimestamp(epoch, timezone.utc).isoformat(timespec="milliseconds")


def docker(args, stdin=None):
    result = subprocess.run(["docker", *args], input=stdin, text=True, encoding="utf-8",
                            capture_output=True, timeout=20, check=False)
    if result.returncode:
        raise RuntimeError(result.stderr.strip() or "Docker command failed")
    return result.stdout


class Database:
    def __init__(self):
        ids = docker(["ps", "-q", "--filter", f"label=com.docker.compose.project={PROJECT}",
                      "--filter", "label=com.docker.compose.service=mysql"]).split()
        if len(ids) != 1:
            raise RuntimeError("Expected one MySQL instance in the week 10 control project")
        self.container = ids[0]

    def query(self, sql):
        output = docker(["exec", "-i", "-e", "MYSQL_PWD=application", self.container, "mysql",
                         "--default-character-set=utf8mb4", "--batch", "--raw", "-uapplication", "loopers"],
                        "SET time_zone='+00:00';\n" + sql + ";\n")
        return list(csv.DictReader(output.splitlines(), delimiter="\t", quoting=csv.QUOTE_NONE))


def prom(expr, at=None):
    args = {"query": expr}
    if at is not None:
        args["time"] = at
    result = get_json(PROM + "query?" + urlencode(args))
    if result["status"] != "success":
        raise RuntimeError(result)
    points = result["data"]["result"]
    if len(points) != 1:
        return None
    value = float(points[0]["value"][1])
    return value if math.isfinite(value) else None


def gauge():
    return {
        "up": prom("up" + JOB),
        "unknown": prom("commerce_payment_unknown" + JOB),
        "aggregated": prom("commerce_payment_unknown_last_success_timestamp_seconds" + JOB),
        "scraped": prom("timestamp(commerce_payment_unknown" + JOB + ")"),
    }


class Telemetry:
    def __init__(self, db, directory):
        self.db = db
        self.path = directory / "telemetry.csv"
        self.last_ms = 0
        self.history = deque(maxlen=18)
        self.details = CatalogReads(directory / "samples.jtl")

    def sync(self):
        if not self.path.exists():
            return
        # At one sample/second the whole telemetry file remains small. Ignore a partially written last line.
        contents = self.path.read_bytes()
        lines = contents[:contents.rfind(b"\n") + 1].decode("utf-8-sig").splitlines()
        values = []
        fresh = []
        for row in csv.DictReader(lines):
            stamp = int(row["timestampMs"])
            if stamp <= self.last_ms:
                continue
            readers, requests, errors, water = (int(row[key]) for key in
                                                ("activeReaders", "productRequests", "productErrors", "waterSent"))
            cpu = float(row["processCpuPct"]) if row["processCpuPct"] else math.nan
            values.append(f"(FROM_UNIXTIME({stamp}/1000.0),{readers},{requests},{errors},{water},"
                          + (str(cpu) if math.isfinite(cpu) and cpu >= 0 else "NULL") + ")")
            fresh.append((stamp / 1000, readers, requests))
        if values:
            self.db.query("INSERT INTO w10_control_samples "
                          "(sampled_at,active_readers,product_requests,product_errors,water_sent,process_cpu_pct) VALUES "
                          + ",".join(values) + " ON DUPLICATE KEY UPDATE sampled_at=VALUES(sampled_at)")
            self.history.extend(fresh)
            self.last_ms = int(fresh[-1][0] * 1000 + 0.5)
        self.details.sync(self.db)

    def stable(self):
        # Three consecutive five-second intervals with 1,000 readers and comparable observed throughput.
        points = list(self.history)[-16:]
        if len(points) != 16 or any(p[1] != 1000 for p in points):
            return False
        rates = [(points[i + 5][2] - points[i][2]) / (points[i + 5][0] - points[i][0]) for i in (0, 5, 10)]
        return min(rates) > 0 and min(rates) >= max(rates) * 0.70


class CatalogReads:
    """Tail completed JMeter detail GETs and publish ten-second per-product counts."""
    def __init__(self, path):
        self.path = path
        self.offset = 0
        self.header = None
        self.hits = Counter()
        self.errors = Counter()

    def sync(self, db):
        if not self.path.exists():
            return
        with self.path.open("rb") as stream:
            stream.seek(self.offset)
            chunk = stream.read()
        length = chunk.rfind(b"\n") + 1
        if length == 0:
            return
        lines = chunk[:length].decode("utf-8-sig").splitlines()
        if self.header is None:
            self.header = next(csv.reader([lines.pop(0)]))
            if not {"productId", "label", "elapsed", "success", "timeStamp"} <= set(self.header):
                raise ValueError("JMeter result header does not include catalog fields")
        changed = set()
        for row in csv.DictReader(lines, fieldnames=self.header):
            if row["label"] not in ("browse-detail", "browse-revisit"):
                continue
            completed_ms = int(row["timeStamp"]) + int(row["elapsed"])
            key = (completed_ms // 10_000 * 10, int(row["productId"]), row["label"].removeprefix("browse-"))
            changed.add(key)
            self.hits[key] += 1
            self.errors[key] += row["success"] != "true"
        if changed:
            values = [f"(FROM_UNIXTIME({b}),{p},'{k}',{self.hits[b,p,k]},{self.errors[b,p,k]})"
                      for b, p, k in sorted(changed)]
            db.query("INSERT INTO w10_catalog_reads(bucket_at,product_id,kind,hits,errors) VALUES "
                     + ",".join(values) + " ON DUPLICATE KEY UPDATE hits=VALUES(hits), errors=VALUES(errors)")
        self.offset += length


def fixture_counts(db):
    rows = db.query("""SELECT i.product_id, COUNT(*) AS n, COUNT(r.id) AS paid
        FROM orders o JOIN order_items i ON i.order_id=o.id
        LEFT JOIN order_records r ON r.order_id=o.id AND r.status='PAID'
        WHERE o.user_id=1002 GROUP BY i.product_id""")
    return {int(r["product_id"]): (int(r["n"]), int(r["paid"])) for r in rows}


def add_phase(db, name, start):
    db.query(f"INSERT INTO w10_control_phases (phase,starts_at,ends_at) "
             f"VALUES ('{name}',FROM_UNIXTIME({start:.6f}),FROM_UNIXTIME({start + PHASE_SECONDS:.6f}))")


def export_tsv(directory, name, rows, columns):
    with (directory / name).open("w", encoding="utf-8", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=columns, delimiter="\t")
        writer.writeheader()
        writer.writerows(rows)


def jmeter_results(directory):
    labels = {}
    with (directory / "samples.jtl").open(encoding="utf-8-sig", newline="") as stream:
        for sample in csv.DictReader(stream):
            label = sample["label"]
            if label not in labels:
                labels[label] = {"requests": 0, "errors": 0, "elapsed": Counter()}
            stats = labels[label]
            stats["requests"] += 1
            stats["errors"] += sample["success"] != "true"
            stats["elapsed"][int(sample["elapsed"])] += 1
    for stats in labels.values():
        seen = 0
        for milliseconds, count in sorted(stats.pop("elapsed").items()):
            seen += count
            if seen >= math.ceil(stats["requests"] * 0.95):
                stats["p95_ms"] = milliseconds
                break
    return labels


def report(db, directory, phases, launch):
    water = db.query("SELECT order_id,attempt_key,accepted_at,confirmed_at,within_30s,"
                     "UNIX_TIMESTAMP(accepted_at) AS accepted_epoch FROM w10_control_water_orders ORDER BY accepted_at")
    export_tsv(directory, "water-orders.tsv", water, ["order_id", "attempt_key", "accepted_at", "confirmed_at",
                                                      "within_30s", "accepted_epoch"])
    with (directory / "order-requests.csv").open(encoding="utf-8-sig", newline="") as f:
        requests = list(csv.DictReader(f))
    confirms = {r["attemptKey"]: r for r in requests if r["kind"] == "water" and r["stage"] == "confirm"}
    if any(r["attempt_key"] not in confirms or confirms[r["attempt_key"]]["orderId"] != r["order_id"] for r in water):
        raise RuntimeError("Server acceptance ledger does not match the generator's order IDs and payment attempt keys")
    summaries = db.query("SELECT phase,starts_at,ends_at,accepted,matured,within_30s,final_pct "
                         "FROM w10_control_phase_sli ORDER BY starts_at")
    for row in summaries:
        start = phases[row["phase"]]
        end = start + PHASE_SECONDS
        cohort = [r for r in water if start <= float(r["accepted_epoch"]) < end]
        if (len(cohort) != int(row["accepted"]) or int(row["matured"]) != len(cohort) or
                sum(int(r["within_30s"]) for r in cohort) != int(row["within_30s"])):
            raise RuntimeError("Dashboard totals and order-level ledger disagree")
        row["sent_confirm"] = sum(start <= int(r["timestampMs"]) / 1000 < end for r in confirms.values())
        row["sent_create"] = sum(r["kind"] == "water" and r["stage"] == "create" and
                                 start <= int(r["timestampMs"]) / 1000 < end for r in requests)
        row["generator"] = db.query("WITH trace AS (SELECT active_readers,process_cpu_pct,sampled_at,"
            "LAG(sampled_at) OVER(ORDER BY sampled_at) AS previous FROM w10_control_samples "
            f"WHERE sampled_at>=FROM_UNIXTIME({start:.6f}) AND sampled_at<FROM_UNIXTIME({end:.6f})) "
            "SELECT MIN(active_readers) AS min_readers,MAX(active_readers) AS max_readers,COUNT(*) AS samples,"
            "ROUND(AVG(process_cpu_pct),2) AS avg_cpu_pct, GREATEST("
            f"{end:.6f}-UNIX_TIMESTAMP(MAX(sampled_at)), UNIX_TIMESTAMP(MIN(sampled_at))-{start:.6f}, "
            "MAX(TIMESTAMPDIFF(MICROSECOND,previous,sampled_at))/1000000) AS longest_gap_sec FROM trace")[0]
        gen = row["generator"]
        row["verified_1000_readers"] = (gen["min_readers"] == "1000" and gen["max_readers"] == "1000"
                                        and gen["longest_gap_sec"] != "NULL" and float(gen["longest_gap_sec"]) <= 8)
        sel = '{job="commerce-control",method="GET",uri="/api/v1/products"}'
        count = "sum(rate(http_server_requests_seconds_count" + sel + "[1m]))"
        failures = 'sum(rate(http_server_requests_seconds_count{job="commerce-control",method="GET",uri="/api/v1/products",status=~"5.."}[1m]))'
        row["product_rps"] = prom(count, end)
        row["product_p95_seconds"] = prom("histogram_quantile(0.95,sum by(le)(rate(http_server_requests_seconds_bucket"+sel+"[1m])))", end)
        row["product_5xx_fraction"] = prom("("+failures+" or vector(0)) / ("+count+")", end)
    finish = time.time()
    for expr, name in [("up" + JOB, "scrape-up"), ("commerce_payment_unknown" + JOB, "unknown"),
                       ("commerce_payment_unknown_last_success_timestamp_seconds" + JOB, "last-success")]:
        args = {"query": expr, "start": launch, "end": finish, "step": 5}
        json_file(directory / (name + ".json"), get_json(PROM + "query_range?" + urlencode(args)))
    board_path = get_json(GRAFANA+"/api/dashboards/uid/"+BOARD_UID)["meta"]["url"]
    dashboard = GRAFANA + board_path + "?" + urlencode({"from": int((launch - 10)*1000), "to": int(finish*1000), "orgId": 1})
    mix = db.query("""SELECT COUNT(DISTINCT o.id) AS confirmed_orders,COUNT(DISTINCT i.product_id) AS distinct_products,
               COUNT(DISTINCT o.user_id) AS purchasing_accounts,COUNT(DISTINCT c.category) AS categories,
               SUM(i.quantity) AS units
        FROM orders o JOIN order_records r ON r.order_id=o.id AND r.status='PAID'
        JOIN order_items i ON i.order_id=o.id JOIN w10_catalog c ON c.product_id=i.product_id
        WHERE o.user_id BETWEEN 2001 AND 2400""")[0]
    views = db.query("""SELECT COUNT(DISTINCT d.product_id) AS products, COUNT(DISTINCT c.category) AS categories,
            SUM(d.hits - d.errors) AS successful_views, SUM(d.errors) AS errors
            FROM w10_catalog_reads d JOIN w10_catalog c ON c.product_id=d.product_id""")[0]
    top = db.query("""SELECT p.name, c.category, SUM(d.hits - d.errors) AS views FROM w10_catalog_reads d
             JOIN products p ON p.id=d.product_id JOIN w10_catalog c ON c.product_id=d.product_id
             GROUP BY p.id,p.name,c.category ORDER BY views DESC, p.id LIMIT 10""")
    categories = db.query("""WITH browsed AS (
               SELECT c.category, SUM(d.hits-d.errors) AS views, COUNT(DISTINCT d.product_id) AS viewed_products
               FROM w10_catalog_reads d JOIN w10_catalog c ON c.product_id=d.product_id GROUP BY c.category
            ), purchased AS (
               SELECT c.category, COUNT(DISTINCT i.order_id) AS paid_orders, SUM(i.quantity) AS units
               FROM order_items i JOIN orders o ON o.id=i.order_id JOIN order_records r ON r.order_id=o.id AND r.status='PAID'
               JOIN w10_catalog c ON c.product_id=i.product_id WHERE o.user_id BETWEEN 2001 AND 2400 GROUP BY c.category)
            SELECT b.*, COALESCE(p.paid_orders,0) AS paid_orders, COALESCE(p.units,0) AS units
            FROM browsed b LEFT JOIN purchased p ON p.category=b.category ORDER BY b.views DESC""")
    export_tsv(directory, "categories.tsv", categories, ["category", "views", "viewed_products", "paid_orders", "units"])
    labels = jmeter_results(directory)
    detail_hits = sum(v["requests"] - v["errors"] for k, v in labels.items() if k in ("browse-detail", "browse-revisit"))
    if int(views["successful_views"] or 0) != detail_hits:
        raise RuntimeError("Grafana per-product view totals differ from the JMeter log")
    summary = {"started_at": utc(launch), "finished_at": utc(finish), "dashboard": dashboard,
               "water_accepted_total": len(water), "water_confirm_sent_total": len(confirms),
               "fixture_counts": fixture_counts(db), "phases": summaries, "mixed_purchases": mix,
               "catalog_views": views, "top_viewed": top, "jmeter": labels}
    json_file(directory / "result.json", summary)
    return summary


def main(incident_run=False):
    global PROM, GRAFANA, PROJECT, BOARD_UID
    if incident_run:
        import incident
        PROM = "http://127.0.0.1:39092/api/v1/"
        GRAFANA = "http://127.0.0.1:33312"
        PROJECT = "week10-catalog-incident"
        BOARD_UID = "w10-catalog-incident"
    db = Database()
    preexisting = db.query("""SELECT (SELECT COUNT(*) FROM w10_control_phases) AS phases,
        (SELECT COUNT(*) FROM orders WHERE user_id IN (1001,1002)) AS orders,
        (SELECT COUNT(*) FROM w10_control_samples) AS samples""")[0]
    if any(int(n) for n in preexisting.values()):
        raise RuntimeError("The control database already contains an experiment. Preserve it and use a fresh project volume.")
    if gauge()["up"] != 1 or get_json(GRAFANA + "/api/health").get("database") != "ok":
        raise RuntimeError("Grafana and the Commerce scrape target must be ready before loading")
    launch = time.time()
    prefix = "catalog-incident-" if incident_run else "catalog-control-"
    directory = REPO / "build" / "week10" / (prefix + datetime.now(timezone.utc).strftime("%Y%m%dT%H%M%SZ"))
    directory.mkdir(parents=True)
    telemetry = Telemetry(db, directory)
    phases = {}
    events = []

    def event(label, **facts):
        events.append(dict(time=utc(time.time()), event=label, **facts))
        json_file(directory / "timeline.json", events)
        print(json.dumps(events[-1], ensure_ascii=False), flush=True)

    def wait_for_gate(label, expected, limit):
        deadline = time.monotonic() + limit
        ready_at = None
        while time.monotonic() < deadline:
            if (directory / "stop-load").exists():
                raise InterruptedError("Operator stopped the load before the next phase")
            telemetry.sync()
            stable = telemetry.stable()
            if time.time() - telemetry.last_ms / 1000 < 8 and stable and fixture_counts(db) == expected:
                if ready_at is None:
                    ready_at = time.time()
                sample = gauge()
                if (sample["up"] == 1 and sample["unknown"] is not None and sample["aggregated"]
                        and sample["aggregated"] >= ready_at and sample["scraped"] >= sample["aggregated"]
                        and time.time() - sample["scraped"] < 12):
                    if incident_run and label == "comparison" and sample["unknown"] < 3:
                        time.sleep(2)
                        continue
                    if incident_run and label in ("comparison", "recovery"):
                        incident.save_evidence(db, directory, PROJECT, label)
                    start = round(time.time(), 6)
                    add_phase(db, label, start)
                    phases[label] = start
                    event(label, starts=utc(start), ends=utc(start + PHASE_SECONDS), gauge=sample)
                    return
            else:
                ready_at = None
            time.sleep(3)
        raise RuntimeError(f"{label} readiness deadline expired; see telemetry and fixture records")

    def hold(until):
        while time.time() < until:
            if (directory / "stop-load").exists():
                raise InterruptedError("Operator stopped the load during the observation window")
            telemetry.sync()
            if time.time() - telemetry.last_ms / 1000 > 20:
                raise RuntimeError("Live generator telemetry stopped during a measurement window")
            time.sleep(min(3, max(0, until - time.time())))

    print(directory, flush=True)
    try:
        ps = ["powershell.exe", "-NoProfile", "-ExecutionPolicy", "Bypass", "-File",
              str(HERE / "jmeter" / "Start-ControlJMeter.ps1"), "-RunDir", str(directory),
              "-ApiPort", "18380" if incident_run else "18280", "-Readers", "1000", "-RampSeconds", "120",
              "-DurationSeconds", "840"]
        if incident_run:
            ps.append("-Incident")
        subprocess.run(ps, check=True, timeout=60, stdout=subprocess.DEVNULL)
        event("load-started", readers=1000, ramp_seconds=120, water_per_second=1)
        wait_for_gate("normal", {1: (6, 6), 2: (4, 4)}, 245)
        hold(phases["normal"] + PHASE_SECONDS)
        (directory / "start-second-fixture").touch()
        event("second-ten-with-three-lost-responses" if incident_run else "second-ten-normal-payments")
        wait_for_gate("comparison", {1: (9, 9), 2: (11, 8 if incident_run else 11)}, 110)
        hold(phases["comparison"] + PHASE_SECONDS)
        if incident_run:
            incident.recover_saved(db, directory)
            event("reconciled-existing-payments")
        wait_for_gate("recovery", {1: (9, 9), 2: (11, 11)}, 70)
        hold(phases["recovery"] + PHASE_SECONDS + (35 if incident_run else EXTRA_CONTROL_SECONDS))
    finally:
        (directory / "stop-load").touch()
        event("stop-requested")
    # In-flight requests at shutdown may still commit. Preserve a full follow-up window.
    end = time.time() + 33
    while time.time() < end:
        telemetry.sync()
        time.sleep(2)
    summary = report(db, directory, phases, launch)
    print(json.dumps(summary, ensure_ascii=False), flush=True)


if __name__ == "__main__":
    if sys.argv[1:] not in ([], ["--incident"]):
        raise SystemExit("Usage: run_control.py [--incident]")
    main(incident_run=sys.argv[1:] == ["--incident"])
