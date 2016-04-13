/*
 * Copyright (C) 2013 The Android Open Source Project
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

package com.android.internal.policy.impl;

import android.content.Context;
import android.util.Slog;
import android.view.MotionEvent;
import android.view.WindowManagerPolicy.PointerEventListener;

/*
 * Listens for system-wide input gestures, firing callbacks when detected.
 * @hide
 */
public class SystemGesturesPointerEventListener implements PointerEventListener {
    private static final String TAG = "SystemGestures";
    private static final boolean DEBUG = false;
    private static final long SWIPE_TIMEOUT_MS = 500;
    private static final int MAX_TRACKED_POINTERS = 32;  // max per input system
    private static final int UNTRACKED_POINTER = -1;

    private static final int SWIPE_NONE = 0;
    private static final int SWIPE_FROM_TOP = 1;
    private static final int SWIPE_FROM_BOTTOM = 2;
    private static final int SWIPE_FROM_RIGHT = 3;
    private static final int SWIPE_THREE_FINGERS_SWIPE_UP = 4;
    private static final int SWIPE_THREE_FINGERS_SWIPE_DOWN = 5;

    private final int mSwipeStartThreshold;
    private final int mSwipeDistanceThreshold;
    private final int mSwipeThreeFingerThreshold = 300;
    private final Callbacks mCallbacks;
    private final int[] mDownPointerId = new int[MAX_TRACKED_POINTERS];
    private final float[] mDownX = new float[MAX_TRACKED_POINTERS];
    private final float[] mDownY = new float[MAX_TRACKED_POINTERS];
    private final long[] mDownTime = new long[MAX_TRACKED_POINTERS];

    int screenHeight;
    int screenWidth;
    private int mDownPointers;
    private boolean mSwipeFireable;
    private boolean mDebugFireable;

    public SystemGesturesPointerEventListener(Context context, Callbacks callbacks) {
        mCallbacks = checkNull("callbacks", callbacks);
        mSwipeStartThreshold = checkNull("context", context).getResources()
                .getDimensionPixelSize(com.android.internal.R.dimen.status_bar_height);
        mSwipeDistanceThreshold = mSwipeStartThreshold;
        if (DEBUG) Slog.d(TAG,  "mSwipeStartThreshold=" + mSwipeStartThreshold
                + " mSwipeDistanceThreshold=" + mSwipeDistanceThreshold);
    }

    private static <T> T checkNull(String name, T arg) {
        if (arg == null) {
            throw new IllegalArgumentException(name + " must not be null");
        }
        return arg;
    }

    @Override
    public void onPointerEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mSwipeFireable = true;
                mDebugFireable = true;
                mDownPointers = 0;
                captureDown(event, 0);
                break;
            case MotionEvent.ACTION_POINTER_DOWN:
                captureDown(event, event.getActionIndex());
                if (mDebugFireable) {
                    mDebugFireable = event.getPointerCount() < 5;
                    if (!mDebugFireable) {
                        if (DEBUG) Slog.d(TAG, "Firing debug");
                        mCallbacks.onDebug();
                    }
                }
                break;
            case MotionEvent.ACTION_MOVE:
                if (mSwipeFireable) {
                    final int swipe = detectSwipe(event);
                    mSwipeFireable = swipe == SWIPE_NONE;
                    if (swipe == SWIPE_FROM_TOP) {
                        if (DEBUG) Slog.d(TAG, "Firing onSwipeFromTop");
                        mCallbacks.onSwipeFromTop();
                    } else if (swipe == SWIPE_FROM_BOTTOM) {
                        if (DEBUG) Slog.d(TAG, "Firing onSwipeFromBottom");
                        mCallbacks.onSwipeFromBottom();
                    } else if (swipe == SWIPE_FROM_RIGHT) {
                        if (DEBUG) Slog.d(TAG, "Firing onSwipeFromRight");
                        mCallbacks.onSwipeFromRight();
                    } else if (swipe == SWIPE_THREE_FINGERS_SWIPE_UP) {
                        if (DEBUG) Slog.d(TAG, "Firing onThreeFingerSwipeUp");
                        mCallbacks.onThreeFingerSwipeUp();
                    } else if (swipe == SWIPE_THREE_FINGERS_SWIPE_DOWN) {
                        if (DEBUG) Slog.d(TAG, "Firing onThreeFingerSwipeDown");
                        mCallbacks.onThreeFingerSwipeDown();
                    }
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                mSwipeFireable = false;
                mDebugFireable = false;
                break;
            default:
                if (DEBUG) Slog.d(TAG, "Ignoring " + event);
        }
    }

    private void captureDown(MotionEvent event, int pointerIndex) {
        final int pointerId = event.getPointerId(pointerIndex);
        final int i = findIndex(pointerId);
        if (DEBUG) Slog.d(TAG, "pointer " + pointerId +
                " down pointerIndex=" + pointerIndex + " trackingIndex=" + i);
        if (i != UNTRACKED_POINTER) {
            mDownX[i] = event.getX(pointerIndex);
            mDownY[i] = event.getY(pointerIndex);
            mDownTime[i] = event.getEventTime();
            if (DEBUG) Slog.d(TAG, "pointer " + pointerId +
                    " down x=" + mDownX[i] + " y=" + mDownY[i]);
        }
    }

    private int findIndex(int pointerId) {
        for (int i = 0; i < mDownPointers; i++) {
            if (mDownPointerId[i] == pointerId) {
                return i;
            }
        }
        if (mDownPointers == MAX_TRACKED_POINTERS || pointerId == MotionEvent.INVALID_POINTER_ID) {
            return UNTRACKED_POINTER;
        }
        mDownPointerId[mDownPointers++] = pointerId;
        return mDownPointers - 1;
    }

    private int detectSwipe(MotionEvent move) {
        final int historySize = move.getHistorySize();
        final int pointerCount = move.getPointerCount();
        if (pointerCount == 3) {
            if (DEBUG) Slog.d(TAG, "ThreePoint Number of Pointers: " + 3);
            switch (detectThreePointSwipe(move)) {
                case SWIPE_THREE_FINGERS_SWIPE_UP:
                    return SWIPE_THREE_FINGERS_SWIPE_UP;
                case SWIPE_THREE_FINGERS_SWIPE_DOWN:
                    return SWIPE_THREE_FINGERS_SWIPE_DOWN;
                default:
                    break;
            }
        }
        for (int p = 0; p < pointerCount; p++) {
            final int pointerId = move.getPointerId(p);
            final int i = findIndex(pointerId);
            if (i != UNTRACKED_POINTER) {
                for (int h = 0; h < historySize; h++) {
                    final long time = move.getHistoricalEventTime(h);
                    final float x = move.getHistoricalX(p, h);
                    final float y = move.getHistoricalY(p,  h);
                    final int swipe = detectSwipe(i, time, x, y);
                    if (swipe != SWIPE_NONE) {
                        return swipe;
                    }
                }
                final int swipe = detectSwipe(i, move.getEventTime(), move.getX(p), move.getY(p));
                if (swipe != SWIPE_NONE) {
                    return swipe;
                }
            }
        }
        return SWIPE_NONE;
    }

    private int detectThreePointSwipe(MotionEvent move) {
        final int historySize = move.getHistorySize();
        final int pointerCount = move.getPointerCount();
        int swipe = SWIPE_NONE;
        int prevSwipe = SWIPE_NONE;

        if (DEBUG) Slog.d(TAG, "detectThreePointSwipe - historySize: " + historySize
                        + ", pointerCount: " + pointerCount);
        for (int p = 0; p < pointerCount; p++) {
            final int pointerId = move.getPointerId(p);
            final int i = findIndex(pointerId);
            if (swipe != prevSwipe) {
                if (DEBUG) Slog.d(TAG, "detectThreePointSwipe - swipe != prevSwipe");
                return SWIPE_NONE;
            }
            if (i != UNTRACKED_POINTER) {
                for (int h = 0; h < historySize; h++) {
                    final long time = move.getHistoricalEventTime(h);
                    final float x = move.getHistoricalX(p, h);
                    final float y = move.getHistoricalY(p,  h);
                    if (DEBUG) Slog.d(TAG, "detectThreePointSwipe - time: " + time + ", x: " + x + ", y: " + y);
                    swipe = detectThreePointSwipe(i, time, x, y);
                    if (swipe != SWIPE_NONE) {
                        break;
                    }
                }
                if (historySize == 0 && swipe == SWIPE_NONE) {
                    swipe = detectThreePointSwipe(i, move.getEventTime(), move.getX(p), move.getY(p));
                }
                if (DEBUG) Slog.d(TAG, "Inside ThreePointSwipe - swipe: " + ((swipe == SWIPE_THREE_FINGERS_SWIPE_UP) ?
                        "SWIPE_THREE_FINGERS_SWIPE_UP" : (swipe == SWIPE_THREE_FINGERS_SWIPE_DOWN)  ?
                                "SWIPE_THREE_FINGERS_SWIPE_DOWN" : "SWIPE_NONE"));
                if (swipe != SWIPE_NONE) {
                    prevSwipe = swipe;
                    return swipe;
                } else {
                    return SWIPE_NONE;
                }
            }
        }

        if ((swipe == prevSwipe) && (swipe != SWIPE_NONE)) {
            if (DEBUG) Slog.d(TAG, "Outside ThreePointSwipe - swipe: " + ((swipe == SWIPE_THREE_FINGERS_SWIPE_UP) ?
                    "SWIPE_THREE_FINGERS_SWIPE_UP" : (swipe == SWIPE_THREE_FINGERS_SWIPE_DOWN)  ?
                            "SWIPE_THREE_FINGERS_SWIPE_DOWN" : "SWIPE_NONE"));
            return swipe;
        }
        return SWIPE_NONE;
    }

    private int detectThreePointSwipe(int i, long time, float x, float y) {
        final float fromX = mDownX[i];
        final float fromY = mDownY[i];
        final long elapsed = time - mDownTime[i];
        if (DEBUG) Slog.d(TAG, "ThreePointSwipe - pointer " + mDownPointerId[i]
                + " moved (" + fromX + "->" + x + "," + fromY + "->" + y + ") in " + elapsed);

        if (y < fromY - mSwipeThreeFingerThreshold
                && elapsed < SWIPE_TIMEOUT_MS) {
            if (DEBUG) Slog.d(TAG, "detectThreePointSwipe: " + "SWIPE_THREE_FINGERS_SWIPE_UP");
            return SWIPE_THREE_FINGERS_SWIPE_UP;
        }
        if (y > fromY + mSwipeThreeFingerThreshold
                && elapsed < SWIPE_TIMEOUT_MS) {
            if (DEBUG) Slog.d(TAG, "detectThreePointSwipe: " + "SWIPE_THREE_FINGERS_SWIPE_DOWN");
            return SWIPE_THREE_FINGERS_SWIPE_DOWN;
        }
        return SWIPE_NONE;
    }

    private int detectSwipe(int i, long time, float x, float y) {
        final float fromX = mDownX[i];
        final float fromY = mDownY[i];
        final long elapsed = time - mDownTime[i];
        if (DEBUG) Slog.d(TAG, "pointer " + mDownPointerId[i]
                + " moved (" + fromX + "->" + x + "," + fromY + "->" + y + ") in " + elapsed);
        if (fromY <= mSwipeStartThreshold
                && y > fromY + mSwipeDistanceThreshold
                && elapsed < SWIPE_TIMEOUT_MS) {
            return SWIPE_FROM_TOP;
        }
        if (fromY >= screenHeight - mSwipeStartThreshold
                && y < fromY - mSwipeDistanceThreshold
                && elapsed < SWIPE_TIMEOUT_MS) {
            return SWIPE_FROM_BOTTOM;
        }
        if (fromX >= screenWidth - mSwipeStartThreshold
                && x < fromX - mSwipeDistanceThreshold
                && elapsed < SWIPE_TIMEOUT_MS) {
            return SWIPE_FROM_RIGHT;
        }
        return SWIPE_NONE;
    }

    interface Callbacks {
        void onSwipeFromTop();
        void onSwipeFromBottom();
        void onSwipeFromRight();
        void onThreeFingerSwipeUp();
        void onThreeFingerSwipeDown();
        void onDebug();
    }
}