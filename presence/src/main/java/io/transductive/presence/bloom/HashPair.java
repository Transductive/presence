package io.transductive.presence.bloom;

/** Reusable hash output owned by a filter; never exposed through the public API. */
final class HashPair {
    private long first;
    private long second;

    HashPair() { }

    HashPair(long first, long second) {
        set(first, second);
    }

    void set(long first, long second) {
        this.first = first;
        this.second = second;
    }

    long first() {
        return first;
    }

    long second() {
        return second;
    }
}
