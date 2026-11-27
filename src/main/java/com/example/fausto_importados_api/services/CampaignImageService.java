package com.example.fausto_importados_api.services;

import com.cloudinary.Cloudinary;
import com.example.fausto_importados_api.concurrency.ApiProblemException;
import com.example.fausto_importados_api.dto.validation.InvalidField;
import com.example.fausto_importados_api.exception.FieldValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/**
 * Banners e imagens das campanhas. O tipo é conferido pelo conteúdo do arquivo, nunca pelo nome ou pelo
 * {@code Content-Type}, que o cliente escreve. SVG fica de fora: é um documento que pode carregar script.
 */
@Service
public class CampaignImageService {

    public static final long MAX_BYTES = 5L * 1024 * 1024;
    public static final String TOO_LARGE_CODE = "file_too_large";

    private static final String FOLDER = "campaigns";
    private static final String FIELD = "file";
    private static final String UNSUPPORTED = "Use uma imagem JPEG, PNG ou WebP. SVG não é aceito.";

    private static final Logger log = LoggerFactory.getLogger(CampaignImageService.class);

    /** @param width  largura em pixels, para a loja reservar o espaço; nula se o Cloudinary não informar */
    public record Uploaded(String url, Integer width, Integer height) {
    }

    private final Cloudinary cloudinary;

    public CampaignImageService(Cloudinary cloudinary) {
        this.cloudinary = cloudinary;
    }

    /**
     * Chamada externa e lenta: nunca dentro de uma transação.
     *
     * @throws FieldValidationException 400 para arquivo ausente ou de outro tipo, 413 acima de {@link #MAX_BYTES}
     * @throws ApiProblemException      502 {@code upload_failed} se o Cloudinary não receber a imagem
     */
    public Uploaded upload(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw invalid("Envie a imagem.");
        }
        if (file.getSize() > MAX_BYTES) {
            throw new FieldValidationException(HttpStatus.PAYLOAD_TOO_LARGE, TOO_LARGE_CODE,
                    "A imagem passa do limite de 5 MB.",
                    List.of(new InvalidField(FIELD, "A imagem passa do limite de 5 MB.")));
        }
        byte[] bytes = read(file);
        if (!isSupportedImage(bytes)) {
            throw invalid(UNSUPPORTED);
        }
        try {
            Map<?, ?> result = cloudinary.uploader().upload(bytes, Map.of(
                    "folder", FOLDER,
                    "resource_type", "image",
                    "allowed_formats", "jpg,png,webp")); // o Cloudinary confere de novo
            return new Uploaded(
                    result.get("secure_url").toString(), pixels(result.get("width")), pixels(result.get("height")));
        } catch (IOException | RuntimeException e) {
            // começa como uma imagem, mas o resto está corrompido
            if (String.valueOf(e.getMessage()).contains("Invalid image")) {
                throw invalid("O arquivo não é uma imagem válida.");
            }
            log.error("Falha ao enviar imagem de campanha ao Cloudinary", e);
            throw new ApiProblemException(HttpStatus.BAD_GATEWAY, "upload_failed",
                    "Não foi possível enviar a imagem. Tente novamente.");
        }
    }

    // assinaturas no início do arquivo
    private static boolean isSupportedImage(byte[] file) {
        boolean jpeg = startsWith(file, 0, 0xFF, 0xD8, 0xFF);
        boolean png = startsWith(file, 0, 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A);
        boolean webp = startsWith(file, 0, 'R', 'I', 'F', 'F') && startsWith(file, 8, 'W', 'E', 'B', 'P');
        return jpeg || png || webp;
    }

    private static boolean startsWith(byte[] file, int offset, int... signature) {
        byte[] expected = new byte[signature.length];
        for (int i = 0; i < signature.length; i++) {
            expected[i] = (byte) signature[i];
        }
        return file.length >= offset + expected.length
                && Arrays.equals(file, offset, offset + expected.length, expected, 0, expected.length);
    }

    private static byte[] read(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException e) {
            throw new IllegalStateException("Could not read the uploaded file", e);
        }
    }

    private static Integer pixels(Object value) {
        return value instanceof Number number ? number.intValue() : null;
    }

    private static FieldValidationException invalid(String message) {
        return new FieldValidationException(List.of(new InvalidField(FIELD, message)));
    }
}
