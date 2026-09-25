package com.pokeemerald.experimental;

import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.os.Bundle;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.Display;
import android.view.DisplayCutout;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;

import java.util.Arrays;

import org.libsdl.app.SDLActivity;

public class PokeEmeraldActivity extends SDLActivity {
    private static final long SNAPSHOT_INTERVAL_MS = 120;

    private DualScreenPresentation presentation;
    private DualScreenView inlineBottomView;
    private final Handler snapshotHandler = new Handler(Looper.getMainLooper());
    private final Runnable snapshotPump = new Runnable() {
        @Override
        public void run() {
            // Self-heal: the Thor's system UI can steal the bottom display and
            // dismiss the presentation; re-show it whenever it is gone.
            if (presentation == null || !presentation.isShowing()) {
                presentation = null;
                showBottomScreen();
            }
            String json = DualScreenBridge.nativeGetSnapshotJson();
            if (presentation != null && presentation.isShowing()) {
                presentation.updateState(DualScreenState.parse(json));
            } else if (inlineBottomView != null) {
                inlineBottomView.setState(DualScreenState.parse(json));
            }
            // The overlay paints letterbox bars from the live setting. On a
            // release cold start DualScreen_FillAssets runs before the config
            // is read, so the first draw sees widescreen=0 and those bars
            // stick until something invalidates this view.
            if (controls != null) {
                controls.postInvalidate();
            }
            snapshotHandler.postDelayed(this, SNAPSHOT_INTERVAL_MS);
        }
    };

    private GbaControlsView controls;

    // Button presses for an open battle takeover panel. The native side
    // queues them off the game's own input; this just hands them over,
    // faster than the snapshot pump so the panel keeps up with a held d-pad.
    private static final long NAV_INTERVAL_MS = 33;
    private final Handler navHandler = new Handler(Looper.getMainLooper());
    private final Runnable navPump = new Runnable() {
        @Override
        public void run() {
            int action;
            while ((action = DualScreenBridge.nativeDrainNavKey()) >= 0) {
                if (presentation != null && presentation.isShowing()) {
                    presentation.navigate(action);
                } else if (inlineBottomView != null) {
                    inlineBottomView.navigate(action);
                }
            }
            navHandler.postDelayed(this, NAV_INTERVAL_MS);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // Hide the bars before the first layout so SDL's SurfaceView is
        // sized to the full display, not inset and then resized.
        applyImmersiveFlags();

        DisplayManager displayManager = (DisplayManager) getSystemService(DISPLAY_SERVICE);
        Display[] displays = displayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION);

        controls = new GbaControlsView(this);

        if (displays.length == 0) {
            inlineBottomView = new DualScreenView(this);
            inlineBottomView.setSettingsListener(() -> {
                if (controls != null) {
                    controls.postInvalidate();
                }
            });

            mLayout.post(() -> {
                int width = mLayout.getWidth();
                int height = mLayout.getHeight();
                if (width > 0 && height > 0) {
                    mLayout.setBackgroundColor(android.graphics.Color.BLACK);

                    float density = getResources().getDisplayMetrics().density;
                    int topMargin = 0;
                    int bottomMargin = 0;

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                        WindowInsets insets = mLayout.getRootWindowInsets();
                        if (insets != null) {
                            DisplayCutout cutout = insets.getDisplayCutout();
                            if (cutout != null) {
                                topMargin = cutout.getSafeInsetTop();
                                bottomMargin = cutout.getSafeInsetBottom();
                            }
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                                android.graphics.Insets navInsets = insets.getInsetsIgnoringVisibility(WindowInsets.Type.navigationBars());
                                bottomMargin = Math.max(bottomMargin, navInsets.bottom);
                                android.graphics.Insets statusInsets = insets.getInsetsIgnoringVisibility(WindowInsets.Type.statusBars());
                                topMargin = Math.max(topMargin, statusInsets.top);
                            }
                        }
                    }

                    // Fallback / minimum safety margin for devices with curved corners
                    int minTopMargin = Math.round(48 * density);
                    int minBottomMargin = Math.round(28 * density);
                    topMargin = Math.max(topMargin, minTopMargin);
                    bottomMargin = Math.max(bottomMargin, minBottomMargin);

                    int usableHeight = height - topMargin - bottomMargin;
                    // GBA display is 240x160 (3:2 aspect ratio).
                    // Size the top screen to exactly match the 3:2 aspect ratio so
                    // the game fills the entire top frame without black letterbox bars.
                    int topHeight = (width * 160) / 240;
                    if (topHeight > usableHeight * 3 / 5) {
                        topHeight = usableHeight / 2;
                    }
                    int bottomHeight = usableHeight - topHeight;

                    if (mSurface != null) {
                        android.widget.RelativeLayout.LayoutParams gameParams =
                                new android.widget.RelativeLayout.LayoutParams(width, topHeight);
                        gameParams.topMargin = topMargin;
                        gameParams.addRule(android.widget.RelativeLayout.ALIGN_PARENT_TOP);
                        mSurface.setLayoutParams(gameParams);
                    }

                    if (controls != null) {
                        android.widget.RelativeLayout.LayoutParams controlsParams =
                                new android.widget.RelativeLayout.LayoutParams(width, topHeight);
                        controlsParams.topMargin = topMargin;
                        controlsParams.addRule(android.widget.RelativeLayout.ALIGN_PARENT_TOP);
                        controls.setLayoutParams(controlsParams);
                    }

                    android.widget.RelativeLayout.LayoutParams bottomParams =
                            new android.widget.RelativeLayout.LayoutParams(width, bottomHeight);
                    bottomParams.bottomMargin = bottomMargin;
                    bottomParams.addRule(android.widget.RelativeLayout.ALIGN_PARENT_BOTTOM);
                    mLayout.addView(inlineBottomView, bottomParams);
                }
            });

            mLayout.addView(controls);
        } else {
            mLayout.addView(controls, new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        showBottomScreen();
        snapshotHandler.removeCallbacks(snapshotPump);
        snapshotHandler.postDelayed(snapshotPump, SNAPSHOT_INTERVAL_MS);
        navHandler.removeCallbacks(navPump);
        navHandler.postDelayed(navPump, NAV_INTERVAL_MS);
    }

    @Override
    protected void onPause() {
        snapshotHandler.removeCallbacks(snapshotPump);
        navHandler.removeCallbacks(navPump);
        dismissBottomScreen();
        super.onPause();
    }

    private void applyImmersiveFlags() {
        Window window = getWindow();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // setSystemUiVisibility is deprecated from API 30 and does not stop
            // the decor insetting the content here, which is what shrank the
            // SurfaceView. setDecorFitsSystemWindows(false) is the call that
            // actually gives the content the whole window.
            window.setDecorFitsSystemWindows(false);
            WindowInsetsController controller = window.getInsetsController();
            if (controller != null) {
                controller.hide(WindowInsets.Type.systemBars());
                controller.setSystemBarsBehavior(
                        WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            window.getDecorView().setSystemUiVisibility(
                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                    | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                    | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        }
    }

    private void showBottomScreen() {
        if (presentation != null && presentation.isShowing()) {
            return;
        }
        DisplayManager displayManager = (DisplayManager) getSystemService(DISPLAY_SERVICE);
        Display[] displays = displayManager.getDisplays(DisplayManager.DISPLAY_CATEGORY_PRESENTATION);
        if (displays.length == 0) {
            return; // Single-display device; game stays fullscreen.
        }
        presentation = new DualScreenPresentation(this, displays[0]);
        presentation.setSettingsListener(() -> {
            if (controls != null) {
                controls.postInvalidate();
            }
        });
        presentation.getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        try {
            presentation.show();
        } catch (WindowManager.InvalidDisplayException e) {
            presentation = null;
        }
    }

    private void dismissBottomScreen() {
        if (presentation != null) {
            presentation.dismiss();
            presentation = null;
        }
    }

    @Override
    public void setOrientationBis(int width, int height, boolean resizable, String hint) {
        // The manifest already keeps this activity in sensor landscape mode.
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (!hasFocus) {
            return;
        }

        // Re-applied on every focus gain because IMMERSIVE_STICKY only hides
        // the bars again after the user swipes them back in.
        applyImmersiveFlags();

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && mSurface != null) {
            mSurface.post(() -> {
                int width = mSurface.getWidth();
                int height = mSurface.getHeight();
                mSurface.setSystemGestureExclusionRects(Arrays.asList(
                        new Rect(0, height / 2, width / 5, height),
                        new Rect(width * 4 / 5, height / 2, width, height)));
            });
        }
    }

    @Override
    protected String[] getLibraries() {
        return new String[] { "SDL2", "main" };
    }
}
