package reactivestreams.commons.publisher;

import java.util.Arrays;
import java.util.concurrent.TimeUnit;

import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.infra.Blackhole;
import org.reactivestreams.Publisher;

import reactivestreams.commons.publisher.internal.PerfSubscriber;


/**
 * Example benchmark. Run from command line as
 * <br>
 * gradle jmh -Pjmh='PublisherConcatArray2Perf'
 */
@BenchmarkMode(Mode.Throughput)
@Warmup(iterations = 5)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@OutputTimeUnit(TimeUnit.SECONDS)
@Fork(value = 1)
@State(Scope.Thread)
public class PublisherConcatArray2Perf {
    Publisher<Integer> rangeConcatRange;
    Publisher<Integer> rangeConcatJust;

    @SuppressWarnings("unchecked")
    @Setup
    public void setup() {
        PublisherBase<Integer> item = PublisherBase.range(1, 1000);

        PublisherBase<Integer>[] sources = new PublisherBase[1000];

        Arrays.fill(sources, item);

        rangeConcatRange = new PublisherConcatArray<>(sources);

        sources = new PublisherBase[1_000_000];

        Arrays.fill(sources, PublisherBase.just(777));

        rangeConcatJust = new PublisherConcatArray<>(sources);
    }

    @Benchmark
    public void rangeConcatRange(Blackhole bh) {
        rangeConcatRange.subscribe(new PerfSubscriber(bh));
    }

    @Benchmark
    public void rangeConcatJust(Blackhole bh) {
        rangeConcatJust.subscribe(new PerfSubscriber(bh));
    }
}
