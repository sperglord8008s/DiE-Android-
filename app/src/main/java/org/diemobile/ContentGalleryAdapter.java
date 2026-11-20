// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile;

import android.content.Context;
import android.graphics.Bitmap;
import android.os.*;
import android.view.*;
import android.widget.*;

import java.util.*;
import java.util.concurrent.Executor;

/** One bounded page of labeled cards. Recycled tiles share a single thumbnail request per card. */
final class ContentGalleryAdapter extends BaseAdapter implements AutoCloseable {
    private final Context context;
    private final List<ContentMap.Card> cards;
    private final InputSnapshot input;
    private final Executor worker;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<Integer, Bitmap> thumbnails = new HashMap<>();
    private final Map<Integer, String> failures = new HashMap<>();
    private final Map<Integer, MediaFrame.Result> media = new HashMap<>();
    private final Set<Integer> pending = new HashSet<>();
    private volatile boolean closed;

    ContentGalleryAdapter(
            Context context, List<ContentMap.Card> cards, InputSnapshot input, Executor worker) {
        if (cards.size() > 24) throw new IllegalArgumentException("Gallery page exceeds 24 cards");
        this.context = context;
        this.cards = Collections.unmodifiableList(new ArrayList<>(cards));
        this.input = input;
        this.worker = worker;
    }

    @Override
    public int getCount() {
        return cards.size();
    }

    @Override
    public Object getItem(int index) {
        return cards.get(index);
    }

    @Override
    public long getItemId(int index) {
        return index;
    }

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
    }

    private TextView text(String value, int size) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(0xffe5eaf0);
        return view;
    }

    @Override
    public View getView(int index, View convert, ViewGroup parent) {
        ContentMap.Card card = cards.get(index);
        LinearLayout tile = new LinearLayout(context);
        tile.setOrientation(LinearLayout.VERTICAL);
        tile.setPadding(dp(8), dp(8), dp(8), dp(8));
        tile.setBackgroundColor(0xff26323a);
        TextView label = text(card.label, 14);
        label.setMaxLines(3);
        tile.addView(label);
        tile.addView(
                text(
                        card.kind.name()
                                + " · 0x"
                                + Long.toHexString(card.offset)
                                + "\n"
                                + (card.length == null ? "Extent unknown" : card.length + " bytes"),
                        12));
        if ((card.kind == ContentMap.Kind.IMAGE
                        || card.kind == ContentMap.Kind.AUDIO
                        || card.kind == ContentMap.Kind.VIDEO)
                && card.length != null) {
            ImageView image = new ImageView(context);
            image.setContentDescription(card.label);
            image.setScaleType(ImageView.ScaleType.FIT_CENTER);
            tile.addView(image, new LinearLayout.LayoutParams(-1, dp(112)));
            TextView status = text("Decoding thumbnail…", 11);
            tile.addView(status);
            Bitmap bitmap = thumbnails.get(index);
            if (bitmap == null && (media.containsKey(index) || card.kind == ContentMap.Kind.AUDIO))
                image.setVisibility(View.GONE);
            if (bitmap != null) {
                image.setImageBitmap(bitmap);
                status.setText(
                        media.containsKey(index)
                                ? media.get(index).description()
                                : "Platform decoded thumbnail · tap for preview/evidence");
            } else if (media.containsKey(index)) status.setText(media.get(index).description());
            else if (failures.containsKey(index))
                status.setText("Thumbnail unavailable: " + failures.get(index));
            else if (!closed && pending.add(index)) thumbnail(index, card);
        } else if (card.kind == ContentMap.Kind.TEXT) {
            TextView preview = text(card.preview, 12);
            preview.setMaxLines(5);
            tile.addView(preview);
        } else
            tile.addView(
                    text(
                            card.length == null
                                    ? "Extent unknown · inspect witness or choose explicit"
                                            + " inspection bytes"
                                    : "Tap to preview, inspect or export exact bytes",
                            12));
        return tile;
    }

    private void thumbnail(int index, ContentMap.Card card) {
        worker.execute(
                () -> {
                    if (closed) return;
                    Bitmap bitmap = null;
                    MediaFrame.Result observation = null;
                    String error = "";
                    try {
                        if (card.kind == ContentMap.Kind.IMAGE)
                            bitmap = ContentPreview.image(input.file, card.range(input.size), 256);
                        else {
                            observation =
                                    MediaFrame.read(
                                            input, card.range(input.size), 256, () -> closed);
                            bitmap = observation.bitmap;
                        }
                    } catch (Exception failure) {
                        error = failure.toString();
                    }
                    Bitmap result = bitmap;
                    MediaFrame.Result captured = observation;
                    String failure = error;
                    main.post(
                            () -> {
                                if (closed) {
                                    if (result != null) result.recycle();
                                    return;
                                }
                                pending.remove(index);
                                if (captured != null) media.put(index, captured);
                                if (result != null) thumbnails.put(index, result);
                                else if (captured == null) failures.put(index, failure);
                                notifyDataSetChanged();
                            });
                });
    }

    org.json.JSONObject mediaObservation(ContentMap.Card card) throws Exception {
        int index = cards.indexOf(card);
        MediaFrame.Result result = media.get(index);
        return result == null ? null : result.report();
    }

    @Override
    public void close() {
        closed = true;
        thumbnails.clear();
        media.clear();
        failures.clear();
        pending.clear();
        // Bitmaps already referenced by displayed ImageViews remain valid until those views are
        // collected.
    }
}
