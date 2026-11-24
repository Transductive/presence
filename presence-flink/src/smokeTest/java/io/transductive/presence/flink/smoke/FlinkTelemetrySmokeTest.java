package io.transductive.presence.flink.smoke;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.transductive.presence.flink.Decision;
import java.nio.file.Path;
import java.time.Duration;
import java.util.HashSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class FlinkTelemetrySmokeTest {
    @Test
    void freshInputsAreUniqueAndRepeatsUseTheSameKeyAndId() {
        var identities = new HashSet<String>();
        var tenants = new HashSet<String>();
        int forwardedRepeats = 0;
        for (int cycle = 0; cycle < 1_000; cycle++) {
            var fresh = FlinkTelemetrySmoke.fresh(cycle);
            var repeat = FlinkTelemetrySmoke.repeat(fresh);
            assertTrue(identities.add(fresh.tenant() + "/" + fresh.id()));
            tenants.add(fresh.tenant());
            assertEquals(fresh.tenant(), repeat.tenant());
            assertEquals(fresh.id(), repeat.id());
            assertFalse(fresh.isTombstone());
            // Preserve known-absent positives so the sink can count false positives.
            assertEquals(Decision.FORWARD_WITH_MEMBERSHIP, FlinkTelemetrySmoke.decision(fresh));
            if (FlinkTelemetrySmoke.decision(repeat) == Decision.FORWARD_WITH_MEMBERSHIP) forwardedRepeats++;
        }
        assertEquals(16, tenants.size());
        assertEquals(250, forwardedRepeats);
    }

    @Test
    void onlyResetMarkersClearMembership() {
        for (var kind : FlinkTelemetrySmoke.Kind.values()) {
            var event = new FlinkTelemetrySmoke.Event("tenant", "id", kind, 0);
            assertEquals(kind == FlinkTelemetrySmoke.Kind.RESET, event.isTombstone());
        }
    }

    @Test
    void localReporterUsesTheRunLabelAndOneSecondCadence() {
        var config = FlinkTelemetrySmoke.configuration("run-id", Path.of("build/checkpoints"));
        assertEquals("http://127.0.0.1:4317", config.getString("metrics.reporter.otel.exporter.endpoint", ""));
        assertEquals("1 SECONDS", config.getString("metrics.reporter.otel.interval", ""));
        assertEquals("presence_run_id:run-id,presence_state_backend:hashmap", config.getString("metrics.reporter.otel.scope.variables.additional", ""));
        assertEquals("presence-flink-smoke", config.getString("metrics.reporter.otel.service.name", ""));
    }

    @Test
    void rocksDbEnablesNativeMetricsAndIncrementalCheckpoints() {
        var config = FlinkTelemetrySmoke.configuration("run", Path.of("build/checkpoints"), "rocksdb", "http://collector:4317");
        assertEquals("rocksdb", config.get(org.apache.flink.configuration.StateBackendOptions.STATE_BACKEND));
        assertTrue(config.get(org.apache.flink.configuration.CheckpointingOptions.INCREMENTAL_CHECKPOINTS));
        assertTrue(config.get(org.apache.flink.state.rocksdb.RocksDBNativeMetricOptions.MONITOR_SIZE_ALL_MEM_TABLES));
        assertTrue(config.get(org.apache.flink.state.rocksdb.RocksDBNativeMetricOptions.MONITOR_COMPACTION_WRITE_BYTES));
        assertTrue(config.get(org.apache.flink.state.rocksdb.RocksDBNativeMetricOptions.COLUMN_FAMILY_AS_VARIABLE));
        assertEquals("http://collector:4317", config.getString("metrics.reporter.otel.exporter.endpoint", ""));
        assertThrows(IllegalArgumentException.class,
                () -> FlinkTelemetrySmoke.configuration("run", Path.of("build/checkpoints"), "typo", "http://collector:4317"));
    }

    @Test
    void fractionalMinutesAreSupported() {
        assertEquals(Duration.ofSeconds(30), FlinkTelemetrySmoke.duration("0.5"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"0", "-1", "NaN", "Infinity", "1441", "1e-30", "abc"})
    void invalidDurationsAreRejected(String minutes) {
        assertThrows(IllegalArgumentException.class, () -> FlinkTelemetrySmoke.duration(minutes));
    }
}
