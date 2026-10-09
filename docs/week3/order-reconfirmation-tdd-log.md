# W3 주문 재확정 거절 TDD 실행 기록

[정책 P16](../week2/commerce-policy-decisions.md#구현-전에-확인할-정책-선택) · [API 계약](../week2/commerce-api-contract.md#고객-api-계약표) · [TDD 계획](../week2/commerce-tdd-plan.md#w3-주문-재확정-거절의-tdd-계획) · [완료 체크리스트](../week2/commerce-completion-checklist.md#w3-주문-재확정-거절)

2026-10-08 사용자가 승인한 **이미 확정된 주문의 재확정 거절 `W3-RECONFIRM-01..06`을 구현·검증했다.** W2의 재확정 성공 반환을 P16으로 변경하며, 당시 실행 기록은 과거 증거로 보존한다. 브랜드 일괄 삭제 구현과 잠금 순서는 유지한다.

## 계약과 변경 책임

| 요청 상황 | 결과 | 저장 상태 |
| --- | --- | --- |
| 본인 CONFIRMED 주문 재확정 | `409 ORDER_ALREADY_CONFIRMED` / `이미 확정된 주문입니다.` | 최초 주문·항목·금액·확정 시각·재고·포인트 보존 |
| 타인의 CONFIRMED 주문 | `404 ORDER_NOT_FOUND` 우선 | 주문 존재·상태를 노출하지 않음 |
| 확정 후 상품 또는 브랜드 삭제 | 재확정은 같은 409, GET은 최초 확정 결과 | 삭제와 무관하게 과거 주문 스냅샷 보존 |
| 유효한 같은 DRAFT의 동시 확정 | 성공 1건·상태 거절 1건 | 차감 1회·확정 1회 |
| 재고 또는 포인트 부족으로 최초 확정 실패 | 기존 부족 오류, 보충 후 최초 확정 가능 | 실패 시 DRAFT 유지. 성공했던 주문의 재확정과 구분 |

실패 응답은 `meta.result=FAIL`, 위 errorCode/message, `data` 생략이다. 기술적 교착·잠금 타임아웃은 기존 `CONCURRENT_MODIFICATION`으로 구분하며 정상 상태 거절로 세지 않는다.

| 파일·책임 | 변경 |
| --- | --- |
| [Order](../../apps/commerce-api/src/main/java/com/loopers/domain/order/Order.java) · [OrderException](../../apps/commerce-api/src/main/java/com/loopers/domain/order/OrderException.java) | `requireDraft()`가 확정 상태를 거절하고 `confirm()`에서도 같은 규칙을 검사 |
| [OrderService](../../apps/commerce-api/src/main/java/com/loopers/application/order/OrderService.java) | 주문 잠금 → 소유권 확인 → 상태 확인. 재확정은 재고·포인트 검증/차감 전에 종료 |
| [CommerceErrors](../../apps/commerce-api/src/main/java/com/loopers/interfaces/api/commerce/CommerceErrors.java) | 도메인 사유를 승인한 409·코드·메시지로 변환 |

기존 `READ_COMMITTED` 트랜잭션과 주문 → 사용자 → 브랜드 ID 오름차순 → 상품 ID 오름차순의 비관적 잠금 순서는 바꾸지 않았다. 새 테이블·컬럼·요청 식별 키·자동 재시도는 추가하지 않았다.

## 테스트 연결

| ID | 테스트 | 확인 내용 |
| --- | --- | --- |
| W3-RECONFIRM-01 | [OrderTest](../../apps/commerce-api/src/test/java/com/loopers/domain/order/OrderTest.java) | 두 번째 `confirm()`의 정확한 예외 사유, 최초 시각·금액·항목·상태 유지 |
| W3-RECONFIRM-02·04 | [OrderV1ApiE2ETest](../../apps/commerce-api/src/test/java/com/loopers/interfaces/api/order/OrderV1ApiE2ETest.java) | 활성 상품/상품 삭제/브랜드 삭제의 3사례. 실제 재확정 HTTP 오류·GET 원본 응답·6개 테이블 전체 행 보존 |
| W3-RECONFIRM-03 | 같은 HTTP 테스트 | bob이 alice의 확정 주문과 없는 주문을 요청할 때 POST·GET 모두 같은 404, DB 보존 |
| W3-RECONFIRM-05 | [OrderConcurrencyIntegrationTest](../../apps/commerce-api/src/test/java/com/loopers/application/order/OrderConcurrencyIntegrationTest.java) | 실제 서비스·MySQL의 같은 주문 경합: 성공 1·정확한 상태 예외 1·차감 1회. 별도 순차 재요청은 전체 DB 보존 |
| W3-RECONFIRM-06 | 같은 HTTP 테스트 · [OrderScenarioApiE2ETest](../../apps/commerce-api/src/test/java/com/loopers/interfaces/api/order/OrderScenarioApiE2ETest.java) | 재고·포인트 부족의 2사례를 실제 보충 API → 같은 DRAFT 최초 확정 성공 → 재확정 거절까지 보강. 기존 수량 합산·재고 보충 시나리오도 회귀 검증 |

고객 요청은 실제 HTTP 서버를 사용한다. 상품·브랜드 삭제 및 재고 설정은 기존 `AdminMockMvc`로 실제 Security·controller·service·MySQL을 연결한다. 신규 재확정 거절·순차 재요청의 DB 비교 대상은 brand·product·order·order_item·user·like의 6개 테이블이다. 기존 OrderScenario의 상태 비교는 주문·항목·상품·사용자의 4개 테이블을 유지한다.

동시성 테스트는 worker의 **시작만 맞춘다**. 기존 테스트의 트랜잭션 내부 커밋 보류 장벽은 과제 조건에 맞게 제거하고, 커밋 후 결과 보존은 순차 테스트로 분리했다. 실제 경합 검증은 별도 동시 실행 사례가 담당한다. 모든 worker 종료 후 상태를 확인하며, 모든 스케줄이나 실제 잠금 대기 시간을 강제 관찰했다는 뜻은 아니다.

## Red·Green·Refactor 기록

| 단계 | 실제 결과 |
| --- | --- |
| 준비 | 새 예외 enum과 HTTP 매핑을 먼저 연결해 테스트 및 exhaustive switch를 컴파일 가능하게 했다. 이 시점에는 새 예외를 발생시키는 업무 검사가 없었다 |
| 도메인 Red | `OrderTest` 8개 중 1개 실패. 기존 `confirm()`이 재확정을 조용히 반환하여 `Expecting code to raise a throwable` 발생 |
| 도메인 Green | `requireDraft()`와 `confirm()` 방어를 추가한 뒤 8개 통과 |
| 통합 환경 오류 | Docker 엔진이 꺼져 DB를 시작하지 못해 47개 실패. 업무 코드 실행 전 환경 오류이므로 Red 증거로 세지 않음. Docker 기동 후 재실행 |
| HTTP·경합 Red | HTTP 40개+경합 7개 중 5개 실패. HTTP 3사례는 기대 409/실제 200, 경합은 성공 1 기대/실제 2, 순차 재요청은 예외 미발생. 서비스의 기존 성공 반환 분기가 원인 |
| 서비스 Green·회귀 | 소유권 확인 후 `requireDraft()`를 호출하도록 교체. 보충 후 재시도 검증까지 포함한 관련 4개 클래스·60개 테스트 통과 |
| 정리 | 도메인 상태 규칙을 서비스에서도 재사용. 이전 성공 기대값 변경은 P16 승인에 따른 계약 변경이며 검사 통과를 위한 완화가 아님 |

## 실행 명령과 최종 결과

```shell
./gradlew :apps:commerce-api:test --tests '*domain.order.OrderTest' --console=plain -q
./gradlew :apps:commerce-api:test --tests '*OrderV1ApiE2ETest' --tests '*OrderConcurrencyIntegrationTest' --console=plain -q
./gradlew :apps:commerce-api:test --tests '*domain.order.OrderTest' --tests '*OrderV1ApiE2ETest' --tests '*OrderConcurrencyIntegrationTest' --tests '*OrderScenarioApiE2ETest' --console=plain -q
./gradlew :apps:commerce-api:check --console=plain -q
```

2026-10-08 16:17 KST, 관련 60개(도메인 8·HTTP 40·경합/순차 7·연결 시나리오 5)가 실패·오류·건너뜀 없이 통과했다. **16:19 KST, 최종 전체 check가 종료 코드 0으로 통과**했다.

| 검사 | 최종 결과 |
| --- | --- |
| API 모듈 테스트 XML | 55개 스위트·541개 통과, 실패·오류·건너뜀 0개 |
| Checkstyle | 연결된 Java 모듈의 XML 보고서 11개, 위반 0개 |
| ArchUnit | ArchitectureTest 1개 통과, 기존 계층 규칙 유지 |
| 변경·문서 확인 | `git diff --check` 통과. 변경한 설계·계약·정책·계획·체크리스트·본 기록의 로컬 링크·앵커·표·코드 블록 검사 |

전체 수치는 `apps/commerce-api/build/test-results/test/TEST-*.xml`을 합산했으며 개별 실행 수를 더하지 않았다. 기존 538개 대비 HTTP의 실행 사례 3개가 증가했고, 재고·포인트 보충 후 재시도는 기존 2개 사례를 보강했다. API check는 연결된 다른 모듈의 정적 검사도 포함하지만 그 모듈의 별도 통합 테스트를 모두 실행했다는 뜻은 아니다.

## 남은 범위

이 기록은 W3-RECONFIRM-01..06 범위만 추적한다. **주문 내부 저장 경계의 실패 주입·롤백, 갱신 유실 대조군, 과제 지정 8개 주문 재고 경쟁·3개 주문 포인트 경쟁·충전 수치 시나리오**는 별도 후속 작업이다. 기존 W2 검사나 이번 재확정 검사 통과로 그 과제까지 완료 처리하지 않는다.
