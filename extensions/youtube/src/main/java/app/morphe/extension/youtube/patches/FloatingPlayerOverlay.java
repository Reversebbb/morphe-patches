package app.morphe.extension.youtube.patches;

import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.Intent;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.SystemClock;
import android.provider.Settings;
import android.util.DisplayMetrics;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.SurfaceView;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;

/**
 * Overlay-based floating player ("fake PiP") for devices whose ROM does not implement
 * Android Picture-in-Picture.
 *
 * Lives in extensions/youtube/src/main/java/app/morphe/extension/youtube/patches/
 *
 * Called once from the patched Application.onCreate:
 *   FloatingPlayerOverlay.init(context)
 *
 * After that it is fully self-driving: it watches activity lifecycle, and when the
 * YouTube activity is paused while media is still playing, it moves the video surface
 * into a draggable overlay window.
 *
 * STATUS: never compiled. Treat every API call as "should work", not "verified".
 */
@SuppressWarnings("unused")
public final class FloatingPlayerOverlay {

    private static final String TAG = "MorpheFloatingPlayer";

    /** Ignore small surfaces (thumbnails, previews) - only pop out real video. */
    private static final double MIN_SURFACE_AREA_RATIO = 0.20;

    private static FloatingPlayerOverlay instance;

    private final Context context;
    private final WindowManager windowManager;

    private FrameLayout container;
    private View playerView;
    private ViewGroup originalParent;
    private int originalIndex = -1;
    private WindowManager.LayoutParams params;

    private int initialX, initialY;
    private float initialTouchX, initialTouchY;

    /* ------------------------------------------------------------------ */
    /* Init                                                                */
    /* ------------------------------------------------------------------ */

    /** Called from the patched Application.onCreate. Safe to call more than once. */
    public static void init(Context context) {
        if (context == null || instance != null) return;
        Context app = context.getApplicationContext();
        if (!(app instanceof Application)) return;

        ((Application) app).registerActivityLifecycleCallbacks(new LifecycleWatcher());
    }

    private static final class LifecycleWatcher implements Application.ActivityLifecycleCallbacks {
        @Override
        public void onActivityPaused(Activity activity) {
            if (activity == null || activity.isFinishing()) return;
            if (instance != null) return; // already floating
            if (!Settings.canDrawOverlays(activity)) return;
            if (!isMediaPlaying(activity)) return;

            View surface = findLargestSurfaceView(activity);
            if (surface == null) return;

            show(activity, surface);
        }

        @Override public void onActivityCreated(Activity a, Bundle b) { }
        @Override public void onActivityStarted(Activity a) { }
        @Override public void onActivityResumed(Activity a) { }
        @Override public void onActivityStopped(Activity a) { }
        @Override public void onActivitySaveInstanceState(Activity a, Bundle b) { }
        @Override public void onActivityDestroyed(Activity a) {
            // If the activity that owns the floating surface is going away, take the
            // overlay down too instead of leaking a dead window.
            if (instance != null) hide();
        }
    }

    private static boolean isMediaPlaying(Context context) {
        try {
            AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            return am != null && am.isMusicActive();
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Heuristic view lookup: the biggest SurfaceView on screen.
     *
     * Deliberately fingerprint-free so the patch does not break on every YouTube
     * update. If you later want the full player container (controls included),
     * replace this with a fingerprint on the player view inflation instead.
     */
    private static View findLargestSurfaceView(Activity activity) {
        try {
            View decor = activity.getWindow().getDecorView();
            DisplayMetrics dm = activity.getResources().getDisplayMetrics();
            double screenArea = (double) dm.widthPixels * dm.heightPixels;
            SurfaceView[] best = new SurfaceView[1];
            double[] bestArea = new double[1];

            walk(decor, v -> {
                if (!(v instanceof SurfaceView)) return;
                int w = v.getWidth();
                int h = v.getHeight();
                double area = (double) w * h;
                if (area > bestArea[0]) {
                    bestArea[0] = area;
                    best[0] = (SurfaceView) v;
                }
            });

            if (best[0] == null) return null;
            if (bestArea[0] < screenArea * MIN_SURFACE_AREA_RATIO) return null;
            return best[0];
        } catch (Throwable t) {
            return null;
        }
    }

    private interface ViewVisitor {
        void visit(View view);
    }

    private static void walk(View view, ViewVisitor visitor) {
        visitor.visit(view);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount(); i++) {
                walk(group.getChildAt(i), visitor);
            }
        }
    }

    /* ------------------------------------------------------------------ */
    /* Permission                                                          */
    /* ------------------------------------------------------------------ */

    public static boolean canDrawOverlays(Context context) {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M
                || Settings.canDrawOverlays(context);
    }

    public static void requestOverlayPermission(Context context) {
        if (canDrawOverlays(context)) return;
        Intent intent = new Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:" + context.getPackageName())
        );
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);
    }

    /* ------------------------------------------------------------------ */
    /* Show / hide                                                         */
    /* ------------------------------------------------------------------ */

    public static boolean show(Context context, View view) {
        if (context == null || view == null) return false;
        if (!canDrawOverlays(context)) {
            requestOverlayPermission(context);
            return false;
        }
        if (instance != null) instance.detach();
        instance = new FloatingPlayerOverlay(context);
        return instance.attach(view);
    }

    public static void hide() {
        if (instance == null) return;
        instance.detach();
        instance = null;
    }

    public static boolean isShowing() {
        return instance != null;
    }

    private FloatingPlayerOverlay(Context context) {
        this.context = context.getApplicationContext();
        this.windowManager = (WindowManager) this.context.getSystemService(Context.WINDOW_SERVICE);
    }

    private boolean attach(View view) {
        try {
            playerView = view;
            originalParent = (ViewGroup) view.getParent();
            if (originalParent != null) {
                originalIndex = originalParent.indexOfChild(view);
                originalParent.removeView(view);
            }

            container = new FrameLayout(context);
            container.setBackground(rounded(0xE6000000, 12f));
            container.addView(view, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));

            addControls(container);

            DisplayMetrics dm = context.getResources().getDisplayMetrics();
            int width = Math.round(dm.widthPixels * 0.58f);
            int height = Math.round(width * 9f / 16f);

            params = new WindowManager.LayoutParams(
                    width, height,
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                            ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                            : WindowManager.LayoutParams.TYPE_PHONE,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                            | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT);
            params.gravity = Gravity.TOP | Gravity.START;
            params.x = Math.max(0, dm.widthPixels - width - 16);
            params.y = Math.round(dm.heightPixels * 0.15f);

            container.setOnTouchListener(new DragListener());
            windowManager.addView(container, params);
            return true;
        } catch (Throwable t) {
            restoreView();
            return false;
        }
    }

    private void detach() {
        try {
            if (container != null) {
                windowManager.removeView(container);
                container.removeAllViews();
            }
        } catch (Throwable ignored) {
            // Window already detached by the system.
        } finally {
            container = null;
        }
        restoreView();
    }

    private void restoreView() {
        try {
            if (playerView == null) return;
            if (playerView.getParent() instanceof ViewGroup) {
                ((ViewGroup) playerView.getParent()).removeView(playerView);
            }
            if (originalParent != null) {
                if (originalIndex >= 0 && originalIndex <= originalParent.getChildCount()) {
                    originalParent.addView(playerView, originalIndex);
                } else {
                    originalParent.addView(playerView);
                }
            }
        } catch (Throwable ignored) {
            // Parent may be gone after a configuration change.
        } finally {
            playerView = null;
            originalParent = null;
            originalIndex = -1;
        }
    }

    /* ------------------------------------------------------------------ */
    /* Controls                                                            */
    /* ------------------------------------------------------------------ */

    private void addControls(FrameLayout parent) {
        LinearLayout bar = new LinearLayout(context);
        bar.setOrientation(LinearLayout.HORIZONTAL);

        ImageButton playPause = iconButton(android.R.drawable.ic_media_play);
        playPause.setOnClickListener(v -> sendMediaKey(KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE));

        ImageButton close = iconButton(android.R.drawable.ic_menu_close_clear_cancel);
        close.setOnClickListener(v -> hide());

        bar.addView(playPause);
        bar.addView(close);

        FrameLayout.LayoutParams lp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.gravity = Gravity.BOTTOM | Gravity.END;
        lp.bottomMargin = dp(4);
        lp.rightMargin = dp(4);
        parent.addView(bar, lp);
    }

    /**
     * Controls live in the player view we moved, but on many devices that view is
     * only the surface, so we drive playback through the media session instead.
     */
    private void sendMediaKey(int keyCode) {
        try {
            AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
            if (am == null) return;
            long now = SystemClock.uptimeMillis();
            am.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0));
            am.dispatchMediaKeyEvent(new KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0));
        } catch (Throwable ignored) {
        }
    }

    private ImageButton iconButton(int drawable) {
        ImageButton button = new ImageButton(context);
        button.setImageResource(drawable);
        button.setBackgroundColor(Color.TRANSPARENT);
        button.setAlpha(0.8f);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(34), dp(34));
        button.setLayoutParams(lp);
        return button;
    }

    private static GradientDrawable rounded(int color, float radiusDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(radiusDp);
        return d;
    }

    private int dp(float value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private final class DragListener implements View.OnTouchListener {
        @Override
        public boolean onTouch(View v, MotionEvent event) {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    initialX = params.x;
                    initialY = params.y;
                    initialTouchX = event.getRawX();
                    initialTouchY = event.getRawY();
                    return true;
                case MotionEvent.ACTION_MOVE:
                    params.x = initialX + Math.round(event.getRawX() - initialTouchX);
                    params.y = initialY + Math.round(event.getRawY() - initialTouchY);
                    try {
                        windowManager.updateViewLayout(container, params);
                    } catch (Throwable ignored) {
                    }
                    return true;
                default:
                    return false;
            }
        }
    }
          }
              
