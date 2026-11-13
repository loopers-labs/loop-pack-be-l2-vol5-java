# PR 게시용 본문

## 📌 Summary

동시 쿠폰 발급에서 업무 총량과 서버 처리 여력을 분리했습니다. 사전 DB 슬롯·쿠폰·issuedCount를 같은 TX로 확정하고,
사용자·슬롯 유일 제약으로 중복과 초과 발급을 막습니다. 서버 처리 여력은 인스턴스별 singleton Semaphore가 제한합니다.

고객 발급은 최초 201, 기존 결과는 200과 동일 couponId를 반환하며 본인 목록은 안정 정렬합니다.
포화는 429, 닫힘·종료 불명·일시 경합은 503으로 구분했습니다. 기존 사용자 헤더와 공통 응답 envelope는 유지합니다.

실행과 TX 종료를 확인한 permit만 한 번 반환합니다. 종료 불명은 permit을 보류하고 해당 인스턴스를 닫으며,
지정 실행의 DB 세션 종료와 업무 정합성을 확인한 뒤 수동 복구합니다. Redis 누적 집계는 확정 발급 이후 반영하고,
장애가 쿠폰 성공을 취소하지 않게 했습니다. DB snapshot 재집계와 UUID fence로 늦은 publish의 최신 집계 덮어쓰기를 막습니다.

## 💬 Review Points

- DB 총량·사용자 중복과 로컬 처리 한도는 다른 보장입니다. 실제 독립 JVM 두 개가 같은 DB에서 각 C=3, 합계 6개를 유지하고 반환·close가 서로 독립임을 확인했습니다.
- 슬롯→캠페인 잠금과 마지막 DB 시각 판정, afterCompletion·프록시 반환을 함께 확인하는 종료 경계, UNKNOWN 보류 및 부분 복구를 중점적으로 봐 주세요.
- Redis는 발급 여부의 원본이 아닙니다. 같은 쿠폰의 중복 반영과 미준비·0건을 구분하며, 새 반영·재집계 이후 옛 publish는 fence로 거절합니다.
- commit 뒤 프로세스 종료의 Redis 누락은 DB 재집계 대상입니다. 영속 전달·EOS·전역 C·실제 인증을 보장하지 않습니다. 운영 계정의 PROCESS 권한과 원문 quota=5/8명, 마감 뒤 지연 commit, 실제 C=3→2 재시작, 네트워크 commit 응답 유실 실험은 [미확인 항목](../verification-plan.md#미확인-항목)에 남겼습니다.

## ✅ Checklist

- [x] 도메인·실제 MySQL 제약/잠금·실제 Redis 경합·고객/관리자 HTTP 테스트 포함
- [x] HTTP 9건, 독립 두 JVM 시험 1건 및 관련 Checkstyle·아키텍처 검증 확인
- [x] 전체 요구 검사에서 실패한 두 모듈의 Docker API 환경 재실행 해결 확인
- [x] 최신 XML 총 635건 실패·오류·skip 0 확인: commerce-api 584, batch 3, test-pg 25, streamer 22, kafka 1
- [x] 운영·복구·재집계 절차와 검증 한계 문서화

635건은 **단일 clean 전체 실행이 아닙니다.** commerce-api는 17:10 KST 실행, batch·test-pg는 최초 Docker API 400 뒤
사용자 PowerShell에만 API 1.44를 설정해 17:24~17:25 재실행했습니다. streamer·kafka는 이번 검사에서 UP-TO-DATE로
재사용한 11월 4일 XML입니다. 임시 환경 변수는 finally에서 복원했고 프로덕션 설정은 변경하지 않았습니다.

## 📎 References

- [구현 현황과 실행 근거](README.md)
- [검증 매핑·미확인 항목](../verification-plan.md)
- [API·개방·복구·집계 운영](operations.md)
- [기술 글](technical-article.md)
