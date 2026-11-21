package com.baktra.cas2audio.features.player.presentation;

import static com.baktra.cas2audio.features.player.domain.CasPlaybackHandler.STOP_REASON_PAUSE;
import static com.baktra.cas2audio.features.player.domain.CasPlaybackHandler.STOP_REASON_TERMINATE;

import android.net.Uri;

import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;

import com.baktra.cas2audio.features.goldies.domain.GoldiesRepository;
import com.baktra.cas2audio.features.goldies.domain.TapeSystem;
import com.baktra.cas2audio.features.player.domain.CasPlaybackHandler;
import com.baktra.cas2audio.features.player.domain.CasPlaybackObserver;
import com.baktra.cas2audio.features.player.domain.signal.SignalGenerator;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.IntFunction;
import com.baktra.cas2audio.features.recent.domain.TapeImageRecents;
import com.baktra.cas2audio.features.settings.repository.SettingsRepository;
import com.baktra.cas2audio.features.settings.domain.UserSettings;
import com.baktra.cas2audio.features.player.domain.tapeimage.ResumePoint;
import com.baktra.cas2audio.features.player.domain.tapeimage.TapeImageCrate;
import com.baktra.cas2audio.features.player.domain.tapeimage.TapeImageOpener;

public class MainViewModel extends ViewModel implements CasPlaybackObserver {


    /*Repositories and services*/
    private SettingsRepository settingsRepository;
    private TapeImageOpener tapeImageOpener;
    private final CasPlaybackHandler casPlaybackHandler;

    static final class PlaylistEntry {
        final TapeImageCrate tape;
        final long frames44100;
        final long frames48000;

        PlaylistEntry(TapeImageCrate tape) throws Exception {
            this.tape = tape;
            int[] instructions = tape.getConvCrate().getInstructions();
            frames44100 = SignalGenerator.countFrames(instructions, 44100);
            frames48000 = SignalGenerator.countFrames(instructions, 48000);
        }

        long frames(boolean do48kHz) {
            return do48kHz ? frames48000 : frames44100;
        }
    }

    private static final class Playlist {
        final List<PlaylistEntry> entries = new ArrayList<>();
        final List<String> unreadableFiles = new ArrayList<>();
        final List<String> filesWithoutPwm = new ArrayList<>();
        String limitMinutes = "30";
        String gapSeconds = "0";
    }

    private final Map<TapeSystem, Playlist> playlists = new HashMap<>();
    private TapeSystem selectedPlaylistSystem = TapeSystem.STANDARD;
    private final MutableLiveData<Boolean> playlistLoading = new MutableLiveData<>(false);
    private final ExecutorService playlistLoader = Executors.newSingleThreadExecutor();
    private final android.os.Handler mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private List<PlaylistEntry> playbackQueue = Collections.emptyList();
    private int playlistPosition;
    private int playbackGapSeconds;
    private boolean gapPending;
    private boolean cleared;
    private Playlist selectedPlaylist() {
        return playlists.computeIfAbsent(selectedPlaylistSystem, system -> new Playlist());
    }

    TapeSystem getSelectedPlaylistSystem() {
        return selectedPlaylistSystem;
    }

    void selectPlaylistSystem(TapeSystem system) {
        selectedPlaylistSystem = system;
    }

    String getPlaylistLimitText() {
        return selectedPlaylist().limitMinutes;
    }

    void setPlaylistLimitText(String minutes) {
        selectedPlaylist().limitMinutes = minutes;
    }

    String getPlaylistGapText() {
        return selectedPlaylist().gapSeconds;
    }

    void setPlaylistGapText(String seconds) {
        selectedPlaylist().gapSeconds = seconds;
    }

    int getPlaylistGapSeconds() {
        String gap = getPlaylistGapText();
        if (gap.isEmpty()) return 0;
        try {
            return Math.max(-1, Integer.parseInt(gap));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    long getPlaylistDurationSeconds() {
        boolean do48kHz = getUserSettings().isDo48kHz();
        int sampleRate = do48kHz ? 48000 : 44100;
        List<PlaylistEntry> playlist = selectedPlaylist().entries;
        long frames = 0;
        for (PlaylistEntry entry : playlist) {
            frames += entry.frames(do48kHz);
        }
        long signalSeconds = (frames + sampleRate - 1) / sampleRate;
        return signalSeconds + Math.max(0, playlist.size() - 1L) * Math.max(0, getPlaylistGapSeconds());
    }

    List<PlaylistEntry> getPlaylist() {
        return Collections.unmodifiableList(selectedPlaylist().entries);
    }

    List<String> getUnreadablePlaylistFiles() {
        return Collections.unmodifiableList(selectedPlaylist().unreadableFiles);
    }

    List<String> getPlaylistFilesWithoutPwm() {
        return Collections.unmodifiableList(selectedPlaylist().filesWithoutPwm);
    }

    LiveData<Boolean> getPlaylistLoading() {
        return playlistLoading;
    }

    void addPlaylistFiles(List<Uri> uris, List<String> names) {
        addPlaylistEntries(names, i -> tapeImageOpener.readPlaylistEntry(uris.get(i), names.get(i)));
    }

    void addGoldiesPlaylistFiles(GoldiesRepository repository, List<String> names) {
        TapeSystem system = selectedPlaylistSystem;
        addPlaylistEntries(names, i -> {
            String name = names.get(i);
            int[] data = repository.getTapeImageData(system, name);
            if (data == null) throw new IllegalStateException("Missing Goldies image: " + name);
            return tapeImageOpener.readTapeImage(null, data, false, name);
        });
    }

    private void addPlaylistEntries(List<String> names, IntFunction<TapeImageOpener.ReadResult> readEntry) {
        if (isPlaying() || Boolean.TRUE.equals(playlistLoading.getValue())) return;
        Playlist target = selectedPlaylist();
        target.unreadableFiles.clear();
        target.filesWithoutPwm.clear();
        boolean requirePwm = !selectedPlaylistSystem.isStandard();
        playlistLoading.setValue(true);
        playlistLoader.execute(() -> {
            List<PlaylistEntry> entries = new ArrayList<>();
            List<String> failures = new ArrayList<>();
            List<String> filesWithoutPwm = new ArrayList<>();
            for (int i = 0; i < names.size() && !Thread.currentThread().isInterrupted(); i++) {
                String name = names.get(i);
                try {
                    TapeImageOpener.ReadResult result = readEntry.apply(i);
                    if (!result.isSuccess()) throw result.getException();
                    TapeImageCrate tape = result.getTapeImageCrate();
                    if (requirePwm && !tape.getConvCrate().hasPwmData()) {
                        filesWithoutPwm.add(name);
                    } else {
                        entries.add(new PlaylistEntry(tape));
                    }
                } catch (Exception e) {
                    failures.add(name);
                }
            }
            mainHandler.post(() -> {
                if (cleared) return;
                target.entries.addAll(entries);
                target.unreadableFiles.addAll(failures);
                target.filesWithoutPwm.addAll(filesWithoutPwm);
                playlistLoading.setValue(false);
            });
        });
    }

    void movePlaylistEntry(int from, int to) {
        Collections.swap(selectedPlaylist().entries, from, to);
    }

    void removePlaylistEntry(int position) {
        selectedPlaylist().entries.remove(position);
    }

    void startPlaylist() {
        List<PlaylistEntry> playlist = selectedPlaylist().entries;
        int gapSeconds = getPlaylistGapSeconds();
        if (gapSeconds < 0 || playlist.isEmpty() || isPlaying()
                || Boolean.TRUE.equals(playlistLoading.getValue())) return;
        playbackGapSeconds = gapSeconds;
        playbackQueue = new ArrayList<>(playlist);
        playlistPosition = 0;
        playPlaylistEntry();
    }

    int getPlaylistPlaybackSize() {
        return playbackQueue.size();
    }

    int getPlaylistPlaybackPosition() {
        return playlistPosition + 1;
    }

    private void selectPlaylistEntry(PlayerState state) {
        gapPending = playlistPosition > 0 && playbackGapSeconds > 0;
        UIState old = uiState.getValue();
        uiState.setValue(new UIState(state, 0, 0, 0,
                playbackQueue.get(playlistPosition).tape, old.chunkListVisible, AlertType.NONE, null));
    }

    private void playPlaylistEntry() {
        selectPlaylistEntry(PlayerState.PLAYING);
        prepareAndPlay();
    }

    enum PlayerState {
        EMPTY,
        TAPE_READY,
        PLAYING,
    }

    enum AlertType {
        NONE,
        CAS_UNREADABLE,
        PLAYER_EMPTY,
        PLAYBACK_ERROR

    }

    protected class UIState {

        private final PlayerState playerState;
        private final int progressValue;
        private final int playerSelectedResumePointIp;
        private final int userSelectedResumePointIp;

        private final TapeImageCrate currentTapeImageCrate;
        private final boolean chunkListVisible;

        private final AlertType alertType;
        private final Throwable alertException;


        public UIState(PlayerState playerState, int progressValue, int playerIp, int userIp, TapeImageCrate crate, boolean chunkListVisible, AlertType alertType, Throwable alertException) {
            this.playerState = playerState;
            this.progressValue = progressValue;
            this.playerSelectedResumePointIp = playerIp;
            this.userSelectedResumePointIp = userIp;
            this.currentTapeImageCrate = crate;
            this.chunkListVisible = chunkListVisible;
            this.alertType = alertType;
            this.alertException = alertException;
        }

        public PlayerState getPlayerState() {
            return this.playerState;
        }

        public int getProgressValue() {
            return this.progressValue;
        }

        public int getPlayerSelectedResumePointIp() {
            return this.playerSelectedResumePointIp;
        }

        public int getUserSelectedResumePointIp() {
            return this.userSelectedResumePointIp;
        }

        public boolean isChunkListVisible() {
            return this.chunkListVisible;
        }

        public TapeImageCrate getCurrentTapeImageCrate() {
            return this.currentTapeImageCrate;
        }

        public AlertType getAlertType() {
            return this.alertType;
        }

        public Throwable getAlertException() {
            return this.alertException;
        }


    }

    /*User interface state*/
    private MutableLiveData<UIState> uiState;


    public MainViewModel(SettingsRepository settingsRepo, TapeImageOpener tiOpener, CasPlaybackHandler cpHandler) {
        settingsRepository = settingsRepo;
        tapeImageOpener = tiOpener;
        casPlaybackHandler = cpHandler;
        uiState = new MutableLiveData<>();
        uiState.setValue(new UIState(PlayerState.EMPTY, 0, 0, 0, null, false, AlertType.NONE, null));
        casPlaybackHandler.setObserver(this);
        loadPreferences();

    }

    void startPlayback() {
        if (isPlaying()) return;

        if (uiState.getValue().playerState == PlayerState.EMPTY) {

            /*Update UI state to ready, with error*/
            UIState oldState = uiState.getValue();
            UIState newState = new UIState
                    (oldState.playerState,
                            oldState.progressValue,
                            oldState.playerSelectedResumePointIp,
                            oldState.userSelectedResumePointIp,
                            oldState.currentTapeImageCrate,
                            oldState.chunkListVisible,
                            AlertType.PLAYER_EMPTY,
                            null
                    );
            uiState.setValue(newState);
            return;
        }

        prepareAndPlay();
    }

    private void prepareAndPlay() {
        try {
            UserSettings us = getUserSettings();
            getCasPlaybackHandler().prepare(
                    uiState.getValue().currentTapeImageCrate.getConvCrate().getInstructions(),
                    !us.isDoMono(),
                    us.isDoSquareWave(),
                    us.getAmplitude(),
                    us.isDo48kHz() ? 48000 : 44100,
                    us.isDoInvertPolarity(),
                    uiState.getValue().userSelectedResumePointIp,
                    gapPending ? playbackGapSeconds : 0
            );
        } catch (Exception e) {

            /*Update UI state to ready, with error*/
            UIState oldState = uiState.getValue();
            UIState newState = new UIState
                    (PlayerState.TAPE_READY,
                            oldState.progressValue,
                            oldState.playerSelectedResumePointIp,
                            oldState.userSelectedResumePointIp,
                            oldState.currentTapeImageCrate,
                            oldState.chunkListVisible,
                            AlertType.PLAYBACK_ERROR,
                            e
                    );
            uiState.setValue(newState);
            return;
        }

        /*Update UI state to playing*/
        UIState oldState = uiState.getValue();
        UIState newState = new UIState
                (PlayerState.PLAYING,
                        oldState.progressValue,
                        oldState.playerSelectedResumePointIp,
                        oldState.userSelectedResumePointIp,
                        oldState.currentTapeImageCrate,
                        oldState.chunkListVisible,
                        AlertType.NONE,
                        null
                );
        uiState.setValue(newState);

        getCasPlaybackHandler().play();


    }

    public void stopPlayback(int reason) {
        getCasPlaybackHandler().stop(reason);
    }

    TapeImageOpener.ReadResult readTapeImage(Uri uri, int[] tapeImageBytes, String fileName) {
        UserSettings us = getSettingsRepository().getUserSettings();
        return getTapeImageOpener().readTapeImage(uri, tapeImageBytes, us.isDo48kHz(), fileName);
    }

    public void onProgressUpdate(int value) {
        UIState oldState = uiState.getValue();
        UIState newState = new UIState
                (oldState.playerState,
                        value,
                        oldState.playerSelectedResumePointIp,
                        oldState.userSelectedResumePointIp,
                        oldState.currentTapeImageCrate,
                        oldState.chunkListVisible,
                        oldState.alertType,
                        oldState.alertException
                );
        uiState.setValue(newState);
    }

    public void onResumePointUpdate(int resumeIp) {
        gapPending = false;

        /*Check if possible*/
        int newResumePoint = calculateResumePoint(resumeIp);

        /*The player changes the resume IP*/
        UIState oldState = uiState.getValue();
        UIState newState = new UIState
                (oldState.playerState,
                        oldState.progressValue,
                        newResumePoint,
                        oldState.userSelectedResumePointIp,
                        oldState.currentTapeImageCrate,
                        oldState.chunkListVisible,
                        oldState.alertType,
                        oldState.alertException
                );
        uiState.setValue(newState);

    }

    public void setUserResumePoint(int resumeIp) {


        int newResumePoint = calculateResumePoint(resumeIp);

        /*The user changes the resume IP*/
        UIState oldState = uiState.getValue();
        UIState newState = new UIState
                (oldState.playerState,
                        oldState.progressValue,
                        oldState.playerSelectedResumePointIp,
                        newResumePoint,
                        oldState.currentTapeImageCrate,
                        oldState.chunkListVisible,
                        oldState.alertType,
                        oldState.alertException
                );
        uiState.setValue(newState);

    }


    private void loadPreferences() {

        /*Restore user settings*/
        getSettingsRepository().loadSettings();
        /*Update the UI*/
        boolean chunkListVisibility = (getSettingsRepository().getUiPersistence().isChunkListVisible());

        /*The user changes the resume IP*/
        UIState oldState = uiState.getValue();
        UIState newState = new UIState
                (oldState.playerState,
                        oldState.progressValue,
                        oldState.playerSelectedResumePointIp,
                        oldState.userSelectedResumePointIp,
                        oldState.currentTapeImageCrate,
                        chunkListVisibility,
                        oldState.alertType,
                        oldState.alertException
                );
        uiState.setValue(newState);
    }

    private void savePreferences() {

        /*Get state of the ui*/
        getSettingsRepository().getUiPersistence().updateChunkListVisible(uiState.getValue().chunkListVisible);

        /*Save settings*/
        getSettingsRepository().saveSettings();


    }

    private int calculateResumePoint(int resumeIp) {
        int newResumePoint = 0;
        if (uiState.getValue().playerState == PlayerState.EMPTY) {
            newResumePoint = 0;
        } else {
            ResumePoint rp = ResumePoint.getClosestResumePoint(resumeIp, uiState.getValue().currentTapeImageCrate.getConvCrate().getResumePoints());
            if (rp != null) {
                newResumePoint = rp.getResumeIp();
            } else {
                newResumePoint = 0;
            }
        }

        return newResumePoint;
    }


    public void onSuccessfulPlayback() {
        if (!playbackQueue.isEmpty()) {
            playlistPosition++;
            if (playlistPosition < playbackQueue.size()) {
                playPlaylistEntry();
            } else {
                playlistPosition = 0;
                selectPlaylistEntry(PlayerState.TAPE_READY);
            }
            return;
        }

        UIState oldState = uiState.getValue();
        UIState newState = new UIState
                (PlayerState.TAPE_READY,
                        0,
                        0,
                        0,
                        oldState.currentTapeImageCrate,
                        oldState.chunkListVisible,
                        oldState.alertType,
                        oldState.alertException
                );
        uiState.setValue(newState);
    }

    public void onCancelledPlayback(int resIp, int stopReason) {
        if (!playbackQueue.isEmpty() && stopReason != STOP_REASON_PAUSE) {
            playlistPosition = 0;
            selectPlaylistEntry(PlayerState.TAPE_READY);
            return;
        }

        int newResumePoint = 0;
        if (stopReason == STOP_REASON_PAUSE) {
            newResumePoint = calculateResumePoint(resIp);
        } else {
            newResumePoint = 0;
        }
        UIState oldState = uiState.getValue();
        UIState newState = new UIState
                (PlayerState.TAPE_READY,
                        0,
                        newResumePoint,
                        newResumePoint,
                        oldState.currentTapeImageCrate,
                        oldState.chunkListVisible,
                        oldState.alertType,
                        oldState.alertException
                );
        uiState.setValue(newState);

    }

    public void onFailedPlayback(Throwable e) {
        UIState oldState = uiState.getValue();
        UIState newState = new UIState
                (PlayerState.TAPE_READY,
                        0,
                        0,
                        0,
                        oldState.currentTapeImageCrate,
                        oldState.chunkListVisible,
                        AlertType.PLAYBACK_ERROR,
                        e
                );
        uiState.setValue(newState);
    }

    UserSettings getUserSettings() {
        return getSettingsRepository().getUserSettings();
    }

    void updateUserSettings(UserSettings us) {
        getSettingsRepository().setUserSettings(us);
    }

    TapeImageRecents getTapeImageRecents() {
        return getSettingsRepository().getTapeImageRecents();
    }

    void setTapeImageRecentsString(String s) {
        getSettingsRepository().getTapeImageRecents().parsePersistenceString(s);
    }

    public void flipChunkListVisibility() {

        boolean newVisibility = !uiState.getValue().chunkListVisible;
        UIState oldState = uiState.getValue();
        UIState newState = new UIState
                (oldState.playerState,
                        oldState.progressValue,
                        oldState.playerSelectedResumePointIp,
                        oldState.userSelectedResumePointIp,
                        oldState.currentTapeImageCrate,
                        newVisibility,
                        oldState.alertType,
                        oldState.alertException

                );
        uiState.setValue(newState);

    }


    @Override
    public void onCleared() {
        super.onCleared();
        cleared = true;
        playlistLoader.shutdownNow();
        savePreferences();
        getCasPlaybackHandler().stop(STOP_REASON_TERMINATE);
        getCasPlaybackHandler().removeObserver();
    }

    private void setEmptyState() {
        UIState oldState = uiState.getValue();
        UIState newState = new UIState
                (PlayerState.EMPTY,
                        oldState.progressValue,
                        0,
                        0,
                        null,
                        oldState.chunkListVisible,
                        oldState.alertType,
                        oldState.alertException
                );
        uiState.setValue(newState);
    }

    private void setEmptyStateWithError(AlertType alertType, Throwable alertException) {
        UIState oldState = uiState.getValue();
        UIState newState = new UIState
                (PlayerState.EMPTY,
                        oldState.progressValue,
                        0,
                        0,
                        null,
                        oldState.chunkListVisible,
                        alertType,
                        alertException
                );
        uiState.setValue(newState);
    }

    public void openTapeImageFromURI(Uri tapeImageUri, String fileName) {
        if (isPlaying()) return;
        playbackQueue = Collections.emptyList();
        gapPending = false;

        /*Open nothing*/
        if (tapeImageUri == null) {
            setEmptyState();
            return;
        }

        /*Try to open the tape image*/
        TapeImageOpener.ReadResult result = readTapeImage(tapeImageUri, null, fileName);
        processOpenedImage(result, true, tapeImageUri, fileName);

    }

    public void openTapeImageFromArray(int[] tapeImageBytes, String fileName) {
        if (isPlaying()) return;
        playbackQueue = Collections.emptyList();
        gapPending = false;

        if (tapeImageBytes == null) {
            setEmptyState();
            return;
        }

        TapeImageOpener.ReadResult result = readTapeImage(null, tapeImageBytes, fileName);
        processOpenedImage(result, false, null, fileName);
    }

    private void processOpenedImage(TapeImageOpener.ReadResult result, boolean addToRecents, Uri tapeImageUri, String fileName) {
        /*If successful, add to recents*/
        if (result.isSuccess()) {
            if (addToRecents && tapeImageUri != null) {
                getTapeImageRecents().addRecentItem(tapeImageUri, fileName);
            }
            UIState oldState = uiState.getValue();
            UIState newState = new UIState
                    (PlayerState.TAPE_READY,
                            oldState.progressValue,
                            0,
                            0,
                            result.getTapeImageCrate(),
                            oldState.chunkListVisible,
                            AlertType.NONE,
                            null
                    );
            uiState.setValue(newState);
        }
        /*Otherwise, set up for just "empty" tape image*/
        else {
            setEmptyStateWithError(AlertType.CAS_UNREADABLE, result.getException());
        }
    }

    public void dismissAlert() {
        UIState oldState = uiState.getValue();
        UIState newState = new UIState
                (oldState.playerState,
                        oldState.progressValue,
                        oldState.playerSelectedResumePointIp,
                        oldState.userSelectedResumePointIp,
                        oldState.currentTapeImageCrate,
                        oldState.chunkListVisible,
                        AlertType.NONE,
                        null
                );
        uiState.setValue(newState);
    }

    public boolean isPlaying() {
        return uiState.getValue().playerState == PlayerState.PLAYING;
    }

    public LiveData<UIState> getUIState() {
        return this.uiState;
    }


    private SettingsRepository getSettingsRepository() {
        return settingsRepository;
    }

    private TapeImageOpener getTapeImageOpener() {
        return tapeImageOpener;
    }

    private CasPlaybackHandler getCasPlaybackHandler() {
        return this.casPlaybackHandler;
    }


}
