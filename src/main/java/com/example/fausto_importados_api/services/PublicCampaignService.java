package com.example.fausto_importados_api.services;

import com.example.fausto_importados_api.concurrency.ApiProblemException;
import com.example.fausto_importados_api.dto.campaign.ActiveCampaignResponse;
import com.example.fausto_importados_api.dto.campaign.PublicCampaign;
import com.example.fausto_importados_api.model.Campaign;
import com.example.fausto_importados_api.repository.CampaignRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

/**
 * A campanha como a loja a vê: a que está no ar, ou a prévia de uma qualquer.
 *
 * <p>REPEATABLE READ: campanha, coleções e produtos ativos saem do mesmo estado do banco.
 */
@Service
@Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
public class PublicCampaignService {

    private final ActiveCampaignService activeCampaigns;
    private final CampaignRepository campaigns;
    private final CampaignCatalog catalog;
    private final Clock clock;

    public PublicCampaignService(
            ActiveCampaignService activeCampaigns,
            CampaignRepository campaigns,
            CampaignCatalog catalog,
            Clock clock
    ) {
        this.activeCampaigns = activeCampaigns;
        this.campaigns = campaigns;
        this.catalog = catalog;
        this.clock = clock;
    }

    /** Rascunhos, agendadas, desativadas, encerradas e arquivadas nunca saem daqui. */
    public ActiveCampaignResponse active() {
        ActiveCampaignService.Resolution resolution = activeCampaigns.resolve();
        PublicCampaign onAir = resolution.campaign() == null ? null : publicView(resolution.campaign());
        return new ActiveCampaignResponse(resolution.serverTime(), resolution.nextChangeAt(), onAir);
    }

    /** Como a loja exibiria a campanha, em qualquer situação. Só para o painel; não altera nada. */
    public ActiveCampaignResponse preview(UUID id) {
        Campaign campaign = campaigns.findDetailById(id).orElseThrow(() -> new ApiProblemException(
                HttpStatus.NOT_FOUND, "not_found", "Campanha não encontrada."));
        return new ActiveCampaignResponse(
                clock.instant().truncatedTo(ChronoUnit.MICROS), null, publicView(campaign));
    }

    private PublicCampaign publicView(Campaign campaign) {
        return new PublicCampaign(campaign.getId(), campaign.getType(), campaign.getStartsAt(), campaign.getEndsAt(),
                campaign.getUpdatedAt(), campaign.getTheme(), campaign.getContent(),
                catalog.publicCollections(campaign));
    }
}
