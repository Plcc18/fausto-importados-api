package com.example.fausto_importados_api.security.ratelimit;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Segredo compartilhado com o proxy do front (função da Vercel que repassa {@code /auth/*} para a API).
 *
 * <p>A Vercel não entrega o IP do visitante a quem recebe o rewrite: a API só enxerga o endereço de saída dela, que é
 * o mesmo para todos os usuários. A função da Vercel lê o IP real (que a própria Vercel preenche) e o repassa em
 * {@code X-Client-IP}, junto com {@code X-Proxy-Secret}. A API só acredita nesse IP quando o segredo confere.
 *
 * @param sharedSecret    segredo (mínimo 32 caracteres). Vazio = recurso desligado: nenhum cabeçalho é aceito
 * @param requireForAuth  se verdadeiro, {@code /auth/**} responde 404 a quem não trouxer o segredo (ninguém chega ao
 *                        login por fora do proxy, então o limite por IP não pode ser contornado indo direto ao Render).
 *                        Exige {@code sharedSecret}
 */
@ConfigurationProperties(prefix = "security.proxy")
public record ProxyProperties(
        @DefaultValue("") String sharedSecret,
        @DefaultValue("false") boolean requireForAuth
) {
}
