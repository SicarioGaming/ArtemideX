package com.limelight.nvstream.http;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;

/**
 * Parses serverinfo responses shaped like Vibepollo's (nvhttp.cpp puts one ServerCommand
 * element per configured command directly under root).
 */
@RunWith(AndroidJUnit4.class)
public class ServerCommandsParsingTest {
    private static final String WITH_COMMANDS =
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
            "<root status_code=\"200\">" +
            "<hostname>AVI-PC</hostname>" +
            "<appversion>7.1.431.-1</appversion>" +
            "<VirtualDisplayCapable>true</VirtualDisplayCapable>" +
            "<mac>00:11:22:33:44:55</mac>" +
            "<ServerCommand>Sleep PC</ServerCommand>" +
            "<ServerCommand>Lock &amp; blank screen</ServerCommand>" +
            "<ServerCommand>Restart Steam</ServerCommand>" +
            "<Permission>1048575</Permission>" +
            "</root>";

    private static final String WITHOUT_COMMANDS =
            "<?xml version=\"1.0\" encoding=\"utf-8\"?>" +
            "<root status_code=\"200\"><hostname>AVI-PC</hostname><Permission>0</Permission></root>";

    @Test
    public void readsCommandsInHostOrder() throws Exception {
        // The order matters: the index is the ID the host runs
        List<String> commands = NvHTTP.getXmlArray(WITH_COMMANDS, "ServerCommand");
        assertEquals(List.of("Sleep PC", "Lock & blank screen", "Restart Steam"), commands);
    }

    @Test
    public void noCommandsGivesEmptyList() throws Exception {
        assertTrue(NvHTTP.getXmlArray(WITHOUT_COMMANDS, "ServerCommand").isEmpty());
    }
}
