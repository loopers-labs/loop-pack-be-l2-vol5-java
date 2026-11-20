create table if not exists purchase_activity (
  id           bigint      not null auto_increment,
  created_at   datetime(6) not null,
  updated_at   datetime(6) not null,
  order_id     bigint      not null,
  product_id   bigint      not null,
  quantity     integer     not null,
  confirmed_at datetime(6) not null,
  primary key (id),
  constraint uk_activity_order_product unique (order_id, product_id),
  key idx_activity_confirmed_at (confirmed_at)
) engine=InnoDB;