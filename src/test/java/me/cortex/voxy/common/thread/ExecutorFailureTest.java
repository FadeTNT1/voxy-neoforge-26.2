package me.cortex.voxy.common.thread;

import java.util.concurrent.atomic.AtomicInteger;

public final class ExecutorFailureTest {
    public static void main(String[] args) throws Exception {
        var errors = new AtomicInteger();
        var executor = new PerThreadContextExecutor(() -> {
            throw new IllegalStateException("Injected SQL context setup failure");
        }, error -> errors.incrementAndGet());
        if (!executor.run() || errors.get() != 1) throw new AssertionError("Context failure not handled");
        var shutdown = Thread.ofVirtual().start(executor::shutdown);
        shutdown.join(3000);
        if (shutdown.isAlive()) throw new AssertionError("Context failure blocked executor shutdown");
        System.out.println("Executor failure passed: failed context setup reported and shutdown terminates.");
    }
}
