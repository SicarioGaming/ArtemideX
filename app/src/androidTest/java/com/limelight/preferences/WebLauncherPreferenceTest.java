package com.limelight.preferences;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.XmlResourceParser;
import android.util.AttributeSet;
import android.util.Xml;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.limelight.HelpActivity;
import com.limelight.R;
import com.limelight.utils.HelpLauncher;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.xmlpull.v1.XmlPullParser;

import java.util.ArrayList;
import java.util.List;

/**
 * The links on the settings screens, built from the real preferences.xml and clicked. On a
 * device with no browser (Android TV) they open the in-app page instead of crashing Settings
 * (fixed in a4580fa6). Nothing is really opened: the context only records what it was asked to
 * start. That every link is a WebLauncherPreference is checked on the JVM (LinkPreferencesTest).
 */
@RunWith(AndroidJUnit4.class)
public class WebLauncherPreferenceTest {
    private static final String ANDROID = "http://schemas.android.com/apk/res/android";

    private final Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();

    // Records the activities it's asked to start. Without a browser, a browser intent fails the
    // way it does on a device that has none.
    private static final class RecordingContext extends ContextWrapper {
        final boolean hasBrowser;
        final List<Intent> started = new ArrayList<>();

        RecordingContext(Context base, boolean hasBrowser) {
            super(base);
            this.hasBrowser = hasBrowser;
        }

        @Override
        public void startActivity(Intent intent) {
            if (!hasBrowser && intent.getComponent() == null && Intent.ACTION_VIEW.equals(intent.getAction())) {
                throw new ActivityNotFoundException("No Activity found to handle " + intent);
            }
            started.add(intent);
        }
    }

    private static final class Link {
        String key;
        String url;
        WebLauncherPreference preference;
    }

    // Every WebLauncherPreference on a settings screen, built the way the screen builds it
    private List<Link> links(int xml, Context context) throws Exception {
        List<Link> links = new ArrayList<>();
        try (XmlResourceParser parser = target.getResources().getXml(xml)) {
            for (int event = parser.next(); event != XmlPullParser.END_DOCUMENT; event = parser.next()) {
                if (event != XmlPullParser.START_TAG || !parser.getName().equals(WebLauncherPreference.class.getName())) {
                    continue;
                }
                Link link = new Link();
                link.key = parser.getAttributeValue(ANDROID, "key");
                link.url = parser.getAttributeValue(null, "url");
                AttributeSet attrs = Xml.asAttributeSet(parser);
                InstrumentationRegistry.getInstrumentation().runOnMainSync(
                        () -> link.preference = new WebLauncherPreference(context, attrs));
                links.add(link);
            }
        }
        return links;
    }

    private void assertOpensInTheApp(int xml, int atLeast) throws Exception {
        RecordingContext noBrowser = new RecordingContext(target, false);
        List<Link> links = links(xml, noBrowser);
        assertTrue("found " + links.size() + " links", links.size() >= atLeast);
        for (Link link : links) {
            noBrowser.started.clear();
            InstrumentationRegistry.getInstrumentation().runOnMainSync(link.preference::onClick);
            assertEquals(link.key, 1, noBrowser.started.size());
            Intent opened = noBrowser.started.get(0);
            assertNotNull(link.key, opened.getComponent());
            assertEquals(link.key, HelpActivity.class.getName(), opened.getComponent().getClassName());
            assertEquals(link.key, link.url, opened.getDataString());
        }
    }

    @Test
    public void withoutABrowserSettingsLinksOpenInTheApp() throws Exception {
        assertOpensInTheApp(R.xml.preferences, 4);
    }

    @Test
    public void withoutABrowserPerGameSettingsLinksOpenInTheApp() throws Exception {
        // The per-game screen may have no links; any it has must behave the same
        assertOpensInTheApp(R.xml.app_preferences, 0);
    }

    @Test
    public void withABrowserTheLinkGoesToIt() {
        // Android TV goes to the in-app page even when a browser says it's there
        boolean tv = target.getPackageManager().hasSystemFeature(PackageManager.FEATURE_LEANBACK);
        RecordingContext withBrowser = new RecordingContext(target, true);
        String url = "https://github.com/SicarioGaming/Starwire";
        HelpLauncher.launchUrl(withBrowser, url);
        assertEquals(1, withBrowser.started.size());
        Intent opened = withBrowser.started.get(0);
        assertEquals(url, opened.getDataString());
        if (tv) {
            assertEquals(HelpActivity.class.getName(), opened.getComponent().getClassName());
        }
        else {
            assertEquals(Intent.ACTION_VIEW, opened.getAction());
            assertNull(opened.getComponent());
        }
    }
}
