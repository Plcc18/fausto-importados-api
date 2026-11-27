package com.example.fausto_importados_api.controller;

import com.example.fausto_importados_api.audit.AuditAction;
import com.example.fausto_importados_api.audit.AuditService;
import com.example.fausto_importados_api.dto.validation.InvalidField;
import com.example.fausto_importados_api.exception.FieldValidationException;
import com.example.fausto_importados_api.services.CampaignImageService;
import com.example.fausto_importados_api.services.ProductService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class UploadController {

    private final ProductService productService;
    private final CampaignImageService campaignImages;
    private final AuditService audit;

    public UploadController(
            ProductService productService, CampaignImageService campaignImages, AuditService audit
    ) {
        this.productService = productService;
        this.campaignImages = campaignImages;
        this.audit = audit;
    }

    /** Imagens de campanha ({@code ?folder=campaigns}, campo {@code file}). Sem {@code folder}, vale a de produto. */
    @PostMapping(value = "/upload", params = "folder")
    @PreAuthorize("hasRole('ADMIN')")
    public CampaignImageService.Uploaded uploadCampaignImage(
            @RequestParam("folder") String folder,
            @RequestPart(value = "file", required = false) MultipartFile file
    ) {
        if (!folder.equals("campaigns")) {
            throw new FieldValidationException(List.of(new InvalidField("folder", "Use folder=campaigns.")));
        }
        CampaignImageService.Uploaded uploaded = campaignImages.upload(file);
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("url", uploaded.url());
        details.put("bytes", file.getSize());
        audit.recordSuccess(AuditAction.CAMPAIGN_IMAGE_UPLOADED, "CAMPAIGN_IMAGE", null, details);
        return uploaded;
    }

    @PostMapping("/upload")
    public ResponseEntity<?> uploadImage(
            @RequestPart("image") MultipartFile file
    ) {
        try {
            String imageUrl = productService.uploadImage(file);

            return ResponseEntity.ok().body(
                    Map.of("url", imageUrl)
            );

        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.status(500).body("Erro no upload");
        }
    }
}