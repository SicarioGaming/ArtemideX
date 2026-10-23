package com.limelight.binding.audio;

/**
 * Packs microphone audio into VBAN packets of 256 samples (VbanSender's format) and hands each
 * one on as it fills. Used by the plain VBAN sender and, inside the encryption, by the encrypted
 * one. Called from one thread (the capture thread).
 */
public final class VbanPacketizer {
    /** Gets each full packet. The buffer is reused for the next packet once this returns. */
    public interface PacketSink {
        void onPacket(byte[] packet, int length);
    }

    public static final int PACKET_SIZE = VbanSender.HEADER_SIZE + VbanSender.SAMPLES_PER_PACKET * 2;

    private final byte[] streamName;
    private final PacketSink sink;
    private final byte[] buffer = new byte[PACKET_SIZE];
    private int samplesInPacket;
    private int frameNumber;

    public VbanPacketizer(String streamName, PacketSink sink) {
        this.streamName = VbanSender.streamNameBytes(streamName);
        this.sink = sink;
    }

    /** Starts again from frame 0 with nothing waiting (a new stream). */
    public void reset() {
        samplesInPacket = 0;
        frameNumber = 0;
    }

    /** Adds samples; each packet that fills goes to the sink with the next frame number. */
    public void write(short[] pcm, int samples) {
        // Fill 256-sample packets, little-endian, and send each one as it fills
        for (int i = 0; i < samples; i++) {
            int at = VbanSender.HEADER_SIZE + samplesInPacket * 2;
            buffer[at] = (byte) pcm[i];
            buffer[at + 1] = (byte) (pcm[i] >> 8);
            if (++samplesInPacket == VbanSender.SAMPLES_PER_PACKET) {
                VbanSender.writeHeader(buffer, VbanSender.SAMPLES_PER_PACKET, frameNumber++, streamName);
                samplesInPacket = 0;
                sink.onPacket(buffer, buffer.length);
            }
        }
    }

    // For tests: where the frame counter is
    void setFrameNumber(int frameNumber) {
        this.frameNumber = frameNumber;
    }
}
