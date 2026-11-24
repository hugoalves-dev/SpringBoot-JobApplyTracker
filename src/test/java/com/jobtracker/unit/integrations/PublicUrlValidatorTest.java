package com.jobtracker.unit.integrations;

import com.jobtracker.exception.BadRequestException;
import com.jobtracker.service.integrations.PublicUrlValidator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.net.InetAddress;
import java.net.UnknownHostException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PublicUrlValidatorTest {

    /** Resolves literals locally and maps a few fake hostnames, so the test never touches DNS. */
    private final PublicUrlValidator validator = new PublicUrlValidator(host -> switch (host) {
        case "jobs.example.com" -> new InetAddress[]{InetAddress.getByName("93.184.216.34")};
        case "rebind.example.com" -> new InetAddress[]{InetAddress.getByName("93.184.216.34"), InetAddress.getByName("10.0.0.5")};
        case "missing.example.com" -> throw new UnknownHostException(host);
        default -> InetAddress.getAllByName(host);
    });

    @Test
    void acceptsPublicHttpsUrl() {
        assertThat(validator.validate(" https://jobs.example.com/view/123?ref=share ").toString())
                .isEqualTo("https://jobs.example.com/view/123?ref=share");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "file:///etc/passwd",
            "ftp://jobs.example.com/x",
            "http://localhost/admin",
            "http://127.0.0.1/",
            "http://10.1.2.3/",
            "http://192.168.0.10/",
            "http://172.16.0.1/",
            "http://169.254.169.254/latest/meta-data",
            "http://100.64.0.1/",
            "http://0.0.0.0/",
            "http://[::1]/",
            "http://[fc00::1]/",
            "http://[::ffff:127.0.0.1]/",
            "http://user:pass@jobs.example.com/",
            "http://jobs.example.com:8080/",
            "http://metadata.internal/",
            "http://rebind.example.com/",
            "http://missing.example.com/",
            "not a url",
            ""
    })
    void rejectsNonPublicOrMalformedTargets(String url) {
        assertThatThrownBy(() -> validator.validate(url)).isInstanceOf(BadRequestException.class);
    }
}
