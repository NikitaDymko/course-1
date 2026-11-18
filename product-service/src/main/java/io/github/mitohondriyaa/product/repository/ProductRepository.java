package io.github.mitohondriyaa.product.repository;

import io.github.mitohondriyaa.product.model.Product;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.List;
import java.util.Optional;

public interface ProductRepository extends MongoRepository<Product, String> {
    Optional<Product> findBySku(String sku);

    List<Product> findBySkuStartingWith(String prefix);
}
