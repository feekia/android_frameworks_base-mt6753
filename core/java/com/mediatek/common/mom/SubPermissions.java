package com.mediatek.common.mom;

/**
 * This class defined permissions to be monitored.
 * Each sub-permission has an parent permission defined by android,
 * and the operation can be executed only with all of the
 * corresponding permissions are granted.
 * To add a item here should sync to PermissionRecordHelper.java, too.
 * @hide
 */
public class SubPermissions {
    /** READ_SMS. */
    public static final String QUERY_SMS = "sub-permission.QUERY_SMS";
    /** READ_SMS. */
    public static final String QUERY_MMS = "sub-permission.QUERY_MMS";
    /** WRITE_SMS. */
    public static final String MODIFY_SMS = "sub-permission.MODIFY_SMS";
    /** WRITE_SMS. */
    public static final String MODIFY_MMS = "sub-permission.MODIFY_MMS";
    /** READ_CONTACTS. */
    public static final String QUERY_CONTACTS = "sub-permission.QUERY_CONTACTS";
    /** WRITE_CONTACTS. */
    public static final String MODIFY_CONTACTS = "sub-permission.MODIFY_CONTACTS";
    /** READ_CALL_LOG. */
    public static final String QUERY_CALL_LOG = "sub-permission.QUERY_CALL_LOG";
    /** WRITE_CALL_LOG. */
    public static final String MODIFY_CALL_LOG = "sub-permission.MODIFY_CALL_LOG";
    /** SEND_SMS. */
    public static final String SEND_SMS = "sub-permission.SEND_SMS";
    /** SEND_EMAIL. */
    public static final String SEND_EMAIL = "sub-permission.SEND_EMAIL";
    /** INTERNET. */
    public static final String SEND_MMS = "sub-permission.SEND_MMS";
    /** ACCESS_FINE_LOCATION. */
    public static final String ACCESS_LOCATION = "sub-permission.ACCESS_LOCATION";
    /** RECORD_AUDIO. */
    public static final String RECORD_MIC = "sub-permission.RECORD_MIC";
    /** CAMERA. */
    public static final String OPEN_CAMERA = "sub-permission.OPEN_CAMERA";
    /** CALL_PHONE. */
    public static final String MAKE_CALL = "sub-permission.MAKE_CALL";
    /** CALL_PHONE. */
    public static final String MAKE_CONFERENCE_CALL = "sub-permission.MAKE_CONFERENCE_CALL";
    /** CHANGE_NETWORK_STATE. */
    public static final String CHANGE_NETWORK_STATE_ON = "sub-permission.CHANGE_NETWORK_STATE_ON";
    /** CHANGE_WIFI_STATE. */
    public static final String CHANGE_WIFI_STATE_ON = "sub-permission.CHANGE_WIFI_STATE_ON";
    /** BLUETOOTH_ADMIM. */
    public static final String CHANGE_BT_STATE_ON = "sub-permission.CHANGE_BT_STATE_ON";
    /** READ_PHONE_STATE. */
    public static final String READ_PHONE_IMEI = "sub-permission.READ_PHONE_IMEI";
    /** HOTKNOT. */
    public static final String ACCESS_HOTKNOT = "sub-permission.ACCESS_HOTKNOT";
}
