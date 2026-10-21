package com.limelight.nvstream.jni;

import static org.junit.Assert.assertEquals;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Checks the JNI link for the server command packet added to moonlight-common-c.
 */
@RunWith(AndroidJUnit4.class)
public class ServerCommandNativeTest {
    @Test
    public void refusedWithoutAStream() {
        // With no connection there is no Sunshine-family host, so the packet must not be sent
        assertEquals(-2, MoonBridge.sendExecServerCmd(0));
    }

    @Test
    public void keepAliveIsSafeWithoutAStream() {
        // Prevent Packet Loss may fire just as a stream ends; it must not crash then
        MoonBridge.sendKeepAlivePayload();
    }
}
