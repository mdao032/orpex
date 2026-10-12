package com.orpex.core.importing;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.apache.poi.ooxml.POIXMLException;
import org.apache.poi.openxml4j.exceptions.NotOfficeXmlFileException;
import org.apache.poi.openxml4j.exceptions.OLE2NotOfficeXmlFileException;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DateUtil;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

/**
 * Lit la première feuille d'un fichier .xlsx : la première ligne donne les en-têtes, chaque ligne suivante non vide
 * devient un jeu de cellules en texte, au format attendu par {@link RowValidator}.
 *
 * <p>Conversion des cellules : date Excel → {@code AAAA-MM-JJ}, heure Excel → {@code HH:MM}, nombre entier →
 * {@code 3} (pas {@code 3.0}), texte inchangé. Un texte comme « 03/04 » n'est pas interprété : le validateur le
 * refuse, une date ambiguë ne doit jamais être devinée.
 */
public final class XlsxReader {

    /** Lignes de données au maximum (spécification §13 : quelques milliers de lignes par utilisateur). */
    public static final int MAX_ROWS = 10_000;

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm");

    /** Une ligne du fichier : son numéro tel qu'affiché par Excel, et ses cellules par en-tête en minuscules. */
    public record SheetRow(int rowNumber, Map<String, String> cells) {
    }

    public record ParsedSheet(List<String> headers, List<SheetRow> rows) {
    }

    /** Le fichier entier est refusé ; le message est destiné à l'utilisateur. */
    public static final class UnreadableFileException extends RuntimeException {

        public UnreadableFileException(String message) {
            super(message);
        }

        public UnreadableFileException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private XlsxReader() {
    }

    public static ParsedSheet read(InputStream content) {
        try (XSSFWorkbook workbook = new XSSFWorkbook(content)) {
            if (workbook.getNumberOfSheets() == 0) {
                throw new UnreadableFileException("Le fichier ne contient aucune feuille.");
            }
            return readSheet(workbook.getSheetAt(0));
        } catch (OLE2NotOfficeXmlFileException e) {
            throw new UnreadableFileException(
                    "Ancien format Excel (.xls) : enregistrez le fichier au format .xlsx puis réessayez.", e);
        } catch (NotOfficeXmlFileException | POIXMLException | IOException e) {
            throw new UnreadableFileException("Le fichier n'est pas un classeur Excel .xlsx lisible.", e);
        }
    }

    private static ParsedSheet readSheet(Sheet sheet) {
        Row headerRow = sheet.getRow(sheet.getFirstRowNum());
        if (sheet.getPhysicalNumberOfRows() == 0 || headerRow == null) {
            throw new UnreadableFileException("La première feuille est vide : la première ligne doit contenir les en-têtes.");
        }
        Map<Integer, String> headersByColumn = readHeaders(headerRow);

        List<SheetRow> rows = new ArrayList<>();
        for (int r = headerRow.getRowNum() + 1; r <= sheet.getLastRowNum(); r++) {
            Row row = sheet.getRow(r);
            if (row == null) {
                continue;
            }
            Map<String, String> cells = new LinkedHashMap<>();
            boolean empty = true;
            for (var header : headersByColumn.entrySet()) {
                String value = text(row.getCell(header.getKey()));
                cells.put(header.getValue(), value);
                empty &= value.isBlank();
            }
            if (empty) {
                continue;
            }
            if (rows.size() == MAX_ROWS) {
                throw new UnreadableFileException("Le fichier dépasse " + MAX_ROWS + " lignes : découpez-le en plusieurs fichiers.");
            }
            rows.add(new SheetRow(r + 1, cells));
        }
        return new ParsedSheet(List.copyOf(headersByColumn.values()), rows);
    }

    private static Map<Integer, String> readHeaders(Row headerRow) {
        Map<Integer, String> headers = new LinkedHashMap<>();
        Set<String> seen = new HashSet<>();
        for (Cell cell : headerRow) {
            String header = text(cell).strip().toLowerCase(Locale.ROOT);
            if (header.isEmpty()) {
                continue;
            }
            if (!seen.add(header)) {
                throw new UnreadableFileException("La colonne « " + header + " » apparaît deux fois dans les en-têtes.");
            }
            headers.put(cell.getColumnIndex(), header);
        }
        return headers;
    }

    private static String text(Cell cell) {
        if (cell == null) {
            return "";
        }
        CellType type = cell.getCellType() == CellType.FORMULA ? cell.getCachedFormulaResultType() : cell.getCellType();
        return switch (type) {
            case STRING -> cell.getStringCellValue();
            case NUMERIC -> DateUtil.isCellDateFormatted(cell) ? dateOrTime(cell) : number(cell.getNumericCellValue());
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            default -> "";
        };
    }

    /** Date seule → AAAA-MM-JJ ; heure seule → HH:MM ; date et heure → forme ISO complète, que le validateur refuse. */
    private static String dateOrTime(Cell cell) {
        double serial = cell.getNumericCellValue();
        LocalDateTime value = cell.getLocalDateTimeCellValue();
        if (serial < 1) {
            return value.getSecond() == 0 ? TIME.format(value) : value.toLocalTime().toString();
        }
        return serial == Math.floor(serial) ? value.toLocalDate().toString() : value.toString();
    }

    private static String number(double value) {
        if (value == Math.rint(value) && !Double.isInfinite(value) && Math.abs(value) < 1e15) {
            return Long.toString((long) value);
        }
        return BigDecimal.valueOf(value).stripTrailingZeros().toPlainString();
    }
}
