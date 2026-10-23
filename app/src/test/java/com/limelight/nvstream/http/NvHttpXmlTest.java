package com.limelight.nvstream.http;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import org.junit.Test;
import org.xmlpull.v1.XmlPullParserException;

import java.io.StringReader;
import java.util.List;

/**
 * Reading the host's serverinfo and app list XML.
 */
public class NvHttpXmlTest {
    private static final String SERVERINFO = "<?xml version=\"1.0\" encoding=\"utf-8\"?>"
            + "<root status_code=\"200\"><hostname>PC</hostname><appversion>7.1.431.-1</appversion>"
            + "<PairStatus>1</PairStatus><ServerCommand>a</ServerCommand><ServerCommand>b</ServerCommand></root>";

    @Test
    public void readsFieldsAndArrays() throws Exception {
        assertEquals("PC", NvHTTP.getXmlString(SERVERINFO, "hostname", true));
        assertEquals("7.1.431.-1", NvHTTP.getXmlString(SERVERINFO, "appversion", true));
        assertNull(NvHTTP.getXmlString(SERVERINFO, "missing", false));
        List<String> cmds = NvHTTP.getXmlArray(SERVERINFO, "ServerCommand");
        assertEquals(2, cmds.size());
        assertEquals("a", cmds.get(0));
    }

    @Test(expected = XmlPullParserException.class)
    public void missingMandatoryFieldThrowsParserException() throws Exception {
        NvHTTP.getXmlString(SERVERINFO, "uniqueid", true);
    }

    @Test
    public void errorStatusBecomesHostHttpResponseException() throws Exception {
        // GFE's 0xFFFFFFFF "Invalid" is its missing-audio-device error, reported as 418
        String err = "<root status_code=\"4294967295\" status_message=\"Invalid\"></root>";
        try {
            NvHTTP.getXmlString(err, "x", false);
            fail();
        } catch (HostHttpResponseException e) {
            assertEquals(418, e.getErrorCode());
        }
        try {
            NvHTTP.getXmlString("<root status_code=\"401\" status_message=\"The client is not authorized\"></root>", "x", false);
            fail();
        } catch (HostHttpResponseException e) {
            assertEquals(401, e.getErrorCode());
        }
        // Any other 0xFFFFFFFF stays -1
        try {
            NvHTTP.getAppListByReader(new StringReader("<root status_code=\"4294967295\" status_message=\"Busy\"></root>"));
            fail();
        } catch (HostHttpResponseException e) {
            assertEquals(-1, e.getErrorCode());
        }
    }

    @Test
    public void compactAppList() throws Exception {
        String xml = "<root status_code=\"200\"><App><IsHdrSupported>1</IsHdrSupported><AppTitle>Desktop</AppTitle><ID>1</ID></App>"
                + "<App><AppTitle>NoId</AppTitle></App></root>";
        List<NvApp> apps = NvHTTP.getAppListByReader(new StringReader(xml));
        assertEquals(1, apps.size());
        assertEquals("Desktop", apps.get(0).getAppName());
        assertTrue(apps.get(0).isHdrSupported());
    }

    // Regression: whitespace between tags (a host that indents its XML) crashed the app list with
    // NoSuchElementException, which AppView and the app list poller don't catch
    @Test
    public void indentedAppList() throws Exception {
        String xml = "<?xml version=\"1.0\"?>\n<root status_code=\"200\">\n  <App>\n    <AppTitle>Desktop</AppTitle>\n"
                + "    <ID>1</ID>\n  </App>\n  <App>\n    <AppTitle>Steam Big Picture</AppTitle>\n    <ID>2</ID>\n"
                + "    <IsHdrSupported>1</IsHdrSupported>\n  </App>\n</root>\n";
        List<NvApp> apps = NvHTTP.getAppListByReader(new StringReader(xml));
        assertEquals(2, apps.size());
        assertEquals("Desktop", apps.get(0).getAppName());
        assertEquals(1, apps.get(0).getAppId());
        assertEquals("Steam Big Picture", apps.get(1).getAppName());
        assertEquals(2, apps.get(1).getAppId());
        assertTrue(apps.get(1).isHdrSupported());
    }

    @Test
    public void appListWithTabsAndWindowsLineEndings() throws Exception {
        String xml = "<root status_code=\"200\">\r\n\t<App>\r\n\t\t<AppTitle>Desktop</AppTitle>\r\n\t\t<ID>1</ID>\r\n\t</App>\r\n</root>";
        List<NvApp> apps = NvHTTP.getAppListByReader(new StringReader(xml));
        assertEquals(1, apps.size());
        assertEquals("Desktop", apps.get(0).getAppName());
    }

    @Test
    public void indentedServerInfo() throws Exception {
        String xml = "<?xml version=\"1.0\"?>\n<root status_code=\"200\">\n  <hostname>PC</hostname>\n"
                + "  <ServerCommand>Sleep</ServerCommand>\n</root>\n";
        assertEquals("PC", NvHTTP.getXmlString(xml, "hostname", true));
        assertEquals(List.of("Sleep"), NvHTTP.getXmlArray(xml, "ServerCommand"));
    }

    @Test
    public void emptyAppList() throws Exception {
        assertEquals(0, NvHTTP.getAppListByReader(new StringReader("<root status_code=\"200\"></root>")).size());
        assertEquals(0, NvHTTP.getAppListByReader(new StringReader("<root status_code=\"200\">\n</root>")).size());
    }

    @Test(expected = XmlPullParserException.class)
    public void truncatedAppListIsAParserError() throws Exception {
        NvHTTP.getAppListByReader(new StringReader("<root status_code=\"200\"><App><AppTitle>A</AppTitle><ID>1</ID></App>"));
    }

    @Test
    public void appIdsThatArentNumbersDropTheApp() throws Exception {
        String xml = "<root status_code=\"200\"><App><AppTitle>A</AppTitle><ID>one</ID></App>"
                + "<App><AppTitle>B</AppTitle><ID>99999999999</ID></App><App><AppTitle>C</AppTitle><ID>3</ID></App></root>";
        List<NvApp> apps = NvHTTP.getAppListByReader(new StringReader(xml));
        assertEquals(1, apps.size());
        assertEquals("C", apps.get(0).getAppName());
    }
}
