package com.limelight;

import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.res.Configuration;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/** The app calls itself Starwire in every language, not Moonlight (left over from upstream). */
@RunWith(AndroidJUnit4.class)
public class StringsTest {
    // Right to say Moonlight: its test servers, its privacy policy, and the credits
    private static final Set<String> ABOUT_MOONLIGHT = new HashSet<>(Arrays.asList(
            "nettest_text_inconclusive", "summary_privacy_policy",
            "summary_about_credits", "credits_section_moonlight"));
    // Moonlight's own Internet Hosting Tool, named in nettest_text_success
    private static final Pattern HOSTING_TOOL =
            Pattern.compile("Moonlight(\\s+(Internet|İnternet|Internett)|互联网)");
    // The name as Hebrew and Tamil wrote it
    private static final String[] WRITTEN_OUT = {"מונלייט", "மூன்லைட்", "நிலவொளி"};

    @Test
    public void theAppIsCalledStarwireInEveryLanguage() throws Exception {
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        Set<String> languages = new HashSet<>();
        for (String tag : target.getAssets().getLocales()) {
            languages.add(tag);
        }
        languages.add("en");
        // The app's own 30 or so translations are among them
        assertTrue(languages + "", languages.contains("de") && languages.contains("ta")
                && (languages.contains("iw") || languages.contains("he")));
        // And the check would see the name where it's meant to be
        assertTrue(target.getString(R.string.nettest_text_inconclusive).contains("Moonlight"));
        Configuration hebrew = new Configuration(target.getResources().getConfiguration());
        hebrew.setLocale(Locale.forLanguageTag("iw"));
        String waiting = target.createConfigurationContext(hebrew).getString(R.string.nettest_text_waiting);
        assertTrue(waiting, waiting.startsWith("Starwire בודק"));

        List<String> wrong = new ArrayList<>();
        Field[] strings = R.string.class.getFields();
        for (String tag : languages) {
            Configuration config = new Configuration(target.getResources().getConfiguration());
            config.setLocale(Locale.forLanguageTag(tag));
            Context localized = target.createConfigurationContext(config);
            for (Field field : strings) {
                if (ABOUT_MOONLIGHT.contains(field.getName())) {
                    continue;
                }
                String text = HOSTING_TOOL.matcher(localized.getString(field.getInt(null))).replaceAll("");
                boolean named = text.contains("Moonlight");
                for (String name : WRITTEN_OUT) {
                    named |= text.contains(name);
                }
                if (named && !wrong.contains(tag + ":" + field.getName())) {
                    wrong.add(tag + ":" + field.getName());
                }
            }
        }
        assertTrue("still say Moonlight: " + wrong, wrong.isEmpty());
    }
}
