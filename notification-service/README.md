# 📬 Notification Service

Сервис уведомлений. Слушает события заказов и остатков, отправляет письма, ведёт журнал уведомлений и список задач на обзвон клиентов.

## 📋 Назначение

- Отправка email по событиям (`inventory-reserved`, `inventory-rejected`, `order-status-changed`, `order-issued`)
- Журнал отправленных уведомлений (Redis)
- Задачи на обзвон клиентов при отказе в резерве
- Идемпотентность обработки сообщений через Redis
- REST API для просмотра журнала и задач

## ⚙️ Технологии

- Java 21, Spring Boot 3.5.3 (Spring MVC)
- Spring Data Redis (журнал, задачи, идемпотентность)
- Spring Mail (SMTP)
- Spring Security (OAuth2 JWT Resource Server)
- Confluent Kafka + Schema Registry (Avro)
- springdoc-openapi (Swagger UI)

## 🔌 API

Базовый путь: `/api/notification`

| Метод | Путь | Описание | Требуемая роль |
|-------|------|----------|----------------|
| `GET` | `/api/notification/journal` | Журнал уведомлений | `SELLER`, `MANAGER` |
| `GET` | `/api/notification/journal/{orderNumber}` | Журнал по номеру заказа | `SELLER`, `MANAGER` |
| `GET` | `/api/notification/call-tasks` | Задачи на обзвон | `SELLER`, `MANAGER` |

### Запись журнала

```json
{
  "orderNumber": "bb360f3a-6dbe-454f-a3d1-97acc23278e9",
  "type": "ORDER_STATUS_CHANGED",
  "recipient": "buyer@example.com",
  "subject": "Статус заказа изменён",
  "sentAt": "2026-11-22T20:27:24"
}
```

### Задача на обзвон

```json
{
  "orderNumber": "bb360f3a-6dbe-454f-a3d1-97acc23278e9",
  "phone": "+79000000000",
  "firstName": "Иван",
  "lastName": "Петров",
  "reason": "Нет товара на складе",
  "createdAt": "2026-11-22T20:27:24",
  "done": false
}
```

## 📨 Kafka

| Направление | Топик | Схема |
|-------------|-------|-------|
| consume | `inventory-reserved` | `avro/inventory-reserved.avsc` |
| consume | `inventory-rejected` | `avro/inventory-rejected.avsc` |
| consume | `order-status-changed` | `avro/order-status-changed.avsc` |
| consume | `order-issued` | `avro/order-issued.avsc` |

Ошибки обработки логируются через [`DefaultErrorHandler`](src/main/java/io/github/mitohondriyaa/notification/config/KafkaConfig.java:18).

## ⚙️ Переменные окружения

| Переменная | Описание | По умолчанию |
|------------|----------|--------------|
| `REDIS_IDEMPOTENCY_HOST` | Хост Redis идемпотентности | `localhost` |
| `REDIS_IDEMPOTENCY_PORT` | Порт Redis идемпотентности | `6381` |
| `MAIL_HOST` | SMTP-хост | `sandbox.smtp.mailtrap.io` |
| `MAIL_PORT` | SMTP-порт | `2525` |
| `MAIL_USERNAME` | SMTP-пользователь | — |
| `MAIL_PASSWORD` | SMTP-пароль | — |
| `AUTH_SERVER_URL` | URL Keycloak | `http://localhost:8088` |
| `AUTH_SERVER_REALM` | Realm Keycloak | `microservices-realm` |
| `KAFKA_BOOTSTRAP_SERVERS` | Брокеры Kafka | `localhost:29092,localhost:29093` |
| `SCHEMA_REGISTRY_URL` | Schema Registry | `http://localhost:8085` |

## 🚀 Запуск

```bash
./mvnw spring-boot:run
```

Порт — `8083`. Swagger UI: `http://localhost:8083/swagger-ui.html`.

## 🧪 Тесты

```bash
./mvnw test
```

Testcontainers: Redis, Kafka, Schema Registry.
