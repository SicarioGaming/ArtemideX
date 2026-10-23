package com.limelight.computers;

import com.limelight.LimeLog;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.PairingManager.PairState;

import org.xmlpull.v1.XmlPullParserException;

import java.io.IOException;

/**
 * Starwire: folds a poll's answer into what's known about a PC, so one odd answer can't unpair it.
 *
 * The poll asks a PC at all its addresses at once, every 1.5 s. On the test tablet that's the
 * PC's IPv4 and IPv6 address, so two HTTPS requests from this client reach the host at the same
 * moment, and to about a third of those Vibepollo answers over HTTPS, status 200, with
 * PairStatus 0 and Permission 0. Requests made one at a time all said paired. Taken as true,
 * that answer closed the games screen with "PC not paired" and showed the PC as not paired until
 * the next poll.
 *
 * So a PC that was paired is shown as not paired only when one more request, made on its own
 * right after, says so too. A PC that's really unpaired on the host says so to both.
 */
final class PairStateCheck {
    interface AskAgain {
        /** The pair state from one more serverinfo request to the PC, made on its own. */
        PairState pairState() throws IOException, XmlPullParserException;
    }

    private PairStateCheck() {
    }

    /**
     * Updates known with a poll's answer. askAgain runs only when the answer would turn a paired
     * PC into an unpaired one.
     */
    static void merge(ComputerDetails known, ComputerDetails polled, AskAgain askAgain) {
        if (wasPaired(known) && polled.pairState == PairState.NOT_PAIRED) {
            polled.pairState = confirmUnpaired(polled, askAgain);
        }
        known.update(polled);
    }

    // Paired at its last poll, or not polled yet since the app started and paired before: the
    // app keeps a PC's certificate from the day it paired. Without the second case, the first
    // poll after the app starts could show a paired PC as unpaired, and a tap on it then pairs.
    private static boolean wasPaired(ComputerDetails known) {
        return known.pairState == PairState.PAIRED || (known.pairState == null && known.serverCert != null);
    }

    private static PairState confirmUnpaired(ComputerDetails polled, AskAgain askAgain) {
        try {
            PairState again = askAgain.pairState();
            if (again == PairState.PAIRED) {
                LimeLog.warning(polled.name + " said it wasn't paired, then that it was; keeping it paired");
            }
            return again;
        } catch (IOException | XmlPullParserException e) {
            // Nothing to confirm it with: it stays paired, and the next poll asks again
            LimeLog.warning(polled.name + " said it wasn't paired and didn't answer again: " + e);
            return PairState.PAIRED;
        }
    }

    /**
     * For a PC found by mDNS that's saved already: its pinned certificate, for the poll that
     * follows, and what its own polls know about pairing. The first poll of a found PC goes
     * without a certificate, over HTTP, where the host always says it isn't paired.
     */
    static void takeSaved(ComputerDetails found, ComputerDetails saved) {
        found.serverCert = saved.serverCert;
        found.pairState = saved.pairState;
    }
}
