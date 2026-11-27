package com.example.fausto_importados_api.controller;

import com.cloudinary.Cloudinary;
import com.cloudinary.Uploader;
import com.example.fausto_importados_api.dto.validation.FieldChecks;
import com.example.fausto_importados_api.model.campaign.CampaignContent;
import com.example.fausto_importados_api.repository.AuditLogRepository;
import com.example.fausto_importados_api.services.CampaignContentValidator;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.domain.Pageable;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Upload de imagens pelo servidor de verdade (o MockMvc não aplica os limites de multipart). O Cloudinary é
 * simulado: nenhum teste envia nada.
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "spring.datasource.url=jdbc:h2:mem:upload;DB_CLOSE_DELAY=-1")
@ActiveProfiles("test")
class UploadApiTest {

    private static final int FIVE_MB = 5 * 1024 * 1024;
    private static final String URL = "https://res.cloudinary.com/test/image/upload/v1/campaigns/abc.png";
    private static final String CAMPAIGNS = "/api/upload?folder=campaigns";

    @LocalServerPort int port;
    @Autowired ObjectMapper json;
    @Autowired AuditLogRepository auditLog;
    @MockitoBean Cloudinary cloudinary;

    private final HttpClient http = HttpClient.newHttpClient();
    private final Uploader uploader = mock(Uploader.class);
    private String token;

    private record Res(int status, JsonNode body) {
    }

    private record Part(String name, String fileName, String contentType, byte[] content) {
    }

    @BeforeEach
    void setUp() throws Exception {
        reset(uploader);
        when(cloudinary.uploader()).thenReturn(uploader);
        when(uploader.upload(any(), anyMap()))
                .thenReturn(Map.of("secure_url", URL, "width", 1920, "height", 640));
        auditLog.deleteAll();
        HttpResponse<String> login = http.send(HttpRequest.newBuilder(uri("/auth/login"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"email\":\"admin@test.com\",\"password\":\"test-password\"}"))
                .build(), HttpResponse.BodyHandlers.ofString());
        token = json.readTree(login.body()).path("accessToken").asText();
        assertThat(token).isNotBlank();
    }

    // ───────────────────────────── banners ─────────────────────────────

    static Stream<Arguments> images() {
        return Stream.of(
                Arguments.of("JPEG", jpeg(2000)),
                Arguments.of("PNG", png(2000)),
                Arguments.of("WebP", webp(2000)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("images")
    void aBannerIsUploadedToItsOwnFolder(String type, byte[] image) throws Exception {
        Res res = upload(CAMPAIGNS, new Part("file", "banner", "application/octet-stream", image));

        assertThat(res.status()).isEqualTo(200);
        assertThat(res.body()).isEqualTo(json.readTree(
                "{\"url\": \"" + URL + "\", \"width\": 1920, \"height\": 640}"));
        @SuppressWarnings("unchecked")
        ArgumentCaptor<Map<String, Object>> options = ArgumentCaptor.forClass(Map.class);
        ArgumentCaptor<Object> sent = ArgumentCaptor.forClass(Object.class);
        verify(uploader).upload(sent.capture(), options.capture());
        assertThat((byte[]) sent.getValue()).isEqualTo(image);
        assertThat(options.getValue()).containsEntry("folder", "campaigns")
                .containsEntry("resource_type", "image")
                .containsEntry("allowed_formats", "jpg,png,webp");
        // a resposta serve direto como imagem do conteúdo da campanha
        FieldChecks checks = new FieldChecks();
        new CampaignContentValidator("test").content(new CampaignContent(null, null, List.of(
                new CampaignContent.PromoImage(new CampaignContent.Image(URL, "Banner", 1920, 640), null)), null),
                checks);
        checks.throwIfInvalid();
    }

    @Test
    void theUploadIsRecorded() throws Exception {
        upload(CAMPAIGNS, new Part("file", "banner.png", "image/png", png(2000)));

        assertThat(auditLog.findByEntityTypeOrderByOccurredAtDesc("CAMPAIGN_IMAGE", Pageable.unpaged()).getContent())
                .singleElement()
                .satisfies(row -> {
                    assertThat(row.getAction()).isEqualTo("CAMPAIGN_IMAGE_UPLOADED");
                    assertThat(row.getActorEmail()).isEqualTo("admin@test.com");
                    assertThat(row.getDetails()).contains(URL).contains("2000");
                });
    }

    @Test
    void aLargeBannerUpToTheLimitGoesThrough() throws Exception {
        Res res = upload(CAMPAIGNS, new Part("file", "grande.jpg", "image/jpeg", jpeg(FIVE_MB)));

        assertThat(res.status()).isEqualTo(200);
    }

    @ParameterizedTest
    @ValueSource(ints = {FIVE_MB + 1, 5 * FIVE_MB / 4, 3 * FIVE_MB}) // o arquivo, a requisição e bem acima
    void aBannerAboveTheLimitIsRefusedWithAClearAnswer(int size) throws Exception {
        Res res = upload(CAMPAIGNS, new Part("file", "enorme.jpg", "image/jpeg", jpeg(size)));

        assertThat(res.status()).isEqualTo(413);
        assertThat(res.body().path("code").asText()).isEqualTo("file_too_large");
        assertThat(res.body().path("message").asText()).contains("5 MB");
        verify(uploader, never()).upload(any(), anyMap());
    }

    static Stream<Arguments> otherFiles() {
        String svg = "<svg xmlns=\"http://www.w3.org/2000/svg\"><script>alert(1)</script></svg>";
        return Stream.of(
                Arguments.of("SVG", "banner.svg", "image/svg+xml", bytes(svg)),
                Arguments.of("SVG disfarçado de PNG", "banner.png", "image/png", bytes(svg)),
                Arguments.of("SVG com declaração XML", "banner.jpg", "image/jpeg", bytes("<?xml version=\"1.0\"?>" + svg)),
                Arguments.of("GIF", "banner.gif", "image/gif", bytes("GIF89a-qualquer-coisa")),
                Arguments.of("HTML", "banner.png", "image/png", bytes("<html><body>oi</body></html>")),
                Arguments.of("PDF", "banner.jpg", "image/jpeg", bytes("%PDF-1.7 documento")),
                Arguments.of("RIFF que não é WebP", "banner.webp", "image/webp", bytes("RIFF0000WAVEfmt ")),
                Arguments.of("assinatura pela metade", "banner.png", "image/png", new byte[]{(byte) 0x89, 'P', 'N'}));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("otherFiles")
    void whatIsNotAnImageIsRefusedWhateverItClaimsToBe(String label, String name, String type, byte[] content)
            throws Exception {
        Res res = upload(CAMPAIGNS, new Part("file", name, type, content));

        assertFileError(res, "Use uma imagem JPEG, PNG ou WebP. SVG não é aceito.");
        verify(uploader, never()).upload(any(), anyMap());
        assertThat(auditLog.findByEntityTypeOrderByOccurredAtDesc("CAMPAIGN_IMAGE", Pageable.unpaged())).isEmpty();
    }

    @Test
    void aMissingOrEmptyFileIsRefused() throws Exception {
        assertFileError(upload(CAMPAIGNS, new Part("file", "vazio.png", "image/png", new byte[0])),
                "Envie a imagem.");
        // o campo de produto não vale para campanha
        assertFileError(upload(CAMPAIGNS, new Part("image", "banner.png", "image/png", png(100))),
                "Envie a imagem.");
        verify(uploader, never()).upload(any(), anyMap());
    }

    @Test
    void onlyTheCampaignsFolderExists() throws Exception {
        Res res = upload("/api/upload?folder=../produtos", new Part("file", "a.png", "image/png", png(100)));

        assertThat(res.status()).isEqualTo(400);
        assertThat(res.body().path("fields").path(0).path("field").asText()).isEqualTo("folder");
        verify(uploader, never()).upload(any(), anyMap());
    }

    @Test
    void aCorruptedImageIsPointedOut() throws Exception {
        when(uploader.upload(any(), anyMap())).thenThrow(new RuntimeException("Invalid image file"));

        assertFileError(upload(CAMPAIGNS, new Part("file", "a.png", "image/png", png(100))),
                "O arquivo não é uma imagem válida.");
    }

    @Test
    void aCloudinaryFailureIsReportedWithoutItsDetails() throws Exception {
        when(uploader.upload(any(), anyMap())).thenThrow(new IOException("api_key 123 recusada"));

        Res res = upload(CAMPAIGNS, new Part("file", "a.png", "image/png", png(100)));

        assertThat(res.status()).isEqualTo(502);
        assertThat(res.body().path("code").asText()).isEqualTo("upload_failed");
        assertThat(res.body().toString()).doesNotContain("api_key");
    }

    @Test
    void onlyAdministratorsUpload() throws Exception {
        token = null;

        assertThat(upload(CAMPAIGNS, new Part("file", "a.png", "image/png", png(100))).status()).isEqualTo(401);
        verify(uploader, never()).upload(any(), anyMap());
    }

    // ───────────────────────────── produtos ─────────────────────────────

    @Test
    void theProductUploadStillWorksTheSameWay() throws Exception {
        byte[] anything = bytes("GIF89a produto"); // sem a conferência de tipo das campanhas

        Res res = upload("/api/upload", new Part("image", "produto.gif", "image/gif", anything));

        assertThat(res.status()).isEqualTo(200);
        assertThat(res.body()).isEqualTo(json.readTree("{\"url\": \"" + URL + "\"}"));
        verify(uploader).upload(any(), org.mockito.ArgumentMatchers.eq(Map.of()));
    }

    @Test
    void productImagesNowGoUpTo5MB() throws Exception {
        assertThat(upload("/api/upload", new Part("image", "foto.jpg", "image/jpeg", jpeg(3 * 1024 * 1024))).status())
                .isEqualTo(200);
        assertThat(upload("/api/upload", new Part("image", "foto.jpg", "image/jpeg", jpeg(FIVE_MB + 1))).status())
                .isEqualTo(413);
    }

    // ───────────────────────────── helpers ─────────────────────────────

    private void assertFileError(Res res, String message) {
        assertThat(res.status()).isEqualTo(400);
        assertThat(res.body().path("code").asText()).isEqualTo("validation_failed");
        assertThat(res.body().path("fields").path(0).path("field").asText()).isEqualTo("file");
        assertThat(res.body().path("fields").path(0).path("message").asText()).isEqualTo(message);
    }

    private Res upload(String path, Part part) throws Exception {
        String boundary = "----" + UUID.randomUUID();
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(bytes("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + part.name()
                + "\"; filename=\"" + part.fileName() + "\"\r\nContent-Type: " + part.contentType() + "\r\n\r\n"));
        body.write(part.content());
        body.write(bytes("\r\n--" + boundary + "--\r\n"));
        HttpRequest.Builder request = HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        HttpResponse<String> response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        return new Res(response.statusCode(), json.readTree(response.body()));
    }

    private URI uri(String path) {
        return URI.create("http://localhost:" + port + path);
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] jpeg(int size) {
        return filled(size, (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0);
    }

    private static byte[] png(int size) {
        return filled(size, (byte) 0x89, (byte) 'P', (byte) 'N', (byte) 'G', (byte) 0x0D, (byte) 0x0A, (byte) 0x1A,
                (byte) 0x0A);
    }

    private static byte[] webp(int size) {
        byte[] file = filled(size, (byte) 'R', (byte) 'I', (byte) 'F', (byte) 'F');
        System.arraycopy(bytes("WEBP"), 0, file, 8, 4);
        return file;
    }

    /** Um arquivo do tamanho pedido com a assinatura no começo. */
    private static byte[] filled(int size, Byte... signature) {
        byte[] file = new byte[size];
        Arrays.fill(file, (byte) 7);
        for (int i = 0; i < signature.length; i++) {
            file[i] = signature[i];
        }
        return file;
    }
}
