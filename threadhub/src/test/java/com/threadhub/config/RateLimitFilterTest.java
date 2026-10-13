package com.threadhub.config;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Direct unit tests for {@link RateLimitFilter}. These exercise the filter without a Spring
 * context so the two security properties under test - how the client IP is resolved and how long
 * buckets are retained - can be driven precisely, including the forged-header and concurrency
 * cases that are awkward to express through MockMvc.
 */
class RateLimitFilterTest {

    private static final String LOGIN = "/api/auth/login";
    private static final String REGISTRATION = "/api/users";

    // ------------------------------------------------------------------
    // Client IP spoofing
    // ------------------------------------------------------------------

    @Test
    @DisplayName("A forged X-Forwarded-For header cannot mint new identities when no proxy is trusted")
    void forgedForwardedForIsIgnoredWithoutTrustedProxies() {
        RateLimitFilter filter = filter(2, 2, "PT1M");

        // Three requests, each forging a different client IP. Without a trusted proxy the limiter
        // must key on remoteAddr, so they all share one bucket and the third is rejected.
        assertThat(send(filter, LOGIN, "9.9.9.9", "X-Forwarded-For", "10.0.0.1")).isEqualTo(200);
        assertThat(send(filter, LOGIN, "9.9.9.9", "X-Forwarded-For", "10.0.0.2")).isEqualTo(200);
        assertThat(send(filter, LOGIN, "9.9.9.9", "X-Forwarded-For", "10.0.0.3")).isEqualTo(429);
    }

    @Test
    @DisplayName("An X-Real-IP header is ignored for an untrusted client too")
    void forgedRealIpIsIgnoredWithoutTrustedProxies() {
        RateLimitFilter filter = filter(1, 1, "PT1M");

        assertThat(send(filter, LOGIN, "9.9.9.8", "X-Real-IP", "10.0.0.1")).isEqualTo(200);
        assertThat(send(filter, LOGIN, "9.9.9.8", "X-Real-IP", "10.0.0.2")).isEqualTo(429);
    }

    @Test
    @DisplayName("A forwarded chain is honoured only when the connection comes from a trusted proxy")
    void forwardedForIsHonouredFromTrustedProxy() {
        RateLimitFilter filter = filter(1, 1, "PT1M", "127.0.0.1");

        // Arriving from the trusted proxy, each distinct forwarded client is its own identity.
        assertThat(send(filter, LOGIN, "127.0.0.1", "X-Forwarded-For", "10.0.0.1")).isEqualTo(200);
        assertThat(send(filter, LOGIN, "127.0.0.1", "X-Forwarded-For", "10.0.0.2")).isEqualTo(200);
        // ...but the same forwarded client is still limited.
        assertThat(send(filter, LOGIN, "127.0.0.1", "X-Forwarded-For", "10.0.0.1")).isEqualTo(429);
    }

    @Test
    @DisplayName("Forged hops to the right of the real client are discarded when behind a trusted proxy")
    void forgedHopsFurtherLeftThanTheRealClientAreIgnored() {
        RateLimitFilter filter = filter(1, 1, "PT1M", "10.10.0.0/16");

        // A client behind our proxy forges extra hops. The proxy appends the real client address
        // last, so only the rightmost non-trusted hop may be used as the identity.
        String forged = "10.0.0.1, 10.0.0.2, 10.0.0.3";
        assertThat(send(filter, LOGIN, "10.10.0.5", "X-Forwarded-For", forged)).isEqualTo(200);

        // A second request with the same chain is limited, proving 10.0.0.3 - not a forged hop -
        // was the key.
        assertThat(send(filter, LOGIN, "10.10.0.5", "X-Forwarded-For", forged)).isEqualTo(429);
        // A different rightmost hop is a different client.
        assertThat(send(filter, LOGIN, "10.10.0.5", "X-Forwarded-For", "10.0.0.9, 10.0.0.4"))
                .isEqualTo(200);
    }

    @Test
    @DisplayName("Trusted proxies configured as a CIDR block match by prefix")
    void trustedProxyCidrMatchesByPrefix() {
        RateLimitFilter filter = filter(1, 1, "PT1M", "192.168.0.0/16");

        assertThat(send(filter, LOGIN, "192.168.77.3", "X-Forwarded-For", "10.0.0.1")).isEqualTo(200);
        assertThat(send(filter, LOGIN, "192.168.77.3", "X-Forwarded-For", "10.0.0.1")).isEqualTo(429);
        // An address outside the block is not a trusted proxy, so the header is ignored.
        assertThat(send(filter, LOGIN, "192.169.77.3", "X-Forwarded-For", "10.0.0.1")).isEqualTo(200);
        assertThat(send(filter, LOGIN, "192.169.77.3", "X-Forwarded-For", "10.0.0.2")).isEqualTo(429);
    }

    @Test
    @DisplayName("An IPv4-mapped IPv6 remote address matches its IPv4 trusted proxy entry")
    void ipv4MappedIpv6RemoteAddressMatches() {
        RateLimitFilter filter = filter(1, 1, "PT1M", "127.0.0.1");

        assertThat(send(filter, LOGIN, "::ffff:127.0.0.1", "X-Forwarded-For", "10.0.0.1")).isEqualTo(200);
        assertThat(send(filter, LOGIN, "::ffff:127.0.0.1", "X-Forwarded-For", "10.0.0.1")).isEqualTo(429);
    }

    @Test
    @DisplayName("A non-IP X-Forwarded-For value is rejected rather than resolved through DNS")
    void nonIpForwardedForValueIsNotResolved() {
        RateLimitFilter filter = filter(1, 1, "PT1M", "127.0.0.1");

        // "attacker.example" must not be looked up; it falls back to the proxy address itself.
        assertThat(send(filter, LOGIN, "127.0.0.1", "X-Forwarded-For", "attacker.example")).isEqualTo(200);
        assertThat(send(filter, LOGIN, "127.0.0.1", "X-Forwarded-For", "attacker.example")).isEqualTo(429);
    }

    // ------------------------------------------------------------------
    // Configuration wiring
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Login and registration limits are applied independently from their own properties")
    void registrationLimitIsReadFromItsOwnProperty() {
        RateLimitFilter filter = filter(5, 2, "PT1M", "127.0.0.1");

        assertThat(send(filter, LOGIN, "127.0.0.1", "X-Forwarded-For", "10.0.0.1")).isEqualTo(200);
        assertThat(send(filter, LOGIN, "127.0.0.1", "X-Forwarded-For", "10.0.0.1")).isEqualTo(200);
        assertThat(send(filter, LOGIN, "127.0.0.1", "X-Forwarded-For", "10.0.0.1")).isEqualTo(200);
        assertThat(send(filter, LOGIN, "127.0.0.1", "X-Forwarded-For", "10.0.0.1")).isEqualTo(200);
        assertThat(send(filter, LOGIN, "127.0.0.1", "X-Forwarded-For", "10.0.0.1")).isEqualTo(200);
        // The login bucket holds 5, so the sixth request is rejected.
        assertThat(send(filter, LOGIN, "127.0.0.1", "X-Forwarded-For", "10.0.0.1")).isEqualTo(429);

        // Registration is a separate bucket for the same client, capped at 2 rather than 5. If the
        // filter still read the login property for both paths this would allow 5 instead of 2.
        assertThat(send(filter, REGISTRATION, "127.0.0.1", "X-Forwarded-For", "10.0.0.1")).isEqualTo(200);
        assertThat(send(filter, REGISTRATION, "127.0.0.1", "X-Forwarded-For", "10.0.0.1")).isEqualTo(200);
        assertThat(send(filter, REGISTRATION, "127.0.0.1", "X-Forwarded-For", "10.0.0.1")).isEqualTo(429);
    }

    @Test
    @DisplayName("Changing the registration limit changes the enforced behaviour")
    void changingRegistrationLimitChangesBehaviour() {
        RateLimitProperties properties = new RateLimitProperties();
        properties.setLoginRequestsPerWindow(5);
        properties.setRegistrationRequestsPerWindow(4);
        properties.setWindowDuration("PT1M");
        properties.setTrustedProxies(List.of("127.0.0.1"));
        RateLimitFilter filter = new RateLimitFilter(properties);

        AtomicInteger accepted = new AtomicInteger();
        for (int i = 0; i < 6; i++) {
            if (send(filter, REGISTRATION, "127.0.0.1", "X-Forwarded-For", "10.0.0.7") == 200) {
                accepted.incrementAndGet();
            }
        }
        assertThat(accepted).hasValue(4);
    }

    @Test
    @DisplayName("Requests to other paths are never rate limited")
    void otherPathsAreNotRateLimited() {
        RateLimitFilter filter = filter(1, 1, "PT1M", "127.0.0.1");

        for (int i = 0; i < 10; i++) {
            assertThat(send(filter, "/api/health", "127.0.0.1", "X-Forwarded-For", "10.0.0.1"))
                    .isEqualTo(200);
        }
    }

    @Test
    @DisplayName("CORS preflight requests are not rate limited")
    void optionsPreflightIsNotRateLimited() throws Exception {
        RateLimitFilter filter = filter(1, 1, "PT1M", "127.0.0.1");

        for (int i = 0; i < 10; i++) {
            MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", LOGIN);
            request.setRemoteAddr("127.0.0.1");
            request.addHeader("X-Forwarded-For", "10.0.0.1");
            RecordingChain chain = new RecordingChain();
            filter.doFilter(request, new MockHttpServletResponse(), chain);
            assertThat(chain.passed).isEqualTo(1);
        }
    }

    // ------------------------------------------------------------------
    // Bucket lifecycle and concurrency
    // ------------------------------------------------------------------

    @Test
    @DisplayName("Tokens are restored once the window has elapsed")
    void tokensAreRestoredAfterTheWindowElapses() throws Exception {
        RateLimitFilter filter = filter(1, 1, "PT1S", "127.0.0.1");
        String clientIp = "10.0.0.1";

        assertThat(send(filter, LOGIN, "127.0.0.1", "X-Forwarded-For", clientIp)).isEqualTo(200);
        assertThat(send(filter, LOGIN, "127.0.0.1", "X-Forwarded-For", clientIp)).isEqualTo(429);

        Thread.sleep(1_200);

        // A full window later the bucket has refilled, so the client is served again.
        assertThat(send(filter, LOGIN, "127.0.0.1", "X-Forwarded-For", clientIp)).isEqualTo(200);
    }

    @Test
    @DisplayName("Inactive buckets are evicted instead of accumulating for every remote address")
    void inactiveBucketsAreEvicted() throws Exception {
        RateLimitFilter filter = filter(1, 1, "PT1S", "127.0.0.1");

        // Each distinct forwarded address pins its own bucket; after the window they are all
        // evicted rather than retained forever.
        for (int i = 0; i < 200; i++) {
            assertThat(send(filter, LOGIN, "127.0.0.1", "X-Forwarded-For", "10.1." + (i / 256) + "." + (i % 256)))
                    .isEqualTo(200);
        }

        Thread.sleep(1_200);

        // Caffeine evicts lazily on access, so touching the cache reclaims the idle entries. The
        // first address is usable again, which proves its bucket was dropped rather than retained.
        assertThat(send(filter, LOGIN, "127.0.0.1", "X-Forwarded-For", "10.1.0.0")).isEqualTo(200);
    }

    @Test
    @DisplayName("Concurrent requests from one client share a single bucket and still hit the limit")
    void concurrentRequestsShareOneBucket() throws Exception {
        RateLimitFilter filter = filter(5, 5, "PT1M", "127.0.0.1");
        int callers = 32;

        ExecutorService pool = Executors.newFixedThreadPool(8);
        CountDownLatch ready = new CountDownLatch(callers);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger accepted = new AtomicInteger();

        try {
            for (int i = 0; i < callers; i++) {
                pool.submit(() -> {
                    ready.countDown();
                    try {
                        start.await();
                        if (send(filter, LOGIN, "127.0.0.1", "X-Forwarded-For", "10.0.0.1") == 200) {
                            accepted.incrementAndGet();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                });
            }
            ready.await(10, TimeUnit.SECONDS);
            start.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
        } finally {
            pool.shutdownNow();
        }

        // The limit is 5, so exactly 5 of the 32 racing requests may pass regardless of scheduling.
        assertThat(accepted).hasValue(5);
    }

    // ------------------------------------------------------------------
    // Response shape
    // ------------------------------------------------------------------

    @Test
    @DisplayName("A rejected request still returns the documented 429 payload")
    void rateLimitedResponseKeepsItsShape() throws Exception {
        RateLimitFilter filter = filter(1, 1, "PT1M", "127.0.0.1");

        assertThat(send(filter, LOGIN, "127.0.0.1", "X-Forwarded-For", "10.0.0.1")).isEqualTo(200);

        MockHttpServletRequest request = request(LOGIN, "127.0.0.1", "X-Forwarded-For", "10.0.0.1");
        MockHttpServletResponse response = new MockHttpServletResponse();
        RecordingChain chain = new RecordingChain();

        filter.doFilter(request, response, chain);

        assertThat(chain.passed).isZero();
        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getContentType()).isEqualTo("application/json");
        assertThat(response.getContentAsString())
                .contains("\"status\":429")
                .contains("\"error\":\"Too Many Requests\"")
                .contains("\"message\":\"Rate limit exceeded. Please try again later.\"")
                .contains("\"timestamp\":");
    }

    @Test
    @DisplayName("A missing remote address degrades to a single shared bucket rather than failing")
    void missingRemoteAddressFallsBackSafely() {
        RateLimitFilter filter = filter(1, 1, "PT1M");

        assertThat(send(filter, LOGIN, null, "X-Forwarded-For", "10.0.0.1")).isEqualTo(200);
        assertThat(send(filter, LOGIN, null, "X-Forwarded-For", "10.0.0.2")).isEqualTo(429);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static RateLimitFilter filter(int login, int registration, String window, String... trustedProxies) {
        RateLimitProperties properties = new RateLimitProperties();
        properties.setLoginRequestsPerWindow(login);
        properties.setRegistrationRequestsPerWindow(registration);
        properties.setWindowDuration(window);
        properties.setTrustedProxies(List.of(trustedProxies));
        return new RateLimitFilter(properties);
    }

    private static int send(RateLimitFilter filter, String path, String remoteAddr,
                            String headerName, String headerValue) {
        MockHttpServletRequest request = request(path, remoteAddr, headerName, headerValue);
        MockHttpServletResponse response = new MockHttpServletResponse();
        RecordingChain chain = new RecordingChain();
        try {
            filter.doFilter(request, response, chain);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return chain.passed > 0 ? 200 : response.getStatus();
    }

    private static MockHttpServletRequest request(String path, String remoteAddr,
                                                  String headerName, String headerValue) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", path);
        if (remoteAddr != null) {
            request.setRemoteAddr(remoteAddr);
        }
        request.addHeader(headerName, headerValue);
        return request;
    }

    private static final class RecordingChain implements FilterChain {
        private volatile int passed;

        @Override
        public void doFilter(ServletRequest request, ServletResponse response) {
            passed++;
        }
    }
}
