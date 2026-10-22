package com.limelight.binding.audio;

import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.media.audiofx.AcousticEchoCanceler;
import android.media.audiofx.NoiseSuppressor;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;


import com.limelight.LimeLog;
import com.limelight.R;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class MicrophoneCaptureManager {
    public interface LevelListener {
        void onLevelUpdate(double level, boolean signalDetected, String status);
    }

    public static final class InputDeviceEntry {
        public final int id;
        public final String label;

        public InputDeviceEntry(int id, String label) {
            this.id = id;
            this.label = label;
        }
    }

    private static final int SAMPLE_RATE = 48000;
    private static final int FRAME_SIZE = 960;
    private static final int LEVEL_UPDATE_INTERVAL_MS = 50;
    private static final int SIGNAL_PEAK_THRESHOLD = 250;
    private static final double SIGNAL_RMS_THRESHOLD = 90.0;
    private static final double PREVIEW_RMS_FLOOR = 40.0;
    private static final double PREVIEW_RMS_CEILING = 4000.0;
    private static final double PREVIEW_PEAK_CEILING = 12000.0;
    private static final double PREVIEW_DECAY_FACTOR = 0.84;

    private final Context context;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private AudioRecord audioRecord;
    private AcousticEchoCanceler echoCanceler;
    private NoiseSuppressor noiseSuppressor;
    private Thread captureThread;
    private volatile boolean running;
    // Where streamed audio goes (null while previewing)
    private MicrophoneSink sink;
    private LevelListener levelListener;
    private String currentStatus;
    private double currentLevel;
    private boolean signalDetected;

    public MicrophoneCaptureManager(Context context) {
        this.context = context.getApplicationContext();
        this.currentStatus = string(R.string.microphone_preview_inactive);
    }

    public static boolean hasRecordAudioPermission(Context context) {
        // Before Android 6 the permission is granted at install time
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
                context.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
                        PackageManager.PERMISSION_GRANTED;
    }

    public static List<InputDeviceEntry> getAvailableInputDevices(Context context) {
        Map<String, InputDeviceEntry> uniqueEntries = new LinkedHashMap<>();

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return new ArrayList<>();
        }

        AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (audioManager == null) {
            return new ArrayList<>();
        }

        for (AudioDeviceInfo deviceInfo : audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
            String label = describeDevice(deviceInfo);
            if (!uniqueEntries.containsKey(label)) {
                uniqueEntries.put(label, new InputDeviceEntry(deviceInfo.getId(), label));
            }
        }

        return new ArrayList<>(uniqueEntries.values());
    }

    public boolean startPreview(int preferredDeviceId, LevelListener listener) {
        return startCapture(preferredDeviceId, listener, null);
    }

    /** Streams to the host over the stream itself (logabell's Apollo build). */
    public boolean startStreaming(int preferredDeviceId, LevelListener listener) {
        return startStreaming(preferredDeviceId, listener, new HostMicrophoneSink());
    }

    /** Streams to the given destination, such as a VBAN receiver on the PC. */
    public boolean startStreaming(int preferredDeviceId, LevelListener listener, MicrophoneSink destination) {
        return startCapture(preferredDeviceId, listener, destination);
    }

    public void stop() {
        AudioRecord recordToRelease;
        Thread threadToJoin;
        MicrophoneSink sinkToClose;

        running = false;
        recordToRelease = audioRecord;
        threadToJoin = captureThread;
        sinkToClose = sink;

        if (recordToRelease != null) {
            try {
                recordToRelease.stop();
            }
            catch (IllegalStateException ignored) {
            }
        }

        if (threadToJoin != null) {
            try {
                threadToJoin.join(2000);
            }
            catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        captureThread = null;
        audioRecord = null;
        releaseVoiceEffects();
        if (recordToRelease != null) {
            recordToRelease.release();
        }

        if (sinkToClose != null) {
            sinkToClose.close();
        }
        sink = null;

        currentLevel = 0.0;
        signalDetected = false;
        dispatchStatus(string(R.string.microphone_preview_inactive), 0.0, false);
    }

    private boolean startCapture(int preferredDeviceId, LevelListener listener, MicrophoneSink streamSink) {
        CaptureConfig config;
        AudioRecord newRecord;
        final int bufferSamples;

        stop();
        levelListener = listener;

        if (!hasRecordAudioPermission(context)) {
            dispatchStatus(string(R.string.microphone_preview_permission_required), 0.0, false);
            return false;
        }

        config = createCaptureConfig(preferredDeviceId);
        if (config == null) {
            dispatchStatus(string(R.string.microphone_preview_open_failed), 0.0, false);
            return false;
        }

        newRecord = config.record;
        bufferSamples = config.bufferSamples;

        if (streamSink != null) {
            int error = streamSink.open();
            if (error != 0) {
                releaseVoiceEffects();
                newRecord.release();
                dispatchStatus(string(error), 0.0, false);
                return false;
            }
        }

        try {
            newRecord.startRecording();
        }
        catch (IllegalStateException e) {
            if (streamSink != null) {
                streamSink.close();
            }
            releaseVoiceEffects();
            newRecord.release();
            dispatchStatus(string(R.string.microphone_capture_start_failed), 0.0, false);
            return false;
        }

        if (newRecord.getRecordingState() != AudioRecord.RECORDSTATE_RECORDING) {
            if (streamSink != null) {
                streamSink.close();
            }
            releaseVoiceEffects();
            newRecord.release();
            dispatchStatus(string(R.string.microphone_capture_start_failed), 0.0, false);
            return false;
        }

        LimeLog.info(String.format((Locale) null,
                "Microphone capture active using source %s, device %s, buffer %d samples",
                config.sourceName,
                config.deviceLabel,
                bufferSamples));

        audioRecord = newRecord;
        sink = streamSink;
        currentStatus = config.statusMessage;
        currentLevel = 0.0;
        signalDetected = false;
        running = true;

        // The thread keeps its own references, since stop() clears the fields while it may still be reading
        final AudioRecord recordForThread = newRecord;
        captureThread = new Thread(() -> runCaptureLoop(recordForThread, streamSink, bufferSamples),
                streamSink != null ? "MicStreamCapture" : "MicPreviewCapture");
        captureThread.start();
        dispatchStatus(config.statusMessage, 0.0, false);
        return true;
    }

    private void runCaptureLoop(AudioRecord record, MicrophoneSink destination, int bufferSamples) {
        // Read 20 ms at a time: the record buffer stays large so nothing is lost, but each piece
        // goes out as soon as it's captured instead of waiting for the whole buffer to fill
        short[] readBuffer = new short[Math.min(bufferSamples, FRAME_SIZE)];
        int pendingPeak = 0;
        double pendingRms = 0.0;
        long lastUpdateTime = SystemClock.elapsedRealtime();
        int readErrors = 0;

        while (running) {
            int samplesRead;

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                samplesRead = record.read(readBuffer, 0, readBuffer.length, AudioRecord.READ_BLOCKING);
            }
            else {
                samplesRead = record.read(readBuffer, 0, readBuffer.length);
            }

            if (samplesRead < 0) {
                // The recorder failed (another app took the mic, or the device went away). Errors
                // come back at once, so wait between tries, and give up after about a second.
                if (++readErrors == 1) {
                    LimeLog.warning("Microphone read failed: " + samplesRead);
                }
                if (readErrors >= 50) {
                    LimeLog.warning("Microphone stopped after repeated read errors");
                    break;
                }
                SystemClock.sleep(20);
                continue;
            }
            readErrors = 0;
            if (samplesRead == 0) {
                continue;
            }

            SignalStats signalStats = calculateSignalStats(readBuffer, samplesRead);
            if (signalStats.peak > pendingPeak) {
                pendingPeak = signalStats.peak;
            }
            if (signalStats.rms > pendingRms) {
                pendingRms = signalStats.rms;
            }

            if (destination != null) {
                destination.write(readBuffer, samplesRead);
            }

            long now = SystemClock.elapsedRealtime();
            if (now - lastUpdateTime >= LEVEL_UPDATE_INTERVAL_MS) {
                double instantaneousLevel = calculatePreviewLevel(pendingPeak, pendingRms);
                double nextLevel = Math.max(instantaneousLevel, currentLevel * PREVIEW_DECAY_FACTOR);
                boolean nextSignalDetected = pendingPeak >= SIGNAL_PEAK_THRESHOLD ||
                        pendingRms >= SIGNAL_RMS_THRESHOLD;
                pendingPeak = 0;
                pendingRms = 0.0;
                lastUpdateTime = now;
                currentLevel = nextLevel;
                signalDetected = nextSignalDetected;
                dispatchStatus(currentStatus, nextLevel, nextSignalDetected);
            }
        }
    }

    private CaptureConfig createCaptureConfig(int preferredDeviceId) {
        // Voice-call capture first: it cancels the game's own sound coming back in through the
        // microphone, so teammates don't hear an echo when the device plays through its speakers
        int[] preferredSources = new int[] {
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                MediaRecorder.AudioSource.MIC
        };
        int minBufferSizeBytes = AudioRecord.getMinBufferSize(SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        if (minBufferSizeBytes <= 0) {
            minBufferSizeBytes = FRAME_SIZE * 4 * 2;
        }
        int bufferSizeBytes = Math.max(minBufferSizeBytes, FRAME_SIZE * 4 * 2);
        int bufferSamples = Math.max(FRAME_SIZE, bufferSizeBytes / 2);
        boolean missingSelectedDevice = false;
        AudioDeviceInfo preferredDevice = null;
        String preferredDeviceLabel = string(R.string.microphone_device_default);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && preferredDeviceId != 0) {
            preferredDevice = findInputDevice(preferredDeviceId);
            if (preferredDevice != null) {
                preferredDeviceLabel = describeDevice(preferredDevice);
            }
            else {
                missingSelectedDevice = true;
            }
        }

        for (int source : preferredSources) {
            AudioRecord candidate;

            if (source < 0) {
                continue;
            }

            candidate = buildAudioRecord(source, bufferSizeBytes);
            if (candidate == null) {
                continue;
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && preferredDevice != null) {
                boolean preferredApplied = candidate.setPreferredDevice(preferredDevice);
                if (!preferredApplied) {
                    LimeLog.info("Preferred microphone device selection was rejected by AudioRecord");
                }
            }

            if (candidate.getState() != AudioRecord.STATE_INITIALIZED) {
                candidate.release();
                continue;
            }

            enableVoiceEffects(candidate);

            CaptureConfig config = new CaptureConfig();
            config.record = candidate;
            config.bufferSamples = bufferSamples;
            config.sourceName = audioSourceToString(source);
            config.deviceLabel = preferredDevice != null ? preferredDeviceLabel : string(R.string.microphone_device_default);
            config.statusMessage = missingSelectedDevice ?
                    string(R.string.microphone_preview_selected_missing) :
                    (preferredDevice != null ?
                            string(R.string.microphone_preview_selected_active) :
                            string(R.string.microphone_preview_default_active));
            return config;
        }

        return null;
    }

    // Echo cancelling and noise suppression, where the device offers them (voice-call capture
    // usually turns them on already; this covers devices that don't)
    private void enableVoiceEffects(AudioRecord record) {
        releaseVoiceEffects();
        int session = record.getAudioSessionId();
        try {
            if (AcousticEchoCanceler.isAvailable()) {
                echoCanceler = AcousticEchoCanceler.create(session);
                if (echoCanceler != null) {
                    echoCanceler.setEnabled(true);
                }
            }
            if (NoiseSuppressor.isAvailable()) {
                noiseSuppressor = NoiseSuppressor.create(session);
                if (noiseSuppressor != null) {
                    noiseSuppressor.setEnabled(true);
                }
            }
        }
        catch (RuntimeException e) {
            // The effects are a bonus; capture works without them
            LimeLog.warning("Microphone voice effects unavailable: " + e.getMessage());
        }
    }

    private void releaseVoiceEffects() {
        if (echoCanceler != null) {
            echoCanceler.release();
            echoCanceler = null;
        }
        if (noiseSuppressor != null) {
            noiseSuppressor.release();
            noiseSuppressor = null;
        }
    }

    private AudioRecord buildAudioRecord(int audioSource, int bufferSizeBytes) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return new AudioRecord.Builder()
                    .setAudioSource(audioSource)
                    .setAudioFormat(new AudioFormat.Builder()
                            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                            .setSampleRate(SAMPLE_RATE)
                            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                            .build())
                    .setBufferSizeInBytes(bufferSizeBytes)
                    .build();
        }

        return new AudioRecord(audioSource,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSizeBytes);
    }

    private AudioDeviceInfo findInputDevice(int deviceId) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return null;
        }

        AudioManager audioManager = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        if (audioManager == null) {
            return null;
        }

        for (AudioDeviceInfo deviceInfo : audioManager.getDevices(AudioManager.GET_DEVICES_INPUTS)) {
            if (deviceInfo.getId() == deviceId) {
                return deviceInfo;
            }
        }

        return null;
    }

    private void dispatchStatus(String status, double level, boolean detected) {
        currentStatus = status;
        if (levelListener == null) {
            // Nothing shows the level during a stream, so don't wake the main thread 20 times a second
            return;
        }
        mainHandler.post(() -> {
            if (levelListener != null) {
                levelListener.onLevelUpdate(level, detected, status);
            }
        });
    }

    private String string(int resId) {
        return context.getString(resId);
    }

    private static SignalStats calculateSignalStats(short[] samples, int sampleCount) {
        int peak = 0;
        double sumSquares = 0.0;

        for (int i = 0; i < sampleCount; i++) {
            int sample = Math.abs(samples[i]);
            if (sample > peak) {
                peak = sample;
            }
            sumSquares += (double) sample * sample;
        }

        SignalStats signalStats = new SignalStats();
        signalStats.peak = peak;
        signalStats.rms = sampleCount > 0 ? Math.sqrt(sumSquares / sampleCount) : 0.0;
        return signalStats;
    }

    private static double calculatePreviewLevel(int peak, double rms) {
        double rmsLevel = (Math.log10(Math.max(rms, PREVIEW_RMS_FLOOR)) -
                Math.log10(PREVIEW_RMS_FLOOR)) /
                (Math.log10(PREVIEW_RMS_CEILING) - Math.log10(PREVIEW_RMS_FLOOR));
        double peakLevel = Math.min(1.0, peak / PREVIEW_PEAK_CEILING);
        return Math.max(0.0, Math.min(1.0, Math.max(rmsLevel, peakLevel)));
    }

    private static String audioSourceToString(int audioSource) {
        if (audioSource == MediaRecorder.AudioSource.VOICE_COMMUNICATION) {
            return "VOICE_COMMUNICATION";
        }
        if (audioSource == MediaRecorder.AudioSource.VOICE_RECOGNITION) {
            return "VOICE_RECOGNITION";
        }
        return "MIC";
    }

    private static String describeDevice(AudioDeviceInfo deviceInfo) {
        switch (deviceInfo.getType()) {
            case AudioDeviceInfo.TYPE_BUILTIN_MIC:
                return "Built-in microphone";
            case AudioDeviceInfo.TYPE_BLUETOOTH_SCO:
                return "Bluetooth headset microphone";
            case AudioDeviceInfo.TYPE_BLUETOOTH_A2DP:
                return "Bluetooth audio input";
            case AudioDeviceInfo.TYPE_WIRED_HEADSET:
                return "Wired headset microphone";
            case AudioDeviceInfo.TYPE_USB_DEVICE:
            case AudioDeviceInfo.TYPE_USB_HEADSET:
                return "USB microphone";
            default:
                CharSequence productName = deviceInfo.getProductName();
                if (productName != null && productName.length() > 0) {
                    return productName.toString();
                }
                return "Input device " + deviceInfo.getId();
        }
    }

    private static final class SignalStats {
        int peak;
        double rms;
    }

    private static final class CaptureConfig {
        AudioRecord record;
        int bufferSamples;
        String sourceName;
        String deviceLabel;
        String statusMessage;
    }
}
