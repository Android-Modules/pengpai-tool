package io.github.dalong.hyperhome;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.WindowInsets;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

/** Shared native presentation, independent of each settings page's persistence. */
final class SettingsUi {
    static final int BLUE = 0xff287dff, TEXT = 0xff182235, MUTED = 0xff7a8496, BG = 0xfff3f5f9;
    final Activity owner;
    SettingsUi(Activity owner) { this.owner = owner; }

    LinearLayout page(String title, boolean back, Runnable goBack, Runnable restart) {
        LinearLayout root = vertical(); root.setBackgroundColor(BG);
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            var bars = insets.getInsets(WindowInsets.Type.systemBars());
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom); return insets;
        });
        LinearLayout toolbar = new LinearLayout(owner); toolbar.setGravity(Gravity.CENTER_VERTICAL);
        toolbar.setPadding(dp(12), dp(4), dp(12), dp(4));
        View leading = back ? iconButton(0, "返回", goBack) : new View(owner);
        toolbar.addView(leading, new LinearLayout.LayoutParams(dp(44), dp(44)));
        TextView caption = text(title, 16, TEXT); caption.setGravity(Gravity.CENTER);
        caption.setTypeface(null, Typeface.BOLD); caption.setAccessibilityHeading(true);
        toolbar.addView(caption, new LinearLayout.LayoutParams(0, -2, 1));
        toolbar.addView(iconButton(1, "运行状态", restart), new LinearLayout.LayoutParams(dp(44), dp(44)));
        root.addView(toolbar);
        ScrollView scroll = new ScrollView(owner); scroll.setFillViewport(true); scroll.setClipToPadding(false);
        scroll.setVerticalScrollBarEnabled(false);
        LinearLayout body = vertical(); body.setPadding(dp(18), dp(12), dp(18), dp(28)); scroll.addView(body);
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1)); owner.setContentView(root);
        return body;
    }

    TextView hero(LinearLayout parent, String title, String subtitle) {
        TextView heading = text(title, 27, TEXT); heading.setTypeface(null, Typeface.BOLD);
        heading.setAccessibilityHeading(true); heading.setPadding(dp(4), dp(8), 0, dp(6)); parent.addView(heading);
        TextView sub = text(subtitle, 12, MUTED); sub.setPadding(dp(4), 0, dp(4), dp(22)); parent.addView(sub);
        return sub;
    }

    TextView chip(LinearLayout parent, String label) {
        TextView view = text(label, 12, BLUE); view.setPadding(dp(12), dp(8), dp(12), dp(8));
        view.setBackground(background(0xffe8efff, 18));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-2, -2); params.bottomMargin = dp(22);
        parent.addView(view, params); return view;
    }

    LinearLayout card(LinearLayout parent) {
        LinearLayout card = vertical(); card.setPadding(dp(16), dp(3), dp(16), dp(5));
        card.setBackground(background(Color.WHITE, 18));
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2); params.bottomMargin = dp(12);
        parent.addView(card, params); return card;
    }

    void section(LinearLayout parent, String title) {
        if (title.isEmpty()) return;
        TextView label = text(title, 12, MUTED); label.setTypeface(null, Typeface.BOLD);
        label.setPadding(dp(6), dp(15), 0, dp(9)); label.setAccessibilityHeading(true); parent.addView(label);
    }

    LinearLayout row(LinearLayout parent) {
        LinearLayout row = new LinearLayout(owner); row.setGravity(Gravity.CENTER_VERTICAL);
        row.setMinimumHeight(dp(58)); row.setPadding(0, dp(13), 0, dp(13));
        parent.addView(row, new LinearLayout.LayoutParams(-1, -2)); return row;
    }

    void menu(LinearLayout parent, String title, String subtitle, int icon, Runnable action) {
        LinearLayout row = row(parent);
        if (icon >= 0) {
            ImageView badge = new ImageView(owner); badge.setImageDrawable(new Symbol(icon, Color.WHITE));
            badge.setPadding(dp(8), dp(8), dp(8), dp(8));
            int color = switch (icon) {
                case 3 -> 0xff7969f5; case 5 -> 0xff2ba68d; case 6 -> 0xff677be8;
                case 7 -> 0xffe9a03b; case 8 -> 0xff36a4bd; case 9 -> 0xff748399; default -> BLUE;
            };
            badge.setBackground(background(color, 11));
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(38), dp(38)); p.setMarginEnd(dp(12)); row.addView(badge, p);
        }
        LinearLayout labels = vertical(); TextView heading = text(title, 15, TEXT);
        if (icon >= 0) heading.setTypeface(null, Typeface.BOLD);
        labels.addView(heading);
        if (!subtitle.isEmpty()) {
            TextView sub = text(subtitle, 12, MUTED); sub.setPadding(0, dp(4), dp(8), 0); labels.addView(sub);
        }
        row.addView(labels, new LinearLayout.LayoutParams(0, -2, 1));
        ImageView chevron = new ImageView(owner); chevron.setImageDrawable(new Symbol(4, MUTED));
        row.addView(chevron, new LinearLayout.LayoutParams(dp(18), dp(18)));
        row.setBackground(ripple()); row.setOnClickListener(v -> action.run());
    }

    /** Capability notice with no click listener, preference control, or navigation affordance. */
    void unavailable(LinearLayout parent, String title, String reason) {
        LinearLayout line = row(parent), labels = vertical();
        TextView heading = text(title + " · 已禁用", 14, MUTED);
        TextView detail = text(reason, 11, MUTED); detail.setPadding(0, dp(5), 0, 0);
        labels.addView(heading); labels.addView(detail); line.addView(labels, new LinearLayout.LayoutParams(-1, -2));
        heading.setEnabled(false); detail.setEnabled(false); labels.setEnabled(false); line.setEnabled(false);
        line.setContentDescription(title + "，已禁用。" + reason);
    }

    TextView text(String value, int size, int color) {
        TextView view = new TextView(owner); view.setText(value); view.setTextSize(size); view.setTextColor(color);
        view.setFontFeatureSettings("tnum"); view.setBreakStrategy(android.graphics.text.LineBreaker.BREAK_STRATEGY_SIMPLE);
        return view;
    }
    LinearLayout vertical() { LinearLayout view = new LinearLayout(owner); view.setOrientation(LinearLayout.VERTICAL); return view; }
    GradientDrawable background(int color, int radius) {
        GradientDrawable bg = new GradientDrawable(); bg.setColor(color); bg.setCornerRadius(dp(radius)); return bg;
    }
    Drawable ripple() { return new RippleDrawable(ColorStateList.valueOf(0x10287dff), null, background(Color.WHITE, 10)); }
    int dp(int value) { return Math.round(value * owner.getResources().getDisplayMetrics().density); }
    /** Reveal a search result without changing its value or opening an editor. */
    static void reveal(View target) {
        if (target == null) return;
        target.post(() -> {
            if (!target.isAttachedToWindow()) return;
            target.requestRectangleOnScreen(new android.graphics.Rect(0, 0, target.getWidth(), target.getHeight()), true);
            Drawable previous = target.getBackground();
            target.setBackgroundColor(0xffe8efff);
            target.postDelayed(() -> { if (target.isAttachedToWindow()) target.setBackground(previous); }, 1600);
        });
    }
    private View iconButton(int icon, String description, Runnable action) {
        ImageView view = new ImageView(owner); view.setPadding(dp(11), dp(11), dp(11), dp(11));
        view.setImageDrawable(new Symbol(icon, TEXT)); view.setContentDescription(description);
        view.setBackground(ripple()); view.setOnClickListener(v -> action.run()); return view;
    }

    /** Deterministic vector marks; no bitmap artwork or font-dependent arrow glyphs. */
    private static final class Symbol extends Drawable {
        private final int icon; private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        Symbol(int icon, int color) { this.icon = icon; paint.setColor(color); paint.setStyle(Paint.Style.STROKE); paint.setStrokeCap(Paint.Cap.ROUND); paint.setStrokeJoin(Paint.Join.ROUND); }
        @Override public void draw(Canvas canvas) {
            canvas.save(); canvas.translate(getBounds().left, getBounds().top);
            canvas.scale(getBounds().width() / 24f, getBounds().height() / 24f); paint.setStrokeWidth(1.8f);
            Path p = new Path();
            switch (icon) {
                case 0 -> { p.moveTo(15, 5); p.lineTo(8, 12); p.lineTo(15, 19); }
                case 1 -> { canvas.drawArc(4, 4, 20, 20, -45, 290, false, paint); p.moveTo(20, 4); p.lineTo(20, 10); p.lineTo(14, 10); }
                case 2 -> { for (int x : new int[]{3, 14}) for (int y : new int[]{3, 14}) canvas.drawRoundRect(x, y, x + 7, y + 7, 1.5f, 1.5f, paint); }
                case 3 -> { canvas.drawRoundRect(3, 5, 21, 19, 3, 3, paint); canvas.drawLine(3, 10, 21, 10, paint); canvas.drawCircle(7, 7.5f, .3f, paint); canvas.drawLine(8, 15, 16, 15, paint); }
                case 5 -> { p.moveTo(4, 4); p.lineTo(20, 4); p.lineTo(14, 11); p.lineTo(14, 18); p.lineTo(10, 20); p.lineTo(10, 11); p.close(); }
                case 6 -> { canvas.drawRoundRect(3, 4, 21, 17, 2, 2, paint); canvas.drawLine(8, 21, 16, 21, paint); canvas.drawLine(12, 17, 12, 21, paint); canvas.drawCircle(12, 10.5f, 3, paint); }
                case 7 -> { canvas.drawRoundRect(3, 6, 19, 18, 2, 2, paint); canvas.drawLine(22, 10, 22, 14, paint); p.moveTo(12, 8); p.lineTo(8, 13); p.lineTo(13, 13); p.lineTo(10, 16); }
                case 8 -> { canvas.drawCircle(12, 12, 3, paint); for (int angle = 0; angle < 360; angle += 60) { canvas.save(); canvas.rotate(angle, 12, 12); canvas.drawLine(12, 3, 12, 5, paint); canvas.restore(); } canvas.drawCircle(12, 12, 7, paint); }
                case 9 -> { canvas.drawRoundRect(5, 3, 19, 21, 2, 2, paint); for (int y : new int[]{8, 12, 16}) canvas.drawLine(9, y, 15, y, paint); }
                case 10 -> { canvas.drawCircle(12, 12, 9, paint); canvas.drawLine(12, 11, 12, 17, paint); canvas.drawCircle(12, 7, .4f, paint); }
                default -> { p.moveTo(9, 5); p.lineTo(16, 12); p.lineTo(9, 19); }
            }
            canvas.drawPath(p, paint); canvas.restore();
        }
        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
        @Override public void setColorFilter(android.graphics.ColorFilter filter) { paint.setColorFilter(filter); }
        @Override public int getOpacity() { return android.graphics.PixelFormat.TRANSLUCENT; }
    }
}
