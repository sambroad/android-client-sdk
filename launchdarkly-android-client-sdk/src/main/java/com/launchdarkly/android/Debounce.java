package com.launchdarkly.android;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Created by jkodumal on 9/18/17.
 */
public class Debounce {
    private volatile Callable<Void> pending;
    private volatile Callable<Void> inFlight = null;
    private final ExecutorService service = Executors.newSingleThreadExecutor();

    public synchronized void call(Callable<Void> task) {
        pending = task;

        schedulePending();
    }

    private synchronized void schedulePending() {
        if (pending == null) {
            return;
        }

        if (inFlight == null) {
            inFlight = pending;
            service.submit(new Callable<Void>() {
                @Override
                public Void call() throws Exception {
                    try {
                        inFlight.call();
                    } finally {
                        inFlight = null;
                        schedulePending();
                    }
                    return null;
                }
            });
            pending = null;
        }
    }

}