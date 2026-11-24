package com.jobtracker.service.integrations;

import com.jobtracker.exception.BadRequestException;
import org.springframework.stereotype.Component;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.Locale;

/**
 * Accepts only absolute http(s) URLs whose host resolves exclusively to public addresses.
 * TinyFish performs the actual request, but the same validation keeps internal hosts out of
 * anything Applywell forwards, caches or might fetch itself in the future.
 */
@Component
public class PublicUrlValidator {

    private static final int MAX_URL_LENGTH = 2048;

    @FunctionalInterface
    public interface HostResolver {
        InetAddress[] resolve(String host) throws UnknownHostException;
    }

    private final HostResolver resolver;

    public PublicUrlValidator() {
        this(InetAddress::getAllByName);
    }

    public PublicUrlValidator(HostResolver resolver) {
        this.resolver = resolver;
    }

    public URI validate(String rawUrl) {
        if (rawUrl == null || rawUrl.isBlank()) {
            throw new BadRequestException("url is required");
        }
        String trimmed = rawUrl.trim();
        if (trimmed.length() > MAX_URL_LENGTH) {
            throw new BadRequestException("url is too long");
        }
        URI uri;
        try {
            uri = new URI(trimmed).normalize();
        } catch (URISyntaxException ex) {
            throw new BadRequestException("url is not a valid URL");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            throw new BadRequestException("Only http and https URLs are supported");
        }
        if (uri.getRawUserInfo() != null) {
            throw new BadRequestException("URLs with embedded credentials are not allowed");
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new BadRequestException("url must include a host");
        }
        int port = uri.getPort();
        if (port != -1 && port != 80 && port != 443) {
            throw new BadRequestException("Only the default http/https ports are allowed");
        }
        String normalizedHost = host.toLowerCase(Locale.ROOT);
        if (normalizedHost.equals("localhost") || normalizedHost.endsWith(".localhost")
                || normalizedHost.endsWith(".local") || normalizedHost.endsWith(".internal")) {
            throw new BadRequestException("Private or local hosts are not allowed");
        }

        InetAddress[] addresses;
        try {
            addresses = resolver.resolve(stripBrackets(normalizedHost));
        } catch (UnknownHostException ex) {
            throw new BadRequestException("Host could not be resolved: " + host);
        }
        if (addresses == null || addresses.length == 0) {
            throw new BadRequestException("Host could not be resolved: " + host);
        }
        for (InetAddress address : addresses) {
            if (!isPublic(address)) {
                throw new BadRequestException("Private or local hosts are not allowed");
            }
        }
        return uri;
    }

    static boolean isPublic(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                || address.isSiteLocalAddress() || address.isMulticastAddress()) {
            return false;
        }
        byte[] bytes = address.getAddress();
        if (address instanceof Inet4Address) {
            int first = bytes[0] & 0xFF;
            int second = bytes[1] & 0xFF;
            if (first == 0) return false;                                 // 0.0.0.0/8
            if (first == 100 && second >= 64 && second <= 127) return false; // 100.64.0.0/10 CGNAT
            if (first == 192 && second == 0 && (bytes[2] & 0xFF) == 0) return false; // 192.0.0.0/24
            if (first == 198 && (second == 18 || second == 19)) return false; // benchmarking
            if (first >= 240) return false;                               // reserved + broadcast
        }
        if (address instanceof Inet6Address) {
            int first = bytes[0] & 0xFF;
            if ((first & 0xFE) == 0xFC) return false;                     // fc00::/7 unique local
            if (isIpv4Mapped(bytes)) {
                try {
                    byte[] v4 = {bytes[12], bytes[13], bytes[14], bytes[15]};
                    return isPublic(InetAddress.getByAddress(v4));
                } catch (UnknownHostException ex) {
                    return false;
                }
            }
        }
        return true;
    }

    private static boolean isIpv4Mapped(byte[] bytes) {
        for (int i = 0; i < 10; i++) {
            if (bytes[i] != 0) return false;
        }
        return (bytes[10] & 0xFF) == 0xFF && (bytes[11] & 0xFF) == 0xFF;
    }

    private static String stripBrackets(String host) {
        return host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
    }
}
