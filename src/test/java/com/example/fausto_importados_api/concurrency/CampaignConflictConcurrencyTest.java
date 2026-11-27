package com.example.fausto_importados_api.concurrency;

import com.example.fausto_importados_api.model.Campaign;
import com.example.fausto_importados_api.model.enums.CampaignStatus;
import com.example.fausto_importados_api.repository.CampaignRepository;
import com.example.fausto_importados_api.services.CampaignConflictGuard;
import com.example.fausto_importados_api.services.exception.CampaignConflictException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static com.example.fausto_importados_api.support.CampaignFixtures.complete;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Publicações simultâneas contra um PostgreSQL de verdade. As rotas de publicação ainda não existem: {@link #publish}
 * faz o que o serviço fará.
 */
class CampaignConflictConcurrencyTest extends ConcurrencyTestSupport {

    private static final int THREADS = 8;

    @Autowired CampaignRepository campaigns;
    @Autowired CampaignConflictGuard guard;
    @Autowired Transactions transactions;

    private final List<UUID> created = new ArrayList<>();

    @AfterEach
    void cleanUp() {
        created.forEach(id -> jdbc.update("delete from campaigns where id = ?", id));
    }

    @Test
    void onlyOneOfManySimultaneousConflictingCampaignsIsPublished() throws Exception {
        List<UUID> drafts = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            // cada uma começa uma hora depois da anterior: todas se cruzam
            drafts.add(draft(5, day(10).plus(i, ChronoUnit.HOURS), day(20).plus(i, ChronoUnit.HOURS)));
        }

        List<Optional<CampaignConflictException>> results;
        try (AutoCloseable slow = slowDown("campaigns", "update", 0.2)) { // alarga a janela entre conferir e confirmar
            results = concurrently(THREADS, i -> () -> publish(drafts.get(i)));
        }

        assertThat(published(drafts)).hasSize(1);
        UUID winner = published(drafts).get(0);
        // todas as outras recebem o conflito esperado, dizendo com quem (nem erro do banco, nem desistência)
        assertThat(results.stream().flatMap(Optional::stream))
                .hasSize(THREADS - 1)
                .allSatisfy(conflict -> assertThat(conflict.conflicting().id()).isEqualTo(winner));
    }

    @Test
    void simultaneousCampaignsWithDifferentPrioritiesAreAllPublished() {
        List<UUID> drafts = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            drafts.add(draft(i, day(10), day(20)));
        }

        List<Optional<CampaignConflictException>> results = concurrently(THREADS, i -> () -> publish(drafts.get(i)));

        assertThat(results).allMatch(Optional::isEmpty);
        assertThat(published(drafts)).hasSize(THREADS);
    }

    @Test
    void simultaneousBackToBackCampaignsAreAllPublished() {
        List<UUID> drafts = new ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            drafts.add(draft(5, day(i + 1), day(i + 2))); // cada uma começa quando a anterior termina
        }

        List<Optional<CampaignConflictException>> results = concurrently(THREADS, i -> () -> publish(drafts.get(i)));

        assertThat(results).allMatch(Optional::isEmpty);
        assertThat(published(drafts)).hasSize(THREADS);
    }

    @Test
    void movingAPublishedCampaignOntoAnotherIsRefused() {
        UUID first = draft(5, day(1), day(5));
        UUID second = draft(5, day(10), day(15));
        assertThat(publish(first)).isEmpty();
        assertThat(publish(second)).isEmpty();

        Optional<CampaignConflictException> result = write(second, campaign -> campaign.setStartsAt(day(4)));

        assertThat(result).hasValueSatisfying(e -> assertThat(e.conflicting().id()).isEqualTo(first));
        assertThat(campaigns.findById(second).orElseThrow().getStartsAt()).isEqualTo(day(10));
    }

    // ───────────────────────────── constraint do banco ─────────────────────────────

    @Test
    void theDatabaseRefusesAnOverlapEvenWithoutTheGuard() {
        UUID first = draft(5, day(10), day(20));
        UUID second = draft(5, day(19), day(25));
        setStatus(first, "PUBLISHED");

        assertThatThrownBy(() -> setStatus(second, "PUBLISHED"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("excl_campaigns_overlap");
    }

    @Test
    void theDatabaseAcceptsWhatIsNotAConflict() {
        UUID first = draft(5, day(10), day(20));
        UUID touching = draft(5, day(20), day(25));
        UUID otherPriority = draft(6, day(10), day(20));
        UUID disabled = draft(5, day(10), day(20));

        setStatus(first, "PUBLISHED");
        setStatus(touching, "PUBLISHED");
        setStatus(otherPriority, "PUBLISHED");
        setStatus(disabled, "DISABLED");

        assertThat(published(List.of(first, touching, otherPriority, disabled))).hasSize(3);
    }

    // ───────────────────────────── helpers ─────────────────────────────

    private Optional<CampaignConflictException> publish(UUID id) {
        return write(id, campaign -> campaign.setStatus(CampaignStatus.PUBLISHED));
    }

    /** Como um serviço faz: trava a campanha, altera, confere o conflito e grava. */
    private Optional<CampaignConflictException> write(UUID id, java.util.function.Consumer<Campaign> change) {
        try {
            transactions.execute(() -> {
                Campaign campaign = campaigns.findByIdForUpdate(id).orElseThrow();
                change.accept(campaign);
                campaign.registerChange(Instant.now(), "admin@test.com");
                guard.ensureNoConflict(campaign);
                campaigns.saveAndFlush(campaign);
            });
            return Optional.empty();
        } catch (CampaignConflictException conflict) {
            return Optional.of(conflict);
        }
    }

    private UUID draft(int priority, Instant start, Instant end) {
        Campaign campaign = complete();
        campaign.setPriority(priority);
        campaign.setStartsAt(start);
        campaign.setEndsAt(end);
        UUID id = campaigns.saveAndFlush(campaign).getId();
        created.add(id);
        return id;
    }

    private void setStatus(UUID id, String status) {
        jdbc.update("update campaigns set status = ? where id = ?", status, id);
    }

    private List<UUID> published(List<UUID> ids) {
        return ids.stream()
                .filter(id -> "PUBLISHED".equals(
                        jdbc.queryForObject("select status from campaigns where id = ?", String.class, id)))
                .toList();
    }

    private static Instant day(int day) {
        return Instant.parse("2027-03-01T00:00:00Z").plus(day - 1L, ChronoUnit.DAYS);
    }
}
