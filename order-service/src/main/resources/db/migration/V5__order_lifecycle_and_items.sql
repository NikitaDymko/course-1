-- Модель заказа:
-- встроенный Customer, статус, скидка, суммы, оплата, чек, отметки времени,
-- позиции заказа, гарантии, журнал действий и таблица исходящих сообщений.

ALTER TABLE t_orders
    ADD COLUMN customer_email VARCHAR(255),
    ADD COLUMN customer_first_name VARCHAR(255),
    ADD COLUMN customer_last_name VARCHAR(255),
    ADD COLUMN customer_phone VARCHAR(50),
    ADD COLUMN customer_consent BOOLEAN DEFAULT FALSE,
    ADD COLUMN status VARCHAR(32) NOT NULL DEFAULT 'NEW',
    ADD COLUMN discount DECIMAL(5, 2) NOT NULL DEFAULT 0,
    ADD COLUMN total_amount DECIMAL(19, 2) NOT NULL DEFAULT 0,
    ADD COLUMN final_amount DECIMAL(19, 2) NOT NULL DEFAULT 0,
    ADD COLUMN payment_method VARCHAR(16),
    ADD COLUMN receipt_number VARCHAR(64),
    ADD COLUMN paid_at DATETIME,
    ADD COLUMN issued_at DATETIME,
    ADD COLUMN created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    ADD COLUMN updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP;

-- Перенос данных из плоской модели (одна позиция в заказе).
UPDATE t_orders
SET customer_email = email,
    customer_first_name = first_name,
    customer_last_name = last_name,
    total_amount = price,
    final_amount = price;

CREATE TABLE t_order_items (
    id BIGINT NOT NULL AUTO_INCREMENT,
    order_id BIGINT NOT NULL,
    product_id VARCHAR(255) NOT NULL,
    quantity INT NOT NULL,
    price DECIMAL(19, 2) NOT NULL,
    warranty_months INT NOT NULL DEFAULT 12,
    PRIMARY KEY (id),
    CONSTRAINT fk_order_items_order FOREIGN KEY (order_id) REFERENCES t_orders (id)
);

INSERT INTO t_order_items (order_id, product_id, quantity, price, warranty_months)
SELECT id, product_id, quantity, price, 12 FROM t_orders;

CREATE TABLE t_warranties (
    id BIGINT NOT NULL AUTO_INCREMENT,
    order_id BIGINT NOT NULL,
    product_id VARCHAR(255) NOT NULL,
    warranty_months INT NOT NULL,
    starts_at DATE NOT NULL,
    ends_at DATE NOT NULL,
    PRIMARY KEY (id)
);

CREATE TABLE t_audit_log (
    id BIGINT NOT NULL AUTO_INCREMENT,
    order_id BIGINT,
    action VARCHAR(255) NOT NULL,
    performed_by VARCHAR(255) NOT NULL,
    performed_at DATETIME NOT NULL,
    details VARCHAR(1000),
    PRIMARY KEY (id)
);

CREATE TABLE t_outbox (
    id BIGINT NOT NULL AUTO_INCREMENT,
    message_id VARCHAR(64) NOT NULL,
    topic VARCHAR(255) NOT NULL,
    event_type VARCHAR(255) NOT NULL,
    payload LONGTEXT NOT NULL,
    published BOOLEAN NOT NULL DEFAULT FALSE,
    created_at DATETIME NOT NULL,
    published_at DATETIME,
    PRIMARY KEY (id),
    UNIQUE KEY uk_outbox_message_id (message_id)
);

CREATE INDEX idx_outbox_published ON t_outbox (published);
CREATE INDEX idx_orders_status ON t_orders (status);
