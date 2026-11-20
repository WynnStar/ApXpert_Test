package tw.holdings.quote.client;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import tw.holdings.quote.Market;
import tw.holdings.quote.QuoteResult;

/** 只解析官方全市場報表，不負責 HTTP、排程或資料庫寫入。 */
final class BatchQuoteParser {
    private BatchQuoteParser() {}

    static OfficialBatchQuoteClient.Snapshot parse(JsonNode document, Market market, LocalDate requestedDate)
            throws IOException {
        if (document == null || !document.isObject() || !document.path("stat").isTextual()
                || !document.path("stat").asText().equalsIgnoreCase("ok")) {
            throw new IOException("Official batch is unavailable or not published");
        }
        LocalDate date = reportDate(text(document.get("date"), "report date"));
        if (!requestedDate.equals(date)) {
            throw new IOException("Official batch date does not match requested Taipei date");
        }
        var labels = market == Market.TWSE
                ? List.of("證券代號", "證券名稱", "收盤價", "成交股數")
                : List.of("代號", "名稱", "收盤", "成交股數");
        JsonNode table = findTable(document.get("tables"), labels);
        if (table.has("date") && !date.equals(reportDate(text(table.get("date"), "table date")))) {
            throw new IOException("Official batch table date does not match report date");
        }
        JsonNode rows = table.get("data");
        if (rows == null || !rows.isArray() || rows.isEmpty()) {
            // 有今天日期與 stat=ok 的空表，仍不代表已公布行情。
            throw new IOException("Official batch contains no published quote rows");
        }
        if (table.has("totalCount") && (!table.get("totalCount").isIntegralNumber()
                || !table.get("totalCount").canConvertToInt()
                || table.get("totalCount").intValue() != rows.size())) {
            throw new IOException("Official batch row count does not match published count");
        }
        int codeColumn = column(table.get("fields"), labels.get(0));
        int nameColumn = column(table.get("fields"), labels.get(1));
        int closeColumn = column(table.get("fields"), labels.get(2));
        int volumeColumn = column(table.get("fields"), labels.get(3));
        int lastColumn = Math.max(Math.max(codeColumn, nameColumn), Math.max(closeColumn, volumeColumn));
        var quotes = new LinkedHashMap<String, QuoteResult>();
        boolean priced = false;
        for (JsonNode row : rows) {
            if (!row.isArray() || row.size() <= lastColumn) {
                throw new IOException("Invalid official batch quote row");
            }
            String code = text(row.get(codeColumn), "stock code");
            String name = text(row.get(nameColumn), "stock name");
            if (!code.matches("[0-9]{4}[0-9A-Z]{0,2}") || name.isEmpty() || name.length() > 255) {
                throw new IOException("Invalid official batch stock identity");
            }
            BigDecimal close = number(row.get(closeColumn), false);
            BigDecimal volume = number(row.get(volumeColumn), true);
            boolean traded = close != null && close.signum() > 0 && volume != null && volume.signum() > 0;
            QuoteResult quote = QuoteResult.found(name, market, traded ? close : null, traded ? date : null);
            if (quotes.putIfAbsent(code, quote) != null) {
                throw new IOException("Duplicate stock code in official batch");
            }
            priced |= traded;
        }
        if (!priced) {
            throw new IOException("Official batch contains no valid closing prices");
        }
        return new OfficialBatchQuoteClient.Snapshot(date, quotes);
    }

    private static JsonNode findTable(JsonNode tables, List<String> labels) throws IOException {
        if (tables == null || !tables.isArray()) {
            throw new IOException("Missing official batch tables");
        }
        JsonNode found = null;
        for (JsonNode table : tables) {
            // MI_INDEX 的報表尾端可包含空物件佔位，不是缺欄位的行情表。
            if (table.isObject() && table.isEmpty()) {
                continue;
            }
            if (!table.isObject() || !table.path("fields").isArray()) {
                throw new IOException("Invalid official batch table fields");
            }
            boolean matches = true;
            for (String label : labels) {
                matches &= column(table.get("fields"), label) >= 0;
            }
            if (matches) {
                if (found != null) {
                    throw new IOException("Ambiguous official batch quote tables");
                }
                found = table;
            }
        }
        if (found == null) {
            throw new IOException("Missing official batch quote columns");
        }
        return found;
    }

    private static int column(JsonNode fields, String label) throws IOException {
        int found = -1;
        for (int i = 0; i < fields.size(); i++) {
            if (!fields.get(i).isTextual()) {
                throw new IOException("Invalid official batch column label");
            }
            String normalized = fields.get(i).asText().replaceAll("<[^>]*>", "")
                    .replaceAll("[\\s\\u3000\\u00a0]", "");
            if (label.equals(normalized)) {
                if (found >= 0) {
                    throw new IOException("Duplicate official batch column");
                }
                found = i;
            }
        }
        return found;
    }

    private static String text(JsonNode node, String label) throws IOException {
        if (node == null || !node.isTextual()) {
            throw new IOException("Missing or invalid official batch " + label);
        }
        return node.asText().strip();
    }

    private static BigDecimal number(JsonNode node, boolean integer) throws IOException {
        if (node == null || !(node.isNull() || node.isTextual() || node.isNumber())) {
            throw new IOException("Invalid official batch numeric cell");
        }
        String value = node.isNull() ? "" : node.asText().strip();
        if (value.isEmpty() || value.matches("-{2,}")) {
            return null;
        }
        String whole = "(?:[0-9]+|[0-9]{1,3}(?:,[0-9]{3})+)";
        if (!value.matches(whole + (integer ? "" : "(?:\\.[0-9]{1,2})?"))) {
            throw new IOException("Invalid official batch " + (integer ? "volume" : "price"));
        }
        try {
            BigDecimal result = new BigDecimal(value.replace(",", ""));
            if (!integer && result.precision() - result.scale() > 36) {
                throw new IOException("Official batch price exceeds storage range");
            }
            return result;
        } catch (NumberFormatException invalid) {
            throw new IOException("Invalid official batch number", invalid);
        }
    }

    private static LocalDate reportDate(String value) throws IOException {
        try {
            if (value.matches("[0-9]{8}")) {
                return LocalDate.parse(value, DateTimeFormatter.BASIC_ISO_DATE);
            }
            String[] parts = value.split("/");
            if (parts.length == 3) {
                int year = Integer.parseInt(parts[0]);
                return LocalDate.of(parts[0].length() <= 3 ? year + 1911 : year,
                        Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
            }
            return LocalDate.parse(value);
        } catch (DateTimeException | NumberFormatException invalid) {
            throw new IOException("Invalid official batch report date", invalid);
        }
    }
}
