package com.baktra.cas2audio.features.player.domain;

import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;

import java.util.Optional;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class CasPlaybackHandler {

    private final Optional<PowerManager> powerManager;

    private Optional<CasTask> casTask;
    private Optional<Future<Void>> taskFuture;

    public static final int STOP_REASON_STOP = 0;
    public static final int STOP_REASON_PAUSE = 1;
    public static final int STOP_REASON_TERMINATE = 2;

    private CasPlaybackObserver observer;
    private ExecutorService executorService;

    public CasPlaybackHandler(Optional<PowerManager> pm) {
        this.powerManager = pm;
        clearTask();
        this.executorService = Executors.newSingleThreadExecutor();
    }

    public void setObserver(final CasPlaybackObserver observer) {
        this.observer = observer;
    }

    public void removeObserver() {
        this.observer = null;
        clearTask();
    }

    private void clearTask() {
        casTask = Optional.empty();
        taskFuture = Optional.empty();
    }

    public void prepare(int[] instructions, boolean stereo, boolean square, int volume, int sampleRate, boolean invertPolarity,
                        int resumeIp, int leadingSilenceSeconds) throws Exception {

        try {
            casTask = Optional.of(new CasTask(
                    instructions,
                    this,
                    stereo,
                    square,
                    volume,
                    sampleRate,
                    invertPolarity,
                    resumeIp,
                    leadingSilenceSeconds,
                    powerManager
            ));
        } catch (Exception e) {
            clearTask();
            throw e;
        }
    }

    public void play() {
        if (casTask.isPresent()) {
            taskFuture = Optional.of(executorService.submit(casTask.get()));
        }
    }

    public void stop(int stopReason) {



        /*Not task, do nothing*/
        if (casTask.isEmpty() || taskFuture.isEmpty()) return;
        CasTask ct = casTask.get();

        /*Request cancellation*/
        ct.cancel(stopReason);
    }

    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    void processSuccess(CasTask task) {
        finish(task, target -> {
            if (task.isCancelled()) {
                target.onCancelledPlayback(task.getLastIp(), task.getStopReason());
            } else {
                target.onSuccessfulPlayback();
            }
        });
    }

    void processCancellation(CasTask task, int resumeIp, int stopReason) {
        finish(task, target -> target.onCancelledPlayback(resumeIp, stopReason));
    }

    void processFailure(CasTask task, Throwable error) {
        finish(task, target -> target.onFailedPlayback(error));
    }

    private void finish(CasTask task, java.util.function.Consumer<CasPlaybackObserver> callback) {
        mainHandler.post(() -> {
            if (casTask.orElse(null) != task) return;
            clearTask();
            if (observer != null) callback.accept(observer);
        });
    }

    void publishProgress(CasTask task, int progress, int resumeIp) {
        mainHandler.post(() -> {
            if (casTask.orElse(null) != task || observer == null) return;
            observer.onProgressUpdate(progress);
            if (resumeIp != -1) observer.onResumePointUpdate(resumeIp);
        });
    }
}
