import io.transductive.presence.bloom.ScalableBloomFilter;

public class Playground {
    public static void main(String[] args) {
        // 1,000 is an initial estimate. The 1% target covers every layer.
        var members = ScalableBloomFilter.forStrings(1_000, 0.01);
        System.out.println("Starting layers: " + members.layerCount());

        for (int i = 0; i < 5_000; i++) {
            members.put("member-" + i);
        }

        System.out.println("Layers after 5,000 inserts: " + members.layerCount());
        System.out.println("First member: " + members.mightContain("member-0"));
        System.out.println("Last member:  " + members.mightContain("member-4999"));

        int missing = 0;
        for (int i = 0; i < 5_000; i++) {
            if (!members.mightContain("member-" + i)) missing++;
        }
        System.out.println("Inserted values missing: " + missing);
        System.out.println("Bit storage (bytes): " + members.bitSize() / 8);

        // false: definitely absent. true: possibly present.
        System.out.println("Unseen member: " + members.mightContain("not-inserted"));
        // Try changing the insertion count. Growth keeps earlier layers intact.
        // Bit storage excludes object overhead and hashing workspace.
    }
}
