package com.example.fausto_importados_api.service;

import com.example.fausto_importados_api.exception.GlobalExceptionHandler;
import com.example.fausto_importados_api.model.Campaign;
import com.example.fausto_importados_api.model.enums.CampaignStatus;
import com.example.fausto_importados_api.repository.CampaignRepository;
import com.example.fausto_importados_api.services.CampaignConflictGuard;
import com.example.fausto_importados_api.services.exception.CampaignConflictException;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static com.example.fausto_importados_api.support.CampaignFixtures.complete;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * O que é e o que não é conflito entre campanhas. A garantia sob concorrência e a constraint do banco estão em
 * CampaignConflictConcurrencyTest (PostgreSQL).
 */
@DataJpaTest(properties = "spring.datasource.url=jdbc:h2:mem:campaignconflict;DB_CLOSE_DELAY=-1")
@Import(CampaignConflictGuard.class)
@ActiveProfiles("test")
class CampaignConflictGuardTest {

    @Autowired CampaignConflictGuard guard;
    @Autowired CampaignRepository campaigns;

    // existente: do dia 10 ao dia 20
    @ParameterizedTest
    @CsvSource({
            "10, 20", // mesmo período
            "12, 18", // dentro
            "5, 25",  // em volta
            "5, 11",  // cruza o começo
            "19, 25", // cruza o fim
    })
    void overlappingPeriodsOfTheSamePriorityConflict(int start, int end) {
        Campaign existing = published(5, day(10), day(20));

        assertThatThrownBy(() -> guard.ensureNoConflict(candidate(5, day(start), day(end))))
                .isInstanceOfSatisfying(CampaignConflictException.class, e -> {
                    assertThat(e.status().value()).isEqualTo(409);
                    assertThat(e.code()).isEqualTo("campaign_conflict");
                    assertThat(e.conflicting()).isEqualTo(new CampaignConflictException.ConflictingCampaign(
                            existing.getId(), existing.getName(), day(10), day(20)));
                });
    }

    @ParameterizedTest
    @CsvSource({
            "1, 10",  // termina quando a outra começa
            "20, 25", // começa quando a outra termina
            "1, 5",
            "25, 28",
    })
    void periodsThatOnlyTouchOrAreApartDoNotConflict(int start, int end) {
        published(5, day(10), day(20));

        guard.ensureNoConflict(candidate(5, day(start), day(end)));
    }

    @Test
    void oneInstantOfOverlapIsAConflict() {
        published(5, day(10), day(20));

        assertThatThrownBy(() -> guard.ensureNoConflict(
                candidate(5, day(20).minus(1, ChronoUnit.MICROS), day(25))))
                .isInstanceOf(CampaignConflictException.class);
    }

    @Test
    void anotherPriorityDoesNotConflict() {
        published(5, day(10), day(20));

        guard.ensureNoConflict(candidate(4, day(10), day(20)));
        guard.ensureNoConflict(candidate(6, day(10), day(20)));
    }

    @ParameterizedTest
    @EnumSource(value = CampaignStatus.class, names = "PUBLISHED", mode = EnumSource.Mode.EXCLUDE)
    void onlyPublishedCampaignsOccupyAPeriod(CampaignStatus other) {
        save(other, 5, day(10), day(20));

        guard.ensureNoConflict(candidate(5, day(10), day(20)));
    }

    @ParameterizedTest
    @EnumSource(value = CampaignStatus.class, names = "PUBLISHED", mode = EnumSource.Mode.EXCLUDE)
    void aCampaignThatWillNotBePublishedIsNotChecked(CampaignStatus own) {
        published(5, day(10), day(20));
        Campaign candidate = candidate(5, day(10), day(20));
        candidate.setStatus(own);

        guard.ensureNoConflict(candidate);
    }

    @Test
    void aCampaignDoesNotConflictWithItself() {
        Campaign existing = published(5, day(10), day(20));
        existing.setEndsAt(day(25));

        guard.ensureNoConflict(existing);
    }

    @Test
    void anExpiredPublishedCampaignStillOccupiesItsPeriod() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        published(5, now.minus(20, ChronoUnit.DAYS), now.minus(10, ChronoUnit.DAYS));

        assertThatThrownBy(() -> guard.ensureNoConflict(
                candidate(5, now.minus(15, ChronoUnit.DAYS), now.plus(5, ChronoUnit.DAYS))))
                .isInstanceOf(CampaignConflictException.class);
    }

    @Test
    void theEarliestConflictingCampaignIsReported() {
        published(5, day(15), day(20));
        Campaign first = published(5, day(10), day(15));

        assertThatThrownBy(() -> guard.ensureNoConflict(candidate(5, day(1), day(30))))
                .isInstanceOfSatisfying(CampaignConflictException.class,
                        e -> assertThat(e.conflicting().id()).isEqualTo(first.getId()));
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void itRefusesToRunOutsideATransaction() {
        assertThatThrownBy(() -> guard.ensureNoConflict(candidate(5, day(10), day(20))))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    // ───────────────────────────── resposta ─────────────────────────────

    @RestController
    static class ProbeController {
        @GetMapping("/probe")
        void probe() {
            Campaign other = complete();
            other.setName("Black Friday 2026");
            throw new CampaignConflictException(other); // 27/11 00:00 a 01/12 00:00 em Fortaleza
        }
    }

    @Test
    void theAnswerSaysWhichCampaignConflicts() throws Exception {
        var mapper = Jackson2ObjectMapperBuilder.json()
                .featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS) // como o Spring Boot
                .build();
        var mvc = MockMvcBuilders.standaloneSetup(new ProbeController())
                .setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new MappingJackson2HttpMessageConverter(mapper))
                .build();
        String message = "O período se sobrepõe ao da campanha \"Black Friday 2026\" (27/11/2026 00:00 a "
                + "01/12/2026 00:00), que tem a mesma prioridade. Mude as datas ou a prioridade, ou desative ou "
                + "arquive a outra.";

        mvc.perform(get("/probe"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("campaign_conflict"))
                .andExpect(jsonPath("$.message").value(message))
                .andExpect(jsonPath("$.error").value(message))
                .andExpect(jsonPath("$.conflictingCampaign.name").value("Black Friday 2026"))
                .andExpect(jsonPath("$.conflictingCampaign.startsAt").value("2026-11-27T03:00:00Z"))
                .andExpect(jsonPath("$.conflictingCampaign.endsAt").value("2026-12-01T03:00:00Z"));
    }

    // ───────────────────────────── helpers ─────────────────────────────

    private Campaign published(int priority, Instant start, Instant end) {
        return save(CampaignStatus.PUBLISHED, priority, start, end);
    }

    private Campaign save(CampaignStatus status, int priority, Instant start, Instant end) {
        Campaign campaign = candidate(priority, start, end);
        campaign.setStatus(status);
        return campaigns.saveAndFlush(campaign);
    }

    /** Uma campanha prestes a ser publicada. */
    private static Campaign candidate(int priority, Instant start, Instant end) {
        Campaign campaign = complete();
        campaign.setStatus(CampaignStatus.PUBLISHED);
        campaign.setPriority(priority);
        campaign.setStartsAt(start);
        campaign.setEndsAt(end);
        return campaign;
    }

    private static Instant day(int day) {
        return Instant.parse("2026-12-01T00:00:00Z").plus(day - 1L, ChronoUnit.DAYS);
    }
}
