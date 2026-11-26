# 주간 랭킹 로컬 인수 실행 기록 (W9)

> **실제 PG 확정 → Outbox relay → Kafka → 구매 활동 → snapshot → job → 랭킹 API를 local 환경에서 이었다. 상품 3 계산 중 다른 세션의 잠금으로 실패시킨 뒤 같은 JobInstance로 이어 갔고, 그 사이 도착한 늦은 구매는 r1에 섞이지 않고 r2에서만 반영됐다.**

- 실행일: 2026-11-20 (기간 `periodStart=2026-11-16`, UTC 월요일)
- 환경: `docker/infra-compose.yml`(MySQL·Kafka·Redis), api `:8080`, streamer `:8090`(포트 덮어씀), 가짜 PG `127.0.0.1:8082`
- 자동 테스트와 다른 점: 실패 원인이 spy가 아니라 **실제 DB 잠금 대기 초과**이고, 입력이 SQL로 넣은 행이 아니라 **실제 이벤트로 저장된 활동**이다.

## 준비

| 항목 | 내용 |
|---|---|
| 상품 | 1 운동화, 2 모자, 3 양말 (§6의 상품 7·8·9 역할) |
| 주문 (실제 API) | 주문 1: 상품 1 ×2 + 상품 2 ×1 / 주문 2: 상품 1 ×3 / 주문 3: 상품 3 ×5 |
| 확정 경로 | `POST /orders` → `POST /orders/{id}/confirm`(PG 202 PENDING) → `POST /orders/{id}/payment/reconcile`(PG 조회 SUCCESS) → CONFIRMED + Outbox |
| 가짜 PG | 실습 PG 계약의 최소 형태. 생성은 202 PENDING, 조회는 SUCCESS (아래 부록) |

Outbox 3건이 모두 PUBLISHED가 되고, streamer가 저장한 활동은 4행이었다(주문 1의 두 품목 포함). `confirmed_at`은 UTC로 저장됐다.

| order_id | product_id | quantity | confirmed_at (UTC) |
|---|---|---|---|
| 1 | 1 | 2 | 2026-11-20 08:34:56.900848 |
| 1 | 2 | 1 | 2026-11-20 08:34:56.900848 |
| 2 | 1 | 3 | 2026-11-20 08:35:01.716740 |
| 3 | 3 | 5 | 2026-11-20 08:35:01.796897 |

## 1. 실패: 상품 3 계산 중 잠금 대기 초과

```sql
-- 세션 A (60초 뒤 ROLLBACK)
SET GLOBAL innodb_lock_wait_timeout=5;   -- 시나리오 뒤 50으로 복구
BEGIN;
INSERT INTO weekly_ranking_score_stage (snapshot_id, product_id, score, created_at, updated_at)
VALUES ('weekly-20261116-r1', 3, 0, NOW(6), NOW(6));
```

```bash
./gradlew :apps:commerce-batch:bootRun --args='--job.name=weeklyRanking --ranking.weekly.chunk-size=2 \
  periodStart=2026-11-16,java.time.LocalDate revision=1,java.lang.Long'
```

writer가 상품 3의 stage를 insert하다 `Lock wait timeout exceeded`로 실패했다.

| 확인 | 값 |
|---|---|
| JobInstance / JobExecution | 1 / 1, FAILED |
| `freezeWeeklyInput` | COMPLETED, 입력 4행·상품 3개, 입력 checksum `ad4941c1…5b27c920` |
| `calculateWeeklyScores` | FAILED, read 3·write 2·commit 1 |
| 영속 ExecutionContext (`BATCH_STEP_EXECUTION_CONTEXT` 역직렬화) | `weeklyRankingProductReader.read.count = 2` |
| stage | 상품 1 = 5, 상품 2 = 1 |
| 공개 pointer / API | 없음 / `NOT_PUBLISHED`, items `[]` |

## 2. 늦은 구매 도착

streamer를 멈춘 상태에서 주문 4(상품 2 ×4)를 확정했다. Outbox가 PUBLISHED가 된 뒤 streamer를 다시 띄우자 활동 5행이 됐다(order 4, `confirmed_at` 08:36:29Z, 같은 주). r1 snapshot 입력은 4행 그대로였다.

## 3. 재시작: 같은 parameter·같은 metadata

세션 A가 ROLLBACK한 뒤 1과 같은 명령으로 다시 실행했다.

| 확인 | 값 |
|---|---|
| JobInstance / JobExecution | **1 / 2**, COMPLETED |
| `freezeWeeklyInput` | 새 실행에 없음 (`Step already complete … no action`) |
| `calculateWeeklyScores` | COMPLETED, **read 1·write 1** (상품 3만) |
| r1 입력 | 4행, 입력 checksum 그대로 (`ad4941c1…`) → 늦은 구매 미포함 |
| 완성 ranking | 1위 상품 1 (5), 2위 상품 3 (5), 3위 상품 2 (1) |
| 결과 checksum | `b35024cb…7f9bd66082886e21d6b10208a979f7dc37111` |
| API | `PUBLISHED`, `weekly-20261116-r1`, 위 순위와 현재 상품 이름 |

입력·결과 checksum은 설계 문서의 직렬화 규칙대로 손으로 만든 텍스트를 `shasum -a 256`한 값과 같았다.

## 4. 새 revision: 늦은 구매 포함

```bash
... periodStart=2026-11-16,java.time.LocalDate revision=2,java.lang.Long
```

| 확인 | 값 |
|---|---|
| JobInstance / JobExecution | **2 / 3**, COMPLETED |
| r2 입력 | 5행 (늦은 구매 포함) |
| 완성 ranking | 상품 1·2·3 모두 5점 → 동점 productId 오름차순으로 1·2·3위 |
| 공개 pointer / API | r2 (`published_revision=2`) / `PUBLISHED`, `weekly-20261116-r2` |
| r1 | 행·checksum 그대로 남음 (지우지 않음) |

## 실행하며 확인한 운영상 주의

- **streamer를 local 기본값(`ddl-auto=create`)으로 다시 띄우면 구매 활동 테이블이 지워진다.** 늦은 구매를 위해 streamer를 재기동할 때 `--spring.jpa.hibernate.ddl-auto=none`을 붙였다. batch는 이 문제 때문에 local을 `update`로 바꿔 두었다.
- batch를 `job.name` 없이(기본 `NONE`) 띄우면 "No job found" 로 끝난다. 테이블 생성(`ddl-auto=update`)은 그 전에 일어나므로 이번에는 테이블 준비 용도로 한 번 띄웠다.
- docker 안의 `mysql` 클라이언트로 한글을 넣을 때 `--default-character-set=utf8mb4`가 없으면 깨져 저장된다(시드 데이터만 해당, 앱과 무관).
- W7 같은 이벤트의 재발행이 활동·점수를 늘리지 않는 것은 W6·W7 자동 테스트(`OutboxDeliveryE2ETest` 등)가 검증한다. 이번 인수에서는 다시 하지 않았다.

## 부록: 가짜 PG

저장소에 넣지 않은 실행 도구다(Python 표준 라이브러리만 사용).

```python
# 생성은 202 PENDING, 조회(키·주문)는 SUCCESS. X-USER-ID: 135135 만 받는다.
import json, itertools
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import urlparse, parse_qs, unquote
TX, SEQ = {}, itertools.count(1)
class H(BaseHTTPRequestHandler):
    def _send(self, code, body):
        data = json.dumps(body).encode()
        self.send_response(code); self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(data))); self.end_headers(); self.wfile.write(data)
    def do_POST(self):
        if self.headers.get('X-USER-ID') != '135135': return self._send(401, {'code': 'UNAUTHORIZED'})
        body = json.loads(self.rfile.read(int(self.headers['Content-Length'])))
        key = 'LOCAL-TX-%04d' % next(SEQ)
        TX[key] = {'transactionKey': key, 'orderId': body['orderId'], 'amount': body['amount'], 'status': 'SUCCESS'}
        self._send(202, dict(TX[key], status='PENDING'))
    def do_GET(self):
        if self.headers.get('X-USER-ID') != '135135': return self._send(401, {'code': 'UNAUTHORIZED'})
        u = urlparse(self.path)
        if u.path == '/api/v1/payments':
            oid = parse_qs(u.query).get('orderId', [None])[0]
            return self._send(200, [t for t in TX.values() if t['orderId'] == oid])
        key = unquote(u.path.rsplit('/', 1)[1])
        return self._send(200, TX[key]) if key in TX else self._send(404, {'code': 'NOT_FOUND'})
ThreadingHTTPServer(('127.0.0.1', 8082), H).serve_forever()
```
