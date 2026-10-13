package com.orpex.core.importing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.orpex.core.calendar.BusinessCalendar;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.LocalDate;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;

class ImportTemplateTest {

    private static final LocalDate TODAY = LocalDate.parse("2026-10-13");

    private final byte[] template = ImportTemplate.create(TODAY);

    @Test
    void theTemplateIsReadByTheImportAndItsExamplesAreValid() {
        XlsxReader.ParsedSheet sheet = XlsxReader.read(new ByteArrayInputStream(template));

        assertTrue(RowValidator.missingColumns(sheet.headers()).isEmpty());
        FileValidator.FileResult result = new FileValidator(BusinessCalendar.weekendsOnly()).validate(sheet, TODAY);
        assertEquals(3, result.rows().size());
        result.rows().forEach(row -> assertTrue(row.isValid(), () -> row.rowNumber() + " : " + row.errors()));
        assertEquals("09:00", sheet.rows().get(0).cells().get("heure_envoi"));
        assertTrue(sheet.rows().stream().allMatch(r -> r.cells().get("email").endsWith("@example.org")),
                "adresses d'exemple non distribuables");
        assertFalse(sheet.headers().contains(Columns.VALIDATOR), "colonne réservée, absente du modèle");
    }

    @Test
    void theTemplateHasDropDownsAndAnInstructionsSheet() throws IOException {
        try (XSSFWorkbook workbook = new XSSFWorkbook(new ByteArrayInputStream(template))) {
            assertEquals("relances", workbook.getSheetName(0));
            assertEquals("instructions", workbook.getSheetName(1));
            assertEquals(4, workbook.getSheetAt(0).getDataValidations().size());
            assertTrue(workbook.getSheetAt(1).getRow(0).getCell(0).getStringCellValue().contains("ORPEX"));
        }
    }

    @Test
    void exampleDatesAreAlwaysInTheFuture() {
        LocalDate later = LocalDate.parse("2027-12-31");
        XlsxReader.ParsedSheet sheet = XlsxReader.read(new ByteArrayInputStream(ImportTemplate.create(later)));

        var result = new FileValidator(BusinessCalendar.weekendsOnly()).validate(sheet, later);

        assertEquals(3, result.validCount());
    }
}
