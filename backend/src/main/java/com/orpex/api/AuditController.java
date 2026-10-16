package com.orpex.api;

import com.orpex.account.CurrentUser;
import com.orpex.audit.AuditCsv;
import com.orpex.audit.AuditQueryService;
import com.orpex.audit.AuditQueryService.AuditView;
import java.time.Clock;
import java.time.LocalDate;
import java.util.List;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Journal d'audit (spécification §11) : l'utilisateur consulte et exporte celui de ses relances et de ses actions
 * ({@code /api/audit}), l'administrateur tout le journal ({@code /api/admin/audit}). Dates de la période incluses,
 * dans le fuseau de l'installation.
 */
@RestController
public class AuditController {

    private final AuditQueryService audit;
    private final CurrentUser currentUser;
    private final Clock clock;

    public AuditController(AuditQueryService audit, CurrentUser currentUser, Clock clock) {
        this.audit = audit;
        this.currentUser = currentUser;
        this.clock = clock;
    }

    @GetMapping("/api/audit")
    public List<AuditView> mine(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            Authentication authentication) {
        AuditQueryService.checkRange(from, to);
        return audit.forUser(currentUser.id(authentication), from, to, AuditQueryService.SCREEN_LIMIT);
    }

    @GetMapping("/api/audit/export")
    public ResponseEntity<byte[]> exportMine(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            Authentication authentication) {
        AuditQueryService.checkRange(from, to);
        return csv(audit.forUser(currentUser.id(authentication), from, to, AuditQueryService.EXPORT_LIMIT),
                "journal-orpex.csv");
    }

    @GetMapping("/api/admin/audit")
    public List<AuditView> all(@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        AuditQueryService.checkRange(from, to);
        return audit.all(from, to, AuditQueryService.SCREEN_LIMIT);
    }

    @GetMapping("/api/admin/audit/export")
    public ResponseEntity<byte[]> exportAll(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        AuditQueryService.checkRange(from, to);
        return csv(audit.all(from, to, AuditQueryService.EXPORT_LIMIT), "journal-orpex-complet.csv");
    }

    private ResponseEntity<byte[]> csv(List<AuditView> views, String fileName) {
        return ResponseEntity.ok()
                .contentType(new MediaType("text", "csv", java.nio.charset.StandardCharsets.UTF_8))
                .header(HttpHeaders.CONTENT_DISPOSITION, ContentDisposition.attachment().filename(fileName).build()
                        .toString())
                .body(AuditCsv.write(views, clock.getZone()));
    }
}
