# Week 10 제출 체크리스트

상태는 구현 및 실행 증거를 확인한 뒤 갱신한다. 계획·파일 존재만으로 완료하지 않는다.

## 관측과 진단

- [x] 실제 health·업무 지표·식별자별 경계 기록으로 증상을 설명한다.
- [x] 최소 두 가설의 예상과 실제를 비교하고 부족한 증거도 표시한다.
- [x] 비밀값·개인정보를 남기지 않고 metric label의 무한 증가를 피한다.

## 동일 업무 복구

- [x] 같은 DB·PG 거래 상태를 유지해 baseline→fault→recovery를 이어 간다.
- [x] 장애 집합은 7·UNKNOWN 3·PG 성공 10이며 같은 집합을 10·0·10으로 복구한다.
- [x] create 추가 0, 예약·재고·포인트·쿠폰 불변 조건과 중복 적용 방지를 확인한다.
- [x] 복구 주문의 실제 Outbox·Kafka·구매 활동·주간 랭킹 API를 연결한다.

## 증거와 종료

- [ ] fault 원문 증거 파일 보존. 사용자가 증거 파일 생성을 보류해 미완료.
- [x] 관련 누적 회귀·lint·ArchUnit 결과와 미검증 위험을 구분한다.
- [ ] 자신이 시작한 테스트 자원만 정리했으며 운영 완료를 주장하지 않는다. Docker daemon 접근 제한으로 격리 Kafka 정리를 확인하지 못했다.

## 기술 글

- [ ] 사용자가 기술 글 작성을 요청하지 않아 미완료.

## 검증 결과 및 제한

- Incident baseline→fault→recovery 연결 테스트, 관련 결제·쿠폰 회귀, API ArchitectureTest, W9 주간 랭킹 흐름 및 chunk 재시작 테스트를 실행했다.
- API·streamer·batch Checkstyle 대상은 통과했다. 전체 세 모듈 `check`는 통합 범위 테스트 단계가 장시간 출력 없이 진행되어 완료 결과로 기록하지 않았다.
- 기존 `OrderConfirmationToPurchaseActivityE2eTest`는 새 incident 연결 테스트와 별도로 실행했을 때 Kafka에서 `order-confirmed:1`을 20초 내 찾지 못해 실패했다. 새 incident 테스트의 실제 Outbox→Kafka→activity→ranking 경로는 통과했지만, 이 기존 단독 E2E 실패의 원인은 미확정이다.
- 실행별 JSON 증거 파일은 저장하지 않았다. 사용자가 생성을 보류했다.
- 운영 배포·실 PG·실계정·부하·전체 장애 조합은 검증하지 않았다.
