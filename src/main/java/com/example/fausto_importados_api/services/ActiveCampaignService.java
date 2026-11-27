package com.example.fausto_importados_api.services;

import com.example.fausto_importados_api.model.Campaign;
import com.example.fausto_importados_api.model.enums.CampaignPhase;
import com.example.fausto_importados_api.model.enums.CampaignStatus;
import com.example.fausto_importados_api.repository.CampaignRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/**
 * Decide qual campanha está no ar. Nada é gravado nem agendado: a resposta sai das datas e do {@code Clock} a cada
 * consulta, e quem consulta usa {@link Resolution#nextChangeAt} para saber quando perguntar de novo.
 *
 * <p>Entre as campanhas publicadas no período, vence a de maior prioridade. Campanhas de mesma prioridade não deveriam
 * se sobrepor; se acontecer, o desempate é sempre o mesmo: a de início mais recente, depois a criada por último e, por
 * fim, a de menor id.
 *
 * <p>Os cálculos são feitos com instantes; o fuso ({@code America/Fortaleza}) é aplicado pelo painel ao exibir e
 * receber as datas.
 */
@Service
public class ActiveCampaignService {

    // a "maior" é a que vence
    private static final Comparator<Campaign> PRECEDENCE = Comparator
            .comparingInt(Campaign::getPriority)
            .thenComparing(Campaign::getStartsAt)
            .thenComparing(Campaign::getCreatedAt)
            .thenComparing(campaign -> campaign.getId().toString(), Comparator.reverseOrder());

    /**
     * @param serverTime   instante usado no cálculo
     * @param campaign     a campanha no ar, com coleções e produtos carregados; nula = a loja usa o tema padrão
     * @param nextChangeAt próximo instante em que a campanha no ar muda sozinha (inclusive passando a não haver
     *                     nenhuma); nulo = nada previsto
     */
    public record Resolution(Instant serverTime, Campaign campaign, Instant nextChangeAt) {
    }

    private final CampaignRepository campaigns;
    private final Clock clock;

    public ActiveCampaignService(CampaignRepository campaigns, Clock clock) {
        this.campaigns = campaigns;
        this.clock = clock;
    }

    // REPEATABLE READ: as duas consultas enxergam o mesmo estado, mesmo com uma edição sendo confirmada entre elas
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public Resolution resolve() {
        // a precisão do banco; com mais casas, o parâmetro poderia ser arredondado para o outro lado do limite
        Instant now = clock.instant().truncatedTo(ChronoUnit.MICROS);
        List<Campaign> published = campaigns.findByStatusAndEndsAtAfter(CampaignStatus.PUBLISHED, now);

        Campaign onAir = onAirAt(published, now);
        Instant nextChange = nextChange(published, now, onAir);
        Campaign detailed = onAir == null ? null : campaigns.findDetailById(onAir.getId()).orElseThrow();
        return new Resolution(now, detailed, nextChange);
    }

    private static Campaign onAirAt(List<Campaign> published, Instant instant) {
        return published.stream()
                .filter(campaign -> campaign.phaseAt(instant) == CampaignPhase.ACTIVE)
                .max(PRECEDENCE)
                .orElse(null);
    }

    // a campanha no ar só pode mudar num início ou num fim; vale o primeiro em que ela de fato muda
    private static Instant nextChange(List<Campaign> published, Instant now, Campaign current) {
        return published.stream()
                .flatMap(campaign -> Stream.of(campaign.getStartsAt(), campaign.getEndsAt()))
                .filter(instant -> instant.isAfter(now))
                .sorted()
                .filter(instant -> onAirAt(published, instant) != current)
                .findFirst()
                .orElse(null);
    }
}
