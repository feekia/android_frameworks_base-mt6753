#
# Copyright (C) 2014 MediaTek Inc.
# Modification based on code covered by the mentioned copyright
# and/or permission notice(s).
#
# Copyright (C) 2011 The Android Open Source Project
#
# Licensed under the Apache License, Version 2.0 (the "License");
# you may not use this file except in compliance with the License.
# You may obtain a copy of the License at
#
#      http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.

LOCAL_PATH := $(call my-dir)
include $(CLEAR_VARS)

LOCAL_MODULE_TAGS := tests

LOCAL_AAPT_FLAGS := --auto-add-overlay --extra-packages com.android.systemui:com.android.keyguard
LOCAL_SRC_FILES := $(call all-java-files-under, src) \
    $(call all-java-files-under, ../src) \
    src/com/android/systemui/EventLogTags.logtags

LOCAL_RESOURCE_DIR := $(LOCAL_PATH)/res \
    frameworks/base/packages/SystemUI/res \
    frameworks/base/packages/Keyguard/res

LOCAL_JAVA_LIBRARIES := android.test.runner telephony-common\
                        mediatek-framework

LOCAL_PACKAGE_NAME := SystemUITests

LOCAL_STATIC_JAVA_LIBRARIES := mockito-target Keyguard gson-systemui-test

# sign this with platform cert, so this test is allowed to inject key events into
# UI it doesn't own. This is necessary to allow screenshots to be taken
LOCAL_CERTIFICATE := platform

LOCAL_INSTRUMENTATION_FOR := SystemUI

include $(BUILD_PACKAGE)
######
include $(CLEAR_VARS)
LOCAL_PREBUILT_STATIC_JAVA_LIBRARIES := gson-systemui-test:libs/gson-2.3.1.jar
include $(BUILD_MULTI_PREBUILT)
#include $(BUILD_PREBUILT)
