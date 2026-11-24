package tw.holdings.quote.client;

import java.io.IOException;
import java.time.Instant;

/** HTTP 邊界對外提供的退避期限，不公開限流門閂。 */
public final class OfficialSourceUnavailableException extends IOException {
    private static final long serialVersionUID=1L;
    private final Instant availableAt;
    OfficialSourceUnavailableException(IOException cause, Instant availableAt) {
        super(cause);this.availableAt=availableAt;
    }
    public Instant availableAt() { return availableAt; }
}
