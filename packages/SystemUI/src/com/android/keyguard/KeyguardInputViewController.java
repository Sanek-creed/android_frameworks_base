/*
 * Copyright (C) 2020 The Android Open Source Project
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
 * limitations under the License.
 */

package com.android.keyguard;

import android.content.res.ColorStateList;
import android.content.res.Resources;
import android.telephony.TelephonyManager;
import android.view.inputmethod.InputMethodManager;

import com.android.internal.util.LatencyTracker;
import com.android.internal.widget.LockPatternUtils;
import com.android.keyguard.KeyguardSecurityModel.SecurityMode;
import com.android.systemui.dagger.qualifiers.Main;
import com.android.systemui.util.ViewController;
import com.android.systemui.util.concurrency.DelayableExecutor;

import javax.inject.Inject;


/** Controller for a {@link KeyguardSecurityView}. */
public abstract class KeyguardInputViewController<T extends KeyguardInputView>
        extends ViewController<T> implements KeyguardSecurityView {

    private final SecurityMode mSecurityMode;
    private final KeyguardSecurityCallback mKeyguardSecurityCallback;
    private boolean mPaused;


    // The following is used to ignore callbacks from SecurityViews that are no longer current
    // (e.g. face unlock). This avoids unwanted asynchronous events from messing with the
    // state for the current security method.
    private KeyguardSecurityCallback mNullCallback = new KeyguardSecurityCallback() {
        @Override
        public void userActivity() { }
        @Override
        public void reportUnlockAttempt(int userId, boolean success, int timeoutMs) { }
        @Override
        public boolean isVerifyUnlockOnly() {
            return false;
        }
        @Override
        public void dismiss(boolean securityVerified, int targetUserId) { }
        @Override
        public void dismiss(boolean authenticated, int targetId,
                boolean bypassSecondaryLockScreen) { }
        @Override
        public void onUserInput() { }
        @Override
        public void reset() {}
    };

    protected KeyguardInputViewController(T view, SecurityMode securityMode,
            KeyguardSecurityCallback keyguardSecurityCallback) {
        super(view);
        mSecurityMode = securityMode;
        mKeyguardSecurityCallback = keyguardSecurityCallback;
    }

    @Override
    protected void onViewAttached() {
    }

    @Override
    protected void onViewDetached() {
    }

    SecurityMode getSecurityMode() {
        return mSecurityMode;
    }

    protected KeyguardSecurityCallback getKeyguardSecurityCallback() {
        if (mPaused) {
            return mNullCallback;
        }

        return mKeyguardSecurityCallback;
    }

    @Override
    public void reset() {
    }

    @Override
    public void onPause() {
        mPaused = true;
    }

    @Override
    public void onResume(int reason) {
        mPaused = false;
    }

    @Override
    public void showPromptReason(int reason) {
    }

    @Override
    public void showMessage(CharSequence message, ColorStateList colorState) {
    }

    public void startAppearAnimation() {
        mView.startAppearAnimation();
    }

    public boolean startDisappearAnimation(Runnable finishRunnable) {
        return mView.startDisappearAnimation(finishRunnable);
    }

    @Override
    public CharSequence getTitle() {
        return mView.getTitle();
    }

    /** Finds the index of this view in the suppplied parent view. */
    public int getIndexIn(KeyguardSecurityViewFlipper view) {
        return view.indexOfChild(mView);
    }

    /** Factory for a {@link KeyguardInputViewController}. */
    public static class Factory {
        private final KeyguardUpdateMonitor mKeyguardUpdateMonitor;
        private final LockPatternUtils mLockPatternUtils;
        private final LatencyTracker mLatencyTracker;
        private final KeyguardMessageAreaController.Factory mMessageAreaControllerFactory;
        private final InputMethodManager mInputMethodManager;
        private final DelayableExecutor mMainExecutor;
        private final Resources mResources;
        private LiftToActivateListener mLiftToActivateListener;
        private TelephonyManager mTelephonyManager;

        @Inject
        public Factory(KeyguardUpdateMonitor keyguardUpdateMonitor,
                LockPatternUtils lockPatternUtils,
                LatencyTracker latencyTracker,
                KeyguardMessageAreaController.Factory messageAreaControllerFactory,
                InputMethodManager inputMethodManager, @Main DelayableExecutor mainExecutor,
                @Main Resources resources, LiftToActivateListener liftToActivateListener,
                TelephonyManager telephonyManager) {
            mKeyguardUpdateMonitor = keyguardUpdateMonitor;
            mLockPatternUtils = lockPatternUtils;
            mLatencyTracker = latencyTracker;
            mMessageAreaControllerFactory = messageAreaControllerFactory;
            mInputMethodManager = inputMethodManager;
            mMainExecutor = mainExecutor;
            mResources = resources;
            mLiftToActivateListener = liftToActivateListener;
            mTelephonyManager = telephonyManager;
        }

        /** Create a new {@link KeyguardInputViewController}. */
        public KeyguardInputViewController create(KeyguardInputView keyguardInputView,
                SecurityMode securityMode, KeyguardSecurityCallback keyguardSecurityCallback) {
            if (keyguardInputView instanceof KeyguardPatternView) {
                return new KeyguardPatternViewController((KeyguardPatternView) keyguardInputView,
                        mKeyguardUpdateMonitor, securityMode, mLockPatternUtils,
                        keyguardSecurityCallback, mLatencyTracker, mMessageAreaControllerFactory);
            } else if (keyguardInputView instanceof KeyguardPasswordView) {
                return new KeyguardPasswordViewController((KeyguardPasswordView) keyguardInputView,
                        mKeyguardUpdateMonitor, securityMode, mLockPatternUtils,
                        keyguardSecurityCallback, mMessageAreaControllerFactory, mLatencyTracker,
                        mInputMethodManager, mMainExecutor, mResources);
            } else if (keyguardInputView instanceof KeyguardPINView) {
                return new KeyguardPinViewController((KeyguardPINView) keyguardInputView,
                        mKeyguardUpdateMonitor, securityMode, mLockPatternUtils,
                        keyguardSecurityCallback, mMessageAreaControllerFactory, mLatencyTracker,
                        mLiftToActivateListener);
            } else if (keyguardInputView instanceof KeyguardSimPinView) {
                return new KeyguardSimPinViewController((KeyguardSimPinView) keyguardInputView,
                        mKeyguardUpdateMonitor, securityMode, mLockPatternUtils,
                        keyguardSecurityCallback, mMessageAreaControllerFactory, mLatencyTracker,
                        mLiftToActivateListener, mTelephonyManager);
            } else if (keyguardInputView instanceof KeyguardSimPukView) {
                return new KeyguardSimPukViewController((KeyguardSimPukView) keyguardInputView,
                        mKeyguardUpdateMonitor, securityMode, mLockPatternUtils,
                        keyguardSecurityCallback, mMessageAreaControllerFactory, mLatencyTracker,
                        mLiftToActivateListener, mTelephonyManager);
            }

            throw new RuntimeException("Unable to find controller for " + keyguardInputView);
        }
    }
}
