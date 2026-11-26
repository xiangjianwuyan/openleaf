/*
 * OpenLeaf JNI bridge over libmobi (0.14.0 T02).
 *
 * Parsing only.  NO decryption API is referenced here: USE_ENCRYPTION is not
 * defined in Android.mk, and every content path is gated by mobi_is_encrypted().
 * Encrypted documents are returned to Java as an opaque handle so that
 * MobiNative.read() can raise EncryptedDocumentException BEFORE any body parse.
 */
#include <jni.h>
#include <stdint.h>
#include <stdlib.h>
#include <string.h>
#include <strings.h>
#include <android/log.h>
#include "mobi.h"

#define LOG_TAG "OpenLeafMobi"
#define OL_MAX_SEGMENT_BYTES (16u * 1024u * 1024u)
#define OL_MAX_TOTAL_BYTES   (64u * 1024u * 1024u)

typedef struct {
    MOBIData *m;
    MOBIRawml *rawml;   /* NULL until ensure_parsed() */
    char *title;        /* UTF-8, strdup'ed */
    char **segments;    /* UTF-8 plain text, one per reconstructed flow part */
    int count;
    int encrypted;
    int parsed;
} OlHandle;

/* ---------- helpers ---------- */

static OlHandle *as_handle(jlong h) {
    return (h == 0) ? NULL : (OlHandle *) (intptr_t) h;
}

static void free_handle(OlHandle *h) {
    int i;
    if (h == NULL) return;
    if (h->segments != NULL) {
        for (i = 0; i < h->count; i++) free(h->segments[i]);
        free(h->segments);
    }
    if (h->rawml != NULL) mobi_free_rawml(h->rawml);
    if (h->m != NULL) mobi_free(h->m);
    free(h->title);
    free(h);
}

/**
 * Convert UTF-8 bytes to a Java String without relying on NewStringUTF's
 * modified-UTF8 rules (raw markup may contain NUL / malformed sequences).
 * Invalid sequences become U+FFFD; surrogate pairs are encoded explicitly.
 */
static jstring to_jstring(JNIEnv *env, const char *data, size_t len) {
    jchar *chars;
    size_t cap = len + 2, n = 0, i = 0;
    jstring result;
    if (data == NULL || len == 0) return (*env)->NewStringUTF(env, "");
    if (cap > (size_t) (INT32_MAX / 2)) return NULL;
    chars = (jchar *) malloc(cap * sizeof(jchar));
    if (chars == NULL) return NULL;
    while (i < len) {
        unsigned char c = (unsigned char) data[i];
        unsigned int cp;
        int extra;
        if (c < 0x80) {
            cp = c; extra = 0;
        } else if ((c & 0xE0) == 0xC0) {
            cp = c & 0x1Fu; extra = 1;
        } else if ((c & 0xF0) == 0xE0) {
            cp = c & 0x0Fu; extra = 2;
        } else if ((c & 0xF8) == 0xF0) {
            cp = c & 0x07u; extra = 3;
        } else {
            cp = 0xFFFDu; extra = 0;
        }
        i++;
        if (i + (size_t) extra > len) { cp = 0xFFFDu; extra = 0; }
        for (int k = 0; k < extra; k++) {
            unsigned char d = (unsigned char) data[i + k];
            if ((d & 0xC0) != 0x80) { cp = 0xFFFDu; extra = 0; break; }
            cp = (cp << 6) | (d & 0x3Fu);
        }
        i += (size_t) extra;
        if (cp > 0x10FFFFu || (cp >= 0xD800u && cp <= 0xDFFFu)) cp = 0xFFFDu;
        if (cp <= 0xFFFFu) {
            chars[n++] = (jchar) cp;
        } else {
            cp -= 0x10000u;
            chars[n++] = (jchar) (0xD800u + (cp >> 10));
            chars[n++] = (jchar) (0xDC00u + (cp & 0x3FFu));
        }
    }
    result = (*env)->NewString(env, chars, (jsize) n);
    free(chars);
    return result;
}

/** True when the tag should become a line break in plain text. */
static int break_tag(const char *tag, size_t len) {
    static const char *const names[] = {
        "br", "hr", "p", "/p", "div", "/div", "li", "/li", "tr", "/tr",
        "h1", "/h1", "h2", "/h2", "h3", "/h3", "h4", "/h4", "h5", "/h5",
        "h6", "/h6", "blockquote", "/blockquote", "mbp:pagebreak"
    };
    size_t k;
    if (len == 0 || len > 15) return 0;
    for (k = 0; k < sizeof(names) / sizeof(names[0]); k++) {
        const char *name = names[k];
        if (strlen(name) == len && strncasecmp(tag, name, len) == 0) return 1;
    }
    return 0;
}

/** Minimal UTF-8 HTML -> plain text; keeps paragraph boundaries for the JVM splitter. */
static char *html_to_text(const unsigned char *src, size_t len, size_t budget) {
    char *out;
    size_t n = 0, i = 0, limit = len < budget ? len : budget;
    if (limit == 0) return NULL;
    out = (char *) malloc(limit + 16);
    if (out == NULL) return NULL;
    while (i < limit) {
        unsigned char c = src[i];
        if (c == '<') {
            size_t start = ++i;
            while (i < limit && src[i] != '>') i++;
            if (i < limit) {
                size_t tlen = i - start;
                while (tlen > 0 && (src[start] == ' ' || src[start] == '\t')) { start++; tlen--; }
                if (break_tag((const char *) src + start, tlen) && n > 0 && out[n - 1] != '\n') out[n++] = '\n';
                i++;
            }
            continue;
        }
        if (c == '&') {
            /* Copy the entity verbatim; the JVM splitter keeps it as text. */
            out[n++] = (char) c; i++;
            continue;
        }
        if (c >= 0x20 || c == '\n' || c == '\r' || c == '\t') out[n++] = (char) c;
        i++;
    }
    out[n] = '\0';
    return out;
}

/* ---------- lazy parse ---------- */

static int ensure_parsed(OlHandle *h) {
    MOBIPart *part;
    int n = 0, i = 0;
    size_t total = 0;
    MOBI_RET ret;
    if (h == NULL || h->encrypted) return 0;
    if (h->parsed) return h->count;
    h->parsed = 1;
    h->rawml = mobi_init_rawml(h->m);
    if (h->rawml == NULL) return 0;
    /* parse_toc=true, parse_dict=false, reconstruct=true (no decryption). */
    ret = mobi_parse_rawml_opt(h->rawml, h->m, true, false, true);
    if (ret != MOBI_SUCCESS) {
        __android_log_print(ANDROID_LOG_WARN, LOG_TAG, "rawml parse failed: %d", (int) ret);
        mobi_free_rawml(h->rawml);
        h->rawml = NULL;
        return 0;
    }
    for (part = h->rawml->flow; part != NULL; part = part->next) {
        if (part->data != NULL && part->size > 0) n++;
    }
    if (n == 0) return 0;
    h->segments = (char **) calloc((size_t) n, sizeof(char *));
    if (h->segments == NULL) return 0;
    for (part = h->rawml->flow; part != NULL && i < n; part = part->next) {
        size_t budget = OL_MAX_SEGMENT_BYTES;
        if (part->data == NULL || part->size == 0) continue;
        if (total >= OL_MAX_TOTAL_BYTES) break;
        if (OL_MAX_TOTAL_BYTES - total < budget) budget = OL_MAX_TOTAL_BYTES - total;
        h->segments[i] = html_to_text(part->data, part->size, budget);
        if (h->segments[i] != NULL) {
            total += strlen(h->segments[i]);
            i++;
        }
    }
    h->count = i;
    return h->count;
}

/* ---------- JNI probes (T01, kept for QA) ---------- */

JNIEXPORT jboolean JNICALL
Java_org_openleaf_reader_MobiNative_available(JNIEnv *env, jclass clazz) {
    (void) env; (void) clazz;
    return JNI_TRUE;
}

JNIEXPORT jstring JNICALL
Java_org_openleaf_reader_MobiNative_version(JNIEnv *env, jclass clazz) {
    (void) clazz;
    const char *v = mobi_version();
    return (*env)->NewStringUTF(env, v == NULL ? "unknown" : v);
}

/* ---------- JNI parsing bridge (T02, signatures per design §5.4) ---------- */

JNIEXPORT jlong JNICALL
Java_org_openleaf_reader_MobiNative_nativeOpen(JNIEnv *env, jclass clazz, jstring path) {
    OlHandle *h;
    const char *chars;
    MOBI_RET ret;
    (void) clazz;
    if (path == NULL) return 0;
    h = (OlHandle *) calloc(1, sizeof(OlHandle));
    if (h == NULL) return 0;
    h->m = mobi_init();
    if (h->m == NULL) { free(h); return 0; }
    chars = (*env)->GetStringUTFChars(env, path, NULL);
    if (chars == NULL) { mobi_free(h->m); free(h); return 0; }
    ret = mobi_load_filename(h->m, chars);
    (*env)->ReleaseStringUTFChars(env, path, chars);
    if (ret != MOBI_SUCCESS) {
        __android_log_print(ANDROID_LOG_WARN, LOG_TAG, "load failed: %d", (int) ret);
        free_handle(h);
        return 0;
    }
    h->encrypted = mobi_is_encrypted(h->m) ? 1 : 0;
    h->title = mobi_meta_get_title(h->m); /* strdup'ed by libmobi, free()'d by us */
    return (jlong) (intptr_t) h;
}

JNIEXPORT jstring JNICALL
Java_org_openleaf_reader_MobiNative_nativeTitle(JNIEnv *env, jclass clazz, jlong handle) {
    OlHandle *h = as_handle(handle);
    (void) clazz;
    if (h == NULL || h->title == NULL) return (*env)->NewStringUTF(env, "");
    return to_jstring(env, h->title, strlen(h->title));
}

JNIEXPORT jint JNICALL
Java_org_openleaf_reader_MobiNative_nativeChapterCount(JNIEnv *env, jclass clazz, jlong handle) {
    (void) env; (void) clazz;
    return (jint) ensure_parsed(as_handle(handle));
}

JNIEXPORT jstring JNICALL
Java_org_openleaf_reader_MobiNative_nativeChapterText(JNIEnv *env, jclass clazz,
                                                     jlong handle, jint index) {
    OlHandle *h = as_handle(handle);
    (void) clazz;
    if (h == NULL || index < 0 || ensure_parsed(h) == 0 || index >= h->count) {
        return (*env)->NewStringUTF(env, "");
    }
    return to_jstring(env, h->segments[index], strlen(h->segments[index]));
}

JNIEXPORT jboolean JNICALL
Java_org_openleaf_reader_MobiNative_nativeIsEncrypted(JNIEnv *env, jclass clazz, jlong handle) {
    OlHandle *h = as_handle(handle);
    (void) env; (void) clazz;
    return (h != NULL && h->encrypted) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_org_openleaf_reader_MobiNative_nativeClose(JNIEnv *env, jclass clazz, jlong handle) {
    (void) env; (void) clazz;
    free_handle(as_handle(handle));
}
