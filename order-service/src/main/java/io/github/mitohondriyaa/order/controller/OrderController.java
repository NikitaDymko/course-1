package io.github.mitohondriyaa.order.controller;

import io.github.mitohondriyaa.order.dto.OrderRequest;
import io.github.mitohondriyaa.order.dto.OrderResponse;
import io.github.mitohondriyaa.order.model.AuditLogEntry;
import io.github.mitohondriyaa.order.model.OrderStatus;
import io.github.mitohondriyaa.order.model.PaymentMethod;
import io.github.mitohondriyaa.order.service.AuditService;
import io.github.mitohondriyaa.order.service.OrderService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/order")
@RequiredArgsConstructor
public class OrderController {
    private final OrderService orderService;
    private final AuditService auditService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public OrderResponse placeOrder(
        @Valid @RequestBody OrderRequest orderRequest,
        @AuthenticationPrincipal Jwt jwt
    ) {
        return orderService.placeOrder(orderRequest, jwt);
    }

    @GetMapping("/my")
    @ResponseStatus(HttpStatus.OK)
    public List<OrderResponse> getOrdersByUserId(@AuthenticationPrincipal Jwt jwt) {
        return orderService.getOrdersByUserId(jwt.getSubject());
    }

    @GetMapping("/my/{id}")
    @ResponseStatus(HttpStatus.OK)
    public OrderResponse getOrderForUser(@PathVariable Long id, @AuthenticationPrincipal Jwt jwt) {
        return orderService.getOrderForUser(id, jwt.getSubject());
    }

    @DeleteMapping("/my/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void cancelOrder(@PathVariable Long id, @AuthenticationPrincipal Jwt jwt) {
        orderService.cancelOrder(id, jwt.getSubject());
    }

    @PostMapping("/{id}/accept")
    @ResponseStatus(HttpStatus.OK)
    public OrderResponse acceptOrder(@PathVariable Long id, @AuthenticationPrincipal Jwt jwt) {
        return orderService.acceptOrder(id, jwt);
    }

    @PostMapping("/{id}/reject")
    @ResponseStatus(HttpStatus.OK)
    public OrderResponse rejectOrder(@PathVariable Long id, @AuthenticationPrincipal Jwt jwt) {
        return orderService.rejectOrder(id, jwt);
    }

    @PostMapping("/{id}/await-payment")
    @ResponseStatus(HttpStatus.OK)
    public OrderResponse awaitPayment(@PathVariable Long id, @AuthenticationPrincipal Jwt jwt) {
        return orderService.awaitPayment(id, jwt);
    }

    @PostMapping("/{id}/pay")
    @ResponseStatus(HttpStatus.OK)
    public OrderResponse payOrder(
        @PathVariable Long id,
        @RequestParam PaymentMethod paymentMethod,
        @AuthenticationPrincipal Jwt jwt
    ) {
        return orderService.payOrder(id, paymentMethod, jwt);
    }

    @PostMapping("/{id}/ready")
    @ResponseStatus(HttpStatus.OK)
    public OrderResponse markReady(@PathVariable Long id, @AuthenticationPrincipal Jwt jwt) {
        return orderService.markReady(id, jwt);
    }

    @PostMapping("/{id}/issue")
    @ResponseStatus(HttpStatus.OK)
    public OrderResponse issueOrder(@PathVariable Long id, @AuthenticationPrincipal Jwt jwt) {
        return orderService.issueOrder(id, jwt);
    }

    @GetMapping("/report")
    @ResponseStatus(HttpStatus.OK)
    public List<OrderResponse> getOrdersByStatus(@RequestParam OrderStatus status) {
        return orderService.getOrdersByStatus(status);
    }

    @GetMapping("/audit")
    @ResponseStatus(HttpStatus.OK)
    public List<AuditLogEntry> getAuditLog() {
        return auditService.getAll();
    }

    @GetMapping("/{id}/audit")
    @ResponseStatus(HttpStatus.OK)
    public List<AuditLogEntry> getAuditLogForOrder(@PathVariable Long id) {
        return auditService.getByOrder(id);
    }
}
