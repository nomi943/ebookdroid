package org.ebookdroid.core;

import org.ebookdroid.common.bitmaps.BitmapManager;
import org.ebookdroid.common.bitmaps.BitmapRef;
import org.ebookdroid.common.bitmaps.Bitmaps;
import org.ebookdroid.common.log.LogContext;
import org.ebookdroid.common.settings.SettingsManager;
import org.ebookdroid.common.settings.books.BookSettings;
import org.ebookdroid.core.codec.CodecPage;
import org.ebookdroid.core.crop.PageCropper;
import org.ebookdroid.core.models.DecodingProgressModel;
import org.ebookdroid.ui.viewer.IViewController;

import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.PointF;
import android.graphics.Rect;
import android.graphics.RectF;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

public class PageTreeNode implements DecodeService.DecodeCallback {

    private static final LogContext LCTX = Page.LCTX;

    final Page page;
    final PageTreeNode parent;
    final int id;
    final PageTreeLevel level;
    final String shortId;
    final String fullId;

    final AtomicBoolean decodingNow = new AtomicBoolean();
    final BitmapHolder holder = new BitmapHolder();

    final RectF pageSliceBounds;

    float bitmapZoom = 1;
    RectF croppedBounds = null;

    PageTreeNode(final Page page) {
        assert page != null;

        this.page = page;
        this.parent = null;
        this.id = 0;
        this.level = PageTreeLevel.ROOT;
        this.shortId = page.index.viewIndex + ":0";
        this.fullId = page.index + ":0";
        this.pageSliceBounds = page.type.getInitialRect();
        this.croppedBounds = null;
    }

    PageTreeNode(final Page page, final PageTreeNode parent, final int id, final RectF localPageSliceBounds) {
        assert id != 0;
        assert page != null;
        assert parent != null;

        this.page = page;
        this.parent = parent;
        this.id = id;
        this.level = parent.level.next;
        this.shortId = page.index.viewIndex + ":" + id;
        this.fullId = page.index + ":" + id;
        this.pageSliceBounds = evaluatePageSliceBounds(localPageSliceBounds, parent);
        this.croppedBounds = evaluateCroppedPageSliceBounds(localPageSliceBounds, parent);
    }

    @Override
    protected void finalize() throws Throwable {
        holder.recycle(null);
    }

    public boolean recycle(final List<Bitmaps> bitmapsToRecycle) {
        stopDecodingThisNode("node recycling");
        return holder.recycle(bitmapsToRecycle);
    }

    protected boolean isReDecodingRequired(final boolean committed, final ViewState viewState) {
        return (committed && viewState.zoom != bitmapZoom) || viewState.zoom > 1.2 * bitmapZoom;
    }

    protected void decodePageTreeNode(final List<PageTreeNode> nodesToDecode, final ViewState viewState) {
        if (this.decodingNow.compareAndSet(false, true)) {
            bitmapZoom = viewState.zoom;
            nodesToDecode.add(this);
        }
    }

    void stopDecodingThisNode(final String reason) {
        if (this.decodingNow.compareAndSet(true, false)) {
            final DecodingProgressModel dpm = page.base.getDecodingProgressModel();
            if (dpm != null) {
                dpm.decrease();
            }
            if (reason != null) {
                final DecodeService ds = page.base.getDecodeService();
                if (ds != null) {
                    ds.stopDecoding(this, reason);
                }
            }
        }
    }

    @Override
    public void decodeComplete(final CodecPage codecPage, final BitmapRef bitmap, final Rect bitmapBounds) {

        try {
            if (bitmap == null || bitmapBounds == null) {
                page.base.getActivity().runOnUiThread(new Runnable() {

                    @Override
                    public void run() {
                        stopDecodingThisNode(null);
                    }
                });
                return;
            }

            BookSettings bs = SettingsManager.getBookSettings();
            if (bs != null && bs.cropPages) {
                if (id == 0 && croppedBounds == null) {
                    croppedBounds = PageCropper.getCropBounds(bitmap, bitmapBounds, pageSliceBounds);
                    final DecodeService decodeService = page.base.getDecodeService();
                    if (decodeService != null) {
                        if (LCTX.isDebugEnabled()) {
                            LCTX.d(fullId + ": cropped image requested: " + croppedBounds);
                        }
                        decodeService.decodePage(new ViewState(PageTreeNode.this), PageTreeNode.this);
                    }
                }
            }

            final Bitmaps bitmaps = holder.reuse(fullId, bitmap, bitmapBounds);

            page.base.getActivity().runOnUiThread(new Runnable() {

                @Override
                public void run() {
                    holder.setBitmap(bitmaps);
                    stopDecodingThisNode(null);

                    final IViewController dc = page.base.getDocumentController();
                    if (dc instanceof AbstractViewController) {
                        new EventChildLoaded((AbstractViewController) dc, PageTreeNode.this, bitmapBounds).process();
                    }
                }
            });
        } catch (OutOfMemoryError ex) {
            LCTX.e("No memory: ", ex);
            BitmapManager.clear("PageTreeNode OutOfMemoryError: ");
            page.base.getActivity().runOnUiThread(new Runnable() {

                @Override
                public void run() {
                    stopDecodingThisNode(null);
                }
            });
        } finally {
            BitmapManager.release(bitmap);
        }
    }

    public RectF getTargetRect(final ViewState viewState, final RectF viewRect, final RectF pageBounds) {
        final Matrix matrix = new Matrix();

        matrix.postScale(pageBounds.width() * page.getTargetRectScale(), pageBounds.height());
        matrix.postTranslate(pageBounds.left - pageBounds.width() * page.type.getLeftPos(), pageBounds.top);

        final RectF targetRectF = new RectF();
        matrix.mapRect(targetRectF, pageSliceBounds);
        return new RectF(targetRectF);
    }

    private static RectF evaluatePageSliceBounds(final RectF localPageSliceBounds, final PageTreeNode parent) {
        final Matrix matrix = new Matrix();
        matrix.postScale(parent.pageSliceBounds.width(), parent.pageSliceBounds.height());
        matrix.postTranslate(parent.pageSliceBounds.left, parent.pageSliceBounds.top);
        final RectF sliceBounds = new RectF();
        matrix.mapRect(sliceBounds, localPageSliceBounds);
        return sliceBounds;
    }

    private static RectF evaluateCroppedPageSliceBounds(final RectF localPageSliceBounds, final PageTreeNode parent) {
        if (parent.croppedBounds == null) {
            return null;
        }
        final Matrix matrix = new Matrix();
        matrix.postScale(parent.croppedBounds.width(), parent.croppedBounds.height());
        matrix.postTranslate(parent.croppedBounds.left, parent.croppedBounds.top);
        final RectF sliceBounds = new RectF();
        matrix.mapRect(sliceBounds, localPageSliceBounds);
        return sliceBounds;
    }

    @Override
    public int hashCode() {
        return (page == null) ? 0 : page.index.viewIndex;
    }

    @Override
    public boolean equals(final Object obj) {
        if (this == obj) {
            return true;
        }

        if (obj instanceof PageTreeNode) {
            final PageTreeNode that = (PageTreeNode) obj;
            if (this.page == null) {
                return that.page == null;
            }
            return this.page.index.viewIndex == that.page.index.viewIndex
                    && this.pageSliceBounds.equals(that.pageSliceBounds);
        }

        return false;
    }

    @Override
    public String toString() {
        final StringBuilder buf = new StringBuilder("PageTreeNode");
        buf.append("[");

        buf.append("id").append("=").append(page.index.viewIndex).append(":").append(id);
        buf.append(", ");
        buf.append("rect").append("=").append(this.pageSliceBounds);
        buf.append(", ");
        buf.append("hasBitmap").append("=").append(holder.hasBitmaps());

        buf.append("]");
        return buf.toString();
    }

    class BitmapHolder {

        Bitmaps day;

        public synchronized void drawBitmap(final Canvas canvas, final PagePaint paint, final PointF viewBase, final RectF tr) {
            if (day != null) {
                day.draw(canvas, paint, viewBase, tr);
            }
        }

        public synchronized Bitmaps reuse(String nodeId, BitmapRef bitmap, Rect bitmapBounds) {
            boolean invert = SettingsManager.getAppSettings().getNightMode();
            if (day != null) {
                if (day.reuse(nodeId, bitmap, bitmapBounds, invert)) {
                    return day;
                }
            }
            return new Bitmaps(nodeId, bitmap, bitmapBounds, invert);
        }

        public synchronized boolean hasBitmaps() {
            return day != null ? day.hasBitmaps() : false;
        }

        public synchronized boolean recycle(final List<Bitmaps> bitmapsToRecycle) {
            if (day != null) {
                if (bitmapsToRecycle != null) {
                    bitmapsToRecycle.add(day);
                } else {
                    BitmapManager.release(Arrays.asList(day));
                }
                day = null;
                return true;
            }
            return false;
        }

        public synchronized void setBitmap(final Bitmaps bitmaps) {
            if (bitmaps == null || bitmaps == day) {
                return;
            }
            recycle(null);
            this.day = bitmaps;
        }
    }

}
