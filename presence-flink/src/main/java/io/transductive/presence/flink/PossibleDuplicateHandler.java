package io.transductive.presence.flink;

import java.io.Serializable;

/**
 * A serializable, synchronous policy for possible duplicates. Called on the
 * operator thread; avoid blocking I/O and do not mutate the input record.
 * Captured objects must also be serializable. Exceptions fail the task.
 *
 * @param <T> the stream record type
 */
@FunctionalInterface
public interface PossibleDuplicateHandler<T> extends Serializable {
    /**
     * Chooses what to do with a possible duplicate, including a possible false positive.
     *
     * @param event the unchanged incoming record
     * @return a non-null action
     * @throws Exception if deciding fails
     */
    Decision onPossibleDuplicate(T event) throws Exception;
}
