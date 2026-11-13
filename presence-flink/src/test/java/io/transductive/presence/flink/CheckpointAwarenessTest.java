package io.transductive.presence.flink;

import static org.junit.jupiter.api.Assertions.*;

import org.apache.flink.configuration.CheckpointingOptions;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@SuppressWarnings("try")
class CheckpointAwarenessTest {
    @ParameterizedTest
    @ValueSource(strings = {"s3", "file", "hdfs"})
    void detectsCheckpointingAndNamesOnlyTheScheme(String scheme) throws Exception {
        var configuration = new Configuration();
        configuration.set(CheckpointingOptions.CHECKPOINTS_DIRECTORY,
                scheme + "://secret-user:secret-password@example.test/private/checkpoints?secret-token=123");
        try (var environment = new StreamExecutionEnvironment(configuration)) {
            environment.enableCheckpointing(1_000);
            var awareness = CheckpointAwareness.capture(environment.getCheckpointConfig());
            assertTrue(awareness.enabled());
            assertTrue(awareness.warning().contains(scheme + " checkpoint storage"));
            assertTrue(awareness.warning().contains("NOT included"));
            assertFalse(awareness.warning().contains("secret"));
            assertFalse(awareness.warning().contains("example.test"));
        }
    }

    @Test
    void aStorageDirectoryDoesNotMeanCheckpointingIsEnabled() throws Exception {
        var configuration = new Configuration();
        configuration.set(CheckpointingOptions.CHECKPOINTS_DIRECTORY, "file:///checkpoints");
        try (var environment = new StreamExecutionEnvironment(configuration)) {
            assertFalse(CheckpointAwareness.capture(environment.getCheckpointConfig()).enabled());
            environment.enableCheckpointing(1_000);
            assertTrue(CheckpointAwareness.capture(environment.getCheckpointConfig()).enabled());
            environment.getCheckpointConfig().disableCheckpointing();
            assertFalse(CheckpointAwareness.capture(environment.getCheckpointConfig()).enabled());
        }
    }

    @Test
    void checkpointingInJobManagerMemoryStillExcludesOperatorHeap() throws Exception {
        var configuration = new Configuration();
        configuration.set(CheckpointingOptions.CHECKPOINT_STORAGE, "jobmanager");
        try (var environment = new StreamExecutionEnvironment(configuration)) {
            environment.enableCheckpointing(1_000);
            var awareness = CheckpointAwareness.capture(environment.getCheckpointConfig());
            assertTrue(awareness.enabled());
            assertTrue(awareness.warning().contains("JobManager checkpoint storage"));
            assertTrue(awareness.warning().contains("HEAP"));
        }
    }
}
