/*
 * Copyright 2016-2017 David Karnok
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package hu.akarnokd.rxjava2.parallel;

import java.util.concurrent.atomic.*;

import org.reactivestreams.*;

import io.reactivex.Flowable;
import io.reactivex.exceptions.Exceptions;
import io.reactivex.functions.BiFunction;
import io.reactivex.internal.functions.ObjectHelper;
import io.reactivex.internal.subscriptions.*;
import io.reactivex.plugins.RxJavaPlugins;

/**
 * Reduces all 'rails' into a single value which then gets reduced into a single
 * Publisher sequence.
 *
 * @param <T> the value type
 */
@SuppressWarnings("deprecation")
final class ParallelReduceFull<T> extends Flowable<T> {

    final ParallelFlowable<? extends T> source;

    final BiFunction<T, T, T> reducer;

    ParallelReduceFull(ParallelFlowable<? extends T> source, BiFunction<T, T, T> reducer) {
        this.source = source;
        this.reducer = reducer;
    }

    @Override
    protected void subscribeActual(Subscriber<? super T> s) {
        ParallelReduceFullMainSubscriber<T> parent = new ParallelReduceFullMainSubscriber<T>(s, source.parallelism(), reducer);
        s.onSubscribe(parent);

        source.subscribe(parent.subscribers);
    }

    static final class ParallelReduceFullMainSubscriber<T> extends DeferredScalarSubscription<T> {


        private static final long serialVersionUID = -5370107872170712765L;

        final ParallelReduceFullInnerSubscriber<T>[] subscribers;

        final BiFunction<T, T, T> reducer;

        final AtomicReference<SlotPair<T>> current = new AtomicReference<SlotPair<T>>();

        final AtomicInteger remaining = new AtomicInteger();

        final AtomicBoolean once = new AtomicBoolean();

        ParallelReduceFullMainSubscriber(Subscriber<? super T> subscriber, int n, BiFunction<T, T, T> reducer) {
            super(subscriber);
            @SuppressWarnings("unchecked")
            ParallelReduceFullInnerSubscriber<T>[] a = new ParallelReduceFullInnerSubscriber[n];
            for (int i = 0; i < n; i++) {
                a[i] = new ParallelReduceFullInnerSubscriber<T>(this, reducer);
            }
            this.subscribers = a;
            this.reducer = reducer;
            remaining.lazySet(n);
        }

        SlotPair<T> addValue(T value) {
            for (;;) {
                SlotPair<T> curr = current.get();

                if (curr == null) {
                    curr = new SlotPair<T>();
                    if (!current.compareAndSet(null, curr)) {
                        continue;
                    }
                }

                int c = curr.tryAcquireSlot();
                if (c < 0) {
                    current.compareAndSet(curr, null);
                    continue;
                }
                if (c == 0) {
                    curr.first = value;
                } else {
                    curr.second = value;
                }

                if (curr.releaseSlot()) {
                    current.compareAndSet(curr, null);
                    return curr;
                }
                return null;
            }
        }

        @Override
        public void cancel() {
            for (ParallelReduceFullInnerSubscriber<T> inner : subscribers) {
                inner.cancel();
            }
        }

        void innerError(Throwable ex) {
            if (once.compareAndSet(false, true)) {
                cancel();
                actual.onError(ex);
            } else {
                RxJavaPlugins.onError(ex);
            }
        }

        void innerComplete(T value) {
            if (value != null) {
                for (;;) {
                    SlotPair<T> sp = addValue(value);

                    if (sp != null) {

                        try {
                            value = reducer.apply(sp.first, sp.second);
                        } catch (Throwable ex) {
                            Exceptions.throwIfFatal(ex);
                            innerError(ex);
                            return;
                        }

                        if (value == null) {
                            innerError(new NullPointerException("The reducer returned a null value"));
                            return;
                        }
                    } else {
                        break;
                    }
                }
            }

            if (remaining.decrementAndGet() == 0) {
                SlotPair<T> sp = current.get();
                current.lazySet(null);

                if (sp != null) {
                    complete(sp.first);
                } else {
                    actual.onComplete();
                }
            }
        }
    }

    static final class ParallelReduceFullInnerSubscriber<T>
    extends AtomicReference<Subscription>
    implements Subscriber<T> {

        private static final long serialVersionUID = -7954444275102466525L;

        final ParallelReduceFullMainSubscriber<T> parent;

        final BiFunction<T, T, T> reducer;

        T value;

        boolean done;

        ParallelReduceFullInnerSubscriber(ParallelReduceFullMainSubscriber<T> parent, BiFunction<T, T, T> reducer) {
            this.parent = parent;
            this.reducer = reducer;
        }

        @Override
        public void onSubscribe(Subscription s) {
            if (SubscriptionHelper.setOnce(this, s)) {
                s.request(Long.MAX_VALUE);
            }
        }

        @Override
        public void onNext(T t) {
            if (done) {
                return;
            }
            T v = value;

            if (v == null) {
                value = t;
            } else {

                try {
                    v = ObjectHelper.requireNonNull(reducer.apply(v, t), "The reducer returned a null value");
                } catch (Throwable ex) {
                    Exceptions.throwIfFatal(ex);
                    get().cancel();
                    onError(ex);
                    return;
                }

                value = v;
            }
        }

        @Override
        public void onError(Throwable t) {
            if (done) {
                RxJavaPlugins.onError(t);
                return;
            }
            done = true;
            parent.innerError(t);
        }

        @Override
        public void onComplete() {
            if (done) {
                return;
            }
            done = true;
            parent.innerComplete(value);
        }

        void cancel() {
            SubscriptionHelper.cancel(this);
        }
    }

    static final class SlotPair<T> {

        T first;

        T second;

        volatile int acquireIndex;
        @SuppressWarnings("rawtypes")
        static final AtomicIntegerFieldUpdater<SlotPair> ACQ =
                AtomicIntegerFieldUpdater.newUpdater(SlotPair.class, "acquireIndex");


        volatile int releaseIndex;
        @SuppressWarnings("rawtypes")
        static final AtomicIntegerFieldUpdater<SlotPair> REL =
                AtomicIntegerFieldUpdater.newUpdater(SlotPair.class, "releaseIndex");

        int tryAcquireSlot() {
            for (;;) {
                int acquired = acquireIndex;
                if (acquired >= 2) {
                    return -1;
                }

                if (ACQ.compareAndSet(this, acquired, acquired + 1)) {
                    return acquired;
                }
            }
        }

        boolean releaseSlot() {
            return REL.incrementAndGet(this) == 2;
        }
    }
}
