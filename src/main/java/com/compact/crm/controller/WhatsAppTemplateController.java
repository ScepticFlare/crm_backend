package com.compact.crm.controller;

import com.compact.crm.dto.request.WhatsAppTemplatePreviewRequest;
import com.compact.crm.dto.request.WhatsAppTemplateRequest;
import com.compact.crm.dto.response.WhatsAppTemplatePreviewResponse;
import com.compact.crm.dto.response.WhatsAppTemplateResponse;
import com.compact.crm.service.WhatsAppTemplateService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

// CRM WhatsApp templates. Authorization lives in WhatsAppTemplateService
// (WHATSAPP_TEMPLATE_MANAGE for authoring, WHATSAPP_SEND to list active ones),
// the same service-level convention as EmailTemplateController.
@RestController
@RequestMapping("/api/whatsapp-templates")
@RequiredArgsConstructor
public class WhatsAppTemplateController {

    private final WhatsAppTemplateService templateService;

    // Active, usable templates - the bulk-send picker.
    @GetMapping
    public List<WhatsAppTemplateResponse> listActive() {
        return templateService.listActive();
    }

    // Admin: every template, active or not.
    @GetMapping("/all")
    public List<WhatsAppTemplateResponse> listAll() {
        return templateService.listAll();
    }

    // Admin: the supported merge fields.
    @GetMapping("/fields")
    public List<Map<String, String>> fields() {
        return templateService.fields();
    }

    @GetMapping("/{id}")
    public WhatsAppTemplateResponse get(@PathVariable Long id) {
        return templateService.get(id);
    }

    @PostMapping
    public WhatsAppTemplateResponse create(@Valid @RequestBody WhatsAppTemplateRequest request) {
        return templateService.create(request);
    }

    @PutMapping("/{id}")
    public WhatsAppTemplateResponse update(@PathVariable Long id, @Valid @RequestBody WhatsAppTemplateRequest request) {
        return templateService.update(id, request);
    }

    @DeleteMapping("/{id}")
    public void deactivate(@PathVariable Long id) {
        templateService.deactivate(id);
    }

    @PutMapping("/{id}/activate")
    public void activate(@PathVariable Long id) {
        templateService.activate(id);
    }

    // Admin editor preview with sample data or a chosen record.
    @PostMapping("/preview")
    public WhatsAppTemplatePreviewResponse preview(@Valid @RequestBody WhatsAppTemplatePreviewRequest request) {
        return templateService.preview(request);
    }
}
