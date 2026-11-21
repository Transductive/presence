package io.transductive.presence.flink;

import java.io.Serializable;
import java.util.Map;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.common.typeinfo.Types;

/**
 * An unchanged input record together with its membership result before insertion.
 * The wrapper does not copy the value; normal Flink object-reuse rules still apply.
 *
 * @param value the original forwarded record
 * @param maybePresent true if the ID was possibly present in this group's current
 *        Bloom membership, including false positives; false if definitely absent
 * @param <T> the input record type
 */
public record MembershipResult<T>(T value, boolean maybePresent) implements Serializable {
    private static final long serialVersionUID = 1L;

    @SuppressWarnings("unchecked")
    static <T> TypeInformation<MembershipResult<T>> typeInformation(TypeInformation<T> valueType) {
        // The class literal erases T; the explicit field type preserves the input serializer.
        Class<MembershipResult<T>> resultClass = (Class<MembershipResult<T>>) (Class<?>) MembershipResult.class;
        return Types.POJO(resultClass, Map.of("value", valueType, "maybePresent", Types.BOOLEAN));
    }
}
