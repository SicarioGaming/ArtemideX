package com.limelight.repo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import com.limelight.RepoFiles;

import org.junit.Test;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Claims in the README, the roadmap, the release notes and the app's own text that no test or
 * measurement backed, reworded after the 0.2.0 code review. These checks keep them from coming
 * back. Once something really is measured or tested, change the wording and this test together.
 */
public class ClaimsTest {
    private static final String[][] UNBACKED = {
            // "not listed with the speed features" says the opposite, so it doesn't count
            {"(?i)turnip[^.\\n]*(faster|(?<!not listed with the )speed)", "the custom Turnip driver hasn't been measured"},
            {"(?i)(faster|speeds? up)[^.\\n]*turnip", "the custom Turnip driver hasn't been measured"},
            {"(?i)speed up vulkan", "the custom Turnip driver hasn't been measured"},
            {"(?i)faster video", "the video options haven't been measured in Starwire"},
            {"(?i)\\bfast video\\b", "nothing has measured the video pipeline in Starwire"},
            {"(?i)every millisecond counts", "the video options haven't been measured in Starwire"},
            {"(?i)every feature is tested", "not every feature has been tried on real hardware"},
            {"(?i)fit every android device", "only narrow screens and foldable sizes are tested"},
            {"(?i)devices of every size", "only narrow screens and foldable sizes are tested"},
            {"(?i)(apollo|sunshine).*\\bworks too\\b", "only Vibepollo has been tried as a host"},
            {"(?i)rebuilds libadrenotools from source", "the libraries are prebuilt"},
            {"(?i)if the driver doesn.{1,2}t work, the system one is used", "a driver that crashes closes Starwire"},
            {"(?i)local network only", "the 0.2.0 microphone isn't protected by being on the local network"},
    };

    private static List<String> textFiles() {
        List<String> files = new ArrayList<>(Arrays.asList("README.md", "docs/ROADMAP.md",
                "app/src/main/res/values/strings.xml"));
        File[] notes = RepoFiles.file(".github/release-notes").listFiles((d, name) -> name.endsWith(".md"));
        assertNotNull(notes);
        for (File note : notes) {
            files.add(".github/release-notes/" + note.getName());
        }
        return files;
    }

    private static String string(String strings, String name) {
        Matcher m = Pattern.compile("<string name=\"" + name + "\">(.*?)</string>", Pattern.DOTALL).matcher(strings);
        assertTrue(name + " not in strings.xml", m.find());
        return m.group(1);
    }

    // Why each unbacked claim in the line is one, or nothing when it makes none
    private static List<String> claimsIn(String line) {
        List<String> reasons = new ArrayList<>();
        for (String[] claim : UNBACKED) {
            if (Pattern.compile(claim[0]).matcher(line).find()) {
                reasons.add(claim[1]);
            }
        }
        return reasons;
    }

    @Test
    public void noClaimsThatNothingBacks() throws Exception {
        // The 0.2.1 notes say the driver is no longer listed with the speed features. That line
        // mustn't count as the claim it takes back, and the claim itself still must.
        assertEquals(0, claimsIn("- **Clearer about what's experimental:** the custom Turnip driver is now"
                + " described as an experimental option, not listed with the speed features.").size());
        assertEquals(1, claimsIn("The custom Turnip driver is listed with the speed features.").size());
        assertEquals(1, claimsIn("A custom Turnip driver for more speed.").size());
        assertEquals(1, claimsIn("Load a Turnip driver for faster PyroWave.").size());

        List<String> found = new ArrayList<>();
        for (String path : textFiles()) {
            String[] lines = RepoFiles.read(path).split("\n", -1);
            for (int i = 0; i < lines.length; i++) {
                for (String reason : claimsIn(lines[i])) {
                    found.add(path + ":" + (i + 1) + " (" + reason + "): " + lines[i].trim());
                }
            }
        }
        assertTrue("claims with nothing behind them:\n" + String.join("\n", found), found.isEmpty());
    }

    // ROADMAP.md says the driver hasn't been tried with a real Turnip build; everything else
    // that describes it has to agree
    @Test
    public void theCustomDriverIsDescribedAsUntried() throws Exception {
        String untried = "not yet tried with a real turnip driver";
        assertTrue(RepoFiles.read("docs/ROADMAP.md").toLowerCase(Locale.ROOT).contains(untried));

        String readme = RepoFiles.read("README.md");
        Matcher row = Pattern.compile("(?m)^\\| Custom Vulkan driver \\(Turnip\\).*$").matcher(readme);
        assertTrue("the README's custom driver row", row.find());
        assertTrue(row.group(), row.group().toLowerCase(Locale.ROOT).contains("experimental"));
        assertTrue(row.group(), row.group().toLowerCase(Locale.ROOT).contains(untried));

        String strings = RepoFiles.read("app/src/main/res/values/strings.xml");
        assertTrue(string(strings, "title_custom_vulkan_driver").toLowerCase(Locale.ROOT).contains("experimental"));
        assertTrue(string(strings, "summary_custom_vulkan_driver").toLowerCase(Locale.ROOT).contains(untried));
        assertTrue(string(strings, "impact_custom_vulkan_driver").toLowerCase(Locale.ROOT).contains(untried));

        // The warning before it's turned on: what it is, who to trust, and what the fallback
        // doesn't cover
        String warning = string(strings, "custom_vulkan_driver_warning_message");
        assertTrue(warning, warning.contains("Only use a driver file from a source you trust"));
        assertTrue(warning, warning.contains("goes back to the normal driver"));
        assertTrue(warning, warning.contains("A driver that crashes closes Starwire"));
        assertTrue(warning, warning.contains("hasn\\'t been tried with a real Turnip driver"));
    }

    // Until the encrypted microphone is ready, the docs say plainly that the 0.2.0 one isn't
    @Test
    public void theMicrophoneSaysItIsntEncrypted() throws Exception {
        assertTrue(RepoFiles.read("README.md").contains("In 0.2.0 the microphone audio isn't encrypted"));
        assertTrue(RepoFiles.read(".github/release-notes/v0.2.0-notes.md").contains("The audio isn't encrypted"));
    }
}
