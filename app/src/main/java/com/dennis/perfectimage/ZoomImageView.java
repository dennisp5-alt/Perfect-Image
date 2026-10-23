package com.dennis.perfectimage;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.widget.ImageView;

public class ZoomImageView extends ImageView {
    private final Matrix transform=new Matrix();
    private final ScaleGestureDetector scale;
    private float lastX,lastY, zoom=1;
    private boolean moving=false;
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
    @Override public boolean onTouchEvent(MotionEvent event){
        scale.onTouchEvent(event);
        switch(event.getActionMasked()){
            case MotionEvent.ACTION_DOWN:lastX=event.getX();lastY=event.getY();moving=true;return true;
            case MotionEvent.ACTION_MOVE:
                if(event.getPointerCount()==1&&!scale.isInProgress()&&moving){
                    transform.postTranslate(event.getX()-lastX,event.getY()-lastY);
                    fixBounds();setImageMatrix(transform);
                }
                lastX=event.getX();lastY=event.getY();return true;
            case MotionEvent.ACTION_UP:case MotionEvent.ACTION_CANCEL:moving=false;return true;
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
