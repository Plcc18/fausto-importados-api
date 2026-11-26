package com.example.fausto_importados_api.services;

import com.example.fausto_importados_api.concurrency.Transactions;
import com.example.fausto_importados_api.model.User;
import com.example.fausto_importados_api.repository.UserRepository;
import com.example.fausto_importados_api.security.refresh.RefreshTokenService;
import com.example.fausto_importados_api.services.exception.AccountConflictException;
import org.springframework.stereotype.Service;

import java.util.UUID;

/** Alterações que o próprio usuário faz na conta. */
@Service
public class AccountService {

    private final UserRepository users;
    private final RefreshTokenService sessions;
    private final Transactions transactions;

    public AccountService(UserRepository users, RefreshTokenService sessions, Transactions transactions) {
        this.users = users;
        this.sessions = sessions;
        this.transactions = transactions;
    }

    /**
     * Grava a senha nova e derruba as outras sessões, numa transação só.
     *
     * @param verifiedHash hash contra o qual a senha atual foi conferida; se a conta mudou desde então (desativada,
     *                     redefinida, sessão encerrada), nada é gravado
     * @param newHash      hash da senha nova, já calculado
     * @param sessionId    sessão a manter; nulo encerra todas
     */
    public void changePassword(UUID userId, String verifiedHash, String newHash, UUID sessionId) {
        transactions.execute(() -> {
            User user = users.findByIdForUpdate(userId).orElseThrow();
            if (!user.isEnabled() || !user.getPassword().equals(verifiedHash)
                    || (sessionId != null && !sessions.isSessionActive(sessionId))) {
                throw new AccountConflictException("The account changed while the request was being processed");
            }
            user.setPassword(newHash);
            users.saveAndFlush(user);
            if (sessionId != null) {
                sessions.revokeOtherSessions(userId, sessionId);
            } else {
                sessions.revokeAllSessions(userId);
            }
        });
    }
}
