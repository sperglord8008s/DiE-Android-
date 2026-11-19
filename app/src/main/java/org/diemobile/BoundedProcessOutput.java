// SPDX-License-Identifier: GPL-3.0-only
package org.diemobile;

import java.io.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

/**
 * Bounded output capture for an already launched trusted platform command, never sample execution.
 */
final class BoundedProcessOutput {
    static final class Result {
        final byte[] bytes;
        final Integer exitCode;
        final boolean complete, timedOut, capped;
        final String error;

        Result(
                byte[] bytes,
                Integer exitCode,
                boolean complete,
                boolean timedOut,
                boolean capped,
                String error) {
            this.bytes = bytes;
            this.exitCode = exitCode;
            this.complete = complete;
            this.timedOut = timedOut;
            this.capped = capped;
            this.error = error;
        }
    }

    private static final class Collected {
        final ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        boolean eof, capped;
        String error = "";

        synchronized boolean append(byte[] value, int count, int maximum) {
            int accepted = Math.min(count, maximum - bytes.size());
            bytes.write(value, 0, accepted);
            capped = count > accepted;
            return !capped;
        }

        synchronized boolean capped() {
            return capped;
        }

        synchronized boolean failed() {
            return !error.isEmpty();
        }

        synchronized void end(String error) {
            this.error = error;
            eof = error.isEmpty() && !capped;
        }

        synchronized Result result(Integer exit, boolean timeout, String reason) {
            return new Result(
                    bytes.toByteArray(),
                    exit,
                    eof && exit != null && !timeout && reason.isEmpty(),
                    timeout,
                    capped,
                    reason.isEmpty() ? error : reason);
        }
    }

    static Result capture(Process process, int maximum, long timeoutMs, BooleanSupplier stop)
            throws Exception {
        if (maximum < 1 || maximum > 1024 * 1024 || timeoutMs < 1 || timeoutMs > 5000)
            throw new IllegalArgumentException("Invalid platform-command capture budget");
        Collected data = new Collected();
        ExecutorService reader = Executors.newSingleThreadExecutor();
        Future<?> read =
                reader.submit(
                        () -> {
                            try (InputStream input = process.getInputStream()) {
                                byte[] buffer = new byte[4096];
                                int count;
                                while ((count = input.read(buffer)) != -1)
                                    if (!data.append(buffer, count, maximum)) return;
                                data.end("");
                            } catch (IOException failure) {
                                data.end(failure.toString());
                            }
                        });
        long started = System.nanoTime();
        Integer exit = null;
        boolean timeout = false;
        String error = "";
        try {
            while (true) {
                EvidenceRows.check(stop);
                if (data.capped()) {
                    error =
                            "Platform command output exceeds its byte budget; captured bytes are a"
                                    + " prefix";
                    break;
                }
                if (data.failed()) {
                    error = "Platform command output read failed";
                    break;
                }
                if (exit == null && process.waitFor(25, TimeUnit.MILLISECONDS))
                    exit = process.exitValue();
                if (exit != null) {
                    try {
                        read.get(25, TimeUnit.MILLISECONDS);
                        break;
                    } catch (TimeoutException waiting) {
                        /* Overall deadline also bounds pipe draining. */
                    }
                }
                if (System.nanoTime() - started >= TimeUnit.MILLISECONDS.toNanos(timeoutMs)) {
                    timeout = true;
                    error =
                            "Platform command/output capture timed out; captured bytes may be a"
                                    + " prefix";
                    break;
                }
            }
            if (data.capped() && error.isEmpty())
                error =
                        "Platform command output exceeded its byte budget; captured bytes are a"
                            + " prefix";
            if (System.nanoTime() - started >= TimeUnit.MILLISECONDS.toNanos(timeoutMs)) {
                timeout = true;
                if (error.isEmpty())
                    error = "Platform command/output capture exceeded its cooperative deadline";
            }
            return data.result(exit, timeout, error);
        } finally {
            if (process.isAlive()) process.destroyForcibly();
            try {
                process.getInputStream().close();
            } catch (IOException ignored) {
            }
            try {
                process.getErrorStream().close();
            } catch (IOException ignored) {
            }
            try {
                process.getOutputStream().close();
            } catch (IOException ignored) {
            }
            read.cancel(true);
            reader.shutdownNow();
            reader.awaitTermination(1, TimeUnit.SECONDS);
            process.waitFor(500, TimeUnit.MILLISECONDS);
        }
    }

    private BoundedProcessOutput() {}
}
