package org.ebookdroid.ui.viewer;

import org.ebookdroid.CodecType;
import org.ebookdroid.R;
import org.ebookdroid.common.bitmaps.BitmapManager;
import org.ebookdroid.common.cache.CacheManager;
import org.ebookdroid.common.keysbinding.KeyBindingsDialog;
import org.ebookdroid.common.keysbinding.KeyBindingsManager;
import org.ebookdroid.common.log.EmergencyHandler;
import org.ebookdroid.common.log.LogContext;
import org.ebookdroid.common.settings.AppSettings;
import org.ebookdroid.common.settings.ISettingsChangeListener;
import org.ebookdroid.common.settings.SettingsManager;
import org.ebookdroid.common.settings.books.BookSettings;
import org.ebookdroid.common.settings.books.Bookmark;
import org.ebookdroid.common.touch.TouchManager;
import org.ebookdroid.core.DecodeService;
import org.ebookdroid.core.Page;
import org.ebookdroid.core.PageIndex;
import org.ebookdroid.core.codec.OutlineLink;
import org.ebookdroid.core.events.CurrentPageListener;
import org.ebookdroid.core.events.DecodingProgressListener;
import org.ebookdroid.core.models.DecodingProgressModel;
import org.ebookdroid.core.models.DocumentModel;
import org.ebookdroid.core.models.ZoomModel;
import org.ebookdroid.ui.library.adapters.OutlineAdapter;
import org.ebookdroid.ui.settings.SettingsUI;
import org.ebookdroid.ui.viewer.stubs.ViewContollerStub;
import org.ebookdroid.ui.viewer.views.ViewEffects;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.ProgressDialog;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.AsyncTask;
import android.provider.MediaStore;
import android.text.Editable;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.Toast;

import java.io.File;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.emdev.ui.actions.ActionController;
import org.emdev.ui.actions.ActionDialogBuilder;
import org.emdev.ui.actions.ActionEx;
import org.emdev.ui.actions.ActionMethod;
import org.emdev.ui.actions.ActionMethodDef;
import org.emdev.ui.actions.ActionTarget;
import org.emdev.ui.actions.IActionController;
import org.emdev.ui.actions.params.Constant;
import org.emdev.ui.actions.params.EditableValue;
import org.emdev.ui.uimanager.IUIManager;
import org.emdev.utils.LengthUtils;
import org.emdev.utils.StringUtils;
import org.emdev.utils.filesystem.PathFromUri;

@ActionTarget(
// action list
actions = {
        // start
        @ActionMethodDef(id = R.id.actions_addBookmark, method = "addBookmark"),
        @ActionMethodDef(id = R.id.mainmenu_close, method = "closeActivity"),
        @ActionMethodDef(id = R.id.actions_gotoOutlineItem, method = "gotoOutlineItem"),
        @ActionMethodDef(id = R.id.actions_redecodingWithPassword, method = "redecodingWithPassword"),
        @ActionMethodDef(id = R.id.mainmenu_settings, method = "showAppSettings"),
        @ActionMethodDef(id = R.id.mainmenu_bookmark, method = "showBookmarkDialog"),
        @ActionMethodDef(id = R.id.mainmenu_booksettings, method = "showBookSettings"),
        @ActionMethodDef(id = R.id.mainmenu_goto_page, method = "showDialog"),
        @ActionMethodDef(id = R.id.mainmenu_outline, method = "showOutline"),
        @ActionMethodDef(id = R.id.mainmenu_nightmode, method = "toggleNightMode"),
        @ActionMethodDef(id = R.id.mainmenu_zoom, method = "toggleControls"),
        @ActionMethodDef(id = R.id.mainmenu_thumbnail, method = "setCurrentPageAsThumbnail"),
        @ActionMethodDef(id = R.id.mainmenu_about, method = "showAbout"),
        @ActionMethodDef(id = R.id.actions_toggleTouchManagerView, method = "toggleControls"),
        @ActionMethodDef(id = R.id.actions_openOptionsMenu, method = "openOptionsMenu"),
        @ActionMethodDef(id = R.id.actions_keyBindings, method = "showKeyBindingsDialog")
// finish
})
public class ViewerActivityController extends ActionController<ViewerActivity> implements IActivityController,
        DecodingProgressListener, CurrentPageListener, ISettingsChangeListener {

    private static final String E_MAIL_ATTACHMENT = "[E-mail Attachment]";

    private static final int DIALOG_GOTO = 0;

    private static final AtomicLong SEQ = new AtomicLong();

    private final LogContext LCTX;

    private final AtomicReference<IViewController> ctrl = new AtomicReference<IViewController>(ViewContollerStub.STUB);

    private ZoomModel zoomModel;

    private DecodingProgressModel progressModel;

    private DocumentModel documentModel;

    private String bookTitle;

    private boolean temporaryBook;

    private CodecType codecType;

    private final Intent intent;

    private int loadingCount = 0;

    private String m_fileName;

    /**
     * Instantiates a new base viewer activity.
     */
    public ViewerActivityController(final ViewerActivity activity) {
        super(activity);
        LCTX = LogContext.ROOT.lctx("Controller", true).lctx("" + SEQ.getAndIncrement(), true);
        this.intent = activity.getIntent();
        SettingsManager.addListener(this);
    }

    public void beforeCreate(final ViewerActivity activity) {
        if (LCTX.isDebugEnabled()) {
            LCTX.d("beforeCreate(): " + activity.LCTX);
        }
        if (getManagedComponent() != activity) {
            setManagedComponent(activity);
        }

        final AppSettings newSettings = SettingsManager.getAppSettings();

        activity.setRequestedOrientation(newSettings.rotation.getOrientation());

        IUIManager.instance.setFullScreenMode(activity, newSettings.fullScreen);
        IUIManager.instance.setTitleVisible(activity, newSettings.showTitle);

        TouchManager.loadFromSettings(newSettings);
        KeyBindingsManager.loadFromSettings(newSettings);

        BitmapManager.setPartSize(1 << newSettings.bitmapSize);
        BitmapManager.setUseEarlyRecycling(newSettings.useEarlyRecycling);
    }

    public void afterCreate() {
        if (LCTX.isDebugEnabled()) {
            LCTX.d("afterCreate()");
        }

        final ViewerActivity activity = getManagedComponent();

        createAction(R.id.mainmenu_goto_page, new Constant("dialogId", DIALOG_GOTO));
        createAction(R.id.mainmenu_zoom).putValue("view", activity.getZoomControls());
        createAction(R.id.actions_toggleTouchManagerView).putValue("view", activity.getTouchView());

        if (++loadingCount == 1) {
            if (intent.getScheme().equals("content")) {
                try {
                    Cursor c = activity.getContentResolver().query(intent.getData(), null, null, null, null);
                    c.moveToFirst();
                    final int fileNameColumnId = c.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME);
                    if (fileNameColumnId >= 0) {
                        String attachmentFileName = c.getString(fileNameColumnId);
                        bookTitle = LengthUtils.safeString(attachmentFileName, E_MAIL_ATTACHMENT);
                        codecType = CodecType.getByUri(attachmentFileName);
                    } else {
                        if (LCTX.isDebugEnabled()) {
                            LCTX.d("No attachment file name returned");
                        }
                    }
                } catch (Throwable th) {
                    LCTX.e("Unexpected error: ", th);
                }
            }
            if (codecType == null) {
                codecType = CodecType.getByUri(intent.getData().toString());
                bookTitle = LengthUtils.safeString(intent.getData().getLastPathSegment(), E_MAIL_ATTACHMENT);
            }
            if (codecType == null) {
                throw new RuntimeException("Unknown intent data type: " + intent.getData());
            }

            documentModel = new DocumentModel(codecType);
            documentModel.addListener(ViewerActivityController.this);
            progressModel = new DecodingProgressModel();
            progressModel.addListener(ViewerActivityController.this);

            final Uri uri = intent.getData();
            m_fileName = "";

            if (intent.getScheme().equals("content")) {
                temporaryBook = true;
                m_fileName = E_MAIL_ATTACHMENT;
                CacheManager.clear(m_fileName);
            } else {
                m_fileName = PathFromUri.retrieve(activity.getContentResolver(), uri);
            }

            SettingsManager.init(m_fileName);
            SettingsManager.applyBookSettingsChanges(null, SettingsManager.getBookSettings(), null);
        }
    }

    public void beforePostCreate() {
        if (LCTX.isDebugEnabled()) {
            LCTX.d("beforePostCreate()");
        }
    }

    public void afterPostCreate() {
        if (LCTX.isDebugEnabled()) {
            LCTX.d("afterPostCreate()");
        }
        setWindowTitle();
        if (loadingCount == 1) {
            startDecoding(m_fileName, "");
        }
    }

    public void startDecoding(final String fileName, final String password) {
        getManagedComponent().view.getView().post(new BookLoadTask(fileName, password));
    }

    public void beforeResume() {
        if (LCTX.isDebugEnabled()) {
            LCTX.d("beforeResume()");
        }
    }

    public void afterResume() {
        if (LCTX.isDebugEnabled()) {
            LCTX.d("afterResume()");
        }
    }

    public void beforePause() {
        if (LCTX.isDebugEnabled()) {
            LCTX.d("beforePause()");
        }
    }

    public void afterPause() {
        if (LCTX.isDebugEnabled()) {
            LCTX.d("afterPause()");
        }
        SettingsManager.storeBookSettings();
    }

    public void beforeDestroy() {
        final boolean finishing = getManagedComponent().isFinishing();
        if (LCTX.isDebugEnabled()) {
            LCTX.d("beforeDestroy(): " + finishing);
        }
        if (finishing) {
            getManagedComponent().view.onDestroy();
            if (documentModel != null) {
                documentModel.recycle();
            }
            if (temporaryBook) {
                CacheManager.clear(E_MAIL_ATTACHMENT);
            }
            SettingsManager.removeListener(this);
        }
    }

    public void afterDestroy() {
        if (LCTX.isDebugEnabled()) {
            LCTX.d("afterDestroy()");
        }
        getDocumentController().onDestroy();
    }

    public void askPassword(final String fileName, final String promt) {
        final EditText input = new EditText(getManagedComponent());
        input.setSingleLine(true);
        input.setInputType(EditorInfo.TYPE_CLASS_TEXT | EditorInfo.TYPE_TEXT_VARIATION_PASSWORD);

        final ActionDialogBuilder builder = new ActionDialogBuilder(getManagedComponent(), this);
        builder.setTitle(fileName).setMessage(promt).setView(input);
        builder.setPositiveButton(R.id.actions_redecodingWithPassword, new EditableValue("input", input), new Constant(
                "fileName", fileName));
        builder.setNegativeButton(R.id.mainmenu_close).show();
    }

    public void showErrorDlg(final String msg) {
        final ActionDialogBuilder builder = new ActionDialogBuilder(getManagedComponent(), this);
        builder.setTitle("Application error").setMessage(LengthUtils.safeString(msg, "Unexpected error occured!"));
        builder.setPositiveButton(R.string.error_close, R.id.mainmenu_close);
        builder.show();
    }

    @ActionMethod(ids = R.id.actions_redecodingWithPassword)
    public void redecodingWithPassword(final ActionEx action) {
        final Editable value = action.getParameter("input");
        final String password = value.toString();
        final String fileName = action.getParameter("fileName");
        startDecoding(fileName, password);
    }

    @Override
    public IViewController switchDocumentController() {
        try {
            final BookSettings bs = SettingsManager.getBookSettings();

            final IViewController newDc = bs.viewMode.create(this);
            final IViewController oldDc = ctrl.getAndSet(newDc);

            getZoomModel().removeListener(oldDc);
            getZoomModel().addListener(newDc);

            return ctrl.get();
        } catch (final Throwable e) {
            throw new RuntimeException(e);
        }
    }

    @Override
    public void decodingProgressChanged(final int currentlyDecoding) {
        final ViewerActivity activity = getManagedComponent();
        activity.runOnUiThread(new Runnable() {

            @Override
            public void run() {
                try {
                    activity.setProgressBarIndeterminateVisibility(currentlyDecoding > 0);
                    activity.getWindow().setFeatureInt(Window.FEATURE_INDETERMINATE_PROGRESS,
                            currentlyDecoding == 0 ? 10000 : currentlyDecoding);
                } catch (final Throwable e) {
                }
            }
        });
    }

    @Override
    public void currentPageChanged(final PageIndex oldIndex, final PageIndex newIndex) {
        final int pageCount = documentModel.getPageCount();
        String pageText = "";
        if (pageCount > 0) {
            pageText = (newIndex.viewIndex + 1) + "/" + pageCount;
        }
        getManagedComponent().currentPageChanged(pageText, bookTitle);
        SettingsManager.currentPageChanged(oldIndex, newIndex);
    }

    public void setWindowTitle() {
        bookTitle = StringUtils.cleanupTitle(bookTitle);
        getManagedComponent().getWindow().setTitle(bookTitle);
    }

    @ActionMethod(ids = R.id.actions_openOptionsMenu)
    public void openOptionsMenu(final ActionEx action) {
        IUIManager.instance.openOptionsMenu(getManagedComponent(), getManagedComponent().view.getView());
    }

    @ActionMethod(ids = R.id.actions_gotoOutlineItem)
    public void gotoOutlineItem(final ActionEx action) {
        final Integer item = action.getParameter(IActionController.DIALOG_ITEM_PROPERTY);
        final List<OutlineLink> outline = action.getParameter("outline");

        final String link = outline.get(item).getLink();
        if (link.startsWith("#")) {
            int pageNumber = 0;
            try {
                pageNumber = Integer.parseInt(link.substring(1).replace(" ", ""));
            } catch (final Exception e) {
                pageNumber = 0;
            }
            if (pageNumber < 1 || pageNumber > documentModel.getPageCount()) {
                getManagedComponent().showToastText(2000, R.string.error_page_out_of_rande,
                        documentModel.getDecodeService().getPageCount());
                return;
            }
            getDocumentController().goToPage(pageNumber - 1);
        } else if (link.startsWith("http:")) {
            final Intent i = new Intent(Intent.ACTION_VIEW);
            i.setData(Uri.parse(link));
            getManagedComponent().startActivity(i);
        }
    }

    @ActionMethod(ids = R.id.mainmenu_outline)
    public void showOutline(final ActionEx action) {
        final List<OutlineLink> outline = documentModel.getDecodeService().getOutline();
        if ((outline != null) && (outline.size() > 0)) {
            final AlertDialog.Builder builder = new AlertDialog.Builder(getManagedComponent());
            builder.setTitle(R.string.outline_title);
            builder.setAdapter(new OutlineAdapter(getManagedComponent(), outline),
                    this.getOrCreateAction(R.id.actions_gotoOutlineItem).putValue("outline", outline));
            final AlertDialog alert = builder.create();
            alert.show();
        } else {
            getManagedComponent().showToastText(Toast.LENGTH_SHORT, R.string.outline_missed);
        }
    }

    @ActionMethod(ids = R.id.mainmenu_goto_page)
    public void showDialog(final ActionEx action) {
        final Integer dialogId = action.getParameter("dialogId");
        getManagedComponent().showDialog(dialogId);
    }

    @ActionMethod(ids = R.id.mainmenu_booksettings)
    public void showBookSettings(final ActionEx action) {
        SettingsUI.showBookSettings(getManagedComponent(), SettingsManager.getBookSettings().fileName);
    }

    @ActionMethod(ids = R.id.mainmenu_settings)
    public void showAppSettings(final ActionEx action) {
        SettingsUI.showAppSettings(getManagedComponent());
    }

    @ActionMethod(ids = R.id.mainmenu_nightmode)
    public void toggleNightMode(final ActionEx action) {
        SettingsManager.toggleNightMode();
    }

    @ActionMethod(ids = R.id.mainmenu_thumbnail)
    public void setCurrentPageAsThumbnail(final ActionEx action) {
        final Page page = documentModel.getCurrentPageObject();
        if (page != null) {
            documentModel.createBookThumbnail(SettingsManager.getBookSettings(), page, true);
        }
    }

    @ActionMethod(ids = R.id.mainmenu_bookmark)
    public void showBookmarkDialog(final ActionEx action) {
        final int page = documentModel.getCurrentViewPageIndex();

        final String message = getManagedComponent().getString(R.string.add_bookmark_name);

        final EditText input = new EditText(getManagedComponent());
        input.setText(getManagedComponent().getString(R.string.text_page) + " " + (page + 1));
        input.selectAll();

        final ActionDialogBuilder builder = new ActionDialogBuilder(getManagedComponent(), this);
        builder.setTitle(R.string.menu_add_bookmark).setMessage(message).setView(input);
        builder.setPositiveButton(R.id.actions_addBookmark, new EditableValue("input", input));
        builder.setNegativeButton().show();
    }

    @ActionMethod(ids = R.id.actions_addBookmark)
    public void addBookmark(final ActionEx action) {
        final Editable value = action.getParameter("input");
        final String name = value.toString();
        final BookSettings bs = SettingsManager.getBookSettings();
        bs.bookmarks.add(new Bookmark(name, getDocumentModel().getCurrentIndex(), 0, 0));
        SettingsManager.storeBookSettings();
    }

    @ActionMethod(ids = R.id.actions_keyBindings)
    public void showKeyBindingsDialog(final ActionEx action) {
        KeyBindingsDialog dlg = new KeyBindingsDialog(this);
        dlg.show();
    }

    /**
     * Gets the zoom model.
     * 
     * @return the zoom model
     */
    @Override
    public ZoomModel getZoomModel() {
        if (zoomModel == null) {
            zoomModel = new ZoomModel();
        }
        return zoomModel;
    }

    @Override
    public DecodeService getDecodeService() {
        return documentModel != null ? documentModel.getDecodeService() : null;
    }

    /**
     * Gets the decoding progress model.
     * 
     * @return the decoding progress model
     */
    @Override
    public DecodingProgressModel getDecodingProgressModel() {
        return progressModel;
    }

    @Override
    public DocumentModel getDocumentModel() {
        return documentModel;
    }

    @Override
    public IViewController getDocumentController() {
        return ctrl.get();
    }

    @Override
    public Context getContext() {
        return getManagedComponent();
    }

    @Override
    public IView getView() {
        return getManagedComponent().view;
    }

    @Override
    public Activity getActivity() {
        return getManagedComponent();
    }

    @Override
    public IActionController<?> getActionController() {
        return this;
    }

    @ActionMethod(ids = { R.id.mainmenu_zoom, R.id.actions_toggleTouchManagerView })
    public void toggleControls(final ActionEx action) {
        final View view = action.getParameter("view");
        ViewEffects.toggleControls(view);
    }

    public final boolean dispatchKeyEvent(final KeyEvent event) {
        if (getDocumentController().dispatchKeyEvent(event)) {
            return true;
        }

        if (event.getAction() == KeyEvent.ACTION_DOWN) {
            switch (event.getKeyCode()) {
                case KeyEvent.KEYCODE_BACK:
                    if (event.getRepeatCount() == 0) {
                        if (getManagedComponent().getTouchView().isShown()) {
                            ViewEffects.toggleControls(getManagedComponent().getTouchView());
                        } else {
                            closeActivity(null);
                        }
                    }
                    return true;
                default:
                    return false;
            }
        }
        return false;
    }

    @ActionMethod(ids = R.id.mainmenu_close)
    public void closeActivity(final ActionEx action) {
        if (documentModel != null) {
            documentModel.recycle();
        }
        if (temporaryBook) {
            CacheManager.clear(E_MAIL_ATTACHMENT);
            SettingsManager.removeCurrentBookSettings();
        } else {
            SettingsManager.clearCurrentBookSettings();
        }
        getManagedComponent().finish();
    }

    /**
     * {@inheritDoc}
     * 
     * @see org.ebookdroid.common.settings.ISettingsChangeListener#onAppSettingsChanged(org.ebookdroid.common.settings.AppSettings,
     *      org.ebookdroid.common.settings.AppSettings, org.ebookdroid.common.settings.AppSettings.Diff)
     */
    @Override
    public void onAppSettingsChanged(final AppSettings oldSettings, final AppSettings newSettings,
            final AppSettings.Diff diff) {
        if (diff.isRotationChanged()) {
            getManagedComponent().setRequestedOrientation(newSettings.rotation.getOrientation());
        }

        if (diff.isFullScreenChanged()) {
            IUIManager.instance.setFullScreenMode(getManagedComponent(), newSettings.fullScreen);
        }

        if (diff.isKeepScreenOnChanged()) {
            getManagedComponent().view.getView().setKeepScreenOn(newSettings.keepScreenOn);
        }

        if (diff.isNightModeChanged()) {
            getDocumentController().toggleNightMode(newSettings.nightMode);
        }

        TouchManager.loadFromSettings(newSettings);
        KeyBindingsManager.loadFromSettings(newSettings);

        BitmapManager.setPartSize(1 << newSettings.bitmapSize);
        BitmapManager.setUseEarlyRecycling(newSettings.useEarlyRecycling);
    }

    /**
     * {@inheritDoc}
     * 
     * @see org.ebookdroid.common.settings.ISettingsChangeListener#onBookSettingsChanged(org.ebookdroid.common.settings.books.BookSettings,
     *      org.ebookdroid.common.settings.books.BookSettings, org.ebookdroid.common.settings.books.BookSettings.Diff,
     *      org.ebookdroid.common.settings.AppSettings.Diff)
     */
    @Override
    public void onBookSettingsChanged(final BookSettings oldSettings, final BookSettings newSettings,
            final BookSettings.Diff diff, final AppSettings.Diff appDiff) {

        boolean redrawn = false;
        if (diff.isViewModeChanged() || diff.isSplitPagesChanged() || diff.isCropPagesChanged()) {
            redrawn = true;
            final IViewController newDc = switchDocumentController();
            if (!diff.isFirstTime()) {
                newDc.init(null);
                newDc.show();
            }
        }

        if (diff.isFirstTime()) {
            getZoomModel().initZoom(newSettings.getZoom());
        }

        final IViewController dc = getDocumentController();
        if (diff.isPageAlignChanged()) {
            dc.setAlign(newSettings.pageAlign);
        }

        if (diff.isAnimationTypeChanged()) {
            dc.updateAnimationType();
        }

        if (!redrawn && appDiff != null) {
            if (appDiff.isPagesInMemoryChanged()) {
                dc.updateMemorySettings();
            }
        }

        currentPageChanged(PageIndex.NULL, documentModel.getCurrentIndex());
    }

    final class BookLoadTask extends AsyncTask<String, String, Throwable> implements IBookLoadTask, Runnable {

        private String m_fileName;
        private final String m_password;
        private ProgressDialog progressDialog;

        public BookLoadTask(final String fileName, final String password) {
            m_fileName = fileName;
            m_password = password;
        }

        @Override
        public void run() {
            execute(" ");
        }

        @Override
        protected void onPreExecute() {
            ViewerActivity activity = getManagedComponent();
            LCTX.d("BookLoadTask.onPreExecute(" + activity.LCTX + "): start");
            try {
                final String message = activity.getString(R.string.msg_loading);
                progressDialog = ProgressDialog.show(activity, "", message, true);
            } catch (final Throwable th) {
                LCTX.e("BookLoadTask.onPreExecute(): Unexpected error", th);
            } finally {
                LCTX.d("BookLoadTask.onPreExecute(): finish");
            }
        }

        @Override
        protected Throwable doInBackground(final String... params) {
            LCTX.d("BookLoadTask.doInBackground(): start");
            try {
                if (intent.getScheme().equals("content")) {
                    final File tempFile = CacheManager.createTempFile(intent.getData());
                    m_fileName = tempFile.getAbsolutePath();
                }
                getView().waitForInitialization();
                documentModel.open(m_fileName, m_password);
                getDocumentController().init(this);
                return null;
            } catch (final Exception e) {
                LCTX.e(e.getMessage(), e);
                return e;
            } catch (final Throwable th) {
                LCTX.e("BookLoadTask.doInBackground(): Unexpected error", th);
                return th;
            } finally {
                LCTX.d("BookLoadTask.doInBackground(): finish");
            }
        }

        @Override
        protected void onPostExecute(Throwable result) {
            LCTX.d("BookLoadTask.onPostExecute(): start");
            try {
                if (result == null) {
                    try {
                        getDocumentController().show();

                        final DocumentModel dm = getDocumentModel();
                        currentPageChanged(PageIndex.NULL, dm.getCurrentIndex());

                    } catch (Throwable th) {
                        result = th;
                    }
                }

                try {
                    progressDialog.dismiss();
                } catch (final Throwable th) {
                }

                if (result != null) {
                    try {
                        progressDialog.dismiss();
                    } catch (final Throwable th) {
                    }

                    final String msg = result.getMessage();
                    if ("PDF needs a password!".equals(msg)) {
                        askPassword(m_fileName, "Enter password...");
                    } else if ("Wrong password given".equals(msg)) {
                        askPassword(m_fileName, msg + "...");
                    } else {
                        EmergencyHandler.onUnexpectedError(result);
                        showErrorDlg(msg);
                    }
                }
            } catch (final Throwable th) {
                LCTX.e("BookLoadTask.onPostExecute(): Unexpected error", th);
                EmergencyHandler.onUnexpectedError(result);
            } finally {
                LCTX.d("BookLoadTask.onPostExecute(): finish");
            }
        }

        @Override
        public void setProgressDialogMessage(final int resourceID, final Object... args) {
            final String message = getManagedComponent().getString(resourceID, args);
            publishProgress(message);
        }

        @Override
        protected void onProgressUpdate(final String... values) {
            if (values != null && values.length > 0) {
                if (!progressDialog.isShowing()) {
                    ViewerActivity activity = getManagedComponent();
                    progressDialog = ProgressDialog.show(activity, "", values[0], true);
                } else {
                    progressDialog.setMessage(values[0]);
                }
            }
        }
    }
}
