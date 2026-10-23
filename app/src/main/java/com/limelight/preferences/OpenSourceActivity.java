package com.limelight.preferences;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Typeface;
import android.os.Bundle;
import android.preference.Preference;
import android.preference.PreferenceCategory;
import android.preference.PreferenceFragment;
import android.preference.PreferenceScreen;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ScrollView;
import android.widget.TextView;

import com.limelight.R;
import com.limelight.ui.AboutCredits;
import com.limelight.ui.CoverGlowSettings;
import com.limelight.ui.OpenSourceNotices;
import com.limelight.utils.HelpLauncher;
import com.limelight.utils.UiHelper;

import java.io.IOException;

/**
 * Settings > About > Credits, or Open-source licences: a Cover Glow settings page with a section
 * pill per group. Tapping a licence shows its full text; tapping a credit opens the project.
 */
public class OpenSourceActivity extends Activity {
    public static final String EXTRA_PAGE = "Page";
    public static final String PAGE_LICENCES = "licences";
    public static final String PAGE_CREDITS = "credits";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        UiHelper.setLocale(this);
        setContentView(R.layout.activity_stream_settings);
        CoverGlowSettings.setUpHeader(this, getString(showsCredits() ?
                R.string.title_about_credits : R.string.title_about_licences));

        getFragmentManager().beginTransaction().replace(
                R.id.stream_settings, new PageFragment()
        ).commitAllowingStateLoss();

        UiHelper.notifyNewRootView(this);
    }

    boolean showsCredits() {
        return PAGE_CREDITS.equals(getIntent().getStringExtra(EXTRA_PAGE));
    }

    public static class PageFragment extends PreferenceFragment {
        @Override
        public void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);

            OpenSourceActivity activity = (OpenSourceActivity) getActivity();
            PreferenceScreen screen = getPreferenceManager().createPreferenceScreen(activity);
            setPreferenceScreen(screen);
            if (activity.showsCredits()) {
                addCredits(activity, screen);
            }
            else {
                addLicences(activity, screen);
            }
        }

        private static PreferenceCategory addSection(Context context, PreferenceScreen screen, int title) {
            PreferenceCategory category = new PreferenceCategory(context);
            category.setTitle(title);
            screen.addPreference(category);
            return category;
        }

        private void addLicences(Context context, PreferenceScreen screen) {
            for (int section : OpenSourceNotices.sections()) {
                PreferenceCategory category = addSection(context, screen, section);
                for (OpenSourceNotices.Component component : OpenSourceNotices.ALL) {
                    if (component.section != section) {
                        continue;
                    }
                    Preference item = new Preference(context);
                    item.setTitle(component.title());
                    item.setSummary(component.licence + " · " + component.notice);
                    item.setOnPreferenceClickListener(preference -> {
                        showLicence(component);
                        return true;
                    });
                    category.addPreference(item);
                }
            }
        }

        private void addCredits(Context context, PreferenceScreen screen) {
            for (int section : AboutCredits.sections()) {
                PreferenceCategory category = addSection(context, screen, section);
                for (AboutCredits.Credit credit : AboutCredits.ALL) {
                    if (credit.section != section) {
                        continue;
                    }
                    Preference item = new Preference(context);
                    item.setTitle(credit.project);
                    item.setSummary(credit.author + " · " + getString(credit.usedFor));
                    item.setOnPreferenceClickListener(preference -> {
                        HelpLauncher.launchUrl(getActivity(), credit.url);
                        return true;
                    });
                    category.addPreference(item);
                }
            }
        }

        // The full licence, scrollable and selectable, with a link to the project
        private void showLicence(OpenSourceNotices.Component component) {
            Activity activity = getActivity();
            if (activity == null) {
                return;
            }
            String text;
            try {
                text = OpenSourceNotices.readLicence(activity, component);
            }
            catch (IOException e) {
                text = getString(R.string.oss_licence_missing);
            }

            AlertDialog.Builder builder = new AlertDialog.Builder(activity);
            float d = getResources().getDisplayMetrics().density;
            TextView body = new TextView(builder.getContext());
            body.setText(text);
            body.setTypeface(Typeface.MONOSPACE);
            body.setTextSize(12);
            body.setTextIsSelectable(true);
            body.setPadding((int) (24 * d), (int) (8 * d), (int) (24 * d), (int) (8 * d));
            ScrollView scroll = new ScrollView(builder.getContext());
            scroll.addView(body);

            builder.setTitle(component.title())
                    .setView(scroll)
                    .setPositiveButton(android.R.string.ok, null)
                    .setNeutralButton(R.string.oss_project_page,
                            (dialog, which) -> HelpLauncher.launchUrl(activity, component.url))
                    .show();
        }

        @Override
        public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
            View view = super.onCreateView(inflater, container, savedInstanceState);
            UiHelper.applyStatusBarPadding(view);
            return view;
        }

        @Override
        public void onViewCreated(View view, Bundle savedInstanceState) {
            super.onViewCreated(view, savedInstanceState);
            CoverGlowSettings.attach(this, view);
        }
    }
}
