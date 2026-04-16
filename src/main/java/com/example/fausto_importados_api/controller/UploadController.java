package com.example.fausto_importados_api.controller;

import com.example.fausto_importados_api.services.ProductService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@RestController
@RequestMapping("/api")
public class UploadController {

    private final ProductService productService;

    public UploadController(ProductService productService) {
        this.productService = productService;
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