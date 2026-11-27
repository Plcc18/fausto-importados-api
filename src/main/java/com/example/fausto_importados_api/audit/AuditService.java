package com.example.fausto_importados_api.audit;

import com.example.fausto_importados_api.controller.ClientInfo;
import com.example.fausto_importados_api.model.AuditLog;
import com.example.fausto_importados_api.model.User;
import com.example.fausto_importados_api.repository.AuditLogRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/**
 * Trilha de auditoria: registra QUEM (usuário), O QUÊ (ação/entidade), QUANDO e DE ONDE (IP, user-agent).
 *
 * <ul>
 *   <li><b>Nunca</b> grava credenciais: chaves de {@code details} que lembram senha, token, segredo, código ou
 *       hash são substituídas por {@code [redacted]}, e textos longos são cortados. Detalhes que ainda não caibam
 *       perdem os maiores valores ({@code [omitted]}, com {@code truncated: true}), sempre como JSON válido.</li>
 *   <li>Cada registro é gravado numa transação própria: eventos de falha/negação persistem mesmo que a
 *       requisição termine em erro. Se a gravação falhar, o erro é logado e a operação segue — a
 *       auditoria nunca derruba o login nem o painel.</li>
 *   <li>Negações (403, bloqueios) têm limite por minuto para que um bot não encha a tabela.</li>
 * </ul>
 */
@Service
public class AuditService {

    private static final Logger log = LoggerFactory.getLogger(AuditService.class);

    private static final Pattern SENSITIVE_KEY =
            Pattern.compile("(?i).*(password|passwd|token|secret|code|hash|authorization|cookie|challenge).*");
    private static final int MAX_VALUE_LENGTH = 500;
    private static final int MAX_DETAILS_LENGTH = 16_000;
    private static final String OMITTED = "[omitted]";
    private static final int DENIED_PER_MINUTE = 10;

    private final AuditLogRepository repository;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final TransactionTemplate transaction;

    private final Map<String, long[]> deniedWindows = new ConcurrentHashMap<>();

    public AuditService(
            AuditLogRepository repository,
            ObjectMapper objectMapper,
            Clock clock,
            PlatformTransactionManager transactionManager
    ) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.transaction = new TransactionTemplate(transactionManager);
        this.transaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /** Registra com o usuário autenticado da requisição atual como autor. */
    public void record(AuditAction action, Outcome outcome, String entityType, Object entityId, Map<String, ?> details) {
        User actor = currentUser();
        write(actor == null ? null : actor.getId(), actor == null ? null : actor.getEmail(),
                action, outcome, entityType, entityId, details);
    }

    public void recordSuccess(AuditAction action, String entityType, Object entityId, Map<String, ?> details) {
        record(action, Outcome.SUCCESS, entityType, entityId, details);
    }

    /** Registra em nome de um usuário específico (login: ainda não há autenticação no contexto). */
    public void recordAs(User actor, AuditAction action, Outcome outcome, Map<String, ?> details) {
        write(actor == null ? null : actor.getId(), actor == null ? null : actor.getEmail(),
                action, outcome, actor == null ? null : "USER", actor == null ? null : actor.getId(), details);
    }

    /**
     * Tentativa cujo usuário não é conhecido (e-mail inexistente): não guarda o que foi digitado. Tem o mesmo limite
     * por minuto (por IP) das negações, porque qualquer um pode gerar esses eventos sem ter conta.
     */
    public void recordAnonymous(AuditAction action, Outcome outcome, Map<String, ?> details) {
        if (!allowDenied("i:" + currentIp())) {
            return;
        }
        write(null, null, action, outcome, null, null, details);
    }

    /**
     * Registra uma NEGAÇÃO com limite por minuto (por usuário ou, sem usuário, por IP). Acima do limite o evento
     * é descartado: o que importa é a ocorrência, não cada repetição de um bot.
     */
    public void recordDenied(AuditAction action, String entityType, Object entityId, Map<String, ?> details) {
        User actor = currentUser();
        String key = actor != null ? "u:" + actor.getId() : "i:" + currentIp();
        if (!allowDenied(key)) {
            return;
        }
        write(actor == null ? null : actor.getId(), actor == null ? null : actor.getEmail(),
                action, Outcome.DENIED, entityType, entityId, details);
    }

    /** Variante de {@link #recordDenied} para um usuário conhecido (login bloqueado, reuso de refresh token). */
    public void recordDeniedAs(User actor, AuditAction action, Map<String, ?> details) {
        String key = actor != null ? "u:" + actor.getId() : "i:" + currentIp();
        if (!allowDenied(key)) {
            return;
        }
        write(actor == null ? null : actor.getId(), actor == null ? null : actor.getEmail(),
                action, Outcome.DENIED, actor == null ? null : "USER", actor == null ? null : actor.getId(), details);
    }

    public int purgeOlderThan(Instant before) {
        Integer removed = transaction.execute(status -> repository.deleteOlderThan(before));
        return removed == null ? 0 : removed;
    }

    // ───────────────────────────── internos ─────────────────────────────

    private void write(
            UUID actorId, String actorEmail, AuditAction action, Outcome outcome,
            String entityType, Object entityId, Map<String, ?> details
    ) {
        try {
            AuditLog row = new AuditLog();
            row.setId(UUID.randomUUID());
            row.setOccurredAt(clock.instant());
            row.setActorId(actorId);
            row.setActorEmail(truncate(actorEmail, 150));
            row.setAction(action.name());
            row.setEntityType(truncate(entityType, 40));
            row.setEntityId(entityId == null ? null : truncate(entityId.toString(), 64));
            row.setOutcome(outcome);
            row.setIp(truncate(currentIp(), 64));
            row.setUserAgent(truncate(currentUserAgent(), 255));
            row.setDetails(serialize(details));
            transaction.executeWithoutResult(status -> repository.save(row));
        } catch (RuntimeException e) {
            // A auditoria não pode derrubar a operação, mas a falha precisa ser visível para quem opera.
            log.error("Falha ao gravar auditoria (action={}, outcome={}): {}", action, outcome, e.toString());
        }
    }

    private String serialize(Map<String, ?> details) {
        if (details == null || details.isEmpty()) {
            return null;
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> clean = (Map<String, Object>) sanitize(details);
            String json = objectMapper.writeValueAsString(clean);
            return json.length() <= MAX_DETAILS_LENGTH ? json : shrink(clean);
        } catch (JsonProcessingException e) {
            return "{\"error\":\"details not serializable\"}";
        }
    }

    /**
     * Um JSON cortado no meio não pode ser lido. Troca os maiores valores por um aviso até caber, olhando dentro de
     * {@code changes} para perder o mínimo.
     */
    @SuppressWarnings("unchecked")
    private String shrink(Map<String, Object> details) throws JsonProcessingException {
        List<Map.Entry<String, Object>> values = new ArrayList<>();
        details.entrySet().forEach(entry -> {
            if (entry.getKey().equals("changes") && entry.getValue() instanceof Map<?, ?> changes) {
                values.addAll(((Map<String, Object>) changes).entrySet());
            } else {
                values.add(entry);
            }
        });
        Map<Map.Entry<String, Object>, Integer> sizes = new IdentityHashMap<>();
        for (Map.Entry<String, Object> value : values) {
            sizes.put(value, objectMapper.writeValueAsString(value.getValue()).length());
        }
        values.sort(Comparator.comparing(sizes::get, Comparator.reverseOrder()));

        details.put("truncated", true);
        String json = objectMapper.writeValueAsString(details);
        for (int i = 0; i < values.size() && json.length() > MAX_DETAILS_LENGTH; i++) {
            values.get(i).setValue(OMITTED);
            json = objectMapper.writeValueAsString(details);
        }
        return json.length() <= MAX_DETAILS_LENGTH ? json : "{\"truncated\":true}";
    }

    /** Remove credenciais e corta valores longos. Público para teste. */
    public static Object sanitize(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            map.forEach((k, v) -> {
                String key = String.valueOf(k);
                out.put(key, SENSITIVE_KEY.matcher(key).matches() ? "[redacted]" : sanitize(v));
            });
            return out;
        }
        if (value instanceof List<?> list) {
            return list.stream().map(AuditService::sanitize).toList();
        }
        if (value instanceof CharSequence text) {
            return truncate(text.toString(), MAX_VALUE_LENGTH);
        }
        return value;
    }

    private boolean allowDenied(String key) {
        long now = clock.millis();
        if (deniedWindows.size() > 10_000) {
            deniedWindows.clear(); // evita crescer sem limite sob ataque com muitos IPs
        }
        long[] window = deniedWindows.compute(key, (k, w) -> {
            if (w == null || now - w[0] >= 60_000) {
                return new long[]{now, 1};
            }
            w[1]++;
            return w;
        });
        return window[1] <= DENIED_PER_MINUTE;
    }

    private static User currentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        return authentication != null && authentication.getPrincipal() instanceof User user ? user : null;
    }

    private static String currentIp() {
        HttpServletRequest request = request();
        return request == null ? null : ClientInfo.ip(request);
    }

    private static String currentUserAgent() {
        HttpServletRequest request = request();
        return request == null ? null : request.getHeader("User-Agent");
    }

    private static HttpServletRequest request() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attrs
                ? attrs.getRequest() : null;
    }

    private static String truncate(String value, int max) {
        if (value == null) return null;
        return value.length() <= max ? value : value.substring(0, max);
    }
}
