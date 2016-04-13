package com.android.internal.policy.impl.hall;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;
import android.database.Cursor;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Handler;
import android.provider.Settings;
import android.text.format.Time;
import android.util.AttributeSet;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.android.internal.R;

import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.Date;

import android.provider.CallLog.Calls;
//import android.provider.Telephony.Mms;
//import android.provider.Telephony.Sms;
//import android.provider.Telephony.Threads;
//import com.google.android.mms.pdu.PduHeaders;
import libcore.icu.ICU;
import libcore.icu.LocaleData;

/**
 * NEW Feature: HALL keyguard UI
 * 
 * @author hsm
 *
 */
public class HallKeyguardNormalView extends LinearLayout {
    static final String TAG = "HallKeyguardNormalView";
    
    boolean mAttached;
    Context mContext;
    final Time mCalendar = new Time();
    final Handler mHandler = new Handler();
    
    long mQueryBaseTime;
    TextView mDaTextView, mSms, mMissCall;
    LinearLayout mIconContainer;
    
    public HallKeyguardNormalView(Context context) {
        this(context, null);
    }

    public HallKeyguardNormalView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }
    
    public HallKeyguardNormalView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
        initView(context);
    }
    
    void initView(Context context) {
        mContext = context;
        setOrientation(VERTICAL);
        final LayoutInflater inflater = (LayoutInflater)context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
        final View v = inflater.inflate(R.layout.hall_keyguard_body, this, true);
        mDaTextView = (TextView)v.findViewById(R.id.hall_keyguard_date);
        mIconContainer = (LinearLayout)v.findViewById(R.id.hall_keyguard_notify_icons);
        mSms = (TextView)v.findViewById(R.id.hall_keyguard_sms);
        mMissCall = (TextView)v.findViewById(R.id.hall_keyguard_miss_call);
    }
    
    @Override protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        if (!mAttached) {
            mAttached = true;
            setQueryBaseTime();
            
            final IntentFilter filter = new IntentFilter();
            filter.addAction(Intent.ACTION_TIME_TICK);
            filter.addAction(Intent.ACTION_TIME_CHANGED);
            filter.addAction(Intent.ACTION_TIMEZONE_CHANGED);
            mContext.registerReceiver(mIntentReceiver, filter, null, mHandler);            
            registerNewEventObserver();
        }
        
        onTimeChanged();
    }

    @Override protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        if (mAttached) {
            unregisterNewEventObserver();
            mContext.unregisterReceiver(mIntentReceiver);
            mAttached = false;
        }
    }
    
    void onTimeChanged() {
        mCalendar.setToNow();
        Date date = new Date(mCalendar.year-1900, mCalendar.month, mCalendar.monthDay, mCalendar.hour, mCalendar.minute, 0);
        mDaTextView.setText(getDateFormat().format(date));
    }
    
    final BroadcastReceiver mIntentReceiver = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) {
            onTimeChanged();
        }
    };
    
    DateFormat getDateFormat() {
        String format = Settings.System.getString(mContext.getContentResolver(), Settings.System.DATE_FORMAT);
        Log.d(TAG, "getDateFormat: format=" + format);
        if (format == null || "".equals(format)) {
            // return DateFormat.getDateInstance(DateFormat.SHORT);
            return getDateFormatForSetting(mContext, "");
        } else {
            try {
                return new SimpleDateFormat(format);
            } catch (IllegalArgumentException e) {
                // If we tried to use a bad format string, fall back to a default.
                return DateFormat.getDateInstance(DateFormat.SHORT);
            }
        }
    }
    
    ////////////////////////////////////////////////////////////////////////////
    //
    MmsUnReadObserver mMmsUnReadObserver;
    MissCallUnReadObserver mMissCallUnReadObserver;
    
    void registerNewEventObserver() {
        if (mMmsUnReadObserver == null) {
            mMmsUnReadObserver = new MmsUnReadObserver(new Handler(), mSms, mQueryBaseTime);
            mContext.getContentResolver().registerContentObserver(MMS_URI, true, mMmsUnReadObserver);
            mMmsUnReadObserver.refreshUnReadNumber();
        }
        if (mMissCallUnReadObserver == null) {
            mMissCallUnReadObserver = new MissCallUnReadObserver(new Handler(), mMissCall, mQueryBaseTime);
            mContext.getContentResolver().registerContentObserver(MISS_CALL_URI, true, mMissCallUnReadObserver);
            mMissCallUnReadObserver.refreshUnReadNumber();
        }
    }
    
    
    void unregisterNewEventObserver() {
        if (mMmsUnReadObserver != null) {
            mContext.getContentResolver().unregisterContentObserver(mMmsUnReadObserver);
            mMmsUnReadObserver = null;
        }
        if (mMissCallUnReadObserver != null) {
            mContext.getContentResolver().unregisterContentObserver(mMissCallUnReadObserver);
            mMissCallUnReadObserver = null;
        }
    }
    
    void setQueryBaseTime() {
        mQueryBaseTime = 0; // java.lang.System.currentTimeMillis();
    }

    long getQueryBaseTime() {
        return mQueryBaseTime;
    }

    static DateFormat getDateFormatForSetting(Context context, String value) {
        String format = getDateFormatStringForSetting(context, value);
        return new SimpleDateFormat(format);
    }

    static String getDateFormatStringForSetting(Context context, String value) {
        String result = null;
        if (value != null) {
            /// M: add week and arrange month day year according to resource's date format defination for settings. CR: ALPS00049014 @{ 
            String dayValue = value.indexOf("dd") < 0 ? "d" : "dd";
            String monthValue = value.indexOf("MMMM") < 0 ? (value.indexOf("MMM") < 0 ? (value.indexOf("MM") < 0 ? "M" : "MM") : "MMM") : "MMMM";
            String yearValue = value.indexOf("yyyy") < 0 ? "y" : "yyyy";
            String weekValue = value.indexOf("EEEE") < 0 ? "E" : "EEEE";

            int day = value.indexOf(dayValue);
            int month = value.indexOf(monthValue);
            int year = value.indexOf(yearValue);
            int week = value.indexOf(weekValue);
             
            if (week >= 0 && month >= 0 && day >= 0 && year >= 0) {
                String template = null;
                if (week < day) {
                    if (year < month && year < day) {
                        if (month < day) {
                            template = context.getString(com.mediatek.internal.R.string.wday_year_month_day);
                            result = String.format(template, weekValue, yearValue, monthValue, dayValue);
                        } else {
                            template = context.getString(com.mediatek.internal.R.string.wday_year_day_month);
                            result = String.format(template, weekValue, yearValue, dayValue, monthValue);
                        }
                    } else if (month < day) {
                        if (day < year) {
                            template = context.getString(com.mediatek.internal.R.string.wday_month_day_year);
                            result = String.format(template, weekValue, monthValue, dayValue, yearValue);
                        } else {
                            template = context.getString(com.mediatek.internal.R.string.wday_month_year_day);
                            result = String.format(template, weekValue, monthValue, yearValue, dayValue);
                        }
                    } else {
                        if (month < year) {
                            template = context.getString(com.mediatek.internal.R.string.wday_day_month_year);
                            result = String.format(template, weekValue, dayValue, monthValue, yearValue);
                        } else {
                            template = context.getString(com.mediatek.internal.R.string.wday_day_year_month);
                            result = String.format(template, weekValue, dayValue, yearValue, monthValue);
                        }
                    }
                } else {
                    if (year < month && year < day) {
                        if (month < day) {
                            template = context.getString(com.mediatek.internal.R.string.year_month_day_wday);
                            result = String.format(template, yearValue, monthValue, dayValue, weekValue);
                        } else {
                            template = context.getString(com.mediatek.internal.R.string.year_day_month_wday);
                            result = String.format(template, yearValue, dayValue, monthValue, weekValue);
                        }
                    } else if (month < day) {
                        if (day < year) {
                            template = context.getString(com.mediatek.internal.R.string.month_day_year_wday);
                            result = String.format(template, monthValue, dayValue, yearValue, weekValue);
                        } else {
                            template = context.getString(com.mediatek.internal.R.string.month_year_day_wday);
                            result = String.format(template, monthValue, yearValue, dayValue, weekValue);
                        }
                    } else {
                        if (month < year) {
                            template = context.getString(com.mediatek.internal.R.string.day_month_year_wday);
                            result = String.format(template, dayValue, monthValue, yearValue, weekValue);
                        } else {
                            template = context.getString(com.mediatek.internal.R.string.day_year_month_wday);
                            result = String.format(template, dayValue, yearValue, monthValue, weekValue);
                        }
                    }
                }
                
                return result;
            /// M: @} 
            } else if (month >= 0 && day >= 0 && year >= 0) {
                String template = context.getString(R.string.numeric_date_template);
                if (year < month && year < day) {
                    if (month < day) {
                        result = String.format(template, yearValue, monthValue, dayValue);
                    } else {
                        result = String.format(template, yearValue, dayValue, monthValue);
                    }
                } else if (month < day) {
                    if (day < year) {
                        result = String.format(template, monthValue, dayValue, yearValue);
                    } else { // unlikely
                        result = String.format(template, monthValue, yearValue, dayValue);
                    }
                } else { // date < month
                    if (month < year) {
                        result = String.format(template, dayValue, monthValue, yearValue);
                    } else { // unlikely
                        result = String.format(template, dayValue, yearValue, monthValue);
                    }
                }

                return result;
            }
        }

        // The setting is not set; use the locale's default.
        LocaleData d = LocaleData.get(context.getResources().getConfiguration().locale);
        return d.shortDateFormat4;
    }
    
    ////////////////////////////////////////////////////////////////////////////
    //
    final Handler mH = new Handler();
    abstract class UnReadObserver extends ContentObserver {        
        final TextView mNewEventView;        
        
        long mCreateTime;
        int mUnreadNum;
        
        public UnReadObserver(Handler handler, TextView newEventView, long createTime) {
            super(handler);
            mNewEventView = newEventView;
            mCreateTime = createTime;
        }
        
        public void onChange(boolean selfChange) {
            refreshUnReadNumber();
        }
        
        public abstract void refreshUnReadNumber();
        
        public final void upateNewEventNumber(final int unreadNumber) {
            if (mNewEventView != null) {
                final String text = unreadNumber > 99 ? "99+" : String.valueOf(unreadNumber);
                mH.post(new Runnable() {
                    @Override public void run() {
                        try {
                            mNewEventView.setText(text);
                            mNewEventView.setVisibility(unreadNumber > 0 ? View.VISIBLE : View.INVISIBLE);
                        } catch (Exception e) {
                            Log.e(TAG, "upateNewEventNumber: " + e);
                        }
                    }
                });
            } else {
                Log.e(TAG, "mNewEventView is null");
            }
            mUnreadNum = unreadNumber;
        }
        
        // When queryt base time changed, we need to reset new event number
        public void updateQueryBaseTime(long newBaseTime) {
            mCreateTime = newBaseTime;
            upateNewEventNumber(0);
        }
        
        public int getUnreadNumber() {
            return mUnreadNum;
        }
    }

    
    
     // SMS-MMS
     
    public static final Uri MMS_URI = Uri.withAppendedPath(
                Uri.parse("content://mms-sms/"), "conversations");
    private static final Uri MMS_QUERY_URI = Uri.parse("content://mms/inbox");    
    private static final String[] MMS_STATUS_PROJECTION = new String[] {
            "date", "_id"};
        
    private static final String NEW_INCOMING_MM_CONSTRAINT = 
                "(" + "read" + " = 0 "
                + " AND (" + "m_type" + " <> " + 0x86
                + " AND " + "m_type" + " <> " + 0x88 + ") AND "+ "date" + " >= ";
        
    private static final Uri SMS_QUERY_URI =Uri.parse("content://sms");
    private static final String[] SMS_STATUS_PROJECTION = new String[] {
            "date", "_id" };
        
    private static final String NEW_INCOMING_SM_CONSTRAINT =
                "(" + "type" + " = " + 1
                + " AND " + "read" + " = 0 AND "+ "date" + " >= ";
    

    final class MmsUnReadObserver extends UnReadObserver {        
        public MmsUnReadObserver(Handler handler, TextView newEventView, long createTime) {
            super(handler, newEventView, createTime);
        }
			
        
        public void refreshUnReadNumber() {
            new AsyncTask<Void, Void, Integer>() {
                @Override public Integer doInBackground(Void... params) {
                    ///M: Mms's database saves Mms received date as Second, so we need to pass second unit instead of millisecond
                    long queryBaseTime = mCreateTime / 1000;
                    Cursor cursor = mNewEventView.getContext().getContentResolver()
                            .query(MMS_QUERY_URI, MMS_STATUS_PROJECTION,
                                    NEW_INCOMING_MM_CONSTRAINT + queryBaseTime + ")", null, null);
                    int mmsCount = 0;
                    if (cursor != null) {
                        try {
                            mmsCount = cursor.getCount();
                        } finally {
                            cursor.close();
                            cursor = null;
                        }
                    }

                    cursor = mNewEventView.getContext().getContentResolver()
                            .query(SMS_QUERY_URI, SMS_STATUS_PROJECTION,
                                    NEW_INCOMING_SM_CONSTRAINT + mCreateTime + ")", null, null);
                    int smsCount = 0;
                    if (cursor != null) {
                        try {
                            smsCount = cursor.getCount();
                        } finally {
                            cursor.close();
                        }
                    }
                    Log.d(TAG, "refreshUnReadNumber mmsCount=" + mmsCount + ", smsCount=" + smsCount + ", mCreateTime=" + mCreateTime);
                    return mmsCount + smsCount;
                }

                @Override public void onPostExecute(Integer result) {
                    upateNewEventNumber(result);
                }
            }.execute(null, null, null);
        }
    }
	

    
    /**
     * miss call
     */
    public static final Uri MISS_CALL_URI = Calls.CONTENT_URI;
    private static final String[] MISS_CALL_PROJECTION = new String[] {Calls._ID, Calls.NEW, Calls.DATE};
    private static final String MISS_CALL_SELECTION = "(" + Calls.NEW + " = ? AND " +
                Calls.TYPE + " = ? AND " + Calls.IS_READ  + " = ? AND " + Calls.DATE + " >= ";
    private static final String[] MISS_CALL_SELECTION_ARGS = new String[] {
        "1", Integer.toString(Calls.MISSED_TYPE), Integer.toString(0)
        };

    final class MissCallUnReadObserver extends UnReadObserver {        
        public MissCallUnReadObserver(Handler handler, TextView newEventView, long createTime) {
            super(handler, newEventView, createTime);
        }
        
        public void refreshUnReadNumber() {
            new AsyncTask<Void, Void, Integer>() {
                @Override public Integer doInBackground(Void... params) {
                    Cursor cursor = mNewEventView.getContext().getContentResolver()
                            .query(MISS_CALL_URI, MISS_CALL_PROJECTION,
                                    MISS_CALL_SELECTION + mCreateTime + " )", MISS_CALL_SELECTION_ARGS, null);
                    int count = 0;
                    if (cursor != null) {
                        try {
                            count = cursor.getCount();
                        } finally {
                            cursor.close();
                        }
                    }
                    Log.d(TAG, "refreshUnReadNumber count=" + count);
                    return count;
                }

                @Override public void onPostExecute(Integer result) {
                    upateNewEventNumber(result);
                }
            }.execute(null, null, null);
        }
    }
}
