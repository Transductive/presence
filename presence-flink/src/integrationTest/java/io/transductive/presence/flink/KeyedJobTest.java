package io.transductive.presence.flink;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import org.apache.flink.api.common.RuntimeExecutionMode;
import org.apache.flink.api.common.typeinfo.TypeInformation;
import org.apache.flink.api.common.typeinfo.Types;
import org.apache.flink.api.java.tuple.Tuple2;
import org.apache.flink.configuration.Configuration;
import org.apache.flink.configuration.RestOptions;
import org.apache.flink.streaming.api.environment.StreamExecutionEnvironment;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@SuppressWarnings("try")
class KeyedJobTest {
    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    @Timeout(90)
    void routesGroupsAcrossParallelTasksAndPreservesTheOriginalType(boolean scalable) throws Exception {
        var configuration = new Configuration();
        configuration.set(RestOptions.BIND_PORT, "0");
        try (var environment = StreamExecutionEnvironment.createLocalEnvironment(2, configuration)) {
            environment.setRuntimeMode(RuntimeExecutionMode.STREAMING);
            environment.getConfig().enableObjectReuse();
            TypeInformation<Tuple2<String, String>> type = Types.TUPLE(Types.STRING, Types.STRING);
            var input = new ArrayList<Tuple2<String, String>>();
            for (int group = 0; group < 20; group++) {
                for (int repeat = 0; repeat < 4; repeat++) input.add(Tuple2.of("tenant-" + group, "same-event-id"));
            }
            var keyed = environment.fromData(input, type).keyBy(event -> event.f0);
            var builder = BloomDeduplicator.<Tuple2<String, String>>by(event -> event.f1)
                    .backend(BloomBackend.HEAP).onPossibleDuplicate(event -> Decision.DROP);
            if (scalable) builder.scalable(1_000);
            else builder.fixed(1_000);
            var deduplicator = builder.build();
            // Later builder edits must not change a previously built deduplicator.
            builder.onPossibleDuplicate(event -> Decision.FORWARD);
            var output = deduplicator.applyTo(keyed).uid("membership");
            assertEquals(type, output.getType());
            List<Tuple2<String, String>> records = new ArrayList<>();
            try (var results = output.executeAndCollect()) {
                results.forEachRemaining(records::add);
            }
            assertEquals(20, records.size());
            assertEquals(20, records.stream().map(value -> value.f0).distinct().count());
            assertTrue(records.stream().allMatch(value -> value.f1.equals("same-event-id")));
        }
    }
}
