package com.example.fausto_importados_api.security.ratelimit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * O proxy do front (função da Vercel) informa o IP do visitante em {@code X-Client-IP}; a API só acredita nele
 * quando {@code X-Proxy-Secret} confere, e com {@code require-for-auth} ninguém chega a {@code /auth/**} sem o segredo.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:proxygate;DB_CLOSE_DELAY=-1",
        "security.proxy.shared-secret=0123456789abcdef0123456789abcdef-secret",
        "security.proxy.require-for-auth=true",
        "security.rate-limit.trusted-proxy-hops=1",
        "security.rate-limit.login-ip=2/1m",
        "security.rate-limit.api-anon=3/1m"
})
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ProxyGateFilterTest {

    private static final String SECRET = "0123456789abcdef0123456789abcdef-secret";
    private static final AtomicInteger NEXT = new AtomicInteger(1);

    @Autowired MockMvc mvc;

    /** Endereço de conexão (o que a API enxerga do proxy) que ninguém mais usa. */
    private static String freshPeer() {
        int n = NEXT.getAndIncrement();
        return "192.0.2." + n;
    }

    private int login(String peer, String secret, String clientIp) throws Exception {
        MockHttpServletRequestBuilder request = post("/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"nobody-" + NEXT.getAndIncrement() + "@test.com\",\"password\":\"x\"}")
                .with(r -> {
                    r.setRemoteAddr(peer);
                    return r;
                });
        if (secret != null) {
            request.header(ProxyGate.SECRET_HEADER, secret);
        }
        if (clientIp != null) {
            request.header(ProxyGate.CLIENT_IP_HEADER, clientIp);
        }
        return mvc.perform(request).andReturn().getResponse().getStatus();
    }

    private int publicGet(String peer, String secret, String clientIp) throws Exception {
        MockHttpServletRequestBuilder request = get("/api/product").with(r -> {
            r.setRemoteAddr(peer);
            return r;
        });
        if (secret != null) {
            request.header(ProxyGate.SECRET_HEADER, secret);
        }
        if (clientIp != null) {
            request.header(ProxyGate.CLIENT_IP_HEADER, clientIp);
        }
        return mvc.perform(request).andReturn().getResponse().getStatus();
    }

    @Test
    void authRoutes_answer404_withoutTheSecret_orWithAWrongOne() throws Exception {
        String peer = freshPeer();

        assertThat(login(peer, null, null)).isEqualTo(404);
        assertThat(login(peer, "wrong-secret", "203.0.113.9")).isEqualTo(404);
        assertThat(login(peer, SECRET + "x", null)).isEqualTo(404);
    }

    @Test
    void authRoutes_work_withTheSecret() throws Exception {
        int status = login(freshPeer(), SECRET, "203.0.113.20");

        assertThat(status).isEqualTo(401); // credenciais inválidas, mas chegou ao login
    }

    @Test
    void theLoginLimit_isPerVisitorIp_notPerProxyAddress() throws Exception {
        String sameProxy = freshPeer(); // todos chegam pelo mesmo endereço de saída da Vercel

        assertThat(login(sameProxy, SECRET, "203.0.113.31")).isEqualTo(401);
        assertThat(login(sameProxy, SECRET, "203.0.113.31")).isEqualTo(401);
        assertThat(login(sameProxy, SECRET, "203.0.113.31")).as("3ª do mesmo visitante").isEqualTo(429);

        // outro visitante, pelo mesmo endereço de saída, não é afetado
        assertThat(login(sameProxy, SECRET, "203.0.113.32")).isEqualTo(401);
    }

    @Test
    void aClientIpWithoutTheSecret_isIgnored_soItCannotPickABucket() throws Exception {
        String peer = freshPeer();

        // api-anon = 3/1m. Cada requisição "alega" um IP diferente, mas sem o segredo todas contam para o mesmo balde
        assertThat(publicGet(peer, null, "203.0.113.41")).isNotEqualTo(429);
        assertThat(publicGet(peer, "wrong", "203.0.113.42")).isNotEqualTo(429);
        assertThat(publicGet(peer, null, "203.0.113.43")).isNotEqualTo(429);
        assertThat(publicGet(peer, null, "203.0.113.44")).as("4ª: o IP alegado não separa os baldes").isEqualTo(429);
    }

    @Test
    void aClientIpWithTheSecret_isUsedOutsideAuthToo() throws Exception {
        String peer = freshPeer();

        for (int i = 0; i < 6; i++) {
            assertThat(publicGet(peer, SECRET, "203.0.113." + (100 + i))).as("visitante %d", i).isNotEqualTo(429);
        }
    }

    @Test
    void publicRoutesOutsideAuth_doNotNeedTheSecret() throws Exception {
        assertThat(publicGet(freshPeer(), null, null)).isNotEqualTo(404).isNotEqualTo(429);
    }
}
