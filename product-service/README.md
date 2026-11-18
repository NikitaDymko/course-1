# 🛒 Product Service

Сервис каталога товаров. Хранит товары в MongoDB, кэширует их в Redis и публикует события об изменениях в Kafka.

## 📋 Назначение

- CRUD по товарам (SKU, наименование, описание, цена, гарантия)
- Поиск по префиксу SKU
- Выдача цены товара для order-service
- Публикация событий `product-created` и `product-deleted` для inventory-service
- Кэширование и счётчик обращений в Redis

## ⚙️ Технологии

- Java 21, Spring Boot 3.5.3 (Spring MVC)
- Spring Data MongoDB
- Spring Data Redis (кэш + счётчик)
- Spring Security (OAuth2 JWT Resource Server)
- Confluent Kafka + Schema Registry (Avro)
- springdoc-openapi (Swagger UI)

## 🔌 API

Базовый путь: `/api/product`

| Метод | Путь | Описание | Требуемая роль |
|-------|------|----------|----------------|
| `POST` | `/api/product` | Создать товар | `PRODUCT_MANAGER` |
| `GET` | `/api/product` | Список всех товаров | любой аутентифицированный |
| `GET` | `/api/product/search?prefix=` | Поиск по префиксу SKU | любой аутентифицированный |
| `GET` | `/api/product/{id}` | Товар по идентификатору | любой аутентифицированный |
| `PUT` | `/api/product/{id}` | Обновить товар | `PRODUCT_MANAGER` |
| `DELETE` | `/api/product/{id}` | Удалить товар | `PRODUCT_MANAGER` |
| `GET` | `/api/product/price/{id}` | Цена товара | любой аутентифицированный |

### Модель товара

```json
{
  "id": "6b03467ed20a43ccdd88f1d2",
  "sku": "SKU-001",
  "name": "Ноутбук",
  "description": "15 дюймов, 16 ГБ RAM",
  "price": 4999.00,
  "warrantyMonths": 24
}
```

Ограничения валидации: `sku`, `name`, `description` — не пустые; `price >= 1.0`; `warrantyMonths >= 0`.

### Пример создания

```bash
curl -X POST http://localhost:9000/api/product \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "sku": "SKU-001",
    "name": "Ноутбук",
    "description": "15 дюймов, 16 ГБ RAM",
    "price": 4999.00,
    "warrantyMonths": 24
  }'
```

## 📨 Kafka

| Направление | Топик | Схема |
|-------------|-------|-------|
| produce | `product-created` | `avro/product-created.avsc` |
| produce | `product-deleted` | `avro/product-deleted.avsc` |

## ⚙️ Переменные окружения

| Переменная | Описание | По умолчанию |
|------------|----------|--------------|
| `DB_HOST` | Хост MongoDB | `localhost` |
| `DB_PORT` | Порт MongoDB | `27017` |
| `DB_USERNAME` | Пользователь MongoDB | `root` |
| `DB_PASSWORD` | Пароль MongoDB | `password` |
| `REDIS_CACHE_HOST` | Хост Redis-кэша | `localhost` |
| `REDIS_CACHE_PORT` | Порт Redis-кэша | `6379` |
| `REDIS_COUNTER_HOST` | Хост Redis-счётчика | `localhost` |
| `REDIS_COUNTER_PORT` | Порт Redis-счётчика | `6380` |
| `AUTH_SERVER_URL` | URL Keycloak | `http://localhost:8088` |
| `AUTH_SERVER_REALM` | Realm Keycloak | `microservices-realm` |
| `KAFKA_BOOTSTRAP_SERVERS` | Брокеры Kafka | `localhost:29092,localhost:29093` |
| `SCHEMA_REGISTRY_URL` | Schema Registry | `http://localhost:8085` |

## 🚀 Запуск

```bash
./mvnw spring-boot:run
```

Порт по умолчанию — `8080`. Swagger UI: `http://localhost:8080/swagger-ui.html`.

## 🧪 Тесты

```bash
./mvnw test
```

Используются Testcontainers (MongoDB, Redis, Kafka, Schema Registry).
