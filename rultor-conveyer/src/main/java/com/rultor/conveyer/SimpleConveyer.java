/**
 * Copyright (c) 2009-2013, rultor.com
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions
 * are met: 1) Redistributions of source code must retain the above
 * copyright notice, this list of conditions and the following
 * disclaimer. 2) Redistributions in binary form must reproduce the above
 * copyright notice, this list of conditions and the following
 * disclaimer in the documentation and/or other materials provided
 * with the distribution. 3) Neither the name of the rultor.com nor
 * the names of its contributors may be used to endorse or promote
 * products derived from this software without specific prior written
 * permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
 * "AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT
 * NOT LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND
 * FITNESS FOR A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL
 * THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT,
 * INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION)
 * HOWEVER CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT,
 * STRICT LIABILITY, OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE)
 * ARISING IN ANY WAY OUT OF THE USE OF THIS SOFTWARE, EVEN IF ADVISED
 * OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package com.rultor.conveyer;

import com.codahale.metrics.Counter;
import com.codahale.metrics.MetricRegistry;
import com.jcabi.aspects.Loggable;
import com.jcabi.aspects.Tv;
import com.jcabi.log.VerboseRunnable;
import com.rultor.spi.Instance;
import com.rultor.spi.Metricable;
import com.rultor.spi.Queue;
import com.rultor.spi.Repo;
import com.rultor.spi.User;
import com.rultor.spi.Users;
import com.rultor.spi.Work;
import java.io.Closeable;
import java.io.IOException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import javax.validation.constraints.NotNull;
import lombok.EqualsAndHashCode;
import lombok.ToString;
import org.apache.log4j.Level;
import org.apache.log4j.Logger;
import org.apache.log4j.PatternLayout;

/**
 * Horizontally scalable execution conveyer.
 *
 * @author Yegor Bugayenko (yegor@tpc2.com)
 * @version $Id$
 * @since 1.0
 * @checkstyle ClassDataAbstractionCoupling (500 lines)
 */
@Loggable(Loggable.INFO)
@ToString
@EqualsAndHashCode(of = "queue")
@SuppressWarnings("PMD.DoNotUseThreads")
public final class SimpleConveyer implements Closeable, Metricable {

    /**
     * Queue.
     */
    private final transient Queue queue;

    /**
     * Repo.
     */
    private final transient Repo repo;

    /**
     * Users.
     */
    private final transient Users users;

    /**
     * Log appender.
     */
    private final transient ConveyerAppender appender;

    /**
     * Counter of executed jobs.
     */
    private transient Counter counter;

    /**
     * Consumer and executer of new specs from Queue.
     */
    private final transient ScheduledExecutorService svc =
        Executors.newScheduledThreadPool(
            Runtime.getRuntime().availableProcessors() * Tv.TEN,
            new ThreadFactory() {
                private final transient AtomicLong group = new AtomicLong();
                @Override
                public Thread newThread(final Runnable runnable) {
                    return new Thread(
                        new ThreadGroup(
                            Long.toString(this.group.incrementAndGet())
                        ),
                        runnable,
                        String.format("conveyer-%d", this.group.get())
                    );
                }
            }
        );

    /**
     * Public ctor.
     * @param que The queue of specs
     * @param rep Repo
     * @param usrs Users
     * @checkstyle ParameterNumber (4 lines)
     */
    public SimpleConveyer(@NotNull final Queue que, @NotNull final Repo rep,
        @NotNull final Users usrs) {
        this.queue = que;
        this.repo = rep;
        this.users = usrs;
        this.appender = new ConveyerAppender();
        this.appender.setThreshold(Level.DEBUG);
        this.appender.setLayout(new PatternLayout("%m"));
        Logger.getRootLogger().addAppender(this.appender);
    }

    /**
     * Start the conveyer.
     */
    public void start() {
        this.svc.scheduleWithFixedDelay(
            new VerboseRunnable(
                new Runnable() {
                    @Override
                    public void run() {
                        SimpleConveyer.this.process();
                    }
                },
                true, false
            ),
            0, 1, TimeUnit.MICROSECONDS
        );
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void close() throws IOException {
        try {
            while (true) {
                this.svc.shutdownNow();
                if (this.svc.awaitTermination(1, TimeUnit.SECONDS)) {
                    break;
                }
                TimeUnit.SECONDS.sleep(Tv.FIVE);
                com.jcabi.log.Logger.info(
                    this, "waiting for threads termination"
                );
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IOException(ex);
        }
        Logger.getRootLogger().removeAppender(this.appender);
        this.appender.close();
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void register(@NotNull final MetricRegistry registry) {
        this.counter = registry.counter(
            MetricRegistry.name(this.getClass(), "done-jobs")
        );
    }

    /**
     * Process the next work from the queue.
     */
    @SuppressWarnings("PMD.AvoidCatchingGenericException")
    private void process() {
        Work work;
        try {
            work = this.queue.pull();
            final User owner = this.users.fetch(work.owner());
            final Instance instance = new LoggableInstance(
                this.repo.make(owner, work.spec()),
                this.appender,
                work,
                owner.units().get(work.unit())
            );
            instance.pulse();
            if (this.counter != null) {
                this.counter.inc();
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } catch (Repo.InstantiationException ex) {
            throw new IllegalStateException(ex);
        // @checkstyle IllegalCatch (1 line)
        } catch (Exception ex) {
            throw new IllegalStateException(ex);
        }
    }

}
