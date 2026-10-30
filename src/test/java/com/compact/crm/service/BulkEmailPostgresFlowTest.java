package com.compact.crm.service;

import com.compact.crm.dto.request.BulkEmailRequest;
import com.compact.crm.dto.request.EmailSuppressionRequest;
import com.compact.crm.dto.response.BulkEmailSendResponse;
import com.compact.crm.entity.Employee;
import com.compact.crm.entity.Lead;
import com.compact.crm.enums.BulkEmailTarget;
import com.compact.crm.enums.LeadStatus;
import com.compact.crm.enums.SuppressionReason;
import com.compact.crm.repository.EmployeeRepository;
import com.compact.crm.repository.LeadRepository;
import com.compact.crm.repository.RoleRepository;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.init.ScriptUtils;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.sql.Connection;
import java.sql.Statement;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * The bulk-email service on REAL PostgreSQL, migrated by the real Flyway chain
 * (V2..V13) so the ADMIN / EMPLOYEE roles and their EMAIL_SEND /
 * EMAIL_SUPPRESSION_MANAGE grants come from the migrations, not from test fixtures.
 * Same gating and warning as the other PostgreSQL tests: CRM_TEST_PG_URL, and the
 * public schema is DROPPED. A FAKE email transport is used; nothing is ever sent.
 */
@EnabledIfEnvironmentVariable(named = "CRM_TEST_PG_URL", matches = ".+")
@SpringBootTest(properties = {
        "spring.datasource.url=${CRM_TEST_PG_URL}",
        "spring.datasource.username=${CRM_TEST_PG_USER:postgres}",
        "spring.datasource.password=${CRM_TEST_PG_PASSWORD:}",
        "spring.datasource.driver-class-name=org.postgresql.Driver",
        "spring.jpa.database-platform=org.hibernate.dialect.PostgreSQLDialect",
        "spring.jpa.hibernate.ddl-auto=update",
        "spring.flyway.enabled=true",
        "spring.flyway.baseline-on-migrate=true",
        "spring.flyway.baseline-version=1",
        "spring.jpa.properties.hibernate.enable_lazy_load_no_trans=true",
})
class BulkEmailPostgresFlowTest {

    @BeforeAll
    static void freshBaseline() throws Exception {

        DriverManagerDataSource ds = new DriverManagerDataSource(
                System.getenv("CRM_TEST_PG_URL"),
                System.getenv().getOrDefault("CRM_TEST_PG_USER", "postgres"),
                System.getenv().getOrDefault("CRM_TEST_PG_PASSWORD", ""));

        try (Connection c = ds.getConnection(); Statement st = c.createStatement()) {
            st.execute("DROP SCHEMA public CASCADE");
            st.execute("CREATE SCHEMA public");
            ScriptUtils.executeSqlScript(c, new ClassPathResource("pg-baseline-v1.sql"));
        }
    }

    @MockitoBean private BrevoEmailClient brevo;

    @Autowired private BulkEmailService bulk;
    @Autowired private EmailSuppressionService suppression;
    @Autowired private EmployeeRepository employeeRepository;
    @Autowired private RoleRepository roleRepository;
    @Autowired private LeadRepository leadRepository;

    private final AtomicInteger providerCalls = new AtomicInteger();
    private int phone = 0;

    private Employee employee(String roleName) {

        String tag = UUID.randomUUID().toString().substring(0, 8);

        return employeeRepository.save(Employee.builder().name(roleName + " " + tag)
                .email(roleName.toLowerCase() + "-" + tag + "@t.test")
                .phone("97" + String.format("%08d", ++phone + (int) (System.nanoTime() % 1000) * 1000))
                .password("x").role(roleRepository.findByNameIgnoreCase(roleName).orElseThrow()).build());
    }

    private List<Long> leads(Employee owner, int count, String tag) {

        List<Long> ids = new ArrayList<>();

        for (int i = 0; i < count; i++) {
            ids.add(leadRepository.save(Lead.builder().companyName(tag + " " + i).contactPerson("P" + i)
                    .email(UUID.randomUUID() + "@pg.test").phone("9000000000")
                    .leadStatus(LeadStatus.VALID).assignedEmployee(owner).build()).getId());
        }

        return ids;
    }

    private BulkEmailRequest create(List<Long> ids) {
        BulkEmailRequest r = new BulkEmailRequest();
        r.setTarget(BulkEmailTarget.LEAD);
        r.setIds(ids);
        r.setSubject("Hi");
        r.setBody("Dear {{contactPerson}}");
        return r;
    }

    private BulkEmailRequest chunk(String batch, List<Long> ids) {
        BulkEmailRequest r = new BulkEmailRequest();
        r.setBatchId(batch);
        r.setIds(ids);
        return r;
    }

    private void login(Employee e) {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(e.getEmail(), null, List.of()));
    }

    private void slowProvider() {
        when(brevo.trySend(any(), any(), any(), any())).thenAnswer(inv -> {
            providerCalls.incrementAndGet();
            Thread.sleep(200);
            return new BrevoEmailClient.SendOutcome(BrevoEmailClient.Result.ACCEPTED, null, null);
        });
    }

    private <T> List<T> parallel(Employee who, int threads, Callable<T> call) throws Exception {

        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();

        for (int t = 0; t < threads; t++) {
            futures.add(pool.submit(() -> {
                login(who);
                go.await();
                return call.call();
            }));
        }

        go.countDown();

        List<T> out = new ArrayList<>();
        for (Future<T> f : futures) {
            out.add(f.get());
        }
        pool.shutdown();
        return out;
    }

    @Test
    void sendFlow_withTheSeededEmployeeRole() {

        Employee emp = employee("EMPLOYEE");
        Employee other = employee("EMPLOYEE");
        login(emp);

        List<Long> mine = leads(emp, 2, "Mine");
        List<Long> theirs = leads(other, 1, "Theirs");

        List<Long> all = new ArrayList<>(mine);
        all.addAll(theirs);

        String failing = leadRepository.findById(mine.get(1)).orElseThrow().getEmail();

        when(brevo.trySend(any(), any(), any(), any()))
                .thenReturn(new BrevoEmailClient.SendOutcome(BrevoEmailClient.Result.ACCEPTED, null, null));
        when(brevo.trySend(eq(failing), any(), any(), any()))
                .thenReturn(new BrevoEmailClient.SendOutcome(BrevoEmailClient.Result.FAILED, 400, "HTTP 400"))
                .thenReturn(new BrevoEmailClient.SendOutcome(BrevoEmailClient.Result.ACCEPTED, null, null));

        String batch = bulk.createBatch(create(all)).getBatchId();

        BulkEmailSendResponse first = bulk.send(chunk(batch, all));
        assertThat(first.getAccepted()).isEqualTo(1);
        assertThat(first.getFailed()).isEqualTo(1);
        assertThat(first.getExcluded()).isEqualTo(1);

        BulkEmailSendResponse retry = bulk.send(chunk(batch, all));
        assertThat(retry.getAccepted()).isEqualTo(2);

        BulkEmailSendResponse view = bulk.results(batch);
        assertThat(view.getResults()).hasSize(3);
        assertThat(view.getAccepted()).isEqualTo(2);
        assertThat(view.getExcluded()).isEqualTo(1);

        // another employee can neither read nor extend it; a new batch cannot resend the accepted ones
        login(other);
        assertThatThrownBy(() -> bulk.results(batch)).isInstanceOf(AccessDeniedException.class);

        login(emp);
        BulkEmailSendResponse again = bulk.send(chunk(bulk.createBatch(create(mine)).getBatchId(), mine));
        assertThat(again.getAccepted()).isZero();
        assertThat(again.getResults()).allMatch(r -> "RECENTLY_EMAILED".equals(r.getExclusionCode()));
    }

    @Test
    void concurrency_sameChunk_twoRacingBatches_andBatchCreation() throws Exception {

        slowProvider();

        Employee emp = employee("EMPLOYEE");
        login(emp);

        // 4 simultaneous identical chunk requests
        List<Long> ids = leads(emp, 6, "Same");
        String batch = bulk.createBatch(create(ids)).getBatchId();
        int before = providerCalls.get();

        parallel(emp, 4, () -> bulk.send(chunk(batch, ids)));
        assertThat(providerCalls.get() - before).isEqualTo(6);
        assertThat(bulk.results(batch).getAccepted()).isEqualTo(6);

        // two NEW batches for the same people at the same instant
        for (int round = 0; round < 3; round++) {

            List<Long> people = leads(emp, 5, "Race" + round);
            String a = bulk.createBatch(create(people)).getBatchId();
            String b = bulk.createBatch(create(people)).getBatchId();

            int calls = providerCalls.get();

            ExecutorService pool = Executors.newFixedThreadPool(2);
            CountDownLatch go = new CountDownLatch(1);

            Future<BulkEmailSendResponse> fa = pool.submit(() -> { login(emp); go.await(); return bulk.send(chunk(a, people)); });
            Future<BulkEmailSendResponse> fb = pool.submit(() -> { login(emp); go.await(); return bulk.send(chunk(b, people)); });

            go.countDown();
            fa.get();
            fb.get();
            pool.shutdown();

            assertThat(providerCalls.get() - calls).as("round " + round).isEqualTo(5);
            assertThat(bulk.results(a).getAccepted() + bulk.results(b).getAccepted()).isEqualTo(5);
        }

        // concurrent batch creation never collides
        List<Long> few = leads(emp, 2, "Create");
        Set<String> created = new HashSet<>();
        parallel(emp, 8, () -> bulk.createBatch(create(few)).getBatchId()).forEach(created::add);
        assertThat(created).hasSize(8);
    }

    @Test
    void suppression_adminOnly_enforcedForSharedAddresses_andAudited() {

        Employee admin = employee("ADMIN");
        Employee emp = employee("EMPLOYEE");

        List<Long> ids = leads(emp, 2, "Shared");
        String shared = "shared-" + UUID.randomUUID() + "@pg.test";

        for (Long id : ids) {
            Lead lead = leadRepository.findById(id).orElseThrow();
            lead.setEmail(id.equals(ids.get(0)) ? shared.toUpperCase() : "  " + shared + " ");
            leadRepository.save(lead);
        }

        when(brevo.trySend(any(), any(), any(), any()))
                .thenReturn(new BrevoEmailClient.SendOutcome(BrevoEmailClient.Result.ACCEPTED, null, null));

        // the ADMIN role seeded by V13 may manage the list; the EMPLOYEE role may not
        login(emp);
        assertThatThrownBy(() -> suppression.list(null, 0, 10)).isInstanceOf(AccessDeniedException.class);
        assertThatThrownBy(() -> suppression.add(new EmailSuppressionRequest(shared, SuppressionReason.OPT_OUT, null)))
                .isInstanceOf(AccessDeniedException.class);

        login(admin);
        Long suppressionId = suppression.add(new EmailSuppressionRequest(" " + shared.toUpperCase() + " ", SuppressionReason.OPT_OUT, "asked")).getId();

        login(emp);
        BulkEmailSendResponse sent = bulk.send(chunk(bulk.createBatch(create(ids)).getBatchId(), ids));

        assertThat(sent.getAccepted()).isZero();
        assertThat(sent.getResults()).allMatch(r -> "SUPPRESSED".equals(r.getExclusionCode()));

        assertThatThrownBy(() -> suppression.remove(suppressionId, "I would like to email them")).isInstanceOf(AccessDeniedException.class);

        login(admin);
        suppression.remove(suppressionId, "Customer re-subscribed, ticket 4711");

        assertThat(suppression.audit(0, 10).getItems().get(0).getAction()).isEqualTo("REMOVED");

        login(emp);
        BulkEmailSendResponse afterRemoval = bulk.send(chunk(bulk.createBatch(create(ids)).getBatchId(), ids));
        assertThat(afterRemoval.getAccepted()).isEqualTo(1);                       // one email per shared address
        assertThat(afterRemoval.getResults().stream().filter(r -> "DUPLICATE_EMAIL".equals(r.getExclusionCode())).count())
                .isEqualTo(1);
    }
}
