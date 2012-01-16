/*
 * DjvuDroidBridge.cpp
 *
 *  Created on: 17.01.2010
 *      Author: Cool
 */

#include <jni.h>
#include <stdlib.h>
#include <DjvuDroidTrace.h>
#include <ddjvuapi.h>
#include <miniexp.h>

/*JNI BITMAP API */

#include <nativebitmap.h>

void ThrowError(JNIEnv* env, const char* msg)
{
    jclass exceptionClass = env->FindClass("java/lang/RuntimeException");
    if (!exceptionClass)
        return;
    if (!msg)
        env->ThrowNew(exceptionClass, "Djvu decoding error!");
    else env->ThrowNew(exceptionClass, msg);
}

void ThrowDjvuError(JNIEnv* env, const ddjvu_message_t* msg)
{
    if (!msg || !msg->m_error.message)
        ThrowError(env, "Djvu decoding error!");
    else ThrowError(env, msg->m_error.message);
}

#define HANDLE_TO_DOC(handle) (ddjvu_document_t*)handle
#define HANDLE(ptr) (jlong)ptr

extern "C" jlong Java_org_ebookdroid_djvudroid_codec_DjvuContext_create(JNIEnv *env, jclass cls)
{
    ddjvu_context_t* context = ddjvu_context_create(DJVU_DROID);
    DEBUG_PRINT("Creating context: %x", context);
    return (jlong) context;
}

extern "C" void Java_org_ebookdroid_djvudroid_codec_DjvuContext_free(JNIEnv *env, jclass cls, jlong contextHandle)
{
    ddjvu_context_release((ddjvu_context_t *) contextHandle);
}

bool number_from_miniexp(miniexp_t sexp, int *number)
{
    if (miniexp_numberp(sexp))
    {
        *number = miniexp_to_int(sexp);
        return TRUE;
    }
    else
    {
        return FALSE;
    }
}

bool string_from_miniexp(miniexp_t sexp, const char **str)
{
    if (miniexp_stringp(sexp))
    {
        *str = miniexp_to_str(sexp);
        return TRUE;
    }
    else
    {
        return FALSE;
    }
}

jint* get_djvu_hyperlink_area(ddjvu_pageinfo_t *page_info, miniexp_t sexp, int &type, int &len)
{
    miniexp_t iter;

    iter = sexp;

    DEBUG_PRINT("Hyperlink area %s", miniexp_to_name(miniexp_car(sexp)));

    if (miniexp_car(iter) == miniexp_symbol("rect"))
        type = 1;
    else if (miniexp_car(iter) == miniexp_symbol("oval"))
        type = 2;
    else if (miniexp_car(iter) == miniexp_symbol("poly"))
        type = 3;
    else return NULL;

    len = miniexp_length(iter);
    jint* array = new jint[len];

    int x, i = 0;
    iter = miniexp_cdr(iter);
    while (iter != miniexp_nil)
    {
        if (!number_from_miniexp(miniexp_car(iter), &x))
            break;
        iter = miniexp_cdr(iter);
        array[i++] = (jint) x;
        if (i >= len)
            break;
    }

    len = i;
    if ((type == 1 || type == 2) && len == 4)
    {
        int miny, width, height;

        miny = array[1];
        width = array[2];
        height = array[3];
        array[1] = (page_info->height - (miny + height));
        array[2] = array[0] + width;
        array[3] = (page_info->height - miny);
    }
    if (type == 3 && (len % 2) == 0)
    {
        int ccc;
        for (int k = 1; k < len; k += 2)
        {
            ccc = array[k];
            array[k] = (page_info->height - ccc);
        }
    }

    return array;
}

jobject get_djvu_hyperlink_mapping(JNIEnv *jenv, ddjvu_document_t* djvu_document, ddjvu_pageinfo_t *page_info,
                                   miniexp_t sexp)
{
    miniexp_t iter;
    const char *url, *url_target;

    jobject hl = NULL;

    iter = sexp;

    if (miniexp_car(iter) != miniexp_symbol("maparea"))
    {
        DEBUG_PRINT("DjvuLibre error: Unknown hyperlink %s", miniexp_to_name(miniexp_car(sexp)));
        return hl;
    }

    iter = miniexp_cdr(iter);

    if (miniexp_caar(iter) == miniexp_symbol("url"))
    {
        if (!string_from_miniexp(miniexp_cadr(miniexp_car(iter)), &url))
        {
            DEBUG_PRINT("DjvuLibre error: Unknown hyperlink %s", miniexp_to_name(miniexp_car(sexp)));
            return hl;
        }
        if (!string_from_miniexp(miniexp_caddr(miniexp_car(iter)), &url_target))
        {
            DEBUG_PRINT("DjvuLibre error: Unknown hyperlink %s", miniexp_to_name(miniexp_car(sexp)));
            return hl;
        }
    }
    else
    {
        if (!string_from_miniexp(miniexp_car(iter), &url))
        {
            DEBUG_PRINT("DjvuLibre error: Unknown hyperlink %s", miniexp_to_name(miniexp_car(sexp)));
            return hl;
        }
        url_target = NULL;
    }

    iter = miniexp_cdr(iter);
    /* FIXME: DjVu hyperlink comments are ignored */

    int len = 0;
    int type;
    jint* data;
    iter = miniexp_cdr(iter);
    if ((data = get_djvu_hyperlink_area(page_info, miniexp_car(iter), type, len)) == NULL)
    {
        DEBUG_PRINT("DjvuLibre error: Unknown hyperlink %s", miniexp_to_name(miniexp_car(sexp)));
        return hl;
    }

    iter = miniexp_cdr(iter);
    /* FIXME: DjVu hyperlink attributes are ignored */

    DEBUG_PRINT("DjvuLibre: Hyperlink url: %s url_target: %s", url, url_target);

    if (!url)
    {
        delete[] data;
        return hl;
    }

    jclass pagelinkClass = jenv->FindClass("org/ebookdroid/core/PageLink");
    if (!pagelinkClass)
    {
        delete[] data;
        return hl;
    }

    jmethodID plInitMethodId = jenv->GetMethodID(pagelinkClass, "<init>", "(Ljava/lang/String;I[I)V");
    if (!plInitMethodId)
    {
        delete[] data;
        return hl;
    }

    jintArray points = jenv->NewIntArray(len);
    jenv->SetIntArrayRegion(points, 0, len, data);

    jstring jstr = jenv->NewStringUTF(url);

    hl = jenv->NewObject(pagelinkClass, plInitMethodId, jstr, (jint) type, points);

    jenv->DeleteLocalRef(jstr);
    jenv->DeleteLocalRef(points);

    delete[] data;

//    DEBUG_PRINT("DjvuLibre: Hyperlink url: %s url_target: %s", url, url_target);

    return hl;

}

jobject djvu_links_get_links(JNIEnv *jenv, ddjvu_document_t* djvu_document, int page)
{

    DEBUG_PRINT("djvu_links_get_links %d", page);

    miniexp_t page_annotations = miniexp_nil;
    miniexp_t *hyperlinks = NULL, *iter = NULL;
    ddjvu_pageinfo_t page_info;

    jobject arrayList = NULL;

    page_annotations = ddjvu_document_get_pageanno(djvu_document, page);

    ddjvu_document_get_pageinfo(djvu_document, page, &page_info);

    if (page_annotations)
    {
        hyperlinks = ddjvu_anno_get_hyperlinks(page_annotations);
        if (hyperlinks)
        {

            jclass arrayListClass = jenv->FindClass("java/util/ArrayList");
            if (!arrayListClass)
                return arrayList;

            jmethodID alInitMethodId = jenv->GetMethodID(arrayListClass, "<init>", "()V");
            if (!alInitMethodId)
                return arrayList;

            jmethodID alAddMethodId = jenv->GetMethodID(arrayListClass, "add", "(Ljava/lang/Object;)Z");
            if (!alAddMethodId)
                return arrayList;

            arrayList = jenv->NewObject(arrayListClass, alInitMethodId);
            if (!arrayList)
                return arrayList;

            for (iter = hyperlinks; *iter; ++iter)
            {
                jobject hl = get_djvu_hyperlink_mapping(jenv, djvu_document, &page_info, *iter);
                if (hl)
                    jenv->CallBooleanMethod(arrayList, alAddMethodId, hl);
                //jenv->DeleteLocalRef(hl);
            }
            free(hyperlinks);
        }
        ddjvu_miniexp_release(djvu_document, page_annotations);
    }
    return arrayList;
}

extern "C" jlong Java_org_ebookdroid_djvudroid_codec_DjvuDocument_open(JNIEnv *env, jclass cls, jlong contextHandle,
                                                                       jstring fileName)
{
    const char* fileNameString = env->GetStringUTFChars(fileName, NULL);
    DEBUG_PRINT("Opening document: %s", fileNameString);

    jlong docHandle = (jlong)(
        ddjvu_document_create_by_filename((ddjvu_context_t*) (contextHandle), fileNameString, FALSE));
//    jlong docHandle = (jlong)(ddjvu_document_create_by_filename_utf8((ddjvu_context_t*)(contextHandle), fileNameString, FALSE));
    env->ReleaseStringUTFChars(fileName, fileNameString);
    if (!docHandle)
        ThrowError(env, "DJVU file not found or corrupted.");
//    if(docHandle)
//    {
//	char *s = ddjvu_document_get_filedump((ddjvu_document_t*)docHandle, 1);
//        DEBUG_PRINT("%s",s);
//    }
    return docHandle;
}

void CallDocInfoCallback(JNIEnv* env, jobject thiz, const ddjvu_message_t* msg)
{
    DEBUG_WRITE("Calling handleDocInfo callback");
    jclass cls = env->GetObjectClass(thiz);
    if (!cls)
        return;
    jmethodID handleDocInfoId = env->GetMethodID(cls, "handleDocInfo", "()V");
    if (!handleDocInfoId)
        return;
    env->CallVoidMethod(thiz, handleDocInfoId);
}

extern "C" void Java_org_ebookdroid_djvudroid_codec_DjvuContext_handleMessage(JNIEnv *env, jobject thiz,
                                                                              jlong contextHandle)
{
    const ddjvu_message_t *msg;
    ddjvu_context_t* ctx = (ddjvu_context_t*) (contextHandle);
//    DEBUG_PRINT("handleMessage for ctx: %x",ctx);
    if (msg = ddjvu_message_peek(ctx))
    {
        switch (msg->m_any.tag)
        {
        case DDJVU_ERROR:
            ThrowDjvuError(env, msg);
            break;
        case DDJVU_INFO:
            break;
        case DDJVU_DOCINFO:
            CallDocInfoCallback(env, thiz, msg);
            break;
        default:
            break;
        }
        ddjvu_message_pop(ctx);
    }
}

extern "C" jlong Java_org_ebookdroid_djvudroid_codec_DjvuDocument_getPage(JNIEnv *env, jclass cls, jlong docHandle,
                                                                          jint pageNumber)
{
    DEBUG_PRINT("getPage num: %d", pageNumber);
    return (jlong) ddjvu_page_create_by_pageno((ddjvu_document_t*) docHandle, pageNumber);
}

extern "C" jobject Java_org_ebookdroid_djvudroid_codec_DjvuDocument_getPageLinks(JNIEnv *env, jclass cls,
                                                                                 jlong docHandle, jint pageNumber)
{
    DEBUG_PRINT("getPageLinks num: %d", pageNumber);
    return djvu_links_get_links(env, (ddjvu_document_t*) docHandle, pageNumber);
}

extern "C" jint Java_org_ebookdroid_djvudroid_codec_DjvuDocument_getPageInfo(JNIEnv *env, jclass cls, jlong docHandle,
                                                                             jint pageNumber, jlong contextHandle,
                                                                             jobject cpi)
{
    ddjvu_status_t r;
    ddjvu_pageinfo_t info;

    jclass clazz;
    jfieldID fid;

    while ((r = ddjvu_document_get_pageinfo((ddjvu_document_t*) docHandle, pageNumber, &info)) < DDJVU_JOB_OK)
        Java_org_ebookdroid_djvudroid_codec_DjvuContext_handleMessage(env, cls, contextHandle);

    clazz = env->GetObjectClass(cpi);
    if (0 == clazz)
    {
        return (-1);
    }
    fid = env->GetFieldID(clazz, "width", "I");

    // This next line is where the power is hidden. Directly change
    // even private fields within java objects. Nasty!
    env->SetIntField(cpi, fid, info.width);

    fid = env->GetFieldID(clazz, "height", "I");
    env->SetIntField(cpi, fid, info.height);

    fid = env->GetFieldID(clazz, "dpi", "I");
    env->SetIntField(cpi, fid, info.dpi);

    fid = env->GetFieldID(clazz, "rotation", "I");
    env->SetIntField(cpi, fid, info.rotation);

    fid = env->GetFieldID(clazz, "version", "I");
    env->SetIntField(cpi, fid, info.version);

    return 0;
}

extern "C" void Java_org_ebookdroid_djvudroid_codec_DjvuDocument_free(JNIEnv *env, jclass cls, jlong docHandle)
{
    ddjvu_document_release((ddjvu_document_t*) docHandle);
}

extern "C" jint Java_org_ebookdroid_djvudroid_codec_DjvuDocument_getPageCount(JNIEnv *env, jclass cls, jlong docHandle)
{
    return ddjvu_document_get_pagenum(HANDLE_TO_DOC(docHandle));
}

extern "C" jboolean Java_org_ebookdroid_djvudroid_codec_DjvuPage_isDecodingDone(JNIEnv *env, jclass cls,
                                                                                jlong pageHandle)
{
    return ddjvu_page_decoding_done((ddjvu_page_t*) pageHandle);
}

extern "C" jint Java_org_ebookdroid_djvudroid_codec_DjvuPage_getWidth(JNIEnv *env, jclass cls, jlong pageHangle)
{
    return ddjvu_page_get_width((ddjvu_page_t*) pageHangle);
}

extern "C" jint Java_org_ebookdroid_djvudroid_codec_DjvuPage_getHeight(JNIEnv *env, jclass cls, jlong pageHangle)
{
    return ddjvu_page_get_height((ddjvu_page_t*) pageHangle);
}

extern "C" jboolean Java_org_ebookdroid_djvudroid_codec_DjvuPage_renderPage(JNIEnv *env, jclass cls, jlong pageHangle,
                                                                            jint targetWidth, jint targetHeight,
                                                                            jfloat pageSliceX, jfloat pageSliceY,
                                                                            jfloat pageSliceWidth,
                                                                            jfloat pageSliceHeight, jintArray buffer,
                                                                            jint rendermode)
{

    DEBUG_WRITE("Rendering page");
    ddjvu_page_t* page = (ddjvu_page_t*) ((pageHangle));
    ddjvu_rect_t pageRect;
    pageRect.x = 0;
    pageRect.y = 0;
    pageRect.w = targetWidth / pageSliceWidth;
    pageRect.h = targetHeight / pageSliceHeight;
    ddjvu_rect_t targetRect;
    targetRect.x = pageSliceX * targetWidth / pageSliceWidth;
    targetRect.y = pageSliceY * targetHeight / pageSliceHeight;
    targetRect.w = targetWidth;
    targetRect.h = targetHeight;
    unsigned int masks[] = { 0xFF0000, 0x00FF00, 0x0000FF };
    ddjvu_format_t* pixelFormat = ddjvu_format_create(DDJVU_FORMAT_RGBMASK32, 3, masks);
    ddjvu_format_set_row_order(pixelFormat, TRUE);
    ddjvu_format_set_y_direction(pixelFormat, TRUE);

    char *pBuffer = (char *) env->GetPrimitiveArrayCritical(buffer, 0);
    jboolean result = ddjvu_page_render(page, (ddjvu_render_mode_t) rendermode, &pageRect, &targetRect, pixelFormat,
        targetWidth * 4, pBuffer);
    env->ReleasePrimitiveArrayCritical(buffer, pBuffer, 0);

    ddjvu_format_release(pixelFormat);
    return result;
}

/*JNI BITMAP API*/

extern "C" jboolean Java_org_ebookdroid_djvudroid_codec_DjvuPage_isNativeGraphicsAvailable(JNIEnv *env, jclass cls)
{
    return NativePresent();
//#ifdef USE_JNI_BITMAP_API
//	return 1;
//#else
//	return 0;
//#endif
}

extern "C" jboolean Java_org_ebookdroid_djvudroid_codec_DjvuPage_renderPageBitmap(JNIEnv *env, jclass cls,
                                                                                  jlong pageHangle, jint targetWidth,
                                                                                  jint targetHeight, jfloat pageSliceX,
                                                                                  jfloat pageSliceY,
                                                                                  jfloat pageSliceWidth,
                                                                                  jfloat pageSliceHeight,
                                                                                  jobject bitmap, jint rendermode)
{
//#ifdef USE_JNI_BITMAP_API

    DEBUG_WRITE("Rendering page bitmap");

    AndroidBitmapInfo info;
    void *pixels;

    int ret;

    if ((ret = NativeBitmap_getInfo(env, bitmap, &info)) < 0)
    {
        DEBUG_PRINT("AndroidBitmap_getInfo() failed ! error=%d", ret);
        return 0;
    }

    DEBUG_WRITE("Checking format");
//    if (info.format != ANDROID_BITMAP_FORMAT_RGBA_8888) {
//    	DEBUG_WRITE("Bitmap format is not RGBA_8888 !");
//            return 0;
//    }
    if (info.format != ANDROID_BITMAP_FORMAT_RGB_565)
    {
        DEBUG_WRITE("Bitmap format is not RGB_565 !");
        return 0;
    }

    DEBUG_WRITE("locking pixels");
    if ((ret = NativeBitmap_lockPixels(env, bitmap, &pixels)) < 0)
    {
        DEBUG_PRINT("AndroidBitmap_lockPixels() failed ! error=%d", ret);
        return 0;
    }

    ddjvu_page_t* page = (ddjvu_page_t*) ((pageHangle));
    ddjvu_rect_t pageRect;
    pageRect.x = 0;
    pageRect.y = 0;
    pageRect.w = targetWidth / pageSliceWidth;
    pageRect.h = targetHeight / pageSliceHeight;
    ddjvu_rect_t targetRect;
    targetRect.x = pageSliceX * targetWidth / pageSliceWidth;
    targetRect.y = pageSliceY * targetHeight / pageSliceHeight;
    targetRect.w = targetWidth;
    targetRect.h = targetHeight;
    unsigned int masks[] = { 0xF800, 0x07E0, 0x001F };
    ddjvu_format_t* pixelFormat = ddjvu_format_create(DDJVU_FORMAT_RGBMASK16, 3, masks);
//    unsigned int masks[] = {0x000000FF, 0x0000FF00, 0x00FF0000, 0xFF000000};    
//    ddjvu_format_t* pixelFormat = ddjvu_format_create(DDJVU_FORMAT_RGBMASK32, 4, masks);

    ddjvu_format_set_row_order(pixelFormat, TRUE);
    ddjvu_format_set_y_direction(pixelFormat, TRUE);

//    jboolean result = ddjvu_page_render(page, DDJVU_RENDER_COLOR, &pageRect, &targetRect, pixelFormat, targetWidth * 4, (char*)pixels);
    jboolean result = ddjvu_page_render(page, (ddjvu_render_mode_t) rendermode, &pageRect, &targetRect, pixelFormat,
        targetWidth * 2, (char*) pixels);

    ddjvu_format_release(pixelFormat);

    NativeBitmap_unlockPixels(env, bitmap);

    return result;
//#else
//    DEBUG_WRITE("Rendering page bitmap not implemented");
//	return 0;
//#endif
}

extern "C" void Java_org_ebookdroid_djvudroid_codec_DjvuPage_free(JNIEnv *env, jclass cls, jlong pageHangle)
{
    ddjvu_page_release((ddjvu_page_t*) pageHangle);
}

//Outline
extern "C" jlong Java_org_ebookdroid_djvudroid_codec_DjvuOutline_open(JNIEnv *env, jclass cls, jlong docHandle)
{
//        DEBUG_PRINT("DjvuOutline.open(%p)",docHandle);
    miniexp_t outline = ddjvu_document_get_outline((ddjvu_document_t*) docHandle);
    if (outline && outline != miniexp_dummy)
    {
        if (!miniexp_consp(outline) || miniexp_car(outline) != miniexp_symbol("bookmarks"))
        {
            DEBUG_PRINT("%s", "Outline data is corrupted");
            return 0;
        }
        else return (jlong) outline;
//	    debug_outline(outline);
    }
    return 0;
}

extern "C" jboolean Java_org_ebookdroid_djvudroid_codec_DjvuOutline_expConsp(JNIEnv *env, jclass cls, jlong expr)
{
//        DEBUG_PRINT("DjvuOutline.expConsp(%p)",expr);
    return miniexp_consp((miniexp_t) expr);
}

extern "C" jstring Java_org_ebookdroid_djvudroid_codec_DjvuOutline_getTitle(JNIEnv *env, jclass cls, jlong expr)
{
//        DEBUG_PRINT("DjvuOutline.getTitle(%p)",expr);
    miniexp_t s = miniexp_car((miniexp_t) expr);
    if (miniexp_consp(s) && miniexp_consp(miniexp_cdr(s)) && miniexp_stringp(miniexp_car(s))
        && miniexp_stringp(miniexp_cadr(s)))
    {
        const char* buf = miniexp_to_str(miniexp_car(s));
        return env->NewStringUTF(buf);
    }
    return NULL;
}

extern "C" jstring Java_org_ebookdroid_djvudroid_codec_DjvuOutline_getLink(JNIEnv *env, jclass cls, jlong expr,
                                                                           jlong docHandle)
{
//        DEBUG_PRINT("DjvuOutline.getLinkPage(%p)",expr);
    miniexp_t s = miniexp_car((miniexp_t) expr);
    if (miniexp_consp(s) && miniexp_consp(miniexp_cdr(s)) && miniexp_stringp(miniexp_car(s))
        && miniexp_stringp(miniexp_cadr(s)))
    {
        const char *link = miniexp_to_str(miniexp_cadr(s));
        int number = -1;
        if (link && link[0] == '#')
        {
            number = ddjvu_document_search_pageno((ddjvu_document_t*) docHandle, link + 1);
            if (number >= 0)
            {
                char linkbuf[128];
                snprintf(linkbuf, 127, "#%d", number + 1);
                return env->NewStringUTF(linkbuf);
            }
        }
        return env->NewStringUTF(link);
    }
    return NULL;
}

extern "C" jlong Java_org_ebookdroid_djvudroid_codec_DjvuOutline_getNext(JNIEnv *env, jclass cls, jlong expr)
{
//    DEBUG_PRINT("DjvuOutline.getNext(%p)",expr);
    return (jlong) miniexp_cdr((miniexp_t) expr);
}

extern "C" jlong Java_org_ebookdroid_djvudroid_codec_DjvuOutline_getChild(JNIEnv *env, jclass cls, jlong expr)
{
//    DEBUG_PRINT("DjvuOutline.getChild(%p)",expr);
    miniexp_t s = miniexp_car((miniexp_t) expr);
    if (miniexp_consp(s) && miniexp_consp(miniexp_cdr(s)) && miniexp_stringp(miniexp_car(s))
        && miniexp_stringp(miniexp_cadr(s)))
        return (jlong) miniexp_cddr(s);
    return 0;
}

