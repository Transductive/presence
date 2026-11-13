package io.transductive.presence.flink;

import java.io.Serializable;
import java.net.URI;
import java.util.Locale;
import org.apache.flink.configuration.CheckpointingOptions;
import org.apache.flink.streaming.api.environment.CheckpointConfig;

/**
 * Retains only a diagnostic flag and a sanitized storage description, never job secrets.
 * @param enabled whether periodic checkpoints were configured when the operator was attached
 * @param storage sanitized description of checkpoint storage
 * @serial exclude
 */
record CheckpointAwareness(boolean enabled, String storage) implements Serializable {
    private static final long serialVersionUID = 1L;

    static CheckpointAwareness capture(CheckpointConfig checkpoints) {
        var configuration = checkpoints.toConfiguration();
        String directory = configuration.getOptional(CheckpointingOptions.CHECKPOINTS_DIRECTORY).orElse(null);
        String storage = "cluster-configured storage";
        if (directory != null) {
            try {
                String scheme = URI.create(directory).getScheme();
                storage = scheme != null && scheme.matches("[A-Za-z][A-Za-z0-9+.-]*")
                        ? scheme.toLowerCase(Locale.ROOT) + " checkpoint storage" : "filesystem checkpoint storage";
            } catch (IllegalArgumentException ignored) {
                storage = "filesystem checkpoint storage";
            }
        } else {
            String configured = configuration.getOptional(CheckpointingOptions.CHECKPOINT_STORAGE).orElse("");
            if (configured.equals("jobmanager")) storage = "JobManager checkpoint storage";
            if (configured.equals("filesystem")) storage = "filesystem checkpoint storage";
        }
        return new CheckpointAwareness(checkpoints.isCheckpointingEnabled(), storage);
    }

    String warning() {
        return "Presence BloomDeduplicator uses HEAP (operator-owned memory), while Flink checkpointing is active ("
                + storage + "). Bloom membership is NOT included in checkpoints or savepoints. "
                + "Restart, restore, or rescaling starts with empty membership and can forward repeats. "
                + "Checkpoint storage and Flink's managed state backend do not change this behavior. Continuing as configured.";
    }
}
