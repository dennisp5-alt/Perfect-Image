package com.dennis.perfectimage;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Color;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.widget.ImageView;

public class ZoomImageView extends ImageView {
    private final Matrix transform=new Matrix();
    private final ScaleGestureDetector scale;
    private float lastX,lastY, zoom=1;
    private boolean moving=false;
    private Bitmap original;
    private boolean comparing=false, possibleSwipe=false;
    private float touchStartX,touchStartY;
    private OnPhotoSwipe swipeListener;
    private final Paint beforePaint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);
    private final Paint dividerPaint=new Paint(Paint.ANTI_ALIAS_FLAG);
    public interface OnPhotoSwipe{void onSwipe(int direction);}
    public void setOnPhotoSwipe(OnPhotoSwipe listener){swipeListener=listener;}
    public void setBeforeBitmap(Bitmap source){original=source;invalidate();}
    public void setSplitComparison(boolean enabled){comparing=enabled;invalidate();}
    public ZoomImageView(Context context){
        super(context);
        setScaleType(ScaleType.MATRIX);
        setBackgroundColor(0xff000000);
        scale=new ScaleGestureDetector(context,new ScaleGestureDetector.SimpleOnScaleGestureListener(){
            @Override public boolean onScale(ScaleGestureDetector d){
                float next=Math.max(1,Math.min(8,zoom*d.getScaleFactor()));
                float factor=next/zoom;zoom=next;
                transform.postScale(factor,factor,d.getFocusX(),d.getFocusY());
                fixBounds();setImageMatrix(transform);return true;
            }
        });
    }
    @Override public void setImageBitmap(Bitmap bitmap){
        super.setImageBitmap(bitmap);
        post(this::fitImage);
    }
    private void fitImage(){
        if(getDrawable()==null||getWidth()<1||getHeight()<1)return;
        int w=getDrawable().getIntrinsicWidth(),h=getDrawable().getIntrinsicHeight();
        if(w<1||h<1)return;
        float fit=Math.min(getWidth()/(float)w,getHeight()/(float)h);
        transform.reset();
        transform.postScale(fit,fit);
        transform.postTranslate((getWidth()-w*fit)/2,(getHeight()-h*fit)/2);
        zoom=1;setImageMatrix(transform);
    }
    private void fixBounds(){
        if(getDrawable()==null)return;
        RectF rect=new RectF(0,0,getDrawable().getIntrinsicWidth(),getDrawable().getIntrinsicHeight());
        transform.mapRect(rect);
        float x=0,y=0;
        if(rect.width()<=getWidth())x=getWidth()/2f-rect.centerX();
        else if(rect.left>0)x=-rect.left;else if(rect.right<getWidth())x=getWidth()-rect.right;
        if(rect.height()<=getHeight())y=getHeight()/2f-rect.centerY();
        else if(rect.top>0)y=-rect.top;else if(rect.bottom<getHeight())y=getHeight()-rect.bottom;
        transform.postTranslate(x,y);
    }
    @Override protected void onDraw(Canvas canvas){
        super.onDraw(canvas);
        if(!comparing||original==null||original.isRecycled()||getDrawable()==null)return;
        int iw=getDrawable().getIntrinsicWidth(),ih=getDrawable().getIntrinsicHeight();
        if(iw<=0||ih<=0)return;
        Matrix m=new Matrix(transform);
        m.preScale(iw/(float)original.getWidth(),ih/(float)original.getHeight());
        float mid=getWidth()*.5f,d=getResources().getDisplayMetrics().density;
        canvas.save();
        canvas.clipRect(0,0,mid,getHeight());
        canvas.drawBitmap(original,m,beforePaint);
        canvas.restore();
        dividerPaint.setColor(Color.WHITE);
        dividerPaint.setStrokeWidth(2*d);
        canvas.drawLine(mid,0,mid,getHeight(),dividerPaint);
        dividerPaint.setColor(0xdb101820);
        canvas.drawRoundRect(8*d,8*d,83*d,36*d,8*d,8*d,dividerPaint);
        canvas.drawRoundRect(getWidth()-81*d,8*d,getWidth()-8*d,36*d,8*d,8*d,dividerPaint);
        dividerPaint.setColor(Color.WHITE);
        dividerPaint.setTextSize(12*d);
        dividerPaint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        canvas.drawText("BEFORE",16*d,27*d,dividerPaint);
        canvas.drawText("AFTER",getWidth()-70*d,27*d,dividerPaint);
    }
    @Override public boolean onTouchEvent(MotionEvent event){
        scale.onTouchEvent(event);
        switch(event.getActionMasked()){
            case MotionEvent.ACTION_DOWN:
                lastX=touchStartX=event.getX();
                lastY=touchStartY=event.getY();
                possibleSwipe=zoom<=1.02f;moving=true;return true;
            case MotionEvent.ACTION_MOVE:
                if(event.getPointerCount()>1)possibleSwipe=false;
                if(event.getPointerCount()==1&&!scale.isInProgress()&&moving&&!possibleSwipe){
                    transform.postTranslate(event.getX()-lastX,event.getY()-lastY);
                    fixBounds();setImageMatrix(transform);
                }
                lastX=event.getX();lastY=event.getY();return true;
            case MotionEvent.ACTION_UP:
                if(possibleSwipe&&swipeListener!=null){
                    float dx=event.getX()-touchStartX,dy=event.getY()-touchStartY;
                    float threshold=64*getResources().getDisplayMetrics().density;
                    if(Math.abs(dx)>threshold&&Math.abs(dx)>Math.abs(dy)*1.5f)
                        swipeListener.onSwipe(dx<0?1:-1);
                }
                moving=false;possibleSwipe=false;return true;
            case MotionEvent.ACTION_CANCEL:
                moving=false;possibleSwipe=false;return true;
        }
        return true;
    }
    public Bitmap saveVisibleView(){
        if(getDrawable()==null||getWidth()<1||getHeight()<1)return null;
        Bitmap result=Bitmap.createBitmap(getWidth(),getHeight(),Bitmap.Config.ARGB_8888);
        Canvas canvas=new Canvas(result);
        canvas.drawColor(0xff000000);
        getDrawable().setBounds(0,0,getDrawable().getIntrinsicWidth(),getDrawable().getIntrinsicHeight());
        canvas.save();canvas.concat(transform);
        getDrawable().draw(canvas);canvas.restore();
        return result;
    }
}
