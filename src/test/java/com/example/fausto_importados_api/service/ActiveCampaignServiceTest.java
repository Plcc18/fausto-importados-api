package com.example.fausto_importados_api.service;

import com.example.fausto_importados_api.model.Campaign;
import com.example.fausto_importados_api.model.enums.CampaignPhase;
import com.example.fausto_importados_api.model.enums.CampaignStatus;
import com.example.fausto_importados_api.repository.CampaignRepository;
import com.example.fausto_importados_api.services.ActiveCampaignService;
import com.example.fausto_importados_api.services.ActiveCampaignService.Resolution;
import com.example.fausto_importados_api.support.MutableClock;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static com.example.fausto_importados_api.support.CampaignFixtures.collection;
import static com.example.fausto_importados_api.support.CampaignFixtures.complete;
import static com.example.fausto_importados_api.support.CampaignFixtures.productIds;
import static org.assertj.core.api.Assertions.assertThat;

/** Qual campanha está no ar, com o relógio controlado. */
// sem transação em volta, como em produção (open-in-view desligado): o que o serviço não carregar não pode ser lido
@DataJpaTest(properties = "spring.datasource.url=jdbc:h2:mem:activecampaign;DB_CLOSE_DELAY=-1")
@Import({ActiveCampaignService.class, ActiveCampaignServiceTest.ClockConfig.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@ActiveProfiles("test")
class ActiveCampaignServiceTest {

    @TestConfiguration
    static class ClockConfig {
        @Bean
        MutableClock clock() {
            return new MutableClock(Instant.EPOCH);
        }
    }

    private static final Instant START = day(10);
    private static final Instant END = day(20);

    @Autowired ActiveCampaignService service;
    @Autowired CampaignRepository campaigns;
    @Autowired MutableClock clock;

    @AfterEach
    void cleanUp() {
        campaigns.deleteAll();
    }

    // ───────────────────────────── limites ─────────────────────────────

    @Test
    void withoutCampaignsTheStoreUsesTheDefaultTheme() {
        clock.set(START);

        assertThat(service.resolve()).isEqualTo(new Resolution(START, null, null));
    }

    @Test
    void theStartIsInclusiveAndTheEndIsExclusive() {
        Campaign campaign = published(0, START, END);

        assertOnAir(before(START), null, START);
        assertOnAir(START, campaign, END);
        assertOnAir(before(END), campaign, END);
        assertOnAir(END, null, null);
        assertOnAir(day(21), null, null);
    }

    @Test
    void midnightInFortalezaIsThreeInTheMorningUtc() {
        Instant midnight = LocalDateTime.parse("2026-11-27T00:00:00")
                .atZone(ZoneId.of("America/Fortaleza")).toInstant();
        Campaign campaign = published(0, midnight, midnight.plus(1, ChronoUnit.DAYS));

        assertThat(midnight).isEqualTo(Instant.parse("2026-11-27T03:00:00Z"));
        assertOnAir(Instant.parse("2026-11-27T02:59:59.999999Z"), null, midnight);
        assertOnAir(Instant.parse("2026-11-27T03:00:00Z"), campaign, Instant.parse("2026-11-28T03:00:00Z"));
    }

    @Test
    void thePhaseFollowsTheSameLimits() {
        Campaign campaign = published(0, START, END);

        assertThat(campaign.phaseAt(before(START))).isEqualTo(CampaignPhase.SCHEDULED);
        assertThat(campaign.phaseAt(START)).isEqualTo(CampaignPhase.ACTIVE);
        assertThat(campaign.phaseAt(before(END))).isEqualTo(CampaignPhase.ACTIVE);
        assertThat(campaign.phaseAt(END)).isEqualTo(CampaignPhase.EXPIRED);
    }

    @ParameterizedTest
    @EnumSource(value = CampaignStatus.class, names = "PUBLISHED", mode = EnumSource.Mode.EXCLUDE)
    void onlyPublishedCampaignsGoOnAir(CampaignStatus status) {
        Campaign campaign = campaign(status, 100, START, END);

        assertThat(campaign.phaseAt(day(15))).isNull();
        assertOnAir(day(5), null, null); // nem como próxima mudança
        assertOnAir(day(15), null, null);
    }

    // ───────────────────────────── prioridade e próxima mudança ─────────────────────────────

    @Test
    void theHighestPriorityWins() {
        Campaign low = published(1, START, END);
        Campaign high = published(50, START, END);

        assertOnAir(day(15), high, END);
        assertThat(low.getPriority()).isLessThan(high.getPriority());
    }

    @Test
    void aStrongerCampaignInterruptsAndThenGivesTheAirBack() {
        Campaign season = published(1, day(10), day(30));
        Campaign weekend = published(10, day(15), day(17));

        assertOnAir(day(12), season, day(15));
        assertOnAir(day(15), weekend, day(17));
        assertOnAir(day(17), season, day(30));
        assertOnAir(day(30), null, null);
    }

    @Test
    void aWeakerCampaignThatNeverWinsDoesNotCountAsAChange() {
        Campaign strong = published(10, day(10), day(30));
        published(1, day(15), day(17));

        assertOnAir(day(12), strong, day(30));
        assertOnAir(day(16), strong, day(30));
    }

    @Test
    void aWeakerCampaignTakesOverWhenTheStrongerOneEnds() {
        Campaign strong = published(10, day(10), day(20));
        Campaign weak = published(1, day(15), day(30));

        assertOnAir(day(16), strong, day(20)); // o início da fraca (dia 15) não mudou nada
        assertOnAir(day(20), weak, day(30));
    }

    @Test
    void oneCampaignCanFollowAnotherAtTheSameInstant() {
        Campaign first = published(0, day(10), day(20));
        Campaign second = published(0, day(20), day(25));

        assertOnAir(before(day(20)), first, day(20));
        assertOnAir(day(20), second, day(25));
    }

    @Test
    void betweenTwoCampaignsTheNextChangeIsTheNextStart() {
        published(0, day(10), day(12));
        published(0, day(18), day(20));
        Campaign later = published(0, day(25), day(28));

        assertOnAir(day(15), null, day(18));
        assertOnAir(day(22), null, day(25));
        assertOnAir(day(26), later, day(28));
    }

    // ───────────────────────────── desempate ─────────────────────────────

    @Test
    void onATieTheMostRecentStartWins() {
        published(5, day(10), day(30));
        Campaign newer = published(5, day(12), day(30));

        assertOnAir(day(15), newer, day(30));
    }

    @Test
    void onATieWithTheSameStartTheLastOneCreatedWins() {
        Campaign older = published(5, START, END);
        Campaign newer = complete(); // criada depois, mas salva com a mesma forma
        newer.setCreatedAt(older.getCreatedAt().plusSeconds(1));
        newer = save(newer, CampaignStatus.PUBLISHED, 5, START, END);

        assertOnAir(day(15), newer, END);
    }

    @Test
    void onAFullTieTheSmallestIdWins() {
        List<Campaign> tied = List.of(published(5, START, END), published(5, START, END), published(5, START, END));
        UUID smallest = tied.stream().map(Campaign::getId)
                .min((a, b) -> a.toString().compareTo(b.toString())).orElseThrow();
        clock.set(day(15));

        assertThat(service.resolve().campaign().getId()).isEqualTo(smallest);
    }

    // ───────────────────────────── conteúdo ─────────────────────────────

    @Test
    void theCampaignOnAirComesWithItsCollectionsAndProducts() {
        // os produtos não precisam existir no H2 de teste (a chave estrangeira é do schema do Flyway)
        UUID product = UUID.randomUUID();
        Campaign campaign = complete();
        campaign.addCollection(collection("ofertas", 0, product));
        save(campaign, CampaignStatus.PUBLISHED, 0, START, END);
        clock.set(day(15));

        Campaign onAir = service.resolve().campaign();

        assertThat(productIds(onAir, "ofertas")).containsExactly(product);
        assertThat(onAir.getTheme()).isEqualTo(campaign.getTheme());
    }

    // ───────────────────────────── helpers ─────────────────────────────

    private void assertOnAir(Instant at, Campaign expected, Instant nextChangeAt) {
        clock.set(at);
        Resolution resolution = service.resolve();

        assertThat(resolution.campaign() == null ? null : resolution.campaign().getId())
                .as("campanha no ar em %s", at)
                .isEqualTo(expected == null ? null : expected.getId());
        assertThat(resolution.nextChangeAt()).as("próxima mudança em %s", at).isEqualTo(nextChangeAt);
    }

    private Campaign published(int priority, Instant start, Instant end) {
        return campaign(CampaignStatus.PUBLISHED, priority, start, end);
    }

    private Campaign campaign(CampaignStatus status, int priority, Instant start, Instant end) {
        return save(complete(), status, priority, start, end);
    }

    private Campaign save(Campaign campaign, CampaignStatus status, int priority, Instant start, Instant end) {
        campaign.setStatus(status);
        campaign.setPriority(priority);
        campaign.setStartsAt(start);
        campaign.setEndsAt(end);
        return campaigns.save(campaign);
    }

    private static Instant day(int day) {
        return Instant.parse("2026-12-01T00:00:00Z").plus(day - 1L, ChronoUnit.DAYS);
    }

    /** O último instante anterior, na precisão do banco. */
    private static Instant before(Instant instant) {
        return instant.minus(1, ChronoUnit.MICROS);
    }
}
