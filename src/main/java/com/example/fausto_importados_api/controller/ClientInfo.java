package com.example.fausto_importados_api.controller;

import jakarta.servlet.http.HttpServletRequest;

import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.regex.Pattern;

/** Dados do cliente, usados para limitar requisições e gravar a sessão e a auditoria. */
public final class ClientInfo {

    /** Atributo da requisição com o IP já resolvido pelo {@code RateLimitFilter} (primeiro filtro da cadeia). */
    public static final String ATTRIBUTE = "fausto.clientIp";

    private static final Pattern IPV4 = Pattern.compile("^(25[0-5]|2[0-4]\\d|1?\\d?\\d)(\\.(25[0-5]|2[0-4]\\d|1?\\d?\\d)){3}$");
    private static final Pattern IPV6_CHARS = Pattern.compile("^[0-9A-Fa-f:.]{2,45}$");

    private ClientInfo() {
    }

    /** IP do cliente desta requisição. Dentro da cadeia de filtros, vem do que o rate limit já resolveu. */
    public static String ip(HttpServletRequest request) {
        return request.getAttribute(ATTRIBUTE) instanceof String resolved ? resolved : resolve(request, 1);
    }

    /**
     * Descobre o IP do cliente sem confiar no que o próprio cliente escreve.
     *
     * <p>Cada proxy seu acrescenta ao FIM de {@code X-Forwarded-For} o endereço de quem falou com ele. Tudo o que vem
     * antes dos proxies é controlado por quem fez a requisição e pode ser forjado. Por isso, com {@code trustedHops}
     * proxies na frente da API (Vercel + Render = 2), o IP do cliente é o item {@code trustedHops} contando a partir
     * do fim: o que o primeiro proxy viu. Itens à esquerda dele são ignorados.
     *
     * <p>Com {@code trustedHops = 0}, ou se o cabeçalho tiver menos itens que proxies (a requisição não passou pela
     * cadeia esperada), ou se o item não for um IP válido, usa o endereço da conexão, que não se forja.
     *
     * <p>Limite conhecido: se a API puder ser acessada direto (sem passar pelo primeiro proxy), quem chama controla
     * o item e pode forjar o IP. Restrinja a origem ao proxy (ver README).
     */
    public static String resolve(HttpServletRequest request, int trustedHops) {
        String peer = request.getRemoteAddr();
        if (trustedHops <= 0) {
            return peer;
        }
        List<String> hops = forwardedFor(request);
        int index = hops.size() - trustedHops;
        if (index < 0) {
            return peer;
        }
        String candidate = hops.get(index);
        return isIp(candidate) ? candidate : peer;
    }

    /** Todos os itens de todas as linhas {@code X-Forwarded-For}, na ordem em que chegaram. */
    private static List<String> forwardedFor(HttpServletRequest request) {
        List<String> hops = new ArrayList<>();
        Enumeration<String> headers = request.getHeaders("X-Forwarded-For");
        while (headers != null && headers.hasMoreElements()) {
            for (String hop : headers.nextElement().split(",")) {
                String trimmed = hop.trim();
                if (!trimmed.isEmpty()) {
                    hops.add(trimmed);
                }
            }
        }
        return hops;
    }

    /** IP literal (sem DNS): IPv4 estrito, ou IPv6 (precisa de ':', então o JDK nunca o resolve por nome). */
    public static boolean isIp(String value) {
        if (value == null) {
            return false;
        }
        if (IPV4.matcher(value).matches()) {
            return true;
        }
        if (value.indexOf(':') < 0 || !IPV6_CHARS.matcher(value).matches()) {
            return false;
        }
        try {
            java.net.InetAddress.getByName(value);
            return true;
        } catch (java.net.UnknownHostException e) {
            return false;
        }
    }
}
