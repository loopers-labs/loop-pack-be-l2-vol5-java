"""Capture and reconcile the three selected tea orders in the local incident experiment."""
import csv
import json
import subprocess
from datetime import datetime, timezone
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode
from urllib.request import Request, urlopen

PROJECT = "week10-catalog-incident"
API = "http://127.0.0.1:18380"
OWNER = "1002"


def require(condition, message):
    if not condition:
        raise RuntimeError(message)


def stamp():
    return datetime.now(timezone.utc).isoformat(timespec="milliseconds")


def persist(path, document):
    tmp = path.with_suffix(".tmp")
    tmp.write_text(json.dumps(document, ensure_ascii=False, indent=2), encoding="utf-8")
    tmp.replace(path)


def container_output(*args):
    result = subprocess.run(["docker", *args], capture_output=True, text=True, encoding="utf-8",
                            errors="replace", timeout=12)
    require(result.returncode == 0, f"Docker {args[0]} failed: {result.stderr.strip()[:500]}")
    return result.stdout


def selected_orders(directory):
    with (directory / "order-requests.csv").open(encoding="utf-8-sig", newline="") as source:
        fixture = [r for r in csv.DictReader(source) if r.get("kind") == "fixture"]
    created = [r for r in fixture if r["stage"] == "create"]
    confirmed = [r for r in fixture if r["stage"] == "confirm"]
    require(len(created) == len(confirmed) == 20, "The 20 fixture payment requests have not all been sent")
    require([r["attemptKey"] for r in created] == [r["attemptKey"] for r in confirmed],
            "Fixture create and payment keys disagree")
    selected = confirmed[17:]
    require(len({r["attemptKey"] for r in confirmed}) == len({r["orderId"] for r in confirmed}) == 20,
            "Duplicate fixture order or payment attempt")
    require(all(r["userId"] == OWNER and r["productId"] == "2" and r["cartItems"] == "2:1" for r in selected),
            "The last three fixtures must be the owner's single-unit tea orders")
    return {int(r["orderId"]): r["attemptKey"] for r in selected}


def commerce_orders(db, ids):
    numbers = ",".join(str(int(i)) for i in ids)
    return db.query(f"""SELECT o.id AS order_id, o.status AS order_status, o.user_id, o.total_amount,
        i.product_id, i.quantity, p.attempt_key, p.provider_order_id, p.dispatch_state, p.outcome,
        p.transaction_key, p.response_deadline, (p.response_deadline < UTC_TIMESTAMP(6)) AS overdue,
        p.create_response_mode, p.callback_mode, r.status AS record_status, r.amount AS paid_amount
        FROM orders o JOIN order_items i ON i.order_id = o.id
        JOIN payment_attempts p ON p.order_id = o.id
        LEFT JOIN order_records r ON r.order_id = o.id
        WHERE o.id IN ({numbers}) ORDER BY o.id""")


def pg_lookup(provider_id):
    query = urlencode({"orderId": provider_id})
    body = container_output("exec", PROJECT + "-commerce-1", "wget", "-qO-", "-T", "4", "-Y", "off",
                            "--header", "X-USER-ID: 135135",
                            "http://127.0.0.1:18181/api/v1/payments?" + query)
    return json.loads(body)


def saved_comparison(directory):
    document = json.loads((directory / "incident-comparison.json").read_text(encoding="utf-8"))
    require(document["project"] == PROJECT and document["stage"] == "comparison", "Wrong comparison evidence")
    rows = document["orders"]
    selected = selected_orders(directory)
    require(len(rows) == 3 and {r["order_id"]: r["attempt_key"] for r in rows} == selected,
            "Saved evidence does not match this run's JMeter fixture")
    return {r["order_id"]: r for r in rows}


def save_evidence(db, directory, project, stage):
    require(project == PROJECT and stage in ("comparison", "recovery"), "Unexpected incident target")
    directory = Path(directory)
    selected = selected_orders(directory)
    original = saved_comparison(directory) if stage == "recovery" else {}
    source = commerce_orders(db, selected)
    require(len(source) == 3 and {int(r["order_id"]) for r in source} == selected.keys(),
            "Commerce did not return exactly the selected three orders")
    logs = container_output("logs", project + "-commerce-1").splitlines()
    rows = []
    for state in source:
        order = int(state["order_id"])
        key = selected[order]
        provider = state["provider_order_id"]
        require((state["user_id"], state["product_id"], state["quantity"], state["total_amount"],
                 state["attempt_key"], state["dispatch_state"], state["create_response_mode"], state["callback_mode"])
                == (OWNER, "2", "1", "1000", key, "STARTED", "DROP", "DROP"), f"Wrong scenario for order {order}")
        match = f"orderId={order} attemptKey={key} providerOrderId={provider}"
        creates = [line for line in logs if f"runId={project} operation=PG_CREATE " in line and match in line]
        unknowns = [line for line in logs if f"runId={project} operation=DB_OBSERVATION result=COMMITTED outcome=UNKNOWN {match}" in line]
        require(len(creates) == len(unknowns) == 1 and "result=TIMEOUT " in creates[0],
                f"Order {order} does not have one timed-out create and a committed UNKNOWN log")
        history = pg_lookup(provider)
        require(isinstance(history, list) and len(history) == 1, f"Order {order} has unexpected PG transaction count")
        tx = history[0]
        require(tx["orderId"] == provider and tx["status"] == "SUCCESS" and tx["amount"] == 1000 and tx["transactionKey"],
                f"Order {order} PG identity, amount or final status disagrees")
        if stage == "comparison":
            require((state["order_status"], state["outcome"], state["overdue"], state["record_status"])
                    == ("PAYMENT_PENDING", "UNKNOWN", "1", "NULL"), f"Order {order} is not overdue and UNKNOWN")
        else:
            require((state["order_status"], state["outcome"], state["record_status"], state["paid_amount"])
                    == ("CONFIRMED", "SUCCESS", "PAID", "1000"), f"Order {order} did not recover")
            require(tx == original[order]["pg"][0] and state["transaction_key"] == tx["transactionKey"],
                    f"Order {order} recovered using a different PG transaction")
        rows.append({"order_id": order, "attempt_key": key, "commerce": state, "pg": history,
                     "create_log": creates[0], "unknown_log": unknowns[0]})
    persist(directory / f"incident-{stage}.json",
            {"project": project, "stage": stage, "observed_at": stamp(), "orders": rows})
    return rows


def recover_saved(db, directory):
    directory = Path(directory)
    evidence = saved_comparison(directory)
    receipts, failures = [], []
    for order, before in evidence.items():
        receipt = {"order_id": order, "time": stamp()}
        try:
            pg = pg_lookup(before["commerce"]["provider_order_id"])
            require(pg == before["pg"], f"Order {order} PG history changed since evidence was saved")
            current = commerce_orders(db, [order])
            require(len(current) == 1 and current[0]["attempt_key"] == before["attempt_key"]
                    and current[0]["provider_order_id"] == before["commerce"]["provider_order_id"]
                    and current[0]["user_id"] == OWNER and current[0]["outcome"] in ("UNKNOWN", "SUCCESS"),
                    f"Order {order} no longer matches saved evidence")
            req = Request(API + f"/api/v1/orders/{order}/payment/reconcile", method="POST",
                          headers={"X-USER-ID": OWNER})
            with urlopen(req, timeout=8) as response:
                receipt["http_status"] = response.status
                receipt["response"] = json.load(response)
            data = receipt["response"].get("data") or {}
            require(receipt["http_status"] == 200 and data.get("orderId") == order and data.get("status") == "CONFIRMED"
                    and data.get("paymentStatus") == "PAID" and data.get("paymentAmount") == 1000
                    and (data.get("paymentAttempt") or {}).get("transactionKey") == pg[0]["transactionKey"],
                    f"Order {order} did not confirm using the saved SUCCESS")
            require(pg_lookup(before["commerce"]["provider_order_id"]) == pg, f"Order {order} changed PG history")
        except HTTPError as error:
            receipt.update(http_status=error.code, error=error.read().decode("utf-8", errors="replace"))
            failures.append(order)
        except (URLError, TimeoutError, RuntimeError, ValueError, OSError, subprocess.TimeoutExpired) as error:
            receipt["error"] = str(error)
            failures.append(order)
        receipts.append(receipt)
        persist(directory / "incident-reconcile.json", receipts)
    require(not failures, f"Recovery incomplete for {failures}; see incident-reconcile.json")
    return receipts
