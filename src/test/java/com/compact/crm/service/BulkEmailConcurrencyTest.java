package com.compact.crm.service;

import com.compact.crm.dto.request.BulkEmailRequest;
import com.compact.crm.dto.response.BulkEmailBatchResponse;
import com.compact.crm.dto.response.BulkEmailSendResponse;
import com.compact.crm.entity.Employee;
import com.compact.crm.entity.Lead;
import com.compact.crm.entity.Permission;
import com.compact.crm.entity.Role;
import com.compact.crm.entity.RolePermission;
import com.compact.crm.enums.BulkEmailTarget;
import com.compact.crm.enums.LeadStatus;
import com.compact.crm.enums.Scope;
import com.compact.crm.repository.ActivityLogRepository;
import com.compact.crm.repository.EmailBulkBatchRepository;
import com.compact.crm.repository.EmailSendLogRepository;
import com.compact.crm.repository.EmployeeRepository;
import com.compact.crm.repository.LeadRepository;
import com.compact.crm.repository.PermissionRepository;
import com.compact.crm.repository.RolePermissionRepository;
import com.compact.crm.repository.RoleRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Real commits and real threads (no surrounding test transaction) with a slow FAKE
 * email transport: the races the single-threaded tests cannot reach. Never sends a
 * real email.
 */
@SpringBootTest(properties = "spring.jpa.properties.hibernate.enable_lazy_load_no_trans=true")
class BulkEmailConcurrencyTest {

    @MockitoBean private BrevoEmailClient brevo;

    @Autowired private BulkEmailService bulk;
    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private PermissionRepository permissionRepository;
    @Autowired private RolePermissionRepository rolePermissionRepository;
    @Autowired private LeadRepository leadRepository;
    @Autowired private EmailSendLogRepository sendLogRepository;
    @Autowired private EmailBulkBatchRepository batchRepository;
    @Autowired private ActivityLogRepository activityLogRepository;

    private Role role;
    private Permission permission;
    private boolean createdPermission;
    private RolePermission grant;
    private Employee emp;
    private final List<Lead> leads = new ArrayList<>();
    private final List<String> batchIds = new ArrayList<>();
    private final AtomicInteger providerCalls = new AtomicInteger();

    @BeforeEach
    void setUp() {

        String tag = UUID.randomUUID().toString().substring(0, 8);

        role = roleRepository.save(Role.builder().name("CONC-" + tag).rank(10).build());

        permission = permissionRepository.findAll().stream()
                .filter(p -> "EMAIL_SEND".equals(p.getCode())).findFirst().orElse(null);

        if (permission == null) {
            permission = permissionRepository.save(Permission.builder().code("EMAIL_SEND").build());
            createdPermission = true;
        }

        grant = rolePermissionRepository.save(
                RolePermission.builder().role(role).permission(permission).scope(Scope.OWN).build());

        emp = employeeRepository.save(Employee.builder().name("Conc " + tag).email("conc-" + tag + "@t.test")
                .phone("98" + String.format("%08d", Math.abs(tag.hashCode()) % 100_000_000)).password("x").role(role).build());

        when(brevo.trySend(any(), any(), any(), any())).thenAnswer(inv -> {
            providerCalls.incrementAndGet();
            Thread.sleep(250);                      // a slow provider widens every race window
            return new BrevoEmailClient.SendOutcome(BrevoEmailClient.Result.ACCEPTED, null, null);
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
        leadRepository.deleteAll(leads);
        employeeRepository.delete(emp);
        rolePermissionRepository.delete(grant);
        roleRepository.delete(role);

        if (createdPermission) {
            permissionRepository.delete(permission);
        }

        SecurityContextHolder.clearContext();
    }

    // ----------------------------------------------------------------- tests

    @Test
    void simultaneousRequestsForTheSameChunk_sendEachRecipientAtMostOnce() throws Exception {

        List<Long> ids = newLeads(6, "same");
        String batch = createBatch(ids);

        BulkEmailRequest chunk = chunk(batch, ids);

        List<BulkEmailSendResponse> responses = runConcurrently(4, () -> bulk.send(chunk));

        assertThat(providerCalls.get()).isEqualTo(6);

        // exactly one request accepted each recipient freshly; the others saw it as in progress / handled
        long fresh = responses.stream().flatMap(r -> r.getResults().stream())
                .filter(r -> "ACCEPTED".equals(r.getOutcome()) && !r.isAlreadyProcessed()).count();
        assertThat(fresh).isEqualTo(6);

        BulkEmailSendResponse view = bulk.results(batch);
        assertThat(view.getAccepted()).isEqualTo(6);
        assertThat(view.getInProgress()).isZero();
    }

    @Test
    void twoNewBatchesRacingForTheSameAddresses_emailEachAddressOnce() throws Exception {

        for (int round = 0; round < 3; round++) {

            List<Long> ids = newLeads(5, "race" + round);

            String batchA = createBatch(ids);
            String batchB = createBatch(ids);

            int before = providerCalls.get();

            CountDownLatch go = new CountDownLatch(1);
            ExecutorService pool = Executors.newFixedThreadPool(2);

            Future<BulkEmailSendResponse> a = pool.submit(() -> as(emp, go, () -> bulk.send(chunk(batchA, ids))));
            Future<BulkEmailSendResponse> b = pool.submit(() -> as(emp, go, () -> bulk.send(chunk(batchB, ids))));

            go.countDown();
            a.get();
            b.get();
            pool.shutdown();

            assertThat(providerCalls.get() - before).as("provider calls in round " + round).isEqualTo(5);

            // whatever the interleaving, the two batches together never emailed an address twice
            int acceptedTotal = bulk.results(batchA).getAccepted() + bulk.results(batchB).getAccepted();
            assertThat(acceptedTotal).isEqualTo(5);
        }
    }

    @Test
    void concurrentBatchCreation_neverCollides() throws Exception {

        List<Long> ids = newLeads(2, "create");

        List<BulkEmailBatchResponse> created = runConcurrently(8, () -> bulk.createBatch(request(ids)));

        Set<String> distinct = new HashSet<>();
        created.forEach(c -> {
            distinct.add(c.getBatchId());
            batchIds.add(c.getBatchId());
        });

        assertThat(distinct).hasSize(8);
        assertThat(batchRepository.findAll().stream().filter(b -> distinct.contains(b.getBatchId())).count()).isEqualTo(8);
    }

    @Test
    void oneAddressOnTwoRecordsOfTheSameBatch_sentInParallelChunks_isEmailedOnce() throws Exception {

        List<Long> ids = newLeads(2, "shared");

        // both records carry the same address
        for (Lead lead : leads) {
            lead.setEmail("shared-" + ids.get(0) + "@conc.test");
            leadRepository.save(lead);
        }

        String batch = createBatch(ids);

        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);

        Future<BulkEmailSendResponse> first = pool.submit(() -> as(emp, go, () -> bulk.send(chunk(batch, List.of(ids.get(0))))));
        Future<BulkEmailSendResponse> second = pool.submit(() -> as(emp, go, () -> bulk.send(chunk(batch, List.of(ids.get(1))))));

        go.countDown();
        first.get();
        second.get();
        pool.shutdown();

        assertThat(providerCalls.get()).isEqualTo(1);
        assertThat(bulk.results(batch).getAccepted()).isEqualTo(1);
        assertThat(bulk.results(batch).getExcluded()).isEqualTo(1);
    }

    // --------------------------------------------------------------- helpers

    private List<Long> newLeads(int count, String tag) {

        List<Long> ids = new ArrayList<>();

        for (int i = 0; i < count; i++) {
            Lead lead = leadRepository.save(Lead.builder()
                    .companyName("Conc " + tag + " " + i).contactPerson("P" + i)
                    .email(UUID.randomUUID() + "@conc.test").phone("9000000000")
                    .leadStatus(LeadStatus.VALID).assignedEmployee(emp).build());
            leads.add(lead);
            ids.add(lead.getId());
        }

        return ids;
    }

    private BulkEmailRequest request(List<Long> ids) {
        BulkEmailRequest r = new BulkEmailRequest();
        r.setTarget(BulkEmailTarget.LEAD);
        r.setIds(ids);
        r.setSubject("Hi");
        r.setBody("Dear {{contactPerson}}");
        return r;
    }

    private String createBatch(List<Long> ids) {
        authenticate(emp);
        String id = bulk.createBatch(request(ids)).getBatchId();
        batchIds.add(id);
        return id;
    }

    private BulkEmailRequest chunk(String batchId, List<Long> ids) {
        BulkEmailRequest r = new BulkEmailRequest();
        r.setBatchId(batchId);
        r.setIds(ids);
        return r;
    }

    private void authenticate(Employee actor) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(actor.getEmail(), null, List.of()));
    }

    private <T> T as(Employee actor, CountDownLatch go, Callable<T> call) throws Exception {
        authenticate(actor);
        go.await();
        return call.call();
    }

    private <T> List<T> runConcurrently(int threads, Callable<T> call) throws Exception {

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();

        for (int t = 0; t < threads; t++) {
            futures.add(pool.submit(() -> as(emp, go, call)));
        }

        go.countDown();

        List<T> out = new ArrayList<>();

        for (Future<T> f : futures) {
            out.add(f.get());
        }

        pool.shutdown();

        return out;
    }
}
