package com.example.fausto_importados_api.security.mfa;

import com.example.fausto_importados_api.concurrency.Transactions;
import com.example.fausto_importados_api.model.MfaRecoveryCode;
import com.example.fausto_importados_api.repository.MfaRecoveryCodeRepository;
import com.example.fausto_importados_api.repository.UserRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Códigos de recuperação do 2FA: 10 códigos de uso único, 80 bits cada, guardados só como hash (SHA-256).
 * Com essa entropia um hash simples basta: não há o que adivinhar offline.
 */
@Service
public class RecoveryCodeService {

    public static final int COUNT = 10;
    static final int LENGTH = 16;
    // sem I, O, 0 e 1: evita confusão ao digitar
    private static final String ALPHABET_TEXT = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final char[] ALPHABET = ALPHABET_TEXT.toCharArray();

    private final MfaRecoveryCodeRepository repository;
    private final UserRepository users;
    private final Transactions transactions;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();

    public RecoveryCodeService(
            MfaRecoveryCodeRepository repository, UserRepository users, Transactions transactions, Clock clock
    ) {
        this.repository = repository;
        this.users = users;
        this.transactions = transactions;
        this.clock = clock;
    }

    /**
     * Gera um conjunto novo (descartando o anterior). Os códigos em claro só existem no retorno desta chamada.
     * Formato exibido: {@code XXXX-XXXX-XXXX-XXXX}.
     */
    public List<String> generate(UUID userId) {
        return transactions.run(() -> {
            // travado: duas gerações simultâneas não deixam dois conjuntos válidos
            users.findByIdForUpdate(userId);
            repository.deleteAllByUser(userId);
            List<String> display = new ArrayList<>(COUNT);
            List<MfaRecoveryCode> rows = new ArrayList<>(COUNT);
            for (int i = 0; i < COUNT; i++) {
                String code = randomCode();
                display.add(format(code));

                MfaRecoveryCode row = new MfaRecoveryCode();
                row.setId(UUID.randomUUID());
                row.setUserId(userId);
                row.setCodeHash(hash(code));
                row.setCreatedAt(clock.instant());
                rows.add(row);
            }
            repository.saveAll(rows);
            return display;
        });
    }

    /** Usa o código, se for válido e ainda não usado. Atômico: dois usos simultâneos, só um passa. */
    @Transactional
    public boolean consume(UUID userId, String input) {
        String normalized = normalize(input);
        if (normalized == null) {
            return false;
        }
        return repository.consume(userId, hash(normalized), clock.instant()) == 1;
    }

    public long remaining(UUID userId) {
        return repository.countByUserIdAndUsedAtIsNull(userId);
    }

    /** O texto tem a forma de um código de recuperação? */
    public static boolean looksLikeCode(String input) {
        return normalize(input) != null;
    }

    /** Maiúsculas, sem hífens/espaços; {@code null} se não tiver o formato certo. */
    static String normalize(String input) {
        if (input == null) {
            return null;
        }
        String s = input.replace("-", "").replace(" ", "").toUpperCase(Locale.ROOT);
        if (s.length() != LENGTH) {
            return null;
        }
        for (int i = 0; i < s.length(); i++) {
            if (ALPHABET_TEXT.indexOf(s.charAt(i)) < 0) {
                return null;
            }
        }
        return s;
    }

    private String randomCode() {
        StringBuilder sb = new StringBuilder(LENGTH);
        for (int i = 0; i < LENGTH; i++) {
            sb.append(ALPHABET[random.nextInt(ALPHABET.length)]);
        }
        return sb.toString();
    }

    private static String format(String code) {
        return code.substring(0, 4) + "-" + code.substring(4, 8) + "-" + code.substring(8, 12) + "-" + code.substring(12);
    }

    static String hash(String code) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(code.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
