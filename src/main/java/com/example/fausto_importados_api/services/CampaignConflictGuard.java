package com.example.fausto_importados_api.services;

import com.example.fausto_importados_api.model.Campaign;
import com.example.fausto_importados_api.model.enums.CampaignStatus;
import com.example.fausto_importados_api.repository.CampaignRepository;
import com.example.fausto_importados_api.services.exception.CampaignConflictException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Impede duas campanhas publicadas de mesma prioridade com períodos sobrepostos.
 *
 * <p>Quem publica, reativa ou edita uma campanha publicada chama {@link #ensureNoConflict} dentro de
 * {@code Transactions.run}, depois de deixar status, prioridade e datas como vão ficar e antes de gravar.
 *
 * <p>Sob concorrência, um lock de transação faz as publicações passarem uma de cada vez: quem chega depois enxerga a
 * que acabou de ser confirmada e recebe o conflito com o nome dela. A constraint {@code excl_campaigns_overlap} (V11)
 * recusa a sobreposição mesmo numa escrita que não passe por aqui.
 */
@Component
public class CampaignConflictGuard {

    /** Chave do advisory lock: um número qualquer, igual em todas as publicações. */
    private static final long PUBLICATION_LOCK = 7_230_001L;

    private static final UUID NO_ID = new UUID(0, 0);

    private final CampaignRepository campaigns;
    private final JdbcTemplate jdbc;
    private final boolean postgres;

    public CampaignConflictGuard(CampaignRepository campaigns, JdbcTemplate jdbc) {
        this.campaigns = campaigns;
        this.jdbc = jdbc;
        // advisory lock só existe no PostgreSQL; o H2 dos testes não exercita concorrência
        this.postgres = Boolean.TRUE.equals(jdbc.execute((ConnectionCallback<Boolean>) connection ->
                "PostgreSQL".equals(connection.getMetaData().getDatabaseProductName())));
    }

    /**
     * Não faz nada se a campanha não vai ficar publicada. Sem transação aberta, falha: o lock não valeria.
     *
     * @throws CampaignConflictException se outra campanha publicada de mesma prioridade ocupa parte do período
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void ensureNoConflict(Campaign campaign) {
        if (campaign.getStatus() != CampaignStatus.PUBLISHED) {
            return;
        }
        lockPublications();
        UUID self = campaign.getId() == null ? NO_ID : campaign.getId();
        campaigns.findPublishedOverlapping(
                        campaign.getPriority(), campaign.getStartsAt(), campaign.getEndsAt(), self)
                .stream()
                .findFirst()
                .ifPresent(other -> {
                    throw new CampaignConflictException(other);
                });
    }

    /** Liberado sozinho no commit ou rollback. */
    private void lockPublications() {
        if (postgres) {
            jdbc.query("select pg_advisory_xact_lock(?)", (RowCallbackHandler) row -> { }, PUBLICATION_LOCK);
        }
    }
}
