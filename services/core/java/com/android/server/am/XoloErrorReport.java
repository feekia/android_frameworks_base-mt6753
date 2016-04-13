// Decompiled by Jad v1.5.8e. Copyright 2001 Pavel Kouznetsov.
// Jad home page: http://www.geocities.com/kpdus/jad.html
// Decompiler options: packimports(3) nonlb 

package com.android.server.am;

import android.content.*;
import android.content.pm.*;
import android.util.Log;
import android.os.SystemProperties;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import java.io.*;
import org.json.JSONException;
import org.json.JSONObject;

// Referenced classes of package com.android.server.am:
//            ProcessRecord

public class XoloErrorReport {

    public XoloErrorReport() { }


    private static String getAnrStackTrack() {
		String s1 = "";
        StringBuilder stringbuilder = new StringBuilder();
        String s = SystemProperties.get("dalvik.vm.stack-trace-file", null);
        if(s != null && s.length() != 0) {
			File file = new File(s);
			BufferedReader bufferedreader = null;
			BufferedReader bufferedreader1 = null; 
			try {
				bufferedreader1 = new BufferedReader(new FileReader(file));
				boolean flag = false;
				int i =0;
				String s2;
				while((s2 = bufferedreader1.readLine()) != null) {
        			if(s2.startsWith("----- end")) {
						break;
				 	} else {
						stringbuilder.append(s2);
						stringbuilder.append("\n");
						i++;
						if(i <= 300)
							continue;
						else 
							break;
				  }
				}
			} catch(FileNotFoundException e) { 
				e.printStackTrace();
			} catch(IOException ioexception) { 
				ioexception.printStackTrace();
			}
			if(bufferedreader1 != null) {
            	try {
            	    bufferedreader1.close();
        	    } catch(IOException ioexception3) { 
					ioexception3.printStackTrace();
				}
        		s1 = stringbuilder.toString();
			} 
		} else {
			s1 = "";
		}
		return s1;
	}

    protected static void populateAnrData(JSONObject jsonobject, ProcessRecord processrecord) {
        try {
            jsonobject.put("error_type", "anr");
            jsonobject.put("anr_cause", processrecord.notRespondingReport.shortMsg);
            String s;
            if(processrecord.notRespondingReport.tag == null)
                s = ""; 
            else
                s = processrecord.notRespondingReport.tag;
            jsonobject.put("anr_activity", s); 
            jsonobject.put("stack_track", getAnrStackTrack());
        }   
        catch(JSONException jsonexception) {
            jsonexception.printStackTrace();
        }   
    }


	public static void sendAnrErrorReport(Context context, ProcessRecord processrecord, boolean flag) {
       // Intent intent;
		JSONObject jsonobject = new JSONObject();
		populateCommonData(jsonobject, context, processrecord);
		populateAnrData(jsonobject, processrecord);
		//ErrorReportUtils.postErrorReport(context, jsonobject);
        Intent intent = new Intent();
		intent.setAction("com.xolo.errorreporter.REPORT");
		intent.putExtra("extra_fc_report", jsonobject.toString());
		intent.putExtra("ACTIVITY_NAME", getPackageApplicationName(context, processrecord.info.packageName));
		intent.putExtra("PACKAGE_NAME", processrecord.info.packageName);
        intent.setFlags(0x10000000);
        try {
           context.startActivity(intent);
        }catch(ActivityNotFoundException activitynotfoundexception) {
			Log.i("Xolo sendAnrErrorReport", "com.xolo.errorreporter Not found");
			activitynotfoundexception.printStackTrace();
		}
    }

    private static String getDeviceString() {
        String s = SystemProperties.get("ro.product.mod_device", null);
        if(TextUtils.isEmpty(s))
            s = android.os.Build.DEVICE;
        return s;
    }

    private static String getIMEI() {
        String s = TelephonyManager.getDefault().getDeviceId();
        if(TextUtils.isEmpty(s))
            s = "";
        return s;
    }

    private static String getNetworkName(Context context) {
        return ((TelephonyManager)context.getSystemService("phone")).getNetworkOperatorName();
    }

    private static String getPackageVersion(Context context, String s) {
		PackageManager packagemanager = context.getPackageManager();
        String s1;
        PackageInfo packageinfo;
        try {
        	packageinfo = packagemanager.getPackageInfo(s, 0);
       	}
        catch(android.content.pm.PackageManager.NameNotFoundException namenotfoundexception) {
                namenotfoundexception.printStackTrace();
                s1 = "";
				return s1;
        }
       	s1 = (new StringBuilder()).append(packageinfo.versionName).append("-").append(packageinfo.versionCode).toString();
        return s1;
    }

	private static String getPackageApplicationName(Context context, String s) {
		PackageManager lPackageManager = context.getPackageManager();
        String s1;
		ApplicationInfo lApplicationInfo;
	 	try {
        	lApplicationInfo = lPackageManager.getApplicationInfo(s, 0);
	    } catch (android.content.pm.PackageManager.NameNotFoundException e) {
			e.printStackTrace();
            s1 = "";
			return s1;
    	}
		s1 = (String) (lApplicationInfo != null ? lPackageManager.getApplicationLabel(lApplicationInfo) : "");
		return s1;
	}

    protected static void populateCommonData(JSONObject jsonobject, Context context, ProcessRecord processrecord) {
		try {
        	jsonobject.put("network", getNetworkName(context));
        	jsonobject.put("device", getDeviceString());
        	jsonobject.put("imei", getIMEI());
        	jsonobject.put("platform", android.os.Build.VERSION.RELEASE);
        	jsonobject.put("build_version", android.os.Build.VERSION.INCREMENTAL);
        	jsonobject.put("package_name", processrecord.info.packageName);
        	jsonobject.put("app_version", getPackageVersion(context, processrecord.info.packageName));
        	jsonobject.put("app_name", getPackageApplicationName(context, processrecord.info.packageName));
		} catch (JSONException jsonexception) {
			jsonexception.printStackTrace();
		}
        return;
    }

    protected static void populateFcData(JSONObject jsonobject, android.app.ApplicationErrorReport.CrashInfo crashinfo) {
    	if(crashinfo != null)
        	try {
                jsonobject.put("error_type", "fc");
                jsonobject.put("exception_class", crashinfo.exceptionClassName);
				jsonobject.put("exception_message", crashinfo.exceptionClassName);
				jsonobject.put("exception_filename", crashinfo.throwFileName);
                jsonobject.put("exception_classname", crashinfo.throwClassName);
                jsonobject.put("exception_methodname", crashinfo.throwMethodName);
	            jsonobject.put("exception_linenumber", crashinfo.throwLineNumber);
                //jsonobject.put("exception_source_method", (new StringBuilder()).append(crashinfo.throwClassName).append(".").append(crashinfo.throwMethodName).toString());
                jsonobject.put("stack_track", crashinfo.stackTrace);
            }
            catch(JSONException jsonexception) {
                jsonexception.printStackTrace();
            }
    }

    public static void sendFcErrorReport(Context context, ProcessRecord processrecord, android.app.ApplicationErrorReport.CrashInfo crashinfo, boolean flag) {
        JSONObject jsonobject;
        //HIVE
        jsonobject = new JSONObject();
        populateCommonData(jsonobject, context, processrecord);
        populateFcData(jsonobject, crashinfo);
		Intent intent = new Intent();
		intent.setAction("com.xolo.errorreporter.REPORT");
		intent.putExtra("extra_fc_report", jsonobject.toString());
        	intent.putExtra("ACTIVITY_NAME", getPackageApplicationName(context, processrecord.info.packageName));
        	intent.putExtra("PACKAGE_NAME", processrecord.info.packageName);

        intent.setFlags(0x10000000);
        try {
            context.startActivity(intent);
        }
        catch(ActivityNotFoundException activitynotfoundexception) {
			Log.i("HIVE sendFcErrorReport", "com.xolo.errorreporter Not found");
			activitynotfoundexception.printStackTrace();
		}
    }
}
