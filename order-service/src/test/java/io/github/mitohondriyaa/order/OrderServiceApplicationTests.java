package io.github.mitohondriyaa.order;

import io.github.mitohondriyaa.inventory.event.InventoryRejectedEvent;
import io.github.mitohondriyaa.inventory.event.InventoryReservedEvent;
import io.github.mitohondriyaa.order.event.OrderPlacedEvent;
import io.github.mitohondriyaa.order.model.Customer;
import io.github.mitohondriyaa.order.model.Order;
import io.github.mitohondriyaa.order.model.OrderItem;
import io.github.mitohondriyaa.order.model.OrderStatus;
import io.github.mitohondriyaa.order.repository.OrderRepository;
import io.github.mitohondriyaa.order.stub.InventoryClientStub;
import io.github.mitohondriyaa.order.stub.ProductClientStub;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.cloud.contract.wiremock.AutoConfigureWireMock;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.utils.KafkaTestUtils;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.kafka.ConfluentKafkaContainer;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWireMock(port = 0)
@ActiveProfiles("test")
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
@RequiredArgsConstructor
class OrderServiceApplicationTests {
	static Network network = Network.newNetwork();
	@ServiceConnection
	@SuppressWarnings("resource")
	static MySQLContainer<?> mySQLContainer = new MySQLContainer<>("mysql:8")
		.withNetwork(network)
		.withNetworkAliases("mysql");
	@ServiceConnection
	static ConfluentKafkaContainer kafkaContainer = new ConfluentKafkaContainer("confluentinc/cp-kafka:7.4.0")
		.withListener("kafka:19092")
		.withNetwork(network)
		.withNetworkAliases("kafka");
	@SuppressWarnings("resource")
	static GenericContainer<?> schemaRegistryContainer = new GenericContainer<>("confluentinc/cp-schema-registry:7.4.0")
		.withEnv("SCHEMA_REGISTRY_KAFKASTORE_BOOTSTRAP_SERVERS", "PLAINTEXT://kafka:19092")
		.withEnv("SCHEMA_REGISTRY_LISTENERS", "http://0.0.0.0:8081")
		.withEnv("SCHEMA_REGISTRY_HOST_NAME", "schema-registry")
		.withExposedPorts(8081)
		.withNetwork(network)
		.withNetworkAliases("schema-registry")
		.waitingFor(Wait.forHttp("/subjects"));
	static final String PRODUCT_ID = "6885edd749327c54f0627f8b";
	static final String USER_ID = "h7g3hg383837h7733hf38h37";
	@LocalServerPort
	Integer port;
	@MockitoBean
	JwtDecoder jwtDecoder;
	final ConsumerFactory<String, OrderPlacedEvent> consumerFactory;
	final KafkaTemplate<String, Object> kafkaTemplate;
	final OrderRepository orderRepository;
	final io.github.mitohondriyaa.order.repository.OutboxRepository outboxRepository;

	static {
		mySQLContainer.start();
		kafkaContainer.start();
		schemaRegistryContainer.start();
	}

	@DynamicPropertySource
	static void dynamicProperties(DynamicPropertyRegistry registry) {
		registry.add("spring.kafka.producer.properties.schema.registry.url",
			() -> "http://localhost:" + schemaRegistryContainer.getMappedPort(8081));
		registry.add("spring.kafka.consumer.properties.schema.registry.url",
			() -> "http://localhost:" + schemaRegistryContainer.getMappedPort(8081));
	}

	@BeforeEach
	void setUp() {
		RestAssured.baseURI = "http://localhost";
		RestAssured.port = port;

		Jwt jwt = Jwt.withTokenValue("mock-token")
			.header("alg", "none")
			.claim("email", "test@example.com")
			.claim("given_name", "Alexander")
			.claim("family_name", "Sidorov")
			.claim("sub", USER_ID)
			.claim("realm_access", java.util.Map.of("roles",
				List.of("SELLER", "CASHIER", "STOREKEEPER", "MANAGER")))
			.build();

		when(jwtDecoder.decode(anyString())).thenReturn(jwt);
	}

	private Order newOrder() {
		Order order = new Order();
		order.setOrderNumber(UUID.randomUUID().toString());
		order.setStatus(OrderStatus.NEW);
		order.setUserId(USER_ID);
		order.setDiscount(BigDecimal.ZERO);
		order.setTotalAmount(new BigDecimal("799.00"));
		order.setFinalAmount(new BigDecimal("799.00"));
		order.setCustomer(new Customer("test@example.com", "Alexander", "Sidorov", "+79001234567", true));

		OrderItem item = new OrderItem();
		item.setProductId(PRODUCT_ID);
		item.setQuantity(1);
		item.setPrice(new BigDecimal("799.00"));
		item.setWarrantyMonths(12);
		order.addItem(item);

		return order;
	}

	@Test
	void shouldPlaceOrder() {
		String requestBody = """
			{
				"items": [
					{ "productId": "%s", "quantity": 1 }
				],
				"customer": {
					"email": "test@example.com",
					"firstName": "Alexander",
					"lastName": "Sidorov",
					"phone": "+79001234567",
					"personalDataConsent": true
				},
				"discount": 0,
				"managerApproved": false
			}
			""".formatted(PRODUCT_ID);

		InventoryClientStub.stubInventoryCall(PRODUCT_ID, 1);
		ProductClientStub.stubProductCall(PRODUCT_ID);

		RestAssured.given()
			.contentType(ContentType.JSON)
			.header("Authorization", "Bearer mock-token")
			.body(requestBody)
			.when()
			.post("/api/order")
			.then()
			.statusCode(201)
			.body("id", Matchers.notNullValue())
			.body("orderNumber", Matchers.notNullValue())
			.body("status", Matchers.is("NEW"))
			.body("items[0].productId", Matchers.is(PRODUCT_ID))
			.body("items[0].price", Matchers.is(500))
			.body("items[0].quantity", Matchers.is(1))
			.body("totalAmount", Matchers.is(500.0F))
			.body("finalAmount", Matchers.is(500.0F))
			.body("customer.email", Matchers.is("test@example.com"))
			.body("customer.firstName", Matchers.is("Alexander"))
			.body("customer.lastName", Matchers.is("Sidorov"));

		// Событие размещения заказа записано в outbox в той же транзакции.
		Assertions.assertTrue(outboxRepository.findAll().stream()
			.anyMatch(event -> "order-placed".equals(event.getTopic())));
	}

	@Test
	void shouldPlaceOrderWithDiscount() {
		String requestBody = """
			{
				"items": [
					{ "productId": "%s", "quantity": 2 }
				],
				"customer": {
					"email": "test@example.com",
					"firstName": "Alexander",
					"lastName": "Sidorov",
					"phone": "+79001234567",
					"personalDataConsent": true
				},
				"discount": 10,
				"managerApproved": true
			}
			""".formatted(PRODUCT_ID);

		InventoryClientStub.stubInventoryCall(PRODUCT_ID, 2);
		ProductClientStub.stubProductCall(PRODUCT_ID);

		RestAssured.given()
			.contentType(ContentType.JSON)
			.header("Authorization", "Bearer mock-token")
			.body(requestBody)
			.when()
			.post("/api/order")
			.then()
			.statusCode(201)
			.body("totalAmount", Matchers.is(1000.0F))
			.body("finalAmount", Matchers.is(900.0F))
			.body("discount", Matchers.is(10));
	}

	@Test
	void shouldRejectDiscountWithoutManagerApproval() {
		String requestBody = """
			{
				"items": [
					{ "productId": "%s", "quantity": 1 }
				],
				"customer": {
					"email": "test@example.com",
					"firstName": "Alexander",
					"lastName": "Sidorov",
					"phone": "+79001234567",
					"personalDataConsent": true
				},
				"discount": 10,
				"managerApproved": false
			}
			""".formatted(PRODUCT_ID);

		InventoryClientStub.stubInventoryCall(PRODUCT_ID, 1);
		ProductClientStub.stubProductCall(PRODUCT_ID);

		RestAssured.given()
			.contentType(ContentType.JSON)
			.header("Authorization", "Bearer mock-token")
			.body(requestBody)
			.when()
			.post("/api/order")
			.then()
			.statusCode(403);
	}

	@Test
	void shouldCancelOrderById() {
		Long id = orderRepository.save(newOrder()).getId();

		RestAssured.given()
			.header("Authorization", "Bearer mock-token")
			.when()
			.delete("/api/order/my/" + id)
			.then()
			.statusCode(204);

		Order cancelled = orderRepository.findById(id).orElseThrow();
		Assertions.assertEquals(OrderStatus.CANCELLED, cancelled.getStatus());
	}

	@Test
	void shouldAcceptOrder() {
		Long id = orderRepository.save(newOrder()).getId();

		RestAssured.given()
			.header("Authorization", "Bearer mock-token")
			.when()
			.post("/api/order/" + id + "/accept")
			.then()
			.statusCode(200)
			.body("status", Matchers.is("ACCEPTED"));
	}

	@Test
	void shouldRejectOrder() {
		Long id = orderRepository.save(newOrder()).getId();

		RestAssured.given()
			.header("Authorization", "Bearer mock-token")
			.when()
			.post("/api/order/" + id + "/reject")
			.then()
			.statusCode(200)
			.body("status", Matchers.is("REJECTED"));
	}

	@Test
	void shouldAwaitPayment() {
		Order order = newOrder();
		order.setStatus(OrderStatus.ACCEPTED);
		Long id = orderRepository.save(order).getId();

		RestAssured.given()
			.header("Authorization", "Bearer mock-token")
			.when()
			.post("/api/order/" + id + "/await-payment")
			.then()
			.statusCode(200)
			.body("status", Matchers.is("AWAITING_PAYMENT"));
	}

	@Test
	void shouldPayOrder() {
		Order order = newOrder();
		order.setStatus(OrderStatus.AWAITING_PAYMENT);
		Long id = orderRepository.save(order).getId();

		RestAssured.given()
			.header("Authorization", "Bearer mock-token")
			.queryParam("paymentMethod", "CARD")
			.when()
			.post("/api/order/" + id + "/pay")
			.then()
			.statusCode(200)
			.body("status", Matchers.is("PAID"))
			.body("paymentMethod", Matchers.is("CARD"))
			.body("receiptNumber", Matchers.notNullValue());
	}

	@Test
	void shouldMarkOrderReady() {
		Order order = newOrder();
		order.setStatus(OrderStatus.PAID);
		Long id = orderRepository.save(order).getId();

		RestAssured.given()
			.header("Authorization", "Bearer mock-token")
			.when()
			.post("/api/order/" + id + "/ready")
			.then()
			.statusCode(200)
			.body("status", Matchers.is("READY"));
	}

	@Test
	void shouldIssueOrder() {
		Order order = newOrder();
		order.setStatus(OrderStatus.READY);
		Long id = orderRepository.save(order).getId();

		RestAssured.given()
			.header("Authorization", "Bearer mock-token")
			.when()
			.post("/api/order/" + id + "/issue")
			.then()
			.statusCode(200)
			.body("status", Matchers.is("ISSUED"))
			.body("issuedAt", Matchers.notNullValue());
	}

	@Test
	void shouldReturnConflictOnInvalidTransition() {
		Long id = orderRepository.save(newOrder()).getId();

		RestAssured.given()
			.header("Authorization", "Bearer mock-token")
			.when()
			.post("/api/order/" + id + "/issue")
			.then()
			.statusCode(409);
	}

	@Test
	void shouldGetOrdersByUserId() {
		orderRepository.save(newOrder());

		RestAssured.given()
			.header("Authorization", "Bearer mock-token")
			.when()
			.get("/api/order/my")
			.then()
			.statusCode(200)
			.body("size()", Matchers.is(1));
	}

	@Test
	void shouldGetOrderForUser() {
		Order order = newOrder();
		Long id = orderRepository.save(order).getId();

		RestAssured.given()
			.when()
			.header("Authorization", "Bearer mock-token")
			.get("/api/order/my/" + id)
			.then()
			.statusCode(200)
			.body("id", Matchers.is(id.intValue()))
			.body("orderNumber", Matchers.is(order.getOrderNumber()))
			.body("items[0].productId", Matchers.is(PRODUCT_ID))
			.body("items[0].price", Matchers.is(799.0F))
			.body("items[0].quantity", Matchers.is(1))
			.body("customer.email", Matchers.is("test@example.com"))
			.body("customer.firstName", Matchers.is("Alexander"))
			.body("customer.lastName", Matchers.is("Sidorov"));
	}

	@Test
	void shouldReturnNotFoundForUnknownOrder() {
		RestAssured.given()
			.header("Authorization", "Bearer mock-token")
			.when()
			.get("/api/order/my/999999")
			.then()
			.statusCode(404);
	}

	@Test
	void shouldGetReportByStatus() {
		Order order = newOrder();
		order.setStatus(OrderStatus.PAID);
		orderRepository.save(order);

		RestAssured.given()
			.header("Authorization", "Bearer mock-token")
			.queryParam("status", "PAID")
			.when()
			.get("/api/order/report")
			.then()
			.statusCode(200)
			.body("size()", Matchers.is(1));
	}

	@Test
	void shouldAcceptOrderOnInventoryReserved() {
		Order order = orderRepository.save(newOrder());
		String orderNumber = order.getOrderNumber();

		kafkaTemplate.send("inventory-reserved", new InventoryReservedEvent(
			orderNumber, "test@example.com", "Alexander", "Sidorov"));

		await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
			Assertions.assertEquals(OrderStatus.ACCEPTED,
				orderRepository.findById(order.getId()).orElseThrow().getStatus()));
	}

	@Test
	void shouldRejectOrderOnInventoryRejected() {
		Order order = orderRepository.save(newOrder());
		String orderNumber = order.getOrderNumber();

		kafkaTemplate.send("inventory-rejected", new InventoryRejectedEvent(
			orderNumber, "test@example.com", "Alexander", "Sidorov"));

		await().atMost(Duration.ofSeconds(20)).untilAsserted(() ->
			Assertions.assertEquals(OrderStatus.REJECTED,
				orderRepository.findById(order.getId()).orElseThrow().getStatus()));
	}

	@Test
	void shouldReturnAuditLog() {
		Long id = orderRepository.save(newOrder()).getId();

		RestAssured.given()
			.header("Authorization", "Bearer mock-token")
			.when()
			.post("/api/order/" + id + "/accept")
			.then()
			.statusCode(200);

		RestAssured.given()
			.header("Authorization", "Bearer mock-token")
			.when()
			.get("/api/order/" + id + "/audit")
			.then()
			.statusCode(200)
			.body("size()", Matchers.greaterThanOrEqualTo(1));
	}

	@AfterEach
	void tearDown() {
		orderRepository.deleteAll();
	}

	@AfterAll
	static void stopContainers() {
		mySQLContainer.stop();
		kafkaContainer.stop();
		schemaRegistryContainer.stop();
		network.close();
	}
}
