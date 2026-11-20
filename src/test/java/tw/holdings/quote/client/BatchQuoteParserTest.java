package tw.holdings.quote.client;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.function.Consumer;
import java.util.stream.Stream;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import tw.holdings.quote.Market;

import static org.junit.jupiter.api.Assertions.*;

class BatchQuoteParserTest {
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final LocalDate DAY = LocalDate.of(2026, 11, 20);

    @ParameterizedTest
    @EnumSource(Market.class)
    void findsQuotesByColumnNamesAndKeepsEtfLeadingZeroes(Market market) throws Exception {
        var root = fixture(market);
        var result = BatchQuoteParser.parse(root, market, DAY);
        assertEquals(DAY, result.priceDate());
        assertEquals(3, result.quotes().size());
        String code = market == Market.TWSE ? "2330" : "6488";
        assertEquals(new BigDecimal(market == Market.TWSE ? "1100.00" : "100.00"),
                result.quotes().get(code).close());
        assertEquals(DAY, result.quotes().get(code).priceDate());
        assertEquals(market, result.quotes().get(code).market());
        if (market == Market.TWSE) {
            assertEquals(new BigDecimal("145.50"), result.quotes().get("0050").close());
        }
        var missing = result.quotes().get(market == Market.TWSE ? "1538" : "8069");
        assertNull(missing.close());
        assertNull(missing.priceDate());
        assertNotNull(missing.name());
    }

    @ParameterizedTest
    @EnumSource(Market.class)
    void normalizesHtmlWhitespaceAndDoesNotTrustTablePosition(Market market) throws Exception {
        var root = fixture(market);
        var quoteTable = table(root, market);
        var fields = (ArrayNode) quoteTable.get("fields");
        fields.set(3, JSON.getNodeFactory().textNode(
                market == Market.TWSE ? " <span>證券代號</span>　" : "<span>代號</span> "));
        // 把報價表再搬到不同位置，避免以固定 tables 索引解析。
        var tables = (ArrayNode) root.get("tables");
        tables.remove(market == Market.TWSE ? 1 : 0);
        tables.add(quoteTable);
        var snapshot = BatchQuoteParser.parse(root, market, DAY);
        assertTrue(snapshot.quotes().containsKey(market == Market.TWSE ? "2330" : "6488"));
    }

    @ParameterizedTest
    @EnumSource(Market.class)
    void zeroVolumeAndNullCloseKeepIdentityWithoutInventingPrice(Market market) throws Exception {
        var root = fixture(market);
        var rows = (ArrayNode) table(root, market).get("data");
        int volumeColumn = market == Market.TWSE ? 1 : 2;
        int closeColumn = market == Market.TWSE ? 2 : 1;
        ((ArrayNode) rows.get(0)).set(volumeColumn, JSON.getNodeFactory().textNode("0"));
        ((ArrayNode) rows.get(2)).set(closeColumn, JSON.getNodeFactory().nullNode());
        var snapshot = BatchQuoteParser.parse(root, market, DAY);
        String code = market == Market.TWSE ? "2330" : "6488";
        assertNull(snapshot.quotes().get(code).close());
        assertNull(snapshot.quotes().get(code).priceDate());
        assertNotNull(snapshot.quotes().get(code).name());
    }

    @Test
    void validatesTpexTableDateAndDeclaredRowCount() throws Exception {
        var root = fixture(Market.TPEX);
        table(root, Market.TPEX).put("totalCount", 3);
        assertDoesNotThrow(() -> BatchQuoteParser.parse(root, Market.TPEX, DAY));
        table(root, Market.TPEX).put("totalCount", 4);
        assertThrows(IOException.class, () -> BatchQuoteParser.parse(root, Market.TPEX, DAY));

        var wrongDate = fixture(Market.TPEX);
        table(wrongDate, Market.TPEX).put("date", "115/11/19");
        assertThrows(IOException.class, () -> BatchQuoteParser.parse(wrongDate, Market.TPEX, DAY));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidDocuments")
    void rejectsEntireMalformedBatchBeforeReturningAnyQuotes(String label, Consumer<ObjectNode> damage)
            throws Exception {
        var root = fixture(Market.TWSE);
        damage.accept(root);
        assertThrows(IOException.class, () -> BatchQuoteParser.parse(root, Market.TWSE, DAY), label);
    }


    @Test
    void rejectsLegacyArraysAndNullInsteadOfAcceptingTheOldEndpointFormat() throws Exception {
        assertThrows(IOException.class, () -> BatchQuoteParser.parse(JSON.readTree("[]"), Market.TWSE, DAY));
        assertThrows(IOException.class, () -> BatchQuoteParser.parse(JSON.readTree("null"), Market.TPEX, DAY));
        assertThrows(IOException.class, () -> BatchQuoteParser.parse(null, Market.TWSE, DAY));
    }

    static Stream<Arguments> invalidDocuments() {
        return Stream.of(
                bad("missing status", root -> root.remove("stat")),
                bad("invalid status type", root -> root.put("stat", true)),
                bad("not yet published", root -> root.put("stat", "很抱歉，沒有符合條件的資料!")),
                bad("missing date", root -> root.remove("date")),
                bad("older report date", root -> root.put("date", "20261119")),
                bad("future report date", root -> root.put("date", "20261121")),
                bad("invalid calendar date", root -> root.put("date", "20260230")),
                bad("missing tables", root -> root.remove("tables")),
                bad("invalid tables type", root -> root.put("tables", "wrong")),
                bad("missing fields", root -> table(root, Market.TWSE).remove("fields")),
                bad("missing data", root -> table(root, Market.TWSE).remove("data")),
                bad("wrong data type", root -> table(root, Market.TWSE).put("data", "wrong")),
                bad("empty published table", root -> table(root, Market.TWSE).putArray("data")),
                bad("incomplete row", root -> rows(root).addArray().add("incomplete")),
                bad("duplicate code", root -> rows(root).add(rows(root).get(0).deepCopy())),
                bad("invalid code", root -> row(root, 0).set(3, JSON.getNodeFactory().textNode("ABC"))),
                bad("missing name", root -> row(root, 0).set(0, JSON.getNodeFactory().textNode(""))),
                bad("malformed final price", root -> row(root, 2).set(2, JSON.getNodeFactory().textNode("bad"))),
                bad("negative price", root -> row(root, 0).set(2, JSON.getNodeFactory().textNode("-1"))),
                bad("invalid comma grouping", root -> row(root, 0).set(2, JSON.getNodeFactory().textNode("1,23.00"))),
                bad("price has three decimals", root -> row(root, 0).set(2, JSON.getNodeFactory().textNode("1.001"))),
                bad("price is an object", root -> row(root, 0).set(2, JSON.createObjectNode())),
                bad("invalid volume", root -> row(root, 0).set(1, JSON.getNodeFactory().textNode("-1"))),
                bad("missing price column", root ->
                        ((ArrayNode) table(root, Market.TWSE).get("fields")).set(2, JSON.getNodeFactory().textNode("未知欄位"))),
                bad("duplicate price column", root ->
                        ((ArrayNode) table(root, Market.TWSE).get("fields")).add("收盤價")),
                bad("ambiguous quote table", root ->
                        ((ArrayNode) root.get("tables")).add(table(root, Market.TWSE).deepCopy())),
                bad("all prices missing", root -> {
                    for (var r : rows(root)) {
                        ((ArrayNode) r).set(2, JSON.getNodeFactory().textNode("--"));
                    }
                }));
    }

    private static Arguments bad(String label, Consumer<ObjectNode> damage) {
        return Arguments.of(label, damage);
    }

    private static ObjectNode fixture(Market market) throws IOException {
        try (var input = BatchQuoteParserTest.class.getResourceAsStream(
                market == Market.TWSE ? "/quotes/twse-mi-index.json" : "/quotes/tpex-otc.json")) {
            if (input == null) {
                throw new IOException("Missing quote test fixture");
            }
            return (ObjectNode) JSON.readTree(input);
        }
    }

    private static ObjectNode table(ObjectNode root, Market market) {
        return (ObjectNode) root.get("tables").get(market == Market.TWSE ? 1 : 0);
    }

    private static ArrayNode rows(ObjectNode root) {
        return (ArrayNode) table(root, Market.TWSE).get("data");
    }

    private static ArrayNode row(ObjectNode root, int index) {
        return (ArrayNode) rows(root).get(index);
    }
}
