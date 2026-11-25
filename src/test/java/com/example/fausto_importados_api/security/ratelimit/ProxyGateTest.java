package com.example.fausto_importados_api.security.ratelimit;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProxyGateTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef-secret";

    private static ProxyGate gate(String secret, boolean require) {
        return new ProxyGate(new ProxyProperties(secret, require));
    }

    private static MockHttpServletRequest request(String secret, String clientIp) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/auth/login");
        if (secret != null) {
            request.addHeader(ProxyGate.SECRET_HEADER, secret);
        }
        if (clientIp != null) {
            request.addHeader(ProxyGate.CLIENT_IP_HEADER, clientIp);
        }
        return request;
    }

    @Test
    void withTheRightSecret_theClientIpIsTrusted() {
        assertThat(gate(SECRET, false).clientIp(request(SECRET, "203.0.113.7"))).contains("203.0.113.7");
        assertThat(gate(SECRET, false).clientIp(request(SECRET, "2001:db8::1"))).contains("2001:db8::1");
    }

    @Test
    void withoutOrWithAWrongSecret_theClientIpIsIgnored() {
        ProxyGate gate = gate(SECRET, false);

        assertThat(gate.clientIp(request(null, "203.0.113.7"))).isEmpty();
        assertThat(gate.clientIp(request("wrong", "203.0.113.7"))).isEmpty();
        assertThat(gate.clientIp(request(SECRET + "x", "203.0.113.7"))).isEmpty();
        assertThat(gate.clientIp(request("", "203.0.113.7"))).isEmpty();
    }

    @Test
    void anInvalidClientIp_isIgnoredEvenWithTheRightSecret() {
        ProxyGate gate = gate(SECRET, false);

        assertThat(gate.clientIp(request(SECRET, "not-an-ip"))).isEmpty();
        assertThat(gate.clientIp(request(SECRET, "example.com"))).isEmpty();
        assertThat(gate.clientIp(request(SECRET, "1.2.3.4, 5.6.7.8"))).isEmpty();
        assertThat(gate.clientIp(request(SECRET, null))).isEmpty();
    }

    @Test
    void disabledGate_trustsNothing() {
        ProxyGate gate = gate("", false);

        assertThat(gate.enabled()).isFalse();
        assertThat(gate.fromTrustedProxy(request("", "203.0.113.7"))).isFalse();
        assertThat(gate.clientIp(request(SECRET, "203.0.113.7"))).isEmpty();
        assertThat(gate.mustBlock(request(null, null), "/auth/login")).isFalse();
    }

    @Test
    void requireForAuth_blocksAuthPathsWithoutTheSecretOnly() {
        ProxyGate gate = gate(SECRET, true);

        assertThat(gate.mustBlock(request(null, null), "/auth/login")).isTrue();
        assertThat(gate.mustBlock(request("wrong", null), "/auth/refresh")).isTrue();
        assertThat(gate.mustBlock(request(null, null), "/auth")).isTrue();
        assertThat(gate.mustBlock(request(SECRET, null), "/auth/login")).isFalse();
        // fora de /auth nada muda (o navegador chama a API direto)
        assertThat(gate.mustBlock(request(null, null), "/api/product")).isFalse();
        assertThat(gate.mustBlock(request(null, null), "/authors")).isFalse();
    }

    @Test
    void withoutRequireForAuth_nothingIsBlocked() {
        assertThat(gate(SECRET, false).mustBlock(request(null, null), "/auth/login")).isFalse();
    }

    @Test
    void misconfiguration_failsFast() {
        assertThatThrownBy(() -> gate("", true)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> gate("curto", false)).isInstanceOf(IllegalStateException.class);
        assertThat(gate("  ", false).enabled()).isFalse();
    }
}
