package com.android.internal.policy.impl.hall;


import android.app.KeyguardManager;
import android.content.Context;
import android.os.Handler;
import android.os.Message;
import android.os.PowerManager;
import android.util.AttributeSet;
import android.util.Log;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.RelativeLayout;
import android.widget.TextView;
import android.app.Activity;
import android.media.AudioManager;
import android.media.IAudioService;
import android.os.RemoteException;
import android.os.ServiceManager;
import android.telephony.TelephonyManager;
import com.android.internal.R;


import android.app.ActivityManagerNative;
import android.os.Binder;
import android.os.UserHandle;
import android.content.Intent;


/**
 * NEW Feature: HALL keyguard UI
 * 
 * @author chongxishen
 *
 */
public class HallKeyguardView extends FrameLayout {
    static final String TAG = "HallKeyguardView";
    static final WindowManager.LayoutParams PP = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT);
    static final boolean IMPL_OPT = true;
    
    Context mContext;
    boolean mShowKeyguard = false;
    LayoutInflater mLayoutInflater;
    
    private final Handler mHandler;
    
    
    public HallKeyguardView(Context context) {
        this(context, null);
    }

    public HallKeyguardView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public HallKeyguardView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        
        mHandler = new Handler(true);
        
        init(context);
    }

    
    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();        
    }

    @Override
    protected void onDetachedFromWindow() {        
        super.onDetachedFromWindow();
    }
    
    void init(Context context) {
        mContext = context;
        mLayoutInflater = (LayoutInflater)mContext.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        if (IMPL_OPT) {
            final View v = mLayoutInflater.inflate(R.layout.hall_keyguard, this, false);
            addView(v, PP);
        }
    }
    
    public void showKeyguard() {
        if (!mShowKeyguard) {
            if (!IMPL_OPT) {
                final View v = mLayoutInflater.inflate(R.layout.hall_keyguard, this, false);
                addView(v, PP);
            }
        }        
        mShowKeyguard = true;
    }
    
    public void hideKeyguard() {
        if (mShowKeyguard) {
            if (!IMPL_OPT) removeAllViews();
        }      
        mShowKeyguard = false;
    }
    
    
    
    
    
    
    private AudioManager mAudioManager;
    private TelephonyManager mTelephonyManager = null;
    private static final boolean KEYGUARD_MANAGES_VOLUME = true;
    
    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (interceptMediaKey(event)) {
            return true;
        }
        return super.dispatchKeyEvent(event);
    }

    /**
     * Allows the media keys to work when the keyguard is showing.
     * The media keys should be of no interest to the actual keyguard view(s),
     * so intercepting them here should not be of any harm.
     * @param event The key event
     * @return whether the event was consumed as a media key.
     */
    private boolean interceptMediaKey(KeyEvent event) {
        final int keyCode = event.getKeyCode();
        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            switch (keyCode) {
                case KeyEvent.KEYCODE_MEDIA_PLAY:
                case KeyEvent.KEYCODE_MEDIA_PAUSE:
                case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                    /* Suppress PLAY/PAUSE toggle when phone is ringing or
                     * in-call to avoid music playback */
                    if (mTelephonyManager == null) {
                        mTelephonyManager = (TelephonyManager) getContext().getSystemService(Context.TELEPHONY_SERVICE);
                    }
                    if (mTelephonyManager != null &&
                            mTelephonyManager.getCallState() != TelephonyManager.CALL_STATE_IDLE) {
                        return true;  // suppress key event
                    }
                case KeyEvent.KEYCODE_MUTE:
                case KeyEvent.KEYCODE_HEADSETHOOK:
                case KeyEvent.KEYCODE_MEDIA_STOP:
                case KeyEvent.KEYCODE_MEDIA_NEXT:
                case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                case KeyEvent.KEYCODE_MEDIA_REWIND:
                case KeyEvent.KEYCODE_MEDIA_RECORD:
                case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                case KeyEvent.KEYCODE_MEDIA_AUDIO_TRACK: {
                    handleMediaKeyEvent(event);
                    return true;
                }

                case KeyEvent.KEYCODE_VOLUME_UP:
                case KeyEvent.KEYCODE_VOLUME_DOWN:
                case KeyEvent.KEYCODE_VOLUME_MUTE: {
                    if (KEYGUARD_MANAGES_VOLUME) {
                        synchronized (this) {
                            if (mAudioManager == null) {
                                mAudioManager = (AudioManager) getContext().getSystemService(Context.AUDIO_SERVICE);
                            }
                        }
                        // Volume buttons should only function for music (local or remote).
                        // TODO: Actually handle MUTE.
                        /// M: Handle Music and FM radio two cases @{
                        int  direction = (keyCode == KeyEvent.KEYCODE_VOLUME_UP
                                        ? AudioManager.ADJUST_RAISE
                                        : AudioManager.ADJUST_LOWER);
                        if (mAudioManager.isMusicActive()) {
                            // TODO: Actually handle MUTE.
                            mAudioManager.adjustLocalOrRemoteStreamVolume(AudioManager.STREAM_MUSIC, direction);
                        } else if (mAudioManager.isFmActive()) {
                            mAudioManager.adjustLocalOrRemoteStreamVolume(AudioManager.STREAM_FM, direction);
                        }
                        /// @}
                        // Don't execute default volume behavior
                        return true;
                    } else {
                        return false;
                    }
                }
            }
        } else if (event.getAction() == KeyEvent.ACTION_UP) {
            switch (keyCode) {
                case KeyEvent.KEYCODE_MUTE:
                case KeyEvent.KEYCODE_HEADSETHOOK:
                case KeyEvent.KEYCODE_MEDIA_PLAY:
                case KeyEvent.KEYCODE_MEDIA_PAUSE:
                case KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE:
                case KeyEvent.KEYCODE_MEDIA_STOP:
                case KeyEvent.KEYCODE_MEDIA_NEXT:
                case KeyEvent.KEYCODE_MEDIA_PREVIOUS:
                case KeyEvent.KEYCODE_MEDIA_REWIND:
                case KeyEvent.KEYCODE_MEDIA_RECORD:
                case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:
                case KeyEvent.KEYCODE_MEDIA_AUDIO_TRACK: {
                    handleMediaKeyEvent(event);
                    return true;
                }
                case KeyEvent.KEYCODE_POWER:
                case KeyEvent.KEYCODE_VOLUME_UP:
                case KeyEvent.KEYCODE_VOLUME_DOWN:
                case KeyEvent.KEYCODE_VOLUME_MUTE:
                case KeyEvent.KEYCODE_CAMERA:
                case KeyEvent.KEYCODE_FOCUS:{
                    mHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            Intent intent = new Intent("com.android.hall.ALARM_CONTROL");
                            mContext.sendBroadcastAsUser(intent, UserHandle.ALL);
                        }
                    });
                }
                
            }
        }
        return false;
    }

    void handleMediaKeyEvent(KeyEvent keyEvent) {
        IAudioService audioService = IAudioService.Stub.asInterface(ServiceManager.checkService(Context.AUDIO_SERVICE));
        if (audioService != null) {
            try {
                audioService.dispatchMediaKeyEvent(keyEvent);
            } catch (RemoteException e) {
                Log.e(TAG, "dispatchMediaKeyEvent threw exception " + e);
            }
        } else {
            Log.w(TAG, "Unable to find IAudioService for media key event");
        }
    }

    @Override
    public void dispatchSystemUiVisibilityChanged(int visibility) {
        super.dispatchSystemUiVisibilityChanged(visibility);

        if (!(mContext instanceof Activity)) {
            setSystemUiVisibility(STATUS_BAR_DISABLE_BACK);
        }
    }
}
