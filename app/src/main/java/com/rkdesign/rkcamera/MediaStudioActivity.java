package com.rkdesign.rkcamera;

import android.Manifest;
import android.app.Activity;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Matrix;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Size;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.GridLayout;
import android.widget.HorizontalScrollView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.MediaController;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.media3.common.MediaItem;
import androidx.media3.common.Effect;
import androidx.media3.effect.SingleColorLut;
import androidx.media3.transformer.Composition;
import androidx.media3.transformer.EditedMediaItem;
import androidx.media3.transformer.ExportException;
import androidx.media3.transformer.ExportResult;
import androidx.media3.transformer.Transformer;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.segmentation.Segmentation;
import com.google.mlkit.vision.segmentation.SegmentationMask;
import com.google.mlkit.vision.segmentation.Segmenter;
import com.google.mlkit.vision.segmentation.selfie.SelfieSegmenterOptions;

import org.opencv.android.OpenCVLoader;
import org.opencv.android.Utils;
import org.opencv.core.Core;
import org.opencv.core.Mat;
import org.opencv.core.Point;
import org.opencv.core.Scalar;
import org.opencv.imgproc.CLAHE;
import org.opencv.imgproc.Imgproc;
import org.opencv.photo.Photo;

import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Local gallery and editor. AI portrait segmentation runs on-device; media stays on device. */
public class MediaStudioActivity extends AppCompatActivity {
    private static final int BG = Color.rgb(12, 12, 14);
    private static final int PANEL = Color.rgb(29, 29, 32);
    private static final int ACCENT = Color.rgb(255, 214, 10);
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private final ArrayList<MediaItem> media = new ArrayList<>();
    private LinearLayout page;
    private TextView status;
    private GridLayout grid;
    private ImageView imageView;
    private VideoView videoView;
    private Bitmap currentBitmap;
    private Uri selectedUri;
    private boolean selectedVideo;
    private boolean editing;
    private boolean busy;
    private boolean permissionResolved = false;
    private float pickedX = -1, pickedY = -1;
    private Transformer transformer;

    private static final class MediaItem {
        final Uri uri; final boolean video; final long date; final String name;
        MediaItem(Uri u, boolean v, long d, String n) { uri=u; video=v; date=d; name=n; }
    }

    private final ActivityResultLauncher<Intent> picker = registerForActivityResult(
            new ActivityResultContracts.StartActivityForResult(), result -> {
                if (result.getResultCode() == Activity.RESULT_OK && result.getData() != null && result.getData().getData() != null) {
                    Uri u = result.getData().getData();
                    try { getContentResolver().takePersistableUriPermission(u, Intent.FLAG_GRANT_READ_URI_PERMISSION); } catch (Exception ignored) { }
                    openItem(new MediaItem(u, isVideoUri(u), System.currentTimeMillis()/1000, "Media pilihan"));
                }
            });

    private final ActivityResultLauncher<String[]> permissionRequest = registerForActivityResult(
            new ActivityResultContracts.RequestMultiplePermissions(), result -> { permissionResolved = true; loadGallery(); });

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        getWindow().setStatusBarColor(Color.BLACK);
        getWindow().setNavigationBarColor(Color.BLACK);
        loadGallery();
    }

    @Override public void onBackPressed() {
        if (editing) { showGallery(); return; }
        super.onBackPressed();
    }

    private void loadGallery() {
        if (!permissionResolved) {
            permissionResolved = true;
            // This app currently targets API 28: READ_EXTERNAL_STORAGE remains its broad-media permission.
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
                permissionResolved = false;
                showGallery();
                permissionRequest.launch(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE});
                return;
            }
        }
        showGallery();
        worker.execute(() -> {
            ArrayList<MediaItem> found = new ArrayList<>();
            queryMedia(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, false, found);
            queryMedia(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, true, found);
            Collections.sort(found, (a,b) -> Long.compare(b.date, a.date));
            runOnUiThread(() -> {
                if (editing || grid == null) return;
                media.clear(); media.addAll(found); grid.removeAllViews();
                int screenW = getResources().getDisplayMetrics().widthPixels;
                int cell = Math.max(dp(88), screenW / 3 - dp(6));
                for (MediaItem item : media) addGridCell(item, cell);
                setStatus(media.isEmpty() ? "Belum ada media yang dapat ditampilkan. Ketuk + untuk memilih dari perangkat." : media.size() + " foto dan video");
            });
        });
    }

    private void queryMedia(Uri collection, boolean video, List<MediaItem> out) {
        String[] projection = {MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DATE_ADDED, MediaStore.MediaColumns.DISPLAY_NAME};
        try (Cursor c = getContentResolver().query(collection, projection, null, null, MediaStore.MediaColumns.DATE_ADDED + " DESC")) {
            if (c == null) return;
            int idCol=c.getColumnIndex(MediaStore.MediaColumns._ID), dateCol=c.getColumnIndex(MediaStore.MediaColumns.DATE_ADDED), nameCol=c.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME);
            while (c.moveToNext()) {
                long id=c.getLong(idCol), date=c.getLong(dateCol); String name=nameCol>=0?c.getString(nameCol):"Media";
                out.add(new MediaItem(ContentUris.withAppendedId(collection, id), video, date, name));
            }
        } catch (SecurityException ignored) { }
        catch (Exception ignored) { }
    }

    private void showGallery() {
        editing=false; selectedUri=null; currentBitmap=null; pickedX=pickedY=-1;
        page = new LinearLayout(this); page.setOrientation(LinearLayout.VERTICAL); page.setBackgroundColor(BG);
        LinearLayout header = new LinearLayout(this); header.setGravity(Gravity.CENTER_VERTICAL); header.setPadding(dp(16),dp(10),dp(12),dp(10));
        TextView title = txt("RK STUDIO", 20, Color.WHITE, true); header.addView(title, new LinearLayout.LayoutParams(0,dp(48),1));
        TextView add = button("＋ PILIH MEDIA", () -> chooseMedia()); header.addView(add, new LinearLayout.LayoutParams(-2,dp(42)));
        page.addView(header);
        status=txt("Membaca galeri…",12,Color.LTGRAY,false); status.setPadding(dp(16),dp(4),dp(16),dp(8)); page.addView(status);
        ScrollView scroll = new ScrollView(this); grid = new GridLayout(this); grid.setColumnCount(3); grid.setPadding(dp(3),dp(3),dp(3),dp(16)); scroll.addView(grid);
        page.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        TextView foot=txt("Foto dan video tetap tersimpan di perangkat. Hasil edit disimpan sebagai salinan.",11,Color.GRAY,false); foot.setPadding(dp(14),dp(8),dp(14),dp(12)); page.addView(foot);
        setContentView(page);
    }

    private void addGridCell(MediaItem item, int cell) {
        FrameLayout box=new FrameLayout(this); box.setBackgroundColor(PANEL);
        GridLayout.LayoutParams lp=new GridLayout.LayoutParams(); lp.width=cell; lp.height=cell; lp.setMargins(dp(2),dp(2),dp(2),dp(2)); box.setLayoutParams(lp);
        ImageView image=new ImageView(this); image.setScaleType(ImageView.ScaleType.CENTER_CROP); box.addView(image,new FrameLayout.LayoutParams(-1,-1));
        if(item.video){ TextView badge=txt("▶ VIDEO",10,Color.WHITE,true); badge.setBackgroundColor(0x99000000); badge.setPadding(dp(5),dp(3),dp(5),dp(3)); box.addView(badge,new FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM|Gravity.START)); }
        box.setOnClickListener(v -> openItem(item)); grid.addView(box);
        worker.execute(() -> {
            Bitmap bmp=thumbnail(item);
            if(bmp!=null) runOnUiThread(() -> { if(image.getParent()!=null) image.setImageBitmap(bmp); });
        });
    }

    private Bitmap thumbnail(MediaItem item) {
        try {
            if(Build.VERSION.SDK_INT>=29) return getContentResolver().loadThumbnail(item.uri,new Size(360,360),null);
            long id=ContentUris.parseId(item.uri);
            return item.video ? MediaStore.Video.Thumbnails.getThumbnail(getContentResolver(),id,MediaStore.Video.Thumbnails.MICRO_KIND,null)
                    : MediaStore.Images.Thumbnails.getThumbnail(getContentResolver(),id,MediaStore.Images.Thumbnails.MINI_KIND,null);
        } catch(Exception ignored) { return null; }
    }

    private void chooseMedia() {
        Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT); i.addCategory(Intent.CATEGORY_OPENABLE); i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"image/*","video/*"}); i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        picker.launch(i);
    }

    private boolean isVideoUri(Uri uri) {
        String type=getContentResolver().getType(uri); return type!=null && type.startsWith("video/");
    }

    private void openItem(MediaItem item) {
        editing=true; selectedUri=item.uri; selectedVideo=item.video; pickedX=pickedY=-1;
        page=new LinearLayout(this); page.setOrientation(LinearLayout.VERTICAL); page.setBackgroundColor(BG);
        LinearLayout header=new LinearLayout(this); header.setGravity(Gravity.CENTER_VERTICAL); header.setPadding(dp(12),dp(6),dp(12),dp(6));
        TextView back=button("‹ GALERI",this::showGallery); header.addView(back,new LinearLayout.LayoutParams(-2,dp(44)));
        TextView title=txt(item.video?"EDITOR VIDEO":"EDITOR FOTO",16,Color.WHITE,true); title.setGravity(Gravity.CENTER); header.addView(title,new LinearLayout.LayoutParams(0,dp(44),1));
        TextView importBtn=button("＋",this::chooseMedia); header.addView(importBtn,new LinearLayout.LayoutParams(dp(48),dp(44))); page.addView(header);
        FrameLayout stage=new FrameLayout(this); stage.setBackgroundColor(Color.BLACK); page.addView(stage,new LinearLayout.LayoutParams(-1,0,1));
        if(selectedVideo) {
            videoView=new VideoView(this); MediaController controller=new MediaController(this); controller.setAnchorView(videoView); videoView.setMediaController(controller); videoView.setVideoURI(item.uri); stage.addView(videoView,new FrameLayout.LayoutParams(-1,-1));
            videoView.setOnPreparedListener(mp -> { mp.setLooping(true); videoView.start(); }); imageView=null; currentBitmap=null;
        } else {
            videoView=null; imageView=new ImageView(this); imageView.setScaleType(ImageView.ScaleType.FIT_CENTER); imageView.setBackgroundColor(Color.BLACK); stage.addView(imageView,new FrameLayout.LayoutParams(-1,-1));
            imageView.setOnTouchListener((v,e)-> {
                if(e.getAction()==MotionEvent.ACTION_UP && currentBitmap!=null) {
                    float scale=Math.min((float)imageView.getWidth()/currentBitmap.getWidth(),(float)imageView.getHeight()/currentBitmap.getHeight());
                    float left=(imageView.getWidth()-currentBitmap.getWidth()*scale)/2f, top=(imageView.getHeight()-currentBitmap.getHeight()*scale)/2f;
                    float x=(e.getX()-left)/scale, y=(e.getY()-top)/scale;
                    if(x>=0&&y>=0&&x<currentBitmap.getWidth()&&y<currentBitmap.getHeight()) { pickedX=x; pickedY=y; setStatus("Titik objek dipilih. Ketuk Hapus Objek untuk memprosesnya."); }
                }
                return true;
            });
            setStatus("Membuka foto…");
            worker.execute(() -> { Bitmap b=decodeBitmap(item.uri,2600); runOnUiThread(()-> { if(editing && imageView!=null && selectedUri.equals(item.uri)) { currentBitmap=b; imageView.setImageBitmap(b); setStatus("Foto siap diedit. Tekan titik objek untuk alat hapus."); } }); });
        }
        status=txt("",11,Color.LTGRAY,false); status.setPadding(dp(14),dp(4),dp(14),dp(4)); page.addView(status);
        HorizontalScrollView toolsScroll=new HorizontalScrollView(this); toolsScroll.setHorizontalScrollBarEnabled(false); LinearLayout tools=new LinearLayout(this); tools.setPadding(dp(8),dp(8),dp(8),dp(12)); tools.setOrientation(LinearLayout.HORIZONTAL); toolsScroll.addView(tools); page.addView(toolsScroll);
        if(selectedVideo) {
            addTool(tools,"▶ / ❚❚",()-> { if(videoView.isPlaying())videoView.pause();else videoView.start(); });
            addTool(tools,"STABILIZER",()->chooseVideoStabilizer());
            addTool(tools,"CINEMA WARNA",()->renderVideo(VideoStabilizer.OFF,IPhoneLook.ALL[12]));
            addTool(tools,"NATURAL",()->renderVideo(VideoStabilizer.OFF,IPhoneLook.ALL[8]));
        } else {
            addTool(tools,"AI POTRET",this::applyPortraitBlur);
            addTool(tools,"MALAM PRO",this::applyNightEnhancement);
            addTool(tools,"FILTER",this::chooseFilter);
            addTool(tools,"HAPUS OBJEK",this::removeSelectedObject);
            addTool(tools,"PUTAR 90°",this::rotateImage);
            addTool(tools,"SIMPAN SALINAN",this::saveEditedImage);
        }
        setContentView(page);
    }

    private Bitmap decodeBitmap(Uri uri,int maxDim) {
        try {
            BitmapFactory.Options bounds=new BitmapFactory.Options(); bounds.inJustDecodeBounds=true;
            try(InputStream in=getContentResolver().openInputStream(uri)){BitmapFactory.decodeStream(in,null,bounds);}
            int sample=1; while(Math.max(bounds.outWidth/sample,bounds.outHeight/sample)>maxDim) sample*=2;
            BitmapFactory.Options opts=new BitmapFactory.Options(); opts.inSampleSize=sample; opts.inPreferredConfig=Bitmap.Config.ARGB_8888;
            try(InputStream in=getContentResolver().openInputStream(uri)){return in==null?null:BitmapFactory.decodeStream(in,null,opts);}
        } catch(Exception|OutOfMemoryError ignored){return null;}
    }

    private void applyPortraitBlur() {
        if(!imageReady())return; setBusy(true,"AI memisahkan orang dari latar belakang…");
        Bitmap input=currentBitmap;
        SelfieSegmenterOptions opts=new SelfieSegmenterOptions.Builder().setDetectorMode(SelfieSegmenterOptions.SINGLE_IMAGE_MODE).build();
        Segmenter segmenter=Segmentation.getClient(opts);
        segmenter.process(InputImage.fromBitmap(input,0)).addOnSuccessListener(mask -> worker.execute(() -> {
            Bitmap output=null;
            try { output=blendBlurBackground(input,mask); } catch(Exception|OutOfMemoryError ignored) { }
            finally { try{segmenter.close();}catch(Exception ignored){} }
            Bitmap result=output;
            runOnUiThread(()-> { setBusy(false,result==null?"AI Potret gagal pada foto ini; foto asli tidak berubah.":"AI Potret selesai. Simpan salinan untuk menyimpan hasil."); if(result!=null){currentBitmap=result; if(imageView!=null)imageView.setImageBitmap(result);} });
        })).addOnFailureListener(err -> { try{segmenter.close();}catch(Exception ignored){} setBusy(false,"Model AI tidak dapat memproses foto ini."); });
    }

    private Bitmap blendBlurBackground(Bitmap input, SegmentationMask mask) {
        int w=input.getWidth(),h=input.getHeight(); Bitmap src=input;
        float max=Math.max(w,h); if(max>1800){float k=1800f/max; src=Bitmap.createScaledBitmap(input,Math.max(1,Math.round(w*k)),Math.max(1,Math.round(h*k)),true); w=src.getWidth();h=src.getHeight();}
        ByteBuffer maskBuffer=mask.getBuffer().order(ByteOrder.nativeOrder()); int mw=mask.getWidth(),mh=mask.getHeight();
        Bitmap blur=gaussianBlur(src,21f); int[] fg=new int[w*h], bg=new int[w*h], out=new int[w*h]; src.getPixels(fg,0,w,0,0,w,h); blur.getPixels(bg,0,w,0,0,w,h);
        for(int y=0;y<h;y++){int my=Math.min(mh-1,(int)((long)y*mh/h)); for(int x=0;x<w;x++){int mx=Math.min(mw-1,(int)((long)x*mw/w)); int at=my*mw+mx; float c=at*4+4<=maskBuffer.limit()?maskBuffer.getFloat(at*4):0f; float a=Math.max(0,Math.min(1,(c-0.25f)/0.5f)); int p=fg[y*w+x],q=bg[y*w+x]; int r=Math.round(Color.red(q)*(1-a)+Color.red(p)*a),g=Math.round(Color.green(q)*(1-a)+Color.green(p)*a),b=Math.round(Color.blue(q)*(1-a)+Color.blue(p)*a);out[y*w+x]=Color.rgb(r,g,b);}}
        Bitmap result=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);result.setPixels(out,0,w,0,0,w,h); if(blur!=src)blur.recycle();if(src!=input)src.recycle();return result;
    }

    private Bitmap gaussianBlur(Bitmap bitmap,float radius) {
        if(OpenCVLoader.initLocal()){
            Mat src=new Mat(),dst=new Mat(); try{Utils.bitmapToMat(bitmap,src); Imgproc.GaussianBlur(src,dst,new org.opencv.core.Size(0,0),radius/3.0); Bitmap b=Bitmap.createBitmap(bitmap.getWidth(),bitmap.getHeight(),Bitmap.Config.ARGB_8888);Utils.matToBitmap(dst,b);return b;}catch(Exception ignored){}finally{src.release();dst.release();}
        }
        int w=Math.max(1,bitmap.getWidth()/12),h=Math.max(1,bitmap.getHeight()/12); Bitmap small=Bitmap.createScaledBitmap(bitmap,w,h,true); Bitmap b=Bitmap.createScaledBitmap(small,bitmap.getWidth(),bitmap.getHeight(),true); if(small!=bitmap)small.recycle();return b;
    }

    private void applyNightEnhancement() {
        if(!imageReady())return; setBusy(true,"AI Malam: mengurangi noise dan mengangkat bayangan…"); Bitmap src=currentBitmap;
        worker.execute(()->{
            Bitmap out=null;
            try {
                if(OpenCVLoader.initLocal()){
                    Mat rgba=new Mat(),rgb=new Mat(),lab=new Mat(),dstRgb=new Mat(),dstRgba=new Mat();
                    try {
                        Utils.bitmapToMat(src,rgba); Imgproc.cvtColor(rgba,rgb,Imgproc.COLOR_RGBA2RGB); Imgproc.cvtColor(rgb,lab,Imgproc.COLOR_RGB2Lab);
                        List<Mat> ch=new ArrayList<>();Core.split(lab,ch); CLAHE clahe=Imgproc.createCLAHE(2.0,new org.opencv.core.Size(8,8)); clahe.apply(ch.get(0),ch.get(0));Core.merge(ch,lab);
                        Imgproc.cvtColor(lab,dstRgb,Imgproc.COLOR_Lab2RGB); Imgproc.bilateralFilter(dstRgb,dstRgb,7,45,45); Imgproc.cvtColor(dstRgb,dstRgba,Imgproc.COLOR_RGB2RGBA);
                        out=Bitmap.createBitmap(src.getWidth(),src.getHeight(),Bitmap.Config.ARGB_8888);Utils.matToBitmap(dstRgba,out);
                    }finally{rgba.release();rgb.release();lab.release();dstRgb.release();dstRgba.release();}
                } else {
                    int w=src.getWidth(),h=src.getHeight();int[] px=new int[w*h];src.getPixels(px,0,w,0,0,w,h);for(int i=0;i<px.length;i++){int p=px[i];double gamma=0.78;int r=(int)(255*Math.pow(Color.red(p)/255.0,gamma)),g=(int)(255*Math.pow(Color.green(p)/255.0,gamma)),b=(int)(255*Math.pow(Color.blue(p)/255.0,gamma));px[i]=Color.rgb(Math.min(255,r),Math.min(255,g),Math.min(255,b));}out=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);out.setPixels(px,0,w,0,0,w,h);
                }
            }catch(Exception|OutOfMemoryError ignored){}
            Bitmap result=out;runOnUiThread(()->{setBusy(false,result==null?"Pemrosesan malam gagal; foto asli tetap aman.":"Pemrosesan malam selesai. Simpan salinan untuk menyimpan hasil.");if(result!=null){currentBitmap=result;if(imageView!=null)imageView.setImageBitmap(result);}});
        });
    }

    private void chooseFilter() {
        String[] names=new String[IPhoneLook.ALL.length];for(int i=0;i<names.length;i++)names[i]=IPhoneLook.ALL[i].name;
        new android.app.AlertDialog.Builder(this).setTitle("Pilih filter warna").setItems(names,(d,which)->applyFilter(IPhoneLook.ALL[which])).setNegativeButton("Batal",null).show();
    }
    private void applyFilter(IPhoneLook look) {
        if(!imageReady())return; setBusy(true,"Menerapkan filter "+look.name+"…"); Bitmap src=currentBitmap;
        worker.execute(()->{Bitmap out=null;try{int w=src.getWidth(),h=src.getHeight();int[] px=new int[w*h];src.getPixels(px,0,w,0,0,w,h);float[] rgb=new float[3];for(int i=0;i<px.length;i++){int p=px[i];look.apply(Color.red(p)/255f,Color.green(p)/255f,Color.blue(p)/255f,rgb);px[i]=Color.argb(Color.alpha(p),Math.round(rgb[0]*255),Math.round(rgb[1]*255),Math.round(rgb[2]*255));}out=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);out.setPixels(px,0,w,0,0,w,h);}catch(OutOfMemoryError ignored){} Bitmap result=out;runOnUiThread(()->{setBusy(false,result==null?"Filter gagal diterapkan.":"Filter "+look.name+" selesai.");if(result!=null){currentBitmap=result;if(imageView!=null)imageView.setImageBitmap(result);}});});
    }

    private void removeSelectedObject() {
        if(!imageReady())return;if(pickedX<0||pickedY<0){setStatus("Ketuk bagian tengah objek yang ingin dihapus terlebih dahulu.");return;}
        if(!OpenCVLoader.initLocal()){setStatus("Mesin inpaint OpenCV tidak tersedia pada build ini.");return;}
        setBusy(true,"Menghapus area terpilih dengan inpainting…");Bitmap src=currentBitmap;float x=pickedX,y=pickedY;
        worker.execute(()->{Bitmap out=null;Mat rgba=new Mat(),rgb=new Mat(),bgr=new Mat(),mask=new Mat(),fixed=new Mat(),fixedRgb=new Mat(),fixedRgba=new Mat();try{Utils.bitmapToMat(src,rgba);Imgproc.cvtColor(rgba,rgb,Imgproc.COLOR_RGBA2RGB);Imgproc.cvtColor(rgb,bgr,Imgproc.COLOR_RGB2BGR);mask=Mat.zeros(src.getHeight(),src.getWidth(),org.opencv.core.CvType.CV_8UC1);int radius=Math.max(12,Math.round(Math.min(src.getWidth(),src.getHeight())*0.045f));Imgproc.circle(mask,new Point(x,y),radius,new Scalar(255),-1);Photo.inpaint(bgr,mask,fixed,Math.max(3,radius*0.12),Photo.INPAINT_TELEA);Imgproc.cvtColor(fixed,fixedRgb,Imgproc.COLOR_BGR2RGB);Imgproc.cvtColor(fixedRgb,fixedRgba,Imgproc.COLOR_RGB2RGBA);out=Bitmap.createBitmap(src.getWidth(),src.getHeight(),Bitmap.Config.ARGB_8888);Utils.matToBitmap(fixedRgba,out);}catch(Exception|OutOfMemoryError ignored){}finally{rgba.release();rgb.release();bgr.release();mask.release();fixed.release();fixedRgb.release();fixedRgba.release();}Bitmap result=out;runOnUiThread(()->{setBusy(false,result==null?"Penghapusan area gagal. Foto asli tidak berubah.":"Area diproses. Untuk objek besar, ulangi dengan beberapa titik.");if(result!=null){currentBitmap=result;if(imageView!=null)imageView.setImageBitmap(result);pickedX=pickedY=-1;}});});
    }

    private void rotateImage() {
        if(!imageReady())return;Matrix m=new Matrix();m.postRotate(90);Bitmap result=Bitmap.createBitmap(currentBitmap,0,0,currentBitmap.getWidth(),currentBitmap.getHeight(),m,true);currentBitmap=result;imageView.setImageBitmap(result);setStatus("Foto diputar 90°. Simpan salinan untuk menyimpan hasil.");
    }

    private void saveEditedImage() {
        if(!imageReady()||busy)return;setBusy(true,"Menyimpan salinan foto…");Bitmap b=currentBitmap;
        worker.execute(()->{Uri uri=null;try{String stamp=new SimpleDateFormat("yyyyMMdd_HHmmss",Locale.US).format(new Date());ContentValues values=new ContentValues();values.put(MediaStore.MediaColumns.DISPLAY_NAME,"RKStudio_"+stamp+".jpg");values.put(MediaStore.MediaColumns.MIME_TYPE,"image/jpeg");if(Build.VERSION.SDK_INT>=29){values.put(MediaStore.MediaColumns.RELATIVE_PATH,Environment.DIRECTORY_DCIM+"/RK Camera AI");values.put(MediaStore.MediaColumns.IS_PENDING,1);}else{File dir=new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM),"RK Camera AI");if(!dir.exists())dir.mkdirs();values.put(MediaStore.MediaColumns.DATA,new File(dir,"RKStudio_"+stamp+".jpg").getAbsolutePath());}uri=getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values);if(uri!=null){try(OutputStream os=getContentResolver().openOutputStream(uri,"w")){if(os==null||!b.compress(Bitmap.CompressFormat.JPEG,96,os))throw new IllegalStateException("JPEG encode gagal");}if(Build.VERSION.SDK_INT>=29){ContentValues ready=new ContentValues();ready.put(MediaStore.MediaColumns.IS_PENDING,0);getContentResolver().update(uri,ready,null,null);}}}catch(Exception e){if(uri!=null)getContentResolver().delete(uri,null,null);uri=null;}Uri finalUri=uri;runOnUiThread(()->{setBusy(false,finalUri==null?"Gagal menyimpan. Pastikan izin penyimpanan tersedia.":"Salinan tersimpan di DCIM/RK Camera AI.");});});
    }

    private void chooseVideoStabilizer() {
        String[] opts={"Stabilizer Standar","Stabilizer Maks","Warna Cinema + Stabilizer Standar"};
        new android.app.AlertDialog.Builder(this).setTitle("Proses video setelah rekam").setItems(opts,(d,w)->{if(w==0)renderVideo(VideoStabilizer.STANDARD,IPhoneLook.ALL[0]);else if(w==1)renderVideo(VideoStabilizer.MAX,IPhoneLook.ALL[0]);else renderVideo(VideoStabilizer.STANDARD,IPhoneLook.ALL[12]);}).setNegativeButton("Batal",null).show();
    }

    private void renderVideo(int mode,IPhoneLook look) {
        if(selectedUri==null||!selectedVideo||busy)return;setBusy(true,"Menganalisis gerakan video… (HP lama mungkin memerlukan waktu)");Uri source=selectedUri;
        worker.execute(()->{
            File output=new File(getCacheDir(),"rkstudio_"+System.currentTimeMillis()+".mp4");
            try{
                VideoStabilizer.MotionPath motion=mode==VideoStabilizer.OFF?null:VideoStabilizer.analyze(this,source,mode);
                float aspect=VideoStabilizer.frameAspect(this,source);
                ArrayList<Effect> effects=new ArrayList<>();if(mode!=VideoStabilizer.OFF)effects.add(VideoStabilizer.transformation(motion,mode,null,null,aspect));
                try{effects.add(SingleColorLut.createFromCube(look.buildCube(17)));}catch(Exception ignored){}
                EditedMediaItem edited=new EditedMediaItem.Builder(MediaItem.fromUri(source.toString())).setEffects(new androidx.media3.transformer.Effects(Collections.emptyList(),effects)).build();
                runOnUiThread(()->setStatus("Merender video… jangan tutup aplikasi dulu."));
                transformer=new Transformer.Builder(getApplicationContext()).addListener(new Transformer.Listener(){
                    @Override public void onCompleted(Composition composition,ExportResult result){worker.execute(()->{Uri saved=publishVideo(output);if(output.exists())output.delete();runOnUiThread(()->{transformer=null;setBusy(false,saved==null?"Ekspor gagal; video asli tetap aman.":"Hasil video tersimpan di galeri.");if(saved!=null){selectedUri=saved;videoView.setVideoURI(saved);videoView.start();}});});}
                    @Override public void onError(Composition composition,ExportResult result,ExportException error){if(output.exists())output.delete();runOnUiThread(()->{transformer=null;setBusy(false,"Pemrosesan gagal pada perangkat ini. Video asli tetap aman.");});}
                }).build();
                transformer.start(edited,output.getAbsolutePath());
            }catch(Exception|OutOfMemoryError e){if(output.exists())output.delete();runOnUiThread(()->setBusy(false,"Tidak dapat memproses video ini. Video asli tetap aman."));}
        });
    }

    private Uri publishVideo(File file) {
        if(file==null||!file.exists()||file.length()==0)return null;Uri uri=null;try{String stamp=new SimpleDateFormat("yyyyMMdd_HHmmss",Locale.US).format(new Date());ContentValues v=new ContentValues();v.put(MediaStore.MediaColumns.DISPLAY_NAME,"RKStudio_"+stamp+".mp4");v.put(MediaStore.MediaColumns.MIME_TYPE,"video/mp4");if(Build.VERSION.SDK_INT>=29){v.put(MediaStore.MediaColumns.RELATIVE_PATH,Environment.DIRECTORY_MOVIES+"/RK Camera AI");v.put(MediaStore.MediaColumns.IS_PENDING,1);}else{File dir=new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES),"RK Camera AI");if(!dir.exists())dir.mkdirs();v.put(MediaStore.MediaColumns.DATA,new File(dir,"RKStudio_"+stamp+".mp4").getAbsolutePath());}uri=getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI,v);if(uri==null)return null;try(InputStream in=new java.io.FileInputStream(file);OutputStream out=getContentResolver().openOutputStream(uri,"w")){byte[] buf=new byte[65536];int n;while((n=in.read(buf))>0)out.write(buf,0,n);}if(Build.VERSION.SDK_INT>=29){ContentValues ready=new ContentValues();ready.put(MediaStore.MediaColumns.IS_PENDING,0);getContentResolver().update(uri,ready,null,null);}return uri;}catch(Exception e){if(uri!=null)getContentResolver().delete(uri,null,null);return null;}
    }

    private void setBusy(boolean value,String message){busy=value;setStatus(message);if(page!=null)page.setAlpha(value?0.86f:1f);}
    private boolean imageReady(){if(currentBitmap==null||imageView==null){setStatus("Foto belum siap diproses.");return false;}if(busy)return false;return true;}
    private void setStatus(String s){if(status!=null)status.setText(s);}
    private void addTool(LinearLayout row,String title,Runnable action){TextView b=button(title,action);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-2,dp(44));p.setMargins(dp(4),0,dp(4),0);row.addView(b,p);}
    private TextView button(String text,Runnable action){TextView t=txt(text,12,Color.WHITE,true);t.setGravity(Gravity.CENTER);t.setPadding(dp(13),0,dp(13),0);t.setBackground(round(PANEL,dp(12)));t.setOnClickListener(v->{if(!busy)action.run();});return t;}
    private TextView txt(String text,int size,int color,boolean bold){TextView t=new TextView(this);t.setText(text);t.setTextSize(size);t.setTextColor(color);t.setTypeface(android.graphics.Typeface.DEFAULT,bold?android.graphics.Typeface.BOLD:android.graphics.Typeface.NORMAL);return t;}
    private android.graphics.drawable.GradientDrawable round(int color,int r){android.graphics.drawable.GradientDrawable d=new android.graphics.drawable.GradientDrawable();d.setColor(color);d.setCornerRadius(r);return d;}
    private int dp(float x){return Math.round(x*getResources().getDisplayMetrics().density);}

    @Override protected void onDestroy(){if(transformer!=null){try{transformer.cancel();}catch(Exception ignored){}}worker.shutdown();super.onDestroy();}
}
