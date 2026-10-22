package com.limelight.binding.audio;

/**
 * Where streamed microphone audio goes: to the host over the stream (logabell's Apollo build), or
 * to a VBAN receiver on the PC. Audio arrives as 48 kHz mono 16-bit PCM.
 */
public interface MicrophoneSink {
    /** Gets ready to send. Returns 0, or a string resource saying why it can't. */
    int open();

    /** Sends some audio. Called on the capture thread. */
    void write(short[] pcm, int samples);

    /** Stops sending and frees what open() set up. Safe to call more than once. */
    void close();
}
