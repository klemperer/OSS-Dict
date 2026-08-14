package itkach.aard2.audio;

import android.content.Context;
import android.media.AudioFormat;
import android.media.AudioTrack;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.media3.common.AudioAttributes;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Plays dictionary audio referenced by a SlobServer URL.
 *
 * <ul>
 *   <li>Ogg-Speex ({@code .spx}) files are downloaded, software-decoded to PCM
 *       by {@link OggSpeexDecoder}, and rendered via {@link AudioTrack}.</li>
 *   <li>All other audio formats are handed directly to {@link ExoPlayer}
 *       (MP3, OGG Vorbis, WAV, etc.).</li>
 * </ul>
 *
 * <p>Only one audio clip plays at a time; starting a new clip automatically
 * stops the previous one.</p>
 */
public class DictAudioPlayer {

    private static final String TAG = DictAudioPlayer.class.getSimpleName();

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Context appContext;

    private Future<?>            currentTask;
    private volatile ExoPlayer   exoPlayer;
    private volatile HandlerThread playbackThread;
    private volatile AudioTrack  audioTrack;

    /**
     * @param context any context — the application context is retained
     */
    public DictAudioPlayer(@NonNull Context context) {
        this.appContext = context.getApplicationContext();
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Starts playing the audio at {@code url} (a localhost SlobServer URL).
     * Any previously playing audio is stopped first.
     */
    public synchronized void play(@NonNull String url) {
        cancelCurrent();
        currentTask = executor.submit(() -> doPlay(url));
    }

    /** Stops any currently playing audio. */
    public synchronized void stop() {
        cancelCurrent();
    }

    /**
     * Releases all resources. Must be called when this player is no longer
     * needed (e.g., from {@code View.onDetachedFromWindow} or
     * {@code WebView.destroy}).
     */
    public void release() {
        stop();
        executor.shutdown();
    }

    // ── Internal ─────────────────────────────────────────────────────────────

    private synchronized void cancelCurrent() {
        if (currentTask != null) {
            currentTask.cancel(true);
            currentTask = null;
        }
        releaseExo();
        releaseAt();
    }

    private void doPlay(@NonNull String url) {
        try {
            if (url.toLowerCase().endsWith(".spx")) {
                playSpx(url);
            } else {
                playUrl(url);
            }
        } catch (InterruptedException ignored) {
            // Cancelled by stop() / play()
        } catch (Exception e) {
            Log.e(TAG, "Audio playback failed for " + url, e);
        }
    }

    // ── SPX (Ogg-Speex) playback via AudioTrack ───────────────────────────────

    private void playSpx(@NonNull String url) throws Exception {
        byte[] spxBytes = fetchBytes(url);
        if (Thread.interrupted()) return;

        OggSpeexDecoder oggDecoder = new OggSpeexDecoder(spxBytes);
        short[] pcm    = oggDecoder.decode();
        int sampleRate = oggDecoder.getSampleRate();
        int channels   = oggDecoder.getChannels();

        if (Thread.interrupted()) return;
        if (pcm.length == 0) return;

        int channelMask = channels > 1
                ? AudioFormat.CHANNEL_OUT_STEREO
                : AudioFormat.CHANNEL_OUT_MONO;

        android.media.AudioAttributes spxAudioAttrs =
                new android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                        .setContentType(android.media.AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build();

        AudioTrack track = new AudioTrack.Builder()
                .setAudioAttributes(spxAudioAttrs)
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(channelMask)
                        .build())
                .setBufferSizeInBytes(pcm.length * 2)
                .setTransferMode(AudioTrack.MODE_STATIC)
                .build();

        synchronized (this) {
            if (Thread.interrupted()) { track.release(); return; }
            audioTrack = track;
        }

        track.write(pcm, 0, pcm.length);
        track.play();

        // Poll until playback finishes or we are cancelled
        while (track.getPlaybackHeadPosition() < pcm.length) {
            if (Thread.interrupted()) { track.stop(); break; }
            try { Thread.sleep(30); } catch (InterruptedException e) { track.stop(); break; }
        }

        track.release();
        synchronized (this) { if (audioTrack == track) audioTrack = null; }
    }

    // ── Standard format playback via ExoPlayer (Media3) ───────────────────────

    /**
     * Plays audio via {@link ExoPlayer}.
     *
     * <p>A dedicated {@link HandlerThread} is used so that the ExoPlayer looper
     * lives on a short-lived thread that is quit immediately after playback.
     * The completion callback fires reliably on all devices, eliminating the
     * truncation issue seen with {@code MediaPlayer}.</p>
     */
    private void playUrl(@NonNull String url) throws Exception {
        HandlerThread thread = new HandlerThread("DictExoPlayer");
        thread.start();
        Handler handler = new Handler(thread.getLooper());

        final ExoPlayer[] playerRef = new ExoPlayer[1];
        final CountDownLatch done = new CountDownLatch(1);

        // Build and configure the player on its looper thread
        handler.post(() -> {
            ExoPlayer player = new ExoPlayer.Builder(appContext)
                    .setLooper(thread.getLooper())
                    .build();
            playerRef[0] = player;

            player.setAudioAttributes(
                    new AudioAttributes.Builder()
                            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MUSIC)
                            .build(),
                    /* handleAudioFocus= */ false);

            player.addListener(new Player.Listener() {
                @Override
                public void onPlaybackStateChanged(int state) {
                    if (state == Player.STATE_ENDED) {
                        Log.d(TAG, "ExoPlayer completed: " + url);
                        done.countDown();
                    }
                }

                @Override
                public void onPlayerError(@NonNull PlaybackException error) {
                    Log.e(TAG, "ExoPlayer error url=" + url, error);
                    done.countDown();
                }
            });

            player.setMediaItem(MediaItem.fromUri(url));
            player.prepare();
            player.play();
            Log.d(TAG, "ExoPlayer started: " + url);
        });

        synchronized (this) {
            exoPlayer = playerRef[0];
            playbackThread = thread;
        }

        try {
            // Wait for playback to finish, be interrupted, or time out.
            long deadline = System.currentTimeMillis() + 60_000; // 60 s safety net
            while (true) {
                if (Thread.interrupted()) {
                    final ExoPlayer p = playerRef[0];
                    if (p != null) {
                        try { p.stop(); } catch (IllegalStateException ignored) {}
                    }
                    return;
                }
                long remaining = deadline - System.currentTimeMillis();
                if (remaining <= 0) {
                    Log.w(TAG, "Timed out waiting for completion: " + url);
                    break;
                }
                if (done.await(Math.min(100, remaining), TimeUnit.MILLISECONDS)) break;
            }
        } finally {
            final ExoPlayer p = playerRef[0];
            if (p != null) {
                handler.post(() -> {
                    p.release();
                    thread.quitSafely();
                });
            } else {
                thread.quitSafely();
            }
            synchronized (this) {
                if (exoPlayer == p) exoPlayer = null;
                if (playbackThread == thread) playbackThread = null;
            }
        }
    }

    // ── Resource cleanup ──────────────────────────────────────────────────────

    private void releaseExo() {
        ExoPlayer player = exoPlayer;
        HandlerThread thread = playbackThread;
        if (player != null) {
            try {
                player.stop();
            } catch (IllegalStateException ignored) {}
            player.release();
            exoPlayer = null;
        }
        if (thread != null) {
            thread.quitSafely();
            playbackThread = null;
        }
    }

    private void releaseAt() {
        AudioTrack at = audioTrack;
        if (at != null) {
            try { at.stop(); } catch (IllegalStateException ignored) {}
            at.release();
            audioTrack = null;
        }
    }

    // ── HTTP helpers ──────────────────────────────────────────────────────────

    @NonNull
    private static byte[] fetchBytes(@NonNull String url) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        try {
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(10_000);
            conn.setReadTimeout(10_000);
            try (InputStream is = conn.getInputStream()) {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) != -1) baos.write(buf, 0, n);
                return baos.toByteArray();
            }
        } finally {
            conn.disconnect();
        }
    }
}
