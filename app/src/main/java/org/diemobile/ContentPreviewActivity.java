// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile;

import android.app.*;
import android.content.*;
import android.graphics.*;
import android.graphics.pdf.PdfRenderer;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.*;
import android.text.*;
import android.view.*;
import android.widget.*;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** In-app viewing of a separately owned exact range. No file name becomes an executable or URL. */
public final class ContentPreviewActivity extends Activity {
    private static final String[] MODES = {
        "Automatic", "Image", "Audio / video", "UTF-8 text", "PDF", "Hex"
    };
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final AtomicBoolean cancellation = new AtomicBoolean();
    private InputSnapshot input;
    private String name, mode = "Automatic", retainedPath;
    private TextView status, identity;
    private TextView exportNotice;
    private Button exportBytes;
    private PreparedExport contentExport;
    private boolean exportBusy, exportPickerOpen;
    private int exportGeneration;
    private String restoredExportNotice = "";
    private LinearLayout content, controls;
    private MediaPlayer player;
    private SurfaceView surface;
    private boolean prepared, paused = true;
    private int generation, pdfPage, seekPosition;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private TextView text(String value, int size) {
        TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(size);
        view.setTextColor(0xffe5eaf0);
        return view;
    }

    private Button button(String label, Runnable action) {
        Button view = new Button(this);
        view.setText(label);
        view.setTextSize(12);
        view.setAllCaps(false);
        view.setOnClickListener(v -> action.run());
        return view;
    }

    private void add(LinearLayout row, View view) {
        row.addView(view, new LinearLayout.LayoutParams(0, -2, 1));
    }

    @Override
    public void onCreate(Bundle saved) {
        super.onCreate(saved);
        retainedPath = getIntent().getStringExtra("path");
        if (retainedPath == null) {
            finish();
            return;
        }
        name = getIntent().getStringExtra("name");
        if (name == null) name = "Selected bytes";
        if (saved != null) {
            mode = saved.getString("mode", "Automatic");
            pdfPage = Math.max(0, saved.getInt("pdfPage"));
            seekPosition = Math.max(0, saved.getInt("seek"));
            if (saved.getString("contentExport") != null)
                try {
                    contentExport =
                            PreparedExport.restore(
                                    new File(getCacheDir(), "tool-exports"),
                                    saved.getString("contentExport"),
                                    saved.getString("contentExportName"),
                                    saved.getString("contentExportMime"),
                                    saved.getLong("contentExportSize"));
                    exportPickerOpen = true;
                    restoredExportNotice =
                            "Captured byte export is ready; finish choosing its destination";
                } catch (IOException error) {
                    restoredExportNotice = "Prepared byte export expired; prepare it again";
                }
        }
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xff15191e);
        root.setPadding(dp(8), dp(8), dp(8), dp(8));
        if (Build.VERSION.SDK_INT >= 30) {
            getWindow().setDecorFitsSystemWindows(false);
            root.setOnApplyWindowInsetsListener(
                    (view, insets) -> {
                        android.graphics.Insets bars =
                                insets.getInsets(
                                        WindowInsets.Type.systemBars()
                                                | WindowInsets.Type.displayCutout());
                        view.setPadding(
                                bars.left + dp(8),
                                bars.top + dp(8),
                                bars.right + dp(8),
                                bars.bottom + dp(8));
                        return insets;
                    });
        }
        TextView title = text(name, 18);
        title.setMaxLines(2);
        root.addView(title);
        identity = text("Checking owned preview bytes…", 12);
        identity.setTextIsSelectable(true);
        root.addView(identity);
        LinearLayout tools = new LinearLayout(this);
        add(tools, button("View as…", this::chooseMode));
        add(tools, button("Inspect / metadata", this::tools));
        exportBytes = button("Export bytes", this::exportBytes);
        exportBytes.setEnabled(false);
        add(tools, exportBytes);
        root.addView(tools);
        status = text("Loading…", 12);
        root.addView(status);
        exportNotice = text(restoredExportNotice, 12);
        exportNotice.setTextIsSelectable(true);
        exportNotice.setVisibility(restoredExportNotice.isEmpty() ? View.GONE : View.VISIBLE);
        root.addView(exportNotice);
        controls = new LinearLayout(this);
        root.addView(controls);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
        int ticket = ++generation;
        worker.execute(
                () -> {
                    try {
                        input =
                                InputSnapshot.restore(
                                        new File(retainedPath),
                                        new File(getCacheDir(), "content-previews"),
                                        getIntent().getLongExtra("sourceSize", -1),
                                        getIntent().getStringExtra("sha256"),
                                        cancellation::get);
                        runOnUiThread(
                                () -> {
                                    if (isDestroyed() || ticket != generation) return;
                                    identity.setText(
                                            input.size
                                                    + " exact bytes · SHA-256 "
                                                    + input.sha256
                                                    + "\nOriginal input "
                                                    + getIntent().getStringExtra("originalSha256")
                                                    + (getIntent().hasExtra("originalOffset")
                                                            ? " · offset 0x"
                                                                    + Long.toHexString(
                                                                            getIntent()
                                                                                    .getLongExtra(
                                                                                            "originalOffset",
                                                                                            0))
                                                            : " · derived archive member; original"
                                                                    + " physical offset"
                                                                    + " unavailable"));
                                    exportBytes.setEnabled(!exportBusy && !exportPickerOpen);
                                    render();
                                });
                    } catch (Exception error) {
                        failed(ticket, error);
                    }
                });
    }

    private void chooseMode() {
        new AlertDialog.Builder(this)
                .setTitle("View exact selected bytes as")
                .setItems(
                        MODES,
                        (dialog, which) -> {
                            mode = MODES[which];
                            render();
                        })
                .show();
    }

    private void exportMessage(String value) {
        exportNotice.setText(value);
        exportNotice.setVisibility(View.VISIBLE);
    }

    private void exportBytes() {
        if (input == null || exportBusy || exportPickerOpen) return;
        InputSnapshot captured = input;
        int operation = ++exportGeneration;
        exportBusy = true;
        exportBytes.setEnabled(false);
        exportMessage("Checking and staging exact selected bytes…");
        String suggested = getIntent().getStringExtra("exportName");
        String filename =
                suggested != null && suggested.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")
                        ? suggested
                        : "content-" + captured.sha256.substring(0, 12) + ".bin";
        worker.execute(
                () -> {
                    PreparedExport staged = null;
                    try {
                        staged =
                                OwnedContent.exportRange(
                                        captured,
                                        new FileRange(captured.size, 0, captured.size),
                                        new File(getCacheDir(), "tool-exports"),
                                        filename,
                                        cancellation::get);
                        EvidenceRows.check(cancellation::get);
                        PreparedExport ready = staged;
                        staged = null;
                        runOnUiThread(
                                () -> {
                                    if (isDestroyed() || operation != exportGeneration) {
                                        ready.discard();
                                        return;
                                    }
                                    exportBusy = false;
                                    contentExport = ready;
                                    exportPickerOpen = true;
                                    exportMessage(
                                            "Captured exact byte export · "
                                                    + ready.size
                                                    + " bytes · SHA-256 "
                                                    + captured.sha256);
                                    try {
                                        startActivityForResult(
                                                new Intent(Intent.ACTION_CREATE_DOCUMENT)
                                                        .addCategory(Intent.CATEGORY_OPENABLE)
                                                        .setType(ready.mime)
                                                        .putExtra(Intent.EXTRA_TITLE, ready.name),
                                                1);
                                    } catch (RuntimeException error) {
                                        contentExport = null;
                                        exportPickerOpen = false;
                                        ready.discard();
                                        exportBytes.setEnabled(input != null);
                                        exportMessage("Byte export could not open: " + error);
                                    }
                                });
                    } catch (Exception error) {
                        if (staged != null) staged.discard();
                        runOnUiThread(
                                () -> {
                                    if (!isDestroyed() && operation == exportGeneration) {
                                        exportBusy = false;
                                        exportBytes.setEnabled(input != null && !exportPickerOpen);
                                        exportMessage(
                                                "Byte export preparation failed: "
                                                        + error.getMessage());
                                    }
                                });
                    }
                });
    }

    private void finishByteExport(int result, Intent data) {
        PreparedExport staged = contentExport;
        contentExport = null;
        exportPickerOpen = false;
        if (staged == null) {
            exportBytes.setEnabled(input != null);
            exportMessage("Prepared byte export expired; prepare it again");
            return;
        }
        if (result != RESULT_OK || data == null || data.getData() == null) {
            staged.discard();
            exportBytes.setEnabled(input != null);
            exportMessage("Byte export cancelled");
            return;
        }
        Uri destination = data.getData();
        int operation = ++exportGeneration;
        exportBusy = true;
        exportBytes.setEnabled(false);
        exportMessage("Writing captured bytes…");
        worker.execute(
                () -> {
                    String message;
                    try (OutputStream output =
                            getContentResolver().openOutputStream(destination, "wt")) {
                        if (output == null) throw new IOException("Destination unavailable");
                        staged.copyTo(output, cancellation::get);
                        message = "Byte export saved · " + staged.size + " exact bytes";
                    } catch (Exception error) {
                        message =
                                "Byte export failed; destination may contain partial data: "
                                        + error.getMessage();
                    } finally {
                        staged.discard();
                    }
                    String response = message;
                    runOnUiThread(
                            () -> {
                                if (!isDestroyed() && operation == exportGeneration) {
                                    exportBusy = false;
                                    exportBytes.setEnabled(input != null);
                                    exportMessage(response);
                                }
                            });
                });
    }

    private void releasePlayer() {
        handler.removeCallbacksAndMessages(null);
        if (player != null) {
            try {
                player.reset();
            } catch (RuntimeException ignored) {
            }
            player.release();
            player = null;
        }
        prepared = false;
        paused = true;
    }

    private void render() {
        if (input == null) return;
        releasePlayer();
        int ticket = ++generation;
        content.removeAllViews();
        controls.removeAllViews();
        status.setText(
                "Rendering "
                        + mode
                        + " · decoder success is separate from complete-file integrity");
        worker.execute(
                () -> {
                    try {
                        input.checkIdentity(cancellation::get);
                        byte[] head = new byte[(int) Math.min(4096, input.size)];
                        try (InputStream stream = new FileInputStream(input.file)) {
                            int n = 0;
                            while (n < head.length) {
                                int read = stream.read(head, n, head.length - n);
                                if (read < 0) throw new EOFException();
                                n += read;
                            }
                        }
                        String type = Inspector.identify(head, head.length), chosen = mode;
                        if (chosen.equals("Automatic")) {
                            String lower = type.toLowerCase(Locale.ROOT);
                            String preferred = getIntent().getStringExtra("preferred");
                            if (lower.contains("image") || lower.equals("png")) chosen = "Image";
                            else if (lower.contains("audio")
                                    || lower.contains("media")
                                    || "AUDIO".equals(preferred)
                                    || "VIDEO".equals(preferred)) chosen = "Audio / video";
                            else if (type.equals("PDF")) chosen = "PDF";
                            else if ("TEXT".equals(preferred) || ContentPreview.looksLikeText(head))
                                chosen = "UTF-8 text";
                            else chosen = "Hex";
                        }
                        String renderer = chosen;
                        if (chosen.equals("Image")) {
                            Bitmap bitmap =
                                    ContentPreview.image(
                                            input.file,
                                            new FileRange(input.size, 0, input.size),
                                            2048);
                            runOnUiThread(
                                    () -> {
                                        if (isDestroyed() || ticket != generation) {
                                            bitmap.recycle();
                                            return;
                                        }
                                        ImageView image = new ImageView(this);
                                        image.setTag("content-image");
                                        image.setContentDescription(name);
                                        image.setScaleType(ImageView.ScaleType.FIT_CENTER);
                                        image.setImageBitmap(bitmap);
                                        content.addView(
                                                image, new LinearLayout.LayoutParams(-1, -1));
                                        status.setText(
                                                type
                                                        + " · platform decoded first frame "
                                                        + bitmap.getWidth()
                                                        + " × "
                                                        + bitmap.getHeight()
                                                        + " (bounded to 2048 × 2048)");
                                    });
                        } else if (chosen.equals("UTF-8 text") || chosen.equals("Hex")) {
                            int maximum = chosen.equals("Hex") ? 4096 : 1024 * 1024;
                            byte[] bytes = new byte[(int) Math.min(input.size, maximum)];
                            try (RandomAccessFile file = new RandomAccessFile(input.file, "r")) {
                                file.readFully(bytes);
                            }
                            String value;
                            if (chosen.equals("UTF-8 text")) {
                                // A bounded preview can end mid-codepoint; omit at most three
                                // suffix bytes explicitly.
                                int end = bytes.length;
                                if (input.size > end) {
                                    int lead = end - 1;
                                    while (lead >= 0 && (bytes[lead] & 0xc0) == 0x80) lead--;
                                    if (lead >= 0) {
                                        int first = bytes[lead] & 255,
                                                needed =
                                                        first >= 0xf0
                                                                ? 4
                                                                : first >= 0xe0
                                                                        ? 3
                                                                        : first >= 0xc2 ? 2 : 1;
                                        if (end - lead < needed) end = lead;
                                    }
                                }
                                value =
                                        StandardCharsets.UTF_8
                                                .newDecoder()
                                                .onMalformedInput(CodingErrorAction.REPORT)
                                                .decode(ByteBuffer.wrap(bytes, 0, end))
                                                .toString();
                            } else {
                                StringBuilder output = new StringBuilder();
                                for (int i = 0; i < bytes.length; i += 16) {
                                    output.append(String.format(Locale.ROOT, "%08X  ", i));
                                    for (int j = i; j < Math.min(i + 16, bytes.length); j++)
                                        output.append(
                                                String.format(
                                                        Locale.ROOT, "%02X ", bytes[j] & 255));
                                    output.append('\n');
                                }
                                value = output.toString();
                            }
                            String rendered = value;
                            runOnUiThread(
                                    () -> {
                                        if (isDestroyed() || ticket != generation) return;
                                        TextView view = text(rendered, 13);
                                        view.setTag("content-text");
                                        view.setTextIsSelectable(true);
                                        if (renderer.equals("Hex"))
                                            view.setTypeface(Typeface.MONOSPACE);
                                        ScrollView scroll = new ScrollView(this);
                                        scroll.addView(view);
                                        content.addView(
                                                scroll, new LinearLayout.LayoutParams(-1, -1));
                                        status.setText(
                                                renderer
                                                        + " · "
                                                        + bytes.length
                                                        + " / "
                                                        + input.size
                                                        + " source bytes read"
                                                        + (bytes.length < input.size
                                                                ? " · bounded preview; exact full"
                                                                      + " bytes remain available in"
                                                                      + " tools"
                                                                : ""));
                                    });
                        } else if (chosen.equals("PDF")) pdf(ticket);
                        else
                            runOnUiThread(
                                    () -> {
                                        if (!isDestroyed() && ticket == generation) media(ticket);
                                    });
                    } catch (Exception error) {
                        failed(ticket, error);
                    }
                });
    }

    private void pdf(int ticket) throws Exception {
        Bitmap bitmap;
        int count, selected;
        try (ParcelFileDescriptor file =
                        ParcelFileDescriptor.open(input.file, ParcelFileDescriptor.MODE_READ_ONLY);
                PdfRenderer renderer = new PdfRenderer(file)) {
            count = renderer.getPageCount();
            if (count == 0) throw new IOException("PDF has no renderable page");
            selected = Math.min(pdfPage, count - 1);
            try (PdfRenderer.Page page = renderer.openPage(selected)) {
                double scale = Math.min(1, 1536.0 / Math.max(page.getWidth(), page.getHeight()));
                bitmap =
                        Bitmap.createBitmap(
                                Math.max(1, (int) Math.round(page.getWidth() * scale)),
                                Math.max(1, (int) Math.round(page.getHeight() * scale)),
                                Bitmap.Config.ARGB_8888);
                bitmap.eraseColor(Color.WHITE);
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);
            }
        }
        runOnUiThread(
                () -> {
                    if (isDestroyed() || ticket != generation) {
                        bitmap.recycle();
                        return;
                    }
                    pdfPage = selected;
                    ImageView image = new ImageView(this);
                    image.setTag("content-pdf");
                    image.setImageBitmap(bitmap);
                    image.setScaleType(ImageView.ScaleType.FIT_CENTER);
                    content.addView(image, new LinearLayout.LayoutParams(-1, -1));
                    Button previous =
                            button(
                                    "Previous page",
                                    () -> {
                                        pdfPage--;
                                        render();
                                    });
                    previous.setEnabled(pdfPage > 0);
                    add(controls, previous);
                    Button next =
                            button(
                                    "Next page",
                                    () -> {
                                        pdfPage++;
                                        render();
                                    });
                    next.setEnabled(pdfPage + 1 < count);
                    add(controls, next);
                    status.setText(
                            "PDF page "
                                    + (pdfPage + 1)
                                    + " / "
                                    + count
                                    + " · platform renderer; scripts/links are not executed");
                });
    }

    private void media(int ticket) {
        try {
            surface = new SurfaceView(this);
            surface.setTag("content-media");
            content.addView(surface, new LinearLayout.LayoutParams(-1, 0, 1));
            player = new MediaPlayer();
            MediaPlayer captured = player;
            player.setOnErrorListener(
                    (value, what, extra) -> {
                        if (ticket == generation && value == player) {
                            status.setText(
                                    "Platform media decoder failed · what="
                                            + what
                                            + " extra="
                                            + extra
                                            + ". MPV probing and original bytes remain available.");
                            releasePlayer();
                        }
                        return true;
                    });
            player.setOnPreparedListener(
                    value -> {
                        if (isDestroyed() || ticket != generation || value != player) return;
                        prepared = true;
                        if (seekPosition > 0) value.seekTo(seekPosition);
                        paused = true;
                        status.setText(
                                "Local media ready · duration="
                                        + value.getDuration()
                                        + " ms · "
                                        + value.getVideoWidth()
                                        + " × "
                                        + value.getVideoHeight()
                                        + " · press Play");
                        SeekBar seek = new SeekBar(this);
                        seek.setMax(Math.max(1, value.getDuration()));
                        seek.setTag("content-seek");
                        content.addView(seek);
                        seek.setOnSeekBarChangeListener(
                                new SeekBar.OnSeekBarChangeListener() {
                                    public void onProgressChanged(
                                            SeekBar bar, int progress, boolean user) {
                                        if (user && player == value && prepared) {
                                            seekPosition = progress;
                                            value.seekTo(progress);
                                        }
                                    }

                                    public void onStartTrackingTouch(SeekBar bar) {}

                                    public void onStopTrackingTouch(SeekBar bar) {}
                                });
                        Runnable tick =
                                new Runnable() {
                                    public void run() {
                                        if (player == value && prepared && ticket == generation) {
                                            seek.setProgress(value.getCurrentPosition());
                                            handler.postDelayed(this, 500);
                                        }
                                    }
                                };
                        handler.post(tick);
                    });
            player.setOnCompletionListener(
                    value -> {
                        if (value == player) {
                            paused = true;
                            status.setText(
                                    "Playback reached the end · complete-file integrity is a"
                                            + " separate check");
                        }
                    });
            try (FileInputStream source = new FileInputStream(input.file)) {
                player.setDataSource(source.getFD(), 0, input.size);
            }
            surface.getHolder()
                    .addCallback(
                            new SurfaceHolder.Callback() {
                                public void surfaceCreated(SurfaceHolder holder) {
                                    if (player == captured) captured.setDisplay(holder);
                                }

                                public void surfaceChanged(
                                        SurfaceHolder holder, int format, int width, int height) {}

                                public void surfaceDestroyed(SurfaceHolder holder) {
                                    if (player == captured) captured.setDisplay(null);
                                }
                            });
            Button play =
                    button(
                            "Play / pause",
                            () -> {
                                if (player != captured || !prepared) return;
                                if (paused) {
                                    captured.start();
                                    paused = false;
                                } else {
                                    captured.pause();
                                    paused = true;
                                }
                            });
            play.setTag("content-play");
            add(controls, play);
            add(
                    controls,
                    button(
                            "Restart",
                            () -> {
                                if (player == captured && prepared) {
                                    seekPosition = 0;
                                    captured.seekTo(0);
                                }
                            }));
            player.prepareAsync();
        } catch (Exception error) {
            releasePlayer();
            status.setText(
                    "Local media unavailable: "
                            + error
                            + ". Use MPV / metadata tools for codec evidence.");
        }
    }

    private void tools() {
        if (input == null) return;
        new AlertDialog.Builder(this)
                .setTitle("Inspect these exact derived bytes")
                .setItems(
                        new String[] {
                            "Collected metadata / EXIF",
                            "MPV codecs / streams / tags",
                            "Classified strings",
                            "Archive hierarchy",
                            "Gallerize this range",
                            "All native tools"
                        },
                        (dialog, which) -> {
                            Class<?> target =
                                    which == 3
                                            ? NativeArchiveExplorerActivity.class
                                            : which == 4
                                                    ? GallerizeActivity.class
                                                    : which == 5
                                                            ? NativeToolsActivity.class
                                                            : InvestigationActivity.class;
                            Intent intent =
                                    new Intent(this, target)
                                            .putExtra("path", input.file.getAbsolutePath())
                                            .putExtra("name", name)
                                            .putExtra("sha256", input.sha256)
                                            .putExtra("sourceSize", input.size);
                            if (which < 3)
                                intent.putExtra(
                                        "provider",
                                        new String[] {"metadata", "mpv", "strings"}[which]);
                            if (which == 3) startActivityForResult(intent, 2);
                            else startActivity(intent);
                        })
                .show();
    }

    @Override
    protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);
        if (request == 1) {
            finishByteExport(result, data);
            return;
        }
        if (request != 2) return;
        if (result != RESULT_OK || data == null) {
            status.setText("Archive selection cancelled");
            return;
        }
        if (input == null) {
            status.setText("Archive parent preview expired");
            return;
        }
        releasePlayer();
        int ticket = ++generation;
        status.setText("Acquiring returned archive member…");
        ArchivePreviewLauncher.launch(
                this,
                worker,
                data,
                input.sha256,
                input.size,
                cancellation::get,
                () -> !isDestroyed() && ticket == generation,
                () -> {},
                message -> status.setText(message));
    }

    private void failed(int ticket, Exception error) {
        runOnUiThread(
                () -> {
                    if (!isDestroyed() && ticket == generation)
                        status.setText(
                                "Preview unavailable: "
                                        + error
                                        + ". Original bytes and other view modes remain"
                                        + " available.");
                });
    }

    @Override
    protected void onPause() {
        if (player != null && prepared) {
            seekPosition = player.getCurrentPosition();
            if (!paused) player.pause();
            paused = true;
        }
        super.onPause();
    }

    @Override
    protected void onSaveInstanceState(Bundle saved) {
        super.onSaveInstanceState(saved);
        saved.putString("mode", mode);
        saved.putInt("pdfPage", pdfPage);
        if (contentExport != null && exportPickerOpen) {
            saved.putString("contentExport", contentExport.file.getAbsolutePath());
            saved.putString("contentExportName", contentExport.name);
            saved.putString("contentExportMime", contentExport.mime);
            saved.putLong("contentExportSize", contentExport.size);
        }
        saved.putInt(
                "seek", player != null && prepared ? player.getCurrentPosition() : seekPosition);
    }

    @Override
    protected void onDestroy() {
        cancellation.set(true);
        generation++;
        exportGeneration++;
        if (contentExport != null && (!exportPickerOpen || isFinishing())) contentExport.discard();
        releasePlayer();
        boolean retain = isChangingConfigurations();
        worker.execute(
                () -> {
                    if (!retain) {
                        if (input != null) input.close();
                        else if (retainedPath != null)
                            try {
                                // Ownership transfers before onCreate; clean an unacquired private
                                // input after cancellation.
                                File file = new File(retainedPath).getCanonicalFile();
                                if (file.getParentFile()
                                                .equals(
                                                        new File(getCacheDir(), "content-previews")
                                                                .getCanonicalFile())
                                        && file.getName()
                                                .matches(
                                                        "analysis-input-[A-Za-z0-9._-]+\\.snapshot"))
                                    file.delete();
                            } catch (IOException ignored) {
                            }
                    }
                });
        worker.shutdown();
        super.onDestroy();
    }
}
