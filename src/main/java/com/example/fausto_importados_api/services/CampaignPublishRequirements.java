package com.example.fausto_importados_api.services;

import com.example.fausto_importados_api.dto.validation.InvalidField;
import com.example.fausto_importados_api.exception.FieldValidationException;
import com.example.fausto_importados_api.model.Campaign;
import com.example.fausto_importados_api.model.campaign.CampaignContent;
import com.example.fausto_importados_api.model.campaign.CampaignTheme;
import org.springframework.http.HttpStatus;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * O que uma campanha precisa ter para estar publicada (contrato da API, §6). Vale ao publicar e a cada edição de uma
 * campanha publicada.
 */
public final class CampaignPublishRequirements {

    public static final String CODE = "publish_requirements";

    private CampaignPublishRequirements() {
    }

    /** @throws FieldValidationException 422 {@value #CODE}, com o que falta */
    public static void check(Campaign campaign, Instant now) {
        List<InvalidField> missing = new ArrayList<>();
        require(missing, "name", campaign.getName(), "Informe o nome.");
        require(missing, "type", campaign.getType(), "Escolha o tipo.");
        require(missing, "startsAt", campaign.getStartsAt(), "Informe o início.");
        require(missing, "endsAt", campaign.getEndsAt(), "Informe o fim.");
        if (campaign.getEndsAt() != null && !campaign.getEndsAt().isAfter(now)) {
            missing.add(new InvalidField("endsAt", "O fim precisa ser no futuro."));
        }

        CampaignTheme theme = campaign.getTheme();
        CampaignTheme.Palette dark = theme == null ? null : theme.dark();
        if (dark == null) {
            missing.add(new InvalidField("theme.dark", "Defina as cores do tema escuro."));
        } else {
            require(missing, "theme.dark.background", dark.background(), "Preencha esta cor.");
            require(missing, "theme.dark.foreground", dark.foreground(), "Preencha esta cor.");
            require(missing, "theme.dark.primary", dark.primary(), "Preencha esta cor.");
            require(missing, "theme.dark.primaryForeground", dark.primaryForeground(), "Preencha esta cor.");
            require(missing, "theme.dark.accent", dark.accent(), "Preencha esta cor.");
        }

        CampaignContent content = campaign.getContent();
        if (content != null && content.hero() != null) {
            require(missing, "content.hero.imageDesktop", content.hero().imageDesktop(), "Envie a imagem.");
        }

        if (!missing.isEmpty()) {
            throw new FieldValidationException(HttpStatus.UNPROCESSABLE_ENTITY, CODE,
                    "Faltam campos obrigatórios para publicar.", missing);
        }
    }

    private static void require(List<InvalidField> missing, String field, Object value, String message) {
        if (value == null) {
            missing.add(new InvalidField(field, message));
        }
    }
}
