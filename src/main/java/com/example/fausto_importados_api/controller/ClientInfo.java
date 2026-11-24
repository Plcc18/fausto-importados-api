package com.example.fausto_importados_api.controller;

import jakarta.servlet.http.HttpServletRequest;

/** Dados do cliente gravados na sessão apenas para auditoria (nenhuma decisão de segurança depende deles). */
final class ClientInfo {

    private ClientInfo() {
    }

    /** IP do cliente. Atrás do proxy da Vercel e do Render vem em X-Forwarded-For (primeiro item). */
    static String ip(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }
}
