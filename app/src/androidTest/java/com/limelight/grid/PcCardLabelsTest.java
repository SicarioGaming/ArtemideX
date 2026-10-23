package com.limelight.grid;

import static org.junit.Assert.assertEquals;

import android.content.Context;
import android.view.ContextThemeWrapper;
import android.view.View;
import android.widget.FrameLayout;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.limelight.PcView;
import com.limelight.R;
import com.limelight.nvstream.http.ComputerDetails;
import com.limelight.nvstream.http.PairingManager;
import com.limelight.preferences.PreferenceConfiguration;

import org.junit.Test;
import org.junit.runner.RunWith;

/** TalkBack reads each PC card's name and state, which the card only shows with icons. */
@RunWith(AndroidJUnit4.class)
public class PcCardLabelsTest {
    private static PcView.ComputerObject pc(String name, ComputerDetails.State state,
                                            PairingManager.PairState pairState) {
        ComputerDetails details = new ComputerDetails();
        details.name = name;
        details.state = state;
        details.pairState = pairState;
        return new PcView.ComputerObject(details);
    }

    @Test
    public void pcCardsSayTheirState() {
        Context target = InstrumentationRegistry.getInstrumentation().getTargetContext();
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> {
            Context context = new ContextThemeWrapper(target, R.style.AppTheme);
            PcGridAdapter adapter = new PcGridAdapter(context, PreferenceConfiguration.readPreferences(context));
            // Sorted by name, so they come back in this order
            adapter.addComputer(pc("A-PC", ComputerDetails.State.ONLINE, PairingManager.PairState.PAIRED));
            adapter.addComputer(pc("B-PC", ComputerDetails.State.ONLINE, PairingManager.PairState.NOT_PAIRED));
            adapter.addComputer(pc("C-PC", ComputerDetails.State.OFFLINE, PairingManager.PairState.PAIRED));
            adapter.addComputer(pc("D-PC", ComputerDetails.State.UNKNOWN, null));

            String[] expected = {
                    "A-PC, " + context.getString(R.string.pcview_menu_header_online),
                    "B-PC, " + context.getString(R.string.scut_not_paired),
                    "C-PC, " + context.getString(R.string.pcview_menu_header_offline),
                    "D-PC, " + context.getString(R.string.pcview_menu_header_unknown),
            };
            FrameLayout parent = new FrameLayout(context);
            for (int i = 0; i < expected.length; i++) {
                View card = adapter.getView(i, null, parent);
                assertEquals(expected[i], card.getContentDescription().toString());
                assertEquals(View.IMPORTANT_FOR_ACCESSIBILITY_NO,
                        card.findViewById(R.id.grid_text).getImportantForAccessibility());
            }
        });
    }
}
