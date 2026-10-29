package com.github.baek.footballobsbackend.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockHttpServletRequest;
import static org.assertj.core.api.Assertions.assertThat;

class ClientIpResolverTest {
    @Test
    void renderUsesCloudflareAddressInsteadOfSpoofableHeadersOrLoopback() {
        var request = request();
        request.addHeader("CF-Connecting-IP", "203.0.113.7");
        assertThat(new ClientIpResolver(true).resolve(request)).isEqualTo("203.0.113.7");
        assertThat(new ClientIpResolver(false).resolve(request)).isEqualTo("::1");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "example.com", "127.1", "2130706433", "999.1.1.1",
            "203.0.113.7, 203.0.113.8", "203.0.113.7:443", "[2001:db8::1]", "fe80::1%lo", "1::2::3"})
    void malformedHeaderFallsBackWithoutUsingOtherForwardedHeaders(String value) {
        var request = request();
        request.addHeader("CF-Connecting-IP", value);
        assertThat(new ClientIpResolver(true).resolve(request)).isEqualTo("::1");
    }

    @Test
    void missingOrDuplicateCloudflareHeaderFallsBackToPeer() {
        var request = request();
        var resolver = new ClientIpResolver(true);
        assertThat(resolver.resolve(request)).isEqualTo("::1");
        request.addHeader("CF-Connecting-IP", "203.0.113.7");
        request.addHeader("CF-Connecting-IP", "203.0.113.8");
        assertThat(resolver.resolve(request)).isEqualTo("::1");
    }

    @Test
    void equivalentIpv6AddressesUseSameBucketKey() {
        var resolver = new ClientIpResolver(true);
        var shortForm = request();
        shortForm.addHeader("CF-Connecting-IP", "2001:db8::1");
        var longForm = request();
        longForm.addHeader("CF-Connecting-IP", "2001:0DB8:0000:0000:0000:0000:0000:0001");
        assertThat(resolver.resolve(shortForm)).isEqualTo(resolver.resolve(longForm));
    }

    private MockHttpServletRequest request() {
        var request = new MockHttpServletRequest();
        request.setRemoteAddr("::1");
        request.addHeader("X-Forwarded-For", "198.51.100.99, 203.0.113.7");
        request.addHeader("True-Client-IP", "198.51.100.98");
        return request;
    }
}
