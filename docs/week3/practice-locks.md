# 실습 · MySQL 잠금을 터미널 두 개로 직접 보기

> `plan.md` ADR-W3-01·03·04의 근거를 손으로 확인한다. 아래 "보이는 결과"는 MySQL 8.0(REPEATABLE READ, `innodb_lock_wait_timeout` 기본 50초)에서 실제로 실행한 출력이다.

## 준비

```bash
docker compose -f docker/infra-compose.yml up -d mysql
# 터미널 두 개를 열고 각각 접속한다 — 이하 "A", "B"
docker compose -f docker/infra-compose.yml exec mysql mysql -uapplication -papplication loopers
```

```sql
-- A에서 한 번만. 앱이 관리하는 테이블과 겹치지 않는 이름을 쓴다.
CREATE TABLE lock_practice (id BIGINT PRIMARY KEY, stock INT NOT NULL);
INSERT INTO lock_practice VALUES (3, 5), (7, 5);
```

실험마다 시작 전에 `UPDATE lock_practice SET stock = 5;`로 되돌린다. **번호 순서대로 A·B에 한 줄씩** 입력한다.

## 실험 1 · 역순으로 잠그면 교착

| 순서 | A | B |
|---|---|---|
| 1 | `BEGIN;` `SELECT * FROM lock_practice WHERE id = 3 FOR UPDATE;` | |
| 2 | | `BEGIN;` `SELECT * FROM lock_practice WHERE id = 7 FOR UPDATE;` |
| 3 | `SELECT * FROM lock_practice WHERE id = 7 FOR UPDATE;` → **멈춤** (B가 7을 가짐) | |
| 4 | | `SELECT * FROM lock_practice WHERE id = 3 FOR UPDATE;` |

**보이는 결과** — 4번 즉시 한쪽이 `ERROR 1213 (40001): Deadlock found when trying to get lock; try restarting transaction`, 다른 쪽은 멈춤이 풀리며 결과를 받는다. InnoDB가 교착을 감지해 한 트랜잭션을 롤백한 것이다. 원인 기록은 `SHOW ENGINE INNODB STATUS\G`의 `LATEST DETECTED DEADLOCK`에서 `HOLDS THE LOCK(S)` / `WAITING FOR THIS LOCK TO BE GRANTED` / `WE ROLL BACK TRANSACTION (n)`으로 본다.

> Redis 분산 락에는 이 감지기가 없다. 같은 상황이면 둘 다 `tryLock` 대기 시간이 끝날 때까지 기다렸다 실패한다 — 그래서 키 정렬이 필수다.

마치면 남은 쪽에서 `COMMIT;`.

## 실험 2 · 같은 순서로 잠그면 기다릴 뿐 교착은 없다

| 순서 | A | B |
|---|---|---|
| 1 | `BEGIN;` `SELECT * FROM lock_practice WHERE id = 3 FOR UPDATE;` | |
| 2 | | `BEGIN;` `SELECT * FROM lock_practice WHERE id = 3 FOR UPDATE;` → **멈춤** |
| 3 | `SELECT * FROM lock_practice WHERE id = 7 FOR UPDATE;` `COMMIT;` | |
| 4 | | 멈춤이 풀림. `SELECT * FROM lock_practice WHERE id = 7 FOR UPDATE;` `COMMIT;` |

**보이는 결과** — B는 A가 commit할 때까지 기다렸다가 3과 7을 차례로 얻는다. 오류 없음. 서로를 원형으로 기다리는 상황이 생길 수 없다.

## 실험 3 · 한 문장 `IN`은 쓴 순서와 관계없이 PK 순서로 잠근다

| 순서 | A | B |
|---|---|---|
| 1 | `BEGIN;` `SELECT * FROM lock_practice WHERE id IN (3, 7) FOR UPDATE;` | |
| 2 | | `BEGIN;` `SELECT * FROM lock_practice WHERE id IN (7, 3) FOR UPDATE;` → **멈춤** |
| 3 | `COMMIT;` | 멈춤이 풀림. `COMMIT;` |

**보이는 결과** — 교착 없음. 주문 확정에서 상품을 `WHERE id IN (…) ORDER BY id FOR UPDATE` 한 문장으로 잠그는 이유다 (ADR-W3-03).

## 실험 4 · 잠금 대기 시간 초과 (ADR-W3-04의 3초)

| 순서 | A | B |
|---|---|---|
| 1 | `BEGIN;` `SELECT * FROM lock_practice WHERE id = 3 FOR UPDATE;` (commit하지 않고 둔다) | |
| 2 | | `SET SESSION innodb_lock_wait_timeout = 3;` `BEGIN;` `SELECT * FROM lock_practice WHERE id = 3 FOR UPDATE;` |
| 3 | | 3초 뒤 결과 확인, 그다음 `ROLLBACK;` |
| 4 | `COMMIT;` | |

**보이는 결과** — B는 정확히 3초 뒤 `ERROR 1205 (HY000): Lock wait timeout exceeded; try restarting transaction`. 기본 설정(`innodb_rollback_on_timeout = OFF`)에서는 **그 문장만** 취소되고 트랜잭션은 열려 있다 — 그래서 애플리케이션이 예외를 받아 트랜잭션 전체를 롤백해야 한다. 이 오류를 "재고 부족"으로 바꾸지 않는다.

## 실험 5 · `NOWAIT` — 기다리지 않고 바로 실패

| 순서 | A | B |
|---|---|---|
| 1 | `BEGIN;` `SELECT * FROM lock_practice WHERE id = 3 FOR UPDATE;` | |
| 2 | | `BEGIN;` `SELECT * FROM lock_practice WHERE id = 3 FOR UPDATE NOWAIT;` |
| 3 | `COMMIT;` | `ROLLBACK;` |

**보이는 결과** — B는 즉시 `ERROR 3572 (HY000): Statement aborted because lock(s) could not be acquired immediately and NOWAIT is set.` MySQL에서 쿼리 단위로 줄 수 있는 대기 옵션은 이것(과 `SKIP LOCKED`)뿐이고, "N초 기다리기"는 실험 4의 세션 변수로 한다.

## 실험 6 · 잠금 없이 읽고 쓰면 갱신 유실 (T-0 대조군과 같은 현상)

| 순서 | A | B |
|---|---|---|
| 1 | `BEGIN;` `SELECT stock FROM lock_practice WHERE id = 3;` → 5 | |
| 2 | | `BEGIN;` `SELECT stock FROM lock_practice WHERE id = 3;` → 5 |
| 3 | `UPDATE lock_practice SET stock = 4 WHERE id = 3;` `COMMIT;` | |
| 4 | | `UPDATE lock_practice SET stock = 4 WHERE id = 3;` `COMMIT;` |
| 5 | `SELECT stock FROM lock_practice WHERE id = 3;` | |

**보이는 결과** — 두 "주문" 모두 성공했는데 최종 재고는 **4**. 1·2번을 `FOR UPDATE`로 바꿔 다시 하면 B는 2번에서 기다렸다가 A가 commit한 4를 읽는다.

## 실험 7 · bulk UPDATE도 행을 잠근다 (브랜드 일괄 삭제 vs 주문 확정)

```sql
-- A에서 한 번만
CREATE TABLE lock_practice_product (id BIGINT PRIMARY KEY, brand_id BIGINT NOT NULL, stock INT NOT NULL,
                                    deleted_at DATETIME NULL, KEY idx_brand (brand_id));
INSERT INTO lock_practice_product VALUES (3, 1, 5, NULL), (7, 1, 0, NULL), (9, 2, 5, NULL);
```

**7-a. 확정이 먼저 잡은 경우**

| 순서 | A (주문 확정) | B (브랜드 1 일괄 삭제) |
|---|---|---|
| 1 | `BEGIN;` `SELECT * FROM lock_practice_product WHERE id = 3 FOR UPDATE;` | |
| 2 | | `BEGIN;` `UPDATE lock_practice_product SET deleted_at = NOW() WHERE brand_id = 1 AND deleted_at IS NULL;` → **멈춤** |
| 3 | `UPDATE lock_practice_product SET stock = stock - 1 WHERE id = 3;` `COMMIT;` | 멈춤이 풀림 → `2 rows affected` |
| 4 | | `SELECT COUNT(*) FROM performance_schema.data_locks WHERE OBJECT_NAME = 'lock_practice_product' AND LOCK_TYPE = 'RECORD' AND THREAD_ID = PS_CURRENT_THREAD_ID();` → 잠금 여러 개 `COMMIT;` |

**보이는 결과** — B의 bulk UPDATE는 A가 commit할 때까지 기다렸다. 최종: 상품 3 재고 4·삭제, 상품 7(재고 0) 삭제, 브랜드 2의 상품 9는 그대로. UPDATE는 바꾸는 행마다 배타 잠금을 잡고 commit까지 들고 있다 (실행 시 레코드 잠금 5개 — 인덱스 `idx_brand`와 PK의 행·범위 잠금).

**7-b. 일괄 삭제가 먼저 잡은 경우**

| 순서 | A (주문 확정) | B (일괄 삭제) |
|---|---|---|
| 1 | | `BEGIN;` `UPDATE lock_practice_product SET deleted_at = NOW() WHERE brand_id = 1 AND deleted_at IS NULL;` |
| 2 | `BEGIN;` `SELECT deleted_at FROM lock_practice_product WHERE id = 3;` → `NULL` (잠금 없는 읽기는 기다리지 않고 commit 전 값을 본다) | |
| 3 | `SELECT deleted_at FROM lock_practice_product WHERE id = 3 FOR UPDATE;` → **멈춤** | |
| 4 | 멈춤이 풀림 → 삭제 시각이 보인다 | `COMMIT;` |

**보이는 결과** — 같은 트랜잭션 안에서도 **잠금 없는 읽기는 스냅샷**(삭제 전), **잠금 읽기는 최신 commit 값**(삭제 후)을 본다. 확정이 상품을 `FOR UPDATE`로 읽어야 "삭제된 상품은 확정 거절"이 경쟁 중에도 지켜지는 이유다.

## 정리

```sql
DROP TABLE lock_practice;
DROP TABLE lock_practice_product;
```
