package com.compact.crm.service;

import com.compact.crm.dto.request.BulkWhatsAppRequest;
import com.compact.crm.dto.response.BulkWhatsAppSendResponse;
import com.compact.crm.entity.Employee;
import com.compact.crm.entity.Lead;
import com.compact.crm.entity.Permission;
import com.compact.crm.entity.Role;
import com.compact.crm.entity.RolePermission;
import com.compact.crm.entity.WhatsAppConsent;
import com.compact.crm.entity.WhatsAppTemplate;
import com.compact.crm.enums.BulkEmailTarget;
import com.compact.crm.enums.LeadStatus;
import com.compact.crm.enums.Scope;
import com.compact.crm.enums.WhatsAppConsentStatus;
import com.compact.crm.repository.ActivityLogRepository;
import com.compact.crm.repository.EmployeeRepository;
import com.compact.crm.repository.LeadRepository;
import com.compact.crm.repository.PermissionRepository;
import com.compact.crm.repository.RolePermissionRepository;
import com.compact.crm.repository.RoleRepository;
import com.compact.crm.repository.WhatsAppBulkBatchRepository;
import com.compact.crm.repository.WhatsAppConsentRepository;
import com.compact.crm.repository.WhatsAppSendLogRepository;
import com.compact.crm.repository.WhatsAppTemplateRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Real commits and real threads (no surrounding test transaction) with a slow FAKE
 * WhatsApp provider: double clicks and racing batches. Never sends a real message.
 */
@SpringBootTest(properties = "spring.jpa.properties.hibernate.enable_lazy_load_no_trans=true")
class BulkWhatsAppConcurrencyTest {

    @MockitoBean private WhatsAppClient client;

    @Autowired private BulkWhatsAppService bulk;
    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private PermissionRepository permissionRepository;
    @Autowired private RolePermissionRepository rolePermissionRepository;
    @Autowired private LeadRepository leadRepository;
    @Autowired private WhatsAppSendLogRepository sendLogRepository;
    @Autowired private WhatsAppBulkBatchRepository batchRepository;
    @Autowired private WhatsAppTemplateRepository templateRepository;
    @Autowired private WhatsAppConsentRepository consentRepository;
    @Autowired private ActivityLogRepository activityLogRepository;

    private Role role;
    private Permission permission;
    private boolean createdPermission;
    private RolePermission grant;
    private Employee emp;
    private WhatsAppTemplate template;
    private final List<Lead> leads = new ArrayList<>();
    private final List<String> phones = new ArrayList<>();
    private final AtomicInteger providerCalls = new AtomicInteger();
    private final AtomicInteger messageIds = new AtomicInteger();
    private String tag;

    @BeforeEach
    void setUp() {

        tag = UUID.randomUUID().toString().substring(0, 8);

        role = roleRepository.save(Role.builder().name("WACONC-" + tag).rank(10).build());

        permission = permissionRepository.findAll().stream()
                .filter(p -> "WHATSAPP_SEND".equals(p.getCode())).findFirst().orElse(null);

        if (permission == null) {
            permission = permissionRepository.save(Permission.builder().code("WHATSAPP_SEND").build());
            createdPermission = true;
        }

        grant = rolePermissionRepository.save(
                RolePermission.builder().role(role).permission(permission).scope(Scope.OWN).build());

        emp = employeeRepository.save(Employee.builder().name("WaConc " + tag).email("waconc-" + tag + "@t.test")
                .phone("97" + String.format("%08d", Math.abs(tag.hashCode()) % 100_000_000)).password("x").role(role).build());

        template = templateRepository.save(WhatsAppTemplate.builder().name("Conc " + tag)
                .body("Hi {{companyName}}").version(1).isActive(true).build());

        when(client.isConfigured()).thenReturn(true);
        when(client.isReady()).thenReturn(true);
        when(client.sessionStatus()).thenReturn("WORKING");
        when(client.sendText(anyString(), anyString())).thenAnswer(inv -> {
            providerCalls.incrementAndGet();
            Thread.sleep(250);                      // a slow provider widens every race window
            return new WhatsAppClient.SendOutcome(WhatsAppClient.Result.ACCEPTED,
                    "conc-" + tag + "-" + messageIds.incrementAndGet(), null, null, false);
        });
    }

    @AfterEach
    void cleanUp() {

        sendLogRepository.deleteAll(sendLogRepository.findAll().stream()
                .filter(l -> l.getSentByEmployeeId() != null && l.getSentByEmployeeId().equals(emp.getId())).toList());
        batchRepository.deleteAll(batchRepository.findAll().stream()
                .filter(b -> b.getOwnerEmployeeId().equals(emp.getId())).toList());
        activityLogRepository.deleteAll(activityLogRepository.findAll().stream()
                .filter(a -> a.getEmployee() != null && emp.getId().equals(a.getEmployee().getId())).toList());
        consentRepository.deleteAll(consentRepository.findByPhoneE164In(phones));
        leadRepository.deleteAll(leads);
        templateRepository.delete(template);
        employeeRepository.delete(emp);
        rolePermissionRepository.delete(grant);
        roleRepository.delete(role);

        if (createdPermission) {
            permissionRepository.delete(permission);
        }

        SecurityContextHolder.clearContext();
    }

    @Test
    void aDoubleClick_simultaneousRequestsForTheSameChunk_messageEachRecipientAtMostOnce() throws Exception {

        List<Long> ids = newLeads(5, false);
        String batch = createBatch(ids);

        List<BulkWhatsAppSendResponse> responses = runConcurrently(4, () -> bulk.send(chunk(batch, ids)));

        assertThat(providerCalls.get()).isEqualTo(5);

        long fresh = responses.stream().flatMap(r -> r.getResults().stream())
                .filter(r -> "ACCEPTED".equals(r.getOutcome()) && !r.isAlreadyProcessed()).count();
        assertThat(fresh).isEqualTo(5);

        authenticate();
        BulkWhatsAppSendResponse view = bulk.results(batch);
        assertThat(view.getAccepted()).isEqualTo(5);
        assertThat(view.getInProgress()).isZero();
    }

    @Test
    void twoNewBatchesRacingForTheSameNumbers_messageEachNumberOnce() throws Exception {

        List<Long> ids = newLeads(4, false);

        String batchA = createBatch(ids);
        String batchB = createBatch(ids);

        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);

        Future<BulkWhatsAppSendResponse> a = pool.submit(() -> as(go, () -> bulk.send(chunk(batchA, ids))));
        Future<BulkWhatsAppSendResponse> b = pool.submit(() -> as(go, () -> bulk.send(chunk(batchB, ids))));

        go.countDown();
        a.get();
        b.get();
        pool.shutdown();

        assertThat(providerCalls.get()).isEqualTo(4);

        authenticate();
        assertThat(bulk.results(batchA).getAccepted() + bulk.results(batchB).getAccepted()).isEqualTo(4);
    }

    @Test
    void oneNumberOnTwoRecordsOfTheSameBatch_sentInParallelChunks_isMessagedOnce() throws Exception {

        List<Long> ids = newLeads(2, true);   // both records share one number
        String batch = createBatch(ids);

        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);

        Future<BulkWhatsAppSendResponse> a = pool.submit(() -> as(go, () -> bulk.send(chunk(batch, List.of(ids.get(0))))));
        Future<BulkWhatsAppSendResponse> b = pool.submit(() -> as(go, () -> bulk.send(chunk(batch, List.of(ids.get(1))))));

        go.countDown();
        a.get();
        b.get();
        pool.shutdown();

        assertThat(providerCalls.get()).isEqualTo(1);
    }

    // ----------------------------------------------------------------- helpers

    private List<Long> newLeads(int count, boolean sameNumber) {

        List<Long> ids = new ArrayList<>();
        int base = Math.abs((tag + count + sameNumber).hashCode()) % 10_000_000;

        for (int i = 0; i < count; i++) {

            String national = "6" + String.format("%09d", sameNumber ? base : base * 10L + i);
            String phone = "91" + national;

            Lead lead = leadRepository.save(Lead.builder().companyName("WaConc " + tag + " " + i).contactPerson("C")
                    .phone(national).leadStatus(LeadStatus.VALID).assignedEmployee(emp).build());

            leads.add(lead);
            ids.add(lead.getId());

            if (!phones.contains(phone)) {
                phones.add(phone);
                consentRepository.save(WhatsAppConsent.builder().phoneE164(phone).status(WhatsAppConsentStatus.OPTED_IN)
                        .source("TEST").evidence("concurrency test").consentedAt(LocalDateTime.now().minusDays(1)).build());
            }
        }

        return ids;
    }

    private String createBatch(List<Long> ids) {
        authenticate();
        BulkWhatsAppRequest r = new BulkWhatsAppRequest();
        r.setTarget(BulkEmailTarget.LEAD);
        r.setIds(ids);
        r.setTemplateId(template.getId());
        return bulk.createBatch(r).getBatchId();
    }

    private BulkWhatsAppRequest chunk(String batchId, List<Long> ids) {
        BulkWhatsAppRequest r = new BulkWhatsAppRequest();
        r.setBatchId(batchId);
        r.setIds(ids);
        return r;
    }

    private <T> List<T> runConcurrently(int threads, Callable<T> work) throws Exception {

        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<T>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> as(go, work)));
        }

        go.countDown();

        List<T> out = new ArrayList<>();
        for (Future<T> f : futures) {
            out.add(f.get());
        }

        pool.shutdown();
        return out;
    }

    private <T> T as(CountDownLatch go, Callable<T> work) throws Exception {
        authenticate();
        try {
            go.await();
            return work.call();
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private void authenticate() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(emp.getEmail(), null, List.of()));
    }
}
