package dev.medveed.safeshare.service;

import android.net.Uri;

import androidx.annotation.MainThread;
import androidx.annotation.Nullable;
import androidx.lifecycle.MutableLiveData;

public final class DownloadController {

    public enum Stage { IDLE, DOWNLOADING, DONE, FAILED }

    public static final class State {
        public final Stage stage;
        public final long bytesDone;
        public final long bytesTotal;
        @Nullable public final String recoveredFilename;
        @Nullable public final Uri output;
        @Nullable public final String error;
        public final long transferRowId;

        public State(
                Stage stage, long done, long total,
                @Nullable String recoveredFilename,
                @Nullable Uri output,
                @Nullable String error,
                long transferRowId
        ) {
            this.stage = stage;
            this.bytesDone = done;
            this.bytesTotal = total;
            this.recoveredFilename = recoveredFilename;
            this.output = output;
            this.error = error;
            this.transferRowId = transferRowId;
        }

        public static State idle() {
            return new State(Stage.IDLE, 0, 0, null, null, null, 0);
        }
    }

    private static final DownloadController INSTANCE = new DownloadController();
    private final MutableLiveData<State> state = new MutableLiveData<>(State.idle());

    private DownloadController() { /* singleton */ }

    public static DownloadController get() { return INSTANCE; }

    public MutableLiveData<State> state() { return state; }

    public void post(State s) {
        state.postValue(s);
    }

    @MainThread
    public void reset() {
        state.setValue(State.idle());
    }
}
