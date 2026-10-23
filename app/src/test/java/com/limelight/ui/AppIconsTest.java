package com.limelight.ui;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Cover Glow: which apps get an icon tile, and which kind and colour of tile. Drawing the tiles
 * is checked on the device (CoverGlowTest).
 */
public class AppIconsTest {
    @Test
    public void kindsByName() {
        assertEquals(AppIcons.Kind.DESKTOP, AppIcons.kindFor("Desktop"));
        assertEquals(AppIcons.Kind.DESKTOP, AppIcons.kindFor("Remote Desktop"));
        assertEquals(AppIcons.Kind.LAUNCHER, AppIcons.kindFor("Steam Big Picture"));
        assertEquals(AppIcons.Kind.LAUNCHER, AppIcons.kindFor("Xbox"));
        assertEquals(AppIcons.Kind.LIBRARY, AppIcons.kindFor("Playnite Fullscreen"));
        assertEquals(AppIcons.Kind.LIBRARY, AppIcons.kindFor("Epic Games Launcher"));
        assertEquals(AppIcons.Kind.RETRO, AppIcons.kindFor("RetroArch"));
        assertEquals(AppIcons.Kind.RETRO, AppIcons.kindFor("ES-DE"));
        assertEquals(AppIcons.Kind.MEDIA, AppIcons.kindFor("YouTube"));
        assertEquals(AppIcons.Kind.TERMINAL, AppIcons.kindFor("PowerShell"));
        assertEquals(AppIcons.Kind.GAME, AppIcons.kindFor("Elden Ring"));
        assertEquals(AppIcons.Kind.GAME, AppIcons.kindFor(""));
        assertEquals(AppIcons.Kind.GAME, AppIcons.kindFor(null));
    }

    // Desktop is checked before the launchers, so "Steam Desktop" is a desktop
    @Test
    public void theFirstMatchingKindWins() {
        assertEquals(AppIcons.Kind.DESKTOP, AppIcons.kindFor("Steam Desktop"));
        assertEquals(AppIcons.Kind.LAUNCHER, AppIcons.kindFor("Steam Library"));
    }

    // Only the apps hosts add themselves, by their exact names: games with the same words keep their covers
    @Test
    public void onlyBuiltInAppsHaveNoCoverArt() {
        for (String name : new String[] {"Desktop", "desktop", " Desktop ", "Virtual Desktop", "Steam Big Picture",
                "Big Picture", "Steam", "Playnite", "Playnite Fullscreen", "Terminal", "PowerShell"}) {
            assertTrue(name, AppIcons.hasNoCoverArt(name));
        }
        for (String name : new String[] {"Desktop Dungeons", "Steamworld Dig", "Terminal Velocity", "Elden Ring", "", null}) {
            assertFalse(String.valueOf(name), AppIcons.hasNoCoverArt(name));
        }
    }

    @Test
    public void accentColours() {
        assertEquals(AppIcons.Kind.DESKTOP.accent, AppIcons.accentFor("Desktop"));
        // A game keeps its colour whatever the case, and it's opaque
        assertEquals(AppIcons.accentFor("Elden Ring"), AppIcons.accentFor("ELDEN RING"));
        assertEquals(0xFF, AppIcons.accentFor("Elden Ring") >>> 24);
        assertEquals(0xFF, AppIcons.accentFor(null) >>> 24);
        // Every kind but GAME has a colour of its own
        for (AppIcons.Kind kind : AppIcons.Kind.values()) {
            if (kind != AppIcons.Kind.GAME) {
                assertNotEquals(kind.name(), 0, kind.accent);
            }
        }
    }
}
