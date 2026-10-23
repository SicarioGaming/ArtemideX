package com.limelight.repo;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.limelight.RepoFiles;

import org.junit.Test;

import java.io.File;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Version numbers follow Semantic Versioning 2.0.0 (https://semver.org/spec/v2.0.0.html). These
 * checks keep app/build.gradle, the CHANGELOG.md headings and the release notes in step. Build
 * metadata (+...) isn't used. Starwire doesn't use pre-release labels (like -beta.1) either, but
 * they're accepted, since SemVer allows them. These checks don't look at the previous release:
 * whether a release's changes make it a PATCH or a MINOR bump, and whether versionCode went up,
 * are checked by .github/tools/check-version.sh at release time, not here. The parsing and
 * comparing take plain strings, so each check is also run on planted mistakes.
 */
public class VersionNumbersTest {
    private static final String REPO = "https://github.com/SicarioGaming/Starwire/";

    // The regular expression semver.org's FAQ suggests. Group 4 is the pre-release part and group
    // 5 the build metadata.
    private static final Pattern SEMVER = Pattern.compile("^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)"
            + "(?:-((?:0|[1-9]\\d*|\\d*[a-zA-Z-][0-9a-zA-Z-]*)(?:\\.(?:0|[1-9]\\d*|\\d*[a-zA-Z-][0-9a-zA-Z-]*))*))?"
            + "(?:\\+([0-9a-zA-Z-]+(?:\\.[0-9a-zA-Z-]+)*))?$");

    private static final Pattern HEADING = Pattern.compile("## \\[([^\\]]*)\\] - (.*)");
    private static final Pattern LINK = Pattern.compile("\\[([^\\]]+)\\]: (.*)");

    // Why the string can't be a Starwire version number, or null when it can
    static String versionProblem(String v) {
        Matcher m = SEMVER.matcher(v);
        if (!m.matches()) {
            return "\"" + v + "\" isn't a SemVer 2.0.0 version: write MAJOR.MINOR.PATCH with no leading zeros,"
                    + " like 0.3.0";
        }
        if (m.group(5) != null) {
            return v + " has build metadata (+" + m.group(5) + "), which Starwire doesn't use: take it off";
        }
        return null;
    }

    static boolean isPreRelease(String v) {
        return parse(v).group(4) != null;
    }

    private static Matcher parse(String v) {
        String problem = versionProblem(v);
        if (problem != null) throw new IllegalArgumentException(problem);
        Matcher m = SEMVER.matcher(v);
        if (!m.matches()) throw new AssertionError(v);
        return m;
    }

    // SemVer precedence (spec section 11): below zero when a comes before b. Build metadata isn't
    // accepted, so two different versions never have the same precedence.
    static int compare(String a, String b) {
        Matcher x = parse(a);
        Matcher y = parse(b);
        for (int i = 1; i <= 3; i++) {
            int c = compareNumbers(x.group(i), y.group(i));
            if (c != 0) return c;
        }
        // A version without a pre-release part comes after all of its pre-releases
        String preA = x.group(4);
        String preB = y.group(4);
        if (preA == null && preB == null) return 0;
        if (preA == null) return 1;
        if (preB == null) return -1;
        String[] idsA = preA.split("\\.");
        String[] idsB = preB.split("\\.");
        for (int i = 0; i < Math.min(idsA.length, idsB.length); i++) {
            int c = compareIdentifiers(idsA[i], idsB[i]);
            if (c != 0) return c;
        }
        // Every identifier so far is the same, so the one with more of them comes after
        return Integer.compare(idsA.length, idsB.length);
    }

    // Numbers are compared as numbers and come before words. Words are compared in ASCII order.
    private static int compareIdentifiers(String a, String b) {
        boolean numberA = a.matches("\\d+");
        boolean numberB = b.matches("\\d+");
        if (numberA && numberB) return compareNumbers(a, b);
        if (numberA != numberB) return numberA ? -1 : 1;
        return Integer.signum(a.compareTo(b));
    }

    // The regex rules out leading zeros, so a longer number is a bigger one. This also works for
    // numbers too big for a long.
    private static int compareNumbers(String a, String b) {
        if (a.length() != b.length()) return a.length() < b.length() ? -1 : 1;
        return Integer.signum(a.compareTo(b));
    }

    // Why the string can't be a versionCode, or null when it can. Android keeps it in an int.
    static String versionCodeProblem(String code) {
        if (code.matches("[1-9]\\d{0,9}") && Long.parseLong(code) <= Integer.MAX_VALUE) return null;
        return "versionCode is " + code + ": it has to be a whole number above 0 with no leading zeros,"
                + " no bigger than " + Integer.MAX_VALUE;
    }

    // The value a build.gradle line gives to versionName or versionCode, with any quotes taken off.
    // Null when no line sets it, or more than one does, so there's no one value to check.
    static String gradleValue(String gradle, String name) {
        Matcher m = Pattern.compile("(?m)^[ \\t]*" + name + "\\b[ \\t]*=?[ \\t]*(.*?)[ \\t]*$")
                .matcher(gradle.replace("\r", ""));
        if (!m.find()) return null;
        String value = m.group(1);
        if (m.find()) return null;
        Matcher quoted = Pattern.compile("([\"'])(.*)\\1").matcher(value);
        return quoted.matches() ? quoted.group(2) : value;
    }

    // The version of the first heading that isn't [Unreleased], or null when there's none
    static String newestVersion(String changelog) {
        for (String line : changelog.replace("\r", "").split("\n", -1)) {
            if (line.startsWith("## [") && !line.equals("## [Unreleased]")) {
                int end = line.indexOf(']');
                return end < 0 ? line.substring(4) : line.substring(4, end);
            }
        }
        return null;
    }

    // What's wrong with a changelog's version headings and their link references
    static List<String> changelogProblems(String changelog) {
        String[] lines = changelog.replace("\r", "").split("\n", -1);
        Map<String, String> links = new HashMap<>();
        for (String line : lines) {
            Matcher m = LINK.matcher(line);
            if (m.matches() && !links.containsKey(m.group(1))) links.put(m.group(1), m.group(2).trim());
        }

        List<String> problems = new ArrayList<>();
        int headings = 0;
        boolean unreleased = false;
        String newest = null;
        String newer = null;
        String newerDated = null;
        LocalDate newerDate = null;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (!line.startsWith("## [")) continue;
            headings++;
            String at = "line " + (i + 1) + ", " + line + ": ";
            if (line.equals("## [Unreleased]")) {
                if (headings != 1) problems.add(at + "[Unreleased] has to be the first version heading");
                unreleased = true;
                continue;
            }
            Matcher m = HEADING.matcher(line);
            if (!m.matches()) {
                problems.add(at + "a version heading has to look like ## [0.3.0] - 2026-11-02");
                continue;
            }
            String version = m.group(1);
            String bad = versionProblem(version);
            if (bad != null) {
                problems.add(at + bad);
                continue;
            }
            // The highest, so one heading out of place gives one problem, not one for its link too
            if (newest == null || compare(version, newest) > 0) newest = version;

            LocalDate date = null;
            try {
                date = LocalDate.parse(m.group(2), DateTimeFormatter.ISO_LOCAL_DATE);
            } catch (DateTimeParseException e) {
                problems.add(at + m.group(2) + " isn't a real date: write it as YYYY-MM-DD, like 2026-11-02");
            }

            if (newer != null) {
                int c = compare(newer, version);
                if (c == 0) {
                    problems.add(at + version + " is listed twice");
                } else if (c < 0) {
                    problems.add(at + version + " comes after " + newer + " but is higher: put the newest version first");
                }
            }
            newer = version;
            if (date != null) {
                if (newerDate != null && date.isAfter(newerDate)) {
                    problems.add(at + version + " is dated " + date + ", after " + newerDated + "'s " + newerDate
                            + ": dates can't go up as you read down");
                }
                newerDate = date;
                newerDated = version;
            }

            String link = links.get(version);
            if (link == null) {
                problems.add(at + "there's no link reference for [" + version + "]: add [" + version + "]: " + REPO
                        + "compare/v<the version before>...v" + version + " at the bottom");
            } else if (!link.startsWith(REPO)) {
                problems.add(at + "the link for [" + version + "] goes to " + link + ": it has to start with " + REPO);
            }
        }

        String unreleasedLink = links.get("Unreleased");
        if (newest != null && (unreleased || unreleasedLink != null)) {
            String want = REPO + "compare/v" + newest + "...HEAD";
            if (unreleasedLink == null) {
                problems.add("there's no link reference for [Unreleased]: add [Unreleased]: " + want + " at the bottom");
            } else if (!unreleasedLink.equals(want)) {
                problems.add("[Unreleased]'s link is " + unreleasedLink + " but the newest version is " + newest
                        + ": make it " + want);
            }
        }
        return problems;
    }

    // What's wrong with versionName, given the changelog's newest version and the names of the
    // release notes files (like v0.2.0-notes.md)
    static List<String> bumpProblems(String versionName, String newest, List<String> notes) {
        List<String> problems = new ArrayList<>();
        String bad = versionProblem(versionName);
        if (bad != null) {
            problems.add("app/build.gradle's versionName: " + bad);
            return problems;
        }
        if (newest == null || versionProblem(newest) != null) {
            problems.add("CHANGELOG.md has no released version to compare versionName " + versionName + " with");
            return problems;
        }
        // A pre-release is numbered after the newest release. It needs no notes here, but
        // check-version.sh wants them before it's tagged.
        if (isPreRelease(versionName)) {
            if (compare(versionName, newest) <= 0) {
                problems.add("versionName is " + versionName + ", a pre-release, but CHANGELOG.md already has "
                        + newest + ": its number has to be higher than the newest release. Starwire doesn't use"
                        + " pre-release labels, so write MAJOR.MINOR.PATCH, higher than " + newest);
            }
            return problems;
        }
        int c = compare(versionName, newest);
        if (c > 0) {
            problems.add("versionName is " + versionName + " but CHANGELOG.md's newest version is " + newest
                    + ": move [Unreleased] to [" + versionName + "] when you bump the version");
        } else if (c < 0) {
            problems.add("versionName is " + versionName + " but CHANGELOG.md's newest version is " + newest
                    + ": raise versionName and versionCode in app/build.gradle to match");
        }
        String file = "v" + versionName + "-notes.md";
        if (!notes.contains(file)) {
            problems.add("there's no .github/release-notes/" + file + ": the Release workflow reads the notes from"
                    + " there, so write them before tagging v" + versionName);
        }
        return problems;
    }

    @Test
    public void versionNameAndCodeAreValid() throws Exception {
        String gradle = RepoFiles.read("app/build.gradle");
        String name = gradleValue(gradle, "versionName");
        String code = gradleValue(gradle, "versionCode");
        assertNotNull("app/build.gradle has to set versionName once, as a plain string like versionName \"0.3.0\"", name);
        assertNotNull("app/build.gradle has to set versionCode once, as a plain number like versionCode = 319", code);
        String problem = versionProblem(name);
        assertTrue("app/build.gradle's versionName: " + problem, problem == null);
        problem = versionCodeProblem(code);
        assertTrue("app/build.gradle's " + problem, problem == null);
    }

    @Test
    public void changelogVersionsAreInOrderWithLinks() throws Exception {
        List<String> problems = changelogProblems(RepoFiles.read("CHANGELOG.md"));
        assertTrue("CHANGELOG.md:\n" + String.join("\n", problems), problems.isEmpty());
    }

    @Test
    public void versionNameIsTheNewestRelease() throws Exception {
        String name = gradleValue(RepoFiles.read("app/build.gradle"), "versionName");
        assertNotNull("app/build.gradle has to set versionName once", name);
        File[] files = RepoFiles.file(".github/release-notes").listFiles(File::isFile);
        assertNotNull(files);
        List<String> notes = new ArrayList<>();
        for (File f : files) notes.add(f.getName());
        List<String> problems = bumpProblems(name, newestVersion(RepoFiles.read("CHANGELOG.md")), notes);
        assertTrue(String.join("\n", problems), problems.isEmpty());
    }

    @Test
    public void precedenceFollowsTheSpec() {
        String[][] orders = {
                // Spec section 11's own examples
                {"1.0.0-alpha", "1.0.0-alpha.1", "1.0.0-alpha.beta", "1.0.0-beta", "1.0.0-beta.2", "1.0.0-beta.11",
                        "1.0.0-rc.1", "1.0.0"},
                {"1.0.0", "2.0.0", "2.1.0", "2.1.1"},
                // Numbers are compared as numbers, not as text, however long they are
                {"0.9.0", "0.10.0"},
                {"9.0.0", "10.0.0", "99999999999999999999.0.0"},
                // A pre-release of 0.3.0 comes between 0.2.1 and 0.3.0
                {"0.2.1", "0.3.0-beta.1", "0.3.0"},
                // Numbers come before words, and words are in ASCII order, so capitals come first
                {"1.0.0-1", "1.0.0-Beta", "1.0.0-alpha", "1.0.0-beta"},
                {"1.0.0-beta.9", "1.0.0-beta.10", "1.0.0-beta.10.1"},
        };
        for (String[] order : orders) {
            for (int i = 0; i < order.length; i++) {
                for (int j = 0; j < order.length; j++) {
                    String want = i < j ? " before " : i > j ? " after " : " level with ";
                    assertEquals(order[i] + " should come" + want + order[j], Integer.compare(i, j),
                            Integer.signum(compare(order[i], order[j])));
                }
            }
        }
    }

    // The FAQ's own lists of strings its regular expression accepts and rejects
    @Test
    public void theRegexAgreesWithTheSpecExamples() {
        String[] valid = {"0.0.4", "1.2.3", "10.20.30", "1.1.2-prerelease+meta", "1.1.2+meta", "1.1.2+meta-valid",
                "1.0.0-alpha", "1.0.0-beta", "1.0.0-alpha.beta", "1.0.0-alpha.beta.1", "1.0.0-alpha.1",
                "1.0.0-alpha0.valid", "1.0.0-alpha.0valid", "1.0.0-alpha-a.b-c-somethinglong+build.1-aef.1-its-okay",
                "1.0.0-rc.1+build.1", "2.0.0-rc.1+build.123", "1.2.3-beta", "10.2.3-DEV-SNAPSHOT", "1.2.3-SNAPSHOT-123",
                "1.0.0", "2.0.0", "1.1.7", "2.0.0+build.1848", "2.0.1-alpha.1227", "1.0.0-alpha+beta",
                "1.2.3----RC-SNAPSHOT.12.9.1--.12+788", "1.2.3----R-S.12.9.1--.12+meta", "1.2.3----RC-SNAPSHOT.12.9.1--.12",
                "1.0.0+0.build.1-rc.10000aaa-kk-0.1", "99999999999999999999999.999999999999999999.99999999999999999",
                "1.0.0-0A.is.legal"};
        String[] invalid = {"1", "1.2", "1.2.3-0123", "1.2.3-0123.0123", "1.1.2+.123", "+invalid", "-invalid",
                "-invalid+invalid", "-invalid.01", "alpha", "alpha.beta", "alpha.beta.1", "alpha.1", "alpha+beta",
                "alpha_beta", "alpha.", "alpha..", "beta", "1.0.0-alpha_beta", "-alpha.", "1.0.0-alpha..",
                "1.0.0-alpha..1", "1.0.0-alpha...1", "01.1.1", "1.01.1", "1.1.01", "1.2.3.DEV", "1.2-SNAPSHOT",
                "1.2.31.2.3----RC-SNAPSHOT.12.09.1--..12+788", "1.2-RC-SNAPSHOT", "-1.0.3-gamma+b7718", "+justmeta",
                "9.8.7+meta+meta", "9.8.7-whatever+meta+meta"};
        for (String v : valid) assertTrue(v + " is valid SemVer", SEMVER.matcher(v).matches());
        for (String v : invalid) assertFalse(v + " isn't valid SemVer", SEMVER.matcher(v).matches());
    }

    @Test
    public void badVersionNumbersAreCaught() {
        for (String good : new String[] {"0.0.0", "0.2.1", "10.20.30", "0.3.0-beta.1", "1.0.0-rc.1", "1.0.0-0A.is.legal"}) {
            assertNull(good, versionProblem(good));
        }
        String[] bad = {"01.2.3", "1.02.3", "1.2.03", "0.3", "1", "1.2.3.4", "v0.3.0", "0.3.0-", "0.3.0-01",
                "0.3.0-beta..1", "0.3.0-beta_1", " 0.3.0", "0.3.0 ", "0.3.0\n", ""};
        for (String v : bad) {
            String problem = versionProblem(v);
            assertNotNull("\"" + v + "\" should be rejected", problem);
            assertTrue(problem, problem.contains("isn't a SemVer 2.0.0 version"));
            // Starwire uses no pre-release labels, so the advice doesn't suggest one
            assertTrue(problem, problem.endsWith("write MAJOR.MINOR.PATCH with no leading zeros, like 0.3.0"));
        }
        for (String v : new String[] {"1.0.0+build.1", "0.3.0-beta.1+exp.sha.5114f85"}) {
            String problem = versionProblem(v);
            assertNotNull(v + " should be rejected", problem);
            assertTrue(problem, problem.contains("has build metadata"));
            assertTrue(problem, problem.endsWith("which Starwire doesn't use: take it off"));
        }
        // The comparison refuses them too, so a bad version can't be put in order by accident
        try {
            compare("1.0.0+build.1", "1.0.0");
            fail("build metadata was compared");
        } catch (IllegalArgumentException e) {
            assertTrue(e.getMessage(), e.getMessage().contains("has build metadata"));
        }
    }

    @Test
    public void badVersionCodesAreCaught() {
        for (String good : new String[] {"1", "318", "2147483647"}) {
            assertNull(good, versionCodeProblem(good));
        }
        for (String bad : new String[] {"0", "-1", "0318", "318.5", "3e2", "", "abc", "2147483648", "318 // x"}) {
            assertNotNull("versionCode " + bad + " should be rejected", versionCodeProblem(bad));
        }
    }

    @Test
    public void buildGradleValuesAreReadWhenSetOnce() {
        String gradle = "    defaultConfig {\r\n        versionName \"0.2.1\"\r\n        versionCode = 319\r\n"
                + "        versionNameSuffix \"-x\"\r\n    }\r\n";
        assertEquals("0.2.1", gradleValue(gradle, "versionName"));
        assertEquals("319", gradleValue(gradle, "versionCode"));
        assertEquals("0.2.1", gradleValue("versionName = '0.2.1'", "versionName"));
        // Set twice (by a flavour, say) or not at all, there's no one value to check
        assertNull(gradleValue(gradle + "        versionName \"0.2.2\"\n", "versionName"));
        assertNull(gradleValue("versionCode 1", "versionName"));
        // A name worked out by code is read as written, so the SemVer check turns it down
        assertNotNull(versionProblem(gradleValue("versionName gitDescribe()", "versionName")));
    }

    // A small changelog that passes. Each planted mistake below changes one thing in it.
    private static final String GOOD = String.join("\n",
            "# Changelog",
            "",
            "## [Unreleased]",
            "",
            "## [0.3.0] - 2026-11-02",
            "",
            "### Added",
            "",
            "## [0.2.1] - 2026-10-23",
            "",
            "## [0.2.0] - 2026-10-23",
            "",
            "## [0.1.0] - 2026-10-22",
            "",
            "[Unreleased]: " + REPO + "compare/v0.3.0...HEAD",
            "[0.3.0]: " + REPO + "compare/v0.2.1...v0.3.0",
            "[0.2.1]: " + REPO + "compare/v0.2.0...v0.2.1",
            "[0.2.0]: " + REPO + "compare/v0.1.0...v0.2.0",
            "[0.1.0]: " + REPO + "releases/tag/v0.1.0",
            "");

    @Test
    public void changelogMistakesAreCaught() {
        List<String> none = Collections.emptyList();
        assertEquals(none, changelogProblems(GOOD));
        assertEquals(none, changelogProblems(GOOD.replace("\n", "\r\n")));
        assertEquals("0.3.0", newestVersion(GOOD));
        assertNull(newestVersion("# Changelog\n\n## [Unreleased]\n"));

        // [Unreleased] can be left out, along with its link
        String noUnreleased = without(without(GOOD, "## [Unreleased]"), "[Unreleased]: " + REPO + "compare/v0.3.0...HEAD");
        assertEquals(none, changelogProblems(noUnreleased));
        assertEquals("0.3.0", newestVersion(noUnreleased));

        // A pre-release heading (Starwire uses none, but SemVer allows them) goes below its release
        String beta = replaced(replaced(GOOD, "## [0.2.1] -", "## [0.3.0-beta.1] - 2026-10-30\n\n## [0.2.1] -"),
                "[0.2.1]:", "[0.3.0-beta.1]: " + REPO + "compare/v0.2.1...v0.3.0-beta.1\n[0.2.1]:");
        assertEquals(none, changelogProblems(beta));
        assertOneProblem(changelogProblems(swap(beta, "## [0.3.0] -", "## [0.3.0-beta.1] -")),
                "0.3.0 comes after 0.3.0-beta.1 but is higher");

        assertOneProblem(changelogProblems(swap(GOOD, "## [0.2.1] -", "## [0.2.0] -")),
                "0.2.1 comes after 0.2.0 but is higher: put the newest version first");
        assertOneProblem(changelogProblems(swap(GOOD, "## [Unreleased]", "## [0.3.0] - 2026-11-02")),
                "[Unreleased] has to be the first version heading");
        assertOneProblem(changelogProblems(replaced(GOOD, "## [0.2.0] -", "## [0.2.1] -")), "0.2.1 is listed twice");

        assertOneProblem(changelogProblems(without(GOOD, "[0.2.0]: " + REPO + "compare/v0.1.0...v0.2.0")),
                "there's no link reference for [0.2.0]");
        assertOneProblem(changelogProblems(replaced(GOOD, "[0.2.0]: " + REPO, "[0.2.0]: https://example.com/Starwire/")),
                "it has to start with " + REPO);
        assertOneProblem(changelogProblems(without(GOOD, "[Unreleased]: " + REPO + "compare/v0.3.0...HEAD")),
                "there's no link reference for [Unreleased]");
        assertOneProblem(changelogProblems(replaced(GOOD, "compare/v0.3.0...HEAD", "compare/v0.2.1...HEAD")),
                "but the newest version is 0.3.0: make it " + REPO + "compare/v0.3.0...HEAD");

        for (String date : new String[] {"2026-02-30", "2026-13-01", "2026-10-5", "23-10-2026", "2026/10/23", "soon"}) {
            assertOneProblem(changelogProblems(replaced(GOOD, "## [0.2.0] - 2026-10-23", "## [0.2.0] - " + date)),
                    date + " isn't a real date");
        }
        assertOneProblem(changelogProblems(replaced(GOOD, "## [0.2.0] - 2026-10-23", "## [0.2.0] - 2026-10-24")),
                "0.2.0 is dated 2026-10-24, after 0.2.1's 2026-10-23: dates can't go up");
        assertOneProblem(changelogProblems(replaced(GOOD, "## [0.2.0] - 2026-10-23", "## [0.2.0]")),
                "a version heading has to look like ## [0.3.0] - 2026-11-02");

        for (String version : new String[] {"0.2", "00.2.0", "0.2.00", "v0.2.0"}) {
            assertOneProblem(changelogProblems(replaced(GOOD, "## [0.2.0] -", "## [" + version + "] -")),
                    "isn't a SemVer 2.0.0 version");
        }
        assertOneProblem(changelogProblems(replaced(GOOD, "## [0.2.0] -", "## [0.2.0+build.1] -")),
                "has build metadata (+build.1)");
    }

    @Test
    public void versionNameMistakesAreCaught() {
        List<String> none = Collections.emptyList();
        List<String> notes = Arrays.asList("v0.2.0-notes.md", "v0.2.1-notes.md");
        assertEquals(none, bumpProblems("0.2.1", "0.2.1", notes));
        // Starwire uses no pre-release labels, but one is accepted if it's numbered after the newest
        // release. It needs no notes here.
        assertEquals(none, bumpProblems("0.3.0-beta.1", "0.2.1", none));

        assertOneProblem(bumpProblems("0.2.1", "0.2.0", notes), "versionName is 0.2.1 but CHANGELOG.md's newest"
                + " version is 0.2.0: move [Unreleased] to [0.2.1] when you bump the version");
        assertOneProblem(bumpProblems("0.2.0", "0.2.1", notes), "raise versionName and versionCode");
        assertOneProblem(bumpProblems("0.2.1", "0.2.1", Collections.singletonList("v0.2.0-notes.md")),
                "there's no .github/release-notes/v0.2.1-notes.md");
        assertOneProblem(bumpProblems("0.2.1-beta.1", "0.2.1", notes), "has to be higher than the newest release."
                + " Starwire doesn't use pre-release labels, so write MAJOR.MINOR.PATCH, higher than 0.2.1");
        assertOneProblem(bumpProblems("0.3.0-rc.1", "0.3.0", notes), "has to be higher than the newest release");
        assertOneProblem(bumpProblems("0.3", "0.2.1", notes), "isn't a SemVer 2.0.0 version");
        assertOneProblem(bumpProblems("0.2.1+build.1", "0.2.1", notes), "has build metadata");
        assertOneProblem(bumpProblems("0.2.1", null, notes), "has no released version");
    }

    // A planted mistake gives exactly one problem, and it says what to fix
    private static void assertOneProblem(List<String> problems, String says) {
        assertEquals("expected one problem saying \"" + says + "\", got " + problems, 1, problems.size());
        assertTrue("expected a problem saying \"" + says + "\", got " + problems.get(0), problems.get(0).contains(says));
    }

    private static String replaced(String text, String from, String to) {
        assertTrue("the planted text has no " + from, text.contains(from));
        return text.replace(from, to);
    }

    private static String without(String text, String line) {
        return replaced(text, line + "\n", "");
    }

    private static String swap(String text, String a, String b) {
        return replaced(replaced(replaced(text, a, "\u0000"), b, a), "\u0000", b);
    }
}
