package com.limelight.devicecheck;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import android.content.Context;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.limelight.computers.ComputerDatabaseManager;
import com.limelight.utils.SessionResumeManager;

import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * device-test.sh --monkey runs this first (with -e starwire.monkeyCheck true), and sends random
 * input only if it passes. It fails when the app knows any PC, or has a stream waiting to
 * resume, so random taps can't stream to, pair with, wake or delete a real PC. In an ordinary
 * run it skips, so a device that is paired on purpose doesn't fail the suite.
 */
@RunWith(AndroidJUnit4.class)
public class MonkeyPreconditionTest {
    @Test
    public void noSavedPc() {
        Assume.assumeTrue("only for device-test.sh --monkey",
                "true".equals(InstrumentationRegistry.getArguments().getString("starwire.monkeyCheck")));
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        ComputerDatabaseManager db = new ComputerDatabaseManager(target);
        try {
            assertEquals("PCs the app knows", 0, db.getAllComputers().size());
        }
        finally {
            db.close();
        }
        assertFalse("a stream is waiting to resume", SessionResumeManager.hasPendingSession(target));
    }
}
