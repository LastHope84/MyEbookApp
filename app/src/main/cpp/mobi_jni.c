#include <jni.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <sys/stat.h>
#include "mobi.h"

static int write_file_bytes(const char *dir, const char *filename, const unsigned char *data, size_t size) {
    char path[1024];
    int n = snprintf(path, sizeof(path), "%s/%s", dir, filename);
    if (n < 0 || (size_t)n >= sizeof(path)) {
        return -1;
    }
    FILE *f = fopen(path, "wb");
    if (f == NULL) {
        return -1;
    }
    if (size > 0 && fwrite(data, 1, size, f) != size) {
        fclose(f);
        return -1;
    }
    fclose(f);
    return 0;
}

JNIEXPORT jstring JNICALL
Java_com_example_myebookapp_MobiNative_unpackToDir(
        JNIEnv *env,
        jobject thiz,
        jstring inputPath_,
        jstring outputOebpsDir_) {
    (void)thiz;

    const char *inputPath = (*env)->GetStringUTFChars(env, inputPath_, NULL);
    const char *outputDir = (*env)->GetStringUTFChars(env, outputOebpsDir_, NULL);

    if (inputPath == NULL || outputDir == NULL) {
        if (inputPath) (*env)->ReleaseStringUTFChars(env, inputPath_, inputPath);
        if (outputDir) (*env)->ReleaseStringUTFChars(env, outputOebpsDir_, outputDir);
        return (*env)->NewStringUTF(env, "ERR:Invalid file paths");
    }

    MOBIData *m = mobi_init();
    if (m == NULL) {
        (*env)->ReleaseStringUTFChars(env, inputPath_, inputPath);
        (*env)->ReleaseStringUTFChars(env, outputOebpsDir_, outputDir);
        return (*env)->NewStringUTF(env, "ERR:Memory initialization failed");
    }

    MOBI_RET ret = mobi_load_filename(m, inputPath);
    if (ret != MOBI_SUCCESS) {
        mobi_free(m);
        (*env)->ReleaseStringUTFChars(env, inputPath_, inputPath);
        (*env)->ReleaseStringUTFChars(env, outputOebpsDir_, outputDir);
        return (*env)->NewStringUTF(env, "ERR:Could not load MOBI/AZW3 file");
    }

    if (mobi_is_encrypted(m)) {
        mobi_free(m);
        (*env)->ReleaseStringUTFChars(env, inputPath_, inputPath);
        (*env)->ReleaseStringUTFChars(env, outputOebpsDir_, outputDir);
        return (*env)->NewStringUTF(env, "ERR:This file is DRM-protected and cannot be converted");
    }

    MOBIRawml *rawml = mobi_init_rawml(m);
    if (rawml == NULL) {
        mobi_free(m);
        (*env)->ReleaseStringUTFChars(env, inputPath_, inputPath);
        (*env)->ReleaseStringUTFChars(env, outputOebpsDir_, outputDir);
        return (*env)->NewStringUTF(env, "ERR:Could not initialize parser");
    }

    ret = mobi_parse_rawml(rawml, m);
    if (ret != MOBI_SUCCESS) {
        mobi_free_rawml(rawml);
        mobi_free(m);
        (*env)->ReleaseStringUTFChars(env, inputPath_, inputPath);
        (*env)->ReleaseStringUTFChars(env, outputOebpsDir_, outputDir);
        return (*env)->NewStringUTF(env, "ERR:Failed to parse MOBI/AZW3 contents");
    }

    mkdir(outputDir, 0755);

    char name[256];
    int writeErr = 0;

    if (rawml->markup != NULL) {
        MOBIPart *curr = rawml->markup;
        while (curr != NULL && !writeErr) {
            MOBIFileMeta meta = mobi_get_filemeta_by_type(curr->type);
            snprintf(name, sizeof(name), "part%05zu.%s", curr->uid, meta.extension);
            if (write_file_bytes(outputDir, name, curr->data, curr->size) != 0) {
                writeErr = 1;
            }
            curr = curr->next;
        }
    }

    if (rawml->flow != NULL && !writeErr) {
        MOBIPart *curr = rawml->flow->next;
        while (curr != NULL && !writeErr) {
            MOBIFileMeta meta = mobi_get_filemeta_by_type(curr->type);
            snprintf(name, sizeof(name), "flow%05zu.%s", curr->uid, meta.extension);
            if (write_file_bytes(outputDir, name, curr->data, curr->size) != 0) {
                writeErr = 1;
            }
            curr = curr->next;
        }
    }

    if (rawml->resources != NULL && !writeErr) {
        MOBIPart *curr = rawml->resources;
        while (curr != NULL && !writeErr) {
            MOBIFileMeta meta = mobi_get_filemeta_by_type(curr->type);
            if (curr->size > 0) {
                if (meta.type == T_OPF) {
                    snprintf(name, sizeof(name), "content.opf");
                } else if (meta.type == T_NCX) {
                    snprintf(name, sizeof(name), "toc.ncx");
                } else {
                    snprintf(name, sizeof(name), "resource%05zu.%s", curr->uid, meta.extension);
                }
                if (write_file_bytes(outputDir, name, curr->data, curr->size) != 0) {
                    writeErr = 1;
                }
            }
            curr = curr->next;
        }
    }

    mobi_free_rawml(rawml);
    mobi_free(m);
    (*env)->ReleaseStringUTFChars(env, inputPath_, inputPath);
    (*env)->ReleaseStringUTFChars(env, outputOebpsDir_, outputDir);

    if (writeErr) {
        return (*env)->NewStringUTF(env, "ERR:Failed writing extracted book files");
    }
    return (*env)->NewStringUTF(env, "OK");
}
