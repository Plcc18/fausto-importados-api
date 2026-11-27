package com.example.fausto_importados_api.controller;

import com.example.fausto_importados_api.dto.validation.FieldChecks;
import com.example.fausto_importados_api.exception.GlobalExceptionHandler;
import com.example.fausto_importados_api.model.campaign.CampaignContent;
import com.example.fausto_importados_api.model.campaign.CampaignTheme;
import com.example.fausto_importados_api.services.CampaignContentValidator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Como uma entrada inválida de campanha chega ao painel: 400 com a mensagem e o caminho de cada campo. As rotas de
 * campanha ainda não existem; aqui um controller de teste recebe {@code theme} e {@code content} como elas farão.
 */
class CampaignInputHttpTest {

    record Input(CampaignTheme theme, CampaignContent content) {
    }

    @RestController
    static class ProbeController {

        private final CampaignContentValidator validator = new CampaignContentValidator("test");

        @PostMapping("/probe")
        Input probe(@RequestBody Input input) {
            FieldChecks checks = new FieldChecks();
            Input clean = new Input(
                    validator.theme(input.theme(), checks), validator.content(input.content(), checks));
            checks.throwIfInvalid();
            return clean;
        }
    }

    private record Response(int status, String text, JsonNode body) {
    }

    private final MockMvc mvc = MockMvcBuilders.standaloneSetup(new ProbeController())
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void invalidValuesAnswer400WithTheMessageAndThePathOfEachField() throws Exception {
        Response response = send("""
                {"theme": {"dark": {"primary": "vermelho"}},
                 "content": {"topBar": {"text": "<b>Oferta</b>", "link": "javascript:alert(1)"}}}
                """);

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.body().path("code").asText()).isEqualTo("validation_failed");
        assertThat(response.body().path("message").asText()).isEqualTo("Há campos inválidos.");
        assertThat(response.body().path("error").asText()).isEqualTo("Há campos inválidos.");
        assertThat(response.body().path("timestamp").asText()).isNotBlank();
        assertThat(response.body().path("fields")).isEqualTo(json.readTree("""
                [{"field": "theme.dark.primary", "message": "Use uma cor no formato #RRGGBB."},
                 {"field": "content.topBar.text", "message": "Use só texto: os sinais < e > não são aceitos."},
                 {"field": "content.topBar.link",
                  "message": "Use um caminho da loja (começando com /) ou um endereço https://."}]
                """));
    }

    @Test
    void aDecorationOutsideTheCatalogIsRefused() throws Exception {
        Response response = send("""
                {"content": {"decorations": ["SNOW", "<style>body{display:none}</style>"]}}
                """);

        assertFieldError(response, "content.decorations[1]",
                "Valor não permitido. Use um destes: SNOW, SPARKLES, HEARTS, RIBBONS, CONFETTI, STARS.");
        assertThat(response.text()).doesNotContain("display:none", "com.example");
    }

    @Test
    void anUnknownButtonStyleIsRefused() throws Exception {
        Response response = send("""
                {"content": {"hero": {"title": "Natal", "buttons": [{"label": "Ver", "link": "/", "style": "NEON"}]}}}
                """);

        assertFieldError(response, "content.hero.buttons[0].style",
                "Valor não permitido. Use um destes: PRIMARY, SECONDARY.");
    }

    @ParameterizedTest
    @CsvSource(delimiter = '|', textBlock = """
            {"theme": {"dark": "vermelho"}}                                  | theme.dark
            {"content": {"decorations": "SNOW"}}                             | content.decorations
            {"content": {"hero": {"imageDesktop": {"width": "larga"}}}}      | content.hero.imageDesktop.width
            {"content": {"hero": {"imageDesktop": {"width": 99999999999}}}}  | content.hero.imageDesktop.width
            """)
    void aValueOfTheWrongTypeIsRefused(String body, String field) throws Exception {
        assertFieldError(send(body), field, "Valor em formato inválido.");
    }

    @Test
    void brokenJsonAnswers400() throws Exception {
        Response response = send("isto não é JSON");

        assertThat(response.status()).isEqualTo(400);
        assertThat(response.body().path("code").asText()).isEqualTo("invalid_data");
        assertThat(response.body().path("message").asText()).isEqualTo("O corpo da requisição não é um JSON válido.");
    }

    @Test
    void propertiesOutsideTheContractAreDropped() throws Exception {
        Response response = send("""
                {"theme": {"dark": null, "light": null, "css": "body{display:none}"},
                 "content": {"script": "alert(1)", "topBar": {"text": "Oferta", "style": "color:red"}}}
                """);

        assertThat(response.status()).isEqualTo(200);
        assertThat(response.text()).doesNotContain("display:none", "alert(1)", "color:red");
    }

    private void assertFieldError(Response response, String field, String message) throws IOException {
        assertThat(response.status()).isEqualTo(400);
        assertThat(response.body().path("code").asText()).isEqualTo("validation_failed");
        assertThat(response.body().path("fields")).hasSize(1);
        assertThat(response.body().path("fields").path(0).path("field").asText()).isEqualTo(field);
        assertThat(response.body().path("fields").path(0).path("message").asText()).isEqualTo(message);
    }

    private Response send(String body) throws Exception {
        var response = mvc.perform(post("/probe")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.getBytes(StandardCharsets.UTF_8)))
                .andReturn().getResponse();
        String text = response.getContentAsString(StandardCharsets.UTF_8);
        return new Response(response.getStatus(), text, json.readTree(text));
    }
}
