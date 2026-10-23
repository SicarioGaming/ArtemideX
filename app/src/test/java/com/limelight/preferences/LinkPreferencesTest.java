package com.limelight.preferences;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.limelight.RepoFiles;

import org.junit.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import javax.xml.parsers.DocumentBuilderFactory;

/**
 * Links on the settings screens. A plain Preference with a browser intent crashes Settings on a
 * device with no browser (Android TV), which a4580fa6 fixed for the microphone links; links go
 * through WebLauncherPreference instead, which falls back to an in-app page. Opening a link that
 * way is checked on the device (WebLauncherPreferenceTest).
 */
public class LinkPreferencesTest {
    private static final String ANDROID = "http://schemas.android.com/apk/res/android";
    private static final String WEB_LAUNCHER = WebLauncherPreference.class.getName();

    private static List<Document> preferenceScreens() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        List<Document> screens = new ArrayList<>();
        File[] files = RepoFiles.file("app/src/main/res/xml").listFiles((dir, name) -> name.endsWith(".xml"));
        for (File file : files) {
            Document doc = factory.newDocumentBuilder().parse(file);
            if (doc.getDocumentElement().getTagName().equals("PreferenceScreen")) {
                doc.setDocumentURI(file.getName());
                screens.add(doc);
            }
        }
        return screens;
    }

    @Test
    public void bothSettingsScreensAreChecked() throws Exception {
        List<String> names = new ArrayList<>();
        for (Document doc : preferenceScreens()) {
            names.add(doc.getDocumentURI());
        }
        assertTrue(names.toString(), names.contains("preferences.xml"));
        assertTrue(names.toString(), names.contains("app_preferences.xml"));
    }

    @Test
    public void noSettingOpensABrowserDirectly() throws Exception {
        List<String> problems = new ArrayList<>();
        for (Document doc : preferenceScreens()) {
            NodeList intents = doc.getElementsByTagName("intent");
            for (int i = 0; i < intents.getLength(); i++) {
                Element intent = (Element) intents.item(i);
                String action = intent.getAttributeNS(ANDROID, "action");
                String data = intent.getAttributeNS(ANDROID, "data");
                if (action.equals("android.intent.action.VIEW") || data.startsWith("http")) {
                    Element parent = (Element) intent.getParentNode();
                    problems.add(doc.getDocumentURI() + ": " + parent.getAttributeNS(ANDROID, "key") + " " + data);
                }
            }
        }
        assertEquals("use WebLauncherPreference for these", new ArrayList<String>(), problems);
    }

    @Test
    public void everyLinkHasAnHttpsAddress() throws Exception {
        // WebLauncherPreference throws while the screen is built if url is missing
        int links = 0;
        for (Document doc : preferenceScreens()) {
            NodeList found = doc.getElementsByTagName(WEB_LAUNCHER);
            for (int i = 0; i < found.getLength(); i++) {
                Element link = (Element) found.item(i);
                String key = link.getAttributeNS(ANDROID, "key");
                String url = link.getAttribute("url");
                assertTrue(doc.getDocumentURI() + ": " + key + " has url '" + url + "'", url.startsWith("https://"));
                links++;
            }
        }
        assertTrue("found " + links + " links", links >= 4);
    }
}
