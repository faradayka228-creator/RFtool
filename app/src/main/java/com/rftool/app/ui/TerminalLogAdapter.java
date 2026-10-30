package com.rftool.app.ui;

/*
 * Путь: /RFTool/app/src/main/java/com/rftool/app/ui/TerminalLogAdapter.java
 *
 * Адаптер для RecyclerView, стилизованного под терминал-консоль.
 * Поддерживает цветовую кодировку строк по уровню: INFO / METRIC / BROADCAST / ERROR.
 */

import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public class TerminalLogAdapter extends RecyclerView.Adapter<TerminalLogAdapter.LogViewHolder> {

    public enum Level { INFO, METRIC, BROADCAST, ERROR, WARN }

    public static class LogEntry {
        final String timestamp;
        final Level level;
        final String message;

        LogEntry(String timestamp, Level level, String message) {
            this.timestamp = timestamp;
            this.level = level;
            this.message = message;
        }
    }

    private final List<LogEntry> entries = new ArrayList<>();      // то, что реально показывается
    private final List<LogEntry> archive = new ArrayList<>();      // полная история, не зависящая от фильтра
    private final SimpleDateFormat timeFormat = new SimpleDateFormat("HH:mm:ss", Locale.US);
    private static final int MAX_ENTRIES = 500; // защита от разрастания памяти при долгих сессиях

    // 0 = показывать всё, 1 = скрывать INFO, 2 = показывать только WARN/ERROR
    private int filterLevel = 0;

    public void setFilterLevel(int level) {
        this.filterLevel = level;
        rebuildVisibleFromArchive();
    }

    private boolean passesFilter(Level level) {
        switch (filterLevel) {
            case 2: return level == Level.WARN || level == Level.ERROR;
            case 1: return level != Level.INFO;
            default: return true;
        }
    }

    private void rebuildVisibleFromArchive() {
        int oldSize = entries.size();
        entries.clear();
        notifyItemRangeRemoved(0, oldSize);
        for (LogEntry e : archive) {
            if (passesFilter(e.level)) entries.add(e);
        }
        notifyItemRangeInserted(0, entries.size());
    }

    public void log(Level level, String message) {
        String ts = timeFormat.format(new java.util.Date());
        LogEntry entry = new LogEntry(ts, level, message);

        archive.add(entry);
        if (archive.size() > MAX_ENTRIES) {
            archive.remove(0);
        }

        if (passesFilter(level)) {
            entries.add(entry);
            if (entries.size() > MAX_ENTRIES) {
                entries.remove(0);
                notifyItemRemoved(0);
            }
            notifyItemInserted(entries.size() - 1);
        }
    }

    public void clear() {
        int size = entries.size();
        entries.clear();
        archive.clear();
        notifyItemRangeRemoved(0, size);
    }

    public int getLastIndex() {
        return entries.size() - 1;
    }

    @NonNull
    @Override
    public LogViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        TextView tv = new TextView(parent.getContext());
        tv.setTypeface(android.graphics.Typeface.MONOSPACE);
        tv.setTextSize(11f);
        tv.setPadding(4, 2, 4, 2);
        return new LogViewHolder(tv);
    }

    @Override
    public void onBindViewHolder(@NonNull LogViewHolder holder, int position) {
        LogEntry entry = entries.get(position);
        String prefix = "[" + entry.level.name() + "]";
        holder.textView.setText(entry.timestamp + " " + prefix + " " + entry.message);
        holder.textView.setTextColor(colorForLevel(entry.level));
    }

    private int colorForLevel(Level level) {
        switch (level) {
            case METRIC:
                return Color.parseColor("#39FF88");
            case BROADCAST:
                return Color.parseColor("#59C8FF");
            case ERROR:
                return Color.parseColor("#FF4C4C");
            case WARN:
                return Color.parseColor("#FFB020");
            case INFO:
            default:
                return Color.parseColor("#9FD8B8");
        }
    }

    @Override
    public int getItemCount() {
        return entries.size();
    }

    static class LogViewHolder extends RecyclerView.ViewHolder {
        final TextView textView;

        LogViewHolder(@NonNull View itemView) {
            super(itemView);
            this.textView = (TextView) itemView;
        }
    }
}
