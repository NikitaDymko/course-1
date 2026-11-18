package io.github.mitohondriyaa.notification;

import com.redis.testcontainers.RedisContainer;
import io.github.mitohondriyaa.inventory.event.InventoryRejectedEvent;
import io.github.mitohondriyaa.inventory.event.InventoryReservedEvent;
import io.github.mitohondriyaa.notification.config.TestRedisConfig;
import io.github.mitohondriyaa.notification.service.NotificationJournalService;
import io.github.mitohondriyaa.order.event.OrderIssuedEvent;
import io.github.mitohondriyaa.order.event.OrderItemEvent;
import io.github.mitohondriyaa.order.event.OrderStatusChangedEvent;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.Lifecycle;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.mail.MailSendException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessagePreparator;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestConstructor;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.kafka.ConfluentKafkaContainer;
import org.testcontainers.shaded.org.awaitility.Awaitility;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.*;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@TestConstructor(autowireMode = TestConstructor.AutowireMode.ALL)
@Import(TestRedisConfig.class)
@RequiredArgsConstructor
class NotificationServiceApplicationTests {
	static Network network = Network.newNetwork();
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
	@LocalServerPort
	Integer port;
	final KafkaListenerEndpointRegistry kafkaListenerEndpointRegistry;
	final KafkaTemplate<String, Object> kafkaTemplate;
	final NotificationJournalService journalService;
	@MockitoSpyBean
	JavaMailSender mailSender;

	static {
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
		// Заглушаем реальную отправку почты: spy-бин не должен ходить в SMTP.
		doNothing().when(mailSender).send(any(MimeMessagePreparator.class));

		kafkaListenerEndpointRegistry.getAllListenerContainers()
			.forEach(Lifecycle::start);

		Thread.sleep(5000);
	}

	private void send(String topic, Object event) {
		ProducerRecord<String, Object> producerRecord = new ProducerRecord<>(topic, event);
		producerRecord.headers().add("messageId", UUID.randomUUID().toString().getBytes());
		kafkaTemplate.send(producerRecord);
	}

	@Test
	void shouldSendInventoryReservedNotification() {
		InventoryReservedEvent inventoryReservedEvent = new InventoryReservedEvent();
		inventoryReservedEvent.setOrderNumber(UUID.randomUUID().toString());
		inventoryReservedEvent.setEmail("test@example.com");
		inventoryReservedEvent.setFirstName("Oleg");
		inventoryReservedEvent.setLastName("Kireev");

		send("inventory-reserved", inventoryReservedEvent);

		Awaitility.await()
			.atMost(Duration.ofSeconds(5))
			.untilAsserted(() ->
				verify(mailSender).send(any(MimeMessagePreparator.class))
			);
	}

	@Test
	void shouldSendInventoryRejectedNotification() {
		InventoryRejectedEvent inventoryRejectedEvent = new InventoryRejectedEvent();
		inventoryRejectedEvent.setOrderNumber(UUID.randomUUID().toString());
		inventoryRejectedEvent.setEmail("test@example.com");
		inventoryRejectedEvent.setFirstName("Oleg");
		inventoryRejectedEvent.setLastName("Kireev");

		send("inventory-rejected", inventoryRejectedEvent);

		Awaitility.await()
			.atMost(Duration.ofSeconds(5))
			.untilAsserted(() ->
				verify(mailSender).send(any(MimeMessagePreparator.class))
			);
	}

	@Test
	void shouldSendStatusChangedNotification() {
		String orderNumber = UUID.randomUUID().toString();
		OrderStatusChangedEvent event = new OrderStatusChangedEvent(
			orderNumber,
			"PAID",
			"2026-01-01T10:00:00",
			"cashier",
			"test@example.com",
			"Oleg",
			"Kireev"
		);

		send("order-status-changed", event);

		Awaitility.await()
			.atMost(Duration.ofSeconds(5))
			.untilAsserted(() ->
				verify(mailSender).send(any(MimeMessagePreparator.class))
			);
	}

	@Test
	void shouldCreateCallTaskWhenOrderAccepted() {
		String orderNumber = UUID.randomUUID().toString();
		OrderStatusChangedEvent event = new OrderStatusChangedEvent(
			orderNumber,
			"ACCEPTED",
			"2026-01-01T10:00:00",
			"seller",
			"test@example.com",
			"Oleg",
			"Kireev"
		);

		send("order-status-changed", event);

		Awaitility.await()
			.atMost(Duration.ofSeconds(5))
			.untilAsserted(() -> Assertions.assertEquals(1,
				journalService.getCallTasks().stream()
					.filter(task -> orderNumber.equals(task.getOrderNumber()))
					.count()));
	}

	@Test
	void shouldWriteJournalRecordForStatusChange() {
		String orderNumber = UUID.randomUUID().toString();
		OrderStatusChangedEvent event = new OrderStatusChangedEvent(
			orderNumber,
			"READY",
			"2026-01-01T10:00:00",
			"storekeeper",
			"test@example.com",
			"Oleg",
			"Kireev"
		);

		send("order-status-changed", event);

		Awaitility.await()
			.atMost(Duration.ofSeconds(5))
			.untilAsserted(() -> Assertions.assertEquals(1,
				journalService.getJournalByOrder(orderNumber).size()));
	}

	@Test
	void shouldRecordFailedAttemptAndCreateCallTaskWhenEmailFails() {
		// Отправка невозможна: почтовый сервер недоступен.
		doThrow(new MailSendException("SMTP is unavailable"))
			.when(mailSender).send(any(MimeMessagePreparator.class));

		String orderNumber = UUID.randomUUID().toString();
		InventoryReservedEvent event = new InventoryReservedEvent();
		event.setOrderNumber(orderNumber);
		event.setEmail("test@example.com");
		event.setFirstName("Oleg");
		event.setLastName("Kireev");

		send("inventory-reserved", event);

		Awaitility.await()
			.atMost(Duration.ofSeconds(5))
			.untilAsserted(() -> {
				// Запись о неудачной попытке сохранена в журнале уведомлений.
				Assertions.assertTrue(journalService.getJournalByOrder(orderNumber).stream()
					.anyMatch(record -> "ORDER_PLACED_FAILED".equals(record.getType())));
				// Создано задание на последующий звонок покупателю.
				Assertions.assertEquals(1, journalService.getCallTasks().stream()
					.filter(task -> orderNumber.equals(task.getOrderNumber()))
					.count());
			});
	}

	@Test
	void shouldWriteJournalRecordForIssuedOrder() {
		String orderNumber = UUID.randomUUID().toString();
		OrderIssuedEvent event = new OrderIssuedEvent(
			orderNumber,
			"2026-01-01T10:00:00",
			"storekeeper",
			List.of(new OrderItemEvent("product-1", 1))
		);

		send("order-issued", event);

		Awaitility.await()
			.atMost(Duration.ofSeconds(5))
			.untilAsserted(() -> Assertions.assertEquals(1,
				journalService.getJournalByOrder(orderNumber).size()));
	}

	@AfterEach
	void tearDown() {
		kafkaListenerEndpointRegistry.getAllListenerContainers()
			.forEach(Lifecycle::stop);
	}

	@AfterAll
	static void stopContainers() {
		kafkaContainer.stop();
		schemaRegistryContainer.stop();
		redisContainer.stop();
		network.close();
	}
}
