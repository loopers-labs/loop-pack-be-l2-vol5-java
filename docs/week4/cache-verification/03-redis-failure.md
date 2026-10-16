# 03 — 상세 캐시의 실제 Redis 중단·재시작과 fallback

상태: **2026-10-15 실제 앱·로컬 redis-master 중단·재시작 1회 실행 완료 / 원상 복구 확인**. Claude 실행 / Codex 원본 검토. Redis 장애·제한 대기·DB 우회·복구 검증은 필수다.

## 질문

실제 master가 멈추면 제한된 대기 뒤 정상 DB로 우회하는가? 재시작 뒤 기본정보의 고정 TTL·원본 일치/재채움 계약을 만족하는가? 초기 connect/command 200ms의 실제 효과는 무엇인가?

## 실행 조건

|항목|값|
|---|---|
|시각|2026-10-15 13:49:50–13:50:01 KST(장애 구간 약 4초)|
|앱|commerce-api bootJar, local 프로필, run3 = `--spring.jpa.hibernate.ddl-auto=none --commerce.cache.product-detail.ttl=10s`, 캐시 on|
|앱 Redis 연결|`RedisStaticMasterReplicaConfiguration`(master localhost:6379 + replica localhost:6380)의 master 전용 factory, `ReadFrom.MASTER`, `redisTemplateMaster`. Lettuce connect timeout 200ms(SocketOptions), command timeout 200ms|
|중단 대상|Docker 컨테이너 `redis-master`(redis:7.0, 7.0.15, `--appendonly yes`, 볼륨 `redis_master_data`)만. `redis-readonly`(replica)·MySQL·kafka·kafka-ui는 건드리지 않음|
|DB|로컬 `docker-mysql-1` MySQL 8.0.46 `loopers`, 진단 seed 자료. 장애 구간 내내 정상|
|기타|순차 요청만 사용하고 동시 부하는 없음. CPU는 수집하지 않음. 같은 시간에 다른 앱/측정은 실행하지 않음|

장애 중 DB 변경은 관리자 API가 HTTP에서 403이어서 SQL `UPDATE product SET price = 777 WHERE id = 7`로 직접 수행했다. 앱의 commit 뒤 DEL이 실행되지 않는 변경이며, DEL 실패와 같은 결과 조건이다. 실험 뒤 원래 가격 359로 되돌렸다.

## 관측 타임라인 (t = 스크립트 시작 기준 초)

|t|단계|결과|
|---|---|---|
|0.46 / 0.54|정상 miss → hit|200, 335ms(앱 기동 뒤 첫 요청) / 20ms. key 저장(PTTL 9,814, price 359)|
|1.46|`docker stop redis-master`|828ms 만에 `exited`|
|1.95–3.39|장애 중 상품 7 상세 4회|모두 **200**, 가격 359(DB 값), **424.6–431.7ms**. 요청마다 WARN 2줄(캐시 읽기 실패·채움 실패). 첫 요청에는 Lettuce `Cannot reconnect … Connection initialization timed out`도 기록|
|3.87|장애 중 한 번도 캐시하지 않은 상품 17|200, 425.6ms, DB 값|
|3.97|장애 중 목록(브랜드 8·가격순)|200, 45.8ms, WARN 없음(목록은 Redis를 쓰지 않음)|
|4.02|SQL로 상품 7 가격 777로 변경|-|
|4.50 / 4.98|장애 중 상품 7 상세|200, **777**(DB 우회라 새 값), 419.7 / 431.1ms|
|5.16|`docker start redis-master` → PONG|start부터 PONG까지 119ms. **AOF로 key 복원: PTTL 5,260, 값 price 359(옛 값)**|
|5.63 / 6.33|재시작 직후 상세 2회|200·777이지만 아직 WARN·416/437ms. 앱 연결은 재연결 대기 중이었다|
|6.70|앱 첫 Redis 성공|Lettuce `Reconnected to localhost:6379`(Redis 기동 약 1.6초 뒤). 이 요청부터 WARN 0|
|6.70–10.35|복원된 옛 key의 hit 12회|200이지만 **옛 가격 359**(DB는 777), 13–112ms|
|10.69|옛 key 실제 만료 뒤 첫 조회|200, **777**, key를 777로 재채움(PTTL 9,859). 이후 hit 6.9ms|
|10.91–10.99|정리|replica `master_link_status`가 처음에는 `down`, 직후 재확인에서 `up`·`connected_slaves:1`. 가격 359 복원·실험 key DEL|

## 판단

- **제한 대기·DB 우회(기능)**: master가 멈춘 동안 상세 요청은 실패하지 않고 정상 DB 값으로 응답했다. 요청당 약 420–430ms가 걸렸다. 그중 Redis 대기는 GET timeout(약 200ms) + miss 채움 SET timeout(약 200ms)이 차지하고, 정상 경로는 7ms 안팎이다. 200ms command timeout이 실제로 적용됐다는 근거다. 다만 이는 HTTP 전체 상한이 아니라 Redis 명령별 대기 한도다. 장애 중에는 매 요청이 GET과 SET을 모두 시도하므로 대기가 두 번 생긴다.
- **connect timeout**: 재연결 시도가 `Connection initialization timed out`으로 약 0.3초 만에 끝났다(51.210 시도 → 51.508 실패). 즉시 연결 거절이 아니라 연결 초기화 시간 초과로 기록됐다. Docker Desktop 포트 전달이 중단된 컨테이너 대신 연결을 받았을 가능성이 있으나 원인은 확인하지 않았다. 이 실패 시간이 200ms connect timeout만으로 정해졌는지도 분리해 검증하지 않았다.
- **연결 복구 ≠ 데이터 최신**: Redis 재시작은 AOF로 장애 전 key를 남은 TTL과 함께 복원했다. 앱이 재연결한 뒤 그 옛 기본정보(359)가 실제 만료까지 약 3.7초 동안 hit로 제공됐다. 만료 뒤 다음 DB miss가 새 값(777)으로 다시 채웠다. 정책(실패·늦은 fill의 옛 기본정보는 실제 만료까지 허용, 만료 뒤 DB 재채움으로 복구)에 맞는 결과이며, "재시작 뒤 즉시 최신"이라고 판정하지 않는다.
- **재연결 지연**: Lettuce 기본 재연결 backoff 때문에 Redis 기동 뒤 약 1.6초 동안은 계속 장애 경로(약 420ms)였다. backoff 설정은 변경하지 않았다.
- **보호 계약**: 장애 중에도 노출 확인·좋아요 수는 DB 조회였다. 목록·좋아요·주문 경로는 Redis에 의존하지 않는다. 주문 확정의 commit 뒤 DEL이 실패해도 주문 결과는 유지된다(04의 주입 테스트로 확인).

## 자원 정리·복구 확인

- `redis-master`: 같은 컨테이너를 `docker start`로 재시작했고 `running`·`healthy`다. AOF는 켜진 상태(`aof_enabled:1`)이고 `DBSIZE 0`이다. 실험 key는 `commerce:product-detail:v1:7` 하나만 DEL했으며, FLUSHALL은 사용하지 않았다.
- `redis-readonly`: `role:slave`, `master_link_status:up`, `master_sync_in_progress:0`. master 쪽 `connected_slaves:1`.
- MySQL: 상품 7 가격 359 복원. 진단 seed 자료와 I 스키마는 남겨 두었다.
- 앱 프로세스는 `stop-app.sh`로 종료했고 남은 java 프로세스는 없다.

## 한계

- 1회 실행이고 장애 구간은 약 4초다. 순차 요청만 보냈으며 동시 부하 중 장애, 즉 DB가 캐시 전체 장애 부하를 감당하는지에 대한 용량은 검증하지 않았다.
- `docker stop`(프로세스 종료)만 재현했다. 명령에 응답하지 않는 정지(`docker pause`, 네트워크 지연·패킷 손실)는 별도 실험 제안이며 실행하지 않았다.
- 장애 중 DB 변경은 앱 밖 SQL로 수행했다. 관리자 API 경로의 실제 DEL 실패는 [02](02-consistency.md)의 주입 테스트로만 확인했다.
- replica는 앱 읽기 대상이 아니다(`ReadFrom.MASTER`). replica 지연은 관측 대상이 아니다.
