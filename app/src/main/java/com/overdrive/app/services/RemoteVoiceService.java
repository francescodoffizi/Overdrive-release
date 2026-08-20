package com.overdrive.app.services;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.core.content.ContextCompat;

import com.overdrive.app.R;
import com.overdrive.app.communication.RemoteCommunicationPolicy;
import com.overdrive.app.communication.RemoteCommunicationSettings;
import com.overdrive.app.communication.RemoteVoicePcmConverter;
import com.overdrive.app.communication.RemoteVoiceProtocol;
import com.overdrive.app.logging.DaemonLogger;
import com.overdrive.app.overlay.OverlayPermissionChecker;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * App-process receiver for browser push-to-talk PCM.
 *
 * The daemon cannot own an audible AudioTrack on this firmware, so it launches
 * this exported service and streams PCM over an authenticated, loopback-only
 * ephemeral socket. The service also owns the compact non-focusable overlay.
 */
public final class RemoteVoiceService extends Service {

    private static final String TAG = "RemoteVoiceService";
    private static final DaemonLogger logger = DaemonLogger.getInstance(TAG);
    private static final String CHANNEL_ID = "remote_voice";
    private static final int NOTIFICATION_ID = 9120;
    private static final int CONNECT_TIMEOUT_MS = 2_500;
    private static final int FALLBACK_OUTPUT_SAMPLE_RATE_HZ = 48_000;
    private static final int OUTPUT_ROUTE_PRIME_MS = 80;
    private static final float ROAD_SENSE_DUCK_FACTOR = 0.12f;

    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicLong generation = new AtomicLong();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Object controlWriteLock = new Object();

    private WindowManager windowManager;
    private AudioManager audioManager;
    private Socket bridgeSocket;
    private DataOutputStream controlOutput;
    private AudioTrack audioTrack;
    private AudioManager.OnAudioFocusChangeListener focusListener;
    private View overlayView;
    private TextView elapsedView;
    private ImageButton muteButton;
    private Runnable elapsedTicker;
    private volatile boolean muted;
    private volatile boolean overlaySafe;
    private volatile boolean roadSenseDucked;
    private volatile int outputLevel =
            RemoteCommunicationPolicy.DEFAULT_OUTPUT_LEVEL;
    private volatile long startedAtMs;
    private int audioOutputSampleRate = FALLBACK_OUTPUT_SAMPLE_RATE_HZ;
    private int audioPrimeBytes;
    private byte[] outputPcmBuffer = new byte[0];
    private boolean firstPcmLogged;
    private boolean audioPlaybackStarted;

    private final PlaybackDuckCoordinator.Target roadSenseDuckTarget = ducked -> {
        roadSenseDucked = ducked;
        mainHandler.post(() -> {
            applyTrackVolume();
            updateOverlayVisibility();
        });
    };

    public static void startSpeakerTest(Context context) {
        Intent intent = new Intent(context, RemoteVoiceService.class)
                .putExtra("action", "test");
        ContextCompat.startForegroundService(context, intent);
    }

    public static void stopNow(Context context) {
        try { context.stopService(new Intent(context, RemoteVoiceService.class)); }
        catch (Throwable ignored) {}
    }

    @Override public void onCreate() {
        super.onCreate();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        audioManager = (AudioManager) getSystemService(AUDIO_SERVICE);
        createNotificationChannel();
        startForegroundCompat();
        MediaPlaybackService.attachRoadSenseDuckTarget(roadSenseDuckTarget);
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        String action = stringExtra(intent, "action", "");
        if ("test".equals(action)) {
            startTestTone();
            return START_NOT_STICKY;
        }
        if (!"start".equals(action)) {
            stopSelf();
            return START_NOT_STICKY;
        }

        int port = parseInt(stringExtra(intent, "port", ""), -1);
        String token = stringExtra(intent, "token", "");
        int level = RemoteCommunicationPolicy.clampOutputLevel(
                parseInt(stringExtra(intent, "outputLevel", ""),
                        RemoteCommunicationPolicy.DEFAULT_OUTPUT_LEVEL));
        if (port <= 0 || port > 65_535 || token.isEmpty()) {
            stopSelf();
            return START_NOT_STICKY;
        }
        startRemoteSession(port, token, level);
        return START_NOT_STICKY;
    }

    private void startRemoteSession(int port, String token, int level) {
        long session = generation.incrementAndGet();
        stopSessionResources();
        running.set(true);
        muted = false;
        overlaySafe = false;
        outputLevel = level;
        startedAtMs = System.currentTimeMillis();
        firstPcmLogged = false;

        Thread worker = new Thread(
                () -> runRemoteSession(session, port, token),
                "RemoteVoiceReceiver");
        worker.setDaemon(true);
        worker.start();
    }

    private void runRemoteSession(long session, int port, String token) {
        String failure = null;
        try {
            Socket socket = new Socket();
            socket.connect(new InetSocketAddress("127.0.0.1", port),
                    CONNECT_TIMEOUT_MS);
            socket.setTcpNoDelay(true);
            socket.setKeepAlive(true);
            bridgeSocket = socket;
            controlOutput = new DataOutputStream(socket.getOutputStream());
            DataInputStream input = new DataInputStream(socket.getInputStream());

            RemoteCommunicationSettings.Snapshot settings =
                    RemoteCommunicationSettings.load();
            if (settings.emergencyDisabled) {
                failure = "Remote communication is emergency-disabled in the car";
            } else if (!settings.voiceEnabled) {
                failure = "Remote voice is disabled in the car settings";
            } else if (!OverlayPermissionChecker.isGranted(this)) {
                failure = "Display-over-other-apps permission is not granted in the car";
            }

            if (failure == null) {
                try {
                    initStreamingAudio();
                    startAudioPlayback();
                    if (!showOverlayBlocking(session)) {
                        failure = "The remote voice overlay could not be displayed";
                    }
                } catch (Throwable setupError) {
                    logger.warn(
                            "Remote voice setup failed: " + setupError.getMessage());
                    failure = "The car audio output could not start";
                }
            }

            sendHandshake(token, failure == null ? "READY" : "ERROR",
                    failure == null ? "" : failure);
            if (failure != null) return;

            while (running.get() && generation.get() == session) {
                RemoteVoiceProtocol.Packet packet = RemoteVoiceProtocol.read(input);
                if (packet == null || packet.type == RemoteVoiceProtocol.TYPE_END) break;
                if (packet.type == RemoteVoiceProtocol.TYPE_OVERLAY_SAFE) {
                    if (packet.payload.length != 1) {
                        throw new IllegalStateException("Malformed overlay safety frame");
                    }
                    overlaySafe = packet.payload[0] != 0;
                    mainHandler.post(this::updateOverlayVisibility);
                    continue;
                }
                if (packet.type != RemoteVoiceProtocol.TYPE_PCM) continue;
                if (packet.payload.length == 0 || (packet.payload.length & 1) != 0) {
                    throw new IllegalStateException("Malformed PCM frame");
                }
                if (!muted) {
                    writeRemotePcm(packet.payload);
                }
            }
        } catch (Throwable error) {
            if (running.get() && generation.get() == session) {
                logger.warn(
                        "Remote voice session ended: " + error.getMessage());
            }
        } finally {
            finishSession(session);
        }
    }

    private void initStreamingAudio() {
        int outputRate = preferredOutputSampleRate();
        int minBuffer = AudioTrack.getMinBufferSize(
                outputRate,
                AudioFormat.CHANNEL_OUT_STEREO,
                AudioFormat.ENCODING_PCM_16BIT);
        if (minBuffer <= 0) {
            throw new IllegalStateException("AudioTrack buffer unavailable");
        }
        int bufferBytes = Math.max(minBuffer * 4, 32 * 1024);
        int bytesPerSecond = outputRate * 2 * 2;
        audioPrimeBytes = Math.min(
                bufferBytes,
                Math.max(minBuffer,
                        bytesPerSecond * OUTPUT_ROUTE_PRIME_MS / 1_000));
        audioPrimeBytes -= audioPrimeBytes % 4;
        AudioAttributes attributes = new AudioAttributes.Builder()
                .setLegacyStreamType(AudioManager.STREAM_MUSIC)
                .build();
        AudioFormat format = new AudioFormat.Builder()
                .setSampleRate(outputRate)
                .setChannelMask(AudioFormat.CHANNEL_OUT_STEREO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                .build();
        audioTrack = new AudioTrack(
                attributes,
                format,
                bufferBytes,
                AudioTrack.MODE_STREAM,
                AudioManager.AUDIO_SESSION_ID_GENERATE);
        if (audioTrack.getState() != AudioTrack.STATE_INITIALIZED) {
            throw new IllegalStateException("AudioTrack did not initialize");
        }
        audioOutputSampleRate = audioTrack.getSampleRate();
        if (audioOutputSampleRate <= 0) audioOutputSampleRate = outputRate;
        requestAudioFocus();
    }

    private static int preferredOutputSampleRate() {
        try {
            int nativeRate =
                    AudioTrack.getNativeOutputSampleRate(AudioManager.STREAM_MUSIC);
            if (nativeRate >= 8_000 && nativeRate <= 192_000) return nativeRate;
        } catch (Throwable ignored) {}
        return FALLBACK_OUTPUT_SAMPLE_RATE_HZ;
    }

    private void startAudioPlayback() {
        AudioTrack track = audioTrack;
        if (track == null || track.getState() != AudioTrack.STATE_INITIALIZED) {
            throw new IllegalStateException("AudioTrack is unavailable");
        }
        applyTrackVolume();
        int primedBytes = primeAudioTrack(track);
        track.play();
        if (track.getPlayState() != AudioTrack.PLAYSTATE_PLAYING) {
            throw new IllegalStateException("AudioTrack did not enter playback state");
        }
        audioPlaybackStarted = true;
        if (audioManager != null) {
            try {
                int current =
                        audioManager.getStreamVolume(AudioManager.STREAM_MUSIC);
                int maximum =
                        audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                logger.info("Remote voice playback ready (mediaVolume="
                        + current + "/" + maximum + ", trackGain="
                        + outputLevel + "%, format=" + audioOutputSampleRate
                        + "Hz stereo, primeBytes=" + primedBytes + ")");
            } catch (Throwable ignored) {
                logger.info("Remote voice playback ready (trackGain="
                        + outputLevel + "%, format=" + audioOutputSampleRate
                        + "Hz stereo, primeBytes=" + primedBytes + ")");
            }
        }
    }

    private int primeAudioTrack(AudioTrack track) {
        if (audioPrimeBytes <= 0) return 0;
        byte[] silence = new byte[audioPrimeBytes];
        int offset = 0;
        while (offset < silence.length) {
            int requested = silence.length - offset;
            int written = track.write(
                    silence, offset, requested, AudioTrack.WRITE_NON_BLOCKING);
            if (written < 0) {
                throw new IllegalStateException(
                        "AudioTrack prime failed: " + written);
            }
            if (written == 0) break;
            offset += written;
            if (written < requested) break;
        }
        if (offset == 0) {
            throw new IllegalStateException("AudioTrack could not be primed");
        }
        return offset;
    }

    private void writeRemotePcm(byte[] monoPcm) {
        int required = RemoteVoicePcmConverter.requiredStereoBytes(
                monoPcm.length,
                RemoteCommunicationPolicy.PCM_SAMPLE_RATE_HZ,
                audioOutputSampleRate);
        if (outputPcmBuffer.length < required) {
            outputPcmBuffer = new byte[required];
        }
        int converted = RemoteVoicePcmConverter.mono16LeToStereo16Le(
                monoPcm,
                monoPcm.length,
                RemoteCommunicationPolicy.PCM_SAMPLE_RATE_HZ,
                audioOutputSampleRate,
                outputPcmBuffer);
        writeFully(audioTrack, outputPcmBuffer, converted);
        if (!firstPcmLogged) {
            firstPcmLogged = true;
            int peak = RemoteVoicePcmConverter.peakAbsoluteSample(
                    monoPcm, monoPcm.length);
            logger.info("First remote PCM written (inputBytes="
                    + monoPcm.length + ", outputBytes=" + converted
                    + ", inputPeak=" + peak + ")");
            if (peak < 64) {
                logger.warn("Remote microphone PCM is effectively silent");
            }
        }
    }

    private static void writeFully(
            AudioTrack track, byte[] pcm, int byteCount) {
        int offset = 0;
        int zeroWrites = 0;
        while (offset < byteCount) {
            int written = track.write(
                    pcm, offset, byteCount - offset, AudioTrack.WRITE_BLOCKING);
            if (written < 0) {
                throw new IllegalStateException(
                        "AudioTrack write failed: " + written);
            }
            if (written == 0) {
                if (track.getPlayState() != AudioTrack.PLAYSTATE_PLAYING
                        || ++zeroWrites >= 3) {
                    throw new IllegalStateException(
                            "AudioTrack stopped accepting PCM");
                }
                Thread.yield();
                continue;
            }
            offset += written;
            zeroWrites = 0;
        }
    }

    private boolean showOverlayBlocking(long session) {
        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean shown = new AtomicBoolean(false);
        mainHandler.post(() -> {
            if (generation.get() == session && running.get()) {
                shown.set(showOverlay());
            }
            latch.countDown();
        });
        try {
            return latch.await(2, TimeUnit.SECONDS) && shown.get();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private boolean showOverlay() {
        removeOverlay();
        Context context = themedContext();

        LinearLayout root = new LinearLayout(context);
        root.setOrientation(LinearLayout.HORIZONTAL);
        root.setGravity(Gravity.CENTER_VERTICAL);
        int horizontal = dp(context, 14);
        root.setPadding(horizontal, dp(context, 9), dp(context, 8), dp(context, 9));
        GradientDrawable background = new GradientDrawable();
        background.setColor(0xF2202422);
        background.setCornerRadius(dp(context, 8));
        background.setStroke(dp(context, 1), 0x665DDBB6);
        root.setBackground(background);
        root.setVisibility(View.GONE);

        ImageView speaker = new ImageView(context);
        speaker.setImageResource(R.drawable.ic_volume_on);
        speaker.setColorFilter(Color.WHITE);
        root.addView(speaker, new LinearLayout.LayoutParams(
                dp(context, 22), dp(context, 22)));

        TextView label = new TextView(context);
        label.setText(R.string.remote_voice_overlay_title);
        label.setTextColor(Color.WHITE);
        label.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        label.setTypeface(label.getTypeface(), android.graphics.Typeface.BOLD);
        LinearLayout.LayoutParams labelParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        labelParams.leftMargin = dp(context, 10);
        root.addView(label, labelParams);

        elapsedView = new TextView(context);
        elapsedView.setText("00:00");
        elapsedView.setTextColor(0xFFC6CEC9);
        elapsedView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13);
        elapsedView.setGravity(Gravity.END);
        elapsedView.setMinWidth(dp(context, 54));
        LinearLayout.LayoutParams elapsedParams = new LinearLayout.LayoutParams(
                dp(context, 54), ViewGroup.LayoutParams.WRAP_CONTENT);
        elapsedParams.leftMargin = dp(context, 18);
        root.addView(elapsedView, elapsedParams);

        muteButton = new ImageButton(context);
        muteButton.setImageResource(R.drawable.ic_volume_on);
        muteButton.setColorFilter(Color.WHITE);
        muteButton.setContentDescription(
                getString(R.string.remote_voice_mute));
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            muteButton.setTooltipText(getString(R.string.remote_voice_mute));
        }
        muteButton.setBackgroundColor(Color.TRANSPARENT);
        muteButton.setPadding(dp(context, 8), dp(context, 8),
                dp(context, 8), dp(context, 8));
        muteButton.setOnClickListener(v -> toggleMute());
        LinearLayout.LayoutParams muteParams = new LinearLayout.LayoutParams(
                dp(context, 40), dp(context, 40));
        muteParams.leftMargin = dp(context, 8);
        root.addView(muteButton, muteParams);

        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_SYSTEM_ALERT,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        params.y = dp(context, 24);

        try {
            windowManager.addView(root, params);
            overlayView = root;
            startElapsedTicker();
            return true;
        } catch (Throwable error) {
            logger.warn(
                    "Could not add remote voice overlay: " + error.getMessage());
            overlayView = null;
            return false;
        }
    }

    private void toggleMute() {
        muted = !muted;
        if (muteButton != null) {
            muteButton.setImageResource(
                    muted ? R.drawable.ic_volume_off : R.drawable.ic_volume_on);
            muteButton.setContentDescription(getString(
                    muted ? R.string.remote_voice_unmute : R.string.remote_voice_mute));
        }
        applyTrackVolume();
        sendControl("MUTE:" + (muted ? "1" : "0"));
    }

    private void sendHandshake(String token, String status, String reason) {
        synchronized (controlWriteLock) {
            try {
                if (controlOutput == null) return;
                controlOutput.writeUTF(token);
                controlOutput.writeUTF(status);
                controlOutput.writeUTF(reason == null ? "" : reason);
                controlOutput.flush();
            } catch (Throwable error) {
                logger.warn(
                        "Could not send receiver handshake: " + error.getMessage());
            }
        }
    }

    private void sendControl(String value) {
        synchronized (controlWriteLock) {
            try {
                if (controlOutput != null) {
                    controlOutput.writeUTF(value);
                    controlOutput.flush();
                }
            } catch (Throwable ignored) {}
        }
    }

    private void updateOverlayVisibility() {
        if (overlayView == null) return;
        boolean show = running.get() && overlaySafe && !roadSenseDucked;
        overlayView.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    private void startElapsedTicker() {
        stopElapsedTicker();
        elapsedTicker = new Runnable() {
            @Override public void run() {
                if (!running.get() || elapsedView == null) return;
                long seconds = Math.max(
                        0L, (System.currentTimeMillis() - startedAtMs) / 1000L);
                elapsedView.setText(String.format(
                        Locale.US, "%02d:%02d", seconds / 60L, seconds % 60L));
                mainHandler.postDelayed(this, 1_000L);
            }
        };
        mainHandler.post(elapsedTicker);
    }

    private void stopElapsedTicker() {
        if (elapsedTicker != null) {
            mainHandler.removeCallbacks(elapsedTicker);
            elapsedTicker = null;
        }
    }

    private void applyTrackVolume() {
        AudioTrack track = audioTrack;
        if (track == null) return;
        float volume = muted ? 0f : outputLevel / 100f;
        if (roadSenseDucked) volume *= ROAD_SENSE_DUCK_FACTOR;
        try { track.setVolume(volume); } catch (Throwable ignored) {}
    }

    private void requestAudioFocus() {
        if (audioManager == null) return;
        focusListener = change -> {
            if (change == AudioManager.AUDIOFOCUS_LOSS) {
                running.set(false);
                closeBridgeSocket();
            } else {
                mainHandler.post(this::applyTrackVolume);
            }
        };
        try {
            audioManager.requestAudioFocus(
                    focusListener,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK);
        } catch (Throwable ignored) {}
    }

    private void abandonAudioFocus() {
        if (audioManager != null && focusListener != null) {
            try { audioManager.abandonAudioFocus(focusListener); }
            catch (Throwable ignored) {}
        }
        focusListener = null;
    }

    private void startTestTone() {
        RemoteCommunicationSettings.Snapshot settings =
                RemoteCommunicationSettings.load();
        if (running.get()) return;
        if (settings.emergencyDisabled || !settings.voiceEnabled) {
            stopSelf();
            return;
        }
        long session = generation.incrementAndGet();
        stopSessionResources();
        running.set(true);
        outputLevel = RemoteCommunicationPolicy.effectiveOutputLevel(
                settings.outputLevelOverrideEnabled, settings.outputLevel);
        firstPcmLogged = false;
        Thread test = new Thread(() -> {
            try {
                initStreamingAudio();
                startAudioPlayback();
                int sampleCount =
                        RemoteCommunicationPolicy.PCM_SAMPLE_RATE_HZ * 7 / 10;
                byte[] pcm = new byte[sampleCount * 2];
                for (int i = 0; i < sampleCount; i++) {
                    double hz = i < sampleCount / 2 ? 660.0 : 880.0;
                    short sample = (short) (Math.sin(
                            2.0 * Math.PI * hz * i
                                    / RemoteCommunicationPolicy.PCM_SAMPLE_RATE_HZ)
                            * 9_000.0);
                    pcm[i * 2] = (byte) (sample & 0xFF);
                    pcm[i * 2 + 1] = (byte) ((sample >>> 8) & 0xFF);
                }
                writeRemotePcm(pcm);
            } catch (Throwable error) {
                logger.warn("Speaker test failed: " + error.getMessage());
            } finally {
                finishSession(session);
            }
        }, "RemoteVoiceTest");
        test.setDaemon(true);
        test.start();
    }

    private void finishSession(long session) {
        if (!generation.compareAndSet(session, session + 1)) return;
        running.set(false);
        stopSessionResources();
        mainHandler.post(() -> {
            removeOverlay();
            stopSelf();
        });
    }

    private void stopSessionResources() {
        running.set(false);
        closeBridgeSocket();
        AudioTrack track = audioTrack;
        audioTrack = null;
        outputPcmBuffer = new byte[0];
        if (track != null) {
            if (audioPlaybackStarted) {
                try {
                    logger.info("Remote voice playback ended (underruns="
                            + track.getUnderrunCount() + ")");
                } catch (Throwable ignored) {}
            }
            try { track.pause(); } catch (Throwable ignored) {}
            try { track.flush(); } catch (Throwable ignored) {}
            try { track.stop(); } catch (Throwable ignored) {}
            try { track.release(); } catch (Throwable ignored) {}
        }
        audioPlaybackStarted = false;
        audioPrimeBytes = 0;
        abandonAudioFocus();
    }

    private void closeBridgeSocket() {
        try { if (bridgeSocket != null) bridgeSocket.close(); }
        catch (Throwable ignored) {}
        bridgeSocket = null;
        controlOutput = null;
    }

    private void removeOverlay() {
        stopElapsedTicker();
        if (overlayView != null) {
            try { windowManager.removeView(overlayView); } catch (Throwable ignored) {}
        }
        overlayView = null;
        elapsedView = null;
        muteButton = null;
    }

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager manager =
                (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager == null || manager.getNotificationChannel(CHANNEL_ID) != null) return;
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "Remote voice", NotificationManager.IMPORTANCE_MIN);
        channel.setShowBadge(false);
        manager.createNotificationChannel(channel);
    }

    private void startForegroundCompat() {
        Notification.Builder builder = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                ? new Notification.Builder(this, CHANNEL_ID)
                : new Notification.Builder(this);
        Notification notification = builder
                .setContentTitle(getString(R.string.remote_voice_notification_title))
                .setContentText(getString(R.string.remote_voice_notification_text))
                .setSmallIcon(R.drawable.ic_volume_on)
                .setPriority(Notification.PRIORITY_MIN)
                .build();
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                startForeground(
                        NOTIFICATION_ID,
                        notification,
                        android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            } else {
                startForeground(NOTIFICATION_ID, notification);
            }
        } catch (Throwable error) {
            try { startForeground(NOTIFICATION_ID, notification); }
            catch (Throwable ignored) {}
        }
    }

    private Context themedContext() {
        try {
            int mode = androidx.appcompat.app.AppCompatDelegate.getDefaultNightMode();
            int night;
            if (mode == androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_YES) {
                night = android.content.res.Configuration.UI_MODE_NIGHT_YES;
            } else if (mode == androidx.appcompat.app.AppCompatDelegate.MODE_NIGHT_NO) {
                night = android.content.res.Configuration.UI_MODE_NIGHT_NO;
            } else {
                return this;
            }
            android.content.res.Configuration config =
                    new android.content.res.Configuration(
                            getResources().getConfiguration());
            config.uiMode = (config.uiMode
                    & ~android.content.res.Configuration.UI_MODE_NIGHT_MASK) | night;
            return createConfigurationContext(config);
        } catch (Throwable ignored) {
            return this;
        }
    }

    private static String stringExtra(Intent intent, String key, String fallback) {
        try {
            Object value = intent.getExtras() == null
                    ? null : intent.getExtras().get(key);
            return value instanceof String ? (String) value : fallback;
        } catch (Throwable ignored) {
            return fallback;
        }
    }

    private static int parseInt(String value, int fallback) {
        try { return Integer.parseInt(value); }
        catch (Throwable ignored) { return fallback; }
    }

    private static int dp(Context context, int value) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP,
                value,
                context.getResources().getDisplayMetrics());
    }

    @Override public void onDestroy() {
        generation.incrementAndGet();
        running.set(false);
        stopSessionResources();
        removeOverlay();
        MediaPlaybackService.detachRoadSenseDuckTarget(roadSenseDuckTarget);
        super.onDestroy();
    }

    @Override public IBinder onBind(Intent intent) {
        return null;
    }
}
