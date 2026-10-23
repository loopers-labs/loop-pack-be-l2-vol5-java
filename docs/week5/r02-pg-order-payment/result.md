# R02 구현·검증 결과

2026-10-22 기준 **로컬 구현과 검증을 완료했다.** `volume-5/r02-pg-order-payment`에서 작업했으며 PR 대상은 `volume-5/main`이다. 이후 사용자 요청으로 [PR #25](https://github.com/corinB/loop-pack-be-l2-vol5-java/pull/25)를 게시했고 사용자가 병합했다. 병합 커밋은 `42c22c2`다.

[요구사항](requirement.md) · [계획·커밋 순서](plan.md) · [기능 리뷰](review.md) · [실험 재현 방법](verification.md) · [상태 흐름](../states/total.md)

## 이번에 연결한 흐름

**재고를 먼저 차감하고 PG를 호출한다. 성공이면 차감을 유지하고, 확정 실패이면 한 번만 돌려준다.** 결과를 모르면 주문은 결제 대기, 재고는 차감 상태로 남긴다.

| 구간 | 함께 처리하는 일 |
|---|---|
| 준비 TX | 주문 상태 CAS 선점, 재고 차감, 결제 시도·발송 시작·차감 기록·복구 작업 저장 |
| TX 밖 PG 요청 | 최초 발송 권한을 받은 실행만 실제 결제 생성 호출 |
| 관찰 저장 TX | 검증한 결과·거래 키·확정 거절·충돌 근거를 먼저 저장 |
| 공통 업무 TX | 주문 종결 CAS, 성공 기록 또는 재고 반환, 차감 기록 종결, 작업 APPLIED를 함께 저장 |

같은 주문·키·금액·실효 테스트 조건은 기존 시도를 반환한다. 다른 키나 조건은 충돌이며 두 번째 결제를 만들지 않는다. 카드 입력은 요청 비교에서 제외하고 원문은 저장·로그에 남기지 않는다.
PG 호출은 DB TX 밖이다. callback과 수동 대사는 같은 관찰 저장·공통 업무 반영을 사용하며, 관찰 저장 뒤 업무 TX만 실패해도 결제 근거는 남는다.

## API에서 확인할 수 있는 것

| 경로 | 동작·접근 |
|---|---|
| `POST /api/v1/orders/{id}/confirm` | 본인 주문의 PG 결제 시작. 업무 처리 완료 200, 미완료 202 |
| `GET /api/v1/orders`, `GET /api/v1/orders/{id}` | 본인의 저장된 주문·결제·복구 상태 조회 |
| `GET /api-admin/v1/orders`, `GET /api-admin/v1/orders/{id}` | 관리자 1번 fixture의 주문 조회 |
| `GET /api-admin/v1/payments/incomplete` | 결제 대기·수동 확인·결과 충돌 목록 |
| `POST /api/v1/orders/{id}/payment/reconcile` | 본인 주문의 저장 종결 근거 재적용 또는 기존 PG 거래 조회 최대 1회 |
| `POST /api-admin/v1/orders/{id}/payment/reconcile` | 관리자 1번 fixture의 동일 대사 처리 |
| `POST /api/v1/payments/callback` | 고객 헤더 없이 알림 수신 → PG 권위 조회 → 관찰 저장 → 공통 반영 |

고객/관리자 API는 기존 `X-USER-ID`를 사용한다. 타인 고객 주문은 없는 주문과 같은 404다. 관리자는 다른 고객의 주문도 처리할 수 있고 응답의 조회 링크도 관리자 경로를 사용한다.
GET은 PG 호출·재고 변경·실행 인계를 하지 않는다. callback 본문의 status로 결제 결과를 결정하지 않으며, 저장 식별자로 조회한 결과와 알림의 거래 키를 대조한다.
이미 업무 완료한 주문에 늦은 결과 충돌이 생기면 완료 상태를 유지하고 주의를 표시한다. 미반영 충돌은 202로 남긴다.

## 필수 전체 검사

기능 코드 기준 `73bc6cf`에서 두 JAR와 아래 전체 검사를 실행했다. 이후 변경은 이번 검증 기록과 문서뿐이다.

```powershell
$env:JAVA_HOME = 'C:/Program Files/Java/jdk-21.0.10'
./gradlew.bat --offline -I build/docker-api.init.gradle :apps:test-pg:bootJar :apps:commerce-api:bootJar :apps:test-pg:check :apps:commerce-api:check --console=plain
```

| 모듈 | 테스트 | 실패 / 오류 / 건너뛰기 |
|---|---:|---|
| test-pg | 25 | 0 / 0 / 0 |
| commerce-api | 407 | 0 / 0 / 0 |
| 합계 | **432** | **0 / 0 / 0** |

Checkstyle·ArchUnit과 기존 slow/example 검사를 포함해 `BUILD SUCCESSFUL`을 확인했다. 과거 POINT 조회·재확정 오류, 포인트 충전/조회, 캐시 commit/rollback, 소유권, 멱등성/경합도 기존 검사와 함께 통과했다.
로컬 Docker API 호환용 무시 대상 init script는 `api.version=1.44`만 지정한다. 검사 기준·추적 대상 설정을 완화하지 않았고 전체 검사도 불필요하게 반복하지 않았다.

자동화 검사는 실제 MySQL과 gateway 제어를 함께 사용한다. 권위 조회 최대 1회/저장 근거 조회 0회, 거래 없음·불일치·늦은 충돌, 4회 사용 fixture의 예산 보존, 옛 실행의 늦은 저장 차단은 이 검사에서 확인했다. 별도 서버 실험의 거래 수만으로 HTTP 호출 횟수까지 증명했다고 해석하지 않는다.

## 별도 서버 실험 10개

commerce와 PG를 각각 독립 JVM으로 실행했다. 기존 실험 전용 MySQL `payment-lab-verification:13316`의 두 스키마만 사용했고, 기존 로컬 MySQL에는 SQL을 적용하지 않았다. R01~R02의 명시 SQL을 적용한 DB에서 `payment-lab`의 validate 기동을 확인했다.

각 주문은 가격 1,000원·재고 5개인 별도 상품 2개를 주문했다. 아래 숫자는 실제 HTTP 처리 뒤 DB에서 확인한 값이다. 모든 주문의 포인트 USE 기록은 0건, 최종 복구 작업은 APPLIED였다.

| 주문 | 실험·확인한 경계 | 최종 주문 | 재고 | PG 거래 / 주문 확정 기록 |
|---|---|---|---:|---|
| 45 | 같은 키 confirm 4개 동시 요청, 생성 응답 1.7초 지연. callback이 약 0.324초 만에 주문 기록을 먼저 저장했고 늦은 PENDING도 성공을 되돌리지 않음 | CONFIRMED | 3 | 1 / 1 |
| 46 | 생성 응답 유실·callback 생략·6초 뒤 PG 실패. 처음 202/차감 유지 → 수동 대사 → confirm/대사 반복에도 추가 반환 없음 | PAYMENT_FAILED | 5 | 1 / 0 |
| 47 | 선차감 후 관리자가 가용 재고를 10으로 설정. 실패 callback에서 원래 차감 2개만 더함 | PAYMENT_FAILED | 12 | 1 / 0 |
| 48 | 선차감 후 상품 삭제. 실패 callback은 재고를 복구하고 삭제 표시를 유지 | PAYMENT_FAILED | 5 | 1 / 0 |
| 49 | PG 성공 뒤 주문 기록 삽입 강제 실패. SUCCESS만 남고 주문/재고/완료는 rollback. PG를 끈 채 저장 근거로 대사 | CONFIRMED | 3 | 1 / 1 |
| 50 | 수동 PG 조회 도중 commerce 강제 종료. 재시작 후 같은 RUNNING/실행 ID/시각/사용 횟수 유지 → 운영 목록 확인 → 새 실행으로 수동 인계 | CONFIRMED | 3 | 1 / 1 |
| 51 | PG의 지연 처리 중 PG 강제 종료. 재시작 후 같은 거래 키·예정 결과가 보존되고 완료 → 수동 대사 | CONFIRMED | 3 | 1 / 1 |
| 52 | 거래 생성 전 확정 거절. 실제 외부 거래 없이 저장 거절 근거로 복구 | PAYMENT_FAILED | 5 | 0 / 0 |
| 53 | callback의 업무 저장을 3초 지연. PG 송신자는 2초 timeout 로그를 남겼지만 commerce의 업무 반영은 완료 | CONFIRMED | 3 | 1 / 1 |
| 54 | 관리자 재고를 int 최대값으로 설정한 뒤 실패 복구. 전체 업무 rollback·FAILED 근거 보존·MANUAL_REQUIRED/202 → 재고를 10으로 조정 후 저장 근거 재적용 | PAYMENT_FAILED | 12 | 1 / 0 |

최종 합계는 성공 5건·실패 처리 완료 5건·이번 실험의 미완료 0건이다. 선차감 뒤 응답이 없을 때는 재고 3/주문 미완료를 유지했고 임의 복구하지 않았다.
주문 50의 재시작 실험은 사용 회차 **0회 보존**을 확인했다. R03의 자동 4회 실행을 수행한 실험이 아니다. 이미 4회 사용한 작업의 수동 예산 보존은 자동화 검사에서 별도로 확인했다.
주문 53은 **PG가 callback 응답을 받았는지와 commerce가 실제 처리했는지가 다를 수 있음**을 직접 확인한 사례다.

## 보존·정리

- 기존 POINT 주문 42는 CONFIRMED·7,000원·PAID·POINT·기록 시각 `2026-10-01 01:02:03.123456`을 유지했고 실제 고객 GET도 200이었다.
- 전용 실험 DB의 wallet 행은 전후 0건이었다. 새 PG 결제가 지갑을 만들거나 포인트를 사용하지 않았다. 포인트 API 회귀는 전체 검사로 확인했다.
- 실패/지연 주입은 주문 49·53에만 반응하는 임시 DB trigger로 수행했다. finally에서 제거했고 잔여 trigger 0개를 확인했다. 제품 코드·공개 API에는 장애 주입 기능을 추가하지 않았다.
- 이 작업에서 시작한 commerce/PG JVM과 전용 MySQL은 정지했다. DB 데이터·볼륨과 기존 MySQL/Redis는 보존했다. 기존 실험 주문 43의 미완료 상태 등 이전 데이터는 정리 대상으로 삼지 않았다.
- JMeter 사용자 변경은 그대로 두고 staging하지 않았다. 원문 두 파일의 SHA-256도 작업 전과 일치한다.

전체 검사 로그/집계는 로컬 `build/r02-full-check.log`, `build/r02-full-check-summary.json`, 각 모듈 `build/test-results/test/`에 있다. 서버 로그는 `build/r02-final-{api,pg}-*.log`, 실험 관찰은 `build/r02-final-lab-state.json`에 남겼다. 기기별 helper·실험 데이터·로그는 커밋하지 않는다. 저장소에 남는 재현 절차는 [verification.md](verification.md)다.

## 리뷰와 남은 범위

기능 커밋 전마다 비즈니스·사용자 경험·기술 리뷰를 받았다. 중요한 보완은 사용자 승인 후 수정·관련 검사·재리뷰를 완료했으며 [기록](review.md)에 남겼다. 미해결 중요 지적은 없다.
이번 마무리에서는 기능 결함이나 추가 수정이 발견되지 않았고, 기능 변경 없는 검증·문서는 별도 서브에이전트 리뷰 대상에서 제외하는 기존 규칙을 따랐다.

R03의 자동 재조회·지터·서킷브레이커·fallback은 아직 구현하지 않았다. 재시작 후 자동 회수나 lease도 없다. 담당자가 미완료 목록을 읽고 명시적으로 POST 대사해야 한다.
`X-USER-ID`와 관리자 1번은 학습용 fixture이며 실제 인증이 아니다. 주문당 한 번의 결제 시도만 지원하고 실패 후 새 결제·환불·취소는 범위 밖이다. PG callback은 best effort 1회이며 미전달 시에도 저장 거래의 수동 대사가 가능하다.
