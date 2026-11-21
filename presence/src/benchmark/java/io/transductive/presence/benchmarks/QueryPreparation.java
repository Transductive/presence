package io.transductive.presence.benchmarks;

final class QueryPreparation {
    private QueryPreparation() { }

    static char[][] characters(String[] queries, String mode) {
        if (mode.equals("CACHED")) {
            return null;
        }
        if (!mode.equals("FRESH")) {
            throw new IllegalArgumentException("Unknown query mode: " + mode);
        }
        char[][] characters = new char[queries.length][];
        for (int i = 0; i < queries.length; i++) {
            characters[i] = queries[i].toCharArray();
        }
        return characters;
    }
}
