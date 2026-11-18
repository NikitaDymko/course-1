/* =========================================================================
   Конфигурация
   ========================================================================= */
// Адрес Keycloak берётся из window.__CONFIG__ (его подставляет config.js,
// генерируемый из переменных окружения при старте контейнера).
// Fallback — локальная разработка.
const KEYCLOAK_URL = (window.__CONFIG__ && window.__CONFIG__.keycloakUrl) || "http://localhost:8088";
const REALM = "microservices-realm";
const CLIENT_ID = "web-ui";
const REDIRECT_URI = window.location.origin + "/";
const API = "/api";

const AUTH_ENDPOINT = KEYCLOAK_URL + "/realms/" + REALM + "/protocol/openid-connect/auth";
const TOKEN_ENDPOINT = KEYCLOAK_URL + "/realms/" + REALM + "/protocol/openid-connect/token";
const LOGOUT_ENDPOINT = KEYCLOAK_URL + "/realms/" + REALM + "/protocol/openid-connect/logout";

const STORAGE = {
    verifier: "pkce_verifier",
    state: "pkce_state",
    access: "access_token",
    refresh: "refresh_token",
    idToken: "id_token"
};

/* =========================================================================
   Справочник товаров
   -------------------------------------------------------------------------
   inventory-service и order-service хранят только productId (технический
   ключ товара из product-service). Чтобы человеку было понятно, о каком
   товаре речь, названия подтягиваются из product-service и кэшируются здесь.
   ========================================================================= */
let productsCache = [];

async function fetchProducts(force) {
    if (!force && productsCache.length > 0) {
        return productsCache;
    }
    productsCache = await apiGet("/product");
    return productsCache;
}

function productLabel(product) {
    return product.name + " (" + product.sku + ")";
}

function productLabelById(productId) {
    const product = productsCache.find(p => p.id === productId);
    return product ? productLabel(product) : null;
}

// Человекочитаемое представление productId: название + артикул,
// а сам идентификатор остаётся в подсказке (title).
function renderProductCell(productId) {
    const label = productLabelById(productId);
    if (!label) {
        return '<span class="muted" title="' + escapeHtml(productId) + '">' +
            escapeHtml(productId) + '</span>';
    }
    return '<span title="' + escapeHtml(productId) + '">' + escapeHtml(label) + '</span>';
}

// Заполняет <select> вариантами товаров. selectedId — предвыбранный товар.
function fillProductSelect(select, selectedId) {
    const options = ['<option value="">— выберите товар —</option>'];
    productsCache.forEach(product => {
        const selected = product.id === selectedId ? " selected" : "";
        options.push('<option value="' + escapeHtml(product.id) + '"' + selected + '>' +
            escapeHtml(productLabel(product)) + '</option>');
    });
    select.innerHTML = options.join("");
}

/* =========================================================================
   PKCE (Authorization Code + PKCE, RFC 7636)
   ========================================================================= */
function base64UrlEncode(bytes) {
    let str = "";
    bytes.forEach(b => str += String.fromCharCode(b));
    return btoa(str).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

function randomString(length) {
    const bytes = new Uint8Array(length);
    crypto.getRandomValues(bytes);
    return base64UrlEncode(bytes);
}

async function sha256(text) {
    const data = new TextEncoder().encode(text);
    const digest = await crypto.subtle.digest("SHA-256", data);
    return base64UrlEncode(new Uint8Array(digest));
}

async function login() {
    const verifier = randomString(64);
    const challenge = await sha256(verifier);
    const state = randomString(16);

    sessionStorage.setItem(STORAGE.verifier, verifier);
    sessionStorage.setItem(STORAGE.state, state);

    const params = new URLSearchParams({
        client_id: CLIENT_ID,
        redirect_uri: REDIRECT_URI,
        response_type: "code",
        scope: "openid profile email",
        state: state,
        code_challenge: challenge,
        code_challenge_method: "S256"
    });
    window.location.href = AUTH_ENDPOINT + "?" + params.toString();
}

async function handleRedirect() {
    const params = new URLSearchParams(window.location.search);
    const code = params.get("code");
    if (!code) return false;

    const state = params.get("state");
    const savedState = sessionStorage.getItem(STORAGE.state);
    if (!state || state !== savedState) {
        showLoginError("Некорректный параметр state. Повторите вход.");
        return false;
    }

    const verifier = sessionStorage.getItem(STORAGE.verifier);
    const body = new URLSearchParams({
        grant_type: "authorization_code",
        client_id: CLIENT_ID,
        code: code,
        redirect_uri: REDIRECT_URI,
        code_verifier: verifier
    });

    try {
        const response = await fetch(TOKEN_ENDPOINT, {
            method: "POST",
            headers: { "Content-Type": "application/x-www-form-urlencoded" },
            body: body.toString()
        });
        if (!response.ok) {
            throw new Error("Не удалось обменять код авторизации на токен");
        }
        const tokens = await response.json();
        storeTokens(tokens);
        sessionStorage.removeItem(STORAGE.verifier);
        sessionStorage.removeItem(STORAGE.state);
        // Убираем code/state из адресной строки
        window.history.replaceState({}, document.title, "/");
        return true;
    } catch (e) {
        showLoginError(e.message);
        return false;
    }
}

function storeTokens(tokens) {
    localStorage.setItem(STORAGE.access, tokens.access_token);
    if (tokens.refresh_token) localStorage.setItem(STORAGE.refresh, tokens.refresh_token);
    if (tokens.id_token) localStorage.setItem(STORAGE.idToken, tokens.id_token);
}

function logout() {
    const idToken = localStorage.getItem(STORAGE.idToken);
    Object.values(STORAGE).forEach(k => localStorage.removeItem(k));
    const params = new URLSearchParams({
        client_id: CLIENT_ID,
        post_logout_redirect_uri: REDIRECT_URI
    });
    if (idToken) params.set("id_token_hint", idToken);
    window.location.href = LOGOUT_ENDPOINT + "?" + params.toString();
}

function getToken() {
    return localStorage.getItem(STORAGE.access);
}

function parseJwt(token) {
    try {
        const payload = token.split(".")[1];
        const json = atob(payload.replace(/-/g, "+").replace(/_/g, "/"));
        return JSON.parse(decodeURIComponent(escape(json)));
    } catch (e) {
        return {};
    }
}

/* =========================================================================
   HTTP-клиент
   ========================================================================= */
// Достаёт человекочитаемое сообщение из тела ошибки.
// Бэкенд отвечает либо {"message": "..."}, либо {"messages": {"поле": "..."}}.
async function readErrorMessage(response, path) {
    let fallback = "Ошибка запроса " + path + ": " + response.status;
    try {
        const data = await response.json();
        if (data && data.message) {
            return data.message;
        }
        if (data && data.messages) {
            const parts = Object.entries(data.messages)
                .map(([field, text]) => field + ": " + text);
            if (parts.length > 0) {
                return "Проверьте поля — " + parts.join("; ");
            }
        }
    } catch (e) { /* тело не JSON */ }
    return fallback;
}

async function apiGet(path) {
    const response = await fetch(API + path, {
        headers: { "Authorization": "Bearer " + getToken() }
    });
    if (response.status === 401) {
        logout();
        throw new Error("Сессия истекла, выполните вход повторно");
    }
    if (response.status === 403) {
        throw new Error("Недостаточно прав для выполнения операции (требуется роль MANAGER)");
    }
    if (!response.ok) {
        throw new Error(await readErrorMessage(response, path));
    }
    return response.json();
}

async function apiPost(path, body) {
    const response = await fetch(API + path, {
        method: "POST",
        headers: {
            "Authorization": "Bearer " + getToken(),
            "Content-Type": "application/json"
        },
        body: body ? JSON.stringify(body) : undefined
    });
    if (response.status === 401) {
        logout();
        throw new Error("Сессия истекла, выполните вход повторно");
    }
    if (response.status === 403) {
        throw new Error("Недостаточно прав для выполнения операции");
    }
    if (!response.ok) {
        throw new Error(await readErrorMessage(response, path));
    }
    const text = await response.text();
    return text ? JSON.parse(text) : null;
}

async function apiPut(path, body) {
    const response = await fetch(API + path, {
        method: "PUT",
        headers: {
            "Authorization": "Bearer " + getToken(),
            "Content-Type": "application/json"
        },
        body: body ? JSON.stringify(body) : undefined
    });
    if (response.status === 401) {
        logout();
        throw new Error("Сессия истекла, выполните вход повторно");
    }
    if (response.status === 403) {
        throw new Error("Недостаточно прав для выполнения операции (требуется роль PRODUCT_MANAGER)");
    }
    if (!response.ok) {
        throw new Error(await readErrorMessage(response, path));
    }
    const text = await response.text();
    return text ? JSON.parse(text) : null;
}

async function apiDelete(path) {
    const response = await fetch(API + path, {
        method: "DELETE",
        headers: { "Authorization": "Bearer " + getToken() }
    });
    if (response.status === 401) {
        logout();
        throw new Error("Сессия истекла, выполните вход повторно");
    }
    if (response.status === 403) {
        throw new Error("Недостаточно прав для выполнения операции (требуется роль PRODUCT_MANAGER)");
    }
    if (!response.ok) {
        throw new Error(await readErrorMessage(response, path));
    }
    return null;
}

/* =========================================================================
   Утилиты отображения
   ========================================================================= */
function escapeHtml(value) {
    if (value === null || value === undefined) return "";
    const amp = String.fromCharCode(38);
    const lt = String.fromCharCode(60);
    const gt = String.fromCharCode(62);
    const quot = String.fromCharCode(34);
    return String(value)
        .replace(new RegExp(amp, "g"), amp + "amp;")
        .replace(new RegExp(lt, "g"), amp + "lt;")
        .replace(new RegExp(gt, "g"), amp + "gt;")
        .replace(new RegExp(quot, "g"), amp + "quot;");
}

function money(value) {
    if (value === null || value === undefined) return "—";
    return Number(value).toFixed(2) + " ₽";
}

function statusBadge(status) {
    return '<span class="status-badge status-' + escapeHtml(status) + '">' + escapeHtml(status) + '</span>';
}

function renderTable(container, columns, rows, emptyText) {
    if (!rows || rows.length === 0) {
        container.innerHTML = '<div class="empty">' + escapeHtml(emptyText || "Нет данных") + '</div>';
        return;
    }
    let html = "<table><thead><tr>";
    columns.forEach(c => html += "<th>" + escapeHtml(c.title) + "</th>");
    html += "</tr></thead><tbody>";
    rows.forEach(row => {
        html += "<tr>";
        columns.forEach(c => {
            const value = c.render ? c.render(row) : escapeHtml(row[c.key]);
            html += "<td>" + value + "</td>";
        });
        html += "</tr>";
    });
    html += "</tbody></table>";
    container.innerHTML = html;
}

function showGlobalError(message) {
    const box = document.getElementById("global-error");
    box.textContent = message;
    box.classList.remove("hidden");
    setTimeout(() => box.classList.add("hidden"), 6000);
}

function showLoginError(message) {
    const box = document.getElementById("login-error");
    box.textContent = message;
    box.classList.remove("hidden");
}

/* =========================================================================
   Вкладки
   ========================================================================= */
const loaders = {
    catalog: loadCatalog,
    products: loadProducts,
    inventory: loadInventory,
    orders: loadOrders,
    "my-orders": loadMyOrders,
    notifications: loadNotifications,
    report: loadReport,
    audit: loadAudit
};

function activateTab(name) {
    document.querySelectorAll("#tabs button").forEach(b => {
        b.classList.toggle("active", b.dataset.tab === name);
    });
    document.querySelectorAll(".panel").forEach(p => {
        p.classList.toggle("active", p.id === "panel-" + name);
    });
    const loader = loaders[name];
    if (loader) {
        loader().catch(e => showGlobalError(e.message));
    }
}

/* =========================================================================
   Загрузчики данных
   ========================================================================= */
async function loadCatalog() {
    const container = document.getElementById("catalog-content");
    const products = await apiGet("/product");
    renderTable(container, [
        { title: "SKU", key: "sku" },
        { title: "Наименование", key: "name" },
        { title: "Описание", key: "description" },
        { title: "Цена", render: p => money(p.price) },
        { title: "Гарантия, мес.", key: "warrantyMonths" },
        { title: "", render: p => catalogActions(p) }
    ], products, "Каталог пуст");
}

function catalogActions(product) {
    return '<span class="actions">' +
        '<button data-catalog-action="view" data-id="' + escapeHtml(product.id) + '">Карточка</button>' +
        '</span>';
}

function renderDetails(container, rows) {
    container.innerHTML = rows.map(r =>
        '<div class="detail-row"><span class="detail-label">' + escapeHtml(r.label) +
        '</span><span class="detail-value">' + r.value + '</span></div>'
    ).join("");
}

async function openProductCard(id) {
    try {
        const product = await apiGet("/product/" + id);
        document.getElementById("product-card").classList.remove("hidden");
        const rows = [
            { label: "ID", value: escapeHtml(product.id) },
            { label: "SKU", value: escapeHtml(product.sku) },
            { label: "Наименование", value: escapeHtml(product.name) },
            { label: "Описание", value: escapeHtml(product.description) },
            { label: "Цена", value: money(product.price) },
            { label: "Гарантия, мес.", value: escapeHtml(product.warrantyMonths) }
        ];
        Object.entries(product.attributes || {}).forEach(([key, value]) => {
            rows.push({ label: key, value: escapeHtml(value) });
        });
        renderDetails(document.getElementById("product-card-content"), rows);
    } catch (e) {
        showGlobalError(e.message);
    }
}

function closeProductCard() {
    document.getElementById("product-card").classList.add("hidden");
}

async function loadProducts() {
    const container = document.getElementById("products-content");
    const products = await apiGet("/product");
    renderTable(container, [
        { title: "SKU", key: "sku" },
        { title: "Наименование", key: "name" },
        { title: "Цена", render: p => money(p.price) },
        { title: "Гарантия, мес.", key: "warrantyMonths" },
        { title: "Действия", render: p => productActions(p) }
    ], products, "Товары отсутствуют");
}

function productActions(product) {
    const id = escapeHtml(product.id);
    return '<span class="actions">' +
        '<button data-product-action="edit" data-id="' + id + '">Изменить</button>' +
        '<button class="danger" data-product-action="delete" data-id="' + id + '">Удалить</button>' +
        '</span>';
}

function addAttributeRow(key, value) {
    const container = document.getElementById("product-attributes");
    const row = document.createElement("div");
    row.className = "attribute-row";
    row.innerHTML =
        '<input class="attribute-key" placeholder="Название (например, Объём памяти)">' +
        '<input class="attribute-value" placeholder="Значение (например, 16 ГБ)">' +
        '<button type="button" class="danger attribute-remove-btn">×</button>';
    row.querySelector(".attribute-key").value = key || "";
    row.querySelector(".attribute-value").value = value || "";
    row.querySelector(".attribute-remove-btn").addEventListener("click", () => row.remove());
    container.appendChild(row);
}

function collectAttributes() {
    const attributes = {};
    document.querySelectorAll("#product-attributes .attribute-row").forEach(row => {
        const key = row.querySelector(".attribute-key").value.trim();
        const value = row.querySelector(".attribute-value").value.trim();
        if (key) {
            attributes[key] = value;
        }
    });
    return attributes;
}

function openProductForm(product) {
    document.getElementById("product-form").classList.remove("hidden");
    document.getElementById("product-form-title").textContent = product ? "Редактирование товара" : "Новый товар";
    document.getElementById("product-id").value = product ? product.id : "";
    document.getElementById("product-sku").value = product ? product.sku : "";
    document.getElementById("product-name").value = product ? product.name : "";
    document.getElementById("product-description").value = product ? product.description : "";
    document.getElementById("product-price").value = product ? product.price : "";
    document.getElementById("product-warranty").value = product ? product.warrantyMonths : "";

    document.getElementById("product-attributes").innerHTML = "";
    const attributes = (product && product.attributes) || {};
    Object.entries(attributes).forEach(([key, value]) => addAttributeRow(key, value));
}

function closeProductForm() {
    document.getElementById("product-form").classList.add("hidden");
}

async function saveProduct() {
    const id = document.getElementById("product-id").value;
    const sku = document.getElementById("product-sku").value.trim();
    const name = document.getElementById("product-name").value.trim();
    const description = document.getElementById("product-description").value.trim();
    const priceRaw = document.getElementById("product-price").value.trim();
    const warrantyRaw = document.getElementById("product-warranty").value.trim();

    // Проверяем на клиенте, чтобы вместо «Ошибка запроса /product: 400»
    // показать понятное сообщение о конкретном незаполненном поле.
    const problems = [];
    if (!sku) problems.push("артикул (SKU)");
    if (!name) problems.push("наименование");
    if (!description) problems.push("описание");
    if (!priceRaw) {
        problems.push("цена");
    } else if (!(Number(priceRaw) >= 1)) {
        problems.push("цена (не меньше 1)");
    }
    if (!warrantyRaw) {
        problems.push("гарантия, мес.");
    } else if (!(Number(warrantyRaw) >= 0)) {
        problems.push("гарантия, мес. (не может быть отрицательной)");
    }
    if (problems.length > 0) {
        showGlobalError("Заполните поля: " + problems.join(", "));
        return;
    }

    const body = {
        sku: sku,
        name: name,
        description: description,
        price: Number(priceRaw),
        warrantyMonths: Number(warrantyRaw),
        attributes: collectAttributes()
    };
    try {
        if (id) {
            await apiPut("/product/" + id, body);
        } else {
            await apiPost("/product", body);
        }
        closeProductForm();
        await fetchProducts(true);
        await loadProducts();
    } catch (e) {
        showGlobalError(e.message);
    }
}

async function deleteProduct(id) {
    if (!confirm("Удалить товар?")) return;
    try {
        await apiDelete("/product/" + id);
        await fetchProducts(true);
        await loadProducts();
    } catch (e) {
        showGlobalError(e.message);
    }
}

async function loadInventory() {
    const container = document.getElementById("inventory-content");
    const [items] = await Promise.all([apiGet("/inventory"), fetchProducts()]);
    renderTable(container, [
        { title: "ID", key: "id" },
        { title: "Товар", render: i => renderProductCell(i.productId) },
        { title: "Количество", key: "quantity" },
        { title: "Действия", render: i => inventoryActions(i) }
    ], items, "Остатки отсутствуют");
}

function inventoryActions(item) {
    return '<span class="actions">' +
        '<button data-inventory-action="edit" data-product-id="' + escapeHtml(item.productId) +
        '" data-quantity="' + escapeHtml(item.quantity) + '">Изменить</button>' +
        '</span>';
}

async function openInventoryForm(productId, quantity) {
    await fetchProducts();
    document.getElementById("inventory-form").classList.remove("hidden");
    document.getElementById("inventory-product-id").value = productId;
    fillProductSelect(document.getElementById("inventory-product-select"), productId);
    document.getElementById("inventory-quantity").value = quantity;
}

function closeInventoryForm() {
    document.getElementById("inventory-form").classList.add("hidden");
}

async function saveInventory() {
    const productId = document.getElementById("inventory-product-select").value;
    const quantity = Number(document.getElementById("inventory-quantity").value);
    if (!productId) {
        showGlobalError("Не выбран товар");
        return;
    }
    if (!Number.isInteger(quantity) || quantity < 0) {
        showGlobalError("Количество должно быть целым неотрицательным числом");
        return;
    }
    try {
        await apiPut("/inventory", { productId: productId, quantity: quantity });
        closeInventoryForm();
        await loadInventory();
    } catch (e) {
        showGlobalError(e.message);
    }
}

async function loadOrders() {
    const container = document.getElementById("orders-content");
    const status = document.getElementById("orders-status").value;
    const path = status ? "/order/report?status=" + encodeURIComponent(status) : "/order/report";
    const orders = await apiGet(path);
    renderTable(container, [
        { title: "Номер", key: "orderNumber" },
        { title: "Статус", render: o => statusBadge(o.status) },
        { title: "Сумма", render: o => money(o.totalAmount) },
        { title: "К оплате", render: o => money(o.finalAmount) },
        { title: "Оплата", key: "paymentMethod" },
        { title: "Чек", key: "receiptNumber" },
        { title: "Действия", render: o => orderActions(o) }
    ], orders, "Заказы отсутствуют");
}

function orderActions(order) {
    const id = order.id;
    const buttons = [];
    if (order.status === "NEW") {
        buttons.push('<button data-action="accept" data-id="' + id + '">Принять</button>');
        buttons.push('<button class="danger" data-action="reject" data-id="' + id + '">Отклонить</button>');
    }
    if (order.status === "ACCEPTED") {
        buttons.push('<button data-action="await-payment" data-id="' + id + '">К оплате</button>');
    }
    if (order.status === "AWAITING_PAYMENT") {
        buttons.push('<button class="primary" data-action="pay-cash" data-id="' + id + '">Наличными</button>');
        buttons.push('<button class="primary" data-action="pay-card" data-id="' + id + '">Картой</button>');
    }
    if (order.status === "PAID") {
        buttons.push('<button data-action="ready" data-id="' + id + '">Готов к выдаче</button>');
    }
    if (order.status === "READY") {
        buttons.push('<button class="primary" data-action="issue" data-id="' + id + '">Выдать</button>');
    }
    // Журнал открывается по номеру заказа — именно его принимает фильтр на вкладке «Журнал действий».
    buttons.push('<button data-action="audit" data-id="' + id + '" data-order-number="' +
        escapeHtml(order.orderNumber) + '">Журнал</button>');
    return '<span class="actions">' + buttons.join("") + '</span>';
}

async function performOrderAction(action, id, orderNumber) {
    try {
        if (action === "pay-cash" || action === "pay-card") {
            const method = action === "pay-cash" ? "CASH" : "CARD";
            await apiPost("/order/" + id + "/pay?paymentMethod=" + method);
        } else if (action === "audit") {
            await showOrderAudit(orderNumber);
            return;
        } else {
            await apiPost("/order/" + id + "/" + action);
        }
        await loadOrders();
    } catch (e) {
        showGlobalError(e.message);
    }
}

// Открывает журнал по конкретному заказу: подставляет номер в фильтр
// и переходит на вкладку «Журнал действий».
async function showOrderAudit(orderNumber) {
    document.getElementById("audit-order-id").value = orderNumber;
    await loadAudit();
    activateTab("audit");
}

/* ---------- Оформление заказа ---------- */
async function openOrderForm() {
    await fetchProducts();
    document.getElementById("order-form").classList.remove("hidden");
    document.getElementById("order-email").value = "";
    document.getElementById("order-first-name").value = "";
    document.getElementById("order-last-name").value = "";
    document.getElementById("order-phone").value = "";
    document.getElementById("order-discount").value = "0";
    document.getElementById("order-consent").checked = false;
    document.getElementById("order-items").innerHTML = "";
    addOrderItemRow();
}

function closeOrderForm() {
    document.getElementById("order-form").classList.add("hidden");
}

function addOrderItemRow() {
    const container = document.getElementById("order-items");
    const row = document.createElement("div");
    row.className = "order-item-row";
    row.innerHTML =
        '<select class="order-item-product"></select>' +
        '<input class="order-item-quantity" type="number" min="1" value="1" placeholder="Количество">' +
        '<button class="danger order-item-remove">Удалить</button>';
    fillProductSelect(row.querySelector(".order-item-product"), "");
    row.querySelector(".order-item-remove").addEventListener("click", () => row.remove());
    container.appendChild(row);
}

async function saveOrder() {
    const items = [];
    document.querySelectorAll("#order-items .order-item-row").forEach(row => {
        const productId = row.querySelector(".order-item-product").value.trim();
        const quantity = Number(row.querySelector(".order-item-quantity").value);
        if (productId) {
            items.push({ productId: productId, quantity: quantity });
        }
    });
    if (items.length === 0) {
        showGlobalError("Добавьте хотя бы одну позицию заказа");
        return;
    }
    const body = {
        items: items,
        customer: {
            email: document.getElementById("order-email").value.trim(),
            firstName: document.getElementById("order-first-name").value.trim(),
            lastName: document.getElementById("order-last-name").value.trim(),
            phone: document.getElementById("order-phone").value.trim(),
            personalDataConsent: document.getElementById("order-consent").checked
        },
        discount: Number(document.getElementById("order-discount").value || 0),
        managerApproved: false
    };
    try {
        await apiPost("/order", body);
        closeOrderForm();
        await loadOrders();
    } catch (e) {
        showGlobalError(e.message);
    }
}

/* ---------- Мои заказы ---------- */
async function loadMyOrders() {
    const container = document.getElementById("my-orders-content");
    const orders = await apiGet("/order/my");
    renderTable(container, [
        { title: "Номер", key: "orderNumber" },
        { title: "Статус", render: o => statusBadge(o.status) },
        { title: "К оплате", render: o => money(o.finalAmount) },
        { title: "Создан", key: "createdAt" },
        { title: "Действия", render: o => myOrderActions(o) }
    ], orders, "У вас пока нет заказов");
}

function myOrderActions(order) {
    const id = order.id;
    const buttons = ['<button data-my-order-action="view" data-id="' + id + '">Детали</button>'];
    if (order.status === "NEW" || order.status === "ACCEPTED" || order.status === "AWAITING_PAYMENT") {
        buttons.push('<button class="danger" data-my-order-action="cancel" data-id="' + id + '">Отменить</button>');
    }
    return '<span class="actions">' + buttons.join("") + '</span>';
}

async function showMyOrder(id) {
    try {
        const order = await apiGet("/order/my/" + id);
        document.getElementById("my-order-details").classList.remove("hidden");
        const rows = [
            { label: "Номер", value: escapeHtml(order.orderNumber) },
            { label: "Статус", value: statusBadge(order.status) },
            { label: "Сумма", value: money(order.totalAmount) },
            { label: "Скидка, %", value: escapeHtml(order.discount) },
            { label: "К оплате", value: money(order.finalAmount) },
            { label: "Способ оплаты", value: escapeHtml(order.paymentMethod || "—") },
            { label: "Чек", value: escapeHtml(order.receiptNumber || "—") },
            { label: "Создан", value: escapeHtml(order.createdAt) }
        ];
        if (order.customer) {
            rows.push({ label: "Покупатель", value: escapeHtml(
                (order.customer.firstName || "") + " " + (order.customer.lastName || "")) });
            rows.push({ label: "Email", value: escapeHtml(order.customer.email || "—") });
            rows.push({ label: "Телефон", value: escapeHtml(order.customer.phone || "—") });
        }
        const content = document.getElementById("my-order-details-content");
        renderDetails(content, rows);

        const itemsContainer = document.createElement("div");
        itemsContainer.style.marginTop = "12px";
        content.appendChild(itemsContainer);
        renderTable(itemsContainer, [
            { title: "Товар", key: "productId" },
            { title: "Кол-во", key: "quantity" },
            { title: "Цена", render: i => money(i.price) },
            { title: "Гарантия, мес.", key: "warrantyMonths" },
            { title: "Сумма", render: i => money(i.lineTotal) }
        ], order.items, "Позиции отсутствуют");
    } catch (e) {
        showGlobalError(e.message);
    }
}

function closeMyOrderDetails() {
    document.getElementById("my-order-details").classList.add("hidden");
}

async function cancelMyOrder(id) {
    if (!confirm("Отменить заказ?")) return;
    try {
        await apiDelete("/order/my/" + id);
        await loadMyOrders();
    } catch (e) {
        showGlobalError(e.message);
    }
}

async function loadNotifications() {
    const journalContainer = document.getElementById("notifications-content");
    const callTasksContainer = document.getElementById("calltasks-content");

    const journal = await apiGet("/notification/journal");
    renderTable(journalContainer, [
        { title: "Заказ", key: "orderNumber" },
        { title: "Тип", key: "type" },
        { title: "Получатель", key: "recipient" },
        { title: "Тема", key: "subject" },
        { title: "Время", key: "sentAt" }
    ], journal, "Журнал уведомлений пуст");

    const callTasks = await apiGet("/notification/call-tasks");
    renderTable(callTasksContainer, [
        { title: "Заказ", key: "orderNumber" },
        { title: "Телефон", key: "phone" },
        { title: "Покупатель", render: t => escapeHtml((t.firstName || "") + " " + (t.lastName || "")) },
        { title: "Причина", key: "reason" },
        { title: "Создано", key: "createdAt" },
        { title: "Выполнено", render: t => t.done ? "да" : "нет" }
    ], callTasks, "Заданий на звонок нет");
}

const ALL_ORDER_STATUSES = ["NEW", "ACCEPTED", "AWAITING_PAYMENT", "PAID", "READY", "ISSUED", "REJECTED", "CANCELLED"];

async function loadReport() {
    const container = document.getElementById("report-content");
    const status = document.getElementById("report-status").value;
    let orders;
    if (status) {
        orders = await apiGet("/order/report?status=" + encodeURIComponent(status));
    } else {
        // Отчет по всем статусам: сервис требует параметр status, поэтому опрашиваем каждый
        const chunks = await Promise.all(
            ALL_ORDER_STATUSES.map(s => apiGet("/order/report?status=" + encodeURIComponent(s)))
        );
        orders = chunks.flat();
    }

    const total = orders.reduce((sum, o) => sum + Number(o.finalAmount || 0), 0);
    const byStatus = {};
    orders.forEach(o => { byStatus[o.status] = (byStatus[o.status] || 0) + 1; });

    let summary = '<div class="toolbar" style="gap:24px;font-size:14px;">'
        + '<span><strong>Заказов:</strong> ' + orders.length + '</span>'
        + '<span><strong>Сумма к оплате:</strong> ' + money(total) + '</span>'
        + '</div>';

    let breakdown = '<div class="toolbar" style="gap:12px;font-size:13px;">';
    Object.keys(byStatus).sort().forEach(s => {
        breakdown += '<span>' + statusBadge(s) + ' ' + byStatus[s] + '</span>';
    });
    breakdown += '</div>';

    container.innerHTML = summary + breakdown;
    const table = document.createElement("div");
    container.appendChild(table);
    renderTable(table, [
        { title: "Номер", key: "orderNumber" },
        { title: "Статус", render: o => statusBadge(o.status) },
        { title: "Сумма", render: o => money(o.totalAmount) },
        { title: "К оплате", render: o => money(o.finalAmount) },
        { title: "Создан", key: "createdAt" }
    ], orders, "Нет заказов для отчета");
}

// Фильтр принимает либо числовой id заказа, либо номер заказа (UUID).
// Бэкенд умеет только /order/{id}/audit, поэтому номер сначала превращаем в id.
async function resolveOrderId(value) {
    if (!value) {
        return null;
    }
    if (/^\d+$/.test(value)) {
        return value;
    }
    const orders = await apiGet("/order/report");
    const order = orders.find(o => o.orderNumber === value);
    if (!order) {
        throw new Error("Заказ с номером " + value + " не найден");
    }
    return String(order.id);
}

async function loadAudit() {
    const container = document.getElementById("audit-content");
    const filter = document.getElementById("audit-order-id").value.trim();
    const orderId = await resolveOrderId(filter);
    const path = orderId ? "/order/" + encodeURIComponent(orderId) + "/audit" : "/order/audit";
    const entries = await apiGet(path);
    renderTable(container, [
        { title: "Заказ", key: "orderId" },
        { title: "Действие", key: "action" },
        { title: "Пользователь", key: "performedBy" },
        { title: "Время", key: "performedAt" },
        { title: "Детали", key: "details" }
    ], entries, "Журнал действий пуст");
}

/* =========================================================================
   Инициализация
   ========================================================================= */
function showApp() {
    document.getElementById("login-screen").classList.add("hidden");
    document.getElementById("app").classList.remove("hidden");

    const claims = parseJwt(getToken());
    document.getElementById("user-name").textContent =
        claims.preferred_username || claims.name || claims.sub || "";
    const allRoles = (claims.realm_access && claims.realm_access.roles) || [];
    const technical = ["offline_access", "uma_authorization"];
    const roles = allRoles.filter(r => !technical.includes(r) && !r.startsWith("default-roles-"));
    document.getElementById("user-roles").textContent = roles.join(", ");

    activateTab("catalog");
}

function bindEvents() {
    document.getElementById("login-btn").addEventListener("click", login);
    document.getElementById("logout-btn").addEventListener("click", logout);

    document.querySelectorAll("#tabs button").forEach(button => {
        button.addEventListener("click", () => activateTab(button.dataset.tab));
    });

    document.getElementById("catalog-refresh-btn").addEventListener("click", () =>
        loadCatalog().catch(e => showGlobalError(e.message)));
    document.getElementById("catalog-search-btn").addEventListener("click", async () => {
        const prefix = document.getElementById("catalog-search").value.trim();
        const container = document.getElementById("catalog-content");
        try {
            const products = prefix
                ? await apiGet("/product/search?prefix=" + encodeURIComponent(prefix))
                : await apiGet("/product");
            renderTable(container, [
                { title: "SKU", key: "sku" },
                { title: "Наименование", key: "name" },
                { title: "Цена", render: p => money(p.price) },
                { title: "Гарантия, мес.", key: "warrantyMonths" },
                { title: "", render: p => catalogActions(p) }
            ], products, "Ничего не найдено");
        } catch (e) {
            showGlobalError(e.message);
        }
    });
    document.getElementById("catalog-content").addEventListener("click", event => {
        const button = event.target.closest("button[data-catalog-action]");
        if (button && button.dataset.catalogAction === "view") {
            openProductCard(button.dataset.id);
        }
    });
    document.getElementById("product-card-close-btn").addEventListener("click", closeProductCard);

    document.getElementById("products-refresh-btn").addEventListener("click", () =>
        loadProducts().catch(e => showGlobalError(e.message)));
    document.getElementById("product-new-btn").addEventListener("click", () => openProductForm(null));
    document.getElementById("product-save-btn").addEventListener("click", saveProduct);
    document.getElementById("product-cancel-btn").addEventListener("click", closeProductForm);
    document.getElementById("product-attribute-add-btn").addEventListener("click", () => addAttributeRow());
    document.getElementById("products-content").addEventListener("click", event => {
        const button = event.target.closest("button[data-product-action]");
        if (!button) return;
        const id = button.dataset.id;
        if (button.dataset.productAction === "delete") {
            deleteProduct(id);
        } else if (button.dataset.productAction === "edit") {
            apiGet("/product/" + id)
                .then(product => openProductForm(product))
                .catch(e => showGlobalError(e.message));
        }
    });
    document.getElementById("inventory-refresh-btn").addEventListener("click", () =>
        loadInventory().catch(e => showGlobalError(e.message)));
    document.getElementById("inventory-save-btn").addEventListener("click", saveInventory);
    document.getElementById("inventory-cancel-btn").addEventListener("click", closeInventoryForm);
    document.getElementById("inventory-content").addEventListener("click", event => {
        const button = event.target.closest("button[data-inventory-action]");
        if (button && button.dataset.inventoryAction === "edit") {
            openInventoryForm(button.dataset.productId, button.dataset.quantity)
                .catch(e => showGlobalError(e.message));
        }
    });

    document.getElementById("orders-refresh-btn").addEventListener("click", () =>
        loadOrders().catch(e => showGlobalError(e.message)));
    document.getElementById("orders-status").addEventListener("change", () =>
        loadOrders().catch(e => showGlobalError(e.message)));
    document.getElementById("order-new-btn").addEventListener("click",
        () => openOrderForm().catch(e => showGlobalError(e.message)));
    document.getElementById("order-add-item-btn").addEventListener("click", addOrderItemRow);
    document.getElementById("order-save-btn").addEventListener("click", saveOrder);
    document.getElementById("order-cancel-btn").addEventListener("click", closeOrderForm);

    document.getElementById("my-orders-refresh-btn").addEventListener("click", () =>
        loadMyOrders().catch(e => showGlobalError(e.message)));
    document.getElementById("my-order-details-close-btn").addEventListener("click", closeMyOrderDetails);
    document.getElementById("my-orders-content").addEventListener("click", event => {
        const button = event.target.closest("button[data-my-order-action]");
        if (!button) return;
        if (button.dataset.myOrderAction === "view") {
            showMyOrder(button.dataset.id);
        } else if (button.dataset.myOrderAction === "cancel") {
            cancelMyOrder(button.dataset.id);
        }
    });

    document.getElementById("notifications-refresh-btn").addEventListener("click", () =>
        loadNotifications().catch(e => showGlobalError(e.message)));
    document.getElementById("notifications-filter-btn").addEventListener("click", () =>
        loadNotifications().catch(e => showGlobalError(e.message)));
    document.getElementById("report-refresh-btn").addEventListener("click", () =>
        loadReport().catch(e => showGlobalError(e.message)));
    document.getElementById("audit-refresh-btn").addEventListener("click", () =>
        loadAudit().catch(e => showGlobalError(e.message)));
    document.getElementById("audit-filter-btn").addEventListener("click", () =>
        loadAudit().catch(e => showGlobalError(e.message)));

    // Делегирование кликов по кнопкам действий в таблице заказов
    document.getElementById("orders-content").addEventListener("click", event => {
        const button = event.target.closest("button[data-action]");
        if (button) {
            performOrderAction(button.dataset.action, button.dataset.id, button.dataset.orderNumber);
        }
    });
}

async function init() {
    bindEvents();
    const handled = await handleRedirect();
    if (handled || getToken()) {
        showApp();
    }
}

init();
