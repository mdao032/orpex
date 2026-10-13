package com.orpex.api;

import com.orpex.account.CurrentUser;
import com.orpex.importing.ImportApplyService;
import com.orpex.importing.ImportApplyService.ImportResult;
import com.orpex.importing.ImportPreviewService;
import com.orpex.importing.ImportPreviewService.ImportPreview;
import com.orpex.rule.ImportRecord;
import com.orpex.rule.ImportRecordRepository;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/** Import d'un fichier Excel de relances, pour l'utilisateur connecté (propriétaire des lignes). */
@RestController
@RequestMapping("/api/imports")
public class ImportController {

    public record ImportView(long id, String fileName, int created, int updated, int unchanged, int rejected,
                             String summary, Instant createdAt) {

        static ImportView of(ImportRecord record) {
            return new ImportView(record.getId(), record.getFileName(), record.getCreatedCount(),
                    record.getUpdatedCount(), record.getUnchangedCount(), record.getRejectedCount(),
                    record.getSummary(), record.getCreatedAt());
        }
    }

    private final ImportPreviewService previewService;
    private final ImportApplyService applyService;
    private final ImportRecordRepository imports;
    private final CurrentUser currentUser;

    public ImportController(ImportPreviewService previewService, ImportApplyService applyService,
            ImportRecordRepository imports, CurrentUser currentUser) {
        this.previewService = previewService;
        this.applyService = applyService;
        this.imports = imports;
        this.currentUser = currentUser;
    }

    /** Historique des imports de l'utilisateur, du plus récent au plus ancien. */
    @GetMapping
    public List<ImportView> history(Authentication authentication) {
        return imports.findAllByUserIdOrderByCreatedAtDesc(currentUser.id(authentication)).stream()
                .map(ImportView::of).toList();
    }

    /** Aperçu : rien n'est écrit en base. */
    @PostMapping(path = "/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ImportPreview preview(@RequestPart("file") MultipartFile file, Authentication authentication) {
        long ownerId = currentUser.id(authentication);
        try (InputStream content = open(file)) {
            return previewService.preview(ownerId, fileName(file), content);
        } catch (IOException e) {
            throw unreadable(e);
        }
    }

    /** Confirmation : le même fichier que l'aperçu, réanalysé puis appliqué en une transaction. */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public ImportResult apply(@RequestPart("file") MultipartFile file, Authentication authentication) {
        long ownerId = currentUser.id(authentication);
        try (InputStream content = open(file)) {
            return applyService.apply(ownerId, fileName(file), content);
        } catch (IOException e) {
            throw unreadable(e);
        }
    }

    private static InputStream open(MultipartFile file) throws IOException {
        if (file.isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Le fichier est vide.");
        }
        return file.getInputStream();
    }

    private static ResponseStatusException unreadable(IOException e) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, "Le fichier n'a pas pu être lu.", e);
    }

    private static String fileName(MultipartFile file) {
        String name = file.getOriginalFilename();
        return name == null || name.isBlank() ? "fichier.xlsx" : name.strip();
    }
}
