package com.rkdesign.rkcamera;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Matrix;
import android.media.MediaMetadataRetriever;
import android.net.Uri;

import androidx.media3.effect.MatrixTransformation;

import org.opencv.android.OpenCVLoader;
import org.opencv.android.Utils;
import org.opencv.core.Mat;
import org.opencv.core.MatOfByte;
import org.opencv.core.MatOfFloat;
import org.opencv.core.MatOfPoint;
import org.opencv.core.MatOfPoint2f;
import org.opencv.core.Point;
import org.opencv.imgproc.Imgproc;
import org.opencv.video.Video;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Stabilisasi software setelah rekam: frame diperbesar sedikit (overscan), lalu bagian tengahnya
 * digeser/diputar mengikuti jalur kamera yang sudah dihaluskan. Juga menangani kunci horizon
 * (dari sensor gravitasi) dan zoom software di atas batas zoom hardware.
 *
 * Konvensi: koreksi x/y dalam piksel sampel (x ke kanan, y ke bawah), sudut dalam derajat
 * searah jarum jam. MatrixTransformation Media3 bekerja di koordinat NDC (y ke atas), sehingga
 * y dibalik dan rotasi dibalik saat dibangun jadi matriks.
 */
final class VideoStabilizer {
    static final int OFF = 0, STANDARD = 1, MAX = 2;

    private static final int SAMPLE_MAX_WIDTH = 320;
    private static final int SAMPLE_MAX_HEIGHT = 426;
    static final float HORIZON_LIMIT_DEG = 15f;

    private VideoStabilizer() { }

    static final class Sample {
        final long timeUs;
        final float x, y, angleDeg;
        Sample(long timeUs, float x, float y, float angleDeg) {
            this.timeUs = timeUs; this.x = x; this.y = y; this.angleDeg = angleDeg;
        }
    }

    static final class MotionPath {
        final List<Sample> samples;
        final int width, height;
        MotionPath(List<Sample> samples, int width, int height) {
            this.samples = samples; this.width = width; this.height = height;
        }
    }

    private static final class RawMotion {
        final long timeUs;
        final double x, y, angle;
        RawMotion(long timeUs, double x, double y, double angle) {
            this.timeUs = timeUs; this.x = x; this.y = y; this.angle = angle;
        }
    }

    /** Pembesaran dasar untuk menyisakan ruang koreksi. Mati = tidak ada pemotongan. */
    static float overscan(int mode) {
        if (mode == MAX) return 1.30f;
        if (mode == STANDARD) return 1.17f;
        return 1.0f;
    }

    private static double maxShiftFraction(int mode) { return mode == MAX ? 0.07 : 0.038; }
    private static double maxAngleDeg(int mode) { return mode == MAX ? 3.5 : 1.8; }
    private static double sigmaSeconds(int mode) { return mode == MAX ? 0.55 : 0.30; }
    private static long minIntervalMs(int mode) { return mode == MAX ? 50 : 100; }
    private static int maxSamples(int mode) { return mode == MAX ? 900 : 500; }

    /** Rasio lebar/tinggi frame video setelah rotasi metadata diterapkan. */
    static float frameAspect(Context context, Uri uri) {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(context, uri);
            float w = parseFloat(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
            float h = parseFloat(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
            float rot = parseFloat(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION));
            if (Math.round(rot) == 90 || Math.round(rot) == 270) { float t = w; w = h; h = t; }
            if (w > 0 && h > 0) return w / h;
        } catch (Exception ignored) {
        } finally {
            try { r.release(); } catch (Exception ignored) { }
        }
        return 9f / 16f;
    }

    private static float parseFloat(String s) {
        if (s == null) return 0f;
        try { return Float.parseFloat(s); } catch (NumberFormatException e) { return 0f; }
    }

    /** Analisis gerakan dengan optical flow pada frame yang dicuplik, lalu haluskan jalur kamera. */
    static MotionPath analyze(Context context, Uri source, int mode) throws Exception {
        if (!OpenCVLoader.initLocal()) throw new IllegalStateException("Mesin analisis gambar tidak dapat dimuat");
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        Mat previousGray = null;
        MatOfPoint2f previousPoints = null;
        ArrayList<RawMotion> raw = new ArrayList<>();
        int sampleWidth = SAMPLE_MAX_WIDTH;
        int sampleHeight = SAMPLE_MAX_HEIGHT;
        double cumulativeX = 0, cumulativeY = 0, cumulativeAngle = 0;
        int trackedPairs = 0;
        try {
            retriever.setDataSource(context, source);
            String d = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            long durationMs = d == null ? 0 : Long.parseLong(d);
            if (durationMs < 400) throw new IllegalStateException("Video terlalu pendek untuk dianalisis");
            int limit = maxSamples(mode);
            long intervalMs = Math.max(minIntervalMs(mode), (long) Math.ceil(durationMs / (double) limit));
            int count = 0;
            for (long tMs = 0; tMs <= durationMs && count < limit; tMs += intervalMs) {
                Bitmap frame = retriever.getFrameAtTime(tMs * 1000L, MediaMetadataRetriever.OPTION_CLOSEST);
                if (frame == null) continue;
                int fw = frame.getWidth(), fh = frame.getHeight();
                float factor = Math.min((float) SAMPLE_MAX_WIDTH / fw, (float) SAMPLE_MAX_HEIGHT / fh);
                factor = Math.min(factor, 1f);
                int sw = Math.max(1, Math.round(fw * factor));
                int sh = Math.max(1, Math.round(fh * factor));
                Bitmap scaled = Bitmap.createScaledBitmap(frame, sw, sh, true);
                if (scaled != frame) frame.recycle();
                sampleWidth = sw;
                sampleHeight = sh;

                Mat rgba = new Mat();
                Mat gray = new Mat();
                Utils.bitmapToMat(scaled, rgba);
                Imgproc.cvtColor(rgba, gray, Imgproc.COLOR_RGBA2GRAY);
                rgba.release();
                scaled.recycle();

                if (previousGray != null && previousPoints != null && previousPoints.rows() >= 8) {
                    MatOfPoint2f nextPoints = new MatOfPoint2f();
                    MatOfByte pointStatus = new MatOfByte();
                    MatOfFloat errors = new MatOfFloat();
                    Video.calcOpticalFlowPyrLK(previousGray, gray, previousPoints, nextPoints, pointStatus, errors);
                    Point[] before = previousPoints.toArray();
                    Point[] after = nextPoints.toArray();
                    byte[] good = pointStatus.toArray();
                    ArrayList<Double> xs = new ArrayList<>();
                    ArrayList<Double> ys = new ArrayList<>();
                    double bx = 0, by = 0, ax = 0, ay = 0;
                    int n = Math.min(Math.min(before.length, after.length), good.length);
                    int valid = 0;
                    for (int i = 0; i < n; i++) {
                        if (good[i] == 0) continue;
                        Point p = before[i], q = after[i];
                        if (q.x < 0 || q.y < 0 || q.x >= sw || q.y >= sh) continue;
                        xs.add(q.x - p.x);
                        ys.add(q.y - p.y);
                        bx += p.x; by += p.y; ax += q.x; ay += q.y;
                        valid++;
                    }
                    if (valid >= 6) {
                        trackedPairs++;
                        double dx = median(xs), dy = median(ys);
                        bx /= valid; by /= valid; ax /= valid; ay /= valid;
                        double cross = 0, dot = 0;
                        for (int i = 0; i < n; i++) {
                            if (good[i] == 0) continue;
                            Point p = before[i], q = after[i];
                            if (q.x < 0 || q.y < 0 || q.x >= sw || q.y >= sh) continue;
                            double px = p.x - bx, py = p.y - by;
                            double qx = q.x - ax, qy = q.y - ay;
                            cross += px * qy - py * qx;
                            dot += px * qx + py * qy;
                        }
                        double angle = Math.atan2(cross, dot);
                        if (Math.abs(dx) < sw * 0.20 && Math.abs(dy) < sh * 0.20 && Math.abs(angle) < Math.toRadians(12)) {
                            cumulativeX += dx;
                            cumulativeY += dy;
                            cumulativeAngle += angle;
                        }
                    }
                    nextPoints.release(); pointStatus.release(); errors.release();
                }
                raw.add(new RawMotion(tMs * 1000L, cumulativeX, cumulativeY, cumulativeAngle));
                count++;

                MatOfPoint corners = new MatOfPoint();
                Imgproc.goodFeaturesToTrack(gray, corners, 220, 0.010, 4.5);
                MatOfPoint2f freshPoints = new MatOfPoint2f();
                freshPoints.fromArray(corners.toArray());
                corners.release();
                if (previousPoints != null) previousPoints.release();
                previousPoints = freshPoints;
                if (previousGray != null) previousGray.release();
                previousGray = gray;
            }
        } finally {
            if (previousGray != null) previousGray.release();
            if (previousPoints != null) previousPoints.release();
            try { retriever.release(); } catch (Exception ignored) { }
        }
        if (raw.size() < 3 || trackedPairs < 2) throw new IllegalStateException("Tidak cukup detail untuk menganalisis gerakan video");

        // Haluskan dengan bobot Gaussian berdasarkan waktu, lalu koreksi = jalur halus - jalur asli.
        double sigma = sigmaSeconds(mode) * 1_000_000.0;
        double shiftX = sampleWidth * maxShiftFraction(mode);
        double shiftY = sampleHeight * maxShiftFraction(mode);
        double angleMax = Math.toRadians(maxAngleDeg(mode));
        ArrayList<Sample> out = new ArrayList<>();
        int n = raw.size();
        for (int i = 0; i < n; i++) {
            RawMotion cur = raw.get(i);
            double wsum = 0, sx = 0, sy = 0, sa = 0;
            for (int j = i; j >= 0; j--) {
                double dt = cur.timeUs - raw.get(j).timeUs;
                if (dt > 3 * sigma) break;
                double w = Math.exp(-0.5 * (dt / sigma) * (dt / sigma));
                RawMotion r = raw.get(j);
                wsum += w; sx += w * r.x; sy += w * r.y; sa += w * r.angle;
            }
            for (int j = i + 1; j < n; j++) {
                double dt = raw.get(j).timeUs - cur.timeUs;
                if (dt > 3 * sigma) break;
                double w = Math.exp(-0.5 * (dt / sigma) * (dt / sigma));
                RawMotion r = raw.get(j);
                wsum += w; sx += w * r.x; sy += w * r.y; sa += w * r.angle;
            }
            double cx = clamp(sx / wsum - cur.x, -shiftX, shiftX);
            double cy = clamp(sy / wsum - cur.y, -shiftY, shiftY);
            double ca = clamp(sa / wsum - cur.angle, -angleMax, angleMax);
            out.add(new Sample(cur.timeUs, (float) cx, (float) cy, (float) Math.toDegrees(ca)));
        }
        return new MotionPath(out, sampleWidth, sampleHeight);
    }

    private static double median(List<Double> values) {
        if (values.isEmpty()) return 0;
        Collections.sort(values);
        int middle = values.size() / 2;
        if (values.size() % 2 == 1) return values.get(middle);
        return (values.get(middle - 1) + values.get(middle)) / 2.0;
    }

    private static double clamp(double value, double min, double max) { return Math.max(min, Math.min(max, value)); }

    private static Sample interpolate(List<Sample> path, long timeUs) {
        int hi = path.size() - 1;
        if (timeUs <= path.get(0).timeUs) return path.get(0);
        if (timeUs >= path.get(hi).timeUs) return path.get(hi);
        int lo = 0;
        while (hi - lo > 1) {
            int mid = (lo + hi) >>> 1;
            if (path.get(mid).timeUs <= timeUs) lo = mid; else hi = mid;
        }
        Sample a = path.get(lo), b = path.get(hi);
        long span = Math.max(1, b.timeUs - a.timeUs);
        float f = Math.max(0f, Math.min(1f, (timeUs - a.timeUs) / (float) span));
        return new Sample(timeUs, a.x + (b.x - a.x) * f, a.y + (b.y - a.y) * f, a.angleDeg + (b.angleDeg - a.angleDeg) * f);
    }

    /** Interpolasi linear untuk deret {waktuUs, nilai} yang terurut menurut waktu. */
    static double interpolate(List<double[]> pts, long timeUs, double fallback) {
        if (pts == null || pts.isEmpty()) return fallback;
        int hi = pts.size() - 1;
        if (timeUs <= pts.get(0)[0]) return pts.get(0)[1];
        if (timeUs >= pts.get(hi)[0]) return pts.get(hi)[1];
        int lo = 0;
        while (hi - lo > 1) {
            int mid = (lo + hi) >>> 1;
            if (pts.get(mid)[0] <= timeUs) lo = mid; else hi = mid;
        }
        double[] a = pts.get(lo), b = pts.get(hi);
        double span = Math.max(1.0, b[0] - a[0]);
        double f = (timeUs - a[0]) / span;
        return a[1] + (b[1] - a[1]) * f;
    }

    /** Skala minimum agar frame yang diputar sebesar thetaDeg tetap menutupi seluruh layar. */
    private static float coverScale(float thetaDeg, float aspect) {
        double t = Math.toRadians(Math.abs(thetaDeg));
        double c = Math.cos(t), s = Math.sin(t);
        double a = Math.max(0.1, aspect);
        return (float) Math.max(c + s / a, c + s * a);
    }

    /**
     * Matriks per frame: zoom software x overscan, putar (kunci horizon + koreksi goyang), lalu geser.
     *
     * @param path         jalur gerak hasil analisis (null bila stabilizer mati)
     * @param rollTimeline {waktuUs, derajat miring HP searah jarum jam}; null bila kunci horizon mati
     * @param zoomTimeline {waktuUs, zoom software}; null bila tidak ada zoom software
     * @param aspect       lebar/tinggi frame keluaran
     */
    static MatrixTransformation transformation(final MotionPath path, final int mode,
                                               final List<double[]> rollTimeline,
                                               final List<double[]> zoomTimeline,
                                               final float aspect) {
        final float overscan = overscan(mode);
        final boolean stabilize = path != null && mode != OFF && !path.samples.isEmpty();
        final float a = Math.max(0.1f, aspect);
        return presentationTimeUs -> {
            float cx = 0f, cy = 0f, ca = 0f;
            if (stabilize) {
                Sample m = interpolate(path.samples, presentationTimeUs);
                cx = m.x; cy = m.y; ca = m.angleDeg;
            }
            float roll = 0f;
            if (rollTimeline != null && !rollTimeline.isEmpty()) {
                roll = (float) interpolate(rollTimeline, presentationTimeUs, 0.0);
                roll = Math.max(-HORIZON_LIMIT_DEG, Math.min(HORIZON_LIMIT_DEG, roll));
            }
            float zoom = (float) Math.max(1.0, interpolate(zoomTimeline, presentationTimeUs, 1.0));
            float horizonScale = Math.abs(roll) < 0.05f ? 1f : coverScale(roll, a);
            float s = overscan * horizonScale * zoom;

            Matrix matrix = new Matrix();
            matrix.postScale(s, s);
            float rotateClockwise = roll + ca;
            if (Math.abs(rotateClockwise) > 0.001f) {
                // Putar di ruang piksel (bukan NDC) supaya gambar tidak melar; postRotate positif
                // di NDC (y ke atas) berputar berlawanan arah jarum jam, maka tandanya dibalik.
                matrix.postScale(a, 1f);
                matrix.postRotate(-rotateClockwise);
                matrix.postScale(1f / a, 1f);
            }
            if (stabilize) {
                float tx = s * 2f * cx / (float) path.width;
                float ty = -s * 2f * cy / (float) path.height;
                matrix.postTranslate(tx, ty);
            }
            return matrix;
        };
    }
}
