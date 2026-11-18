-- Резервы товара под заказ (резервирование «всё или ничего»).
CREATE TABLE t_reservations (
    id BIGINT NOT NULL AUTO_INCREMENT,
    order_number VARCHAR(255) NOT NULL,
    product_id VARCHAR(255) NOT NULL,
    quantity INT NOT NULL,
    status VARCHAR(32) NOT NULL,
    created_at DATETIME NOT NULL,
    PRIMARY KEY (id)
);

CREATE INDEX idx_reservations_order_number ON t_reservations(order_number);
CREATE INDEX idx_reservations_product_id ON t_reservations(product_id);

-- Журнал выдачи товара со склада.
CREATE TABLE t_issue_journal (
    id BIGINT NOT NULL AUTO_INCREMENT,
    order_number VARCHAR(255) NOT NULL,
    product_id VARCHAR(255) NOT NULL,
    quantity INT NOT NULL,
    issued_by VARCHAR(255),
    issued_at DATETIME NOT NULL,
    PRIMARY KEY (id)
);

CREATE INDEX idx_issue_journal_order_number ON t_issue_journal(order_number);

-- Идемпотентность обработки сообщений на уровне БД.
CREATE TABLE t_processed_messages (
    message_id VARCHAR(255) NOT NULL,
    processed_at DATETIME NOT NULL,
    PRIMARY KEY (message_id)
);
