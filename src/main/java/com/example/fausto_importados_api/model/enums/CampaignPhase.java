package com.example.fausto_importados_api.model.enums;

/** Situação de uma campanha publicada, calculada pelas datas a cada leitura. Nunca é gravada. */
public enum CampaignPhase {
    SCHEDULED,  // antes do início
    ACTIVE,     // dentro do período (início inclusivo, fim exclusivo)
    EXPIRED     // depois do fim
}
