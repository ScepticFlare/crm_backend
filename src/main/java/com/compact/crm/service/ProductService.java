package com.compact.crm.service;

import com.compact.crm.dto.request.ProductRequest;
import com.compact.crm.entity.Product;
import com.compact.crm.exception.ResourceNotFoundException;
import com.compact.crm.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ProductService {

    private final ProductRepository repository;

    public Product create(ProductRequest request) {

        repository.findByNameIgnoreCase(request.getName())
                .ifPresent(product -> {
                    throw new IllegalArgumentException("Product already exists");
                });

        Product product = Product.builder()
                .name(request.getName().trim())
                .build();

        return repository.save(product);

    }

    public List<Product> getAll() {

        return repository.findAll();

    }

    public Product getById(Long id) {

        return repository.findById(id)
                .orElseThrow(() ->
                        new ResourceNotFoundException("Product not found"));

    }

    public Product update(Long id, ProductRequest request) {

        Product product = getById(id);

        product.setName(request.getName().trim());

        return repository.save(product);

    }

    public void delete(Long id) {

        Product product = getById(id);

        repository.delete(product);

    }

}