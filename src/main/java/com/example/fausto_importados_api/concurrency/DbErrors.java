package com.example.fausto_importados_api.concurrency;

import java.sql.SQLException;
import java.util.Optional;
import java.util.Set;

/** Classifica erros do banco pelo SQLSTATE, que não muda com a camada (driver, Hibernate, Spring) que o embrulha. */
public final class DbErrors {

    /**
     * Falhas que somem ao repetir a transação inteira: serialização (40001), deadlock (40P01) e lock timeout
     * (55P03 no PostgreSQL, HYT00 no H2). Conflito de versão (edição do painel) NÃO entra: repetir sobrescreveria
     * a alteração de outra pessoa.
     */
    private static final Set<String> TRANSIENT = Set.of("40001", "40P01", "55P03", "HYT00");

    private DbErrors() {
    }

    public static boolean isTransient(Throwable error) {
        return sqlState(error).map(TRANSIENT::contains).orElse(false);
    }

    public static Optional<String> sqlState(Throwable error) {
        int depth = 0;
        for (Throwable t = error; t != null && depth++ < 20; t = t.getCause()) {
            if (t instanceof SQLException sql && sql.getSQLState() != null) {
                return Optional.of(sql.getSQLState());
            }
        }
        return Optional.empty();
    }
}
