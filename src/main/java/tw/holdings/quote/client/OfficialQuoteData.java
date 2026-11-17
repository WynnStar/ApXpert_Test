package tw.holdings.quote.client;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Set;

import com.fasterxml.jackson.databind.JsonNode;

final class OfficialQuoteData {
    record Price(LocalDate date, BigDecimal close) {}

    private OfficialQuoteData() {}

    static Price latest(JsonNode fields, JsonNode rows, LocalDate today) throws IOException {
        int dateColumn = column(fields, Set.of("日期"));
        int closeColumn = column(fields, Set.of("收盤", "收盤價"));
        if (dateColumn < 0 || closeColumn < 0 || !rows.isArray()) {
            throw new IOException("Missing trade date or closing price column");
        }

        int volumeColumn = column(fields, Set.of("成交股數", "成交股數(股)", "成交張數"));
        Price latest = null;
        for (JsonNode row : rows) {
            if (!row.isArray() || row.size() <= Math.max(dateColumn, closeColumn)) {
                throw new IOException("Invalid daily trade row");
            }
            LocalDate date = parseDate(row.get(dateColumn).asText().strip());
            String rawClose = row.get(closeColumn).asText("").replace(",", "").strip();
            if (rawClose.isEmpty() || rawClose.equals("--") || rawClose.equals("---")) {
                continue;
            }
            if (volumeColumn >= 0 && !hasTradingVolume(row.path(volumeColumn).asText())) {
                continue;
            }
            BigDecimal close = new BigDecimal(rawClose);
            if (close.signum() > 0 && !date.isAfter(today)
                    && (latest == null || date.isAfter(latest.date()))) {
                latest = new Price(date, close);
            }
        }
        return latest;
    }

    private static boolean hasTradingVolume(String text) {
        String amount = text.replace(",", "").strip();
        return amount.matches("[0-9]+") && amount.chars().anyMatch(digit -> digit != '0');
    }

    private static int column(JsonNode fields, Set<String> labels) {
        for (int index = 0; index < fields.size(); index++) {
            if (labels.contains(fields.get(index).asText().replaceAll("[\\s\\u3000]", ""))) {
                return index;
            }
        }
        return -1;
    }

    private static LocalDate parseDate(String text) {
        String[] parts = text.split("/");
        if (parts.length == 3) {
            int year = Integer.parseInt(parts[0]);
            if (parts[0].length() < 4) {
                year += 1911;
            }
            return LocalDate.of(year, Integer.parseInt(parts[1]), Integer.parseInt(parts[2]));
        }
        return LocalDate.parse(text, DateTimeFormatter.ISO_LOCAL_DATE);
    }
}
