package com.limelight.nvstream.http;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.List;

/**
 * The JVM tests (NvHttpXmlTest, NvHttpHostileInputTest) read XML with kxml2. On a device the
 * app gets Android's own parser, so the cases that crashed before are run again here with it.
 */
@RunWith(AndroidJUnit4.class)
public class PlatformXmlParserTest {
    private static final String INDENTED = "<?xml version=\"1.0\"?>\n<root status_code=\"200\">\n  <App>\n"
            + "    <AppTitle>Desktop</AppTitle>\n    <ID>1</ID>\n  </App>\n  <App>\n    <AppTitle>Steam</AppTitle>\n"
            + "    <ID>2</ID>\n  </App>\n</root>\n";

    // Regression: NoSuchElementException from the whitespace before the first App
    @Test
    public void indentedAppListParses() throws Exception {
        List<NvApp> apps = NvHTTP.getAppListByReader(new StringReader(INDENTED));
        assertEquals(2, apps.size());
        assertEquals("Desktop", apps.get(0).getAppName());
        assertEquals(2, apps.get(1).getAppId());
    }

    // Every prefix of the response, and every character replaced by each of a fixed set
    @Test
    public void damagedAppListsOnlyGiveParserErrors() {
        char[] replacements = {'<', '>', '&', '"', '/', '=', 'x', ' '};
        List<String> inputs = new ArrayList<>();
        for (int end = 0; end <= INDENTED.length(); end++) {
            inputs.add(INDENTED.substring(0, end));
        }
        for (int i = 0; i < INDENTED.length(); i++) {
            for (char c : replacements) {
                inputs.add(INDENTED.substring(0, i) + c + INDENTED.substring(i + 1));
            }
        }
        inputs.add("<root></root>");
        inputs.add("<root status_code=\"OK\"></root>");
        inputs.add("<root status_code=\"200\"><ID>5</ID></root>");

        List<String> crashes = new ArrayList<>();
        for (String xml : inputs) {
            try {
                NvHTTP.getAppListByReader(new StringReader(xml));
            } catch (XmlPullParserException | IOException expected) {
            } catch (Throwable t) {
                if (crashes.size() < 5) crashes.add(t + " for " + xml.replace("\n", "\\n"));
            }
            try {
                NvHTTP.getXmlString(xml, "AppTitle", false);
            } catch (XmlPullParserException | IOException expected) {
            } catch (Throwable t) {
                if (crashes.size() < 5) crashes.add(t + " for " + xml.replace("\n", "\\n"));
            }
        }
        assertTrue("exceptions the callers don't catch: " + crashes, crashes.isEmpty());
    }
}
