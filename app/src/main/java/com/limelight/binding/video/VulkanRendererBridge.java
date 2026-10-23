package com.limelight.binding.video;

import android.content.Context;
import android.view.Surface;

import java.io.File;
import java.util.Locale;

import com.limelight.LimeLog;

/**
 * Java side of the native Vulkan video renderer (app/src/main/jni/vulkan).
 *
 * The decoder renders into {@link #getDecoderSurface()}. Native code imports each decoded
 * frame into Vulkan without a copy, converts and dithers it, and presents it on the output
 * surface at the vsync its frame pacing mode picks.
 *
 * For PyroWave streams there is no decoder surface: whole frames go to
 * {@link #submitPyrowaveFrame}, and the renderer decodes them itself.
 */
public class VulkanRendererBridge {
    private static Boolean supported;
    private static Boolean pyrowaveSupported;

    private static boolean driverConfigured;
    private static String configuredDriverPath;
    private static String configuredDriverSha256;
    private static String resolvedDriverPath;
    private static String resolvedDriverSha256;

    private long handle;
    private Surface decoderSurface;
    private boolean tracing;

    private VulkanRendererBridge(long handle) {
        this.handle = handle;
    }

    /**
     * Tells the native renderer to load a custom Vulkan driver (Turnip) through libadrenotools,
     * or the system driver when {@code driverPath} is null. Must be called before the first
     * {@link #isSupported()} or {@link #isPyrowaveSupported()} call, since the loader is opened
     * once per process.
     *
     * @param setting the imported driver file (.so, .zip or .adpkg) in Starwire's driver folder,
     *                or null to use the system driver
     * @param sha256 the file's SHA-256 recorded when it was imported. A file that no longer
     *               matches it isn't loaded.
     */
    public static synchronized void configureCustomDriver(Context context, String setting, String sha256) {
        if (setting != null && setting.trim().isEmpty()) {
            setting = null;
        }
        if (driverConfigured && same(configuredDriverPath, setting) && same(configuredDriverSha256, sha256)) {
            return;
        }
        driverConfigured = true;
        configuredDriverPath = setting;
        configuredDriverSha256 = sha256;

        // Checked first (see CustomVulkanDriver). A .so is used as-is; a .zip/.adpkg is unpacked
        // into app-private storage.
        String driverPath = CustomVulkanDriver.resolve(context, setting, sha256);
        resolvedDriverPath = driverPath;
        resolvedDriverSha256 = driverPath != null ? sha256.trim().toLowerCase(Locale.ROOT) : null;
        String hookLibDir = null;
        String cacheDir = null;
        if (driverPath != null) {
            hookLibDir = context.getApplicationInfo().nativeLibraryDir;
            File cache = context.getCodeCacheDir();
            cacheDir = (cache != null ? cache : context.getCacheDir()).getAbsolutePath();
        }
        try {
            System.loadLibrary("vulkan_renderer");
            nativeConfigureCustomDriver(driverPath, hookLibDir, cacheDir);
        }
        catch (UnsatisfiedLinkError e) {
            LimeLog.warning("Vulkan renderer library unavailable: " + e.getMessage());
        }

        // Which driver is loaded decides the probe, so the cached answers no longer apply
        supported = null;
        pyrowaveSupported = null;
        LimeLog.info("Custom Vulkan driver: " + (driverPath != null ? driverPath : "none"));
    }

    /**
     * Whether the custom Vulkan driver was actually loaded, rather than the system driver. Only
     * meaningful after something has probed Vulkan.
     */
    public static boolean isCustomDriverActive() {
        try {
            return nativeIsCustomDriverActive();
        }
        catch (UnsatisfiedLinkError e) {
            return false;
        }
    }

    /** File name of the resolved custom driver, for a status message, or null. */
    public static String getCustomDriverName() {
        return resolvedDriverPath != null ? new File(resolvedDriverPath).getName() : null;
    }

    /** SHA-256 of the imported driver file that was resolved, or null. */
    public static String getCustomDriverSha256() {
        return resolvedDriverSha256;
    }

    private static boolean same(String a, String b) {
        return a == null ? b == null : a.equals(b);
    }

    /**
     * Whether this device can run the renderer. Needs Android 10 for the NDK APIs it uses,
     * plus Vulkan 1.1 with AHardwareBuffer import and YCbCr sampling.
     */
    public static synchronized boolean isSupported() {
        if (supported == null) {
            boolean result = false;
            try {
                System.loadLibrary("vulkan_renderer");
                result = nativeProbe();
            } catch (UnsatisfiedLinkError e) {
                LimeLog.warning("Vulkan renderer library unavailable: " + e.getMessage());
            }
            LimeLog.info("Vulkan renderer supported: " + result);
            supported = result;
        }
        return supported;
    }

    /**
     * Whether the renderer can decode PyroWave here: libpyrowave-shared.so is packaged for this
     * ABI, and the GPU can run it (Vulkan 1.3 and the features PyroWave needs). Tried once, by
     * making a decoder, which takes a moment.
     */
    public static synchronized boolean isPyrowaveSupported() {
        if (pyrowaveSupported == null) {
            boolean result = isSupported() && nativeProbePyrowave();
            LimeLog.info("PyroWave decoding supported: " + result);
            pyrowaveSupported = result;
        }
        return pyrowaveSupported;
    }

    /**
     * Starts a renderer on the output surface, or returns null if it can't run there.
     *
     * @param framePacing one of PreferenceConfiguration.FRAME_PACING_*
     * @param jitterBuffer one of PreferenceConfiguration.JITTER_BUFFER_*
     * @param ditherMode 0 = off, 1 = low, 2 = high
     * @param colorspace one of MoonBridge.COLORSPACE_*
     * @param pyrowave the stream is PyroWave, which the renderer decodes itself
     * @param pyrowaveCenterChroma the PyroWave host sites 4:2:0 chroma at the center (the nonary
     *                             host) rather than with the left luma sample
     * @param pyrowaveLateFrames one of PreferenceConfiguration.PYROWAVE_LATE_FRAMES_*
     * @param traceDirectory where frame pacing traces are written when switched on with
     *                       {@code adb shell setprop debug.moonlight.pacer_trace 1}, or null
     */
    public static VulkanRendererBridge create(Surface output, int streamWidth, int streamHeight, int streamFps,
                                              int framePacing, int jitterBuffer, int ditherMode, int colorspace,
                                              boolean fullRange, boolean tenBit, boolean pyrowave,
                                              boolean pyrowaveCenterChroma, int pyrowaveLateFrames,
                                              float displayRefreshHz, String traceDirectory) {
        if (!isSupported() || (pyrowave && !isPyrowaveSupported())) {
            return null;
        }

        long handle = nativeCreate(output, streamWidth, streamHeight, streamFps, framePacing, jitterBuffer, ditherMode,
                colorspace, fullRange, tenBit, pyrowave, pyrowaveCenterChroma, pyrowaveLateFrames, displayRefreshHz,
                traceDirectory);
        if (handle == 0) {
            return null;
        }

        VulkanRendererBridge bridge = new VulkanRendererBridge(handle);
        bridge.tracing = nativeIsTracing(handle);
        if (!pyrowave) {
            bridge.decoderSurface = nativeGetDecoderSurface(handle);
            if (bridge.decoderSurface == null) {
                bridge.destroy();
                return null;
            }
        }
        return bridge;
    }

    /**
     * Decodes a PyroWave frame and queues it to be shown.
     *
     * @param hostPtsUs the host's timestamp for the frame
     * @param lastPacketUs when its last packet arrived (its enqueue time), on moonlight-common-c's
     *                     clock. Frames still arriving at their deadline are cut short from it.
     * @param missingRanges for a partial frame, offset and length pairs of the gaps in it (see
     *                      VideoDecoderRenderer.submitDecodeUnit()), or null
     * @param recordStarts for a partial frame in record framing, offsets where records start in
     *                     it (possibly none), or null
     * @param partialKind MoonBridge.PARTIAL_KIND_*
     * @return false if the frame couldn't be decoded
     */
    public boolean submitPyrowaveFrame(byte[] frame, int length, long hostPtsUs, long lastPacketUs,
                                       int[] missingRanges, int[] recordStarts, int partialKind) {
        return handle != 0 && nativeSubmitPyrowaveFrame(handle, frame, length, hostPtsUs, lastPacketUs,
                missingRanges, recordStarts, partialKind);
    }

    /** Surface for the decoder to render into (null for PyroWave) */
    public Surface getDecoderSurface() {
        return decoderSurface;
    }

    /**
     * Records a frame's network timing in the frame pacing trace, if one is being recorded, as
     * it's handed to the decoder. All times in microseconds; receive and enqueue times are
     * moonlight-common-c's. hostProcessingLatency is the host's, in tenths of a millisecond
     * (0 if the host doesn't report it).
     */
    public void noteFrameReceived(long hostPtsUs, long receiveTimeUs, long enqueueTimeUs, char hostProcessingLatency) {
        if (tracing && handle != 0) {
            nativeNoteReceived(handle, hostPtsUs, receiveTimeUs, enqueueTimeUs, hostProcessingLatency);
        }
    }

    public void setHdrMode(boolean enabled, byte[] hdrMetadata) {
        if (handle != 0) {
            nativeSetHdrMode(handle, enabled, hdrMetadata);
        }
    }

    /** Frames presented since the last call */
    public int takePresentedFrames() {
        return handle != 0 ? nativeTakePresentedFrames(handle) : 0;
    }

    /**
     * Total PyroWave decode time since the last call, in nanoseconds, leaving out waits: each
     * frame's CPU work, then its run on the GPU. Where the GPU can't time it, from handing the
     * frame to the renderer until the GPU was done with it.
     */
    public long takeDecodedNs() {
        return handle != 0 ? nativeTakeDecodedNs(handle) : 0;
    }

    /** One line for the performance overlay */
    public String getRendererText() {
        return handle != 0 ? nativeGetRendererText(handle) : null;
    }

    /**
     * Pacing numbers for the performance overlay: a headline and details, separated by a
     * newline. Either may be empty.
     */
    public String getPacingText() {
        return handle != 0 ? nativeGetPacingText(handle) : null;
    }

    /**
     * Stops presenting and lets go of the output surface. Must be called before the output
     * surface is destroyed.
     */
    public void stop() {
        if (handle != 0) {
            nativeStop(handle);
        }
    }

    /** Frees the renderer. The decoder must be released first. */
    public void destroy() {
        if (decoderSurface != null) {
            decoderSurface.release();
            decoderSurface = null;
        }
        if (handle != 0) {
            nativeDestroy(handle);
            handle = 0;
        }
    }

    private static native boolean nativeProbe();
    private static native boolean nativeProbePyrowave();
    private static native void nativeConfigureCustomDriver(String driverPath, String hookLibDir, String cacheDir);
    private static native boolean nativeIsCustomDriverActive();
    private static native long nativeCreate(Surface output, int streamWidth, int streamHeight, int streamFps,
                                            int framePacing, int jitterBuffer, int ditherMode, int colorspace,
                                            boolean fullRange, boolean tenBit, boolean pyrowave,
                                            boolean pyrowaveCenterChroma, int pyrowaveLateFrames,
                                            float displayRefreshHz, String traceDirectory);
    private static native boolean nativeSubmitPyrowaveFrame(long handle, byte[] frame, int length, long hostPtsUs,
                                                            long lastPacketUs, int[] missingRanges,
                                                            int[] recordStarts, int partialKind);
    private static native Surface nativeGetDecoderSurface(long handle);
    private static native boolean nativeIsTracing(long handle);
    private static native void nativeNoteReceived(long handle, long hostPtsUs, long receiveTimeUs, long enqueueTimeUs,
                                                  char hostProcessingLatency);
    private static native void nativeSetHdrMode(long handle, boolean enabled, byte[] hdrMetadata);
    private static native int nativeTakePresentedFrames(long handle);
    private static native long nativeTakeDecodedNs(long handle);
    private static native String nativeGetRendererText(long handle);
    private static native String nativeGetPacingText(long handle);
    private static native void nativeStop(long handle);
    private static native void nativeDestroy(long handle);
}
