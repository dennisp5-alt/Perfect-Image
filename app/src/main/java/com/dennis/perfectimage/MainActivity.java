package com.dennis.perfectimage;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Intent;
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
    private static final int PICK=12;
    private final ImageEngine.Settings settings=new ImageEngine.Settings();
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final ExecutorService executor=Executors.newSingleThreadExecutor();
    private Future<?> pending;
    private Bitmap original, preview;
    private ZoomImageView image;
    private TextView status;
    private LinearLayout controls;
    private int generation=0;
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
        top.addView(button("Open",()->choosePhoto()));
        top.addView(button("Save",()->save(false,false)));
        root.addView(top);
        image=new ZoomImageView(this);
        root.addView(image,new LinearLayout.LayoutParams(-1,0,1));
        status=label("Open a photo to begin",12);status.setGravity(Gravity.CENTER);
        root.addView(status,new LinearLayout.LayoutParams(-1,dp(26)));
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
    private void choosePhoto(){
        Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);i.setType("image/*");
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        startActivityForResult(i,PICK);
    }
    @Override protected void onActivityResult(int code,int result,Intent data){
        super.onActivityResult(code,result,data);
        if(code==PICK&&result==RESULT_OK&&data!=null&&data.getData()!=null)
            open(data.getData());
    }
    private void open(Uri uri){
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
                    if(old!=null&&old!=preview)old.recycle();
                    renderPreview();
                });
            }catch(Exception ex){ui.post(()->status.setText("Open failed: "+ex.getMessage()));}
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
        if(original==null){status.setText("Open a photo first");return;}
        if(visible){
            Bitmap crop=image.saveVisibleView();
            if(crop!=null)executor.submit(()->saveBitmap(crop,png,true,true));
            return;
        }
        final Bitmap source=original;
        final ImageEngine.Settings snap=settings.copy();
        status.setText("Saving full rendered image…");
        executor.submit(()->{
            try{
                Bitmap out=ImageEngine.render(source,source.getWidth(),source.getHeight(),snap);
                saveBitmap(out,png,false,true);
            }catch(Exception ex){ui.post(()->status.setText("Save failed: "+ex.getMessage()));}
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
            ui.post(()->status.setText("Saved to Pictures/Perfect Image"));
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
