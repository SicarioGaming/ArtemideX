package com.limelight.nvstream.http;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.xmlpull.v1.XmlPullParserException;

import java.io.File;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Anything on the network can answer on a host's port, so the XML readers must turn any text at
 * all into either a result or an XmlPullParserException / IOException, the only exceptions their
 * callers (AppView, ShortcutTrampoline and the app list and serverinfo pollers) catch. Anything
 * else would crash the app.
 *
 * The inputs are fixed: every prefix of real responses, and every single-character change from a
 * fixed set, so a failure always reproduces. This is the JVM's kxml2 parser; Android's platform
 * parser is a fork of the same code.
 */
public class NvHttpHostileInputTest {
    @Rule public TemporaryFolder tmp = new TemporaryFolder();

    private static final String APP_LIST = "<?xml version=\"1.0\" encoding=\"utf-8\"?>\n"
            + "<root status_code=\"200\">\n"
            + "  <App>\n    <IsHdrSupported>1</IsHdrSupported>\n    <AppTitle>Desktop &amp; more</AppTitle>\n    <ID>1</ID>\n  </App>\n"
            + "  <App><AppTitle>Steam</AppTitle><ID>2</ID></App>\n"
            + "</root>\n";

    private static final String SERVERINFO = "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
            + "<root status_code=\"200\"><hostname>PC</hostname><appversion>7.1.431.-1</appversion>"
            + "<uniqueid>0F1E2D3C</uniqueid><HttpsPort>47984</HttpsPort><state>SUNSHINE_SERVER_FREE</state>"
            + "<ServerCommand>Sleep</ServerCommand><PairStatus>1</PairStatus></root>";

    private static final char[] REPLACEMENTS = {'<', '>', '&', '"', '/', '=', '!', '?', 'x', ' ', '\u0000'};

    private interface Reader {
        void read(String xml) throws Exception;
    }

    // Every prefix of the text, and every position replaced by each character in REPLACEMENTS
    private static List<String> variantsOf(String xml) {
        List<String> variants = new ArrayList<>();
        for (int end = 0; end <= xml.length(); end++) {
            variants.add(xml.substring(0, end));
        }
        for (int i = 0; i < xml.length(); i++) {
            for (char c : REPLACEMENTS) {
                if (xml.charAt(i) != c) {
                    variants.add(xml.substring(0, i) + c + xml.substring(i + 1));
                }
            }
        }
        return variants;
    }

    private static final String[] HANDWRITTEN = {
            "",
            "not xml at all",
            "<root>",
            "<root></root>",
            "<root status_code=\"\"></root>",
            "<root status_code=\"OK\"></root>",
            "<root status_code=\"99999999999999999999\"></root>",
            "<root status_code=\"200\"><ID>5</ID><AppTitle>Before any App</AppTitle></root>",
            "<root status_code=\"200\"><App><App><AppTitle>Nested</AppTitle><ID>1</ID></App></App></root>",
            "<root status_code=\"200\"></root><root status_code=\"200\"></root>",
            "<root status_code=\"200\"><App><AppTitle>&nope;</AppTitle><ID>1</ID></App></root>",
            "<root status_code=\"200\"><App><AppTitle><![CDATA[x]]></AppTitle><ID>1</ID></App></root>",
            "<App><AppTitle>No root</AppTitle><ID>1</ID></App>",
            "<?xml version=\"1.0\" encoding=\"nope\"?><root status_code=\"200\"></root>",
            "﻿<root status_code=\"200\"></root>",
            "<root status_code=\"200\"><hostname></hostname></root>",
            "</root>",
    };

    private static void assertOnlyExpectedExceptions(String what, List<String> inputs, Reader reader) {
        List<String> crashes = new ArrayList<>();
        for (String input : inputs) {
            try {
                reader.read(input);
            } catch (XmlPullParserException | IOException expected) {
                // What the callers handle
            } catch (Throwable t) {
                if (crashes.size() < 5) {
                    crashes.add(t + " for " + input.replace("\n", "\\n"));
                }
                else if (crashes.size() == 5) {
                    crashes.add("...");
                }
            }
        }
        assertTrue(what + ": exceptions the callers don't catch: " + crashes, crashes.isEmpty());
    }

    private static List<String> corpus(String base) {
        List<String> inputs = variantsOf(base);
        for (String s : HANDWRITTEN) {
            inputs.add(s);
        }
        return inputs;
    }

    @Test
    public void appListNeverThrowsAnythingElse() {
        List<String> inputs = corpus(APP_LIST);
        assertTrue(inputs.size() > 2000);
        assertOnlyExpectedExceptions("app list", inputs, xml -> NvHTTP.getAppListByReader(new StringReader(xml)));
    }

    @Test
    public void serverInfoFieldsNeverThrowAnythingElse() {
        List<String> inputs = corpus(SERVERINFO);
        assertOnlyExpectedExceptions("getXmlString", inputs, xml -> NvHTTP.getXmlString(xml, "uniqueid", true));
        assertOnlyExpectedExceptions("getXmlArray", inputs, xml -> NvHTTP.getXmlArray(xml, "ServerCommand"));
    }

    // Also builds the HTTPS address from the port it read, as the poller does next
    @Test
    public void computerDetailsNeverThrowAnythingElse() throws Exception {
        NvHTTP http = NvHttpServerInfoTest.http();
        List<String> inputs = corpus(SERVERINFO);
        for (String port : new String[] {"0", "-1", "65536", "", " 47984"}) {
            inputs.add(SERVERINFO.replace("<HttpsPort>47984</HttpsPort>", "<HttpsPort>" + port + "</HttpsPort>"
                    + "<ExternalIP>203.0.113.9</ExternalIP><ExternalPort>" + port + "</ExternalPort>"));
        }
        assertOnlyExpectedExceptions("getComputerDetails", inputs, xml -> {
            ComputerDetails details = http.getComputerDetails(xml);
            // A port of 0 would make getHttpsUrl() ask the network for it
            assertTrue(details.httpsPort > 0);
            NvHttpServerInfoTest.http(details.httpsPort).getHttpsUrl(true);
        });
    }

    // The parser itself is fine with these, so they must come back as a list, not an exception
    @Test
    public void whitespaceAndCommentsAnywhereStillParse() throws Exception {
        String xml = "<?xml version=\"1.0\"?>\n<!-- host -->\n<root status_code=\"200\">\n<!-- apps -->\n"
                + "<App>\n<AppTitle>A</AppTitle>\n<!-- id -->\n<ID>1</ID>\n</App>\n</root>\n<!-- end -->\n";
        assertEquals(1, NvHTTP.getAppListByReader(new StringReader(xml)).size());
    }

    // A response must not be able to make the parser read a file on the device (an XML external
    // entity). The parser doesn't process DOCTYPEs, so the entity is never resolved.
    @Test
    public void externalEntitiesAreNeverRead() throws Exception {
        File secret = tmp.newFile("secret.txt");
        Files.write(secret.toPath(), "SECRET-1234".getBytes(StandardCharsets.UTF_8));
        String uri = secret.toURI().toString();
        String xml = "<?xml version=\"1.0\"?><!DOCTYPE root [<!ENTITY x SYSTEM \"" + uri + "\">]>"
                + "<root status_code=\"200\"><hostname>&x;</hostname><App><AppTitle>&x;</AppTitle><ID>1</ID></App></root>";
        try {
            for (NvApp app : NvHTTP.getAppListByReader(new StringReader(xml))) {
                assertFalse(app.getAppName().contains("SECRET"));
            }
        } catch (XmlPullParserException expected) {
        }
        try {
            String name = NvHTTP.getXmlString(xml, "hostname", false);
            assertFalse(name != null && name.contains("SECRET"));
        } catch (XmlPullParserException expected) {
        }
    }

    @Test
    public void aRootWithoutAStatusIsAParserError() throws Exception {
        for (String xml : new String[] {"<root></root>", "<root status_code=\"OK\"></root>"}) {
            try {
                NvHTTP.getAppListByReader(new StringReader(xml));
                fail("accepted " + xml);
            } catch (XmlPullParserException expected) {
            }
            try {
                NvHTTP.getXmlString(xml, "hostname", false);
                fail("accepted " + xml);
            } catch (XmlPullParserException expected) {
            }
        }
    }
}
