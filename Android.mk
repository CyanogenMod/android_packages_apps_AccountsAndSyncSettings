LOCAL_PATH:= $(call my-dir)
include $(CLEAR_VARS)

LOCAL_MODULE_TAGS := user

base_packages := ../../../frameworks/base/packages
source_files := $(base_packages)/SubscribedFeedsProvider/src/com/android/providers/subscribedfeeds/SubscribedFeedsProvider.java
source_files += $(base_packages)/SubscribedFeedsProvider/src/com/android/providers/subscribedfeeds/SubscribedFeedsIntentService.java
source_files += $(base_packages)/SubscribedFeedsProvider/src/com/android/providers/subscribedfeeds/SubscribedFeedsBroadcastReceiver.java

LOCAL_SRC_FILES := $(call all-subdir-java-files) $(source_files)

# We depend on googlelogin-client also, but that is already being included by google-framework
LOCAL_STATIC_JAVA_LIBRARIES := google-framework

LOCAL_OVERRIDES_PACKAGES := SubscribedFeedsProvider

LOCAL_PACKAGE_NAME := GoogleSubscribedFeedsProvider
LOCAL_CERTIFICATE := platform

include $(BUILD_PACKAGE)
