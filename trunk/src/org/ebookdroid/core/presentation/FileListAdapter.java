package org.ebookdroid.core.presentation;

import org.ebookdroid.R;
import org.ebookdroid.core.settings.SettingsManager;
import org.ebookdroid.utils.FileUtils;

import android.database.DataSetObserver;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseExpandableListAdapter;
import android.widget.ImageView;
import android.widget.TextView;

import java.io.File;

public class FileListAdapter extends BaseExpandableListAdapter {

    final BooksAdapter adapter;
    final FolderObserver observer = new FolderObserver();

    public FileListAdapter(final BooksAdapter adapter) {
        this.adapter = adapter;
        adapter.registerDataSetObserver(new DataSetObserver() {

            @Override
            public void onChanged() {
                for (BookShelfAdapter a : adapter) {
                    if (a != null && a.id > 0) {
                        a.registerDataSetObserver(observer);
                    }
                }
                notifyDataSetChanged();
            }

            @Override
            public void onInvalidated() {
                notifyDataSetChanged();
            }
        });
    }

    @Override
    public BookNode getChild(final int groupPosition, final int childPosition) {
        return adapter.getItem(groupPosition + 1, childPosition);
    }

    @Override
    public long getChildId(final int groupPosition, final int childPosition) {
        return childPosition;
    }

    @Override
    public int getChildrenCount(final int groupPosition) {
        return adapter.getList(groupPosition + 1).getCount();
    }

    @Override
    public View getChildView(final int groupPosition, final int childPosition, final boolean isLastChild,
            final View convertView, final ViewGroup parent) {

        final ViewHolder holder = BaseViewHolder.getOrCreateViewHolder(ViewHolder.class, R.layout.browseritem,
                convertView, parent);

        final BookNode book = getChild(groupPosition, childPosition);
        final File file = new File(book.path);
        final boolean wasRead = SettingsManager.getBookSettings(file.getAbsolutePath()) != null;

        holder.name.setText(book.name);
        holder.image.setImageResource(wasRead ? R.drawable.bookwatched : R.drawable.book);
        holder.info.setText(FileUtils.getFileDate(file.lastModified()));
        holder.fileSize.setText(FileUtils.getFileSize(file.length()));

        return holder.getView();
    }

    @Override
    public View getGroupView(final int groupPosition, final boolean isExpanded, final View convertView,
            final ViewGroup parent) {

        final ViewHolder holder = BaseViewHolder.getOrCreateViewHolder(ViewHolder.class, R.layout.browseritem,
                convertView, parent);

        final BookShelfAdapter curr = getGroup(groupPosition);

        holder.name.setText(curr.name);
        holder.image.setImageResource(R.drawable.folderopen);
        holder.info.setText("Books: " + curr.getCount());
        holder.fileSize.setText("");

        return holder.getView();
    }

    @Override
    public BookShelfAdapter getGroup(final int groupPosition) {
        return adapter.getList(groupPosition + 1);
    }

    @Override
    public int getGroupCount() {
        return adapter.getListCount() - 1;
    }

    @Override
    public long getGroupId(final int groupPosition) {
        return groupPosition;
    }

    @Override
    public boolean isChildSelectable(final int groupPosition, final int childPosition) {
        return true;
    }

    @Override
    public boolean hasStableIds() {
        return false;
    }

    public void clearData() {
        adapter.clearData();
        notifyDataSetInvalidated();
    }

    public void startScan() {
        adapter.startScan();
    }

    public void stopScan() {
        adapter.stopScan();
    }

    static class ViewHolder extends BaseViewHolder {

        TextView name;
        ImageView image;
        TextView info;
        TextView fileSize;

        @Override
        public void init(final View convertView) {
            super.init(convertView);
            this.name = (TextView) convertView.findViewById(R.id.browserItemText);
            this.image = (ImageView) convertView.findViewById(R.id.browserItemIcon);
            this.info = (TextView) convertView.findViewById(R.id.browserItemInfo);
            this.fileSize = (TextView) convertView.findViewById(R.id.browserItemfileSize);
        }
    }

    class FolderObserver extends DataSetObserver {

        @Override
        public void onChanged() {
            notifyDataSetChanged();
        }

        @Override
        public void onInvalidated() {
            notifyDataSetChanged();
        }
    }

}
