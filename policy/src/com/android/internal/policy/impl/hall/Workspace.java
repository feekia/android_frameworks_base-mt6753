package com.android.internal.policy.impl.hall;

import java.lang.reflect.Field;
import java.util.ArrayList;

import android.content.Context;
import android.graphics.Canvas;
import android.os.Parcel;
import android.os.Parcelable;
import android.util.AttributeSet;
import android.view.Display;
import android.view.MotionEvent;
import android.view.VelocityTracker;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.WindowManager;
import android.view.View.OnClickListener;
import android.view.animation.Interpolator;
import android.widget.Scroller;
import android.util.Log;
import com.android.internal.R;

/**
 * NEW Feature: HALL keyguard UI
 * 
 * @author chongxishen
 *
 */
public class Workspace extends ViewGroup {
	static final String TAG = "Workspace";
	
	static final int INVALID_SCREEN = -1;
	static final int INVALID_POINTER = -1;
	static final int SNAP_VELOCITY = 600;
	static final float BASELINE_FLING_VELOCITY = 2500.f;
	static final float FLING_VELOCITY_INFLUENCE = 0.4f;
	
	static final int TOUCH_STATE_REST = 0;
	static final int TOUCH_STATE_SCROLLING = 1;

    int mTouchState = TOUCH_STATE_REST;
	
	int mCurrentScreen;
	int mDefaultScreen;
    int mNextScreen = INVALID_SCREEN;
    int mOverscrollDistance;    
    boolean mFirstLayout = true;
    float mLastMotionX;
   	float mLastMotionY;
   	int mTouchSlop;
   	int mMaximumVelocity;
//   	int mScrollX, mScrollY;
   	boolean mAllowLongPress = true;
   	int mActivePointerId = INVALID_POINTER;

   	Display mDisplay;
	Scroller mScroller;
	VelocityTracker mVelocityTracker;
	WorkspaceOvershootInterpolator mScrollInterpolator;
	PageIndicator mPageIndicator;
	
	
	static final class WorkspaceOvershootInterpolator implements Interpolator {
        private static final float DEFAULT_TENSION = 1.3f;
        private float mTension;

        public WorkspaceOvershootInterpolator() {
            mTension = DEFAULT_TENSION;
        }

        public void setDistance(int distance) {
            mTension = distance > 0 ? DEFAULT_TENSION / distance : DEFAULT_TENSION;
        }

        public void disableSettle() {
            mTension = 0.f;
        }

        public float getInterpolation(float t) {
            t -= 1.0f;
            return t * t * ((mTension + 1) * t + mTension) + 1.0f;
        }
    }
	
	
	// callback for updating indicator
    public static interface ScrollToScreenCallback {
        public void onScrollFinish(int currentIndex);
    }
    ScrollToScreenCallback mScrollToScreenCallback;
    
    
    void notifyScrollChanged(int index) {
    	if (mScrollToScreenCallback != null) {
    		mScrollToScreenCallback.onScrollFinish(index);
    	}
    	if (mPageIndicator != null) {
    	    mPageIndicator.setActiveMarker(index);
    	}
    }
    
    
    static final class SavedState extends BaseSavedState {
        int currentScreen = -1;

        SavedState(Parcelable superState) {
            super(superState);
        }

        private SavedState(Parcel in) {
            super(in);
            currentScreen = in.readInt();
        }

        @Override
        public void writeToParcel(Parcel out, int flags) {
            super.writeToParcel(out, flags);
            out.writeInt(currentScreen);
        }

        public static final Parcelable.Creator<SavedState> CREATOR = new Parcelable.Creator<SavedState>() {
            public SavedState createFromParcel(Parcel in) {
                return new SavedState(in);
            }

            public SavedState[] newArray(int size) {
                return new SavedState[size];
            }
        };
    }
    

	public Workspace(Context context, AttributeSet attrs) {
		this(context, attrs, 0);
	}
	
	public Workspace(Context context, AttributeSet attrs, int defStyle) {
		super(context, attrs, defStyle);
		mDefaultScreen = 0;
		initWorkspace(context);
	}
	
	protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        ViewGroup parent = (ViewGroup) getParent();
        if (mPageIndicator == null) {
            mPageIndicator = (PageIndicator) parent.findViewById(R.id.hall_keyguard_page_indicator);
            mPageIndicator.removeAllMarkers(false);

            final ArrayList<PageIndicator.PageMarkerResources> markers = new ArrayList<PageIndicator.PageMarkerResources>();
            for (int i = 0; i < getChildCount(); ++i) {
                markers.add(getPageIndicatorMarker(i));
            }

            mPageIndicator.addMarkers(markers, false);

            OnClickListener listener = getPageIndicatorClickListener();
            if (listener != null) {
                mPageIndicator.setOnClickListener(listener);
            }
            // mPageIndicator.setContentDescription(getPageIndicatorDescription());
        }
        computeScroll();
    }

	OnClickListener getPageIndicatorClickListener() {
        return null;
    }
	
	PageIndicator.PageMarkerResources getPageIndicatorMarker(int pageIndex) {
        return new PageIndicator.PageMarkerResources();
    }

	@Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
	    super.onMeasure(widthMeasureSpec, heightMeasureSpec);

        final int widthSize = MeasureSpec.getSize(widthMeasureSpec);
        final int widthMode = MeasureSpec.getMode(widthMeasureSpec);
        final int heightMode = MeasureSpec.getMode(heightMeasureSpec);
        final int heightSize = MeasureSpec.getSize(heightMeasureSpec);

        if (widthSize <= 0 || heightSize <= 0) {
            Log.d(TAG, "onMeasure: " + widthSize + "x" + heightSize);
            return;
        }
        
        // The children are given the same width and height as the workspace
        final int count = getChildCount();
        for (int i = 0; i < count; i++) {
        	View child = getChildAt(i);
            if (child != null) {
                try {
                    child.measure(widthMeasureSpec, heightMeasureSpec);
                } catch (Exception e) {
                    Log.e(TAG, "onMeasure: " + e);
                }
            }
        }

        if (mFirstLayout) {
            setHorizontalScrollBarEnabled(false);
            scrollTo(mCurrentScreen * widthSize, 0);
            setHorizontalScrollBarEnabled(true);
            mFirstLayout = false;
        }
    }
	
	@Override
	protected void onLayout(boolean arg0, int arg1, int arg2, int arg3, int arg4) {
		int childLeft = 0;
        final int count = getChildCount();
        for (int i = 0; i < count; i++) {
            final View child = getChildAt(i);
            if (child.getVisibility() != View.GONE) {
                final int childWidth = child.getMeasuredWidth();
                child.layout(childLeft, 0, childLeft + childWidth, child.getMeasuredHeight());
                childLeft += childWidth;
            }
        }
	}
	
	@Override
    public void scrollTo(int x, int y) {
        super.scrollTo(x, y);
    }

    @Override
    public void computeScroll() {
        if (mScroller.computeScrollOffset()) {
        	setScrollXInner(mScroller.getCurrX());
        	setScrollYInner(mScroller.getCurrY());
            postInvalidate();
        } else if (mNextScreen != INVALID_SCREEN) {
            mCurrentScreen = Math.max(0, Math.min(mNextScreen, getChildCount() - 1));
            notifyScrollChanged(mCurrentScreen);
            mNextScreen = INVALID_SCREEN;
            clearChildrenCache();
        }
    }

    @Override
    public void focusableViewAvailable(View focused) {
        View current = getChildAt(mCurrentScreen);
        View v = focused;
        while (true) {
            if (v == current) {
                super.focusableViewAvailable(focused);
                return;
            }
            if (v == this) {
                return;
            }
            ViewParent parent = v.getParent();
            if (parent instanceof View) {
                v = (View) v.getParent();
            } else {
                return;
            }
        }
    }
    
    @Override
    public boolean onInterceptTouchEvent(MotionEvent ev) {
        final int action = ev.getAction();
        if ((action == MotionEvent.ACTION_MOVE) && (mTouchState != TOUCH_STATE_REST)) {
            return true;
        }

        acquireVelocityTrackerAndAddMovement(ev);

        switch (action & MotionEvent.ACTION_MASK) {
        case MotionEvent.ACTION_MOVE: {
            /*
             * mIsBeingDragged == false, otherwise the shortcut would have
             * caught it. Check whether the user has moved far enough from his
             * original down touch.
             */

            /*
             * Locally do absolute value. mLastMotionX is set to the y value of
             * the down event.
             */
            final int pointerIndex = ev.findPointerIndex(mActivePointerId);
            final float x = ev.getX(pointerIndex);
            final float y = ev.getY(pointerIndex);
            final int xDiff = (int) Math.abs(x - mLastMotionX);
            final int yDiff = (int) Math.abs(y - mLastMotionY);

            final int touchSlop = mTouchSlop;
            boolean xMoved = xDiff > touchSlop;
            boolean yMoved = yDiff > touchSlop;

            if (xMoved || yMoved) {
                if (xMoved) {
                    // Scroll if the user moved far enough along the X axis
                    mTouchState = TOUCH_STATE_SCROLLING;
                    enableChildrenCache(mCurrentScreen - 1, mCurrentScreen + 1);
                }
                // Either way, cancel any pending longpress
                if (mAllowLongPress) {
                    mAllowLongPress = false;
                    // Try canceling the long press. It could also have been
                    // scheduled
                    // by a distant descendant, so use the mAllowLongPress flag
                    // to block
                    // everything
                    final View currentScreen = getChildAt(mCurrentScreen);
                    currentScreen.cancelLongPress();
                }
            }
            break;
        }

        case MotionEvent.ACTION_DOWN: {
            final float x = ev.getX();
            final float y = ev.getY();
            // Remember location of down touch
            mLastMotionX = x;
            mLastMotionY = y;
            mActivePointerId = ev.getPointerId(0);
            mAllowLongPress = true;

            /*
             * If being flinged and user touches the screen, initiate drag;
             * otherwise don't. mScroller.isFinished should be false when being
             * flinged.
             */
            mTouchState = mScroller.isFinished() ? TOUCH_STATE_REST : TOUCH_STATE_SCROLLING;
            break;
        }

        case MotionEvent.ACTION_CANCEL:
        case MotionEvent.ACTION_UP:
            // Release the drag
            clearChildrenCache();
            mTouchState = TOUCH_STATE_REST;
            mActivePointerId = INVALID_POINTER;
            mAllowLongPress = false;
            releaseVelocityTracker();
            break;

        case MotionEvent.ACTION_POINTER_UP:
            onSecondaryPointerUp(ev);
            break;
        }

        /*
         * The only time we want to intercept motion events is if we are in the
         * drag mode.
         */
        return mTouchState != TOUCH_STATE_REST;
    }
    
    
    @Override
    public boolean onTouchEvent(MotionEvent ev) {
        acquireVelocityTrackerAndAddMovement(ev);

        final int action = ev.getAction();
        switch (action & MotionEvent.ACTION_MASK) {
        case MotionEvent.ACTION_DOWN:
            /*
             * If being flinged and user touches, stop the fling. isFinished
             * will be false if being flinged.
             */
            if (!mScroller.isFinished()) {
                mScroller.abortAnimation();
            }

            // Remember where the motion event started
            mLastMotionX = ev.getX();
            mActivePointerId = ev.getPointerId(0);
            if (mTouchState == TOUCH_STATE_SCROLLING) {
                enableChildrenCache(mCurrentScreen - 1, mCurrentScreen + 1);
            }
            break;
            
        case MotionEvent.ACTION_MOVE:
            if (mTouchState == TOUCH_STATE_SCROLLING) {
                // Scroll to follow the motion event
                final int pointerIndex = ev.findPointerIndex(mActivePointerId);
                final float x = ev.getX(pointerIndex);
                final float deltaX = mLastMotionX - x;
                mLastMotionX = x;

                final int availableToScroll;
                if (deltaX < 0) {
                    availableToScroll = getScrollX() + mOverscrollDistance;
                    if (availableToScroll > 0) {
                        scrollBy((int) Math.max(-availableToScroll, deltaX), 0);
                    }
                } else if (deltaX > 0) {
                    availableToScroll = getChildAt(getChildCount() - 1).getRight() - getScrollX() - getWidth() + mOverscrollDistance;
                    if (availableToScroll > 0) {
                        scrollBy((int) Math.min(availableToScroll, deltaX), 0);
                    }
                } else {
                    awakenScrollBars();
                }
            }
            break;
            
        case MotionEvent.ACTION_UP:
            if (mTouchState == TOUCH_STATE_SCROLLING) {
                final VelocityTracker velocityTracker = mVelocityTracker;
                velocityTracker.computeCurrentVelocity(1000, mMaximumVelocity);
                final int velocityX = (int) velocityTracker.getXVelocity(mActivePointerId);

                final int screenWidth = getWidth();
                final int whichScreen = (getScrollX() + (screenWidth / 2)) / screenWidth;
                final float scrolledPos = (float) getScrollX() / screenWidth;

                if (velocityX > SNAP_VELOCITY && mCurrentScreen > 0) {
                    // Fling hard enough to move left.
                    // Don't fling across more than one screen at a time.
                    final int bound = scrolledPos < whichScreen ? mCurrentScreen - 1 : mCurrentScreen;
                    snapToScreen(Math.min(whichScreen, bound), velocityX, true);
                } else if (velocityX < -SNAP_VELOCITY && mCurrentScreen < getChildCount() - 1) {
                    // Fling hard enough to move right
                    // Don't fling across more than one screen at a time.
                    final int bound = scrolledPos > whichScreen ? mCurrentScreen + 1 : mCurrentScreen;
                    snapToScreen(Math.max(whichScreen, bound), velocityX, true);
                } else {
                    snapToScreen(whichScreen, 0, true);
                }
            }
            mTouchState = TOUCH_STATE_REST;
            mActivePointerId = INVALID_POINTER;
            releaseVelocityTracker();
            break;
            
        case MotionEvent.ACTION_CANCEL:
            if (mTouchState == TOUCH_STATE_SCROLLING) {
                final int screenWidth = getWidth();
                final int whichScreen = (getScrollX() + (screenWidth / 2)) / screenWidth;
                snapToScreen(whichScreen, 0, true);
            }
            mTouchState = TOUCH_STATE_REST;
            mActivePointerId = INVALID_POINTER;
            releaseVelocityTracker();
            break;
            
        case MotionEvent.ACTION_POINTER_UP:
            onSecondaryPointerUp(ev);
            break;
        }

        return true;
    }
    
    
    
    @Override
    protected Parcelable onSaveInstanceState() {
        final SavedState state = new SavedState(super.onSaveInstanceState());
        state.currentScreen = mCurrentScreen;
        return state;
    }

    @Override
    protected void onRestoreInstanceState(Parcelable state) {
        SavedState savedState = (SavedState) state;
        super.onRestoreInstanceState(savedState.getSuperState());
        if (savedState.currentScreen != -1) {
            mCurrentScreen = savedState.currentScreen;
        }
    }
    
    
    @Override
    protected void dispatchDraw(Canvas canvas) {
        boolean restore = false;
        int restoreCount = 0;

        // ViewGroup.dispatchDraw() supports many features we don't need:
        // clip to padding, layout animation, animation listener, disappearing
        // children, etc. The following implementation attempts to fast-track
        // the drawing dispatch by drawing only what we know needs to be drawn.

        boolean fastDraw = mTouchState != TOUCH_STATE_SCROLLING && mNextScreen == INVALID_SCREEN;
        // If we are not scrolling or flinging, draw only the current screen
        if (fastDraw) {
        	final View child = getChildAt(mCurrentScreen);
            if (child != null) drawChild(canvas, child, getDrawingTime());
        } else {
            final long drawingTime = getDrawingTime();
            final float scrollPos = (float) getScrollX() / getWidth();
            final int leftScreen = (int) scrollPos;
            final int rightScreen = leftScreen + 1;
            if (leftScreen >= 0) {
            	final View left = getChildAt(leftScreen);
                if (left != null) drawChild(canvas, left, drawingTime);
            }
            if (scrollPos != leftScreen && rightScreen < getChildCount()) {
            	final View right = getChildAt(rightScreen);
                if (right != null) drawChild(canvas, right, drawingTime);
            }
        }

        if (restore) {
            canvas.restoreToCount(restoreCount);
        }
    }
	
    
    void onSecondaryPointerUp(MotionEvent ev) {
        final int pointerIndex = (ev.getAction() & MotionEvent.ACTION_POINTER_INDEX_MASK) >> MotionEvent.ACTION_POINTER_INDEX_SHIFT;
        final int pointerId = ev.getPointerId(pointerIndex);
        if (pointerId == mActivePointerId) {
            // This was our active pointer going up. Choose a new
            // active pointer and adjust accordingly.
            // TODO: Make this decision more intelligent.
            final int newPointerIndex = pointerIndex == 0 ? 1 : 0;
            mLastMotionX = ev.getX(newPointerIndex);
            mLastMotionY = ev.getY(newPointerIndex);
            mActivePointerId = ev.getPointerId(newPointerIndex);
            if (mVelocityTracker != null) {
                mVelocityTracker.clear();
            }
        }
    }
    
    void acquireVelocityTrackerAndAddMovement(MotionEvent ev) {
        if (mVelocityTracker == null) {
            mVelocityTracker = VelocityTracker.obtain();
        }
        mVelocityTracker.addMovement(ev);
    }
    
    void releaseVelocityTracker() {
        if (mVelocityTracker != null) {
            mVelocityTracker.recycle();
            mVelocityTracker = null;
        }
    }
    
    public void snapToScreen(int whichScreen) {
        snapToScreen(whichScreen, 0, false);
    }
    
    void snapToScreen(int whichScreen, int velocity, boolean settle) {
        whichScreen = Math.max(0, Math.min(whichScreen, getChildCount() - 1));
        enableChildrenCache(mCurrentScreen, whichScreen);
        mNextScreen = whichScreen;
        notifyScrollChanged(mNextScreen);
        View focusedChild = getFocusedChild();
        if (focusedChild != null && whichScreen != mCurrentScreen
                && focusedChild == getChildAt(mCurrentScreen)) {
            focusedChild.clearFocus();
        }

        final int screenDelta = Math.max(1, Math.abs(whichScreen - mCurrentScreen));
        final int newX = whichScreen * getWidth();
        final int delta = newX - getScrollX();
        int duration = (screenDelta + 1) * 100;

        if (!mScroller.isFinished()) {
            mScroller.abortAnimation();
        }

        if (settle) {
            mScrollInterpolator.setDistance(screenDelta);
        } else {
            mScrollInterpolator.disableSettle();
        }

        velocity = Math.abs(velocity);
        if (velocity > 0) {
            duration += (duration / (velocity / BASELINE_FLING_VELOCITY)) * FLING_VELOCITY_INFLUENCE;
        } else {
            duration += 100;
        }
        awakenScrollBars(duration);
        mScroller.startScroll(getScrollX(), 0, delta, 0, duration);
        invalidate();
    }
    
    void enableChildrenCache(int fromScreen, int toScreen) {
        if (fromScreen > toScreen) {
            final int temp = fromScreen;
            fromScreen = toScreen;
            toScreen = temp;
        }

        final int count = getChildCount();

        fromScreen = Math.max(fromScreen, 0);
        toScreen = Math.min(toScreen, count - 1);

        for (int i = fromScreen; i <= toScreen; i++) {
            final ViewGroup layout = (ViewGroup) getChildAt(i);
            layout.setDrawingCacheEnabled(true);
            layout.setDrawingCacheEnabled(true);
        }
    }

    void clearChildrenCache() {
        final int count = getChildCount();
        for (int i = 0; i < count; i++) {
            final ViewGroup layout = (ViewGroup) getChildAt(i);
            layout.setDrawingCacheEnabled(false);
        }
    }
	
	
	void initWorkspace(Context context) {		
		mDisplay = ((WindowManager)context.getSystemService(Context.WINDOW_SERVICE)).getDefaultDisplay();
		setHapticFeedbackEnabled(false);
        mScrollInterpolator = new WorkspaceOvershootInterpolator();
        mScroller = new Scroller(context, mScrollInterpolator);
        mCurrentScreen = mDefaultScreen;

        final ViewConfiguration configuration = ViewConfiguration.get(getContext());
        mTouchSlop = configuration.getScaledTouchSlop();
        mMaximumVelocity = configuration.getScaledMaximumFlingVelocity();
        mOverscrollDistance = configuration.getScaledOverscrollDistance();
    }
	
	
	public void scrollLeft() {
		if (mScroller.isFinished()) {
			if (mCurrentScreen > 0) {
				snapToScreen(mCurrentScreen - 1);
			}
		} else {
			if (mNextScreen > 0) {
				snapToScreen(mNextScreen - 1);
			}
		}
	}

	public void scrollRight() {
		if (mScroller.isFinished()) {
			if (mCurrentScreen < getChildCount() - 1) {
				snapToScreen(mCurrentScreen + 1);
			}
		} else {
			if (mNextScreen < getChildCount() - 1) {
				snapToScreen(mNextScreen + 1);
			}
		}
	}
	
	public void moveToDefaultScreen(boolean animate) {
        if (animate) {
            snapToScreen(mDefaultScreen);
        } else {
            setCurrentScreen(mDefaultScreen);
        }
        getChildAt(mDefaultScreen).requestFocus();
    }
    
    public void moveToCurrentScreen() {
        setCurrentScreen(mCurrentScreen);
    }
	
	/**
     * @return True is long presses are still allowed for the current touch
     */
    public boolean allowLongPress() {
        return mAllowLongPress;
    }

    /**
     * Set true to allow long-press events to be triggered, usually checked by
     * Launcher to accept or block dpad-initiated long-presses.
     */
    public void setAllowLongPress(boolean allowLongPress) {
        mAllowLongPress = allowLongPress;
    }
    
	
	public void setCurrentScreen(int currentScreen) {
        if (!mScroller.isFinished()) {
            mScroller.abortAnimation();
        }
        mCurrentScreen = Math.max(0, Math.min(currentScreen, getChildCount() - 1));
        notifyScrollChanged(mCurrentScreen);
        int width = mDisplay.getWidth();
        Log.d(TAG, "width is: " + width);
        scrollTo(mCurrentScreen * width, 0);
        invalidate();
    }
	
	public void setScrollToScreenCallback(ScrollToScreenCallback scrollToScreenCallback) {
        mScrollToScreenCallback = scrollToScreenCallback;
    }
	
	
	Field mFieldScrollX, mFieldScrollY;
	void setScrollXInner(int scrollX) {
		try {
			if (mFieldScrollX == null) {
				mFieldScrollX = View.class.getDeclaredField("mScrollX");
			}
			Field field = mFieldScrollX;
			if (!field.isAccessible()) field.setAccessible(true);
			field.set(this, scrollX);
		} catch (Exception e) {
			Log.e(TAG, "setScrollXInner::" + e);
		}
	}
	
	void setScrollYInner(int scrollY) {
		try {
			if (mFieldScrollY == null) {
				mFieldScrollY = View.class.getDeclaredField("mScrollY");
			}
			Field field = mFieldScrollY;
			if (!field.isAccessible()) field.setAccessible(true);
			field.set(this, scrollY);
		} catch (Exception e) {
			Log.e(TAG, "setScrollYInner::" + e);
		}
	}
}
