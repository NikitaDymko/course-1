package io.github.mitohondriyaa.product.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

@Document("product")
@NoArgsConstructor
@AllArgsConstructor
@Data
@Builder
public class Product {
    @Id
    private String id;
    @Indexed(unique = true)
    private String sku;
    private String name;
    private String description;
    private BigDecimal price;
    private Integer warrantyMonths;
    @Builder.Default
    private Map<String, String> attributes = new LinkedHashMap<>();
}
