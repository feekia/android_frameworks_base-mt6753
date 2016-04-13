package com.android.internal.policy.impl.hall;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.RemoteException;
import android.telephony.TelephonyManager;
import android.util.AttributeSet;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import com.android.internal.R;
import android.provider.Settings;
import android.os.Handler;
import android.database.Cursor;
import android.net.Uri;
import android.telephony.SubscriptionManager;
import android.provider.ContactsContract.Contacts;
import android.text.format.DateUtils;





/**
 * 
 * hall phone call and incoming
 * 
 * @author hsm
 *
 */
public class HallPhoneCallView extends LinearLayout implements
		View.OnClickListener {

	private String TAG = HallPhoneCallView.class.getSimpleName();

	static final String[] CONTACTS_SUMMARY_PROJECTION = new String[]
		    {
		        Contacts._ID,
		        Contacts.LOOKUP_KEY,
		        Contacts.PHOTO_ID,
		        Contacts.DISPLAY_NAME_PRIMARY,
		    };
	
	static final int CONTACTS_ID_COLUMN_INDEX = 0;
    static final int CONTACTS_LOOKUP_KEY_COLUMN_INDEX = 1;
    static final int CONTACTS_PHOTO_ID_COLUMN_INDEX = 2;
    static final int CONTACTS_DISPLAY_NAME_COLUMN_INDEX = 3;
	static final int INCOMING_HANDLE = 50;
	static final int OUTGOING_HANDLE = 500;
	
	private final String ANSWER_INTENT="com.malata.hall.answer";

	private final String STATBAR_ANSWER_INTENT="com.android.incallui.ACTION_ANSWER_VOICE_INCOMING_CALL";

	private Context mContext;

	private boolean mIncoming = false;

	private TextView mNumberText;

	private TextView mHitText;

	private View mIncomingLayout;

	private ImageView mCallEnd;

	private TextView mDismiss;

	private TextView mAnswer;
	
	private TextView mOperator;

	private Handler mHandler=null;
	
	private Runnable mRunnable=null;
	
	private boolean mIsSet=false;

	private String mOperatorStr="";

	private String mNativeOperatorStr="";

	private String mCallTime="";


	private TelephonyManager mTelephonyManager;

	public HallPhoneCallView(Context context) {
		this(context, null);
	}

	public HallPhoneCallView(Context context, AttributeSet attrs) {
		this(context, attrs, 0);
	}

	public HallPhoneCallView(Context context, AttributeSet attrs, int defStyle) {
		super(context, attrs, defStyle);
		init(context);
	}

	private void init(Context context) {
		initTelephonyManager(context);
		mContext = context;
		setOrientation(VERTICAL);
		LayoutInflater inflater = (LayoutInflater) mContext
				.getSystemService(Context.LAYOUT_INFLATER_SERVICE);
		View view = inflater.inflate(R.layout.hall_phone, this, true);
		mNumberText = (TextView) view.findViewById(R.id.hall_number);
		mHitText = (TextView) view.findViewById(R.id.hall_hint);
		mCallEnd = (ImageView) view.findViewById(R.id.hall_call_end);
		mIncomingLayout = view.findViewById(R.id.hall_incoming_layout);
		mDismiss = (TextView) view.findViewById(R.id.hall_dismiss);
		mAnswer = (TextView) view.findViewById(R.id.hall_answer);
		mOperator=(TextView)view.findViewById(R.id.hall_operator);
		mAnswer.setOnClickListener(this);
		mDismiss.setOnClickListener(this);
		mCallEnd.setOnClickListener(this);
	}
	
	public boolean getIncoming(){
		return mIncoming;
	}


	public void setIncoming(boolean incoming) {
		mIncoming = incoming;
		setIncomingLayouVisible(incoming);
		
	}

	public void setIncomingLayouVisible(boolean visible){
		if (visible) {
			mIncomingLayout.setVisibility(VISIBLE);
			mCallEnd.setVisibility(GONE);
			mDismiss.setText(mContext.getResources().getString(R.string.hall_dismiss));
			mAnswer.setText(mContext.getResources().getString(R.string.hall_answer));
		} else {
			mIncomingLayout.setVisibility(GONE);
			mCallEnd.setVisibility(VISIBLE);
		}
	}
	
	public void setPhoneNumer(String number){
		if(number==null||"".equals(number)){
			mNumberText.setText(mContext.getResources().getString(R.string.hall_unknown));
		}else{
		   mNumberText.setText(number);
		   setContactsName(mNumberText,number);
		}		
	}

	
	public void setOperator(String operator){
		Log.e("operator",operator);
		if(operator==null||"".equals(operator)){
			mOperator.setVisibility(View.INVISIBLE);
		}else{
			mOperator.setVisibility(View.VISIBLE);
			mNativeOperatorStr=operator;
			mOperator.setText("");
			if(SubscriptionManager.from(mContext).getActiveSubscriptionInfoCount()==1){
				mOperator.setText(mNativeOperatorStr);
			}else if(!"".equals(mOperatorStr)){
			   mOperator.setText(mOperatorStr);
			}
		}
	}
	
	public void setHintText(String hint){
		mHitText.setText(hint);
	}

	public String getHintText(){
		return mHitText.getText().toString();
	}
	private void initTelephonyManager(Context context){
		mTelephonyManager=(TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
	}

	@Override
	protected void onAttachedToWindow() {
		// TODO Auto-generated method stub
		super.onAttachedToWindow();
		globalRegistReceiver();
	}
	
	@Override
	protected void onDetachedFromWindow() {
		// TODO Auto-generated method stub
		super.onDetachedFromWindow();
		globalUnregistReceiver();
	}

	@Override
	public void setVisibility(int visibility) {
		// TODO Auto-generated method stub
		super.setVisibility(visibility);
		switch (visibility) {
		case View.GONE:
		case View.INVISIBLE:
			
			if(mHandler!=null&&mRunnable!=null){
				mHandler.removeCallbacks(mRunnable);
			}
			break;
		case View.VISIBLE:
			setStateHint();
			
			break;
		default:
			break;
		}
	}

	public void setStateHint(){
		if(mIncoming){
			handlerExecute();
			return;
		}
		if(!"".equals(Settings.System.getString(mContext.getContentResolver(),"hall_hit"))){
			setHintText(Settings.System.getString(mContext.getContentResolver(),"hall_hit"));
		}
		if(!"".equals(Settings.System.getString(mContext.getContentResolver(),"hall_number"))){
			setPhoneNumer(Settings.System.getString(mContext.getContentResolver(),"hall_number"));
		}
		handlerExecute();
	}

	int mInt=0;
	private void handlerExecute(){
		 mIsSet=false;
		 mInt=0;
		 final int time= mIncoming ? INCOMING_HANDLE:OUTGOING_HANDLE;
		 mHandler = new Handler(); 
		 mRunnable = new Runnable(){  
         @Override  
         public void run() {  
             // TODO Auto-generated method stub
             /**
             		    * dual cards show operator
             		    */
             if(SubscriptionManager.from(mContext).getActiveSubscriptionInfoCount()!=1){
				  mInt++;
                  if(!"".equals(mOperatorStr)){
				      mOperator.setText(mOperatorStr); 
			      }else{
			          if(mInt>25){
				   	      mOperator.setText(mNativeOperatorStr);
				       }  
			      }
			 }
             /**
             		    * outgoing show number
             		    */
             if(!mIncoming){
		        if(!mIsSet&&!getHintText().equals(Settings.System.getString(mContext.getContentResolver(),"hall_number"))){
			       setPhoneNumer(Settings.System.getString(mContext.getContentResolver(),"hall_number"));
			       mIsSet=true;
			    }
			 }
			 /**
			 * hit call time
			 */
			 if(!"".equals(mCallTime)){
			 	setHintText(mCallTime);
			 }else if(!mIncoming&&!getHintText().equals(Settings.System.getString(mContext.getContentResolver(),"hall_hit"))){
			       setHintText(Settings.System.getString(mContext.getContentResolver(),"hall_hit"));
		     }
			 
             mHandler.postDelayed(this, time);  
         }   
     };
		mHandler.postDelayed(mRunnable, time);
	}

	private void globalRegistReceiver() {
		IntentFilter filter = new IntentFilter();
		filter.addAction("com.malata.incall.state.chenged");
		filter.addAction("com.malata.hall.operator");
		filter.addAction(Intent.ACTION_NEW_OUTGOING_CALL);
		filter.addAction("android.intent.action.PHONE_STATE");
		filter.addAction("com.malata.call.time");
		this.mContext.registerReceiver(mGlobalPhoneStatReceiver, filter);
	}

	private void globalUnregistReceiver() {
		setIncoming(false);
		try {
			mContext.unregisterReceiver(mGlobalPhoneStatReceiver);
		} catch (Exception e) {
			Log.e(TAG, e.getMessage().toString());
		}
	}

	private BroadcastReceiver mGlobalPhoneStatReceiver = new BroadcastReceiver() {

		@Override
		public void onReceive(Context context, final Intent intent) {
			// TODO Auto-generated method stub
			if (intent.getAction().equals("com.malata.incall.state.chenged")) {
					
					Settings.System.putString(mContext.getContentResolver(), "hall_hit",
											  mContext.getResources().getString(R.string.hall_outgoing_call));  
				
			} else if (intent.getAction().equals(
					Intent.ACTION_NEW_OUTGOING_CALL)) {
					Settings.System.putString(mContext.getContentResolver(), "hall_hit",
											  mContext.getResources().getString(R.string.hall_dialing)
					);
					Settings.System.putInt(mContext.getContentResolver(), "hall_outgoing",1);
					String phoneNumber = intent.getStringExtra(Intent.EXTRA_PHONE_NUMBER);
					Settings.System.putString(mContext.getContentResolver(), "hall_number",
											  phoneNumber
					);			 
			} else if(intent.getAction().equals("com.malata.hall.operator")){
			     mOperatorStr=intent.getStringExtra("operator");
			}else if(intent.getAction().equals("com.malata.call.time")){

				long time = intent.getLongExtra("time", 0);
				mCallTime=time<1000? "" : DateUtils.formatElapsedTime(time/1000);
			}
		}
	};

	private void setContactsName(TextView tv,String number){

		Uri uri = Contacts.CONTENT_FILTER_URI;
		Uri queryUri = uri.buildUpon().appendPath(number).build();
		String selection = "has_phone_number=1";

		Cursor c = mContext.getContentResolver().query(queryUri,
				CONTACTS_SUMMARY_PROJECTION, selection, null,
				Contacts.SORT_KEY_PRIMARY);

		if (c != null) {
			while (c.moveToNext()) {

				String name = c.getString(CONTACTS_DISPLAY_NAME_COLUMN_INDEX);
				long mContactId = c.getLong(CONTACTS_ID_COLUMN_INDEX);
				tv.setText(name);
			}
			c.close();
		}
		
	}

	public void onClick(View v) {
		// TODO Auto-generated method stub
		switch (v.getId()) {
		case R.id.hall_dismiss:
				mTelephonyManager.endCall();
			break;
		case R.id.hall_answer:
				//mTelephonyManager.answerRingingCall();
				mContext.sendBroadcast(new Intent(ANSWER_INTENT));
				mContext.sendBroadcast(new Intent(STATBAR_ANSWER_INTENT));
			break;
		case R.id.hall_call_end:
				mTelephonyManager.endCall();
			break;
			
		default:
			break;
		}
	}
}
