package com.rkdesign.rkcamera;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.view.View;

/** Ikon garis sederhana bergaya iOS, digambar langsung (tanpa file gambar). */
final class IconView extends View {
    static final int FLASH = 0, FLASH_AUTO = 1, FLASH_OFF = 2, FLIP = 3, SLIDERS = 4;

    private int icon;
    private int color = Color.WHITE;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final RectF rect = new RectF();

    IconView(Context context, int icon) {
        super(context);
        this.icon = icon;
        fill.setStyle(Paint.Style.FILL);
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        stroke.setStrokeJoin(Paint.Join.ROUND);
    }

    void setIcon(int icon) { this.icon = icon; invalidate(); }
    void setColor(int color) { this.color = color; invalidate(); }

    @Override protected void onDraw(Canvas canvas) {
        float availW = getWidth() - getPaddingLeft() - getPaddingRight();
        float availH = getHeight() - getPaddingTop() - getPaddingBottom();
        float side = Math.min(availW, availH);
        if (side <= 0) return;
        canvas.save();
        canvas.translate(getPaddingLeft() + (availW - side) / 2f, getPaddingTop() + (availH - side) / 2f);
        float u = side / 24f;
        canvas.scale(u, u);
        fill.setColor(color);
        stroke.setColor(color);
        stroke.setStrokeWidth(1.9f);
        switch (icon) {
            case FLASH:
            case FLASH_AUTO:
            case FLASH_OFF:
                drawBolt(canvas);
                if (icon == FLASH_OFF) {
                    canvas.drawLine(4f, 3.5f, 20f, 21f, stroke);
                } else if (icon == FLASH_AUTO) {
                    fill.setTypeface(Typeface.DEFAULT_BOLD);
                    fill.setTextSize(9.5f);
                    canvas.drawText("A", 16.2f, 22.5f, fill);
                }
                break;
            case FLIP:
                drawFlip(canvas);
                break;
            case SLIDERS:
                drawSliders(canvas);
                break;
            default:
                break;
        }
        canvas.restore();
    }

    private void drawBolt(Canvas canvas) {
        path.reset();
        path.moveTo(13.5f, 2f);
        path.lineTo(5f, 13.5f);
        path.lineTo(11f, 13.5f);
        path.lineTo(10f, 22f);
        path.lineTo(19f, 10f);
        path.lineTo(13f, 10f);
        path.close();
        canvas.drawPath(path, fill);
    }

    private void drawFlip(Canvas canvas) {
        rect.set(5f, 5f, 19f, 19f);
        canvas.drawArc(rect, 200f, 125f, false, stroke);   // busur atas, searah jarum jam
        canvas.drawArc(rect, 20f, 125f, false, stroke);    // busur bawah
        arrowHead(canvas, 325f);
        arrowHead(canvas, 145f);
    }

    private void arrowHead(Canvas canvas, float angleDeg) {
        double a = Math.toRadians(angleDeg);
        float cx = 12f + 7f * (float) Math.cos(a);
        float cy = 12f + 7f * (float) Math.sin(a);
        float tx = (float) -Math.sin(a), ty = (float) Math.cos(a);   // arah singgung (searah jarum jam)
        float nx = (float) Math.cos(a), ny = (float) Math.sin(a);    // arah radial
        path.reset();
        path.moveTo(cx + tx * 3.2f, cy + ty * 3.2f);
        path.lineTo(cx + nx * 2.4f, cy + ny * 2.4f);
        path.lineTo(cx - nx * 2.4f, cy - ny * 2.4f);
        path.close();
        canvas.drawPath(path, fill);
    }

    private void drawSliders(Canvas canvas) {
        float[] ys = {6f, 12f, 18f};
        float[] knobs = {8f, 16f, 10f};
        for (int i = 0; i < 3; i++) {
            float y = ys[i], k = knobs[i];
            canvas.drawLine(3f, y, k - 3.2f, y, stroke);
            canvas.drawLine(k + 3.2f, y, 21f, y, stroke);
            canvas.drawCircle(k, y, 2.4f, stroke);
        }
    }
}
