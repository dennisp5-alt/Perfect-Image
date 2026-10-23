package com.dennis.perfectimage;

import android.app.Activity;
import android.Manifest;
import android.content.pm.PackageManager;
import android.view.MotionEvent;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
import android.content.ClipData;
import android.database.Cursor;
import android.provider.DocumentsContract;
import android.os.Build;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.Collections;
import android.graphics.Bitmap;
import android.graphics.ImageDecoder;
import android.net.Uri;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.TextView;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class MainActivity extends Activity {
    private static final int PICK=12, PICK_FOLDER=13, GALLERY_PERMISSION=14, PICK_CONTINUE_PERMISSION=15;
    private final ImageEngine.Settings settings=new ImageEngine.Settings();
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final ExecutorService executor=Executors.newSingleThreadExecutor();
    private Future<?> pending;
    private Bitmap original, preview;
    private ZoomImageView image;
    private TextView status;
    private LinearLayout controls;
    private int generation=0;
    private volatile boolean opening=false;
    private final java.util.concurrent.atomic.AtomicInteger activeExports=
        new java.util.concurrent.atomic.AtomicInteger();
    private final ArrayList<Bitmap> deferredRecycling=new ArrayList<>();
    private void releaseOldImages(){
        if(activeExports.get()!=0)return;
        for(Bitmap bmp:deferredRecycling)if(bmp!=null&&!bmp.isRecycled())bmp.recycle();
        deferredRecycling.clear();
    }
    private final ArrayList<Uri> photos=new ArrayList<>();
    private int position=-1;
    private TextView counter;
    private Button compareButton;
    private Uri pendingPickedUri;
    private boolean promptedForContinuation=false;
    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        getWindow().setStatusBarColor(0xff101010);
        getWindow().setNavigationBarColor(0xff101010);
        buildUI();
        if(getIntent()!=null&&getIntent().getData()!=null)open(getIntent().getData());
    }
    private int dp(int n){return (int)(getResources().getDisplayMetrics().density*n+.5f);}
    private TextView label(String text,int size){
        TextView v=new TextView(this);v.setText(text);v.setTextSize(size);v.setTextColor(-1);
        v.setGravity(Gravity.CENTER_VERTICAL);return v;
    }
    private Button button(String text,Runnable action){
        Button b=new Button(this);b.setText(text);b.setAllCaps(false);
        b.setOnClickListener(v->action.run());return b;
    }
    private void buildUI(){
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xff101010);setContentView(root);
        LinearLayout top=new LinearLayout(this);top.setGravity(Gravity.CENTER_VERTICAL);
        TextView title=label("PERFECT IMAGE",19);title.setPadding(dp(14),0,0,0);
        top.addView(title,new LinearLayout.LayoutParams(0,dp(52),1));
        top.addView(button("Gallery",()->showGallery()));
        top.addView(button("Pick",()->choosePhoto()));
        top.addView(button("Save",()->save(false,false)));
        root.addView(top);
        image=new ZoomImageView(this);
        root.addView(image,new LinearLayout.LayoutParams(-1,0,1));
        image.setOnPhotoSwipe(direction->nextPhoto(direction));
        status=label("Open a photo to begin",12);status.setGravity(Gravity.CENTER);
        root.addView(status,new LinearLayout.LayoutParams(-1,dp(26)));
        LinearLayout nav=new LinearLayout(this);
        nav.addView(button("‹",()->nextPhoto(-1)),new LinearLayout.LayoutParams(0,dp(48),1));
        counter=label("No photos",12);counter.setGravity(Gravity.CENTER);
        nav.addView(counter,new LinearLayout.LayoutParams(0,dp(48),2));
        compareButton=button("Hold original",()->{});
        // Consume all button touch events; the original disappears immediately
        // on lifting, dragging out of the control, or cancelling.
        compareButton.setOnTouchListener((v,e)->{
            switch(e.getActionMasked()){
                case MotionEvent.ACTION_DOWN:
                    image.setShowOriginal(true);
                    compareButton.setPressed(true);
                    return true;
                case MotionEvent.ACTION_CANCEL:
                case MotionEvent.ACTION_UP:
                    image.setShowOriginal(false);
                    compareButton.setPressed(false);
                    return true;
                default:return true;
            }
        });
        nav.addView(compareButton,new LinearLayout.LayoutParams(0,dp(48),3));
        nav.addView(button("›",()->nextPhoto(1)),new LinearLayout.LayoutParams(0,dp(48),1));
        nav.addView(button("Folder",()->chooseFolder()),new LinearLayout.LayoutParams(0,dp(48),2));
        root.addView(nav);
        ScrollView scroller=new ScrollView(this);scroller.setFillViewport(false);
        controls=new LinearLayout(this);controls.setOrientation(LinearLayout.VERTICAL);
        controls.setPadding(dp(14),dp(5),dp(14),dp(14));
        scroller.addView(controls);
        root.addView(scroller,new LinearLayout.LayoutParams(-1,dp(345)));
        TextView modes=label("RENDERING MODE",12);modes.setGravity(Gravity.CENTER);
        controls.addView(modes);
        RadioGroup rg=new RadioGroup(this);rg.setOrientation(RadioGroup.HORIZONTAL);
        for(ImageEngine.Mode mode:ImageEngine.Mode.values()){
            RadioButton b=new RadioButton(this);b.setText(mode.name());
            b.setTextSize(12);b.setTextColor(-1);b.setId(View.generateViewId());
            b.setChecked(settings.mode==mode);
            b.setOnClickListener(v->{settings.mode=mode;renderPreview();});
            rg.addView(b,new RadioGroup.LayoutParams(0,dp(45),1));
        }
        controls.addView(rg);
        check("Linear colour space",settings.linear,v->settings.linear=v);
        check("Normalization",settings.normalize,v->settings.normalize=v);
        check("Auto white balance",settings.wb,v->settings.wb=v);
        addSlider("Heat",v->settings.heat=v);
        addSlider("Brightness",v->settings.brightness=v);
        addSlider("Contrast",v->settings.contrast=v);
        addSlider("Saturation",v->settings.saturation=v);
        addRenderStrengthSlider();
        LinearLayout actions=new LinearLayout(this);
        actions.addView(button("Save rendered PNG",()->save(true,false)),
            new LinearLayout.LayoutParams(0,dp(53),1));
        actions.addView(button("Save visible crop",()->save(false,true)),
            new LinearLayout.LayoutParams(0,dp(53),1));
        controls.addView(actions);
        controls.addView(button("More save options",()->new AlertDialog.Builder(this)
            .setItems(new String[]{"Full rendered JPEG (100%)","Full rendered PNG",
                    "Visible crop JPEG","Visible crop PNG"},
                (dlg,which)->save(which%2==1,which>=2)).show()));
    }
    private interface BoolChange {void change(boolean value);}
    private interface ValueChange {void change(int value);}
    private void check(String text,boolean initial,BoolChange changed){
        CheckBox cb=new CheckBox(this);cb.setText(text);cb.setTextColor(-1);
        cb.setChecked(initial);cb.setOnCheckedChangeListener((v,b)->{changed.change(b);renderPreview();});
        controls.addView(cb);
    }
    private void addSlider(String text,ValueChange changed){
        LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.VERTICAL);
        TextView title=label(text+": 0",12);title.setGravity(Gravity.CENTER);
        row.addView(title);
        SeekBar bar=new SeekBar(this);bar.setMax(200);bar.setProgress(100);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            public void onStartTrackingTouch(SeekBar s){}
            public void onStopTrackingTouch(SeekBar s){renderPreview();}
            public void onProgressChanged(SeekBar s,int progress,boolean user){
                if(!user)return;int value=progress-100;
                changed.change(value);title.setText(text+": "+value);
            }
        });
        row.addView(bar);controls.addView(row);
    }
    private void addRenderStrengthSlider(){
        LinearLayout item=new LinearLayout(this);
        item.setOrientation(LinearLayout.VERTICAL);
        TextView title=label("Rendering strength: "+settings.renderStrength+"%",12);
        title.setGravity(Gravity.CENTER);
        item.addView(title);
        SeekBar bar=new SeekBar(this);bar.setMax(100);bar.setProgress(settings.renderStrength);
        bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){
            @Override public void onStartTrackingTouch(SeekBar b){}
            @Override public void onStopTrackingTouch(SeekBar b){renderPreview();}
            @Override public void onProgressChanged(SeekBar b,int value,boolean user){
                if(!user)return;
                settings.renderStrength=value;
                title.setText("Rendering strength: "+value+"%");
            }
        });
        item.addView(bar);
        controls.addView(item);
    }
    private boolean hasFullGalleryAccess(){
        return checkSelfPermission(Build.VERSION.SDK_INT>=33?
            Manifest.permission.READ_MEDIA_IMAGES:
            Manifest.permission.READ_EXTERNAL_STORAGE)==PackageManager.PERMISSION_GRANTED;
    }
    private void continuePickedPhoto(Uri picked){
        if(picked==null||!hasFullGalleryAccess())return;
        final String key=picked.toString();
        status.setText("Finding selected photo's album…");
        GalleryPicker.continueFromPicked(this,picked,(queue,index)->{
            if(pendingPickedUri==null||!key.equals(pendingPickedUri.toString()))return;
            photos.clear();photos.addAll(queue);position=index;
            counter.setText((position+1)+" / "+photos.size());
            status.setText("Album ready · swipe for next photo");
            // Reuse selected image; next swipe uses the correctly matched MediaStore URI.
        },reason->{
            if(pendingPickedUri!=null&&key.equals(pendingPickedUri.toString()))
                status.setText("Swipe selected photos, or open Gallery to browse albums");
        });
    }
    private void offerPickContinuation(){
        if(pendingPickedUri==null)return;
        if(hasFullGalleryAccess()){continuePickedPhoto(pendingPickedUri);return;}
        if(promptedForContinuation)return;
        promptedForContinuation=true;
        new AlertDialog.Builder(this).setTitle("Continue through this folder?")
            .setMessage("To swipe through other photos after using Pick, Perfect Image needs access to the rest of your photo library. Choose Allow all photos. Otherwise, swipe is limited to the photos you selected.")
            .setPositiveButton("Allow photo access",(dialog,which)->{
                if(Build.VERSION.SDK_INT>=34)requestPermissions(new String[]{
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED},
                    PICK_CONTINUE_PERMISSION);
                else requestPermissions(new String[]{Build.VERSION.SDK_INT>=33?
                    Manifest.permission.READ_MEDIA_IMAGES:
                    Manifest.permission.READ_EXTERNAL_STORAGE},PICK_CONTINUE_PERMISSION);
            })
            .setNegativeButton("Only selected",null).show();
    }
    private void showGallery(){
        if(Build.VERSION.SDK_INT>=34){
            if(checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES)!=
                    PackageManager.PERMISSION_GRANTED &&
               checkSelfPermission(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED)!=
                    PackageManager.PERMISSION_GRANTED){
                requestPermissions(new String[]{
                    Manifest.permission.READ_MEDIA_IMAGES,
                    Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED},
                    GALLERY_PERMISSION);
                return;
            }
        }else if(Build.VERSION.SDK_INT>=33){
            if(checkSelfPermission(Manifest.permission.READ_MEDIA_IMAGES)!=
                    PackageManager.PERMISSION_GRANTED){
                requestPermissions(new String[]{Manifest.permission.READ_MEDIA_IMAGES},
                    GALLERY_PERMISSION);
                return;
            }
        }else if(checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE)!=
                PackageManager.PERMISSION_GRANTED){
            requestPermissions(new String[]{Manifest.permission.READ_EXTERNAL_STORAGE},
                GALLERY_PERMISSION);
            return;
        }
        GalleryPicker.open(this,(queue,index)->{
            pendingPickedUri=null;
            photos.clear();photos.addAll(queue);position=index;
            open(photos.get(position));
        });
    }
    @Override public void onRequestPermissionsResult(int requestCode,
            String[] perms,int[] grants){
        super.onRequestPermissionsResult(requestCode,perms,grants);
        if(requestCode==PICK_CONTINUE_PERMISSION){
            if(hasFullGalleryAccess()&&pendingPickedUri!=null)
                continuePickedPhoto(pendingPickedUri);
            else status.setText("Only selected photos accessible · use Gallery or Folder");
            return;
        }
        if(requestCode==GALLERY_PERMISSION){
            boolean allowed=false;
            for(int grant:grants)if(grant==PackageManager.PERMISSION_GRANTED)allowed=true;
            if(allowed)showGallery();
            else new AlertDialog.Builder(this)
                .setMessage("To swipe through your whole gallery, allow photo access. You can still use Pick to select several pictures or Folder to browse one folder.")
                .setPositiveButton("OK",null).show();
        }
    }
    private void choosePhoto(){
        Intent i;
        if(Build.VERSION.SDK_INT>=33){
            i=new Intent(MediaStore.ACTION_PICK_IMAGES);
            i.setType("image/*");
            i.putExtra(MediaStore.EXTRA_PICK_IMAGES_MAX,
                Math.min(50,MediaStore.getPickImagesMaxLimit()));
        }else{
            i=new Intent(Intent.ACTION_OPEN_DOCUMENT);
            i.addCategory(Intent.CATEGORY_OPENABLE);
            i.setType("image/*");i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true);
        }
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivityForResult(i,PICK);
    }
    private void chooseFolder(){
        Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|
                   Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        startActivityForResult(i,PICK_FOLDER);
    }
    @Override protected void onActivityResult(int code,int result,Intent data){
        super.onActivityResult(code,result,data);
        if(result!=RESULT_OK||data==null)return;
        if(code==PICK_FOLDER){
            Uri folder=data.getData();if(folder==null)return;
            try{getContentResolver().takePersistableUriPermission(folder,
                Intent.FLAG_GRANT_READ_URI_PERMISSION);}catch(Exception ignored){}
            readFolder(folder);return;
        }
        if(code!=PICK)return;
        ArrayList<Uri> chosen=new ArrayList<>();
        ClipData clips=data.getClipData();
        if(clips!=null)for(int n=0;n<clips.getItemCount();n++){
            Uri u=clips.getItemAt(n).getUri();
            if(u!=null&&!chosen.contains(u))chosen.add(u);
        }
        Uri single=data.getData();
        if(single!=null&&!chosen.contains(single))chosen.add(single);
        if(chosen.isEmpty())return;
        photos.clear();photos.addAll(chosen);position=0;open(photos.get(0));
        pendingPickedUri=chosen.size()==1?chosen.get(0):null;
        promptedForContinuation=false;
        if(chosen.size()==1)offerPickContinuation();
    }
    private void readFolder(Uri folder){
        ArrayList<String> names=new ArrayList<>(), ids=new ArrayList<>();
        try{
            Uri children=DocumentsContract.buildChildDocumentsUriUsingTree(
                folder,DocumentsContract.getTreeDocumentId(folder));
            String[] cols={DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                DocumentsContract.Document.COLUMN_MIME_TYPE};
            try(Cursor c=getContentResolver().query(children,cols,null,null,null)){
                if(c!=null)while(c.moveToNext()){
                    String mime=c.getString(2);
                    if(mime!=null&&mime.startsWith("image/")){
                        ids.add(c.getString(0));names.add(c.getString(1));
                    }
                }
            }
            ArrayList<Integer> order=new ArrayList<>();
            for(int n=0;n<ids.size();n++)order.add(n);
            Collections.sort(order,(a,b)->names.get(a).compareToIgnoreCase(names.get(b)));
            photos.clear();
            for(int n:order)photos.add(
                DocumentsContract.buildDocumentUriUsingTree(folder,ids.get(n)));
            if(photos.isEmpty()){status.setText("No photos in folder");return;}
            position=0;open(photos.get(0));
        }catch(Exception e){status.setText("Folder error: "+e.getMessage());}
    }
    private void nextPhoto(int direction){
        int next=position+direction;
        if(next<0||next>=photos.size()){
            Toast.makeText(this,next<0?"First photo":"Last photo",Toast.LENGTH_SHORT).show();
            return;
        }
        position=next;open(photos.get(next));
    }
    private void open(Uri uri){
        opening=true;
        image.setShowOriginal(false);
        image.setBeforeBitmap(null);
        image.setImageBitmap(null);
        if(counter!=null)counter.setText(photos.isEmpty()?"Single photo":
            (position+1)+" / "+photos.size());
        status.setText("Opening photo…");
        if(pending!=null)pending.cancel(true);
        final int id=++generation;
        pending=executor.submit(()->{
            try {
                ImageDecoder.Source src=ImageDecoder.createSource(getContentResolver(),uri);
                Bitmap decoded=ImageDecoder.decodeBitmap(src,(decoder,info,source)->{
                    decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
                    int w=info.getSize().getWidth(),h=info.getSize().getHeight();
                    int biggest=Math.max(w,h);
                    if(biggest>4096)decoder.setTargetSampleSize((int)Math.ceil(biggest/4096.0));
                });
                ui.post(()->{
                    if(id!=generation){decoded.recycle();return;}
                    Bitmap old=original;original=decoded;
                    opening=false;
                    image.setBeforeBitmap(decoded);
                    if(old!=null&&old!=preview){
                        if(activeExports.get()>0)deferredRecycling.add(old);
                        else old.recycle();
                    }
                    releaseOldImages();
                    renderPreview();
                });
            }catch(Exception ex){ui.post(()->{
                if(id==generation){opening=false;status.setText("Open failed: "+ex.getMessage());}
            });}
        });
    }
    private void renderPreview(){
        if(original==null||isFinishing())return;
        final int id=++generation;
        if(pending!=null)pending.cancel(true);
        final Bitmap source=original;
        final ImageEngine.Settings snap=settings.copy();
        int width=source.getWidth(),height=source.getHeight();
        int longest=Math.max(width,height);
        final int outW=Math.max(1,(int)Math.round(width*Math.min(1,1440.0/longest)));
        final int outH=Math.max(1,(int)Math.round(height*Math.min(1,1440.0/longest)));
        status.setText("Rendering "+snap.mode+"…");
        pending=executor.submit(()->{
            try{
                Bitmap b=ImageEngine.render(source,outW,outH,snap);
                ui.post(()->{
                    if(id!=generation){b.recycle();return;}
                    Bitmap old=preview;preview=b;image.setImageBitmap(b);
                    status.setText(snap.mode+" · "+outW+"×"+outH+
                        (snap.linear?" · Linear":""));
                    if(old!=null&&old!=source&&old!=b)old.recycle();
                });
            }catch(Exception ex){ui.post(()->{
                if(id==generation)status.setText("Render failed: "+ex.getMessage());
            });}
        });
    }
    private void save(boolean png,boolean visible){
        if(original==null||opening){status.setText("Photo is still opening");return;}
        if(visible){
            Bitmap crop=image.saveVisibleView();
            if(crop!=null)executor.submit(()->saveBitmap(crop,png,true,true));
            return;
        }
        final Bitmap source=original;
        final ImageEngine.Settings snap=settings.copy();
        status.setText("Saving full rendered image…");
        activeExports.incrementAndGet();
        executor.submit(()->{
            try{
                Bitmap out=ImageEngine.render(source,source.getWidth(),source.getHeight(),snap);
                saveBitmap(out,png,false,true);
            }catch(Exception ex){ui.post(()->status.setText("Save failed: "+ex.getMessage()));}
            finally{
                activeExports.decrementAndGet();
                ui.post(this::releaseOldImages);
            }
        });
    }
    private void saveBitmap(Bitmap bmp,boolean png,boolean visible,boolean release){
        Uri file=null;
        try {
            ContentResolver resolver=getContentResolver();
            ContentValues values=new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME,
                (visible?"PerfectImage_View_":"PerfectImage_Rendered_")+
                System.currentTimeMillis()+(png?".png":".jpg"));
            values.put(MediaStore.Images.Media.MIME_TYPE,png?"image/png":"image/jpeg");
            values.put(MediaStore.Images.Media.RELATIVE_PATH,Environment.DIRECTORY_PICTURES+"/Perfect Image");
            values.put(MediaStore.Images.Media.IS_PENDING,1);
            file=resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,values);
            if(file==null)throw new IllegalStateException("Unable to create image file");
            try(OutputStream os=resolver.openOutputStream(file)){
                if(os==null||!bmp.compress(png?Bitmap.CompressFormat.PNG:
                    Bitmap.CompressFormat.JPEG,100,os))
                    throw new IllegalStateException("Encoding failed");
            }
            values.clear();values.put(MediaStore.Images.Media.IS_PENDING,0);
            resolver.update(file,values,null,null);
            ui.post(()->{
                status.setText("Saved to Pictures/Perfect Image");
                Toast.makeText(this,"Saved · swipe left for next photo",Toast.LENGTH_SHORT).show();
            });
        }catch(Exception ex){
            if(file!=null)getContentResolver().delete(file,null,null);
            ui.post(()->status.setText("Save failed: "+ex.getMessage()));
        }finally{if(release)bmp.recycle();}
    }
    @Override protected void onDestroy(){
        ++generation;
        if(pending!=null)pending.cancel(true);
        executor.shutdownNow();
        super.onDestroy();
    }
}
