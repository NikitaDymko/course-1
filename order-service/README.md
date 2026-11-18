# 🧾 Order Service

Сервис заказов. Реализует полный жизненный цикл заказа, расчёт стоимости со скидкой, журнал аудита и сагу с inventory-service через Kafka (с outbox-паттерном).

## 📋 Назначение

- Оформление заказа с позициями, данными покупателя и скидкой
- Жизненный цикл: `NEW → ACCEPTED → AWAITING_PAYMENT → PAID → READY → ISSUED`
- Отклонение (`REJECTED`) и отмена (`CANCELLED`)
- Расчёт стоимости: `S = Σ pi·qi`, `C = S·(1 − d/100)`
- Скидка свыше 5 % требует подтверждения руководителя
- Журнал аудита всех действий по заказу
- Отчёты по статусам
- Сага с inventory-service (резервирование/отказ) через outbox

## ⚙️ Технологии

- Java 21, Spring Boot 3.5.3 (Spring MVC)
- Spring Data JPA + MySQL + Flyway
- Spring Security (OAuth2 JWT Resource Server)
- Confluent Kafka + Schema Registry (Avro)
- Resilience4j (Circuit Breaker)
- Outbox-паттерн (`@Scheduled` публикатор)
- springdoc-openapi (Swagger UI)

## 🔌 API

Базовый путь: `/api/order`

| Метод | Путь | Описание | Требуемая роль |
|-------|------|----------|----------------|
| `POST` | `/api/order` | Оформить заказ | любой аутентифицированный |
| `GET` | `/api/order/my` | Мои заказы | любой аутентифицированный |
| `GET` | `/api/order/my/{id}` | Мой заказ по id | любой аутентифицированный |
| `DELETE` | `/api/order/my/{id}` | Отменить свой заказ | любой аутентифицированный |
| `POST` | `/api/order/{id}/accept` | Принять заказ | `SELLER`, `MANAGER` |
| `POST` | `/api/order/{id}/reject` | Отклонить заказ | `SELLER`, `MANAGER` |
| `POST` | `/api/order/{id}/await-payment` | Перевести на оплату | `SELLER`, `MANAGER` |
| `POST` | `/api/order/{id}/pay?paymentMethod=CASH\|CARD` | Принять оплату | `CASHIER`, `MANAGER` |
| `POST` | `/api/order/{id}/ready` | Готов к выдаче | `STOREKEEPER`, `MANAGER` |
| `POST` | `/api/order/{id}/issue` | Выдать товар | `STOREKEEPER`, `MANAGER` |
| `GET` | `/api/order/report?status=` | Отчёт по статусу | `MANAGER` |
| `GET` | `/api/order/audit` | Общий журнал аудита | `MANAGER` |
| `GET` | `/api/order/{id}/audit` | Журнал по заказу | `MANAGER` |

### Создание заказа

```json
{
  "items": [
    { "productId": "6b03467ed20a43ccdd88f1d2", "quantity": 2 }
  ],
  "customer": {
    "email": "buyer@example.com",
    "firstName": "Иван",
    "lastName": "Петров",
    "phone": "+79000000000",
    "personalDataConsent": true
  },
  "discount": 3,
  "managerApproved": false
}
```

### Ответ

```json
{
  "id": 2,
  "orderNumber": "bb360f3a-6dbe-454f-a3d1-97acc23278e9",
  "status": "NEW",
  "items": [
    { "productId": "6b03467ed20a43ccdd88f1d2", "quantity": 2, "price": 4999.00, "warrantyMonths": 24, "lineTotal": 9998.00 }
  ],
  "discount": 3,
  "totalAmount": 9998.00,
  "finalAmount": 9698.06,
  "paymentMethod": null,
  "receiptNumber": null,
  "paidAt": null,
  "issuedAt": null,
  "createdAt": "2026-11-22T20:27:24",
  "customer": { "email": "buyer@example.com", "firstName": "Иван", "lastName": "Петров", "phone": "+79000000000" }
}
```

### Пример полного цикла

```bash
curl -X POST "http://localhost:9000/api/order/2/accept"        -H "Authorization: Bearer $TOKEN"
curl -X POST "http://localhost:9000/api/order/2/await-payment" -H "Authorization: Bearer $TOKEN"
curl -X POST "http://localhost:9000/api/order/2/pay?paymentMethod=CARD" -H "Authorization: Bearer $TOKEN"
curl -X POST "http://localhost:9000/api/order/2/ready"         -H "Authorization: Bearer $TOKEN"
curl -X POST "http://localhost:9000/api/order/2/issue"         -H "Authorization: Bearer $TOKEN"
```

## 📨 Kafka

| Направление | Топик | Схема |
|-------------|-------|-------|
| produce | `order-placed` | `avro/order-placed.avsc` |
| produce | `order-cancelled` | `avro/order-cancelled.avsc` |
| produce | `order-status-changed` | `avro/order-status-changed.avsc` |
| produce | `order-issued` | `avro/order-issued.avsc` |
| consume | `inventory-reserved` | `avro/inventory-reserved.avsc` |
| consume | `inventory-rejected` | `avro/inventory-rejected.avsc` |

События пишутся в таблицу `outbox_event` в той же транзакции, что и заказ, а затем публикуются планировщиком [`OutboxPublisher`](src/main/java/io/github/mitohondriyaa/order/service/OutboxPublisher.java:31).

## ⚙️ Переменные окружения

| Переменная | Описание | По умолчанию |
|------------|----------|--------------|
| `DB_HOST` | Хост MySQL | `localhost` |
| `DB_PORT` | Порт MySQL | `3306` |
| `DB_USERNAME` | Пользователь MySQL | `root` |
| `DB_PASSWORD` | Пароль MySQL | `password` |
| `PRODUCT_URL` | URL product-service | `http://localhost:8080` |
| `INVENTORY_URL` | URL inventory-service | `http://localhost:8082` |
| `AUTH_SERVER_URL` | URL Keycloak | `http://localhost:8088` |
| `AUTH_SERVER_REALM` | Realm Keycloak | `microservices-realm` |
| `KAFKA_BOOTSTRAP_SERVERS` | Брокеры Kafka | `localhost:29092,localhost:29093` |
| `SCHEMA_REGISTRY_URL` | Schema Registry | `http://localhost:8085` |

## 🚀 Запуск

```bash
./mvnw spring-boot:run
```

Порт — `8081`. Swagger UI: `http://localhost:8081/swagger-ui.html`.

## 🧪 Тесты

```bash
./mvnw test
```

Testcontainers: MySQL, Kafka, Schema Registry.
