package lain.lib;

import static org.junit.Assert.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.Test;

public class SharedPoolTest
{

    @Test
    public void runsMoreThanTwoLookupsAtOnce() throws Exception
    {
        // A ThreadPoolExecutor with an unbounded queue never grows past its core size: with two core
        // threads every profile lookup and skin download waited for the two in front of it
        int tasks = 4;
        CountDownLatch started = new CountDownLatch(tasks);
        CountDownLatch release = new CountDownLatch(1);
        for (int i = 0; i < tasks; i++)
        {
            SharedPool.execute(() -> {
                started.countDown();
                try
                {
                    release.await(5, TimeUnit.SECONDS);
                }
                catch (InterruptedException e)
                {
                }
            });
        }
        boolean allRunning = started.await(2, TimeUnit.SECONDS);
        release.countDown();
        assertTrue("only " + (tasks - started.getCount()) + " of " + tasks + " ran at once", allRunning);
    }

}
