package com.limelight.ui;

import android.app.Activity;
import android.graphics.Typeface;
import android.graphics.drawable.ColorDrawable;
import android.preference.Preference;
import android.preference.PreferenceCategory;
import android.preference.PreferenceFragment;
import android.preference.PreferenceScreen;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AbsListView;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ListAdapter;
import android.widget.ListView;
import android.widget.TextView;

import com.limelight.R;

import java.util.ArrayList;
import java.util.List;

/**
 * Cover Glow settings pages: the back button and title, and a pill per section above the list.
 * Tapping a pill scrolls to that section; scrolling the list moves the highlight along.
 */
public final class CoverGlowSettings {
    private static final int PILL_TEXT = 0xFFE2E8F0;
    private static final int PILL_TEXT_SELECTED = 0xFF0B0F14;

    private CoverGlowSettings() {}

    /** Call after setContentView. A null title keeps "Settings". */
    public static void setUpHeader(Activity activity, CharSequence title) {
        if (title != null) {
            ((TextView) activity.findViewById(R.id.settingsTitle)).setText(title);
        }
        activity.findViewById(R.id.settingsBack).setOnClickListener(v -> activity.onBackPressed());
    }

    /** Call from the fragment's onViewCreated, once its preferences are added. */
    public static void attach(PreferenceFragment fragment, View fragmentView) {
        Activity activity = fragment.getActivity();
        LinearLayout pills = activity.findViewById(R.id.settingsSections);
        HorizontalScrollView pillScroll = activity.findViewById(R.id.settingsSectionsScroll);
        ListView list = fragmentView.findViewById(android.R.id.list);
        if (pills == null || list == null) {
            return;
        }
        float d = activity.getResources().getDisplayMetrics().density;

        list.setDivider(new ColorDrawable(0x1AFFFFFF));
        list.setDividerHeight(Math.max(1, Math.round(d)));
        list.setClipToPadding(false);

        List<PreferenceCategory> sections = new ArrayList<>();
        PreferenceScreen screen = fragment.getPreferenceScreen();
        for (int i = 0; screen != null && i < screen.getPreferenceCount(); i++) {
            Preference preference = screen.getPreference(i);
            if (preference instanceof PreferenceCategory && preference.getTitle() != null) {
                sections.add((PreferenceCategory) preference);
            }
        }

        pills.removeAllViews();
        if (sections.size() < 2) {
            pillScroll.setVisibility(View.GONE);
            return;
        }
        pillScroll.setVisibility(View.VISIBLE);

        Sections state = new Sections(sections, pills, pillScroll, list);
        for (int i = 0; i < sections.size(); i++) {
            TextView pill = new TextView(activity);
            pill.setText(shortName(sections.get(i).getTitle()));
            pill.setTextSize(15);
            pill.setGravity(Gravity.CENTER);
            pill.setMinHeight((int) (44 * d));
            pill.setPadding((int) (18 * d), 0, (int) (18 * d), 0);
            pill.setFocusable(true);
            final int index = i;
            pill.setOnClickListener(v -> state.jumpTo(index));
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            params.setMarginEnd((int) (8 * d));
            pills.addView(pill, params);
        }
        state.highlight(0);

        list.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(AbsListView view, int scrollState) {
                if (scrollState == SCROLL_STATE_TOUCH_SCROLL) {
                    state.pinned = -1;
                }
            }

            @Override
            public void onScroll(AbsListView view, int firstVisible, int visibleCount, int totalCount) {
                state.follow(firstVisible);
            }
        });
    }

    /** "Audio Settings" reads as "Audio" on a pill. */
    static String shortName(CharSequence title) {
        String name = title.toString().trim();
        String suffix = " settings";
        if (name.length() > suffix.length()
                && name.toLowerCase().endsWith(suffix)) {
            name = name.substring(0, name.length() - suffix.length());
        }
        return name;
    }

    private static final class Sections {
        final List<PreferenceCategory> sections;
        final LinearLayout pills;
        final HorizontalScrollView pillScroll;
        final ListView list;
        int selected = -1;
        // A tapped section stays highlighted until the list is dragged, even if it is too
        // near the end to reach the top
        int pinned = -1;

        Sections(List<PreferenceCategory> sections, LinearLayout pills,
                 HorizontalScrollView pillScroll, ListView list) {
            this.sections = sections;
            this.pills = pills;
            this.pillScroll = pillScroll;
            this.list = list;
        }

        int positionOf(PreferenceCategory section) {
            ListAdapter adapter = list.getAdapter();
            for (int i = 0; adapter != null && i < adapter.getCount(); i++) {
                if (adapter.getItem(i) == section) {
                    return i;
                }
            }
            return -1;
        }

        void jumpTo(int index) {
            int position = positionOf(sections.get(index));
            if (position < 0) {
                return;
            }
            pinned = index;
            highlight(index);
            list.smoothScrollToPositionFromTop(position, 0, 300);
        }

        void follow(int firstVisible) {
            if (pinned >= 0) {
                highlight(pinned);
                return;
            }
            int current = 0;
            for (int i = 0; i < sections.size(); i++) {
                int position = positionOf(sections.get(i));
                if (position >= 0 && position <= firstVisible) {
                    current = i;
                }
            }
            highlight(current);
        }

        void highlight(int index) {
            if (index == selected) {
                return;
            }
            selected = index;
            for (int i = 0; i < pills.getChildCount(); i++) {
                TextView pill = (TextView) pills.getChildAt(i);
                boolean on = i == index;
                pill.setBackgroundResource(on ? R.drawable.cg_pill_white : R.drawable.cg_pill_frosted);
                pill.setTextColor(on ? PILL_TEXT_SELECTED : PILL_TEXT);
                pill.setTypeface(on ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
                pill.setSelected(on);
            }
            View pill = pills.getChildAt(index);
            pill.post(() -> pillScroll.smoothScrollTo(
                    pill.getLeft() - (pillScroll.getWidth() - pill.getWidth()) / 2, 0));
        }
    }
}
