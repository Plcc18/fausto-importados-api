package com.example.fausto_importados_api.controller;

import jakarta.servlet.http.HttpServletRequest;

/** Dados do cliente, usados para limitar tentativas de login e gravar a sessão. */
final class ClientInfo {

    private ClientInfo() {
    }

    /**
     * IP da conexão, como o proxy do Render a viu: o ÚLTIMO item de {@code X-Forwarded-For}.
     * Os itens anteriores vêm do cliente e podem ser forjados, então não servem para limitar tentativas.
     * Atrás do proxy da Vercel (rota /auth), este IP é o da própria Vercel, compartilhado — por isso o
     * controle principal de força bruta é por conta, não por IP.
     */
    static String ip(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            String[] hops = forwarded.split(",");
            return hops[hops.length - 1].trim();
        }
        return request.getRemoteAddr();
    }
}
