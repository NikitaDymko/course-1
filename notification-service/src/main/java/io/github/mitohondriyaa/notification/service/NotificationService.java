package io.github.mitohondriyaa.notification.service;

import io.github.mitohondriyaa.inventory.event.InventoryRejectedEvent;
import io.github.mitohondriyaa.inventory.event.InventoryReservedEvent;
import io.github.mitohondriyaa.notification.model.CallTask;
import io.github.mitohondriyaa.notification.model.NotificationRecord;
import io.github.mitohondriyaa.order.event.OrderIssuedEvent;
import io.github.mitohondriyaa.order.event.OrderStatusChangedEvent;
import lombok.RequiredArgsConstructor;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.mail.javamail.MimeMessagePreparator;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class NotificationService {
    private final JavaMailSender mailSender;
    private final RedisService redisService;
    private final NotificationJournalService journalService;

    @KafkaListener(topics = "inventory-reserved")
    public void reserveInventory(
        @Payload InventoryReservedEvent inventoryReservedEvent,
        @Header("messageId") String messageId
    ) {
        if (redisService.setValue(messageId)) {
            String orderNumber = inventoryReservedEvent.getOrderNumber().toString();
            String email = inventoryReservedEvent.getEmail().toString();
            String firstName = inventoryReservedEvent.getFirstName().toString();
            String lastName = inventoryReservedEvent.getLastName().toString();
            String subject = String.format("Your order #%s has been placed successfully!", orderNumber);

            deliver(orderNumber, "ORDER_PLACED", email, subject, firstName, lastName, String.format(
                """
                    Hi, %s %s,

                    Thank you for your order!

                    Your order #%s has been placed successfully!

                    Best regards,
                    Nikita Dymko
                    """,
                firstName, lastName, orderNumber
            ));
        }
    }

    @KafkaListener(topics = "inventory-rejected")
    public void rejectInventory(
        @Payload InventoryRejectedEvent inventoryRejectedEvent,
        @Header("messageId") String messageId
    ) {
        if (redisService.setValue(messageId)) {
            String orderNumber = inventoryRejectedEvent.getOrderNumber().toString();
            String email = inventoryRejectedEvent.getEmail().toString();
            String firstName = inventoryRejectedEvent.getFirstName().toString();
            String lastName = inventoryRejectedEvent.getLastName().toString();
            String subject = String.format("Your order #%s has been cancelled!", orderNumber);

            deliver(orderNumber, "ORDER_CANCELLED", email, subject, firstName, lastName, String.format(
                """
                    Hi, %s %s,

                    Your order #%s has been cancelled!

                    Best regards,
                    Nikita Dymko
                    """,
                firstName, lastName, orderNumber
            ));
        }
    }

    @KafkaListener(topics = "order-status-changed")
    public void orderStatusChanged(
        @Payload OrderStatusChangedEvent event,
        @Header(value = "messageId", required = false) String messageId
    ) {
        if (messageId != null && !redisService.setValue(messageId)) {
            return;
        }

        String orderNumber = event.getOrderNumber().toString();
        String status = event.getStatus().toString();
        String email = event.getEmail().toString();
        String firstName = event.getFirstName().toString();
        String lastName = event.getLastName().toString();
        String subject = String.format("Order #%s status: %s", orderNumber, status);

        deliver(orderNumber, "STATUS_" + status, email, subject, firstName, lastName, String.format(
            """
                Hi, %s %s,

                The status of your order #%s has changed to %s.

                Best regards,
                Nikita Dymko
                """,
            firstName, lastName, orderNumber, status
        ));

        // Задание на звонок клиенту при подтверждении заказа продавцом
        if ("ACCEPTED".equals(status)) {
            journalService.addCallTask(new CallTask(
                orderNumber,
                null,
                firstName,
                lastName,
                "Подтвердить детали заказа и согласовать оплату",
                LocalDateTime.now(),
                false
            ));
        }
    }

    @KafkaListener(topics = "order-issued")
    public void orderIssued(
        @Payload OrderIssuedEvent event,
        @Header(value = "messageId", required = false) String messageId
    ) {
        if (messageId != null && !redisService.setValue(messageId)) {
            return;
        }

        String orderNumber = event.getOrderNumber().toString();
        String issuedBy = event.getIssuedBy().toString();
        String subject = String.format("Order #%s has been issued", orderNumber);

        journalService.addRecord(new NotificationRecord(
            orderNumber, "ORDER_ISSUED", issuedBy, subject, LocalDateTime.now()));
    }

    /**
     * Отправка уведомления с фиксацией результата.
     *
     * Если отправка по подключенному каналу невозможна, сохраняется запись
     * о неудачной попытке и создается задание на последующий звонок покупателю.
     */
    private void deliver(
        String orderNumber,
        String type,
        String email,
        String subject,
        String firstName,
        String lastName,
        String text
    ) {
        try {
            sendEmail(email, subject, text);
            journalService.addRecord(new NotificationRecord(
                orderNumber, type, email, subject, LocalDateTime.now()));
        } catch (Exception e) {
            journalService.addRecord(new NotificationRecord(
                orderNumber, type + "_FAILED", email, subject, LocalDateTime.now()));
            journalService.addCallTask(new CallTask(
                orderNumber,
                null,
                firstName,
                lastName,
                "Уведомление не отправлено, связаться с покупателем",
                LocalDateTime.now(),
                false
            ));
        }
    }

    private void sendEmail(String to, String subject, String text) {
        MimeMessagePreparator messagePreparator = mimeMessage -> {
            MimeMessageHelper messageHelper = new MimeMessageHelper(mimeMessage);
            messageHelper.setFrom("mitohondriyaa@gmail.com");
            messageHelper.setTo(to);
            messageHelper.setSubject(subject);
            messageHelper.setText(text);
        };

        mailSender.send(messagePreparator);
    }
}
