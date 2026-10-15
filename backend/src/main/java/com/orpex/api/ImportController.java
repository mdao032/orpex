package com.orpex.api;

import com.orpex.account.CurrentUser;
import com.orpex.core.importing.ImportTemplate;
import com.orpex.importing.ImportApplyService;
import com.orpex.importing.ImportApplyService.Choice;
import com.orpex.importing.ImportApplyService.ImportResult;
import com.orpex.importing.ImportPreviewService;
import com.orpex.importing.ImportPreviewService.ImportPreview;
import com.orpex.rule.ImportRecord;
import com.orpex.rule.ImportRecordRepository;
import java.io.IOException;
import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

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

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<Map<String, Map<String, Choice>>> RESOLUTIONS = new TypeReference<>() {
    };

    private final ImportPreviewService previewService;
    private final ImportApplyService applyService;
    private final ImportRecordRepository imports;
    private final CurrentUser currentUser;
    private final Clock clock;

    public ImportController(ImportPreviewService previewService, ImportApplyService applyService,
            ImportRecordRepository imports, CurrentUser currentUser, Clock clock) {
        this.previewService = previewService;
        this.applyService = applyService;
        this.imports = imports;
        this.currentUser = currentUser;
        this.clock = clock;
    }

    /** Modèle .xlsx à remplir : listes déroulantes, feuille d'instructions, lignes d'exemple aux dates futures. */
    @GetMapping("/template")
    public ResponseEntity<byte[]> template() {
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(
                        "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"))
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename("modele-relances-orpex.xlsx").build().toString())
                .body(ImportTemplate.create(LocalDate.now(clock)));
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

    /**
     * Confirmation : le même fichier que l'aperçu, réanalysé puis appliqué en une transaction ; {@code closeAbsent} :
     * clore aussi les relances absentes du fichier (non par défaut) ; {@code resolutions} (JSON) : pour chaque id en
     * conflit, {@code {"champ": "APP" | "FILE"}}.
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public ImportResult apply(@RequestPart("file") MultipartFile file,
            @RequestParam(name = "closeAbsent", defaultValue = "false") boolean closeAbsent,
            @RequestParam(name = "resolutions", required = false) String resolutions,
            Authentication authentication) {
        long ownerId = currentUser.id(authentication);
        try (InputStream content = open(file)) {
            return applyService.apply(ownerId, fileName(file), content, closeAbsent, resolutions(resolutions));
        } catch (IOException e) {
            throw unreadable(e);
        }
    }

    private static Map<String, Map<String, Choice>> resolutions(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return JSON.readValue(json, RESOLUTIONS);
        } catch (JacksonException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Choix des conflits illisibles.", e);
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
