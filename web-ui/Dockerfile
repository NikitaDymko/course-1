FROM nginx:1.27-alpine

# Конфигурация nginx (проксирование /api на api-gateway)
COPY nginx.conf /etc/nginx/conf.d/default.conf

# Статика
COPY html/ /usr/share/nginx/html/

# Скрипт генерации config.js из переменных окружения при старте контейнера
COPY docker-entrypoint.d/40-generate-config.sh /docker-entrypoint.d/40-generate-config.sh
RUN chmod +x /docker-entrypoint.d/40-generate-config.sh

# Значения по умолчанию (переопределяются через environment в compose)
ENV KEYCLOAK_URL=http://localhost:8088

EXPOSE 80
