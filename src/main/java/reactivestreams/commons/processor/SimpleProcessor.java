/*
 * Copyright (c) 2011-2016 Pivotal Software Inc, All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package reactivestreams.commons.processor;

import java.util.Arrays;
import java.util.Iterator;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLongFieldUpdater;
import java.util.concurrent.atomic.AtomicReferenceFieldUpdater;

import org.reactivestreams.Processor;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import reactivestreams.commons.flow.MultiProducer;
import reactivestreams.commons.flow.Producer;
import reactivestreams.commons.flow.Receiver;
import reactivestreams.commons.publisher.PublisherBase;
import reactivestreams.commons.state.Cancellable;
import reactivestreams.commons.state.Completable;
import reactivestreams.commons.state.Failurable;
import reactivestreams.commons.state.Introspectable;
import reactivestreams.commons.state.Requestable;
import reactivestreams.commons.util.BackpressureHelper;
import reactivestreams.commons.util.SubscriptionHelper;

/**
 * Dispatches onNext, onError and onComplete signals to zero-to-many Subscribers.
 * <p>
 * <p>
 * This implementation signals an IllegalStateException if a Subscriber is not ready to receive a value due to not
 * requesting enough.
 * <p>
 * <p>
 * The implementation ignores Subscriptions set via onSubscribe.
 * <p>
 * <p>
 * A terminated TestProcessor will emit the terminal signal to late subscribers.
 *
 * @param <T> the input and output value type
 */
public final class SimpleProcessor<T> 
    extends PublisherBase<T>
    implements Processor<T, T>, Receiver, Completable, Failurable, MultiProducer {

    @SuppressWarnings("rawtypes")
    private static final SimpleProcessorSubscription[] EMPTY = new SimpleProcessorSubscription[0];

    @SuppressWarnings("rawtypes")
    private static final SimpleProcessorSubscription[] TERMINATED = new SimpleProcessorSubscription[0];

    @SuppressWarnings("unchecked")
    private volatile     SimpleProcessorSubscription<T>[]                                            subscribers = EMPTY;
    @SuppressWarnings("rawtypes")
    private static final AtomicReferenceFieldUpdater<SimpleProcessor, SimpleProcessorSubscription[]> SUBSCRIBERS =
      AtomicReferenceFieldUpdater.newUpdater(SimpleProcessor.class,
        SimpleProcessorSubscription[].class,
        "subscribers");

    private Throwable error;

    @Override
    public void onSubscribe(Subscription s) {
        Objects.requireNonNull(s, "s");
        if (subscribers != TERMINATED) {
            s.request(Long.MAX_VALUE);
        } else {
            s.cancel();
        }
    }

    @Override
    public void onNext(T t) {
        Objects.requireNonNull(t, "t");

        for (SimpleProcessorSubscription<T> s : subscribers) {
            s.onNext(t);
        }
    }

    @Override
    public void onError(Throwable t) {
        Objects.requireNonNull(t, "t");

        error = t;
        for (SimpleProcessorSubscription<?> s : SUBSCRIBERS.getAndSet(this, TERMINATED)) {
            s.onError(t);
        }
    }

    @Override
    public void onComplete() {
        for (SimpleProcessorSubscription<?> s : SUBSCRIBERS.getAndSet(this, TERMINATED)) {
            s.onComplete();
        }
    }

    @Override
    public void subscribe(Subscriber<? super T> s) {
        Objects.requireNonNull(s, "s");

        SimpleProcessorSubscription<T> p = new SimpleProcessorSubscription<>(s, this);
        s.onSubscribe(p);

        if (add(p)) {
            if (p.cancelled) {
                remove(p);
            }
        } else {
            Throwable e = error;
            if (e != null) {
                s.onError(e);
            } else {
                s.onComplete();
            }
        }
    }

    @Override
    public boolean isStarted() {
        return true;
    }

    @Override
    public boolean isTerminated() {
        return TERMINATED == subscribers;
    }

    @Override
    public Iterator<?> downstreams() {
        return Arrays.asList(subscribers).iterator();
    }

    @Override
    public long downstreamCount() {
        return subscribers.length;
    }

    boolean add(SimpleProcessorSubscription<T> s) {
        SimpleProcessorSubscription<T>[] a = subscribers;
        if (a == TERMINATED) {
            return false;
        }

        synchronized (this) {
            a = subscribers;
            if (a == TERMINATED) {
                return false;
            }
            int len = a.length;

            @SuppressWarnings("unchecked") SimpleProcessorSubscription<T>[] b = new SimpleProcessorSubscription[len + 1];
            System.arraycopy(a, 0, b, 0, len);
            b[len] = s;

            subscribers = b;

            return true;
        }
    }

    @SuppressWarnings("unchecked")
    void remove(SimpleProcessorSubscription<T> s) {
        SimpleProcessorSubscription<T>[] a = subscribers;
        if (a == TERMINATED || a == EMPTY) {
            return;
        }

        synchronized (this) {
            a = subscribers;
            if (a == TERMINATED || a == EMPTY) {
                return;
            }
            int len = a.length;

            int j = -1;

            for (int i = 0; i < len; i++) {
                if (a[i] == s) {
                    j = i;
                    break;
                }
            }
            if (j < 0) {
                return;
            }
            if (len == 1) {
                subscribers = EMPTY;
                return;
            }

            SimpleProcessorSubscription<T>[] b = new SimpleProcessorSubscription[len - 1];
            System.arraycopy(a, 0, b, 0, j);
            System.arraycopy(a, j + 1, b, j, len - j - 1);

            subscribers = b;
        }
    }

    public boolean hasSubscribers() {
        SimpleProcessorSubscription<T>[] s = subscribers;
        return s != EMPTY && s != TERMINATED;
    }

    public boolean hasCompleted() {
        if (subscribers == TERMINATED) {
            return error == null;
        }
        return false;
    }

    public boolean hasError() {
        if (subscribers == TERMINATED) {
            return error != null;
        }
        return false;
    }

    @Override
    public Throwable getError() {
        if (subscribers == TERMINATED) {
            return error;
        }
        return null;
    }

    static final class SimpleProcessorSubscription<T> implements Subscription, Introspectable, Completable, Requestable,
                                                                 Receiver, Producer, Cancellable {

        final Subscriber<? super T> actual;

        final SimpleProcessor<T> parent;

        volatile boolean cancelled;

        volatile long requested;
        @SuppressWarnings("rawtypes")
        static final AtomicLongFieldUpdater<SimpleProcessorSubscription> REQUESTED =
          AtomicLongFieldUpdater.newUpdater(SimpleProcessorSubscription.class, "requested");

        public SimpleProcessorSubscription(Subscriber<? super T> actual, SimpleProcessor<T> parent) {
            this.actual = actual;
            this.parent = parent;
        }

        @Override
        public void request(long n) {
            if (SubscriptionHelper.validate(n)) {
                BackpressureHelper.addAndGet(REQUESTED, this, n);
            }
        }

        @Override
        public void cancel() {
            if (!cancelled) {
                cancelled = true;
                parent.remove(this);
            }
        }

        @Override
        public boolean isCancelled() {
            return cancelled;
        }

        @Override
        public Subscriber<? super T> downstream() {
            return actual;
        }

        @Override
        public long requestedFromDownstream() {
            return 0;
        }

        @Override
        public Processor<T, T> upstream() {
            return parent;
        }

        void onNext(T value) {
            if (requested != 0L) {
                actual.onNext(value);
                if (requested != Long.MAX_VALUE) {
                    REQUESTED.decrementAndGet(this);
                }
                return;
            }
            parent.remove(this);
            actual.onError(new IllegalStateException("Can't deliver value due to lack of requests"));
        }

        void onError(Throwable e) {
            actual.onError(e);
        }

        void onComplete() {
            actual.onComplete();
        }

        @Override
        public boolean isStarted() {
            return parent.isStarted();
        }

        @Override
        public boolean isTerminated() {
            return parent.isTerminated();
        }

        @Override
        public int getMode() {
            return INNER;
        }

        @Override
        public String getName() {
            return getClass().getSimpleName();
        }
    }

    @Override
    public Object upstream() {
        return null;
    }

    @Override
    public int getMode() {
        return 0;
    }
}
