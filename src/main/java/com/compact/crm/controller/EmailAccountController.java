package com.compact.crm.controller;

import com.compact.crm.dto.response.MailboxStatusResponse;
import com.compact.crm.service.mail.MailboxConnectionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.Map;

// The signed-in employee's OWN sending mailbox (Gmail / Microsoft 365). No
// endpoint takes an employee id: every call acts on the caller only, so one
// employee can never connect, read or disconnect another's mailbox.
@RestController
@RequestMapping("/api/email-accounts")
@RequiredArgsConstructor
public class EmailAccountController {

    private final MailboxConnectionService connectionService;

    @GetMapping("/me")
    public MailboxStatusResponse status() {
        return connectionService.status();
    }

    // Starts the OAuth flow; the browser then navigates to authorizationUrl.
    @PostMapping("/connect/{provider}")
    public Map<String, String> connect(@PathVariable String provider) {
        return Map.of("authorizationUrl", connectionService.startConnection(provider));
    }

    @DeleteMapping("/me")
    public MailboxStatusResponse disconnect() {
        return connectionService.disconnect();
    }

    // The provider's redirect after consent. Unauthenticated by nature (a
    // top-level browser navigation from Google/Microsoft carries no CRM token):
    // the single-use state stored at /connect is what identifies the employee.
    // Answers only with a redirect to the CRM's Email Account page.
    @GetMapping("/oauth/{provider}/callback")
    public ResponseEntity<Void> callback(@PathVariable String provider,
                                         @RequestParam(required = false) String code,
                                         @RequestParam(required = false) String state,
                                         @RequestParam(required = false) String error) {

        String target = connectionService.handleCallback(provider, code, state, error);

        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(target))
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header("Referrer-Policy", "no-referrer")
                .build();
    }
}
