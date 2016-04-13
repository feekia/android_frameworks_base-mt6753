LOCAL_PATH:= $(call my-dir)

# the library
# ============================================================
include $(CLEAR_VARS)

# NEW Feature: HALL keyguard UI. hsm
LOCAL_SRC_FILES := $(call all-java-files-under, src) \
	src/com/xolo/music/IMediaPlaybackService.aidl

//LOCAL_SRC_FILES += $(call all-java-files-under, src) \
            
LOCAL_MODULE := android.policy

include $(BUILD_JAVA_LIBRARY)

ifeq ($(strip $(BUILD_MTK_API_DEP)), yes)
# android.policy API table.
# ============================================================
LOCAL_MODULE := android.policy-api

LOCAL_STATIC_JAVA_LIBRARIES := 
LOCAL_MODULE_CLASS := JAVA_LIBRARIES

# NEW Feature: HALL keyguard UI. hsm
LOCAL_JAVA_LIBRARIES += telephony-common  mms-common

LOCAL_DROIDDOC_OPTIONS:= \
		-api $(TARGET_OUT_COMMON_INTERMEDIATES)/PACKAGING/android.policy-api.txt \
		-nodocs \
		-hidden

include $(BUILD_DROIDDOC)
endif

# additionally, build unit tests in a separate .apk
include $(call all-makefiles-under,$(LOCAL_PATH))
