package com.example.fausto_importados_api.controller;

import com.example.fausto_importados_api.security.ratelimit.RateLimitKeys;
import com.example.fausto_importados_api.security.ratelimit.RateLimitProperties.Rule;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClientInfoTest {

    private static MockHttpServletRequest request(String peer, String... forwardedFor) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(peer);
        for (String line : forwardedFor) {
            request.addHeader("X-Forwarded-For", line);
        }
        return request;
    }

    @Test
    void zeroHops_ignoresTheHeaderCompletely() {
        assertThat(ClientInfo.resolve(request("10.0.0.1", "6.6.6.6"), 0)).isEqualTo("10.0.0.1");
    }

    @Test
    void oneHop_takesTheLastItem_thatTheProxyAppended() {
        // o cliente mandou "1.1.1.1"; o proxy acrescentou o IP real dele no fim
        assertThat(ClientInfo.resolve(request("10.0.0.1", "1.1.1.1, 203.0.113.9"), 1)).isEqualTo("203.0.113.9");
    }

    @Test
    void twoHops_skipsTheEdgeProxy_andIgnoresEverythingForgedToTheLeft() {
        // Vercel acrescentou o cliente; o Render acrescentou a Vercel
        assertThat(ClientInfo.resolve(request("10.0.0.1", "203.0.113.9, 76.76.21.21"), 2)).isEqualTo("203.0.113.9");
        // o cliente tentou se passar por outro IP: o que ele escreveu fica à esquerda e é ignorado
        assertThat(ClientInfo.resolve(request("10.0.0.1", "8.8.8.8, 203.0.113.9, 76.76.21.21"), 2))
                .isEqualTo("203.0.113.9");
    }

    @Test
    void multipleHeaderLinesAreTreatedAsOneList() {
        assertThat(ClientInfo.resolve(request("10.0.0.1", "8.8.8.8", "203.0.113.9", "76.76.21.21"), 2))
                .isEqualTo("203.0.113.9");
    }

    @Test
    void fewerItemsThanProxies_fallsBackToTheConnectionAddress() {
        assertThat(ClientInfo.resolve(request("10.0.0.1", "6.6.6.6"), 2)).isEqualTo("10.0.0.1");
        assertThat(ClientInfo.resolve(request("10.0.0.1"), 1)).isEqualTo("10.0.0.1");
    }

    @Test
    void garbageInTheHeader_neverBecomesAKey() {
        assertThat(ClientInfo.resolve(request("10.0.0.1", "not-an-ip"), 1)).isEqualTo("10.0.0.1");
        assertThat(ClientInfo.resolve(request("10.0.0.1", "999.1.1.1"), 1)).isEqualTo("10.0.0.1");
        assertThat(ClientInfo.resolve(request("10.0.0.1", "evil.example.com"), 1)).isEqualTo("10.0.0.1");
        assertThat(ClientInfo.resolve(request("10.0.0.1", "1.2.3.4.5"), 1)).isEqualTo("10.0.0.1");
    }

    @Test
    void ipv6_isAccepted_andLimitedByItsSlash64() {
        String ip = ClientInfo.resolve(request("10.0.0.1", "2001:db8:1:2:aaaa:bbbb:cccc:dddd"), 1);
        assertThat(ip).isEqualTo("2001:db8:1:2:aaaa:bbbb:cccc:dddd");

        // mesma faixa /64 = mesma chave; outra faixa = outra chave
        assertThat(RateLimitKeys.ip("2001:db8:1:2:aaaa:bbbb:cccc:dddd"))
                .isEqualTo(RateLimitKeys.ip("2001:db8:1:2:1111:2222:3333:4444"))
                .isNotEqualTo(RateLimitKeys.ip("2001:db8:1:3::1"));
    }

    @Test
    void rule_parsesMaxAndWindow() {
        assertThat(Rule.valueOf("5/15m")).isEqualTo(new Rule(5, Duration.ofMinutes(15)));
        assertThat(Rule.valueOf("100/1m")).isEqualTo(new Rule(100, Duration.ofMinutes(1)));
        assertThat(Rule.valueOf("3/1h")).isEqualTo(new Rule(3, Duration.ofHours(1)));
        assertThatThrownBy(() -> Rule.valueOf("5")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Rule.valueOf("0/1m")).isInstanceOf(IllegalArgumentException.class);
    }
}
