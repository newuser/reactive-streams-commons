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
package reactivestreams.commons.publisher;

import java.util.Objects;
import java.util.function.Function;

import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import reactivestreams.commons.flow.Loopback;
import reactivestreams.commons.flow.Producer;
import reactivestreams.commons.flow.Receiver;
import reactivestreams.commons.state.Completable;
import reactivestreams.commons.util.ExceptionHelper;
import reactivestreams.commons.util.SubscriptionHelper;
import reactivestreams.commons.util.UnsignalledExceptions;

/**
 * Filters out subsequent and repeated elements.
 *
 * @param <T> the value type
 * @param <K> the key type used for comparing subsequent elements
 */
public final class PublisherDistinctUntilChanged<T, K> extends PublisherSource<T, T> {

    final Function<? super T, K> keyExtractor;

    public PublisherDistinctUntilChanged(Publisher<? extends T> source, Function<? super T, K> keyExtractor) {
        super(source);
        this.keyExtractor = Objects.requireNonNull(keyExtractor, "keyExtractor");
    }

    @Override
    public void subscribe(Subscriber<? super T> s) {
        source.subscribe(new PublisherDistinctUntilChangedSubscriber<>(s, keyExtractor));
    }

    static final class PublisherDistinctUntilChangedSubscriber<T, K>
            implements Subscriber<T>, Receiver, Producer, Loopback, Completable, Subscription {
        final Subscriber<? super T> actual;

        final Function<? super T, K> keyExtractor;

        Subscription s;

        boolean done;

        K lastKey;

        public PublisherDistinctUntilChangedSubscriber(Subscriber<? super T> actual,
                                                       Function<? super T, K> keyExtractor) {
            this.actual = actual;
            this.keyExtractor = keyExtractor;
        }

        @Override
        public void onSubscribe(Subscription s) {
            if (SubscriptionHelper.validate(this.s, s)) {
                this.s = s;

                actual.onSubscribe(this);
            }
        }

        @Override
        public void onNext(T t) {
            if (done) {
                UnsignalledExceptions.onNextDropped(t);
                return;
            }

            K k;

            try {
                k = keyExtractor.apply(t);
            } catch (Throwable e) {
                s.cancel();
                ExceptionHelper.throwIfFatal(e);
                onError(ExceptionHelper.unwrap(e));
                return;
            }


            if (Objects.equals(lastKey, k)) {
                lastKey = k;
                s.request(1);
            } else {
                lastKey = k;
                actual.onNext(t);
            }
        }

        @Override
        public void onError(Throwable t) {
            if (done) {
                UnsignalledExceptions.onErrorDropped(t);
                return;
            }
            done = true;

            actual.onError(t);
        }

        @Override
        public void onComplete() {
            if (done) {
                return;
            }
            done = true;

            actual.onComplete();
        }

        @Override
        public boolean isStarted() {
            return s != null && !done;
        }

        @Override
        public boolean isTerminated() {
            return done;
        }

        @Override
        public Object downstream() {
            return actual;
        }

        @Override
        public Object connectedInput() {
            return keyExtractor;
        }

        @Override
        public Object connectedOutput() {
            return lastKey;
        }

        @Override
        public Object upstream() {
            return s;
        }
        
        @Override
        public void request(long n) {
            s.request(n);
        }
        
        @Override
        public void cancel() {
            s.cancel();
        }
    }
}
