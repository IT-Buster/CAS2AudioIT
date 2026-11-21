package com.baktra.cas2audio.features.player.presentation;

import static com.baktra.cas2audio.features.player.domain.CasPlaybackHandler.STOP_REASON_PAUSE;
import static com.baktra.cas2audio.features.player.domain.CasPlaybackHandler.STOP_REASON_STOP;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.drawable.AnimationDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.*;

import androidx.activity.result.ActivityResult;
import androidx.activity.result.ActivityResultCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.IntentCompat;
import androidx.lifecycle.Observer;
import androidx.lifecycle.ViewModelProvider;

import com.baktra.cas2audio.Cas2AudioApp;
import com.baktra.cas2audio.R;
import com.baktra.cas2audio.core.util.Utils;
import com.baktra.cas2audio.features.goldies.domain.TapeSystem;
import com.baktra.cas2audio.features.goldies.presentation.GoldiesActivity;
import com.baktra.cas2audio.features.goldies.presentation.GoldiesSystemAdapter;
import com.baktra.cas2audio.features.recent.presentation.RecentActivity;
import com.baktra.cas2audio.features.settings.SettingsActivity;
import com.baktra.cas2audio.features.settings.domain.UserSettings;
import com.baktra.cas2audio.features.player.domain.tapeimage.ResumePoint;
import com.baktra.cas2audio.features.player.domain.tapeimage.TapeImageCrate;
import com.baktra.cas2audio.features.player.domain.tapeimage.TapeImageOpener;

import java.io.File;
import java.util.ArrayList;
import java.util.Optional;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

public class MainActivity extends AppCompatActivity {

    private final ArrayList<View> playBackViewsDisabled;
    private final ArrayList<MenuItem> playBackMenuItemsDisabled;
    private final ArrayList<View> playBackViewsEnabled;
    private final ArrayList<MenuItem> playBackMenuItemsEnabled;

    private final String LN_SP;
    private MainViewModel viewModel;

    private ActivityResultLauncher<Intent> launchBrowse;
    private ActivityResultLauncher<Intent> launchSettings;
    private ActivityResultLauncher<Intent> launchRecents;

    private ActivityResultLauncher<Intent> launchGoldies;

    private AlertDialog alertDialog;
    private AlertDialog playlistDialog;
    private AlertDialog playlistEntryDialog;
    private ActivityResultLauncher<String[]> launchPlaylistFiles;
    private TextView playlistSummary;
    private ListView playlistRows;
    private Spinner playlistSystems;

    public MainActivity() {
        super();
        LN_SP = System.lineSeparator();
        playBackViewsDisabled = new ArrayList<>(8);
        playBackViewsEnabled = new ArrayList<>(8);
        playBackMenuItemsDisabled = new ArrayList<>(1);
        playBackMenuItemsEnabled = new ArrayList<>(1);
        alertDialog=null;
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        //System.out.println("MainActivity::onCreate()");
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Cas2AudioApp c2aApp = (Cas2AudioApp) getApplication();
        MainViewModelFactory factory = new MainViewModelFactory(c2aApp.getSettingsRepository(), c2aApp.getTapeImageOpener(), c2aApp.getCasPlaybackHandler());

        /*Widgets to be disabled during playback*/
        playBackViewsDisabled.add(getBrowseButton());
        playBackViewsDisabled.add(findViewById(R.id.btnPlay));
        playBackViewsDisabled.add(findViewById(R.id.btnRecent));
        playBackViewsDisabled.add(findViewById(R.id.lvChunks));

        /*Widgets to be enabled during playback*/
        playBackViewsEnabled.add(findViewById(R.id.btnStop));
        playBackViewsEnabled.add(findViewById(R.id.btnPause));

        viewModel = new ViewModelProvider(this, factory).get(MainViewModel.class);
        setupModelObservers();

        /*Initialize launchers*/
        initializeLaunchers();
        launchPlaylistFiles = registerForActivityResult(
                new ActivityResultContracts.OpenMultipleDocuments(), this::processPlaylistFiles);
        viewModel.getPlaylistLoading().observe(this, loading -> refreshPlaylist());
        if (savedInstanceState != null) {
            String systemCode = savedInstanceState.getString("playlist_system");
            for (TapeSystem system : TapeSystem.GOLDIES_SYSTEMS) {
                if (system.getPrefixCode().equals(systemCode)) {
                    viewModel.selectPlaylistSystem(system);
                    break;
                }
            }
            if (savedInstanceState.getBoolean("playlist_open")) onPlaylist(null);
        }
    }

    public boolean onCreateOptionsMenu(Menu menu) {

        MenuInflater inflater = getMenuInflater();
        inflater.inflate(R.menu.main_menu, menu);
        MenuItem mi1 = menu.findItem(R.id.miSettings);
        MenuItem mi2 = menu.findItem(R.id.miGoldies);
        MenuItem playlistItem = menu.findItem(R.id.miPlaylist);
        playBackMenuItemsDisabled.add(playlistItem);
        playlistItem.setEnabled(!viewModel.isPlaying());
        playBackMenuItemsDisabled.add(mi1);
        playBackMenuItemsDisabled.add(mi2);

        mi1.setEnabled(!(viewModel.isPlaying()));
        mi2.setEnabled(!(viewModel.isPlaying()));
        return true;
    }


    protected void onResume() {
        super.onResume();
    }

    protected void onStop() {
        super.onStop();

    }

    protected void onPause() {
        closeOptionsMenu();
        super.onPause();
    }

    protected void onSaveInstanceState(Bundle state) {
        super.onSaveInstanceState(state);
        state.putBoolean("playlist_open", playlistDialog != null && playlistDialog.isShowing());
        state.putString("playlist_system", viewModel.getSelectedPlaylistSystem().getPrefixCode());
    }

    @Override
    protected void onDestroy() {
        if (playlistEntryDialog != null) playlistEntryDialog.dismiss();
        if (playlistDialog != null) playlistDialog.dismiss();
        closeOptionsMenu();
        if (alertDialog!=null && alertDialog.isShowing()) {
            alertDialog.dismiss();
        }
        super.onDestroy();
    }


    private void handlePlayerControls(boolean isPlaying) {

        for (View v : playBackViewsDisabled) {
            v.setEnabled(!isPlaying);
        }
        for (View v : playBackViewsEnabled) {
            v.setEnabled(isPlaying);
        }

        for (MenuItem mi : playBackMenuItemsDisabled) {
            mi.setEnabled(!isPlaying);
        }
        for (MenuItem mi : playBackMenuItemsEnabled) {
            mi.setEnabled(isPlaying);
        }

        /*Cassette image animation*/
        ImageView iv = findViewById(R.id.ivCassette);

        if (isPlaying) {
            iv.setImageDrawable(getResources().getDrawable(R.drawable.tape_animation, null));
            AnimationDrawable ad = (AnimationDrawable) iv.getDrawable();
            ad.start();
        } else {
            iv.setImageDrawable(getResources().getDrawable(R.drawable.tape_animation, null));
            if (iv.getDrawable() instanceof AnimationDrawable) {
                AnimationDrawable ad = (AnimationDrawable) iv.getDrawable();
                ad.stop();
            }
            iv.setImageDrawable(getResources().getDrawable(R.drawable.tape_inactive, null));
        }
    }


    private Optional<Boolean> oldVisibility = Optional.empty();
    private Optional<Integer> oldProgress = Optional.empty();
    private Optional<Integer> oldPlayerResumeIp = Optional.empty();
    private Optional<Integer> oldUserResumeIp = Optional.empty();

    private Optional<MainViewModel.PlayerState> oldPlayerState = Optional.empty();
    private Optional<TapeImageCrate> oldCrate = Optional.empty();

    private void setupModelObservers() {


        viewModel.getUIState().observe(this, new Observer<MainViewModel.UIState>() {
            @Override
            public void onChanged(MainViewModel.UIState newState) {

                boolean isPlaying = (newState.getPlayerState() == MainViewModel.PlayerState.PLAYING);

                boolean playbackChanged=false;
                boolean crateChanged=false;


                /*Handle chunk list visibility*/

                if (oldVisibility.isEmpty() || oldVisibility.get()!=newState.isChunkListVisible()) {
                    ListView lv = (ListView) findViewById(R.id.lvChunks);
                    lv.setVisibility(newState.isChunkListVisible() ? View.VISIBLE : View.INVISIBLE);
                    oldVisibility=Optional.of(newState.isChunkListVisible());
                }

                /*Handle progress*/
                if (oldProgress.isEmpty() || oldProgress.get()!=newState.getProgressValue()) {
                    getProgressBar().setProgress(newState.getProgressValue());
                    oldProgress=Optional.of(newState.getProgressValue());
                }

                /*Handle playback state, playing/not playing*/
                if (oldPlayerState.isEmpty() || oldPlayerState.get()!=newState.getPlayerState()) {
                    handlePlayerControls(isPlaying);
                    oldPlayerState=Optional.of(newState.getPlayerState());
                    playbackChanged=true;
                }

                TapeImageCrate tape = newState.getCurrentTapeImageCrate();
                TextView tapeName = findViewById(R.id.tvTapeImageName);
                int queueSize = viewModel.getPlaylistPlaybackSize();
                String name = tape == null ? "" : tape.getFileName();
                if (tape != null && queueSize > 0) {
                    name = getString(R.string.playlist_now_playing,
                            viewModel.getPlaylistPlaybackPosition(), queueSize, name);
                }
                if (!android.text.TextUtils.equals(tapeName.getText(), name)) {
                    tapeName.setText(name);
                }

                /*Handle tape image opened, not opened*/
                if (oldCrate.isEmpty() || oldCrate.get()!=newState.getCurrentTapeImageCrate()) {
                    if (newState.getPlayerState() == MainViewModel.PlayerState.TAPE_READY || newState.getPlayerState() == MainViewModel.PlayerState.PLAYING) {
                        setChunkDisplay(newState.getCurrentTapeImageCrate().getConvCrate().getResumePoints());
                    } else {
                        setChunkDisplay(new ArrayList<>());
                    }
                    oldCrate=Optional.ofNullable(newState.getCurrentTapeImageCrate());
                    crateChanged=true;
                }

                /*Handle player resume point*/
                if (isPlaying || playbackChanged || crateChanged) {

                    int playerIp = newState.getPlayerSelectedResumePointIp();

                    if (oldPlayerResumeIp.isEmpty() || oldPlayerResumeIp.get()!=playerIp) {
                        updatePlayerResumePoint(playerIp);
                        oldPlayerResumeIp=Optional.of(playerIp);
                    }
                }

                /*Handle user resume point*/
                if (!isPlaying) {
                    int userIp = newState.getUserSelectedResumePointIp();
                    if (oldUserResumeIp.isEmpty() || oldUserResumeIp.get() != userIp) {
                        updateUserSelectedResumePoint(userIp);
                        oldUserResumeIp = Optional.of(userIp);
                    }
                }


                /*Handle alerts*/
                if (newState.getAlertType()!= MainViewModel.AlertType.NONE) {

                    if (newState.getAlertType() == MainViewModel.AlertType.PLAYBACK_ERROR) {
                        displaySimpleAlert(getString(R.string.msg_unable_to_process_tit), Utils.getExceptionMessage(newState.getAlertException()));
                    } else if (newState.getAlertType() == MainViewModel.AlertType.CAS_UNREADABLE) {
                        displaySimpleAlert(getString(R.string.msg_file_unable_open_tit), Utils.getExceptionMessage(newState.getAlertException()));
                    } else if (newState.getAlertType() == MainViewModel.AlertType.PLAYER_EMPTY) {
                        displaySimpleAlert(getString(R.string.msg_nothing_to_play_tit), getString(R.string.msg_nothing_to_play));
                    }
                }

            }
        });

    }


    public void onPlay(View v) {
        viewModel.startPlayback();
    }

    private void setChunkDisplay(ArrayList<ResumePoint> resumePoints) {

        ListView lv = (ListView) findViewById(R.id.lvChunks);
        ResumePointAdapter rpa = new ResumePointAdapter(this, lv, resumePoints, 0);
        lv.setAdapter(rpa);
        rpa.notifyDataSetChanged();

        if (lv.getOnItemClickListener() == null) {
            lv.setOnItemClickListener(new AdapterView.OnItemClickListener() {
                @Override
                public void onItemClick(AdapterView<?> adapterView, View view, int i, long l) {
                    onClickChunks(adapterView, view, i, l);
                }
            });
        }

    }

    public void onDisplayChunks(View v) {
        viewModel.flipChunkListVisibility();
    }

    public void onClickChunks(AdapterView<?> adapterView, View view, int i, long l) {
        ListView lv = (ListView) adapterView;
        ResumePointAdapter rpa = (ResumePointAdapter) lv.getAdapter();
        ResumePoint p = (ResumePoint) rpa.getItem(i);
        if (p != null) {
            viewModel.setUserResumePoint(p.getResumeIp());
        }
    }

    public void onStopPlaying(View v) {

        int stopReason;

        if (v == findViewById(R.id.btnPause)) {
            stopReason = STOP_REASON_PAUSE;
        } else {
            stopReason = STOP_REASON_STOP;
        }

        viewModel.stopPlayback(stopReason);


    }

    public void onSettings(MenuItem item) {
        doSettings();
    }

    public void doSettings() {
        Intent intent = new Intent(this, SettingsActivity.class);
        intent.setAction(Intent.ACTION_GET_CONTENT);
        intent.putExtra("user_settings", viewModel.getUserSettings());
        launchSettings.launch(intent);
    }

    public void onRecent(View v) {
        Intent intent = new Intent(this, RecentActivity.class);
        intent.setAction(Intent.ACTION_GET_CONTENT);
        intent.putExtra("recent_items", viewModel.getTapeImageRecents().createPersistenceString());
        launchRecents.launch(intent);
    }

    public void onGoldies(MenuItem i) {
        Intent intent = new Intent(this, GoldiesActivity.class);
        intent.setAction(Intent.ACTION_GET_CONTENT);
        launchGoldies.launch(intent);
    }

    /*Browse for a tape image*/
    public void onBrowseTapeImage(android.view.View view) {

        /*Ask for document selection*/
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");

        intent.addFlags(Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

        launchBrowse.launch(intent);

    }

    private void processBrowseResult(ActivityResult o) {
        if (o.getResultCode() != Activity.RESULT_OK || o.getData() == null || o.getData().getData() == null)
            return;
        openTapeImageUri(o.getData().getData());

    }

    private void processRecentsResult(ActivityResult o) {

        if (o.getResultCode() != Activity.RESULT_OK || o.getData() == null) return;

        String recentString = o.getData().getStringExtra("recents");
        if (recentString != null) {
            this.viewModel.setTapeImageRecentsString(recentString);
        }

        if (o.getData().getData() != null) {
            openTapeImageUri(o.getData().getData());
        }
    }

    private void openTapeImageUri(Uri uri) {
        String fileName = null;
        fileName = extractFileNameFromURI(uri);
        viewModel.openTapeImageFromURI(uri, fileName);
    }

    private void processSettingsResult(ActivityResult o) {

        /*Handle the settings activity*/
        if (o.getResultCode() != Activity.RESULT_OK || o.getData() == null) return;
        UserSettings us = IntentCompat.getSerializableExtra(o.getData(), "user_settings", UserSettings.class);
        this.viewModel.updateUserSettings(us);
    }

    private void processGoldiesResult(ActivityResult o) {

        /*Handle the Goldies Activity*/
        if (o.getResultCode() != Activity.RESULT_OK || o.getData() == null) return;

        Intent intentData = o.getData();
        viewModel.openTapeImageFromArray(intentData.getIntArrayExtra("tape_image_data"), intentData.getStringExtra("tape_image_name"));

    }

    private void initializeLaunchers() {

        launchBrowse = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                new ActivityResultCallback<ActivityResult>() {
                    @Override
                    public void onActivityResult(ActivityResult o) {
                        processBrowseResult(o);
                    }
                }
        );


        launchRecents = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                new ActivityResultCallback<ActivityResult>() {
                    @Override
                    public void onActivityResult(ActivityResult o) {
                        processRecentsResult(o);
                    }
                }
        );
        launchSettings = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                new ActivityResultCallback<ActivityResult>() {
                    @Override
                    public void onActivityResult(ActivityResult o) {
                        processSettingsResult(o);
                    }
                }
        );

        launchGoldies = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                new ActivityResultCallback<ActivityResult>() {
                    @Override
                    public void onActivityResult(ActivityResult o) {
                        processGoldiesResult(o);
                    }
                }
        );


    }


    private ProgressBar getProgressBar() {
        return findViewById(R.id.pbProgress);
    }

    private ImageButton getBrowseButton() {
        return findViewById(R.id.btnBrowse);
    }


    void updateUserSelectedResumePoint(int resumeIp) {
        if (resumeIp == -1) return;
        ListView lv = (ListView) findViewById(R.id.lvChunks);
        ResumePointAdapter rpa = (ResumePointAdapter) lv.getAdapter();
        if (rpa==null) return;
        rpa.setUserResumePoint(resumeIp);
    }

    void updatePlayerResumePoint(int resumeIp) {
        if (resumeIp == -1) return;
        ListView lv = (ListView) findViewById(R.id.lvChunks);
        ResumePointAdapter rpa = (ResumePointAdapter) lv.getAdapter();
        if (rpa==null) return;
        rpa.setPlayerResumePoint(resumeIp);
    }

    private String extractFileNameFromURI(Uri uri) {
        String result = null;

        /*Let us have content URI*/
        if (uri.getScheme() != null && uri.getScheme().equals("content")) {
            Cursor cursor = getContentResolver().query(uri, null, null, null, null);
            try {
                if (cursor != null && cursor.moveToFirst() && cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME) >= 0) {
                    result = cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME));
                }

            }
            catch(IllegalArgumentException e) {
                result=null;
            }

            finally {
                if (cursor!=null){
                    cursor.close();
                }
            }
        }
        /*If not content URI, consider it a file*/
        if (result == null) {
            File f = new File(uri.toString());
            result = f.getName();
        }
        return result;

    }

    private void displaySimpleAlert(String title, String message) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(title);
        builder.setMessage(message);
        builder.setPositiveButton(R.string.btn_ok, new DialogInterface.OnClickListener() {
            public void onClick(DialogInterface dialog, int id) {
                dialog.dismiss();
                viewModel.dismissAlert();
            }
        });

        alertDialog = builder.create();
        alertDialog.show();
    }

    public void onPlaylist(MenuItem item) {
        if (viewModel.isPlaying() || playlistDialog != null) return;

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        int padding = Math.round(24 * getResources().getDisplayMetrics().density);
        content.setPadding(padding, 0, padding, 0);

        LinearLayout systemRow = new LinearLayout(this);
        systemRow.setGravity(android.view.Gravity.CENTER_VERTICAL);
        TextView systemLabel = new TextView(this);
        systemLabel.setText(R.string.playlist_system);
        systemRow.addView(systemLabel);
        playlistSystems = new Spinner(this);
        playlistSystems.setId(View.generateViewId());
        systemLabel.setLabelFor(playlistSystems.getId());
        GoldiesSystemAdapter systems = new GoldiesSystemAdapter(this,
                R.layout.goldies_system_item, TapeSystem.GOLDIES_SYSTEMS);
        playlistSystems.setAdapter(systems);
        playlistSystems.setSelection(systems.getPosition(viewModel.getSelectedPlaylistSystem()));
        systemRow.addView(playlistSystems, new LinearLayout.LayoutParams(
                0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        content.addView(systemRow);

        LinearLayout fields = new LinearLayout(this);
        content.addView(fields);
        EditText limit = addPlaylistNumberField(fields, R.string.playlist_limit,
                viewModel.getPlaylistLimitText(), viewModel::setPlaylistLimitText);
        EditText gap = addPlaylistNumberField(fields, R.string.playlist_gap,
                viewModel.getPlaylistGapText(), viewModel::setPlaylistGapText);

        playlistSummary = new TextView(this);
        playlistSummary.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        content.addView(playlistSummary);

        playlistRows = new ListView(this);
        playlistRows.setOnItemClickListener((parent, view, position, id) -> editPlaylistEntry(position));
        int rowsHeightDp = Math.min(240, getResources().getConfiguration().screenHeightDp / 4);
        int rowsHeight = Math.round(rowsHeightDp * getResources().getDisplayMetrics().density);
        content.addView(playlistRows, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, rowsHeight));

        content.setFocusableInTouchMode(true);
        content.requestFocus();
        playlistDialog = new AlertDialog.Builder(this)
                .setTitle(R.string.playlist_title)
                .setView(content)
                .setNeutralButton(R.string.playlist_add, null)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.playlist_play, (dialog, which) -> viewModel.startPlaylist())
                .create();
        playlistDialog.setOnDismissListener(dialog -> {
            playlistDialog = null;
            playlistSummary = null;
            playlistRows = null;
            playlistSystems = null;
        });
        playlistDialog.show();
        playlistSystems.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View row, int position, long id) {
                if (parent != playlistSystems) return;
                TapeSystem system = (TapeSystem) parent.getItemAtPosition(position);
                if (system.equals(viewModel.getSelectedPlaylistSystem())) return;
                viewModel.selectPlaylistSystem(system);
                limit.setText(viewModel.getPlaylistLimitText());
                gap.setText(viewModel.getPlaylistGapText());
                refreshPlaylist();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });
        playlistDialog.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(view -> {
            playlistDialog.dismiss();
            launchPlaylistFiles.launch(new String[]{"*/*"});
        });
        refreshPlaylist();
    }

    private EditText addPlaylistNumberField(LinearLayout fields, int labelId, String value,
                                        Consumer<String> onChange) {
        LinearLayout field = new LinearLayout(this);
        field.setOrientation(LinearLayout.VERTICAL);
        fields.addView(field, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        TextView label = new TextView(this);
        label.setText(labelId);
        label.setMinLines(2);
        field.addView(label);

        EditText input = new EditText(this);
        input.setId(View.generateViewId());
        label.setLabelFor(input.getId());
        input.setSingleLine();
        input.setInputType(InputType.TYPE_CLASS_NUMBER);
        input.setText(value);
        input.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence text, int start, int before, int count) {
                onChange.accept(text.toString());
                updatePlaylistSummary();
            }
            @Override public void afterTextChanged(Editable text) {}
        });
        field.addView(input);
        return input;
    }

    private void processPlaylistFiles(List<Uri> uris) {
        if (!uris.isEmpty()) {
            List<String> names = new ArrayList<>();
            for (Uri uri : uris) {
                try {
                    names.add(extractFileNameFromURI(uri));
                } catch (SecurityException e) {
                    names.add(uri.getLastPathSegment() == null ? uri.toString() : uri.getLastPathSegment());
                }
            }
            viewModel.addPlaylistFiles(uris, names);
        }
        onPlaylist(null);
    }

    private void refreshPlaylist() {
        if (playlistDialog == null) return;
        boolean loading = Boolean.TRUE.equals(viewModel.getPlaylistLoading().getValue());
        List<MainViewModel.PlaylistEntry> entries = viewModel.getPlaylist();
        boolean do48kHz = viewModel.getUserSettings().isDo48kHz();
        int rate = do48kHz ? 48000 : 44100;
        List<String> rows = new ArrayList<>();
        for (int i = 0; i < entries.size(); i++) {
            MainViewModel.PlaylistEntry entry = entries.get(i);
            rows.add(getString(R.string.playlist_row, i + 1, entry.tape.getFileName(),
                    formatPlaylistTime(roundUpSeconds(entry.frames(do48kHz), rate))));
        }
        playlistRows.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, rows));
        playlistRows.setEnabled(!loading);
        playlistSystems.setEnabled(!loading);
        playlistDialog.getButton(AlertDialog.BUTTON_NEUTRAL).setEnabled(!loading);
        updatePlaylistSummary();
    }

    private void updatePlaylistSummary() {
        if (playlistSummary == null) return;
        int gapSeconds = viewModel.getPlaylistGapSeconds();
        boolean loading = Boolean.TRUE.equals(viewModel.getPlaylistLoading().getValue());
        playlistDialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setEnabled(!loading && !viewModel.getPlaylist().isEmpty() && gapSeconds >= 0);
        if (gapSeconds < 0) {
            playlistSummary.setText(R.string.playlist_gap_invalid);
            return;
        }
        long seconds = viewModel.getPlaylistDurationSeconds();
        StringBuilder summary = new StringBuilder(getString(
                R.string.playlist_total, formatPlaylistTime(seconds)));
        try {
            int limit = Integer.parseInt(viewModel.getPlaylistLimitText());
            if (limit > 0) {
                long remaining = limit * 60L - seconds;
                summary.append('\n').append(getString(
                        remaining >= 0 ? R.string.playlist_remaining : R.string.playlist_over,
                        formatPlaylistTime(Math.abs(remaining))));
            }
        } catch (NumberFormatException ignored) {
            // The limit can be cleared while editing.
        }
        if (loading) {
            summary.append('\n').append(getString(R.string.playlist_loading));
        } else if (!viewModel.getUnreadablePlaylistFiles().isEmpty()) {
            summary.append('\n').append(getString(R.string.playlist_unreadable,
                    String.join(", ", viewModel.getUnreadablePlaylistFiles())));
        } else if (viewModel.getPlaylist().isEmpty()) {
            summary.append('\n').append(getString(R.string.playlist_add_for_system,
                    viewModel.getSelectedPlaylistSystem().getName()));
        }
        playlistSummary.setText(summary);
    }

    private static long roundUpSeconds(long frames, int sampleRate) {
        return (frames + sampleRate - 1) / sampleRate;
    }

    private static String formatPlaylistTime(long seconds) {
        return String.format(Locale.getDefault(), "%d:%02d", seconds / 60, seconds % 60);
    }

    private void editPlaylistEntry(int position) {
        List<MainViewModel.PlaylistEntry> entries = viewModel.getPlaylist();
        playlistEntryDialog = new AlertDialog.Builder(this)
                .setTitle(entries.get(position).tape.getFileName())
                .setNegativeButton(R.string.playlist_remove, (dialog, which) -> {
                    viewModel.removePlaylistEntry(position);
                    refreshPlaylist();
                })
                .setNeutralButton(R.string.playlist_up, (dialog, which) -> {
                    viewModel.movePlaylistEntry(position, position - 1);
                    refreshPlaylist();
                })
                .setPositiveButton(R.string.playlist_down, (dialog, which) -> {
                    viewModel.movePlaylistEntry(position, position + 1);
                    refreshPlaylist();
                })
                .create();
        playlistEntryDialog.show();
        playlistEntryDialog.getButton(AlertDialog.BUTTON_NEUTRAL).setEnabled(position > 0);
        playlistEntryDialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(position + 1 < entries.size());
    }

    public void onAbout(MenuItem mi) {
        Toast.makeText(this, getString(R.string.toast_about), Toast.LENGTH_LONG).show();
    }

    public void onExit(MenuItem mi) {
        finish();
    }


}
