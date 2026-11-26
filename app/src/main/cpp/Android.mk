LOCAL_PATH := $(call my-dir)

# libmobi v0.12 (commit 85dcfe803fc2a21020ddcf15c3eb66b93d388add), unmodified upstream C sources.
# Only the two JNI probe methods are exported in T01; full parsing bridge lands in T02.
include $(CLEAR_VARS)
LOCAL_MODULE := openleafmobi

LOCAL_C_INCLUDES := $(LOCAL_PATH)/libmobi/src
MOBI_SRC_FILES := $(patsubst $(LOCAL_PATH)/%,%,$(wildcard $(LOCAL_PATH)/libmobi/src/*.c))
LOCAL_SRC_FILES := mobi_bridge.c $(MOBI_SRC_FILES)

# No HAVE_CONFIG_H: libmobi's src/config.h then needs no generated root config.h.
# PACKAGE_VERSION makes mobi_version() return the pinned version instead of "unknown".
# USE_MINIZ selects the bundled miniz (src/util.h); no system zlib dependency.
LOCAL_CFLAGS := -DUSE_MINIZ -DPACKAGE_VERSION='"0.12"' -Wall -Wno-unused-parameter -Wno-unused-function
LOCAL_LDLIBS := -llog

include $(BUILD_SHARED_LIBRARY)
