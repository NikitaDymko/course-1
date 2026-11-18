# 🌐 API Gateway

Единая точка входа для всех клиентов системы автоматизации продаж. Принимает запросы на порту **9000**, проверяет JWT-токен Keycloak, применяет ролевые ограничения и маршрутизирует запросы к микросервисам.

## Назначение

- **Маршрутизация** — `/api/product/**`, `/api/order/**`, `/api/inventory/**`, `/api/notification/**` проксируются в соответствующие сервисы.
- **Аутентификация** — каждый запрос проверяется как OAuth2 JWT (Resource Server), issuer = Keycloak realm `microservices-realm`.
- **Авторизация** — часть маршрутов закрыта ролями (см. таблицу ниже).
- **Отказоустойчивость** — Resilience4j Circuit Breaker + Time Limiter для product/order/inventory.
- **Агрегация Swagger** — единый Swagger UI со схемами трёх сервисов.

## Технологии

| Компонент | Версия / детали |
|---|---|
| Java | 21 |
| Spring Boot | 3.5.3 |
| Spring Cloud Gateway | WebFlux (реактивный) |
| Spring Security | OAuth2 Resource Server (JWT) |
| Resilience4j | Circuit Breaker + Time Limiter |
| springdoc-openapi | агрегация Swagger UI |
| Порт | `9000` |

## Маршруты

| Путь | Назначение | Требуемые роли |
|---|---|---|
| `/api/product/**` | Product Service | любой аутентифицированный |
| `/api/order/**` | Order Service | любой аутентифицированный (детально — в order-service) |
| `/api/inventory/**` | Inventory Service | `INVENTORY_MANAGER`, `STOREKEEPER`, `SELLER`, `MANAGER` |
| `/api/notification/**` | Notification Service | `SELLER`, `MANAGER` |
| `/swagger-ui/**`, `/swagger-api/**`, `/swagger-ui.html`, `/aggregate/**` | Документация | без авторизации |

> Роли внутри конкретного сервиса проверяются повторно его собственным `SecurityConfig` — шлюз выполняет лишь первый (грубый) уровень фильтрации.

## Swagger UI

Открыть: `http://localhost:9000/swagger-ui.html`

Доступны три спецификации:

- Product Service — `/aggregate/product-service/swagger-api`
- Order Service — `/aggregate/order-service/swagger-api`
- Inventory Service — `/aggregate/inventory-service/swagger-api`

## Отказоустойчивость

Для маршрутов product/order/inventory настроены:

- **Circuit Breaker** (`sliding-window-size=10`, `failure-rate-threshold=50`, `wait-duration-in-open-state=5s`, автоматический переход в half-open).
- **Time Limiter** — таймаут 3 секунды.

Состояние доступно через Actuator:

```bash
curl http://localhost:9000/actuator/health
curl http://localhost:9000/actuator/circuitbreakerevents
```

## Переменные окружения

| Переменная | По умолчанию | Описание |
|---|---|---|
| `PRODUCT_URL` | `http://localhost:8080` | Адрес Product Service |
| `ORDER_URL` | `http://localhost:8081` | Адрес Order Service |
| `INVENTORY_URL` | `http://localhost:8082` | Адрес Inventory Service |
| `NOTIFICATION_URL` | `http://localhost:8083` | Адрес Notification Service |
| `AUTH_SERVER_URL` | `http://localhost:8088` | Базовый URL Keycloak |
| `AUTH_SERVER_REALM` | `microservices-realm` | Имя realm |

## Запуск

```bash
./mvnw spring-boot:run
```

Сервис поднимется на `http://localhost:9000`.

## Пример запроса

```bash
TOKEN=$(curl -s -X POST \
  http://localhost:8088/realms/microservices-realm/protocol/openid-connect/token \
  -d "grant_type=password" \
  -d "client_id=admin-cli" \
  -d "username=test-user" \
  -d "password=password" | jq -r .access_token)

curl -H "Authorization: Bearer $TOKEN" http://localhost:9000/api/product
```

## Тесты

```bash
./mvnw test
```

`ApiGatewayApplicationTests` поднимает Keycloak-контейнер, выпускает токен и проверяет маршрутизацию и ролевые ограничения.
