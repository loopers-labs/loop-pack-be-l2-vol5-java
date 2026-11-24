-- W10 준비 데이터 (plan.md D-3). 빈 DB에 사용자 1 · 브랜드 1 · 상품 1(재고 100)을 넣는다.
-- 회원가입 API는 없고(X-USER-ID 식별만), 관리자 API는 ADMIN 역할 인증이 있어 진짜 프로세스로는 부를 수 없다 → SQL로 넣는다.
-- 결제·주문 상태는 여기서 쓰지 않는다 (과제: commerce DB에 UNKNOWN·CONFIRMED를 직접 쓰지 않는다).
INSERT INTO users (id, name, created_at, updated_at) VALUES (1, 'w10-user', NOW(6), NOW(6));
INSERT INTO brands (id, name, description, created_at, updated_at) VALUES (1, 'W10 브랜드', '사고 실험용', NOW(6), NOW(6));
INSERT INTO products (id, brand_id, name, price, stock, created_at, updated_at) VALUES (1, 1, 'W10 상품', 10000, 100, NOW(6), NOW(6));
