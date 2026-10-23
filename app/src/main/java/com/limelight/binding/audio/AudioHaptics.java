package com.limelight.binding.audio;

import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;

/**
 * Audio haptics (an idea from Moonlight V+): the bass in the game's audio drives the device's
 * vibration motor and/or a controller's rumble, so explosions and engines can be felt.
 *
 * The decoded audio is mixed to mono and low-pass filtered (below about CUTOFF_HZ). An envelope
 * follows the loudness of that bass, and every TICK_MS a handler thread turns it into vibration:
 * a strength level on motors that support one, or a longer or shorter pulse on on/off motors.
 */
public class AudioHaptics {
    public static final int OUTPUT_DEVICE = 1;
    public static final int OUTPUT_CONTROLLER = 2;
    public static final int OUTPUT_BOTH = OUTPUT_DEVICE | OUTPUT_CONTROLLER;

    // Sends rumble to the first controller (Game passes it on to ControllerHandler)
    public interface RumbleSink {
        void rumble(short lowFreqMotor, short highFreqMotor);
    }

    private static final float CUTOFF_HZ = 120f;
    private static final int TICK_MS = 30;
    private static final float RELEASE_SECONDS = 0.08f;
    // Bass quieter than this (fraction of full scale) is ignored, so music and speech stay still
    private static final float NOISE_GATE = 0.02f;
    // Loud game bass sits around a quarter of full scale; this brings it near full strength
    private static final float GAIN = 4f;

    private final int outputs;
    private final float intensity;
    private final RumbleSink rumbleSink;
    private final Vibrator vibrator;
    private final boolean amplitudeControl;

    private float lowPassAlpha;
    private float releasePerSample;
    private int channelCount = 2;

    // Audio thread only
    private float lowPassState;
    private float envelope;

    // Read on the haptics thread
    private volatile float latestLevel;

    private HandlerThread thread;
    private Handler handler;
    private boolean deviceVibrating;
    private boolean controllerRumbling;

    /**
     * @param outputs OUTPUT_DEVICE, OUTPUT_CONTROLLER or OUTPUT_BOTH
     * @param intensityPercent strength scale, 100 = normal
     */
    public AudioHaptics(Context context, int outputs, int intensityPercent, RumbleSink rumbleSink) {
        this.outputs = outputs;
        this.intensity = intensityPercent / 100f;
        this.rumbleSink = rumbleSink;

        Vibrator v;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            VibratorManager manager = (VibratorManager) context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE);
            v = manager != null ? manager.getDefaultVibrator() : null;
        }
        else {
            v = (Vibrator) context.getSystemService(Context.VIBRATOR_SERVICE);
        }
        this.vibrator = v != null && v.hasVibrator() ? v : null;
        this.amplitudeControl = vibrator != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                vibrator.hasAmplitudeControl();
    }

    public void setup(int sampleRate, int channelCount) {
        this.channelCount = Math.max(1, channelCount);
        // One-pole low-pass filter and envelope release, per sample
        lowPassAlpha = (float) (1 - Math.exp(-2 * Math.PI * CUTOFF_HZ / sampleRate));
        releasePerSample = (float) Math.exp(-1.0 / (RELEASE_SECONDS * sampleRate));
    }

    /**
     * Called with each block of decoded audio (interleaved 16-bit PCM) on the audio thread.
     * Cheap: one filter step and one envelope step per sample frame.
     */
    public void process(short[] pcm) {
        float env = envelope;
        float lp = lowPassState;
        for (int i = 0; i + channelCount <= pcm.length; i += channelCount) {
            int sum = 0;
            for (int c = 0; c < channelCount; c++) {
                sum += pcm[i + c];
            }
            float mono = (sum / (float) channelCount) / 32768f;
            lp += lowPassAlpha * (mono - lp);

            float magnitude = Math.abs(lp);
            env = magnitude > env ? magnitude : env * releasePerSample;
        }
        lowPassState = lp;
        envelope = env;
        latestLevel = levelFor(env, intensity);
    }

    // Current vibration strength 0..1
    float level() {
        return latestLevel;
    }

    // Bass envelope (fraction of full scale) to vibration strength 0..1, after the noise gate
    static float levelFor(float envelope, float intensity) {
        float gated = (envelope * GAIN - NOISE_GATE) / (1 - NOISE_GATE);
        if (gated <= 0) {
            return 0;
        }
        return Math.min(1f, gated * intensity);
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            float level = latestLevel;

            if ((outputs & OUTPUT_DEVICE) != 0 && vibrator != null) {
                if (level > 0) {
                    // amplitudeControl is only set on Android 8 and up; the check repeats it for lint
                    if (amplitudeControl && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        int amplitude = Math.max(1, Math.round(level * 255));
                        vibrator.vibrate(VibrationEffect.createOneShot(TICK_MS + 15, amplitude));
                    }
                    else {
                        // On/off motor: stronger bass, longer pulse within each tick
                        long pulseMs = Math.max(5, Math.round(level * TICK_MS));
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            vibrator.vibrate(VibrationEffect.createOneShot(pulseMs, VibrationEffect.DEFAULT_AMPLITUDE));
                        }
                        else {
                            vibrator.vibrate(pulseMs);
                        }
                    }
                    deviceVibrating = true;
                }
                else if (deviceVibrating) {
                    vibrator.cancel();
                    deviceVibrating = false;
                }
            }

            if ((outputs & OUTPUT_CONTROLLER) != 0 && rumbleSink != null) {
                if (level > 0) {
                    // The low-frequency (heavy) motor suits bass
                    rumbleSink.rumble((short) Math.round(level * 0xFFFF), (short) 0);
                    controllerRumbling = true;
                }
                else if (controllerRumbling) {
                    rumbleSink.rumble((short) 0, (short) 0);
                    controllerRumbling = false;
                }
            }

            handler.postDelayed(this, TICK_MS);
        }
    };

    public void start() {
        if (thread != null) {
            return;
        }
        thread = new HandlerThread("AudioHaptics");
        thread.start();
        handler = new Handler(thread.getLooper());
        handler.post(tick);
    }

    public void stop() {
        if (thread == null) {
            return;
        }
        handler.removeCallbacks(tick);
        handler.post(() -> {
            if (deviceVibrating && vibrator != null) {
                vibrator.cancel();
                deviceVibrating = false;
            }
            if (controllerRumbling && rumbleSink != null) {
                rumbleSink.rumble((short) 0, (short) 0);
                controllerRumbling = false;
            }
        });
        thread.quitSafely();
        thread = null;
    }

    public boolean hasDeviceVibrator() {
        return vibrator != null;
    }
}
