package local.pd2405.exttele.prototype;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.RelativeSizeSpan;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/** A compact focal-step overlay; it never owns a Camera2 session or changes device files. */
final class ExternalTelePanel {
    interface Listener {
        void onZoomStep(int step);
        void onClose();
    }

    private static final String[] LABELS = {"200", "400", "800", "1600"};
    private View panel;
    private final TextView[] steps = new TextView[LABELS.length];

    static Activity findActivity(Context context) {
        while (context instanceof ContextWrapper) {
            if (context instanceof Activity) return (Activity) context;
            Context next = ((ContextWrapper) context).getBaseContext();
            if (next == context) break;
            context = next;
        }
        return context instanceof Activity ? (Activity) context : null;
    }

    boolean isShowing() {
        return panel != null && panel.getParent() != null;
    }

    void show(Activity activity, Listener listener) {
        hide();
        ViewGroup decor = (ViewGroup) activity.getWindow().getDecorView();
        boolean landscape = activity.getResources().getConfiguration().orientation
                == Configuration.ORIENTATION_LANDSCAPE;
        LinearLayout body = new LinearLayout(activity);
        body.setOrientation(landscape ? LinearLayout.VERTICAL : LinearLayout.HORIZONTAL);
        body.setGravity(Gravity.CENTER);
        // The donor UI presents focal steps on a slim rail beside the preview,
        // not as a boxed dialog. This remains a visual approximation only.
        if (landscape) addClose(activity, body, listener, true);
        int[] order = landscape ? new int[]{3, 2, 1, 0} : new int[]{0, 1, 2, 3};
        for (int position = 0; position < order.length; position++) {
            final int step = order[position];
            TextView button = text(activity, LABELS[step], 12, Color.WHITE);
            button.setIncludeFontPadding(false);
            button.setContentDescription("長焦增距 " + LABELS[step] + " 毫米");
            button.setOnClickListener(v -> listener.onZoomStep(step));
            LinearLayout.LayoutParams item = new LinearLayout.LayoutParams(
                    dp(activity, landscape ? 50 : 54), dp(activity, landscape ? 46 : 44));
            body.addView(button, item);
            steps[step] = button;
            if (position < order.length - 1) {
                View tick = new View(activity);
                GradientDrawable dot = new GradientDrawable();
                dot.setColor(0x88FFFFFF);
                dot.setCornerRadius(dp(activity, 2));
                tick.setBackground(dot);
                LinearLayout.LayoutParams separator = new LinearLayout.LayoutParams(
                        dp(activity, 2), dp(activity, 2));
                separator.gravity = Gravity.CENTER;
                body.addView(tick, separator);
            }
        }
        if (!landscape) addClose(activity, body, listener, false);

        FrameLayout.LayoutParams placement = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT,
                landscape ? Gravity.END | Gravity.CENTER_VERTICAL
                        : Gravity.BOTTOM | Gravity.CENTER_HORIZONTAL);
        if (landscape) {
            int width = decor.getWidth() > 0 ? decor.getWidth()
                    : activity.getResources().getDisplayMetrics().widthPixels;
            placement.rightMargin = Math.round(width * 0.28f);
        } else {
            placement.bottomMargin = dp(activity, 165);
        }
        decor.addView(body, placement);
        panel = body;
        select(-1);
    }

    void select(int selected) {
        for (int i = 0; i < steps.length; i++) {
            TextView step = steps[i];
            if (step == null) continue;
            boolean active = i == selected;
            step.setTextColor(active ? 0xFFFFD66B : Color.WHITE);
            step.setTypeface(Typeface.DEFAULT, active ? Typeface.BOLD : Typeface.NORMAL);
            if (active) {
                SpannableString label = new SpannableString(LABELS[i] + "\nmm");
                label.setSpan(new RelativeSizeSpan(0.65f), LABELS[i].length() + 1,
                        label.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                step.setText(label);
                GradientDrawable pill = new GradientDrawable();
                pill.setColor(0xBB25252B);
                pill.setCornerRadius(dp(step.getContext(), 24));
                pill.setStroke(dp(step.getContext(), 1), 0x99E4C36D);
                step.setBackground(pill);
            } else {
                step.setText(LABELS[i]);
                step.setBackground(null);
            }
        }
    }

    private static void addClose(Activity activity, LinearLayout body,
                                 Listener listener, boolean landscape) {
        TextView close = text(activity, "×", 18, 0xCCFFFFFF);
        close.setContentDescription("關閉長焦增距介面");
        close.setOnClickListener(v -> listener.onClose());
        body.addView(close, new LinearLayout.LayoutParams(
                dp(activity, landscape ? 50 : 36), dp(activity, landscape ? 30 : 44)));
    }

    void hide() {
        if (panel != null && panel.getParent() instanceof ViewGroup) {
            ((ViewGroup) panel.getParent()).removeView(panel);
        }
        panel = null;
    }

    private static TextView text(Context context, String label, int sp, int color) {
        TextView view = new TextView(context);
        view.setText(label);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setGravity(Gravity.CENTER);
        return view;
    }

    private static int dp(Context context, int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }
}
