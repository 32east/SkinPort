package lain.lib;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public final class SharedPool {

    // Core size is the real size: a ThreadPoolExecutor only grows past its core threads when the
    // queue refuses a task, and an unbounded queue never does. With two core threads every lookup
    // and every download in the game went through two threads, one request after another.
    private static final int THREADS = Math.max(4, Math.min(16, Runtime.getRuntime().availableProcessors() * 2));
    private static final ThreadPoolExecutor thePool = new ThreadPoolExecutor(
            THREADS,
            THREADS,
            60L,
            TimeUnit.SECONDS,
            new LinkedBlockingQueue<>(),
            SharedPool::newWorker);

    static {
        thePool.allowCoreThreadTimeOut(true);
    }

    private SharedPool() {
        throw new Error("NoInstance");
    }

    private static Thread newWorker(Runnable target) {
        Thread thread = new Thread(target, "SharedPoolWorker");
        if (!thread.isDaemon())
            thread.setDaemon(true);
        if (thread.getPriority() != Thread.NORM_PRIORITY)
            thread.setPriority(Thread.NORM_PRIORITY);
        return thread;
    }

    public static void execute(Runnable command) {
        thePool.execute(command);
    }

}
