package com.example.fausto_importados_api.controller;

import com.example.fausto_importados_api.dto.campaign.ActiveCampaignResponse;
import com.example.fausto_importados_api.services.PublicCampaignService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** A campanha que a loja deve exibir. Público: não exige autenticação. */
@RestController
@RequestMapping("/api/campaigns")
public class PublicCampaignController {

    private final PublicCampaignService campaigns;

    public PublicCampaignController(PublicCampaignService campaigns) {
        this.campaigns = campaigns;
    }

    @GetMapping("/active")
    public ActiveCampaignResponse active() {
        return campaigns.active();
    }
}
