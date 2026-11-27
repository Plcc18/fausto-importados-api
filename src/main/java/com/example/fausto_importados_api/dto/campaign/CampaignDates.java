package com.example.fausto_importados_api.dto.campaign;

import java.time.Instant;

/** Datas aceitas nas campanhas. Evita que um ano absurdo chegue ao banco e vire erro interno. */
public final class CampaignDates {

    public static final String OUT_OF_RANGE = "Use uma data entre 2000 e 2100.";

    private static final Instant EARLIEST = Instant.parse("2000-01-01T00:00:00Z");
    private static final Instant LIMIT = Instant.parse("2101-01-01T00:00:00Z");

    private CampaignDates() {
    }

    public static boolean isSupported(Instant instant) {
        return !instant.isBefore(EARLIEST) && instant.isBefore(LIMIT);
    }
}
