package com.mediatek.rns;

import static com.android.internal.util.Preconditions.checkNotNull;
import static com.mediatek.rns.RnsPolicy.POLICY_NAME_PREFERENCE;
import static com.mediatek.rns.RnsPolicy.POLICY_NAME_ROVE_THRESHOLD;
import static com.mediatek.rns.RnsPolicy.UserPreference.PREFERENCE_NONE;
import static com.mediatek.rns.RnsPolicy.UserPreference.PREFERENCE_WIFI_ONLY;
import static com.mediatek.rns.RnsPolicy.UserPreference.PREFERENCE_WIFI_PREFERRED;
import static com.mediatek.rns.RnsPolicy.UserPreference.PREFERENCE_CELLULAR_ONLY;
import static com.mediatek.rns.RnsPolicy.UserPreference.PREFERENCE_CELLULAR_PREFERRED;

import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.ContentObserver;

import android.net.ConnectivityManager;
import android.net.NetworkCapabilities;
import android.net.Network;
import android.net.NetworkRequest;
import android.net.NetworkInfo;
import android.net.wifi.WifiManager;

import android.os.AsyncTask;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Message;
import android.os.Messenger;
import android.os.SystemProperties;
import android.provider.Settings;

import android.telephony.PhoneStateListener;
import android.telephony.TelephonyManager;
import android.telephony.ServiceState;
import android.util.Slog;
import android.widget.Toast;

import com.android.internal.util.IndentingPrintWriter;


import java.io.FileDescriptor;
import java.io.PrintWriter;

import java.util.HashMap;

/**
 * Radio Network Selection Service.
 */
public class RnsServiceImpl extends IRnsManager.Stub {

    private final String TAG = "RnsService";
    private final boolean DEBUG = true;
    private final int DISCONNECT_RSSI = -200;
    private Context mContext;
    private ConnectivityManager mConnMgr;
    private WifiManager mWifiMgr;
    private TelephonyManager mTeleMgr;
    private InternalHandler mHandler;
    private AsyncTask<Void, Void, Void> mWifiTask;
    private boolean mIsWifiConnected = false;
    private boolean mIsWifiEnabled = false;
    private int mAllowedRadio;
    private HashMap<String, RnsPolicy> mPolicies = new HashMap<String, RnsPolicy>();
    private int mState = RnsManager.STATE_DEFAULT;

    private boolean mIsWfcEnabled = false;
    private ServiceState mLtePhoneState;
    private WifiCallingSettingsObserver mWfcSettingsObserver;
    // sequence number of NetworkRequests
    //private int mNextNetworkRequestId = 1;
    private int mLastRssi;
    private long mStartTime ;
    private static final NetworkRequest REQUEST = new NetworkRequest.Builder()
    .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN)
    .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_RESTRICTED)
    .removeCapability(NetworkCapabilities.NET_CAPABILITY_TRUSTED)
    .build();
    private boolean isLteImsConnected = false;
    private boolean isEpdgImsConnected = false;
    /**
     * constructor of rns service.
     * @param context from system server
     */
    public RnsServiceImpl(Context context) {
        mContext = checkNotNull(context, "missing Context");
        mConnMgr = (ConnectivityManager) mContext.getSystemService(
                       Context.CONNECTIVITY_SERVICE);
        mWifiMgr = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        Slog.d(TAG, "Current RSSI on constructor: " + mWifiMgr.getConnectionInfo().getRssi());
        HandlerThread handlerThread = new HandlerThread("RnsServiceThread");
        handlerThread.start();
        mHandler = new InternalHandler(handlerThread.getLooper());

        IntentFilter filter = new IntentFilter();
        filter.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        filter.addAction(WifiManager.RSSI_CHANGED_ACTION);
        filter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
        filter.addAction(ConnectivityManager.CONNECTIVITY_ACTION_IMMEDIATE);
        mContext.registerReceiver(mIntentReceiver, filter);

        mTeleMgr = (TelephonyManager) mContext.getSystemService(
                                        Context.TELEPHONY_SERVICE);
        mTeleMgr.listen(mPhoneStateListener, PhoneStateListener.LISTEN_SERVICE_STATE);

        mWfcSettingsObserver
            = new WifiCallingSettingsObserver(mHandler, EVENT_APPLY_WIFI_CALL_SETTINGS);
        mWfcSettingsObserver.observe(mContext);
        //create default policies for UT/IT
        createDefaultPolicies();
    }

    /**
     *
     * Start function for service.
     */
    public void start() {
        mHandler.obtainMessage(EVENT_APPLY_WIFI_CALL_SETTINGS).sendToTarget();
        mConnMgr.registerNetworkCallback(REQUEST, mNetworkCallback);
        mStartTime = System.currentTimeMillis();
    }

    protected BroadcastReceiver mIntentReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();

            if (action.equals(WifiManager.NETWORK_STATE_CHANGED_ACTION)) {
                final NetworkInfo networkInfo = (NetworkInfo)
                        intent.getParcelableExtra(WifiManager.EXTRA_NETWORK_INFO);
                synchronized (this) {
                mIsWifiConnected = (networkInfo != null && networkInfo.isConnected());
                }
                Slog.d(TAG, "onReceive: NETWORK_STATE_CHANGED_ACTION connected = "
                       + mIsWifiConnected);
                if(mIsWifiConnected == false){
                    mLastRssi = DISCONNECT_RSSI;
                    mHandler.sendMessage(mHandler.obtainMessage(EVENT_WIFI_WIFI_DISCONNECT, 0)); 
                }
                /*if (mIsWifiConnected) {
                    mWifiTask = new WifiRssiMonitor();
                    mWifiTask.execute();
                } else {
                    mWifiTask.cancel(true);
                    mWifiTask = null;
                }*/
            } else if (action.equals(WifiManager.WIFI_STATE_CHANGED_ACTION)) {
                mIsWifiEnabled =
                    intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE,
                    WifiManager.WIFI_STATE_UNKNOWN) == WifiManager.WIFI_STATE_ENABLED;
                Slog.d(TAG, "onReceive: WIFI_STATE_CHANGED_ACTION enable = " + mIsWifiEnabled);
            } else if (action.equals(WifiManager.RSSI_CHANGED_ACTION)) {
                int rssi = intent.getIntExtra(WifiManager.EXTRA_NEW_RSSI, 0);
                mHandler.sendMessage(mHandler.obtainMessage(EVENT_WIFI_RSSI_UPDATE, rssi, 0));
            } else if (action.equals(ConnectivityManager.CONNECTIVITY_ACTION_IMMEDIATE)) {
                final NetworkInfo networkInfo = (NetworkInfo)
                        intent.getParcelableExtra(ConnectivityManager.EXTRA_NETWORK_INFO);
                if (networkInfo != null) {
                    Slog.d(TAG, "onReceive: CONNECTIVITY_ACTION_IMMEDIATE");
                    String typename = networkInfo.getTypeName();
                    String subtypename =  networkInfo.getSubtypeName();
                    Slog.d(TAG, "typename = " + typename + " subtypename = " + subtypename);
                    if ("MOBILE_IMS".equals(typename) && "LTE".equals(subtypename)) {
                       isLteImsConnected = networkInfo.isConnected();
                    } else if ("Wi-Fi".equals(typename) && "IMS".equals(subtypename)) {
                        isEpdgImsConnected = networkInfo.isConnected();
                    }
                    Slog.d(TAG, "isLteImsConnected = " + isLteImsConnected +
                           " isEpdgImsConnected = " + isEpdgImsConnected);
                }
            }
        }
    };

    /**
     * settings of wifi calling.
     */
    private static class WifiCallingSettingsObserver extends ContentObserver {
        private int mWhat;
        private Handler mHandler;
        WifiCallingSettingsObserver(Handler handler, int what) {
            super(handler);
            mHandler = handler;
            mWhat = what;
        }

        void observe(Context context) {
            ContentResolver resolver = context.getContentResolver();
            resolver.registerContentObserver(
                Settings.System.getUriFor(Settings.System.WHEN_TO_MAKE_WIFI_CALLS),
                                          false, this);

            resolver.registerContentObserver(
                Settings.System.getUriFor(Settings.System.RNS_USER_PREFERENCE),
                                          false, this);

            resolver.registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.RNS_WIFI_ROVE_IN_RSSI),
                                          false, this);

            resolver.registerContentObserver(
                Settings.Global.getUriFor(Settings.Global.RNS_WIFI_ROVE_OUT_RSSI),
                                          false, this);

        }

        @Override
        public void onChange(boolean selfChange) {
            mHandler.obtainMessage(mWhat).sendToTarget();
        }
    }

    private void createDefaultPolicies() {
        RnsPolicy policy;

        RnsPolicy.UserPreference preference =
            new RnsPolicy.UserPreference(PREFERENCE_WIFI_PREFERRED);
        policy = new RnsPolicy(preference);
        mPolicies.put(POLICY_NAME_PREFERENCE, policy);

        RnsPolicy.WifiRoveThreshold threshold =
            new RnsPolicy.WifiRoveThreshold(-75, -85);
        policy = new RnsPolicy(threshold);
        mPolicies.put(POLICY_NAME_ROVE_THRESHOLD, policy);
    }

    private final PhoneStateListener mPhoneStateListener = new PhoneStateListener() {
        @Override
        public void onServiceStateChanged(ServiceState serviceState) {
            mLtePhoneState = serviceState;
            Slog.d(TAG, "onServiceStateChanged:" + mLtePhoneState.getState());
        }
    };

    private void tryConnectToRadio(int radio) {
        Slog.d(TAG, "tryConnectToRadio:" + radio);
        mConnMgr.connectToRadio(radio);
    }

    @Override
    public int getAllowedRadioList(int capability) {
        //TODO: make radio by capability, ims or mms ...etc
        switch (capability) {
            case ConnectivityManager.TYPE_MOBILE_IMS:
        return makeImsRadio();
        default:
                return makeMmsRadio();
        }
    }

    @Override
    public int getTryAnotherRadioType(int failedNetType) {
        int profile = PREFERENCE_NONE;
        int netType = ConnectivityManager.TYPE_NONE;
        RnsPolicy policy = mPolicies.get(POLICY_NAME_PREFERENCE);
        if (policy.getUserPreference() != null) {
            profile = policy.getUserPreference().getMode();
        }
        //Handover case
        if (isHandoverInProgress()) {
            if (mState == RnsManager.STATE_ROVEIN &&
                    failedNetType == ConnectivityManager.TYPE_WIFI) {
                Slog.d(TAG, "RoveIn failed:" +
                            (System.currentTimeMillis() - mStartTime) + " msec.");
            } else if (mState == RnsManager.STATE_ROVEOUT &&
                       failedNetType == ConnectivityManager.TYPE_MOBILE) {
                Slog.d(TAG, "RoveOut failed:" +
                            (System.currentTimeMillis() - mStartTime) + " msec.");
            }
            mState = RnsManager.STATE_DEFAULT;
        }

        //initial connection fail and try another case
        switch(failedNetType) {
        case ConnectivityManager.TYPE_WIFI:
            if (profile == PREFERENCE_WIFI_ONLY) {
                Slog.d(TAG, "PREFERENCE_WIFI_ONLY - no need try another");
            } else if (profile == PREFERENCE_WIFI_PREFERRED) {
                Slog.d(TAG, "isLteNetworkReady "+ isLteNetworkReady()+" mIsWfcEnabled "+ mIsWfcEnabled);
                if (isLteNetworkReady() && mIsWfcEnabled) {
                    netType = ConnectivityManager.TYPE_MOBILE;
                    mStartTime = System.currentTimeMillis();
                }
            }
            break;
        case ConnectivityManager.TYPE_MOBILE:
            if (profile == PREFERENCE_CELLULAR_ONLY) {
                Slog.d(TAG, "PREFERENCE_CELLULAR_ONLY - no need try another");
            } else if (profile == PREFERENCE_CELLULAR_PREFERRED) {
                Slog.d(TAG, "isWifiConnected "+ isWifiConnected() + "mIsWfcEnabled "+ mIsWfcEnabled);
                if (isWifiConnected() && mIsWfcEnabled) {
                    netType = ConnectivityManager.TYPE_WIFI;
                    mStartTime = System.currentTimeMillis();
                }
            }
            break;
        default:
            break;
        }
        Slog.d(TAG, "needTryAnotherRadio = " + netType + ":" + failedNetType + ":" + profile);
        return netType;
    }

    @Override
    public int getRnsState() {
        return mState;
    }

    private int makeImsRadio() {
        mAllowedRadio = 0;
        RnsPolicy policy = mPolicies.get(POLICY_NAME_PREFERENCE);
        if (policy.getUserPreference() != null) {
            int profile = policy.getUserPreference().getMode();


            switch (profile) {
                case PREFERENCE_WIFI_ONLY:
                    if (isWifiConnected() && mIsWfcEnabled) {
                        addRadio(RnsManager.ALLOWED_RADIO_WIFI);
                    } else {
                        addRadio(RnsManager.ALLOWED_RADIO_DENY);
                    }
                    break;
                case PREFERENCE_WIFI_PREFERRED:
                    if (isWifiConnected() && mIsWfcEnabled && mWifiMgr.getConnectionInfo().getRssi() >
                        mPolicies.get(POLICY_NAME_ROVE_THRESHOLD).getWifiRoveThreshold().getRssiRoveIn()) {
                        addRadio(RnsManager.ALLOWED_RADIO_WIFI);
                    } else if (isLteNetworkReady()) {
                        addRadio(RnsManager.ALLOWED_RADIO_MOBILE);
                    } else if (isWifiConnected() && mIsWfcEnabled) {
                        /* This case was required to establish
                           connection even if RSSI strength is not so strong*/
                        addRadio(RnsManager.ALLOWED_RADIO_WIFI);
                        Slog.d(TAG, "Establishing connection over" + 
                            "Wifi even the RSSI strength is less than Rove in value");
                    } else {
                        addRadio(RnsManager.ALLOWED_RADIO_DENY);
                    }
                    break;
                case PREFERENCE_CELLULAR_ONLY:
                    if (isLteNetworkReady()) {
                        addRadio(RnsManager.ALLOWED_RADIO_MOBILE);
                    } else {
                        addRadio(RnsManager.ALLOWED_RADIO_DENY);
                    }
                    break;
                case PREFERENCE_CELLULAR_PREFERRED:
                    if (isLteNetworkReady()) {
                        addRadio(RnsManager.ALLOWED_RADIO_MOBILE);
                    } else if (isWifiConnected() && mIsWfcEnabled) {
                        addRadio(RnsManager.ALLOWED_RADIO_WIFI);
                    } else {
                        addRadio(RnsManager.ALLOWED_RADIO_DENY);
                    }
                    break;
                default:
                    break;
            }
            /*// Following are from TMO requirements
            if (!mIsWfcEnabled || !isWifiConnected() &&
                profile != PREFERENCE_WIFI_ONLY && isLteNetworkReady()) {
                addRadio(RnsManager.ALLOWED_RADIO_MOBILE);
            } else if (isWifiConnected() && mIsWfcEnabled == false &&
                       isLteNetworkReady()) {
                addRadio(RnsManager.ALLOWED_RADIO_MOBILE);
            } else if (isWifiConnected() && mIsWfcEnabled == true &&
                        profile == PREFERENCE_WIFI_PREFERRED && isLteNetworkReady()) {
                addRadio(RnsManager.ALLOWED_RADIO_WIFI);
            } else if (isWifiConnected() && mIsWfcEnabled == true &&
                        profile == PREFERENCE_CELLULAR_PREFERRED && !isLteNetworkReady()) {
                addRadio(RnsManager.ALLOWED_RADIO_WIFI);
            } else if (isWifiConnected() && mIsWfcEnabled == true &&
                        profile == PREFERENCE_WIFI_ONLY) {
                addRadio(RnsManager.ALLOWED_RADIO_WIFI);
            }*/
        }

        return transToReadableType(mAllowedRadio);
    }

    private int makeMmsRadio() {
        mAllowedRadio = 0;
        RnsPolicy policy = mPolicies.get(POLICY_NAME_PREFERENCE);
        if (policy.getUserPreference() != null) {
            int profile = policy.getUserPreference().getMode();


            switch (profile) {
                case PREFERENCE_WIFI_ONLY:
                    if (isWifiConnected() && mIsWfcEnabled) {
                        addRadio(RnsManager.ALLOWED_RADIO_WIFI);
                    } else {
                        addRadio(RnsManager.ALLOWED_RADIO_DENY);
                    }
                    break;
                case PREFERENCE_WIFI_PREFERRED:
                    if (isWifiConnected() && mIsWfcEnabled && mWifiMgr.getConnectionInfo().getRssi() >
                        mPolicies.get(POLICY_NAME_ROVE_THRESHOLD).getWifiRoveThreshold().getRssiRoveIn()) {
                        addRadio(RnsManager.ALLOWED_RADIO_WIFI);
                    } else if (isLteNetworkReady()) {
                        addRadio(RnsManager.ALLOWED_RADIO_MOBILE);
                    } else if (isWifiConnected() && mIsWfcEnabled) {
                        /* This case was required to establish
                           connection even if RSSI strength is not so strong*/
                        addRadio(RnsManager.ALLOWED_RADIO_WIFI);
                        Slog.d(TAG, "Establishing connection over" +
                            "Wifi even the RSSI strength is less than Rove in value");
                    } else if (isNetworkReady()) {
                        addRadio(RnsManager.ALLOWED_RADIO_MOBILE);
                    } else {
                        addRadio(RnsManager.ALLOWED_RADIO_DENY);
                    }
                    break;
                case PREFERENCE_CELLULAR_ONLY:
                    if (isNetworkReady()) {
                        addRadio(RnsManager.ALLOWED_RADIO_MOBILE);
                    } else {
                        addRadio(RnsManager.ALLOWED_RADIO_DENY);
                    }
                    break;
                case PREFERENCE_CELLULAR_PREFERRED:
                    if (isNetworkReady()) {
                        addRadio(RnsManager.ALLOWED_RADIO_MOBILE);
                    } else if (isWifiConnected() && mIsWfcEnabled) {
                        addRadio(RnsManager.ALLOWED_RADIO_WIFI);
                    } else {
                        addRadio(RnsManager.ALLOWED_RADIO_DENY);
                    }
                    break;
                default:
                    break;
            }
        }

        return transToReadableType(mAllowedRadio);
    }
    private boolean isWifiConnected() {
        synchronized (this) {
        return mIsWifiEnabled && mIsWifiConnected;
    }
    }

    private boolean isLteNetworkReady() {
        if (mLtePhoneState != null &&
            mLtePhoneState.getState() == ServiceState.STATE_IN_SERVICE) {
            //TODO: uncomment after IT done
            return (mTeleMgr.getNetworkType() == TelephonyManager.NETWORK_TYPE_LTE);
        }
        return false;
    }

    private boolean isNetworkReady() {
        if (mLtePhoneState != null &&
            mLtePhoneState.getState() == ServiceState.STATE_IN_SERVICE) {
            return true;
        }
        return false;
    }

    private int[] enumerateBits(long val) {
        int size = Long.bitCount(val);
        int[] result = new int[size];
        int index = 0;
        int resource = 0;
        while (val > 0) {
            if ((val & 1) == 1) { result[index++] = resource; }
            val = val >> 1;
            resource++;
        }
        return result;
    }

    private int transToReadableType(int val) {
        //simple impl. here, can be extended in the future
        if (val == 1) {
            Slog.d(TAG, "makeImsRadio = ALLOWED_RADIO_WIFI");
            return RnsManager.ALLOWED_RADIO_WIFI;
        } else if (val == 2) {
            Slog.d(TAG, "makeImsRadio = ALLOWED_RADIO_MOBILE");
            return RnsManager.ALLOWED_RADIO_MOBILE;
        } else if (val == 4) {
            Slog.d(TAG, "makeImsRadio = ALLOWED_RADIO_DENY");
            return RnsManager.ALLOWED_RADIO_DENY;
        }
         else if (val == 8) {
            Slog.d(TAG, "makeImsRadio = ALLOWED_RADIO_MAX");
            return RnsManager.ALLOWED_RADIO_MAX;
        }
        Slog.d(TAG, "makeImsRadio = ALLOWED_RADIO_NONE");
        return RnsManager.ALLOWED_RADIO_NONE;
    }

    private void addRadio(int connectionType) {
        if (connectionType < RnsManager.ALLOWED_RADIO_MAX) {
            mAllowedRadio |= 1 << connectionType;
        } else {
            throw new IllegalArgumentException("connectionType out of range");
        }
    }

    private boolean isMatchRoveIn() {
        int profile = PREFERENCE_NONE;
        RnsPolicy policy = mPolicies.get(POLICY_NAME_PREFERENCE);

        if (policy.getUserPreference() != null) {
            profile = policy.getUserPreference().getMode();
        }

        //1. The Handover is not initiated from handset if the WFC
        //   preference is set "Cellular only" mode.
        if (profile == PREFERENCE_CELLULAR_ONLY || profile == PREFERENCE_NONE) {
            Slog.d(TAG, "isMatchRoveIn = false, cellular only/none");
            return false;
        }

        //2. RAT signal strength criteria not met.
        policy = mPolicies.get(POLICY_NAME_ROVE_THRESHOLD);
        if (policy.getWifiRoveThreshold() != null) {

            if (mLastRssi > policy.getWifiRoveThreshold().getRssiRoveIn()) {
                Slog.d(TAG, "isMatchRoveIn signal strength criteria met");
            } else {
                Slog.d(TAG, "isMatchRoveIn = false, rssi issue");
                return false;
            }
        }

        //3. check current pdn connections status
       /* if (isEpdgImsConnected || (!isEpdgImsConnected && !isLteImsConnected)) {
            Slog.d(TAG, "isMatchRoveIn = false, check pdn connection");
            return false;
        }*/
        if (isEpdgImsConnected) {
            Slog.d(TAG, "isMatchRoveIn = false, check pdn connection");
            return false;
        }

        if (isLteImsConnected && profile == PREFERENCE_CELLULAR_PREFERRED) {
            Slog.d(TAG, "isMatchRoveIn = false, cellular preferred");
            return false;
        }

        if (isWifiConnected() && mIsWfcEnabled && System.currentTimeMillis() - mStartTime > 2000) {
            return true;
        }

        Slog.d(TAG, "isMatchRoveIn = false");
        return false;
    }

    private boolean isMatchRoveOut() {
        int profile = PREFERENCE_NONE;
        RnsPolicy policy = mPolicies.get(POLICY_NAME_PREFERENCE);

        if (policy.getUserPreference() != null) {
            profile = policy.getUserPreference().getMode();
        }

        //1. The Handover is not initiated from handset if the WFC
        //   preference is set "Wi-Fi only" mode.
        if (profile == PREFERENCE_WIFI_ONLY || profile == PREFERENCE_NONE) {
            Slog.d(TAG, "isMatchRoveOut = false, profile issue");
            return false;
        }

        //2. RAT signal strength criteria not met.
        policy = mPolicies.get(POLICY_NAME_ROVE_THRESHOLD);
        if (policy.getWifiRoveThreshold() != null && profile != PREFERENCE_CELLULAR_PREFERRED) {
            if (mLastRssi < policy.getWifiRoveThreshold().getRssiRoveOut()) {
                Slog.d(TAG, "isMatchRoveOut signal strength criteria met");
            } else {
                Slog.d(TAG, "isMatchRoveOut = false, rssi issue");
                return false;
            }
        }

        //3. check current pdn connections status
        if (isLteImsConnected) {
            Slog.d(TAG, "isMatchRoveOut = false, check pdn connection");
            return false;
        }


        if (mIsWfcEnabled && isLteNetworkReady() &&
            System.currentTimeMillis() - mStartTime > 2000) {
            return true;
        }

        Slog.d(TAG, "isMatchRoveOut = false");
        return false;
    }

    private void decideHandover() {
        //TODO: consider a pdn is connecting case, we should not trigger hadover
        if (isHandoverInProgress()) {
            Slog.d(TAG, "decideHandover - handover in progress");
            return ;
        }
        RnsPolicy policy = mPolicies.get(POLICY_NAME_PREFERENCE);
        if (policy.getUserPreference() != null) {
            int profile = policy.getUserPreference().getMode();
            switch (profile) {
                case PREFERENCE_WIFI_ONLY:
                    if (isWifiConnected() && mIsWfcEnabled) {
                        startRoveIn();
                    }
                    break;
                case PREFERENCE_WIFI_PREFERRED:
                    if (isWifiConnected() && mIsWfcEnabled && mWifiMgr.getConnectionInfo().getRssi() >
                        mPolicies.get(POLICY_NAME_ROVE_THRESHOLD).getWifiRoveThreshold().getRssiRoveIn()) {
                        //addRadio(RnsManager.ALLOWED_RADIO_WIFI);
                        startRoveIn();
                    } else if (isLteNetworkReady()) {
                        startRoveOut();
                    } else if (isWifiConnected() && mIsWfcEnabled) {
                        /* This case was required to establish
                           connection even if RSSI strength is not so strong*/
                        //addRadio(RnsManager.ALLOWED_RADIO_WIFI);
                        Slog.d(TAG, "Establishing connection over" + 
                            "Wifi even the RSSI strength is less than Rove in value");
                        startRoveIn();
                    }
                    break;
                case PREFERENCE_CELLULAR_ONLY:
                    if (isLteNetworkReady()) {
                        startRoveOut();
                    }
                    break;
                case PREFERENCE_CELLULAR_PREFERRED:
                    if (isLteNetworkReady()) {
                        startRoveOut();
                    } else if (isWifiConnected() && mIsWfcEnabled) {
                        startRoveIn();
                    }
                    break;
                default:
                    break;
            }

        }
    }

    /* LTE -> Wifi(ePDG) */
    private void startRoveIn() {
        Slog.d(TAG, "startRoveIn");

        //Check ePDG is active then return
        if (isEpdgImsConnected) {
            Slog.d(TAG, "No rove-in");
            if (mState == RnsManager.STATE_ROVEIN) {
      			mState = RnsManager.STATE_DEFAULT;
            }
            return;
        }

        mStartTime = System.currentTimeMillis();
        synchronized (this) {
            if (mState == RnsManager.STATE_ROVEIN) {
                Slog.d(TAG, "RoveIn is in progress");
                return;
            }
            showToast("startRoveIn");
            mState = RnsManager.STATE_ROVEIN;
            tryConnectToRadio(ConnectivityManager.TYPE_WIFI);
        }
    }

    /* Wifi(ePDG) -> LTE */
    private void startRoveOut() {
        Slog.d(TAG, "startRoveOut");

        //Check ePDG is active then return
        if (isLteImsConnected) {
            Slog.d(TAG, "No rove-out");
            if (mState == RnsManager.STATE_ROVEOUT) {
      			mState = RnsManager.STATE_DEFAULT;
            }
            return;
        }

        mStartTime = System.currentTimeMillis();
        synchronized (this) {
            if (mState == RnsManager.STATE_ROVEOUT) {
                Slog.d(TAG, "RoveOut is in progress");
                return;
            }
            showToast("startRoveOut");
            mState = RnsManager.STATE_ROVEOUT;
            tryConnectToRadio(ConnectivityManager.TYPE_MOBILE);
        }
    }

    private boolean isHandoverInProgress() {
        synchronized (this) {
            return (mState == RnsManager.STATE_ROVEOUT) || (mState == RnsManager.STATE_ROVEIN);
        }
    }
    /*private synchronized int nextNetworkRequestId() {
        return mNextNetworkRequestId++;
    }*/

    ConnectivityManager.NetworkCallback mNetworkCallback
        = new ConnectivityManager.NetworkCallback() {

        @Override
        public void onAvailable(Network network) {
            Slog.d(TAG, "NetworkCallback - onAvailable:" + network);
            if (isHandoverInProgress()) {
                mState = RnsManager.STATE_DEFAULT;
            }
        }

        @Override
        public void onUnavailable() {
            Slog.d(TAG, "NetworkCallback - onUnavailable");
        }

        @Override
        public void onLost(Network network) {
            Slog.d(TAG, "NetworkCallback - onLost:" + network);
        }
    };

    private static final int EVENT_WIFI_RSSI_UPDATE = 0;
    private static final int EVENT_REGISTER_RNS_AGENT = 1;
    private static final int EVENT_APPLY_WIFI_CALL_SETTINGS = 10;
    private static final int EVENT_WIFI_WIFI_DISCONNECT = 100;

    /**
     * internal handler for events.
     */
    private class InternalHandler extends Handler {
        public InternalHandler(Looper looper) {
            super(looper);
        }

        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
            case EVENT_WIFI_RSSI_UPDATE:
                handleEventWifiRssiUpdate(msg.arg1);
                break;
            case EVENT_REGISTER_RNS_AGENT:
                break;
            case EVENT_APPLY_WIFI_CALL_SETTINGS:
                handleEventApplyWifiCallSettings();
                break;
            case EVENT_WIFI_WIFI_DISCONNECT:
                handleEventWifiDisconnect();
                break;
            default:
                Slog.d(TAG, "Unknown message");
                break;
            }
        }
    }

    private void handleEventWifiRssiUpdate(int newRssi) {
        Slog.d(TAG, "handleEventWifiRssiUpdate: " + mLastRssi);
        if (DEBUG) {
            int testRssi = SystemProperties.getInt("persist.net.test.rssi", 0);
            if (testRssi != 0) {
                newRssi = testRssi;
            }
        }
            mLastRssi = newRssi;
        if (isWifiConnected()) {
            decideHandover();
        }
    }

    private void handleEventApplyWifiCallSettings() {
        mIsWfcEnabled = TelephonyManager.WifiCallingChoices.ALWAYS_USE ==
                        Settings.System.getInt(mContext.getContentResolver(),
                                               Settings.System.WHEN_TO_MAKE_WIFI_CALLS, -1);
        Slog.d(TAG, "handleEventApplyWifiCallSettings, mIsWfcEnabled = " + mIsWfcEnabled);
        RnsPolicy policy = mPolicies.get(POLICY_NAME_PREFERENCE);
        if (policy.getUserPreference() != null) {
            policy.getUserPreference().setMode(
                Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.RNS_USER_PREFERENCE, 0));
            Slog.d(TAG, " Preference = " + policy.getUserPreference().getMode());
        }

        policy = mPolicies.get(POLICY_NAME_ROVE_THRESHOLD);
        if (policy.getWifiRoveThreshold() != null) {
            policy.getWifiRoveThreshold().setRssiRoveIn(
                Settings.Global.getInt(mContext.getContentResolver(),
                    Settings.Global.RNS_WIFI_ROVE_IN_RSSI, 0));

            policy.getWifiRoveThreshold().setRssiRoveOut(
                Settings.Global.getInt(mContext.getContentResolver(),
                    Settings.Global.RNS_WIFI_ROVE_OUT_RSSI, 0));
            Slog.d(TAG, " RoveIn = " + policy.getWifiRoveThreshold().getRssiRoveIn() +
                   " RoveOut = " + policy.getWifiRoveThreshold().getRssiRoveOut());
        }

        decideHandover();
    }

    @Override
    public void registerRnsAgent(String name, Messenger messenger) {

    }
    public void handleEventWifiDisconnect() {

        int profile = PREFERENCE_NONE;
        RnsPolicy policy = mPolicies.get(POLICY_NAME_PREFERENCE);

        if (policy.getUserPreference() != null) {
            profile = policy.getUserPreference().getMode();
        }
        Slog.d(TAG, "handleEventWifiDisconnect ");
        if (profile == PREFERENCE_CELLULAR_PREFERRED || profile == PREFERENCE_WIFI_PREFERRED) {
            decideHandover();                    
        }           
    }
    /**
     * Wifi Rssi Monitor, consider to remove.
     */
    private class WifiRssiMonitor extends AsyncTask<Void, Void, Void> {

        WifiRssiMonitor() {
            try {
                Thread.sleep(10);
            } catch (InterruptedException ie) {
                return;
            }
        }

        @Override
        protected Void doInBackground(Void... params) {
            checkWifi();
            return null;
        }

        private void checkWifi() {
            Slog.d(TAG, "checkWifi");
        }
    }

    @Override
    protected void dump(FileDescriptor fd, PrintWriter writer, String[] args) {
        final IndentingPrintWriter pw = new IndentingPrintWriter(writer, "  ");

        int i = 0;
        pw.println("Policies:");
        pw.increaseIndent();
        for (String key : mPolicies.keySet()) {
            pw.println(i + "  policy[" + key + "]: " + mPolicies.get(key));
            i++;
        }
        pw.println("(none(-1)|wifi_only(0)|wifi_preferred(1)" +
                   "|cellular_only(2)|cellular_preferred(3))");

        pw.decreaseIndent();
        pw.println();
        pw.println("Status:");
        pw.increaseIndent();
        pw.println("isWifiConnected = " + isWifiConnected());
        pw.println("isWfcEnabled = " + mIsWfcEnabled);
        pw.println("isHandoverInProgress = " + isHandoverInProgress());
        pw.println("isLteNetworkReady = " + isLteNetworkReady());
        pw.println("isLteImsConnected = " + isLteImsConnected);
        pw.println("isEpdgImsConnected = " + isEpdgImsConnected);
        pw.decreaseIndent();
        pw.println();
        pw.println("Radio Selection: " + makeImsRadio());
        pw.println("none(-1)|wifi(0)|moible(1)|all(2)");
    }

    private void dump() {
        Slog.d(TAG, "--- dump ---");
        for (String key : mPolicies.keySet()) {
            Slog.d(TAG, "policy[" + key + "]:" + mPolicies.get(key));
        }
        Slog.d(TAG, "isWifiConnected = " + isWifiConnected());
        Slog.d(TAG, "isWfcEnabled = " + mIsWfcEnabled);
        Slog.d(TAG, "isLteNetworkReady = " + isLteNetworkReady());
        Slog.d(TAG, "--- end ---");
    }

    private void showToast(String s) {
        Toast toast = Toast.makeText(mContext, s, Toast.LENGTH_LONG);
        toast.show();
    }
}

