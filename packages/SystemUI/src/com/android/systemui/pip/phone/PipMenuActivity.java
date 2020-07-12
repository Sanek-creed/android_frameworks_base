/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License
 */

package com.android.systemui.pip.phone;

import static android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK;
import static android.content.Intent.FLAG_ACTIVITY_NEW_TASK;
import static android.provider.Settings.ACTION_PICTURE_IN_PICTURE_SETTINGS;
import static android.view.accessibility.AccessibilityManager.FLAG_CONTENT_CONTROLS;
import static android.view.accessibility.AccessibilityManager.FLAG_CONTENT_ICONS;
import static android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK;

import static com.android.systemui.pip.phone.PipMenuActivityController.EXTRA_ACTIONS;
import static com.android.systemui.pip.phone.PipMenuActivityController.EXTRA_ALLOW_TIMEOUT;
import static com.android.systemui.pip.phone.PipMenuActivityController.EXTRA_CONTROLLER_MESSENGER;
import static com.android.systemui.pip.phone.PipMenuActivityController.EXTRA_DISMISS_FRACTION;
import static com.android.systemui.pip.phone.PipMenuActivityController.EXTRA_MENU_STATE;
import static com.android.systemui.pip.phone.PipMenuActivityController.EXTRA_SHOW_MENU_WITH_DELAY;
import static com.android.systemui.pip.phone.PipMenuActivityController.EXTRA_SHOW_RESIZE_HANDLE;
import static com.android.systemui.pip.phone.PipMenuActivityController.EXTRA_STACK_BOUNDS;
import static com.android.systemui.pip.phone.PipMenuActivityController.EXTRA_WILL_RESIZE_MENU;
import static com.android.systemui.pip.phone.PipMenuActivityController.MENU_STATE_CLOSE;
import static com.android.systemui.pip.phone.PipMenuActivityController.MENU_STATE_FULL;
import static com.android.systemui.pip.phone.PipMenuActivityController.MENU_STATE_NONE;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.AnimatorSet;
import android.animation.ObjectAnimator;
import android.animation.ValueAnimator;
import android.annotation.Nullable;
import android.app.Activity;
import android.app.ActivityManager;
import android.app.PendingIntent.CanceledException;
import android.app.RemoteAction;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.ParceledListSlice;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.Log;
import android.util.Pair;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager.LayoutParams;
import android.view.accessibility.AccessibilityManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;

import com.android.systemui.Interpolators;
import com.android.systemui.R;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Translucent activity that gets started on top of a task in PIP to allow the user to control it.
 */
public class PipMenuActivity extends Activity {

    private static final String TAG = "PipMenuActivity";

    private static final int MESSAGE_INVALID_TYPE = -1;

    public static final int MESSAGE_SHOW_MENU = 1;
    public static final int MESSAGE_POKE_MENU = 2;
    public static final int MESSAGE_HIDE_MENU = 3;
    public static final int MESSAGE_UPDATE_ACTIONS = 4;
    public static final int MESSAGE_UPDATE_DISMISS_FRACTION = 5;
    public static final int MESSAGE_ANIMATION_ENDED = 6;
    public static final int MESSAGE_POINTER_EVENT = 7;
    public static final int MESSAGE_MENU_EXPANDED = 8;
    public static final int MESSAGE_FADE_OUT_MENU = 9;

    private static final int INITIAL_DISMISS_DELAY = 3500;
    private static final int POST_INTERACTION_DISMISS_DELAY = 2000;
    private static final long MENU_FADE_DURATION = 125;
    private static final long MENU_SLOW_FADE_DURATION = 175;
    private static final long MENU_SHOW_ON_EXPAND_START_DELAY = 30;

    private static final float MENU_BACKGROUND_ALPHA = 0.3f;
    private static final float DISMISS_BACKGROUND_ALPHA = 0.6f;

    private static final float DISABLED_ACTION_ALPHA = 0.54f;

    private static final boolean ENABLE_RESIZE_HANDLE = false;

    private int mMenuState;
    private boolean mResize = true;
    private boolean mAllowMenuTimeout = true;
    private boolean mAllowTouches = true;

    private final List<RemoteAction> mActions = new ArrayList<>();

    private AccessibilityManager mAccessibilityManager;
    private View mViewRoot;
    private Drawable mBackgroundDrawable;
    private View mMenuContainer;
    private LinearLayout mActionsGroup;
    private View mSettingsButton;
    private View mDismissButton;
    private View mResizeHandle;
    private int mBetweenActionPaddingLand;

    private AnimatorSet mMenuContainerAnimator;

    private ValueAnimator.AnimatorUpdateListener mMenuBgUpdateListener =
            new ValueAnimator.AnimatorUpdateListener() {
                @Override
                public void onAnimationUpdate(ValueAnimator animation) {
                    final float alpha = (float) animation.getAnimatedValue();
                    mBackgroundDrawable.setAlpha((int) (MENU_BACKGROUND_ALPHA*alpha*255));
                }
            };

    private Handler mHandler = new Handler(Looper.getMainLooper()) {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MESSAGE_SHOW_MENU: {
                    final Bundle data = (Bundle) msg.obj;
                    showMenu(data.getInt(EXTRA_MENU_STATE),
                            data.getParcelable(EXTRA_STACK_BOUNDS),
                            data.getBoolean(EXTRA_ALLOW_TIMEOUT),
                            data.getBoolean(EXTRA_WILL_RESIZE_MENU),
                            data.getBoolean(EXTRA_SHOW_MENU_WITH_DELAY),
                            data.getBoolean(EXTRA_SHOW_RESIZE_HANDLE));
                    break;
                }
                case MESSAGE_POKE_MENU:
                    cancelDelayedFinish();
                    break;
                case MESSAGE_HIDE_MENU:
                    hideMenu((Runnable) msg.obj);
                    break;
                case MESSAGE_UPDATE_ACTIONS: {
                    final Bundle data = (Bundle) msg.obj;
                    final ParceledListSlice<RemoteAction> actions = data.getParcelable(
                            EXTRA_ACTIONS);
                    setActions(data.getParcelable(EXTRA_STACK_BOUNDS), actions != null
                            ? actions.getList() : Collections.emptyList());
                    break;
                }
                case MESSAGE_UPDATE_DISMISS_FRACTION: {
                    final Bundle data = (Bundle) msg.obj;
                    updateDismissFraction(data.getFloat(EXTRA_DISMISS_FRACTION));
                    break;
                }
                case MESSAGE_ANIMATION_ENDED: {
                    mAllowTouches = true;
                    break;
                }
                case MESSAGE_POINTER_EVENT: {
                    final MotionEvent ev = (MotionEvent) msg.obj;
                    dispatchPointerEvent(ev);
                    break;
                }
                case MESSAGE_MENU_EXPANDED : {
                    mMenuContainerAnimator.setStartDelay(MENU_SHOW_ON_EXPAND_START_DELAY);
                    mMenuContainerAnimator.start();
                    break;
                }
                case MESSAGE_FADE_OUT_MENU: {
                    fadeOutMenu();
                    break;
                }
            }
        }
    };
    private Messenger mToControllerMessenger;
    private Messenger mMessenger = new Messenger(mHandler);

    private final Runnable mFinishRunnable = this::hideMenu;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        // Set the flags to allow us to watch for outside touches and also hide the menu and start
        // manipulating the PIP in the same touch gesture
        getWindow().addFlags(LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH);

        super.onCreate(savedInstanceState);
        setContentView(R.layout.pip_menu_activity);

        mAccessibilityManager = getSystemService(AccessibilityManager.class);
        mBackgroundDrawable = new ColorDrawable(Color.BLACK);
        mBackgroundDrawable.setAlpha(0);
        mViewRoot = findViewById(R.id.background);
        mViewRoot.setBackground(mBackgroundDrawable);
        mMenuContainer = findViewById(R.id.menu_container);
        mMenuContainer.setAlpha(0);
        mSettingsButton = findViewById(R.id.settings);
        mSettingsButton.setAlpha(0);
        mSettingsButton.setOnClickListener((v) -> {
            if (v.getAlpha() != 0) {
                showSettings();
            }
        });
        mDismissButton = findViewById(R.id.dismiss);
        mDismissButton.setAlpha(0);
        mDismissButton.setOnClickListener(v -> dismissPip());
        findViewById(R.id.expand_button).setOnClickListener(v -> {
            if (mMenuContainer.getAlpha() != 0) {
                expandPip();
            }
        });
        mResizeHandle = findViewById(R.id.resize_handle);
        mResizeHandle.setAlpha(0);
        mActionsGroup = findViewById(R.id.actions_group);
        mBetweenActionPaddingLand = getResources().getDimensionPixelSize(
                R.dimen.pip_between_action_padding_land);

        updateFromIntent(getIntent());
        setTitle(R.string.pip_menu_title);
        setDisablePreviewScreenshots(true);

        // Hide without an animation.
        getWindow().setExitTransition(null);

        initAccessibility();
    }

    private void initAccessibility() {
        getWindow().getDecorView().setAccessibilityDelegate(new View.AccessibilityDelegate() {
            @Override
            public void onInitializeAccessibilityNodeInfo(View host, AccessibilityNodeInfo info) {
                super.onInitializeAccessibilityNodeInfo(host, info);
                String label = getResources().getString(R.string.pip_menu_title);
                info.addAction(new AccessibilityNodeInfo.AccessibilityAction(ACTION_CLICK, label));
            }

            @Override
            public boolean performAccessibilityAction(View host, int action, Bundle args) {
                if (action == ACTION_CLICK && mMenuState == MENU_STATE_CLOSE) {
                    Message m = Message.obtain();
                    m.what = PipMenuActivityController.MESSAGE_SHOW_MENU;
                    sendMessage(m, "Could not notify controller to show PIP menu");
                }
                return super.performAccessibilityAction(host, action, args);
            }
        });
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_ESCAPE) {
            hideMenu();
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        updateFromIntent(intent);
    }

    @Override
    public void onUserInteraction() {
        if (mAllowMenuTimeout) {
            repostDelayedFinish(POST_INTERACTION_DISMISS_DELAY);
        }
    }

    @Override
    protected void onUserLeaveHint() {
        super.onUserLeaveHint();

        // If another task is starting on top of the menu, then hide and finish it so that it can be
        // recreated on the top next time it starts
        hideMenu();
    }

    @Override
    public void onTopResumedActivityChanged(boolean isTopResumedActivity) {
        super.onTopResumedActivityChanged(isTopResumedActivity);
        if (!isTopResumedActivity && mMenuState != MENU_STATE_NONE) {
            hideMenu();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();

        // In cases such as device lock, hide and finish it so that it can be recreated on the top
        // next time it starts, see also {@link #onUserLeaveHint}
        hideMenu();
        cancelDelayedFinish();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();

        // Fallback, if we are destroyed for any other reason (like when the task is being reset),
        // also reset the callback.
        notifyActivityCallback(null);
    }

    @Override
    public void onPictureInPictureModeChanged(boolean isInPictureInPictureMode) {
        if (!isInPictureInPictureMode) {
            finish();
        }
    }

    /**
     * Dispatch a pointer event from {@link PipTouchHandler}.
     */
    private void dispatchPointerEvent(MotionEvent event) {
        if (event.isTouchEvent()) {
            dispatchTouchEvent(event);
        } else {
            dispatchGenericMotionEvent(event);
        }
    }

    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (!mAllowTouches) {
            return false;
        }

        // On the first action outside the window, hide the menu
        switch (ev.getAction()) {
            case MotionEvent.ACTION_OUTSIDE:
                hideMenu();
                return true;
        }
        return super.dispatchTouchEvent(ev);
    }

    @Override
    public void finish() {
        notifyActivityCallback(null);
        super.finish();
    }

    @Override
    public void setTaskDescription(ActivityManager.TaskDescription taskDescription) {
        // Do nothing
    }

    private void showMenu(int menuState, Rect stackBounds, boolean allowMenuTimeout,
            boolean resizeMenuOnShow, boolean withDelay, boolean showResizeHandle) {
        mAllowMenuTimeout = allowMenuTimeout;
        if (mMenuState != menuState) {
            // Disallow touches if the menu needs to resize while showing, and we are transitioning
            // to/from a full menu state.
            boolean disallowTouchesUntilAnimationEnd = resizeMenuOnShow &&
                    (mMenuState == MENU_STATE_FULL || menuState == MENU_STATE_FULL);
            mAllowTouches = !disallowTouchesUntilAnimationEnd;
            cancelDelayedFinish();
            updateActionViews(stackBounds);
            if (mMenuContainerAnimator != null) {
                mMenuContainerAnimator.cancel();
            }
            mMenuContainerAnimator = new AnimatorSet();
            ObjectAnimator menuAnim = ObjectAnimator.ofFloat(mMenuContainer, View.ALPHA,
                    mMenuContainer.getAlpha(), 1f);
            menuAnim.addUpdateListener(mMenuBgUpdateListener);
            ObjectAnimator settingsAnim = ObjectAnimator.ofFloat(mSettingsButton, View.ALPHA,
                    mSettingsButton.getAlpha(), 1f);
            ObjectAnimator dismissAnim = ObjectAnimator.ofFloat(mDismissButton, View.ALPHA,
                    mDismissButton.getAlpha(), 1f);
            ObjectAnimator resizeAnim = ObjectAnimator.ofFloat(mResizeHandle, View.ALPHA,
                    mResizeHandle.getAlpha(),
                    ENABLE_RESIZE_HANDLE && menuState == MENU_STATE_CLOSE && showResizeHandle
                            ? 1f : 0f);
            if (menuState == MENU_STATE_FULL) {
                mMenuContainerAnimator.playTogether(menuAnim, settingsAnim, dismissAnim,
                        resizeAnim);
            } else {
                mMenuContainerAnimator.playTogether(dismissAnim, resizeAnim);
            }
            mMenuContainerAnimator.setInterpolator(Interpolators.ALPHA_IN);
            mMenuContainerAnimator.setDuration(menuState == MENU_STATE_CLOSE
                    ? MENU_FADE_DURATION
                    : MENU_SLOW_FADE_DURATION);
            if (allowMenuTimeout) {
                mMenuContainerAnimator.addListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator animation) {
                        repostDelayedFinish(INITIAL_DISMISS_DELAY);
                    }
                });
            }
            if (withDelay) {
                // starts the menu container animation after window expansion is completed
                notifyMenuStateChange(menuState, resizeMenuOnShow, MESSAGE_MENU_EXPANDED);
            } else {
                notifyMenuStateChange(menuState, resizeMenuOnShow, MESSAGE_INVALID_TYPE);
                mMenuContainerAnimator.start();
            }
        } else {
            // If we are already visible, then just start the delayed dismiss and unregister any
            // existing input consumers from the previous drag
            if (allowMenuTimeout) {
                repostDelayedFinish(POST_INTERACTION_DISMISS_DELAY);
            }
        }
    }

    /**
     * Different from {@link #hideMenu()}, this function does not try to finish this menu activity
     * and instead, it fades out the controls by setting the alpha to 0 directly without menu
     * visibility callbacks invoked.
     */
    private void fadeOutMenu() {
        mMenuContainer.setAlpha(0f);
        mSettingsButton.setAlpha(0f);
        mDismissButton.setAlpha(0f);
        mResizeHandle.setAlpha(0f);
    }

    private void hideMenu() {
        hideMenu(null);
    }

    private void hideMenu(Runnable animationEndCallback) {
        hideMenu(animationEndCallback, true /* notifyMenuVisibility */, false /* isDismissing */,
                true /* animate */);
    }

    private void hideMenu(final Runnable animationFinishedRunnable, boolean notifyMenuVisibility,
            boolean isDismissing, boolean animate) {
        if (mMenuState != MENU_STATE_NONE) {
            cancelDelayedFinish();
            if (notifyMenuVisibility) {
                notifyMenuStateChange(MENU_STATE_NONE, mResize, MESSAGE_INVALID_TYPE);
            }
            mMenuContainerAnimator = new AnimatorSet();
            ObjectAnimator menuAnim = ObjectAnimator.ofFloat(mMenuContainer, View.ALPHA,
                    mMenuContainer.getAlpha(), 0f);
            menuAnim.addUpdateListener(mMenuBgUpdateListener);
            ObjectAnimator settingsAnim = ObjectAnimator.ofFloat(mSettingsButton, View.ALPHA,
                    mSettingsButton.getAlpha(), 0f);
            ObjectAnimator dismissAnim = ObjectAnimator.ofFloat(mDismissButton, View.ALPHA,
                    mDismissButton.getAlpha(), 0f);
            ObjectAnimator resizeAnim = ObjectAnimator.ofFloat(mResizeHandle, View.ALPHA,
                    mResizeHandle.getAlpha(), 0f);
            mMenuContainerAnimator.playTogether(menuAnim, settingsAnim, dismissAnim, resizeAnim);
            mMenuContainerAnimator.setInterpolator(Interpolators.ALPHA_OUT);
            mMenuContainerAnimator.setDuration(animate ? MENU_FADE_DURATION : 0);
            mMenuContainerAnimator.addListener(new AnimatorListenerAdapter() {
                @Override
                public void onAnimationEnd(Animator animation) {
                    if (animationFinishedRunnable != null) {
                        animationFinishedRunnable.run();
                    }

                    if (!isDismissing) {
                        // If we are dismissing the PiP, then don't try to pre-emptively finish the
                        // menu activity
                        finish();
                    }
                }
            });
            mMenuContainerAnimator.start();
        } else {
            // If the menu is not visible, just finish now
            finish();
        }
    }

    private void updateFromIntent(Intent intent) {
        mToControllerMessenger = intent.getParcelableExtra(EXTRA_CONTROLLER_MESSENGER);
        if (mToControllerMessenger == null) {
            Log.w(TAG, "Controller messenger is null. Stopping.");
            finish();
            return;
        }
        notifyActivityCallback(mMessenger);

        ParceledListSlice<RemoteAction> actions = intent.getParcelableExtra(EXTRA_ACTIONS);
        if (actions != null) {
            mActions.clear();
            mActions.addAll(actions.getList());
        }

        final int menuState = intent.getIntExtra(EXTRA_MENU_STATE, MENU_STATE_NONE);
        if (menuState != MENU_STATE_NONE) {
            Rect stackBounds = intent.getParcelableExtra(EXTRA_STACK_BOUNDS);
            boolean allowMenuTimeout = intent.getBooleanExtra(EXTRA_ALLOW_TIMEOUT, true);
            boolean willResizeMenu = intent.getBooleanExtra(EXTRA_WILL_RESIZE_MENU, false);
            boolean withDelay = intent.getBooleanExtra(EXTRA_SHOW_MENU_WITH_DELAY, false);
            boolean showResizeHandle = intent.getBooleanExtra(EXTRA_SHOW_RESIZE_HANDLE, false);
            showMenu(menuState, stackBounds, allowMenuTimeout, willResizeMenu, withDelay,
                    showResizeHandle);
        }
    }

    private void setActions(Rect stackBounds, List<RemoteAction> actions) {
        mActions.clear();
        mActions.addAll(actions);
        updateActionViews(stackBounds);
    }

    private void updateActionViews(Rect stackBounds) {
        ViewGroup expandContainer = findViewById(R.id.expand_container);
        ViewGroup actionsContainer = findViewById(R.id.actions_container);
        actionsContainer.setOnTouchListener((v, ev) -> {
            // Do nothing, prevent click through to parent
            return true;
        });

        if (mActions.isEmpty() || mMenuState == MENU_STATE_CLOSE) {
            actionsContainer.setVisibility(View.INVISIBLE);
        } else {
            actionsContainer.setVisibility(View.VISIBLE);
            if (mActionsGroup != null) {
                // Ensure we have as many buttons as actions
                final LayoutInflater inflater = LayoutInflater.from(this);
                while (mActionsGroup.getChildCount() < mActions.size()) {
                    final ImageButton actionView = (ImageButton) inflater.inflate(
                            R.layout.pip_menu_action, mActionsGroup, false);
                    mActionsGroup.addView(actionView);
                }

                // Update the visibility of all views
                for (int i = 0; i < mActionsGroup.getChildCount(); i++) {
                    mActionsGroup.getChildAt(i).setVisibility(i < mActions.size()
                            ? View.VISIBLE
                            : View.GONE);
                }

                // Recreate the layout
                final boolean isLandscapePip = stackBounds != null &&
                        (stackBounds.width() > stackBounds.height());
                for (int i = 0; i < mActions.size(); i++) {
                    final RemoteAction action = mActions.get(i);
                    final ImageButton actionView = (ImageButton) mActionsGroup.getChildAt(i);

                    // TODO: Check if the action drawable has changed before we reload it
                    action.getIcon().loadDrawableAsync(this, d -> {
                        d.setTint(Color.WHITE);
                        actionView.setImageDrawable(d);
                    }, mHandler);
                    actionView.setContentDescription(action.getContentDescription());
                    if (action.isEnabled()) {
                        actionView.setOnClickListener(v -> {
                            mHandler.post(() -> {
                                try {
                                    action.getActionIntent().send();
                                } catch (CanceledException e) {
                                    Log.w(TAG, "Failed to send action", e);
                                }
                            });
                        });
                    }
                    actionView.setEnabled(action.isEnabled());
                    actionView.setAlpha(action.isEnabled() ? 1f : DISABLED_ACTION_ALPHA);

                    // Update the margin between actions
                    LinearLayout.LayoutParams lp = (LinearLayout.LayoutParams)
                            actionView.getLayoutParams();
                    lp.leftMargin = (isLandscapePip && i > 0) ? mBetweenActionPaddingLand : 0;
                }
            }

            // Update the expand container margin to adjust the center of the expand button to
            // account for the existence of the action container
            FrameLayout.LayoutParams expandedLp =
                    (FrameLayout.LayoutParams) expandContainer.getLayoutParams();
            expandedLp.topMargin = getResources().getDimensionPixelSize(
                    R.dimen.pip_action_padding);
            expandedLp.bottomMargin = getResources().getDimensionPixelSize(
                    R.dimen.pip_expand_container_edge_margin);
            expandContainer.requestLayout();
        }
    }

    private void updateDismissFraction(float fraction) {
        int alpha;
        final float menuAlpha = 1 - fraction;
        if (mMenuState == MENU_STATE_FULL) {
            mMenuContainer.setAlpha(menuAlpha);
            mSettingsButton.setAlpha(menuAlpha);
            mDismissButton.setAlpha(menuAlpha);
            final float interpolatedAlpha =
                    MENU_BACKGROUND_ALPHA * menuAlpha + DISMISS_BACKGROUND_ALPHA * fraction;
            alpha = (int) (interpolatedAlpha * 255);
        } else {
            if (mMenuState == MENU_STATE_CLOSE) {
                mDismissButton.setAlpha(menuAlpha);
            }
            alpha = (int) (fraction * DISMISS_BACKGROUND_ALPHA * 255);
        }
        mBackgroundDrawable.setAlpha(alpha);
    }

    private void notifyMenuStateChange(int menuState, boolean resize, int callbackWhat) {
        mMenuState = menuState;
        mResize = resize;
        Message m = Message.obtain();
        m.what = PipMenuActivityController.MESSAGE_MENU_STATE_CHANGED;
        m.arg1 = menuState;
        m.arg2 = resize ? 1 : 0;
        if (callbackWhat != MESSAGE_INVALID_TYPE) {
            // This message could be sent across processes when in secondary user.
            // Make the receiver end sending back via our own Messenger
            m.replyTo = mMessenger;
            final Bundle data = new Bundle(1);
            data.putInt(PipMenuActivityController.EXTRA_MESSAGE_CALLBACK_WHAT, callbackWhat);
            m.obj = data;
        }
        sendMessage(m, "Could not notify controller of PIP menu visibility");
    }

    private void expandPip() {
        // Do not notify menu visibility when hiding the menu, the controller will do this when it
        // handles the message
        hideMenu(() -> {
            sendEmptyMessage(PipMenuActivityController.MESSAGE_EXPAND_PIP,
                    "Could not notify controller to expand PIP");
        }, false /* notifyMenuVisibility */, false /* isDismissing */, true /* animate */);
    }

    private void dismissPip() {
        // Since tapping on the close-button invokes a double-tap wait callback in PipTouchHandler,
        // we want to disable animating the fadeout animation of the buttons in order to call on
        // PipTouchHandler#onPipDismiss fast enough.
        final boolean animate = mMenuState != MENU_STATE_CLOSE;
        // Do not notify menu visibility when hiding the menu, the controller will do this when it
        // handles the message
        hideMenu(() -> {
            sendEmptyMessage(PipMenuActivityController.MESSAGE_DISMISS_PIP,
                    "Could not notify controller to dismiss PIP");
        }, false /* notifyMenuVisibility */, true /* isDismissing */, animate);
    }

    private void showSettings() {
        final Pair<ComponentName, Integer> topPipActivityInfo =
                PipUtils.getTopPipActivity(this, ActivityManager.getService());
        if (topPipActivityInfo.first != null) {
            final UserHandle user = UserHandle.of(topPipActivityInfo.second);
            final Intent settingsIntent = new Intent(ACTION_PICTURE_IN_PICTURE_SETTINGS,
                    Uri.fromParts("package", topPipActivityInfo.first.getPackageName(), null));
            settingsIntent.putExtra(Intent.EXTRA_USER_HANDLE, user);
            settingsIntent.setFlags(FLAG_ACTIVITY_NEW_TASK | FLAG_ACTIVITY_CLEAR_TASK);
            startActivity(settingsIntent);
        }
    }

    private void notifyActivityCallback(Messenger callback) {
        Message m = Message.obtain();
        m.what = PipMenuActivityController.MESSAGE_UPDATE_ACTIVITY_CALLBACK;
        m.replyTo = callback;
        m.arg1 = mResize ?  1 : 0;
        sendMessage(m, "Could not notify controller of activity finished");
    }

    private void sendEmptyMessage(int what, String errorMsg) {
        Message m = Message.obtain();
        m.what = what;
        sendMessage(m, errorMsg);
    }

    private void sendMessage(Message m, String errorMsg) {
        if (mToControllerMessenger == null) {
            return;
        }
        try {
            mToControllerMessenger.send(m);
        } catch (RemoteException e) {
            Log.e(TAG, errorMsg, e);
        }
    }

    private void cancelDelayedFinish() {
        mHandler.removeCallbacks(mFinishRunnable);
    }

    private void repostDelayedFinish(int delay) {
        int recommendedTimeout = mAccessibilityManager.getRecommendedTimeoutMillis(delay,
                FLAG_CONTENT_ICONS | FLAG_CONTENT_CONTROLS);
        mHandler.removeCallbacks(mFinishRunnable);
        mHandler.postDelayed(mFinishRunnable, recommendedTimeout);
    }
}
