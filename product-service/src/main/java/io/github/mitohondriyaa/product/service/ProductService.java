package io.github.mitohondriyaa.product.service;

import io.github.mitohondriyaa.product.dto.ProductRequest;
import io.github.mitohondriyaa.product.dto.ProductResponse;
import io.github.mitohondriyaa.product.event.ProductCreatedEvent;
import io.github.mitohondriyaa.product.event.ProductDeletedEvent;
import io.github.mitohondriyaa.product.exception.NotFoundException;
import io.github.mitohondriyaa.product.model.Product;
import io.github.mitohondriyaa.product.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ProductService {
    private final ProductRepository productRepository;
    private final RedisCacheService redisCacheService;
    private final RedisCounterService redisCounterService;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    @Value("${cache.threshold}")
    private Integer cacheThreshold;

    public ProductResponse createProduct(ProductRequest productRequest) {
        Product product = Product.builder()
                .sku(productRequest.sku())
                .name(productRequest.name())
                .description(productRequest.description())
                .price(productRequest.price())
                .warrantyMonths(productRequest.warrantyMonths())
                .attributes(normalizeAttributes(productRequest.attributes()))
                .build();

        productRepository.save(product);

        ProductCreatedEvent productCreatedEvent = new ProductCreatedEvent();
        productCreatedEvent.setProductId(product.getId());

        ProducerRecord<String, Object> producerRecord
            = new ProducerRecord<>("product-created", productCreatedEvent);
        producerRecord.headers().add("messageId", UUID.randomUUID().toString().getBytes());

        kafkaTemplate.send(producerRecord);

        return toResponse(product);
    }

    public List<ProductResponse> getAllProducts() {
        return productRepository.findAll()
                .stream()
                .map(this::toResponse)
                .toList();
    }

    public List<ProductResponse> searchBySkuPrefix(String prefix) {
        return productRepository.findBySkuStartingWith(prefix)
                .stream()
                .map(this::toResponse)
                .toList();
    }

    public ProductResponse getProductById(String id) {
        Object cached = redisCacheService.getValue(id);

        if (cached != null) {
            return (ProductResponse) cached;
        }

        Product product = productRepository.findById(id)
            .orElseThrow(() -> new NotFoundException("Product not found"));
        ProductResponse productResponse = toResponse(product);

        Long count = redisCounterService.incrementAndGet(id);

        if (count >= cacheThreshold) {
            redisCacheService.setValue(id, productResponse);
        }

        return productResponse;
    }

    public ProductResponse updateProductById(String id, ProductRequest productRequest) {
        Product product = productRepository.findById(id)
            .orElseThrow(() -> new NotFoundException("Product not found"));

        product.setSku(productRequest.sku());
        product.setName(productRequest.name());
        product.setDescription(productRequest.description());
        product.setPrice(productRequest.price());
        product.setWarrantyMonths(productRequest.warrantyMonths());
        product.setAttributes(normalizeAttributes(productRequest.attributes()));

        productRepository.save(product);

        ProductResponse productResponse = toResponse(product);

        redisCacheService.delete(id);
        redisCounterService.delete(id);

        return productResponse;
    }

    public void deleteProductById(String id) {
        Product product = productRepository.findById(id)
            .orElseThrow(() -> new NotFoundException("Product not found"));

        productRepository.delete(product);

        redisCacheService.delete(id);
        redisCounterService.delete(id);

        ProductDeletedEvent productDeletedEvent = new ProductDeletedEvent();
        productDeletedEvent.setProductId(product.getId());

        ProducerRecord<String, Object> producerRecord
            = new ProducerRecord<>("product-deleted", productDeletedEvent);
        producerRecord.headers().add("messageId", UUID.randomUUID().toString().getBytes());

        kafkaTemplate.send(producerRecord);
    }

    public BigDecimal findPriceById(String id) {
        Product product = productRepository.findById(id)
            .orElseThrow(() -> new NotFoundException("Product not found"));

        return product.getPrice();
    }

    private ProductResponse toResponse(Product product) {
        return new ProductResponse(
            product.getId(),
            product.getSku(),
            product.getName(),
            product.getDescription(),
            product.getPrice(),
            product.getWarrantyMonths(),
            product.getAttributes()
        );
    }

    private Map<String, String> normalizeAttributes(Map<String, String> attributes) {
        Map<String, String> result = new LinkedHashMap<>();

        if (attributes == null) {
            return result;
        }

        attributes.forEach((key, value) -> {
            if (key == null || key.isBlank()) {
                return;
            }

            result.put(key.trim(), value == null ? "" : value.trim());
        });

        return result;
    }
}
