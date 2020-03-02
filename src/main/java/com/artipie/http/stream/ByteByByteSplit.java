/*
 * MIT License
 *
 * Copyright (c) 2020 Artipie
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package com.artipie.http.stream;

import com.google.common.collect.EvictingQueue;
import org.apache.commons.lang3.ArrayUtils;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;
import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 *
 */
public class ByteByByteSplit extends ByteStreamSplit {

    /**
     * A ring buffer with bytes.
     */
    private final EvictingQueue<Byte> ring;

    private final AtomicReference<Subscription> subscription;
    private final AtomicReference<Subscriber<? super Publisher<ByteBuffer>>> subscriber;
    private final AtomicBoolean started;
    private final AtomicBoolean terminated;
    private final AtomicLong requested;

    /**
     * Ctor.
     * @param delim The delim.
     */
    public ByteByByteSplit(final byte[] delim) {
        super(delim);
        this.ring = EvictingQueue.<Byte>create(delim.length);
        this.subscription = new AtomicReference<>();
        this.subscriber = new AtomicReference<>();
        this.started = new AtomicBoolean(false);
        this.requested = new AtomicLong(0);
        this.terminated = new AtomicBoolean(false);
    }

    /// Publisher ///

    @Override
    public void subscribe(final Subscriber<? super Publisher<ByteBuffer>> sub) {
        if (this.subscriber.get() != null) {
            throw new IllegalStateException("Only one subscription is allowed");
        }
        this.subscriber.set(sub);
        sub.onSubscribe(new Subscription() {
            @Override
            public void request(final long ask) {
                ByteByByteSplit.this.subscription.get().request(ask);
            }

            @Override
            public void cancel() {
                throw new IllegalStateException("Cancel is not allowed");
            }
        });
        this.tryToStart();
    }


    /// Subscriber ///

    @Override
    public void onSubscribe(final Subscription sub) {
        if (this.subscriber.get() != null) {
            throw new IllegalStateException("Only one subscription is allowed");
        }
        this.subscription.set(sub);
    }

    @Override
    public void onNext(final ByteBuffer byteBuffer) {
        final byte[] bytes = new byte[byteBuffer.remaining()];
        byteBuffer.get(bytes);
        this.feedByteByByte(bytes);
    }

    private void feedByteByByte(byte[] bytes) {
        for (final byte each : bytes) {
            ring.add(each);
            final byte[] primitive = ArrayUtils.toPrimitive(ring.stream().toArray(Byte[]::new));
            if (Arrays.equals(delim, primitive)) {
                ring.peek();
            }
        }
    }

    @Override
    public void onError(final Throwable throwable) {
        final Subscriber<? super Publisher<ByteBuffer>> subscriber = this.subscriber.get();
        if (subscriber != null) {
            subscriber.onError(throwable);
        }
        this.terminated.set(true);
    }

    @Override
    public void onComplete() {
        final Subscriber<? super Publisher<ByteBuffer>> subscriber = this.subscriber.get();
        if (subscriber != null) {
            subscriber.onComplete();
        }
        this.terminated.set(true);
    }

    private void tryToStart() {
        if (this.subscriber.get() != null &&
            this.subscription.get() != null &&
            !this.terminated.get()) {
            this.started.compareAndSet(false, true);
        }
    }
}
