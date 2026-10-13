package com.orpex.importing;

import com.orpex.importing.ImportPreviewService.Action;
import com.orpex.importing.ImportPreviewService.Analysis;
import com.orpex.importing.ImportPreviewService.RowPreview;
import com.orpex.rule.ImportRecord;
import com.orpex.rule.ImportRecordRepository;
import com.orpex.rule.Rule;
import com.orpex.rule.RuleRepository;
import java.io.InputStream;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

/**
 * Confirmation d'un import (spécification §9) : le fichier est réanalysé exactement comme pour l'aperçu, puis les
 * lignes valides nouvelles sont créées en une seule transaction et l'import est enregistré.
 *
 * <p>Version actuelle : une ligne existante dont le fichier propose une configuration différente n'est <b>pas</b>
 * modifiée ; elle est comptée « non appliquée ». La mise à jour, avec choix en cas de conflit avec l'interface,
 * viendra avec le ré-import : jamais d'écrasement silencieux.
 */
@Service
public class ImportApplyService {

    public record ImportResult(long importId, String fileName, int created, int unchanged, int notApplied,
                               int rejected) {
    }

    private final ImportPreviewService analyser;
    private final RuleRepository rules;
    private final ImportRecordRepository imports;
    private final Clock clock;

    public ImportApplyService(ImportPreviewService analyser, RuleRepository rules, ImportRecordRepository imports,
            Clock clock) {
        this.analyser = analyser;
        this.rules = rules;
        this.imports = imports;
        this.clock = clock;
    }

    @Transactional
    public ImportResult apply(long ownerId, String fileName, InputStream content) {
        Analysis analysis = analyser.analyse(ownerId, fileName, content);
        if (!analysis.preview().missingColumns().isEmpty()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Le fichier ne peut pas être importé : "
                    + "colonnes obligatoires absentes (" + String.join(", ", analysis.preview().missingColumns()) + ").");
        }

        Instant now = clock.instant();
        int created = 0;
        int unchanged = 0;
        int notApplied = 0;
        int rejected = 0;
        List<String> notAppliedIds = new ArrayList<>();
        for (RowPreview row : analysis.preview().rows()) {
            switch (row.action()) {
                case CREATE -> {
                    rules.save(Rule.fromImport(analysis.rules().get(row.rowNumber()),
                            analysis.hashes().get(row.rowNumber()), ownerId, now));
                    created++;
                }
                case UNCHANGED -> unchanged++;
                case UPDATE -> {
                    notApplied++;
                    notAppliedIds.add(row.id());
                }
                case REJECTED -> rejected++;
            }
        }

        String summary = notAppliedIds.isEmpty() ? null
                : "Modifications non appliquées (lignes existantes) : " + String.join(", ", notAppliedIds);
        ImportRecord record;
        try {
            record = imports.save(new ImportRecord(ownerId, fileName, created, 0, unchanged, rejected, summary, now));
            rules.flush();
        } catch (DataIntegrityViolationException e) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "Un autre import des mêmes lignes a eu lieu entre-temps : relancez l'aperçu.", e);
        }
        return new ImportResult(record.getId(), fileName, created, unchanged, notApplied, rejected);
    }
}
