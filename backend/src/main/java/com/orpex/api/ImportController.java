package com.orpex.api;

import com.orpex.account.CurrentUser;
import com.orpex.importing.ImportPreviewService;
import com.orpex.importing.ImportPreviewService.ImportPreview;
import java.io.IOException;
import java.io.InputStream;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/** Import d'un fichier Excel de relances, pour l'utilisateur connecté (propriétaire des lignes). */
@RestController
@RequestMapping("/api/imports")
public class ImportController {

    private final ImportPreviewService previewService;
    private final CurrentUser currentUser;

    public ImportController(ImportPreviewService previewService, CurrentUser currentUser) {
        this.previewService = previewService;
        this.currentUser = currentUser;
    }

    /** Aperçu : rien n'est écrit en base. */
    @PostMapping(path = "/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ImportPreview preview(@RequestPart("file") MultipartFile file, Authentication authentication) {
        if (file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Le fichier est vide.");
        }
        try (InputStream content = file.getInputStream()) {
            return previewService.preview(currentUser.id(authentication), fileName(file), content);
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Le fichier n'a pas pu être lu.", e);
        }
    }

    private static String fileName(MultipartFile file) {
        String name = file.getOriginalFilename();
        return name == null || name.isBlank() ? "fichier.xlsx" : name.strip();
    }
}
