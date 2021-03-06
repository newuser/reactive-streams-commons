package reactivestreams.commons.util;

import java.util.Queue;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;
import java.util.function.BooleanSupplier;

import org.reactivestreams.Subscriber;

public enum DrainHelper {
    ;

    static final long COMPLETED_MASK = 0x8000_0000_0000_0000L;
    static final long REQUESTED_MASK = 0x7FFF_FFFF_FFFF_FFFFL;

    /**
     * Perform a potential post-completion request accounting.
     *
     * @param <T> the value type emitted
     * @param n
     * @param actual
     * @param queue
     * @param state
     * @param isCancelled
     * @return true if the state indicates a completion state.
     */
    public static <T> boolean postCompleteRequest(long n,
                                                  Subscriber<? super T> actual,
                                                  Queue<T> queue,
                                                  AtomicLong state,
                                                  BooleanSupplier isCancelled) {
        for (; ; ) {
            long r = state.get();

            // extract the current request amount
            long r0 = r & REQUESTED_MASK;

            // preserve COMPLETED_MASK and calculate new requested amount
            long u = (r & COMPLETED_MASK) | BackpressureHelper.addCap(r0, n);

            if (state.compareAndSet(r, u)) {
                // (complete, 0) -> (complete, n) transition then replay
                if (r == COMPLETED_MASK) {

                    postCompleteDrain(n | COMPLETED_MASK, actual, queue, state, isCancelled);

                    return true;
                }
                // (active, r) -> (active, r + n) transition then continue with requesting from upstream
                return false;
            }
        }

    }

    /**
     * Drains the queue either in a pre- or post-complete state.
     *
     * @param n
     * @param actual
     * @param queue
     * @param state
     * @param isCancelled
     * @return true if the queue was completely drained or the drain process was cancelled
     */
    static <T> boolean postCompleteDrain(long n,
                                         Subscriber<? super T> actual,
                                         Queue<T> queue,
                                         AtomicLong state,
                                         BooleanSupplier isCancelled) {

// TODO enable fast-path
//        if (n == -1 || n == Long.MAX_VALUE) {
//            for (;;) {
//                if (isCancelled.getAsBoolean()) {
//                    break;
//                }
//                
//                T v = queue.poll();
//                
//                if (v == null) {
//                    actual.onComplete();
//                    break;
//                }
//                
//                actual.onNext(v);
//            }
//            
//            return true;
//        }

        long e = n & COMPLETED_MASK;

        for (; ; ) {

            while (e != n) {
                if (isCancelled.getAsBoolean()) {
                    return true;
                }

                T t = queue.poll();

                if (t == null) {
                    actual.onComplete();
                    return true;
                }

                actual.onNext(t);
                e++;
            }

            if (isCancelled.getAsBoolean()) {
                return true;
            }

            if (queue.isEmpty()) {
                actual.onComplete();
                return true;
            }

            n = state.get();

            if (n == e) {

                n = state.addAndGet(-(e & REQUESTED_MASK));

                if ((n & REQUESTED_MASK) == 0L) {
                    return false;
                }

                e = n & COMPLETED_MASK;
            }
        }

    }

    /**
     * Tries draining the queue if the source just completed.
     *
     * @param <T> the value type emitted
     * @param actual
     * @param queue
     * @param state
     * @param isCancelled
     */
    public static <T> void postComplete(Subscriber<? super T> actual,
                                        Queue<T> queue,
                                        AtomicLong state,
                                        BooleanSupplier isCancelled) {

        if (queue.isEmpty()) {
            actual.onComplete();
            return;
        }

        if (postCompleteDrain(state.get(), actual, queue, state, isCancelled)) {
            return;
        }

        for (; ; ) {
            long r = state.get();

            if ((r & COMPLETED_MASK) != 0L) {
                return;
            }

            long u = r | COMPLETED_MASK;
            // (active, r) -> (complete, r) transition
            if (state.compareAndSet(r, u)) {
                // if the requested amount was non-zero, drain the queue
                if (r != 0L) {
                    postCompleteDrain(u, actual, queue, state, isCancelled);
                }

                return;
            }
        }

    }

    /**
     * Perform a potential post-completion request accounting.
     *
     * @param <T> the value type emitted
     * @param <F> the type of the parent class containing the field
     * @param n
     * @param actual
     * @param queue
     * @param field
     * @param instance 
     * @param isCancelled
     * @return true if the state indicates a completion state.
     */
    public static <T, F> boolean postCompleteRequest(long n,
                                                     Subscriber<? super T> actual,
                                                     Queue<T> queue,
                                                     AtomicLongFieldUpdater<F> field,
                                                     F instance,
                                                     BooleanSupplier isCancelled) {
        for (; ; ) {
            long r = field.get(instance);

            // extract the current request amount
            long r0 = r & REQUESTED_MASK;

            // preserve COMPLETED_MASK and calculate new requested amount
            long u = (r & COMPLETED_MASK) | BackpressureHelper.addCap(r0, n);

            if (field.compareAndSet(instance, r, u)) {
                // (complete, 0) -> (complete, n) transition then replay
                if (r == COMPLETED_MASK) {

                    postCompleteDrain(n | COMPLETED_MASK, actual, queue, field, instance, isCancelled);

                    return true;
                }
                // (active, r) -> (active, r + n) transition then continue with requesting from upstream
                return false;
            }
        }

    }

    /**
     * Drains the queue either in a pre- or post-complete state.
     *
     * @param n
     * @param actual
     * @param queue
     * @param field
     * @param isCancelled
     * @return true if the queue was completely drained or the drain process was cancelled
     */
    static <T, F> boolean postCompleteDrain(long n,
                                            Subscriber<? super T> actual,
                                            Queue<T> queue,
                                            AtomicLongFieldUpdater<F> field,
                                            F instance,
                                            BooleanSupplier isCancelled) {

// TODO enable fast-path
//        if (n == -1 || n == Long.MAX_VALUE) {
//            for (;;) {
//                if (isCancelled.getAsBoolean()) {
//                    break;
//                }
//                
//                T v = queue.poll();
//                
//                if (v == null) {
//                    actual.onComplete();
//                    break;
//                }
//                
//                actual.onNext(v);
//            }
//            
//            return true;
//        }

        long e = n & COMPLETED_MASK;

        for (; ; ) {

            while (e != n) {
                if (isCancelled.getAsBoolean()) {
                    return true;
                }

                T t = queue.poll();

                if (t == null) {
                    actual.onComplete();
                    return true;
                }

                actual.onNext(t);
                e++;
            }

            if (isCancelled.getAsBoolean()) {
                return true;
            }

            if (queue.isEmpty()) {
                actual.onComplete();
                return true;
            }

            n = field.get(instance);

            if (n == e) {

                n = field.addAndGet(instance, -(e & REQUESTED_MASK));

                if ((n & REQUESTED_MASK) == 0L) {
                    return false;
                }

                e = n & COMPLETED_MASK;
            }
        }

    }

    /**
     * Tries draining the queue if the source just completed.
     *
     * @param <T> the value type emitted
     * @param <F> the type of the parent class containing the field
     * @param actual
     * @param queue
     * @param field
     * @param instance
     * @param isCancelled
     */
    public static <T, F> void postComplete(Subscriber<? super T> actual,
                                           Queue<T> queue,
                                           AtomicLongFieldUpdater<F> field,
                                           F instance,
                                           BooleanSupplier isCancelled) {

        if (queue.isEmpty()) {
            actual.onComplete();
            return;
        }

        if (postCompleteDrain(field.get(instance), actual, queue, field, instance, isCancelled)) {
            return;
        }

        for (; ; ) {
            long r = field.get(instance);

            if ((r & COMPLETED_MASK) != 0L) {
                return;
            }

            long u = r | COMPLETED_MASK;
            // (active, r) -> (complete, r) transition
            if (field.compareAndSet(instance, r, u)) {
                // if the requested amount was non-zero, drain the queue
                if (r != 0L) {
                    postCompleteDrain(u, actual, queue, field, instance, isCancelled);
                }

                return;
            }
        }

    }

}
