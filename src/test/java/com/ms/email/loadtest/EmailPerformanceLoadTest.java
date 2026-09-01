package com.ms.email.loadtest;

import com.ms.email.Repository.EmailRepository;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Teste de carga comparativo com SWEEP de concorrências: para cada nível de
 * concorrência dispara o mesmo volume de requisições contra os endpoints
 * SÍNCRONO e ASSÍNCRONO, medindo latência (média/p50/p95/p99), throughput,
 * tempo total (wall) e — no async — o tempo de drenagem da fila.
 *
 * Objetivo: identificar o PONTO DE VIRADA (a partir de qual concorrência o
 * async passa a compensar) e onde os dois modos se equivalem.
 *
 * Garantias:
 *  - SMTP 100% mockado ({@link LatencyMockMailSender}) — nenhum e-mail real.
 *  - RabbitMQ externo via -Dloadtest.rabbitmq.addresses (async pulado se ausente).
 *  - Nº de consumers configurável via -Dloadtest.consumers (default 1).
 *
 * Parâmetros (-D):
 *   load.requests            total de requisições por cenário (default 1000)
 *   load.concurrency.sweep   lista de concorrências (default "1,5,10,25,50,100")
 *   test.smtp.latency.ms     latência SMTP simulada (default 150)
 *   loadtest.consumers       consumers concorrentes drenando a fila (default 1)
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class EmailPerformanceLoadTest {

    private static final int TOTAL_REQUESTS = Integer.getInteger("load.requests", 1000);
    private static final long SMTP_LATENCY_MS = Long.getLong("test.smtp.latency.ms", 150L);
    private static final int CONSUMERS = Integer.getInteger("loadtest.consumers", 1);
    private static final List<Integer> SWEEP = parseSweep(
            System.getProperty("load.concurrency.sweep", "1,5,10,25,50,100"));

    private static final String RABBIT_ADDRESSES = resolveRabbitAddresses();

    private static List<Integer> parseSweep(String s) {
        return Arrays.stream(s.split(","))
                .map(String::trim).filter(x -> !x.isEmpty())
                .map(Integer::parseInt).collect(Collectors.toList());
    }

    private static String resolveRabbitAddresses() {
        String p = System.getProperty("loadtest.rabbitmq.addresses");
        if (p != null && !p.isBlank()) return p;
        String e = System.getenv("LOADTEST_RABBITMQ_ADDRESSES");
        return (e != null && !e.isBlank()) ? e : null;
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry registry) {
        if (RABBIT_ADDRESSES != null) {
            registry.add("spring.rabbitmq.addresses", () -> RABBIT_ADDRESSES);
        }
        // Consumers concorrentes drenando a fila.
        registry.add("spring.rabbitmq.listener.simple.concurrency", () -> CONSUMERS);
        registry.add("spring.rabbitmq.listener.simple.max-concurrency", () -> CONSUMERS);
        registry.add("spring.rabbitmq.listener.simple.prefetch", () -> Math.max(1, CONSUMERS));

        registry.add("spring.datasource.url",
                () -> "jdbc:h2:mem:loadtest;DB_CLOSE_DELAY=-1;MODE=PostgreSQL");
        registry.add("spring.datasource.driver-class-name", () -> "org.h2.Driver");
        registry.add("spring.datasource.username", () -> "sa");
        registry.add("spring.datasource.password", () -> "");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "create-drop");
        registry.add("spring.jpa.database-platform", () -> "org.hibernate.dialect.H2Dialect");
    }

    @TestConfiguration
    static class MailMockConfig {
        @Bean
        JavaMailSender javaMailSender() {
            return new LatencyMockMailSender(SMTP_LATENCY_MS);
        }
    }

    @LocalServerPort
    int port;

    @Autowired
    EmailRepository emailRepository;

    private final RestTemplate rest = new RestTemplate();

    @BeforeAll
    static void banner() {
        System.out.println("\n======================================================");
        System.out.println(" LOAD TEST SWEEP  sync vs async");
        System.out.println(" requests/cenario = " + TOTAL_REQUESTS);
        System.out.println(" smtpLatencyMs    = " + SMTP_LATENCY_MS);
        System.out.println(" consumers (async)= " + CONSUMERS);
        System.out.println(" sweep concorr.   = " + SWEEP);
        System.out.println(" rabbitmq         = " + (RABBIT_ADDRESSES == null ? "<none>" : RABBIT_ADDRESSES));
        System.out.println("======================================================\n");
    }

    @Test
    void sweep() throws Exception {
        boolean asyncEnabled = RABBIT_ADDRESSES != null;

        List<String> csv = new ArrayList<>();
        csv.add("modo;concorrencia;requests;wall_ms;throughput_req_s;lat_media_ms;p50_ms;p95_ms;p99_ms;max_ms;erros;drain_ms");

        System.out.println(">>> RESULTADOS POR CENARIO <<<\n");

        for (int conc : SWEEP) {
            // ---- SÍNCRONO ----
            emailRepository.deleteAll();
            Result sync = fire("/sending-email", 201, conc);
            System.out.println(report("SINCRONO  conc=" + conc, sync, -1));
            csv.add(line("sync", conc, sync, -1));
            assertThat(sync.errors.get()).isZero();

            // ---- ASSÍNCRONO ----
            if (asyncEnabled) {
                emailRepository.deleteAll();
                Result async = fire("/sending-email/async", 202, conc);
                long drainMs = waitUntilProcessed(TOTAL_REQUESTS, Duration.ofSeconds(180));
                System.out.println(report("ASSINCRONO conc=" + conc, async, drainMs));
                csv.add(line("async", conc, async, drainMs));
                assertThat(async.errors.get()).isZero();
            }
            System.out.println("------------------------------------------------------");
        }

        System.out.println("\n>>> CSV (copie para planilha) <<<\n");
        csv.forEach(System.out::println);
        System.out.println();
    }

    // ---------- Motor de carga ----------

    private Result fire(String path, int expectedStatus, int concurrency) throws InterruptedException {
        String url = "http://localhost:" + port + path;
        ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        List<Long> latencies = Collections.synchronizedList(new ArrayList<>(TOTAL_REQUESTS));
        AtomicInteger errors = new AtomicInteger();
        CountDownLatch done = new CountDownLatch(TOTAL_REQUESTS);

        long wallStart = System.nanoTime();
        for (int i = 0; i < TOTAL_REQUESTS; i++) {
            final int idx = i;
            pool.submit(() -> {
                long t0 = System.nanoTime();
                try {
                    ResponseEntity<String> resp = rest.postForEntity(url, request(idx), String.class);
                    if (resp.getStatusCode().value() != expectedStatus) {
                        errors.incrementAndGet();
                    }
                } catch (Exception e) {
                    errors.incrementAndGet();
                } finally {
                    latencies.add((System.nanoTime() - t0) / 1_000_000);
                    done.countDown();
                }
            });
        }
        done.await(180, TimeUnit.SECONDS);
        long wallMs = (System.nanoTime() - wallStart) / 1_000_000;
        pool.shutdownNow();

        Result r = new Result();
        r.latencies = latencies;
        r.wallMs = wallMs;
        r.errors = errors;
        return r;
    }

    private long waitUntilProcessed(int expected, Duration timeout) throws InterruptedException {
        long start = System.nanoTime();
        long deadline = start + timeout.toNanos();
        while (System.nanoTime() < deadline) {
            if (emailRepository.count() >= expected) break;
            Thread.sleep(50);
        }
        return (System.nanoTime() - start) / 1_000_000;
    }

    private static String jsonBody(int i) {
        return "{"
                + "\"ownerRef\":\"user-" + i + "\","
                + "\"emailFrom\":\"no-reply@exemplo.com\","
                + "\"emailTo\":\"destino" + i + "@exemplo.com\","
                + "\"subject\":\"Teste de carga " + i + "\","
                + "\"text\":\"Corpo do e-mail de teste " + i + "\""
                + "}";
    }

    private HttpEntity<String> request(int i) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        return new HttpEntity<>(jsonBody(i), headers);
    }

    // ---------- Relatório ----------

    static class Result {
        List<Long> latencies;
        long wallMs;
        AtomicInteger errors;
    }

    private Stats stats(Result r) {
        List<Long> sorted = new ArrayList<>(r.latencies);
        Collections.sort(sorted);
        Stats s = new Stats();
        s.total = sorted.size();
        s.avg = sorted.stream().mapToLong(Long::longValue).average().orElse(0);
        s.p50 = pct(sorted, 50);
        s.p95 = pct(sorted, 95);
        s.p99 = pct(sorted, 99);
        s.max = sorted.isEmpty() ? 0 : sorted.get(sorted.size() - 1);
        s.throughput = r.wallMs > 0 ? (s.total * 1000.0 / r.wallMs) : 0;
        return s;
    }

    static class Stats {
        long total;
        double avg;
        long p50, p95, p99, max;
        double throughput;
    }

    private String report(String title, Result r, long drainMs) {
        Stats s = stats(r);
        StringBuilder sb = new StringBuilder();
        sb.append("\n--- ").append(title).append(" ---\n");
        sb.append(String.format("  requests            : %d (erros: %d)%n", s.total, r.errors.get()));
        sb.append(String.format("  tempo total (wall)  : %d ms%n", r.wallMs));
        sb.append(String.format("  throughput          : %.1f req/s%n", s.throughput));
        sb.append(String.format("  latencia media      : %.1f ms%n", s.avg));
        sb.append(String.format("  latencia p50/p95/p99: %d / %d / %d ms%n", s.p50, s.p95, s.p99));
        sb.append(String.format("  latencia max        : %d ms%n", s.max));
        if (drainMs >= 0) {
            sb.append(String.format("  drain (fila->banco) : %d ms%n", drainMs));
        }
        return sb.toString();
    }

    private String line(String modo, int conc, Result r, long drainMs) {
        Stats s = stats(r);
        return String.format(java.util.Locale.US,
                "%s;%d;%d;%d;%.1f;%.1f;%d;%d;%d;%d;%d;%s",
                modo, conc, s.total, r.wallMs, s.throughput, s.avg,
                s.p50, s.p95, s.p99, s.max, r.errors.get(),
                drainMs >= 0 ? String.valueOf(drainMs) : "");
    }

    private long pct(List<Long> sorted, int p) {
        if (sorted.isEmpty()) return 0;
        int idx = (int) Math.ceil(p / 100.0 * sorted.size()) - 1;
        idx = Math.max(0, Math.min(idx, sorted.size() - 1));
        return sorted.get(idx);
    }
}
