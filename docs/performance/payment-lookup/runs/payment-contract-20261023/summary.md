# 결제 HTTP 계약 보완 검증

reconcile의 조회 불가 결과가 응답에서 사라지는 문제를 보완했습니다. 마지막으로 확인한 결제 상태는 유지하고 이번 조회가 불가능했을 때 `lookupUnavailable: true`를 반환합니다. confirm과 일반 주문 조회에는 이 필드를 추가하지 않았습니다.

## 변경과 검증

- Red: timeout, 회로 차단, 호출 제한, 전체 조회 시간 초과 4개 테스트에서 응답 필드가 없어 실패하는 것을 확인했습니다.
- Green: 조회 결과를 응답까지 전달한 뒤 정상 PENDING, 조회 불가, 종결 재요청, 결과 반영 rollback을 구분했습니다.
- confirm HTTP 테스트에 소유권 거절, 다른 주문의 키 재사용, 같은 주문의 새 키, PG 성공 결과 재요청, 이전 POINT 주문 거절을 추가했습니다. 응답과 저장 상태, 재고, 포인트, PG 생성 수를 확인했습니다.
- 실제 adapter의 HTTP 요청을 캡처해 POST와 두 GET의 메서드, 경로, 제공자 헤더, 생성 JSON 타입과 고정 callback URL, 거래 키 경로 및 주문 ID query 인코딩을 검증했습니다.
- 대상 테스트 47개와 Checkstyle이 통과했습니다.

## 전체 검사

```bash
./gradlew :apps:commerce-api:check :apps:test-pg:check
```

Commerce 테스트 692개, 실패 0개이며 Checkstyle과 ArchUnit 2개도 통과했습니다. test-pg는 변경하지 않아 `UP-TO-DATE`였고 기존 테스트 112개, 실패 0개 결과를 재사용했습니다. 실행 시간은 4분 30초입니다. 검사 결과와 소스 해시(`final-check.json`)

기본 프로필의 정책 비활성화, 단계별 실험값, 앞서 제외한 복수 거래 처리 범위는 유지했습니다. 중앙 설계 문서와 블로그 MD, HTML에 현재 계약을 반영했습니다. 기존 측정 표는 보존했으며 정상 결제 5분 부하는 다시 실행하지 않았습니다.
