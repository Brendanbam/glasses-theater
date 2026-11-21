package com.brendanbam.glassestheater;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Parcelable;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.datasource.DefaultDataSource;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.PlayerView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Glasses Cinema: plays a video on a "virtual screen" that can be shrunk inside the glasses,
 * with pure black (see-through) all around it.
 *
 * Opens any video another app hands over (pick "Glasses Theater" as the external player in Nuvio,
 * or open a file from Home). Accepts the common MX Player extras (title, position, headers, subs)
 * and returns position / duration when closing, so apps like Nuvio can save where you stopped.
 *
 * Controller: L1 / R1 or D-pad up / down = smaller / bigger screen, left / right = back / forward
 * 10 s, A = pause / play, B = close.
 */
public class CinemaActivity extends Activity {

    private ExoPlayer player;
    private PlayerView playerView;
    private Theater.ScreenFrame frame;
    private TextView badge;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private String resumeKey;
    private boolean ended;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Theater.immersive(this);
        Uri uri = getIntent().getData();
        if (uri == null) { finish(); return; }
        resumeKey = "pos:" + uri;

        frame = new Theater.ScreenFrame(this, Theater.screenSize(this));
        playerView = new PlayerView(this);
        playerView.setBackgroundColor(Color.BLACK);
        playerView.setShutterBackgroundColor(Color.BLACK);
        playerView.setKeepScreenOn(true);
        playerView.setShowNextButton(false);
        playerView.setShowPreviousButton(false);
        playerView.setControllerShowTimeoutMs(3000);
        frame.addView(playerView);

        badge = Theater.text(this, 18, Color.WHITE, true);
        badge.setGravity(Gravity.CENTER);
        badge.setTag(Theater.OVERLAY);
        badge.setVisibility(View.GONE);
        FrameLayout badgeHolder = new FrameLayout(this);
        badgeHolder.setTag(Theater.OVERLAY);
        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        lp.topMargin = Theater.dp(this, 28);
        badge.setPadding(Theater.dp(this, 16), Theater.dp(this, 8), Theater.dp(this, 16), Theater.dp(this, 8));
        badge.setBackground(Theater.round(0xE61A202C, 0, 0, this, 12));
        badgeHolder.addView(badge, lp);
        frame.addView(badgeHolder);
        setContentView(frame);

        // Headers (MX Player style: alternating key, value) for streams that need them
        Map<String, String> headers = new HashMap<>();
        String[] h = getIntent().getStringArrayExtra("headers");
        if (h != null) for (int i = 0; i + 1 < h.length; i += 2) headers.put(h[i], h[i + 1]);
        DefaultHttpDataSource.Factory http = new DefaultHttpDataSource.Factory()
                .setAllowCrossProtocolRedirects(true)
                .setUserAgent(headers.containsKey("User-Agent") ? headers.remove("User-Agent") : "GlassesTheater/1.0")
                .setDefaultRequestProperties(headers);
        DefaultDataSource.Factory data = new DefaultDataSource.Factory(this, http);

        player = new ExoPlayer.Builder(this).setMediaSourceFactory(new DefaultMediaSourceFactory(data)).build();
        player.setAudioAttributes(new AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true);
        playerView.setPlayer(player);

        MediaItem.Builder item = new MediaItem.Builder().setUri(uri);
        String type = getIntent().getType();
        String path = uri.toString().toLowerCase(Locale.US);
        if ((type != null && type.contains("mpegurl")) || path.contains(".m3u8")) item.setMimeType(MimeTypes.APPLICATION_M3U8);
        else if ((type != null && type.contains("dash")) || path.contains(".mpd")) item.setMimeType(MimeTypes.APPLICATION_MPD);

        // External subtitles (MX Player style "subs" / "subs.name")
        Parcelable[] subs = getIntent().getParcelableArrayExtra("subs");
        String[] subNames = getIntent().getStringArrayExtra("subs.name");
        if (subs != null) {
            List<MediaItem.SubtitleConfiguration> list = new ArrayList<>();
            for (int i = 0; i < subs.length; i++) {
                if (!(subs[i] instanceof Uri)) continue;
                Uri s = (Uri) subs[i];
                String sp = s.toString().toLowerCase(Locale.US);
                String mime = sp.contains(".vtt") ? MimeTypes.TEXT_VTT : sp.contains(".ass") || sp.contains(".ssa") ? MimeTypes.TEXT_SSA : MimeTypes.APPLICATION_SUBRIP;
                list.add(new MediaItem.SubtitleConfiguration.Builder(s).setMimeType(mime)
                        .setLabel(subNames != null && i < subNames.length ? subNames[i] : "Subtitles " + (i + 1)).build());
            }
            item.setSubtitleConfigurations(list);
        }

        player.setMediaItem(item.build());
        // Start where the calling app says, otherwise where you left off last time
        long start = getIntent().getIntExtra("position", -1);
        if (start <= 0) start = Theater.prefs(this).getLong(resumeKey, 0);
        if (start > 30_000) {
            player.seekTo(start);
            showBadge("Resumed at " + clock(start));
        }
        player.addListener(new Player.Listener() {
            @Override
            public void onPlaybackStateChanged(int state) {
                if (state == Player.STATE_ENDED) {
                    ended = true;
                    Theater.prefs(CinemaActivity.this).edit().remove(resumeKey).apply();
                    finish();
                }
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                showBadge("Can't play this video (" + error.getErrorCodeName().replace("ERROR_CODE_", "").toLowerCase(Locale.US) + ")");
            }
        });
        player.prepare();
        player.play();
    }

    /* ---------- Controller ---------- */

    @Override
    public boolean dispatchKeyEvent(KeyEvent e) {
        int k = e.getKeyCode();
        boolean down = e.getAction() == KeyEvent.ACTION_DOWN;
        if (Theater.isSizeDown(k) || Theater.isSizeUp(k)) {
            if (down) resize(Theater.isSizeUp(k) ? 5 : -5);
            return true;
        }
        if (Theater.isBack(k)) {
            if (!down && e.getRepeatCount() == 0) finish();
            return true;
        }
        // While the on-screen controls are hidden, the D-pad drives the screen directly
        if (!playerView.isControllerFullyVisible() && player != null) {
            if (k == KeyEvent.KEYCODE_DPAD_LEFT || k == KeyEvent.KEYCODE_DPAD_RIGHT) {
                if (down) {
                    long target = player.getCurrentPosition() + (k == KeyEvent.KEYCODE_DPAD_RIGHT ? 10_000 : -10_000);
                    player.seekTo(Math.max(0, target));
                    showBadge((k == KeyEvent.KEYCODE_DPAD_RIGHT ? "» " : "« ") + clock(Math.max(0, target)));
                }
                return true;
            }
            if (k == KeyEvent.KEYCODE_DPAD_UP || k == KeyEvent.KEYCODE_DPAD_DOWN) {
                if (down) resize(k == KeyEvent.KEYCODE_DPAD_UP ? 5 : -5);
                return true;
            }
            if (Theater.isConfirm(k) || k == KeyEvent.KEYCODE_BUTTON_Y) {
                if (down && e.getRepeatCount() == 0) {
                    if (Theater.isConfirm(k)) player.setPlayWhenReady(!player.getPlayWhenReady());
                    playerView.showController();
                }
                return true;
            }
        }
        return super.dispatchKeyEvent(e);
    }

    private void resize(int delta) {
        int pct = Theater.setScreenSize(this, Theater.screenSize(this) + delta);
        frame.setPct(pct);
        showBadge("Screen size " + pct + "%");
    }

    private final Runnable hideBadge = () -> badge.setVisibility(View.GONE);

    private void showBadge(String msg) {
        badge.setText(msg);
        badge.setVisibility(View.VISIBLE);
        handler.removeCallbacks(hideBadge);
        handler.postDelayed(hideBadge, 2000);
    }

    private static String clock(long ms) {
        long s = ms / 1000;
        return s >= 3600 ? String.format(Locale.US, "%d:%02d:%02d", s / 3600, (s / 60) % 60, s % 60)
                : String.format(Locale.US, "%d:%02d", s / 60, s % 60);
    }

    /* ---------- Lifecycle ---------- */

    @Override
    public void finish() {
        if (player != null) {
            long pos = player.getCurrentPosition(), dur = player.getDuration();
            if (!ended && pos > 30_000) Theater.prefs(this).edit().putLong(resumeKey, pos).apply();
            // MX Player style result so the calling app can remember your spot
            Intent result = new Intent("com.mxtech.intent.result.VIEW");
            result.putExtra("position", (int) Math.min(Integer.MAX_VALUE, pos));
            if (dur != C.TIME_UNSET) result.putExtra("duration", (int) Math.min(Integer.MAX_VALUE, dur));
            result.putExtra("end_by", ended ? "playback_completion" : "user");
            setResult(RESULT_OK, result);
        }
        super.finish();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) Theater.immersive(this);
    }

    @Override
    protected void onStop() {
        super.onStop();
        if (player != null) {
            if (player.getCurrentPosition() > 30_000) Theater.prefs(this).edit().putLong(resumeKey, player.getCurrentPosition()).apply();
            player.pause();
        }
    }

    @Override
    protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        if (player != null) { player.release(); player = null; }
        super.onDestroy();
    }
}
