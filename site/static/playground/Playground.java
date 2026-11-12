import io.transductive.presence.bloom.BloomFilter;

public class Playground {
    public static void main(String[] args) {
        var members = BloomFilter.forStrings(10_000, 0.01);

        members.put("alice");

        System.out.println("alice: " + members.mightContain("alice"));
        System.out.println("bob:   " + members.mightContain("bob"));

        // false: definitely absent. true: possibly present.
        // Try adding "bob", changing the values, or using Unicode.
    }
}
