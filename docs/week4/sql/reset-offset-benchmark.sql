-- 주의: 로컬 13306의 loopers_offset_bench 전용 DB 전체를 삭제한다.
-- 3306/loopers에는 사용하지 않는다. 실행 전 연결·백업을 확인한다.
-- 현재 JPA 엔티티에서 생성한 DDL이며, 기준선용으로 조회 인덱스 2개만 제외했다.
DROP DATABASE IF EXISTS loopers_offset_bench;
CREATE DATABASE loopers_offset_bench CHARACTER SET utf8mb4 COLLATE utf8mb4_general_ci;
USE loopers_offset_bench;
create table brands (created_at datetime(6) not null, deleted_at datetime(6), id bigint not null auto_increment, updated_at datetime(6) not null, name varchar(50) not null, primary key (id)) engine=InnoDB;
create table example (created_at datetime(6) not null, deleted_at datetime(6), id bigint not null auto_increment, updated_at datetime(6) not null, description varchar(255), name varchar(255), primary key (id)) engine=InnoDB;
create table order_items (item_position integer not null, quantity integer not null, line_amount bigint not null, order_id bigint not null, product_id bigint not null, unit_price bigint not null, primary key (item_position, order_id)) engine=InnoDB;
create table orders (confirmed_at datetime(6), created_at datetime(6) not null, deleted_at datetime(6), id bigint not null auto_increment, paid_amount bigint, total_amount bigint not null, updated_at datetime(6) not null, user_id bigint not null, payment_result varchar(255), status enum ('CONFIRMED','DRAFT','PAYMENT_FAILED','PAYMENT_PENDING') not null, primary key (id)) engine=InnoDB;
create table payments (amount bigint not null, created_at datetime(6) not null, deleted_at datetime(6), id bigint not null auto_increment, order_id bigint not null, pg_request_started_at datetime(6) not null, updated_at datetime(6) not null, payment_attempt_key varchar(36) not null, provider_order_id varchar(64) not null, transaction_key varchar(255), status enum ('FAILED','PENDING','SUCCESS','UNKNOWN') not null, primary key (id)) engine=InnoDB;
create table point_balances (balance bigint not null, created_at datetime(6) not null, deleted_at datetime(6), id bigint not null auto_increment, updated_at datetime(6) not null, user_id bigint not null, primary key (id)) engine=InnoDB;
create table product_like_counts (product_id bigint not null, like_count bigint not null, primary key (product_id), constraint ck_product_like_counts_nonnegative check (like_count >= 0)) engine=InnoDB;
create table product_likes (created_at datetime(6) not null, deleted_at datetime(6), id bigint not null auto_increment, product_id bigint not null, updated_at datetime(6) not null, user_id bigint not null, primary key (id)) engine=InnoDB;
create table products (stock integer not null, brand_id bigint not null, created_at datetime(6) not null, deleted_at datetime(6), id bigint not null auto_increment, price bigint not null, updated_at datetime(6) not null, name varchar(100) not null, primary key (id)) engine=InnoDB;
create table stock_reservations (quantity integer not null, created_at datetime(6) not null, deleted_at datetime(6), id bigint not null auto_increment, order_id bigint not null, product_id bigint not null, updated_at datetime(6) not null, status enum ('CONSUMED','HELD','RELEASED') not null, primary key (id)) engine=InnoDB;
create table users (id bigint not null, primary key (id)) engine=InnoDB;
alter table payments add constraint uk_payments_order unique (order_id);
alter table payments add constraint uk_payments_attempt unique (payment_attempt_key);
alter table payments add constraint uk_payments_provider_order unique (provider_order_id);
alter table payments add constraint uk_payments_transaction unique (transaction_key);
alter table point_balances add constraint UKf73gobj58s2jkt8h5diqs76lx unique (user_id);
alter table product_likes add constraint UK712hmqku6f5l2q28jql7vj0f5 unique (user_id, product_id);
alter table stock_reservations add constraint uk_reservations_order_product unique (order_id, product_id);
alter table order_items add constraint FKbioxgbv59vetrxe0ejfubep1w foreign key (order_id) references orders (id);
