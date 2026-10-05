package me.cortex.voxy.common.world;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class ActiveSectionFailureTest {
    public static void main(String[] args) throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var tracker = new ActiveSectionTracker(2, section -> {
            entered.countDown();
            try { release.await(); }
            catch (InterruptedException e) { throw new RuntimeException(e); }
            throw new IllegalStateException("Injected unreadable section");
        }, 16);
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            var first = executor.submit(() -> rejects(tracker));
            if (!entered.await(3, TimeUnit.SECONDS)) throw new AssertionError("Loader did not start");
            var second = executor.submit(() -> rejects(tracker));
            release.countDown();
            if (!first.get(3, TimeUnit.SECONDS) || !second.get(3, TimeUnit.SECONDS))
                throw new AssertionError("Load failure was not propagated");
            if (tracker.getLoadedCacheCount() != 0) throw new AssertionError("Failed loads leaked cache entries");
            if (!rejects(tracker)) throw new AssertionError("Failed load could not be retried");
        } finally {
            release.countDown();
            executor.shutdownNow();
        }
        System.out.println("Section failure checks passed: propagation, waiter termination, cache cleanup, retry.");
    }

    private static boolean rejects(ActiveSectionTracker tracker) {
        try { tracker.acquire(1, 2, 3, 4, false); return false; }
        catch (IllegalStateException e) { return e.getMessage().equals("Injected unreadable section"); }
    }
}
