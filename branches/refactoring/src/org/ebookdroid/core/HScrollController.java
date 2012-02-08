package org.ebookdroid.core;

import org.ebookdroid.R;
import org.ebookdroid.common.settings.SettingsManager;
import org.ebookdroid.common.settings.types.DocumentViewMode;
import org.ebookdroid.core.models.DocumentModel;
import org.ebookdroid.ui.viewer.IActivityController;
import org.ebookdroid.ui.viewer.views.DragMark;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import android.graphics.RectF;

import org.emdev.ui.hwa.IHardwareAcceleration;

public class HScrollController extends AbstractViewController {

    protected static Bitmap dragBitmap;

    public HScrollController(final IActivityController base) {
        super(base, DocumentViewMode.HORIZONTAL_SCROLL);
        if (dragBitmap == null) {
            dragBitmap = BitmapFactory.decodeResource(base.getContext().getResources(), R.drawable.drag);
        }
        IHardwareAcceleration.Factory.getInstance().setMode(getView().getView(), true);
    }

    /**
     * {@inheritDoc}
     * 
     * @see org.ebookdroid.core.AbstractViewController#goToPageImpl(int)
     */
    @Override
    protected final void goToPageImpl(final int toPage) {
        final DocumentModel dm = getBase().getDocumentModel();
        final int pageCount = dm.getPageCount();
        if (toPage >= 0 && toPage < pageCount) {
            final Page page = dm.getPageObject(toPage);
            if (page != null) {
                final RectF viewRect = view.getViewRect();
                final RectF bounds = page.getBounds(getBase().getZoomModel().getZoom());
                dm.setCurrentPageIndex(page.index);
                view.scrollTo(Math.round(bounds.left - (viewRect.width() - bounds.width()) / 2), getScrollY());
            } else {
                if (LCTX.isDebugEnabled()) {
                    LCTX.d("No page found for index: " + toPage);
                }
            }
        } else {
            if (LCTX.isDebugEnabled()) {
                LCTX.d("Bad page index: " + toPage + ", page count: " + pageCount);
            }
        }
    }

    /**
     * {@inheritDoc}
     * 
     * @see org.ebookdroid.ui.viewer.IViewController#calculateCurrentPage(org.ebookdroid.core.ViewState)
     */
    @Override
    public final int calculateCurrentPage(final ViewState viewState) {
        int result = 0;
        long bestDistance = Long.MAX_VALUE;

        final int viewX = Math.round(viewState.viewRect.centerX());

        if (viewState.firstVisible != -1) {
            for (final Page page : getBase().getDocumentModel().getPages(viewState.firstVisible,
                    viewState.lastVisible + 1)) {
                final RectF bounds = viewState.getBounds(page);
                final int pageX = Math.round(bounds.centerX());
                final long dist = Math.abs(pageX - viewX);
                if (dist < bestDistance) {
                    bestDistance = dist;
                    result = page.index.viewIndex;
                }
            }
        }

        return result;
    }

    /**
     * {@inheritDoc}
     * 
     * @see org.ebookdroid.ui.viewer.IViewController#verticalConfigScroll(int)
     */
    @Override
    public final void verticalConfigScroll(final int direction) {
        final int scrollheight = SettingsManager.getAppSettings().getScrollHeight();
        final int dx = (int) (direction * getWidth() * (scrollheight / 100.0));

        view.startPageScroll(dx, 0);
    }

    /**
     * {@inheritDoc}
     * 
     * @see org.ebookdroid.ui.viewer.IViewController#getScrollLimits()
     */
    @Override
    public final Rect getScrollLimits() {
        final int width = getWidth();
        final int height = getHeight();
        final Page lpo = getBase().getDocumentModel().getLastPageObject();
        final float zoom = getBase().getZoomModel().getZoom();

        final int right = lpo != null ? (int) lpo.getBounds(zoom).right - width : 0;
        final int bottom = (int) (height * zoom) - height;

        return new Rect(0, 0, right, bottom);
    }

    @Override
    public void drawView(EventDraw eventDraw) {
        final DocumentModel dm = getBase().getDocumentModel();
        if (dm == null) {
            return;
        }
        for (Page page : dm.getPages(eventDraw.viewState.firstVisible, eventDraw.viewState.lastVisible + 1)) {
            if (page != null) {
                eventDraw.process(page);
            }
        }

        if (SettingsManager.getAppSettings().getShowAnimIcon()) {
            DragMark.draw(eventDraw.canvas, eventDraw.viewState);
        }
        view.continueScroll();
    }

    /**
     * {@inheritDoc}
     * 
     * @see org.ebookdroid.core.AbstractViewController#onLayoutChanged(boolean, boolean, android.graphics.Rect,
     *      android.graphics.Rect)
     */
    @Override
    public final boolean onLayoutChanged(final boolean layoutChanged, final boolean layoutLocked, final Rect oldLaout,
            final Rect newLayout) {
        int page = -1;
        if (isShown && layoutChanged) {
            page = base.getDocumentModel().getCurrentViewPageIndex();
        }
        if (super.onLayoutChanged(layoutChanged, layoutLocked, oldLaout, newLayout)) {
            if (page > 0) {
                goToPage(page);
            }
            return true;
        }
        return false;
    }

    /**
     * {@inheritDoc}
     * 
     * @see org.ebookdroid.ui.viewer.IViewController#invalidatePageSizes(org.ebookdroid.ui.viewer.IViewController.InvalidateSizeReason,
     *      org.ebookdroid.core.Page)
     */
    @Override
    public synchronized final void invalidatePageSizes(final InvalidateSizeReason reason, final Page changedPage) {
        if (!isInitialized) {
            return;
        }

        if (reason == InvalidateSizeReason.PAGE_ALIGN) {
            return;
        }

        final int height = getHeight();

        if (changedPage == null) {
            float widthAccum = 0;
            for (final Page page : getBase().getDocumentModel().getPages()) {
                final float pageWidth = height * page.getAspectRatio();
                final float pageHeight = pageWidth / page.getAspectRatio();
                page.setBounds(new RectF(widthAccum, 0, widthAccum + pageWidth, pageHeight));
                widthAccum += pageWidth + 1;
            }
        } else {
            float widthAccum = changedPage.getBounds(1.0f).left;
            for (final Page page : getBase().getDocumentModel().getPages(changedPage.index.viewIndex)) {
                final float pageWidth = height * page.getAspectRatio();
                final float pageHeight = pageWidth / page.getAspectRatio();
                page.setBounds(new RectF(widthAccum, 0, widthAccum + pageWidth, pageHeight));
                widthAccum += pageWidth + 1;
            }
        }
    }

    /**
     * {@inheritDoc}
     * 
     * @see org.ebookdroid.core.AbstractViewController#isPageVisible(org.ebookdroid.core.Page,
     *      org.ebookdroid.core.ViewState)
     */
    @Override
    public final boolean isPageVisible(final Page page, final ViewState viewState) {
        return RectF.intersects(viewState.viewRect, viewState.getBounds(page));
    }
}
