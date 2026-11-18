# 🖥️ Web UI

Веб-интерфейс системы автоматизации продаж. Реализует **все возможности API** через единый экран: каталог, товары, остатки, заказы, «мои заказы», уведомления, отчёты и журнал аудита.

## Назначение

- Вход через Keycloak по **Authorization Code + PKCE** (без хранения client secret).
- Отображение ролей пользователя в шапке (технические роли Keycloak скрыты).
- Полный цикл работы продавца, кассира, кладовщика и руководителя.
- Все запросы идут через API Gateway (`/api` → `http://api-gateway:9000`).

## Технологии

| Компонент | Детали |
|---|---|
| Веб-сервер | nginx 1.27-alpine |
| Порт | `3000` (наружу) → `80` (в контейнере) |
| Фронтенд | Vanilla JS + HTML + CSS (без фреймворков) |
| Аутентификация | OIDC Authorization Code + PKCE |
| Проксирование | `/api`, `/swagger-ui`, `/aggregate` → API Gateway |

## Структура

```
web-ui/
├── nginx.conf          # конфигурация nginx и проксирование на шлюз
├── Dockerfile
└── html/
    ├── index.html      # разметка (вкладки и панели)
    ├── styles.css      # стили
    └── app.js          # логика: OIDC, HTTP-клиент, загрузка данных
```

## Возможности интерфейса

| Вкладка | Действия | Эндпоинты |
|---|---|---|
| **Каталог** | Список товаров, поиск по префиксу, карточка товара | `GET /api/product`, `GET /api/product/search?prefix=`, `GET /api/product/{id}` |
| **Товары** | Создание, редактирование, удаление | `POST /api/product`, `PUT /api/product/{id}`, `DELETE /api/product/{id}` |
| **Остатки** | Просмотр и изменение количества | `GET /api/inventory`, `PUT /api/inventory` |
| **Заказы** | Создание заказа с позициями и скидкой; приём, отклонение, ожидание оплаты, оплата (наличные/карта), готовность, выдача; журнал по заказу | `POST /api/order`, `POST /api/order/{id}/accept`, `/reject`, `/await-payment`, `/pay?paymentMethod=`, `/ready`, `/issue`, `GET /api/order/{id}/audit` |
| **Мои заказы** | Список своих заказов, детали, отмена | `GET /api/order/my`, `GET /api/order/my/{id}`, `DELETE /api/order/my/{id}` |
| **Уведомления** | Журнал уведомлений, задачи на обзвон, фильтр по номеру заказа | `GET /api/notification/journal`, `GET /api/notification/journal/{orderNumber}`, `GET /api/notification/call-tasks` |
| **Отчёты** | Отчёт по статусу или по всем статусам с итоговой суммой | `GET /api/order/report?status=` |
| **Аудит** | Журнал аудита, фильтр по ID заказа | `GET /api/order/audit`, `GET /api/order/{id}/audit` |

## Аутентификация

1. Пользователь нажимает «Войти».
2. `app.js` генерирует `code_verifier` и `code_challenge` (S256), сохраняет `state` в `sessionStorage`.
3. Редирект на Keycloak: `http://172.17.0.1:8088/realms/microservices-realm/protocol/openid-connect/auth`.
4. После входа Keycloak возвращает `code`, который обменивается на токены через `/token` (PKCE, без secret).
5. Access-токен хранится в `sessionStorage` и добавляется в заголовок `Authorization: Bearer ...` каждого запроса.
6. Роли читаются из `realm_access.roles` JWT; технические (`offline_access`, `uma_authorization`, `default-roles-*`) скрываются.

## Переменные окружения

| Переменная | По умолчанию | Описание |
|---|---|---|
| `KEYCLOAK_URL` | `http://172.17.0.1:8088` | Базовый URL Keycloak (должен совпадать с issuer токена) |

> Значение `KEYCLOAK_URL` подставляется в `app.js` при старте контейнера. Оно обязано совпадать с `--hostname` Keycloak, иначе браузер получит токен с другим issuer и API вернёт 401.

## Запуск

В составе общего compose-файла:

```bash
docker compose up -d web-ui
```

Открыть: `http://localhost:3000`

Тестовый пользователь: `test-user` / `password` (все роли: SELLER, CASHIER, STOREKEEPER, MANAGER, PRODUCT_MANAGER, INVENTORY_MANAGER).

## Проксирование (nginx.conf)

```nginx
location /api/ {
    proxy_pass http://api-gateway:9000;
    proxy_set_header Host $host;
    proxy_set_header X-Real-IP $remote_addr;
    proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
    proxy_set_header X-Forwarded-Proto $scheme;
}
```

Статика отдаётся из `/usr/share/nginx/html` с fallback на `index.html` (SPA-поведение).
