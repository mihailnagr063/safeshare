package dev.medveed.safeshare.service;

import androidx.annotation.MainThread;
import androidx.annotation.Nullable;
import androidx.lifecycle.MutableLiveData;

public final class UploadController {

    public enum Stage { IDLE, UPLOADING, DONE, FAILED }

    public static final class State {
        public final Stage stage;
        public final long bytesDone;
        public final long bytesTotal;
        @Nullable public final String fileId;
        @Nullable public final String transferCode;
        @Nullable public final String compactUri;
        public final long expiresAt;
        @Nullable public final String ownerTokenHex;
        @Nullable public final String error;
        public final long transferRowId;

        public State(
                Stage stage, long done, long total,
                @Nullable String fileId, @Nullable String transferCode,
                @Nullable String compactUri,
                long expiresAt, @Nullable String ownerTokenHex,
                @Nullable String error, long transferRowId
        ) {
            this.stage = stage;
            this.bytesDone = done;
            this.bytesTotal = total;
            this.fileId = fileId;
            this.transferCode = transferCode;
            this.compactUri = compactUri;
            this.expiresAt = expiresAt;
            this.ownerTokenHex = ownerTokenHex;
            this.error = error;
            this.transferRowId = transferRowId;
        }

        public static State idle() {
            return new State(Stage.IDLE, 0, 0, null, null, null, 0, null, null, 0);
        }
    }

    private static final UploadController INSTANCE = new UploadController();
    private final MutableLiveData<State> state = new MutableLiveData<>(State.idle());

    private UploadController() { /* singleton */ }

    public static UploadController get() { return INSTANCE; }

    public MutableLiveData<State> state() { return state; }

    public void post(State s) {
        state.postValue(s);
    }

    @MainThread
    public void reset() {
        state.setValue(State.idle());
    }
}
