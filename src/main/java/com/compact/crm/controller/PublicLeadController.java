package com.compact.crm.controller;

import com.compact.crm.dto.request.PublicLeadRequest;
import com.compact.crm.dto.response.PublicLeadResponse;
import com.compact.crm.entity.Battery;
import com.compact.crm.entity.Industry;
import com.compact.crm.entity.Product;
import com.compact.crm.service.BatteryService;
import com.compact.crm.service.IndustryService;
import com.compact.crm.service.ProductService;
import com.compact.crm.service.PublicLeadService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

// Everything under /api/public/** is the ONLY unauthenticated surface added
// for the public lead form - see config.SecurityConfig's permitAll matcher.
// GET /industries, /products and /batteries are thin, read-only passthroughs
// to the existing (already-active-only) IndustryService/ProductService/
// BatteryService so the public form can populate its dropdowns without
// needing a CRM login; nothing here lets a caller write to Industry/Product/
// Battery, only Lead creation (via PublicLeadService, which itself only ever
// creates - never updates or deletes).
@RestController
@RequestMapping("/api/public")
@RequiredArgsConstructor
public class PublicLeadController {

    private final PublicLeadService publicLeadService;
    private final IndustryService industryService;
    private final ProductService productService;
    private final BatteryService batteryService;

    @PostMapping("/leads")
    public PublicLeadResponse createLead(
            @Valid @RequestBody PublicLeadRequest request,
            HttpServletRequest httpRequest) {

        return publicLeadService.createPublicLead(request, resolveClientIp(httpRequest));
    }

    @GetMapping("/industries")
    public List<Industry> getIndustries() {
        return industryService.getAll();
    }

    @GetMapping("/products")
    public List<Product> getProducts() {
        return productService.getAll();
    }

    @GetMapping("/batteries")
    public List<Battery> getBatteries() {
        return batteryService.getAll();
    }

    // Render (and most PaaS/proxy setups) terminates TLS and forwards to
    // this app behind a proxy, so the real caller IP arrives in
    // X-Forwarded-For rather than as the socket's remote address. Only the
    // first (left-most, original client) entry is trusted; anything else in
    // the chain is proxy-appended.
    private String resolveClientIp(HttpServletRequest request) {

        String forwardedFor = request.getHeader("X-Forwarded-For");

        if (forwardedFor != null && !forwardedFor.isBlank()) {
            return forwardedFor.split(",")[0].trim();
        }

        return request.getRemoteAddr();
    }
}
