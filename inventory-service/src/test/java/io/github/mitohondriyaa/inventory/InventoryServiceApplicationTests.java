package io.github.mitohondriyaa.inventory;

import com.redis.testcontainers.RedisContainer;
import io.github.mitohondriyaa.inventory.config.TestRedisConfig;
import io.github.mitohondriyaa.inventory.model.Inventory;
import io.github.mitohondriyaa.inventory.model.Reservation;
import io.github.mitohondriyaa.inventory.repository.InventoryRepository;
import io.github.mitohondriyaa.inventory.repository.IssueJournalRepository;
import io.github.mitohondriyaa.inventory.repository.ReservationRepository;
import io.github.mitohondriyaa.inventory.service.InventoryService;
import io.github.mitohondriyaa.order.event.OrderCancelledEvent;
import io.github.mitohondriyaa.order.event.OrderIssuedEvent;
import io.github.mitohondriyaa.order.event.OrderItemEvent;
import io.github.mitohondriyaa.order.event.OrderPlacedEvent;
import io.github.mitohondriyaa.product.event.ProductCreatedEvent;
import io.github.mitohondriyaa.product.event.ProductDeletedEvent;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.*;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.Lifecycle;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.kafka.ConfluentKafkaContainer;
import org.testcontainers.shaded.org.awaitility.Awaitility;

import java.time.Duration;
import java.util.*;

import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
@RequiredArgsConstructor
@ActiveProfiles("test")
@Import(TestRedisConfig.class)
class InventoryServiceApplicationTests {
	static Network network = Network.newNetwork();
	@ServiceConnection
	@SuppressWarnings("resource")
	static MySQLContainer<?> mySQLContainer =  new MySQLContainer<>("mysql:8")
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
	static RedisContainer redisContainer = new  RedisContainer("redis:8.0")
		.withExposedPorts(6379)
		.withNetwork(network)
		.withNetworkAliases("redis");
	static final String PRODUCT_ID = "a876af73h3uf3hj";
	static final String ORDER_NUMBER = "748f7f87ff78893983k";
	@LocalServerPort
	Integer port;
	@MockitoBean
	JwtDecoder jwtDecoder;
	final KafkaListenerEndpointRegistry kafkaListenerEndpointRegistry;
	final KafkaTemplate<String, Object> kafkaTemplate;
	final ConsumerFactory<String, Object> consumerFactory;
	final InventoryRepository inventoryRepository;
	final ReservationRepository reservationRepository;
	final IssueJournalRepository issueJournalRepository;
	@MockitoSpyBean
	InventoryService inventoryService;

	static {
		mySQLContainer.start();
		kafkaContainer.start();
		schemaRegistryContainer.start();
		redisContainer.start();
	}

	@DynamicPropertySource
	static void dynamicProperties(DynamicPropertyRegistry registry) {
		registry.add("spring.kafka.producer.properties.schema.registry.url",
			() -> "http://localhost:" + schemaRegistryContainer.getMappedPort(8081));
		registry.add("spring.kafka.consumer.properties.schema.registry.url",
			() -> "http://localhost:" + schemaRegistryContainer.getMappedPort(8081));
		registry.add("redis.port",
			() -> redisContainer.getMappedPort(6379));
	}

	@BeforeEach
	void setUp() throws InterruptedException {
		RestAssured.baseURI = "http://localhost";
		RestAssured.port = port;

		Map<String, Object> realmAccess = new HashMap<>();
		realmAccess.put("roles", List.of("INVENTORY_MANAGER"));

		Jwt jwt = Jwt.withTokenValue("mock-token")
			.header("alg", "none")
			.claim("email", "test@example.com")
			.claim("given_name", "Alexander")
			.claim("family_name", "Sidorov")
			.claim("sub", "h7g3hg383837h7733hf38h37")
			.claim("realm_access", realmAccess)
			.build();

		when(jwtDecoder.decode(anyString())).thenReturn(jwt);

		kafkaListenerEndpointRegistry.getAllListenerContainers()
			.forEach(Lifecycle::start);

		Thread.sleep(5000);
	}

	private Inventory inventoryWith(int quantity) {
		Inventory inventory = new Inventory();
		inventory.setProductId(PRODUCT_ID);
		inventory.setQuantity(quantity);
		return inventoryRepository.save(inventory);
	}

	private OrderPlacedEvent placedEvent(int quantity) {
		return new OrderPlacedEvent(
			ORDER_NUMBER,
			List.of(new OrderItemEvent(PRODUCT_ID, quantity)),
			"test@example.com",
			"Alexander",
			"Sidorov"
		);
	}

	private void send(String topic, Object event) {
		ProducerRecord<String, Object> producerRecord = new ProducerRecord<>(topic, event);
		producerRecord.headers().add("messageId", UUID.randomUUID().toString().getBytes());
		kafkaTemplate.send(producerRecord);
	}

	@Test
	void shouldCreateInventory() {
		ProductCreatedEvent productCreatedEvent = new ProductCreatedEvent();
		productCreatedEvent.setProductId(PRODUCT_ID);

		send("product-created", productCreatedEvent);

		Awaitility.await().atMost(Duration.ofSeconds(5))
			.untilAsserted(() -> verify(inventoryService, atLeastOnce())
				.createInventory(productCreatedEvent));

		Awaitility.await().atMost(Duration.ofSeconds(5))
			.untilAsserted(() -> {
				Optional<Inventory> optionalInventory
					= inventoryRepository.findByProductId(PRODUCT_ID);
				optionalInventory
					.ifPresentOrElse(inventory -> {
						Assertions.assertNotNull(inventory.getId());
						Assertions.assertEquals(PRODUCT_ID, inventory.getProductId());
						Assertions.assertEquals(0, inventory.getQuantity());
					}, () -> Assertions.fail("Inventory not found"));
			});
	}

	@Test
	void shouldDeductStockWhenQuantityIsEnough() {
		inventoryWith(20);

		send("order-placed", placedEvent(10));

		try (Consumer<String, Object> consumer = consumerFactory.createConsumer("testNotificationService", "test-client")) {
			consumer.subscribe(List.of("inventory-reserved"));

			ConsumerRecords<String , Object> records =
				KafkaTestUtils.getRecords(consumer, Duration.ofSeconds(5));

			Assertions.assertFalse(records.isEmpty());
		}

		Awaitility.await().atMost(Duration.ofSeconds(5))
			.untilAsserted(() -> Assertions.assertEquals(10,
				inventoryRepository.findByProductId(PRODUCT_ID).orElseThrow().getQuantity()));

		List<Reservation> reservations = reservationRepository.findByOrderNumber(ORDER_NUMBER);
		Assertions.assertEquals(1, reservations.size());
		Assertions.assertEquals(10, reservations.get(0).getQuantity());
	}

	@Test
	void shouldDeductStockWhenQuantityIsNotEnough() {
		inventoryWith(20);

		send("order-placed", placedEvent(25));

		try (Consumer<String, Object> consumer = consumerFactory.createConsumer("testNotificationService", "test-client")) {
			consumer.subscribe(List.of("inventory-rejected"));

			ConsumerRecords<String , Object> records =
				KafkaTestUtils.getRecords(consumer, Duration.ofSeconds(5));

			Assertions.assertFalse(records.isEmpty());
		}

		Awaitility.await().atMost(Duration.ofSeconds(5))
			.untilAsserted(() -> Assertions.assertEquals(20,
				inventoryRepository.findByProductId(PRODUCT_ID).orElseThrow().getQuantity()));

		Assertions.assertTrue(reservationRepository.findByOrderNumber(ORDER_NUMBER).isEmpty());
	}

	@Test
	void shouldReserveAllOrNothingForMultipleItems() {
		inventoryWith(20);

		OrderPlacedEvent event = new OrderPlacedEvent(
			ORDER_NUMBER,
			List.of(
				new OrderItemEvent(PRODUCT_ID, 5),
				new OrderItemEvent("another-product", 5)
			),
			"test@example.com",
			"Alexander",
			"Sidorov"
		);

		send("order-placed", event);

		try (Consumer<String, Object> consumer = consumerFactory.createConsumer("testNotificationService", "test-client")) {
			consumer.subscribe(List.of("inventory-rejected"));

			ConsumerRecords<String , Object> records =
				KafkaTestUtils.getRecords(consumer, Duration.ofSeconds(5));

			Assertions.assertFalse(records.isEmpty());
		}

		Awaitility.await().atMost(Duration.ofSeconds(5))
			.untilAsserted(() -> Assertions.assertEquals(20,
				inventoryRepository.findByProductId(PRODUCT_ID).orElseThrow().getQuantity()));
	}

	@Test
	void shouldGetAllInventories() {
		inventoryWith(20);

		RestAssured.given()
			.header("Authorization", "Bearer mock-token")
			.when()
			.get("/api/inventory")
			.then()
			.statusCode(200)
			.body("size()", Matchers.is(1));
	}

	@Test
	void shouldCheckStock() {
		inventoryWith(20);

		RestAssured.given()
			.header("Authorization", "Bearer mock-token")
			.queryParam("productId", PRODUCT_ID)
			.queryParam("quantity", 20)
			.when()
			.get("/api/inventory/check")
			.then()
			.statusCode(200)
			.body(Matchers.equalTo("true"));
	}

	@Test
	void shouldReportNotEnoughStock() {
		inventoryWith(5);

		RestAssured.given()
			.header("Authorization", "Bearer mock-token")
			.queryParam("productId", PRODUCT_ID)
			.queryParam("quantity", 20)
			.when()
			.get("/api/inventory/check")
			.then()
			.statusCode(200)
			.body(Matchers.equalTo("false"));
	}

	@Test
	void shouldGetInventoryByProductId() {
		inventoryWith(20);

		RestAssured.given()
			.header("Authorization", "Bearer mock-token")
			.when()
			.get("/api/inventory/" + PRODUCT_ID)
			.then()
			.statusCode(200)
			.body("id", Matchers.notNullValue())
			.body("productId", Matchers.equalTo(PRODUCT_ID))
			.body("quantity", Matchers.equalTo(20));
	}

	@Test
	void shouldUpdateInventoryByProductId() {
		inventoryWith(20);

		String requestBody = """
			{
				"productId": "%s",
				"quantity": 40
			}
			""".formatted(PRODUCT_ID);

		RestAssured.given()
			.contentType(ContentType.JSON)
			.header("Authorization", "Bearer mock-token")
			.body(requestBody)
			.when()
			.put("/api/inventory")
			.then()
			.statusCode(200)
			.body("id", Matchers.notNullValue())
			.body("productId", Matchers.equalTo(PRODUCT_ID))
			.body("quantity", Matchers.equalTo(40));
	}

	@Test
	void shouldDeleteInventoryByProductId() {
		inventoryWith(20);

		ProductDeletedEvent productDeletedEvent = new ProductDeletedEvent();
		productDeletedEvent.setProductId(PRODUCT_ID);

		send("product-deleted", productDeletedEvent);

		Awaitility.await().atMost(Duration.ofSeconds(5))
			.untilAsserted(() -> verify(inventoryService, atLeastOnce())
				.deleteInventoryByProductID(eq(productDeletedEvent)));

		Awaitility.await().atMost(Duration.ofSeconds(5))
			.untilAsserted(() -> {
				Optional<Inventory> optionalInventory
					= inventoryRepository.findByProductId(PRODUCT_ID);
				Assertions.assertTrue(optionalInventory.isEmpty());
			});
	}

	@Test
	void shouldReleaseReservationOnCancel() {
		inventoryWith(20);

		send("order-placed", placedEvent(10));

		Awaitility.await().atMost(Duration.ofSeconds(5))
			.untilAsserted(() -> Assertions.assertEquals(10,
				inventoryRepository.findByProductId(PRODUCT_ID).orElseThrow().getQuantity()));

		OrderCancelledEvent cancelled = new OrderCancelledEvent(
			ORDER_NUMBER,
			List.of(new OrderItemEvent(PRODUCT_ID, 10)),
			"test@example.com",
			"Alexander",
			"Sidorov"
		);

		send("order-cancelled", cancelled);

		Awaitility.await().atMost(Duration.ofSeconds(5))
			.untilAsserted(() -> Assertions.assertEquals(20,
				inventoryRepository.findByProductId(PRODUCT_ID).orElseThrow().getQuantity()));
	}

	@Test
	void shouldWriteIssueJournal() {
		inventoryWith(20);

		send("order-placed", placedEvent(10));

		Awaitility.await().atMost(Duration.ofSeconds(5))
			.untilAsserted(() -> Assertions.assertEquals(10,
				inventoryRepository.findByProductId(PRODUCT_ID).orElseThrow().getQuantity()));

		OrderIssuedEvent issued = new OrderIssuedEvent(
			ORDER_NUMBER,
			"2026-01-01T10:00:00",
			"storekeeper",
			List.of(new OrderItemEvent(PRODUCT_ID, 10))
		);

		send("order-issued", issued);

		Awaitility.await().atMost(Duration.ofSeconds(5))
			.untilAsserted(() -> Assertions.assertEquals(1,
				issueJournalRepository.findByOrderNumber(ORDER_NUMBER).size()));
	}

	@AfterEach
	void tearDown() {
		kafkaListenerEndpointRegistry.getAllListenerContainers()
			.forEach(Lifecycle::stop);

		inventoryRepository.deleteAll();
		reservationRepository.deleteAll();
		issueJournalRepository.deleteAll();
	}

	@AfterAll
	static void stopContainers() {
		mySQLContainer.stop();
		kafkaContainer.stop();
		schemaRegistryContainer.stop();
		redisContainer.stop();
		network.close();
	}
}
