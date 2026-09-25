package com.compact.crm.service;

import com.compact.crm.dto.request.ProductRequest;
import com.compact.crm.entity.Product;
import com.compact.crm.exception.ResourceNotFoundException;
import com.compact.crm.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@RequiredArgsConstructor
public class ProductService {

    private final ProductRepository repository;

    @CacheEvict(cacheNames = "products", allEntries = true)
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

    // Cached: this list is fetched on nearly every Lead/Opportunity page
    // load (see pages/Leads.jsx, AddLead.jsx, etc.) but changes only when
    // an admin adds/edits/(de)activates a product - see the @CacheEvict
    // methods below, which invalidate it on exactly those writes.
    @Cacheable(cacheNames = "products")
    public List<Product> getAll() {

        return repository.findByIsActiveTrue();

    }

    public List<Product> getAllIncludingInactive() {

        return repository.findAll();

    }

    public Product getById(Long id) {

        return repository.findById(id)
                .orElseThrow(() ->
                        new ResourceNotFoundException("Product not found"));

    }

    @CacheEvict(cacheNames = "products", allEntries = true)
    public Product update(Long id, ProductRequest request) {

        Product product = getById(id);

        product.setName(request.getName().trim());

        return repository.save(product);

    }

    @CacheEvict(cacheNames = "products", allEntries = true)
    public void deactivate(Long id) {

        Product product = getById(id);

        product.setIsActive(false);

        repository.save(product);

    }

    @CacheEvict(cacheNames = "products", allEntries = true)
    public void activate(Long id) {

        Product product = getById(id);

        product.setIsActive(true);

        repository.save(product);

    }

}
