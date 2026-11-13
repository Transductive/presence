package io.transductive.presence.flink;

import io.transductive.presence.bloom.HashingStrategy;
import java.io.Serializable;

record BloomConfiguration(boolean scalable, long capacity, double probability,
        HashingStrategy hashingStrategy, BloomBackend backend) implements Serializable {
    private static final long serialVersionUID = 1L;
}
