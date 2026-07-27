package com.compact.crm.controller;

import com.compact.crm.dto.request.ProductRequest;
import com.compact.crm.entity.Product;
import com.compact.crm.service.ProductService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/products")
@RequiredArgsConstructor
public class ProductController {

    private final ProductService service;

    @PostMapping
    public Product create(
            @Valid @RequestBody ProductRequest request) {

        return service.create(request);

    }

    @GetMapping
    public List<Product> getAll() {

        return service.getAll();

    }

    @GetMapping("/{id}")
    public Product getById(@PathVariable Long id) {

        return service.getById(id);

    }

    @PutMapping("/{id}")
    public Product update(
            @PathVariable Long id,
            @Valid @RequestBody ProductRequest request) {

        return service.update(id, request);

    }

    @DeleteMapping("/{id}")
    public void delete(@PathVariable Long id) {

        service.delete(id);

    }

}