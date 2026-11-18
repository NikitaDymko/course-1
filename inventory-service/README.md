# 📦 Inventory Service

Сервис складских остатков. Резервирует товары по событиям заказов, ведёт журнал выдачи и обеспечивает идемпотентность обработки сообщений через Redis.

## 📋 Назначение

- Хранение остатков по `productId`
- Проверка наличия товара
- Изменение количества вручную
- Автоматическое создание остатка при `product-created`
- Резервирование при `order-placed` (сага) → `inventory-reserved` / `inventory-rejected`
- Возврат остатка при `order-cancelled`
- Списание при `order-issued` + запись в журнал выдачи
- Удаление остатка при `product-deleted`
- Идемпотентность через Redis (`message-id`)

## ⚙️ Технологии

- Java 21, Spring Boot 3.5.3 (Spring MVC)
- Spring Data JPA + MySQL + Flyway
- Spring Data Redis (идемпотентность)
- Spring Security (OAuth2 JWT Resource Server)
- Confluent Kafka + Schema Registry (Avro)
- springdoc-openapi (Swagger UI)

## 🔌 API

Базовый путь: `/api/inventory`

| Метод | Путь | Описание | Требуемая роль |
|-------|------|----------|----------------|
| `GET` | `/api/inventory` | Все остатки | `STOREKEEPER`, `SELLER`, `INVENTORY_MANAGER`, `MANAGER` |
| `GET` | `/api/inventory/{productId}` | Остаток по товару | `STOREKEEPER`, `SELLER`, `INVENTORY_MANAGER`, `MANAGER` |
| `GET` | `/api/inventory/check?productId=&quantity=` | Проверка наличия | `STOREKEEPER`, `SELLER`, `INVENTORY_MANAGER`, `MANAGER` |
| `PUT` | `/api/inventory` | Изменить остаток | `STOREKEEPER`, `INVENTORY_MANAGER`, `MANAGER` |

### Модель остатка

```json
{
  "id": 3,
  "productId": "6b03467ed20a43ccdd88f1d2",
  "quantity": 25
}
```

### Пример изменения остатка

```bash
curl -X PUT http://localhost:9000/api/inventory \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{ "productId": "6b03467ed20a43ccdd88f1d2", "quantity": 25 }'
```

## 📨 Kafka

| Направление | Топик | Схема |
|-------------|-------|-------|
| consume | `product-created` | `avro/product-created.avsc` |
| consume | `product-deleted` | `avro/product-deleted.avsc` |
| consume | `order-placed` | `avro/order-placed.avsc` |
| consume | `order-cancelled` | `avro/order-cancelled.avsc` |
| consume | `order-issued` | `avro/order-issued.avsc` |
| produce | `inventory-reserved` | `avro/inventory-reserved.avsc` |
| produce | `inventory-rejected` | `avro/inventory-rejected.avsc` |

## ⚙️ Переменные окружения

| Переменная | Описание | По умолчанию |
|------------|----------|--------------|
| `DB_HOST` | Хост MySQL | `localhost` |
| `DB_PORT` | Порт MySQL | `3307` |
| `DB_USERNAME` | Пользователь MySQL | `root` |
| `DB_PASSWORD` | Пароль MySQL | `password` |
| `REDIS_IDEMPOTENCY_HOST` | Хост Redis идемпотентности | `localhost` |
| `REDIS_IDEMPOTENCY_PORT` | Порт Redis идемпотентности | `6381` |
| `AUTH_SERVER_URL` | URL Keycloak | `http://localhost:8088` |
| `AUTH_SERVER_REALM` | Realm Keycloak | `microservices-realm` |
| `KAFKA_BOOTSTRAP_SERVERS` | Брокеры Kafka | `localhost:29092,localhost:29093` |
| `SCHEMA_REGISTRY_URL` | Schema Registry | `http://localhost:8085` |

## 🚀 Запуск

```bash
./mvnw spring-boot:run
```

Порт — `8082`. Swagger UI: `http://localhost:8082/swagger-ui.html`.

## 🧪 Тесты

```bash
./mvnw test
```

Testcontainers: MySQL, Redis, Kafka, Schema Registry.
