-- Удаление устаревших плоских колонок заказа.
-- Данные перенесены в t_order_items и встроенный Customer (V5).

ALTER TABLE t_orders
    DROP COLUMN product_id,
    DROP COLUMN price,
    DROP COLUMN quantity,
    DROP COLUMN email,
    DROP COLUMN first_name,
    DROP COLUMN last_name;
