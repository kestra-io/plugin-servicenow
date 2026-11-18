package io.kestra.plugin.servicenow;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import com.fasterxml.jackson.annotation.JsonIgnore;

import io.kestra.core.models.WorkerJobLifecycle;
import io.kestra.core.runners.RunContext;

import lombok.AccessLevel;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;
import lombok.experimental.SuperBuilder;

@SuperBuilder
@Getter
@NoArgsConstructor
@ToString(callSuper = true)
@EqualsAndHashCode(callSuper = true)
public abstract class AbstractServiceNowPollingTask extends AbstractServiceNow implements WorkerJobLifecycle {
    @JsonIgnore
    @Getter(AccessLevel.NONE)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private final Set<Poll> polls = ConcurrentHashMap.newKeySet();
    @JsonIgnore
    @Getter(AccessLevel.NONE)
    @ToString.Exclude
    @EqualsAndHashCode.Exclude
    private final AtomicBoolean killRequested = new AtomicBoolean();

    protected Poll begin(RunContext context, Duration defaultTimeout, Duration maximumTimeout) throws Exception {
        var rTimeout = context.render(getTimeout()).as(Duration.class).orElse(defaultTimeout);
        var budget = rTimeout.compareTo(maximumTimeout) > 0 ? maximumTimeout : rTimeout;
        ServiceNowValues.duration(budget, defaultTimeout, "timeout", maximumTimeout);
        if (rTimeout.compareTo(maximumTimeout) > 0) {
            context.logger().warn("ServiceNow timeout {} exceeds the {} polling cap. Polling stops after {}.", rTimeout, maximumTimeout, budget);
        }
        var poll = new Poll(budget);
        polls.add(poll);
        if (killRequested.get()) {
            poll.cancel();
        }
        try {
            poll.check();
        } catch (RuntimeException e) {
            poll.close();
            throw e;
        }
        return poll;
    }

    @Override
    public void kill() {
        killRequested.set(true);
        polls.forEach(Poll::cancel);
    }

    @Override
    public void stop() {
        kill();
    }

    protected final class Poll implements AutoCloseable {
        private final CountDownLatch cancelled = new CountDownLatch(1);
        private final long deadline;

        private Poll(Duration timeout) {
            deadline = System.nanoTime() + timeout.toNanos();
        }

        void check() {
            if (cancelled.getCount() == 0 || Thread.currentThread().isInterrupted()) {
                throw new CancellationException("ServiceNow task was cancelled; polling and remaining writes have stopped.");
            }
            if (System.nanoTime() - deadline >= 0) {
                throw new ServiceNowApi.ApiException(-1, "ServiceNow task timeout expired before completion. Check the remote state before retrying.");
            }
        }

        Duration remaining() {
            check();
            return Duration.ofNanos(Math.max(1, deadline - System.nanoTime()));
        }

        void pause(Duration interval) throws InterruptedException {
            check();
            cancelled.await(Math.min(interval.toNanos(), remaining().toNanos()), TimeUnit.NANOSECONDS);
            check();
        }

        @Override
        public void close() {
            polls.remove(this);
            if (polls.isEmpty()) {
                killRequested.set(false);
            }
        }

        private void cancel() {
            cancelled.countDown();
        }
    }
}
