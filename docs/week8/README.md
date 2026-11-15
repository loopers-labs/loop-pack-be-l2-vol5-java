# 8주차 쿠폰 발급 실습

MySQL은 쿠폰 발급 총량과 사용자별 중복을 보호하고, Redis 대기열은 신청을 보관한다. 신청 시 이미 발급된 쿠폰이 있으면 기존 쿠폰 ID를 즉시 반환한다. 신규 신청은 접수 응답을 반환하고 서버별 작업자가 대기열에서 꺼내 발급한다.

과제 원문의 세마포어 대신 멘토와 논의한 대기열을 사용한다. 따라서 신규 발급을 완료한 뒤 `201`을 반환하는 원문의 동기 흐름과 달리, 신청 접수 시 `202`를 반환하고 발급 여부는 본인 쿠폰 목록에서 확인한다. 동시 중복 방어도 트랜잭션 안에서 재조회하는 원문과 달리, DB 유일 제약 충돌을 롤백한 뒤 별도 조회로 기존 쿠폰을 반환한다.

## 로컬 실행

아래 예시는 저장소 루트와 로컬 프로필 기준이다. `X-USER-ID`는 기존 로컬 식별 방식이며 실제 서비스 인증이 아니다.

**commerce-api의 로컬 프로필에서 적용되는 `spring.jpa.hibernate.ddl-auto` 기본값은 `update`다.** `jpa.yml`에도 `create`가 선언돼 있으므로 다른 실행 설정을 사용할 때는 실제 적용값을 확인한다. 같은 MySQL에 JVM 여러 대를 띄울 때는 모든 인스턴스에서 `--spring.jpa.hibernate.ddl-auto=none` 등으로 설정을 덮어쓰고 스키마를 별도로 준비한다. 스키마가 이미 필요한 테이블과 유일 제약을 갖추었는지 확인한 뒤 시작한다.

1. 필요하면 `docker compose -f docker/infra-compose.yml up -d mysql redis-master redis-readonly kafka`로 로컬 인프라를 실행한다.
2. 신규 접수는 기본적으로 닫혀 있다. 이전 발급 작업과 DB 결과를 확인한 로컬 실습에서만 `./gradlew :apps:commerce-api:bootRun --args='--coupon.queue.open-on-start=true'`로 명시적으로 연다. 각 서버는 대기열 작업자 하나를 실행한다. 이 옵션을 자동 재시작 설정에 상시 지정하지 않는다.
3. 애플리케이션의 로컬 스키마 준비 후 `docker compose -f docker/infra-compose.yml exec -T mysql mysql -uapplication -papplication loopers < docs/week8/seed-campaign.sql`을 실행한다. 매 실행마다 새 100장 캠페인을 만들고 `campaign_id`를 출력하며 기존 행을 바꾸지 않는다.
4. `users`에 있는 실습용 사용자 ID를 사용한다. 없으면 로컬 DB에서 `INSERT INTO users (id) VALUES (800000001) ON DUPLICATE KEY UPDATE id = id;`로 준비할 수 있다. 이미 같은 ID가 있으면 기존 사용자가 유지된다.
5. 출력된 ID로 `curl -i -X POST -H 'X-USER-ID: 800000001' http://localhost:8080/api/v1/coupon-campaigns/<campaignId>/issue-requests`를 호출한다. 신규 신청이면 202 접수 응답을 받고, 이미 발급된 쿠폰이 있으면 200과 기존 couponId를 받는다. 잠시 후 `curl -i -H 'X-USER-ID: 800000001' http://localhost:8080/api/v1/coupons`에서 본인 쿠폰 목록을 확인한다. 꺾쇠를 포함하지 말고 실제 ID로 바꾼다.

신청 전에 사용자와 기존 쿠폰을 DB에서 확인하므로 신규 신청과 거절되는 요청에도 DB 조회 부하는 발생한다. 대기열 크기 100은 대기 중인 신청의 상한이며, 서버 N대의 작업자 수 또는 전체 DB 연결 수를 제한하지 않는다.

## 장애 복구와 검증

확인된 DB 잠금 경합은 제한된 횟수만 재시도한다. DB 연결 오류·권한 오류 또는 처리 결과가 불명확한 경우에는 신청을 보존하고 수동 확인을 기다린다. Redis 장애 후에도 자동 재개하지 않는다. 자세한 동작과 복구 방법은 [대기열 문서](redis-coupon-queue.md)와 [복구 절차](coupon-queue-recovery.md)를 참조한다.

`./gradlew :apps:commerce-api:test --tests '*Coupon*Test'`는 쿠폰 관련 테스트를 실행하고, `./gradlew :apps:commerce-api:check`는 회귀 테스트·Checkstyle·ArchUnit을 포함한다. 실제 MySQL·Redis 통합 테스트에는 Docker 접근이 필요하다. 실행된 테스트 수와 실패·skip을 확인해야 한다.
