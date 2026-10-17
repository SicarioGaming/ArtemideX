package com.limelight.ui;

import android.content.Context;
import android.database.DataSetObserver;
import android.graphics.Typeface;
import android.preference.Preference;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.RelativeSizeSpan;
import android.text.style.StyleSpan;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ListAdapter;
import android.widget.ListView;
import android.widget.TextView;

import com.limelight.R;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * A short "Impact:" line under the settings that change latency, smoothness, picture quality or
 * battery use, so it's clear what turning one on or up costs or gains. Settings with no such
 * effect get no line.
 *
 * The line is added when each row is drawn rather than written into the summary, because list
 * settings rebuild their summary as plain text and some summaries change while the screen is open.
 */
public final class SettingsImpact {
    private static final int LABEL_COLOR = 0xFFC4B5FD;
    private static final int TEXT_COLOR = 0xFFCBD5E1;

    private static final Map<String, Integer> IMPACT = new HashMap<>();
    // Per-game settings use their own keys for the same settings
    private static final Map<String, String> ALIASES = new HashMap<>();

    static {
        IMPACT.put("list_resolution", R.string.impact_resolution);
        IMPACT.put("list_fps", R.string.impact_fps);
        IMPACT.put("seekbar_bitrate_kbps", R.string.impact_bitrate);
        IMPACT.put("frame_pacing", R.string.impact_frame_pacing);
        IMPACT.put("jitter_buffer", R.string.impact_jitter_buffer);
        IMPACT.put("vsync_mode", R.string.impact_vsync);
        IMPACT.put("checkbox_sync_lfr", R.string.impact_sync_lfr);
        IMPACT.put("checkbox_async_decode", R.string.impact_async_decode);
        IMPACT.put("checkbox_async_lfr", R.string.impact_async_lfr);
        IMPACT.put("checkbox_ultra_low_latency", R.string.impact_ultra_low_latency);
        IMPACT.put("checkbox_stretch_video", R.string.impact_none);
        IMPACT.put("list_audio_config", R.string.impact_surround);
        IMPACT.put("checkbox_enable_audiofx", R.string.impact_audiofx);
        IMPACT.put("checkbox_enable_microphone", R.string.impact_microphone);
        IMPACT.put("list_microphone_target", R.string.impact_microphone_target);
        IMPACT.put("list_audio_haptics", R.string.impact_audio_haptics);
        IMPACT.put("seekbar_deadzone", R.string.impact_deadzone);
        IMPACT.put("checkbox_prevent_packet_loss", R.string.impact_prevent_packet_loss);
        IMPACT.put("checkbox_reduce_refresh_rate", R.string.impact_reduce_refresh_rate);
        IMPACT.put("text_actual_display_refresh_rate", R.string.impact_actual_refresh_rate);
        IMPACT.put("video_format", R.string.impact_video_format);
        IMPACT.put("pyrowave_late_frames", R.string.impact_pyrowave_late_frames);
        IMPACT.put("video_renderer", R.string.impact_video_renderer);
        IMPACT.put("checkbox_custom_vulkan_driver", R.string.impact_custom_vulkan_driver);
        IMPACT.put("spatial_dithering", R.string.impact_spatial_dithering);
        IMPACT.put("checkbox_enable_hdr", R.string.impact_hdr);
        IMPACT.put("checkbox_full_range", R.string.impact_none);
        IMPACT.put("checkbox_enable_perf_overlay", R.string.impact_perf_overlay);

        ALIASES.put("pref_app_resolution", "list_resolution");
        ALIASES.put("text_app_fps", "list_fps");
        ALIASES.put("text_app_bitrate_kbps", "seekbar_bitrate_kbps");
        ALIASES.put("list_app_frame_pacing", "frame_pacing");
        ALIASES.put("text_app_actual_display_refresh_rate", "text_actual_display_refresh_rate");
        ALIASES.put("list_app_enable_hdr", "checkbox_enable_hdr");
        ALIASES.put("list_app_enable_perf_overlay", "checkbox_enable_perf_overlay");
    }

    private SettingsImpact() {}

    /** The impact line's text for a setting, or 0 when it has none. */
    public static int impactFor(String key) {
        if (key == null) {
            return 0;
        }
        String alias = ALIASES.get(key);
        if (alias != null) {
            key = alias;
        }
        else if (key.startsWith("game_")) {
            key = key.substring("game_".length());
        }
        Integer res = IMPACT.get(key);
        return res != null ? res : 0;
    }

    /** The summary with the impact line under it, or the summary unchanged. */
    public static CharSequence withImpact(Context context, CharSequence summary, int impactRes) {
        if (impactRes == 0) {
            return summary;
        }
        SpannableStringBuilder text = new SpannableStringBuilder();
        if (!TextUtils.isEmpty(summary)) {
            text.append(summary).append('\n');
            // A half-height blank line, so the impact line doesn't run into the description
            int gap = text.length();
            text.append('\n');
            text.setSpan(new RelativeSizeSpan(0.5f), gap, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        int start = text.length();
        text.append(context.getString(R.string.impact_label)).append(' ');
        text.setSpan(new StyleSpan(Typeface.BOLD), start, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        text.setSpan(new ForegroundColorSpan(LABEL_COLOR), start, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        int body = text.length();
        text.append(context.getString(impactRes));
        text.setSpan(new ForegroundColorSpan(TEXT_COLOR), body, text.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return text;
    }

    /**
     * Adds the impact lines to a settings list. Call once the fragment has bound its list (the
     * lines are only written in English, so other languages are left as they are).
     */
    public static void attach(ListView list) {
        Locale locale = list.getResources().getConfiguration().locale;
        if (locale == null || !"en".equals(locale.getLanguage())) {
            return;
        }
        ListAdapter adapter = list.getAdapter();
        if (adapter == null || adapter instanceof ImpactAdapter) {
            return;
        }
        // Swapping the adapter would jump back to the top, so keep the place
        int first = list.getFirstVisiblePosition();
        View top = list.getChildAt(0);
        int offset = top != null ? top.getTop() - list.getPaddingTop() : 0;
        list.setAdapter(new ImpactAdapter(adapter));
        list.setSelectionFromTop(first, offset);
    }

    // The fragment's own adapter, with each row's summary given its impact line as it's drawn.
    // Positions are the same, so the preference screen's clicks still find the right setting.
    static final class ImpactAdapter implements ListAdapter {
        private final ListAdapter inner;

        ImpactAdapter(ListAdapter inner) {
            this.inner = inner;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View view = inner.getView(position, convertView, parent);
            Object item = inner.getItem(position);
            if (item instanceof Preference && view != null) {
                Preference preference = (Preference) item;
                int impact = impactFor(preference.getKey());
                TextView summary = view.findViewById(android.R.id.summary);
                if (impact != 0 && summary != null) {
                    summary.setText(withImpact(view.getContext(), preference.getSummary(), impact));
                    summary.setVisibility(View.VISIBLE);
                    summary.setMaxLines(12);
                }
            }
            return view;
        }

        @Override public boolean areAllItemsEnabled() { return inner.areAllItemsEnabled(); }
        @Override public boolean isEnabled(int position) { return inner.isEnabled(position); }
        @Override public void registerDataSetObserver(DataSetObserver observer) { inner.registerDataSetObserver(observer); }
        @Override public void unregisterDataSetObserver(DataSetObserver observer) { inner.unregisterDataSetObserver(observer); }
        @Override public int getCount() { return inner.getCount(); }
        @Override public Object getItem(int position) { return inner.getItem(position); }
        @Override public long getItemId(int position) { return inner.getItemId(position); }
        @Override public boolean hasStableIds() { return inner.hasStableIds(); }
        @Override public int getItemViewType(int position) { return inner.getItemViewType(position); }
        @Override public int getViewTypeCount() { return inner.getViewTypeCount(); }
        @Override public boolean isEmpty() { return inner.isEmpty(); }
    }
}
