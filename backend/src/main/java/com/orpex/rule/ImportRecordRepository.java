package com.orpex.rule;

import java.util.List;
import org.springframework.data.repository.Repository;

/** Historique des imports, toujours filtré par auteur. */
public interface ImportRecordRepository extends Repository<ImportRecord, Long> {

    ImportRecord save(ImportRecord record);

    List<ImportRecord> findAllByUserIdOrderByCreatedAtDesc(long userId);
}
