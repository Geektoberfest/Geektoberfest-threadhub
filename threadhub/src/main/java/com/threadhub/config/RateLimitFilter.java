package com.threadhub.config;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.Refill;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Token-bucket rate limiter for the login and registration endpoints.
 *
 * <p>Two properties drive the behaviour and are worth stating explicitly:</p>
 *
 * <ul>
 *   <li><b>Client identity.</b> The limiter keys on {@link HttpServletRequest#getRemoteAddr()}.
 *       {@code X-Forwarded-For} and {@code X-Real-IP} are attacker-controlled on any request that
 *       does not arrive from a proxy this application operates, so trusting them would let a caller
 *       mint a new identity per request and bypass the limit entirely. Forwarding headers are read
 *       only when {@code remoteAddr} matches {@code rate-limit.trusted-proxies}; the forwarded chain
 *       is then walked right-to-left and the rightmost address that is not itself a trusted proxy is
 *       taken as the client, which is the only hop a trusted proxy can vouch for.</li>
 *   <li><b>Bucket lifetime.</b> Buckets live in a Caffeine cache bounded by size and by
 *       {@code expireAfterAccess(windowDuration)}. After one idle window a bucket has fully
 *       refilled, so dropping it is lossless, and an arbitrary remote address can no longer pin
 *       memory forever.</li>
 * </ul>
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private static final String LOGIN_PATH = "/api/auth/login";
    private static final String REGISTRATION_PATH = "/api/users";

    /** Hard cap on retained buckets per endpoint, independent of the time-based expiry. */
    private static final long MAX_BUCKETS = 10_000;

    private final Cache<String, Bucket> loginBuckets;
    private final Cache<String, Bucket> registrationBuckets;

    private final int loginRequestsPerWindow;
    private final int registrationRequestsPerWindow;
    private final Duration windowDuration;
    private final List<Cidr> trustedProxies;

    public RateLimitFilter(RateLimitProperties properties) {
        this.loginRequestsPerWindow = requirePositive(
                properties.getLoginRequestsPerWindow(), "rate-limit.login-requests-per-window");
        this.registrationRequestsPerWindow = requirePositive(
                properties.getRegistrationRequestsPerWindow(), "rate-limit.registration-requests-per-window");
        this.windowDuration = parseWindow(properties.getWindowDuration());
        this.trustedProxies = parseTrustedProxies(properties.getTrustedProxies());

        this.loginBuckets = newBucketCache();
        this.registrationBuckets = newBucketCache();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        if (request.getMethod().equalsIgnoreCase("OPTIONS")) {
            filterChain.doFilter(request, response);
            return;
        }

        String path = request.getRequestURI();
        Cache<String, Bucket> cache;
        int limit;
        if (LOGIN_PATH.equals(path)) {
            cache = loginBuckets;
            limit = loginRequestsPerWindow;
        } else if (REGISTRATION_PATH.equals(path)) {
            cache = registrationBuckets;
            limit = registrationRequestsPerWindow;
        } else {
            filterChain.doFilter(request, response);
            return;
        }

        String clientIp = resolveClientIp(request);

        // Caffeine's get(key, mappingFunction) is atomic per key, so concurrent requests from the
        // same client share one bucket rather than each creating their own.
        Bucket bucket = cache.get(clientIp, ip -> newBucket(limit));

        if (bucket.tryConsume(1)) {
            filterChain.doFilter(request, response);
        } else {
            response.setStatus(429);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            response.getWriter().write(String.format(
                    "{\"timestamp\":\"%s\",\"status\":429,\"error\":\"Too Many Requests\",\"message\":\"Rate limit exceeded. Please try again later.\"}",
                    java.time.LocalDateTime.now()));
        }
    }

    private Cache<String, Bucket> newBucketCache() {
        return Caffeine.newBuilder()
                .expireAfterAccess(windowDuration)
                .maximumSize(MAX_BUCKETS)
                .build();
    }

    private Bucket newBucket(int capacity) {
        return Bucket.builder()
                .addLimit(Bandwidth.classic(capacity, Refill.intervally(capacity, windowDuration)))
                .build();
    }

    /**
     * Resolves the client IP, trusting forwarding headers only when the request demonstrably came
     * from a proxy this application operates. With the default empty trusted-proxy list this
     * always returns {@code remoteAddr}.
     */
    String resolveClientIp(HttpServletRequest request) {
        String remoteAddr = normalize(request.getRemoteAddr());
        if (remoteAddr == null) {
            return "unknown";
        }
        if (trustedProxies.isEmpty() || !isTrustedProxy(remoteAddr)) {
            return remoteAddr;
        }

        // The connection came from one of our own proxies, so its forwarded headers describe hops
        // that proxy saw. Walk right-to-left and stop at the first address that is not itself a
        // trusted proxy: everything to its right was appended by infrastructure we control, and
        // anything a client forged sits further left and is ignored.
        String forwardedFor = header(request, "X-Forwarded-For");
        if (forwardedFor != null) {
            String[] hops = forwardedFor.split(",");
            for (int i = hops.length - 1; i >= 0; i--) {
                String hop = normalize(hops[i]);
                if (hop != null && !isTrustedProxy(hop)) {
                    return hop;
                }
            }
        }

        String realIp = header(request, "X-Real-IP");
        if (realIp != null) {
            return realIp;
        }
        return remoteAddr;
    }

    private static String header(HttpServletRequest request, String name) {
        String value = request.getHeader(name);
        return (value != null && !value.isBlank()) ? value : null;
    }

    private boolean isTrustedProxy(String ip) {
        byte[] address = toBytes(ip);
        if (address == null) {
            return false;
        }
        for (Cidr cidr : trustedProxies) {
            if (cidr.matches(address)) {
                return true;
            }
        }
        return false;
    }

    /** Normalizes an IP literal, returning {@code null} when the value is not one. */
    private static String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String candidate = value.trim();
        int zone = candidate.indexOf('%');
        if (zone >= 0) {
            candidate = candidate.substring(0, zone);
        }
        byte[] address = toBytes(candidate);
        return address == null ? null : toText(address);
    }

    /**
     * Parses an IP literal into its bytes without ever performing a DNS lookup. Only literals are
     * accepted: an {@code X-Forwarded-For} value is attacker-controlled, and resolving it as a
     * hostname would let a client trigger outbound DNS lookups.
     */
    private static byte[] toBytes(String value) {
        if (value == null || value.isEmpty()) {
            return null;
        }
        String candidate = value.trim();
        if (candidate.startsWith("[")) {
            candidate = candidate.substring(1);
            int close = candidate.indexOf(']');
            if (close < 0) {
                return null;
            }
            candidate = candidate.substring(0, close);
        }
        if (candidate.toLowerCase(Locale.ROOT).startsWith("::ffff:")) {
            candidate = candidate.substring(7);
        }
        try {
            return candidate.indexOf(':') >= 0
                    ? parseIpv6(candidate)
                    : parseIpv4(candidate);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static byte[] parseIpv4(String value) {
        String[] parts = value.split("\\.", -1);
        if (parts.length != 4) {
            throw new IllegalArgumentException(value);
        }
        byte[] out = new byte[4];
        for (int i = 0; i < 4; i++) {
            if (parts[i].isEmpty() || parts[i].length() > 3) {
                throw new IllegalArgumentException(value);
            }
            int octet = 0;
            for (char c : parts[i].toCharArray()) {
                if (c < '0' || c > '9') {
                    throw new IllegalArgumentException(value);
                }
                octet = octet * 10 + (c - '0');
            }
            if (octet > 255) {
                throw new IllegalArgumentException(value);
            }
            out[i] = (byte) octet;
        }
        return out;
    }

    private static byte[] parseIpv6(String value) {
        String[] halves = value.split("::", -1);
        if (halves.length > 2) {
            throw new IllegalArgumentException(value);
        }
        List<String> groups = new ArrayList<>();
        if (halves.length == 2) {
            addGroups(groups, halves[0]);
            List<String> tail = new ArrayList<>();
            addGroups(tail, halves[1]);
            int explicit = groups.size() + tail.size();
            if (explicit > 7) {
                throw new IllegalArgumentException(value);
            }
            for (int i = explicit; i < 8; i++) {
                groups.add("0");
            }
            groups.addAll(tail);
        } else {
            addGroups(groups, value);
            if (groups.size() != 8) {
                throw new IllegalArgumentException(value);
            }
        }

        byte[] out = new byte[16];
        for (int i = 0; i < 8; i++) {
            String group = groups.get(i);
            int hex = 0;
            if (!group.isEmpty()) {
                if (group.length() > 4) {
                    throw new IllegalArgumentException(value);
                }
                for (char c : group.toCharArray()) {
                    int digit = Character.digit(c, 16);
                    if (digit < 0) {
                        throw new IllegalArgumentException(value);
                    }
                    hex = (hex << 4) + digit;
                }
            }
            out[i * 2] = (byte) (hex >> 8);
            out[i * 2 + 1] = (byte) hex;
        }
        return out;
    }

    private static void addGroups(List<String> groups, String section) {
        if (section.isEmpty()) {
            return;
        }
        for (String group : section.split(":", -1)) {
            groups.add(group);
        }
    }

    private static String toText(byte[] address) {
        if (address.length == 4) {
            return (address[0] & 0xff) + "." + (address[1] & 0xff) + "."
                    + (address[2] & 0xff) + "." + (address[3] & 0xff);
        }
        StringBuilder text = new StringBuilder(39);
        for (int i = 0; i < 16; i += 2) {
            if (i > 0) {
                text.append(':');
            }
            text.append(Integer.toHexString(((address[i] & 0xff) << 8) | (address[i + 1] & 0xff)));
        }
        return text.toString();
    }

    private static Duration parseWindow(String value) {
        Duration duration;
        try {
            duration = Duration.parse(value);
        } catch (Exception e) {
            throw new IllegalArgumentException(
                    "rate-limit.window-duration must be an ISO-8601 duration such as PT1M, but was: " + value, e);
        }
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException("rate-limit.window-duration must be positive, but was: " + value);
        }
        return duration;
    }

    private static int requirePositive(int value, String property) {
        if (value <= 0) {
            throw new IllegalArgumentException(property + " must be positive, but was: " + value);
        }
        return value;
    }

    private static List<Cidr> parseTrustedProxies(List<String> configured) {
        List<Cidr> parsed = new ArrayList<>();
        if (configured == null) {
            return parsed;
        }
        for (String entry : configured) {
            if (entry == null || entry.isBlank()) {
                continue;
            }
            String value = entry.trim();
            int slash = value.indexOf('/');
            try {
                if (slash < 0) {
                    byte[] address = toBytes(value);
                    if (address == null) {
                        throw new IllegalArgumentException(value);
                    }
                    parsed.add(new Cidr(address, address.length * 8));
                } else {
                    byte[] network = toBytes(value.substring(0, slash));
                    int prefix = Integer.parseInt(value.substring(slash + 1).trim());
                    if (network == null || prefix < 0 || prefix > network.length * 8) {
                        throw new IllegalArgumentException(value);
                    }
                    parsed.add(new Cidr(network, prefix));
                }
            } catch (RuntimeException e) {
                throw new IllegalArgumentException(
                        "rate-limit.trusted-proxies entry is not a valid IP literal or CIDR block: " + value, e);
            }
        }
        return List.copyOf(parsed);
    }

    /** A network block in either address family, matched by prefix length. */
    private record Cidr(byte[] network, int prefixLength) {

        boolean matches(byte[] address) {
            if (address.length != network.length) {
                return false;
            }
            int wholeBytes = prefixLength / 8;
            for (int i = 0; i < wholeBytes; i++) {
                if (address[i] != network[i]) {
                    return false;
                }
            }
            int remainingBits = prefixLength % 8;
            if (remainingBits == 0) {
                return true;
            }
            int mask = (0xff << (8 - remainingBits)) & 0xff;
            return (address[wholeBytes] & mask) == (network[wholeBytes] & mask);
        }
    }
}
