#!/bin/sh
# Генерирует /usr/share/nginx/html/config.js из переменных окружения.
# Выполняется автоматически при старте контейнера (nginx image entrypoint).
set -e

CONFIG_FILE=/usr/share/nginx/html/config.js

cat > "$CONFIG_FILE" <<EOF
window.__CONFIG__ = {
    keycloakUrl: "${KEYCLOAK_URL}"
};
EOF

echo "web-ui: config.js generated with KEYCLOAK_URL=${KEYCLOAK_URL}"
