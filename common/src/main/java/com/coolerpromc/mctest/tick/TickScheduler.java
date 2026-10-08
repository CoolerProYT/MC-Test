package com.coolerpromc.mctest.tick;

import com.coolerpromc.mctest.Constants;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * Runs tasks once per client tick until they report completion. Render-thread only: add tasks from inside
 * {@link com.coolerpromc.mctest.rpc.ClientThread#call}.
 */
public final class TickScheduler {

    /** Returns true when finished; it is then removed. */
    @FunctionalInterface
    public interface TickTask {
        boolean tick() throws Exception;
    }

    private static final List<TickTask> TASKS = new ArrayList<>();
    private static long tickCount;

    private TickScheduler() {
    }

    public static void add(TickTask task) {
        TASKS.add(task);
    }

    /** True while any MCTest action is waiting on ticks (drives lockstep). */
    public static boolean hasTasks() {
        return !TASKS.isEmpty();
    }

    public static long tickCount() {
        return tickCount;
    }

    public static void tick() {
        tickCount++;
        // Copy so tasks may schedule further tasks without a ConcurrentModificationException.
        List<TickTask> snapshot = new ArrayList<>(TASKS);
        for (TickTask task : snapshot) {
            boolean done;
            try {
                done = task.tick();
            } catch (Throwable t) {
                Constants.LOG.error("MCTest tick task threw; dropping it", t);
                done = true;
            }
            if (done) {
                for (Iterator<TickTask> it = TASKS.iterator(); it.hasNext(); ) {
                    if (it.next() == task) {
                        it.remove();
                        break;
                    }
                }
            }
        }
    }
}
