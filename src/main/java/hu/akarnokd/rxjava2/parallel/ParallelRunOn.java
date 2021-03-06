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

import io.reactivex.Scheduler;
import io.reactivex.Scheduler.Worker;
import io.reactivex.internal.queue.SpscArrayQueue;
import io.reactivex.internal.subscriptions.SubscriptionHelper;
import io.reactivex.internal.util.BackpressureHelper;
import io.reactivex.plugins.RxJavaPlugins;

/**
 * Ensures each 'rail' from upstream runs on a Worker from a Scheduler.
 *
 * @param <T> the value type
 */
@SuppressWarnings("deprecation")
final class ParallelRunOn<T> extends ParallelFlowable<T> {
    final ParallelFlowable<? extends T> source;

    final Scheduler scheduler;

    final int prefetch;

    ParallelRunOn(ParallelFlowable<? extends T> parent,
            Scheduler scheduler, int prefetch) {
        this.source = parent;
        this.scheduler = scheduler;
        this.prefetch = prefetch;
    }

    @Override
    public void subscribe(Subscriber<? super T>[] subscribers) {
        if (!validate(subscribers)) {
            return;
        }

        int n = subscribers.length;

        @SuppressWarnings("unchecked")
        Subscriber<T>[] parents = new Subscriber[n];

        int prefetch = this.prefetch;

        for (int i = 0; i < n; i++) {
            Subscriber<? super T> a = subscribers[i];

            Worker w = scheduler.createWorker();
            SpscArrayQueue<T> q = new SpscArrayQueue<T>(prefetch);

            RunOnSubscriber<T> parent = new RunOnSubscriber<T>(a, prefetch, q, w);
            parents[i] = parent;
        }

        source.subscribe(parents);
    }


    @Override
    public int parallelism() {
        return source.parallelism();
    }

    static final class RunOnSubscriber<T>
    extends AtomicInteger
    implements Subscriber<T>, Subscription, Runnable {


        private static final long serialVersionUID = 1075119423897941642L;

        final Subscriber<? super T> actual;

        final int prefetch;

        final int limit;

        final SpscArrayQueue<T> queue;

        final Worker worker;

        Subscription s;

        volatile boolean done;

        Throwable error;

        final AtomicLong requested = new AtomicLong();

        volatile boolean cancelled;

        int consumed;

        RunOnSubscriber(Subscriber<? super T> actual, int prefetch, SpscArrayQueue<T> queue, Worker worker) {
            this.actual = actual;
            this.prefetch = prefetch;
            this.queue = queue;
            this.limit = prefetch - (prefetch >> 2);
            this.worker = worker;
        }

        @Override
        public void onSubscribe(Subscription s) {
            if (SubscriptionHelper.validate(this.s, s)) {
                this.s = s;

                actual.onSubscribe(this);

                s.request(prefetch);
            }
        }

        @Override
        public void onNext(T t) {
            if (done) {
                return;
            }
            if (!queue.offer(t)) {
                onError(new IllegalStateException("Queue is full?!"));
                return;
            }
            schedule();
        }

        @Override
        public void onError(Throwable t) {
            if (done) {
                RxJavaPlugins.onError(t);
                return;
            }
            error = t;
            done = true;
            schedule();
        }

        @Override
        public void onComplete() {
            if (done) {
                return;
            }
            done = true;
            schedule();
        }

        @Override
        public void request(long n) {
            if (SubscriptionHelper.validate(n)) {
                BackpressureHelper.add(requested, n);
                schedule();
            }
        }

        @Override
        public void cancel() {
            if (!cancelled) {
                cancelled = true;
                s.cancel();
                worker.dispose();

                if (getAndIncrement() == 0) {
                    queue.clear();
                }
            }
        }

        void schedule() {
            if (getAndIncrement() == 0) {
                worker.schedule(this);
            }
        }

        @Override
        public void run() {
            int missed = 1;
            int c = consumed;
            SpscArrayQueue<T> q = queue;
            Subscriber<? super T> a = actual;
            int lim = limit;

            for (;;) {

                long r = requested.get();
                long e = 0L;

                while (e != r) {
                    if (cancelled) {
                        q.clear();
                        return;
                    }

                    boolean d = done;

                    if (d) {
                        Throwable ex = error;
                        if (ex != null) {
                            q.clear();

                            a.onError(ex);

                            worker.dispose();
                            return;
                        }
                    }

                    T v = q.poll();

                    boolean empty = v == null;

                    if (d && empty) {
                        a.onComplete();

                        worker.dispose();
                        return;
                    }

                    if (empty) {
                        break;
                    }

                    a.onNext(v);

                    e++;

                    int p = ++c;
                    if (p == lim) {
                        c = 0;
                        s.request(p);
                    }
                }

                if (e == r) {
                    if (cancelled) {
                        q.clear();
                        return;
                    }

                    if (done) {
                        Throwable ex = error;
                        if (ex != null) {
                            q.clear();

                            a.onError(ex);

                            worker.dispose();
                            return;
                        }
                        if (q.isEmpty()) {
                            a.onComplete();

                            worker.dispose();
                            return;
                        }
                    }
                }

                if (e != 0L && r != Long.MAX_VALUE) {
                    requested.addAndGet(-e);
                }

                int w = get();
                if (w == missed) {
                    consumed = c;
                    missed = addAndGet(-missed);
                    if (missed == 0) {
                        break;
                    }
                } else {
                    missed = w;
                }
            }
        }
    }
}
