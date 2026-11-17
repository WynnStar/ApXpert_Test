package tw.holdings.quote;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.spi.ToolProvider;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuotePackageDependenciesTest {
    @Test
    void holdingClassesDoNotDependDirectlyOnOfficialClients() throws URISyntaxException {
        Path classes = Path.of(QuoteService.class.getProtectionDomain().getCodeSource().getLocation().toURI());
        ToolProvider jdeps = ToolProvider.findFirst("jdeps")
                .orElseThrow(() -> new IllegalStateException("需要完整 JDK 的 jdeps 工具"));
        StringWriter output = new StringWriter();
        PrintWriter writer = new PrintWriter(output);
        int result = jdeps.run(writer, writer, "--ignore-missing-deps", "-verbose:class", "-filter:none",
                "-include", "tw\\.holdings\\.holding\\..+", classes.toString());
        assertEquals(0, result, output::toString);

        Pattern directClientDependency = Pattern.compile("\\s*tw\\.holdings\\.holding\\.\\S+\\s+->\\s+"
                + "tw\\.holdings\\.quote\\.client\\.\\S+.*");
        var violations = output.toString().lines()
                .filter(line -> directClientDependency.matcher(line).matches())
                .map(String::strip)
                .toList();
        assertTrue(violations.isEmpty(),
                () -> "持股套件請透過 QuoteService 查行情，請移除對 quote.client 的直接依賴\n"
                        + String.join("\n", violations));
    }
}
