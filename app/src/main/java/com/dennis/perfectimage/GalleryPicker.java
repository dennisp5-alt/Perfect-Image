package com.dennis.perfectimage;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentUris;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.CancellationSignal;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.util.LruCache;
import android.util.Size;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Real MediaStore folder/album browser, distinct from the permission-scoped
 * Android system picker. Never assumes the latter grants neighbouring files.
 */
public final class GalleryPicker {
    private GalleryPicker(){}
    public interface Picked{void onPick(ArrayList<Uri> ordered,int index);}
    public interface NotFound{void onNotFound(String reason);}
    private static final ExecutorService IO=Executors.newFixedThreadPool(3);
    private static final LruCache<String,Bitmap> THUMBS=new LruCache<String,Bitmap>(25*1024){
        @Override protected int sizeOf(String key,Bitmap bmp){
            return Math.max(1,bmp.getByteCount()/1024);
        }
    };
    private static final class Photo{
        Uri uri;
        String name,bucket;
        long size;
        Photo(Uri u,String n,String b,long s){uri=u;name=n;bucket=b;size=s;}
    }
    private static final class Album{
        String key,title;
        ArrayList<Photo> photos=new ArrayList<>();
        Album(String k,String t){key=k;title=t;}
    }
    private static final class Library{
        ArrayList<Photo> all=new ArrayList<>();
        LinkedHashMap<String,Album> albums=new LinkedHashMap<>();
    }
    private static Library load(Activity a) throws Exception {
        Library data=new Library();
        Uri root=MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        String[] cols={MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.BUCKET_ID,
            MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
            MediaStore.Images.Media.SIZE};
        String sort=MediaStore.Images.Media.DATE_TAKEN+" DESC, "+
            MediaStore.Images.Media._ID+" DESC";
        try(Cursor c=a.getContentResolver().query(root,cols,null,null,sort)){
            if(c!=null)while(c.moveToNext()){
                Uri uri=ContentUris.withAppendedId(root,c.getLong(0));
                String name=c.getString(1),bucket=c.getString(2),
                    label=c.getString(3);
                long size=c.getLong(4);
                if(bucket==null)bucket="other";
                if(label==null||label.trim().isEmpty())label="Other";
                Photo photo=new Photo(uri,name,bucket,size);
                data.all.add(photo);
                Album album=data.albums.get(bucket);
                if(album==null){
                    album=new Album(bucket,label);
                    data.albums.put(bucket,album);
                }
                album.photos.add(photo);
            }
        }
        return data;
    }
    public static void open(Activity a,Picked callback){
        Toast.makeText(a,"Loading albums…",Toast.LENGTH_SHORT).show();
        IO.execute(()->{
            Library library=null;
            String error=null;
            try{library=load(a);}catch(Exception e){error=e.getMessage();}
            final Library result=library;final String issue=error;
            a.runOnUiThread(()->{
                if(a.isFinishing()||a.isDestroyed())return;
                if(issue!=null){
                    new AlertDialog.Builder(a).setMessage("Gallery unavailable: "+issue)
                        .setPositiveButton("OK",null).show();
                }else if(result==null||result.all.isEmpty()){
                    new AlertDialog.Builder(a)
                        .setMessage("No accessible images. Grant full photo access to browse every album, or use Pick / Folder.")
                        .setPositiveButton("OK",null).show();
                }else showAlbums(a,result,callback);
            });
        });
    }
    private static void showAlbums(Activity a,Library library,Picked picked){
        ArrayList<Album> albums=new ArrayList<>(library.albums.values());
        Collections.sort(albums,Comparator.comparing(x->x.title.toLowerCase(java.util.Locale.ROOT)));
        String[] choices=new String[albums.size()+1];
        choices[0]="All photos ("+library.all.size()+")";
        for(int n=0;n<albums.size();n++){
            Album x=albums.get(n);
            choices[n+1]=x.title+" ("+x.photos.size()+")";
        }
        new AlertDialog.Builder(a).setTitle("Browse albums / folders")
            .setItems(choices,(dialog,index)->{
                ArrayList<Photo> list=index==0?library.all:albums.get(index-1).photos;
                showGrid(a,list,index==0?"All photos":albums.get(index-1).title,picked);
            })
            .setNegativeButton("Cancel",null).show();
    }
    /** Match a system-picked file by both its name and exact byte count.
     * If ambiguous, do not silently choose the wrong file or neighbouring album.
     */
    public static void continueFromPicked(Activity a,Uri selected,Picked picked,NotFound failure){
        IO.execute(()->{
            Library library=null;String issue=null;
            try{library=load(a);}catch(Exception e){issue=e.getMessage();}
            String display=null;long bytes=-1;
            try(Cursor c=a.getContentResolver().query(selected,
                    new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE},
                    null,null,null)){
                if(c!=null&&c.moveToFirst()){
                    display=c.getString(0);
                    if(!c.isNull(1))bytes=c.getLong(1);
                }
            }catch(Exception ignored){}
            ArrayList<Uri> queue=null;int selectedIndex=-1;
            if(library!=null&&display!=null&&bytes>=0){
                Photo unique=null;
                for(Photo p:library.all){
                    if(display.equals(p.name)&&bytes==p.size){
                        if(unique!=null){unique=null;selectedIndex=-2;break;}
                        unique=p;
                    }
                }
                if(unique!=null){
                    Album album=library.albums.get(unique.bucket);
                    if(album!=null){
                        queue=new ArrayList<>();
                        for(Photo p:album.photos){
                            if(p.uri.equals(unique.uri))selectedIndex=queue.size();
                            queue.add(p.uri);
                        }
                    }
                }
            }
            final ArrayList<Uri> found=queue;
            final int index=selectedIndex;
            final String problem=issue;
            a.runOnUiThread(()->{
                if(a.isFinishing()||a.isDestroyed())return;
                if(found!=null&&index>=0)picked.onPick(found,index);
                else failure.onNotFound(problem!=null?problem:
                    "Couldn't safely identify this photo's album. Choose Gallery and select its folder.");
            });
        });
    }
    private static void showGrid(Activity a,ArrayList<Photo> list,
                                 String albumName,Picked picked){
        GridView grid=new GridView(a);
        grid.setNumColumns(3);
        grid.setHorizontalSpacing(dp(a,3));
        grid.setVerticalSpacing(dp(a,3));
        grid.setPadding(dp(a,3),dp(a,3),dp(a,3),dp(a,3));
        grid.setBackgroundColor(0xff111820);
        grid.setAdapter(new BaseAdapter(){
            @Override public int getCount(){return list.size();}
            @Override public Object getItem(int pos){return list.get(pos);}
            @Override public long getItemId(int pos){return pos;}
            @Override public View getView(int pos,View existing,ViewGroup parent){
                ImageView view;
                if(existing instanceof ImageView)view=(ImageView)existing;
                else{
                    view=new ImageView(a);
                    view.setScaleType(ImageView.ScaleType.CENTER_CROP);
                    view.setBackgroundColor(0xff27333e);
                    view.setLayoutParams(new android.widget.AbsListView.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,dp(a,108)));
                }
                Uri photo=list.get(pos).uri;
                String key=photo.toString();
                view.setTag(key);
                Bitmap cached=THUMBS.get(key);
                view.setImageBitmap(cached);
                if(cached==null)IO.execute(()->{
                    Bitmap thumb=null;
                    try{thumb=a.getContentResolver().loadThumbnail(
                        photo,new Size(220,220),(CancellationSignal)null);
                    }catch(Exception ignored){}
                    if(thumb!=null){
                        THUMBS.put(key,thumb);
                        final Bitmap ready=thumb;
                        a.runOnUiThread(()->{
                            if(!a.isDestroyed()&&key.equals(view.getTag()))
                                view.setImageBitmap(ready);
                        });
                    }
                });
                return view;
            }
        });
        AlertDialog dialog=new AlertDialog.Builder(a)
            .setTitle(albumName+" · "+list.size()+" photos")
            .setView(grid).setNegativeButton("Albums",(d,w)->open(a,picked))
            .create();
        grid.setOnItemClickListener((parent,v,pos,id)->{
            ArrayList<Uri> uris=new ArrayList<>();
            for(Photo p:list)uris.add(p.uri);
            dialog.dismiss();
            picked.onPick(uris,pos);
        });
        dialog.show();
    }
    private static int dp(Activity a,int n){
        return (int)(a.getResources().getDisplayMetrics().density*n+.5f);
    }
}
