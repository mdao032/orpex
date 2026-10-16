package com.orpex.audit;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AuditCsvTest {

    @Test
    void separatorsQuotesAndLineBreaksAreQuoted() {
        assertThat(AuditCsv.cell("a;b")).isEqualTo("\"a;b\"");
        assertThat(AuditCsv.cell("dit \"oui\"")).isEqualTo("\"dit \"\"oui\"\"\"");
        assertThat(AuditCsv.cell("ligne 1\nligne 2")).isEqualTo("\"ligne 1\nligne 2\"");
        assertThat(AuditCsv.cell(null)).isEmpty();
    }

    @Test
    void aValueThatLooksLikeAFormulaIsNeverExecutedByExcel() {
        assertThat(AuditCsv.cell("=HYPERLINK(\"x\")")).startsWith("\"'=HYPERLINK");
        assertThat(AuditCsv.cell("+33 1 23")).isEqualTo("'+33 1 23");
        assertThat(AuditCsv.cell("@x")).isEqualTo("'@x");
        assertThat(AuditCsv.cell("-5")).isEqualTo("'-5");
    }
}
