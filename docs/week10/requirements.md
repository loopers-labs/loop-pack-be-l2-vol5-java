# Week 10 결제 사고 진단·복구 요구사항

이 문서는 Week 10의 추가 요구사항을 기록한다. 기존 week2 요구사항은 수정하거나 ID를 재사용하지 않는다.

| ID | 요구사항 | 출처 |
| --- | --- | --- |
| R-W10-01 | 로컬 합성 사용자·실제 생성 주문·실제 DB를 사용해 서로 다른 정상 주문 10건과 장애 주문 10건을 준비한다. | Week 10 Implementation Quest · 사고 범위 |
| R-W10-02 | baseline에서 정상 집합 10건을 PG SUCCESS로 확정하고 주문·재고 예약 결과를 보존한다. | Week 10 Implementation Quest · baseline |
| R-W10-03 | fault에서 별도 장애 집합 10건 중 7건은 정상 확정하고, 3건은 PG 거래 SUCCESS 저장 뒤 생성 응답 유실로 내부 UNKNOWN을 유지한다. | Week 10 Implementation Quest · fault |
| R-W10-04 | recovery는 fault와 같은 주문·PG 거래·DB 상태를 사용하고 PG create 추가 없이 기존 거래 조회 결과를 적용한다. | Week 10 Implementation Quest · recovery |
| R-W10-05 | 결제 미확정 수는 DB의 `PENDING`과 `UNKNOWN` 주문 합계로 제공하고, 5초 주기 조회 gauge의 관찰 범위와 갱신 지연을 명시한다. | Week 10 Implementation Quest · 필요한 관측 |
| R-W10-06 | 결제 경계 기록은 주문 ID·attempt key·결제 상태·상태 시각·PG 조회 결과·내부 적용 또는 rollback 결과를 연결한다. 개인 식별자와 비밀값을 기록하지 않는다. | Week 10 Implementation Quest · 필요한 관측 |
| R-W10-07 | 식별자·사용자·주문별 값을 metric label로 만들지 않고 상태·결과와 같은 유한한 값만 label로 허용한다. | Week 10 Implementation Quest · metric label |
| R-W10-08 | 본인 주문 reconcile은 저장된 동일 결제 시도의 권위 PG 조회만 수행하며, 성공 적용 전에 PG 주문 ID·금액을 내부 주문과 대사한다. | Week 10 Implementation Quest · 제한적 복구 |
| R-W10-09 | 조회 실패·미확정·0건·중복 거래·주문 ID 불일치·금액 불일치에서는 내부 성공을 만들지 않고 미확정 상태를 보존하거나 계약된 오류로 중단한다. | Week 10 Implementation Quest · 제한적 복구 |
| R-W10-10 | PG SUCCESS 결과를 반복 적용해도 주문 확정·예약 소비·Outbox 사실·구매 활동은 중복 생성되지 않는다. | Week 10 Implementation Quest · 동일 적용 방지 |
| R-W10-11 | 한 사고 테스트 lifecycle에서 baseline → fault → recovery를 수행하고, 복구 전후 장애 주문 ID 집합이 동일함을 assertion으로 검증한다. | Week 10 Implementation Quest · 동일 상태 실험 |
| R-W10-12 | 복구된 주문의 Outbox·Kafka·구매 활동을 실제 구성 경로로 처리하고 새 주간 snapshot 및 랭킹 API에서 전체 해당 주 입력을 검증한다. | Week 10 Implementation Quest · 후속 결과 |
| R-W10-13 | 테스트는 실제 health·Prometheus HTTP 응답과 업무 gauge 변화를 유한 대기 안에 관찰하며, management port는 실행 시 주입된 주소를 사용한다. | Week 10 Implementation Quest · 관측 확인 |
| R-W10-14 | 각 실행의 fault 증거 원문·hash·단계·시각·주문/attempt/거래 ID·create 수·상태·가설 판단·복구 후 효과를 별도 위치에 보존한다. | Week 10 Implementation Quest · 증거와 Runbook |
| R-W10-15 | 로컬 통제 사고 결과와 운영 배포·실계정·부하·전체 장애 검증을 구분하고, 미확인 위험을 명시한다. | Week 10 Implementation Quest · 제출 |
| R-W10-16 | W8 쿠폰 발급·본인 목록 계약과 기존 테스트 기대값을 보존한다. | Week 10 Implementation Quest · 누적 기능 |

## 완료 판단

이 요구사항은 관련 HTTP·통합 assertion, 실제 실행 응답/증거, 회귀 및 품질 검사 결과가 함께 있을 때만 완료로 표시한다. 계획 문서·파일 marker·skip/0건 실행은 완료 증거가 아니다.
