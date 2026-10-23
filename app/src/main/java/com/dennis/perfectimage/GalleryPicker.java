package com.dennis.perfectimage;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentUris;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.os.CancellationSignal;
import android.provider.MediaStore;
import android.util.LruCache;
import android.util.Size;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.LinearLayout;
import android.view.Gravity;
import java.util.ArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Browse the images actually accessible through scoped MediaStore permission.
 * A system Photo Picker URI cannot provide its neighbouring files; Gallery
 * explicitly requests library access and builds a proper navigable sequence.
 */
public final class GalleryPicker {
    private GalleryPicker(){}
    public interface Picked { void onPick(ArrayList<Uri> ordered, int index); }
    private static final ExecutorService IO=Executors.newFixedThreadPool(3);
    private static final LruCache<String,Bitmap> THUMBS=new LruCache<String,Bitmap>(20*1024){
        @Override protected int sizeOf(String key,Bitmap bitmap){
            return Math.max(1,bitmap.getByteCount()/1024);
        }
    };
    public static void open(Activity a,Picked onPicked){
        Toast.makeText(a,"Loading photo library…",Toast.LENGTH_SHORT).show();
        IO.execute(()->{
            ArrayList<Uri> list=new ArrayList<>();
            String error=null;
            try{
                Uri content=MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
                String[] projection={MediaStore.Images.Media._ID};
                try(Cursor c=a.getContentResolver().query(content,projection,null,null,
                        MediaStore.Images.Media.DATE_TAKEN+" DESC, "+
                        MediaStore.Images.Media._ID+" DESC")){
                    if(c!=null)while(c.moveToNext()){
                        list.add(ContentUris.withAppendedId(content,c.getLong(0)));
                    }
                }
            }catch(Exception ex){error=ex.getMessage();}
            final String problem=error;
            a.runOnUiThread(()->{
                if(a.isFinishing()||a.isDestroyed())return;
                if(problem!=null){
                    new AlertDialog.Builder(a).setMessage("Gallery unavailable: "+problem)
                        .setPositiveButton("OK",null).show();
                }else if(list.isEmpty()){
                    new AlertDialog.Builder(a)
                        .setMessage("No accessible photos. Allow all photos in Android permissions, or use Photos / Folder.")
                        .setPositiveButton("OK",null).show();
                }else showGrid(a,list,onPicked);
            });
        });
    }
    private static void showGrid(Activity a,ArrayList<Uri> list,Picked chosen){
        GridView grid=new GridView(a);
        grid.setNumColumns(3);
        grid.setHorizontalSpacing(dp(a,3));grid.setVerticalSpacing(dp(a,3));
        grid.setPadding(dp(a,3),dp(a,3),dp(a,3),dp(a,3));
        grid.setBackgroundColor(0xff111820);
        grid.setAdapter(new BaseAdapter(){
            @Override public int getCount(){return list.size();}
            @Override public Object getItem(int pos){return list.get(pos);}
            @Override public long getItemId(int pos){return pos;}
            @Override public View getView(int pos,View convert,ViewGroup parent){
                ImageView view;
                if(convert instanceof ImageView) view=(ImageView)convert;
                else {
                    view=new ImageView(a);
                    view.setScaleType(ImageView.ScaleType.CENTER_CROP);
                    view.setBackgroundColor(0xff263542);
                    view.setLayoutParams(new android.widget.AbsListView.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,dp(a,110)));
                }
                Uri uri=list.get(pos);
                String key=uri.toString();
                view.setTag(key);
                Bitmap cached=THUMBS.get(key);
                view.setImageBitmap(cached);
                if(cached==null){
                    IO.execute(()->{
                        Bitmap thumb=null;
                        try{thumb=a.getContentResolver().loadThumbnail(uri,new Size(220,220),
                            (CancellationSignal)null);}catch(Exception ignored){}
                        if(thumb!=null){
                            THUMBS.put(key,thumb);
                            final Bitmap ready=thumb;
                            a.runOnUiThread(()->{
                                if(key.equals(view.getTag())&&!a.isDestroyed())
                                    view.setImageBitmap(ready);
                            });
                        }
                    });
                }
                return view;
            }
        });
        AlertDialog dialog=new AlertDialog.Builder(a)
            .setTitle("Gallery · "+list.size()+" photos")
            .setView(grid).setNegativeButton("Close",null).create();
        grid.setOnItemClickListener((parent,v,index,id)->{
            dialog.dismiss();
            chosen.onPick(list,index);
        });
        dialog.show();
    }
    private static int dp(Activity a,int n){
        return (int)(a.getResources().getDisplayMetrics().density*n+0.5f);
    }
}
