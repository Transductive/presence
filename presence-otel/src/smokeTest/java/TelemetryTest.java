import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class TelemetryTest {
    @Test
    void readsMinutesBeforeStartingInfrastructure() {
        assertEquals(1, Telemetry.smokeMinutes(new String[0]));
        assertEquals(0.5, Telemetry.smokeMinutes(new String[] {"--minutes", "0.5"}));
        assertThrows(IllegalArgumentException.class, () -> Telemetry.smokeMinutes(new String[] {"--minutes"}));
        assertThrows(IllegalArgumentException.class, () -> Telemetry.smokeMinutes(new String[] {"--typo", "1"}));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "NaN", "Infinity", "1441", "1e-30", "abc"})
    void rejectsInvalidMinutes(String minutes) {
        assertThrows(IllegalArgumentException.class,
                () -> Telemetry.smokeMinutes(new String[] {"--minutes", minutes}));
    }

    @Test
    void reportLinkSelectsTheExactRunAndWindow() {
        assertEquals("http://localhost:3000/d/presence-smoke/presence-smoke?theme=light&var-run=a+b&from=100&to=200&refresh=1s",
                Telemetry.dashboardUrl("a b", "100", "200"));
        assertEquals("http://localhost:3000/d/presence-flink-smoke/presence-flink?theme=light&var-run=a+b&from=100&to=200&refresh=1s",
                Telemetry.dashboardUrl(true, "a b", "100", "200"));
    }
}
