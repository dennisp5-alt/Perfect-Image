package com.dennis.perfectimage;

import android.graphics.Bitmap;
import android.graphics.Color;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;

/** Independently implemented photo resampling and adjustment engine. */
public final class ImageEngine {
    private ImageEngine() {}
    enum Mode { STANDARD, NATURAL, CLEAR }
    static final class Settings {
        Mode mode=Mode.CLEAR;
        boolean linear=true, normalize=true, wb=true;
        int heat=0, brightness=0, contrast=0, saturation=0;
        Settings copy() {
            Settings s=new Settings(); s.mode=mode;s.linear=linear;
            s.normalize=normalize;s.wb=wb;s.heat=heat;s.brightness=brightness;
            s.contrast=contrast;s.saturation=saturation;return s;
        }
    }
    private static int clip(int n) {return n<0?0:Math.min(n,255);}
    private static float clip01(float n) {return Math.max(0,Math.min(n,1));}
    private static final float[] DECODE=new float[256];
    private static final int[] ENCODE=new int[4097];
    static {
        for(int i=0;i<256;i++) {
            double v=i/255.0;
            DECODE[i]=(float)(v<=0.04045?v/12.92:Math.pow((v+.055)/1.055,2.4));
        }
        for(int i=0;i<=4096;i++) {
            double v=i/4096.0;
            ENCODE[i]=clip((int)Math.round(255*(v<=.0031308?v*12.92:1.055*Math.pow(v,1/2.4)-.055)));
        }
    }
    private static int encode(float n){return ENCODE[Math.min(4096,Math.max(0,Math.round(n*4096)))];}
    private static double sinc(double x){return Math.abs(x)<1e-8?1:Math.sin(Math.PI*x)/(Math.PI*x);}
    private static float kernel(double x,Mode mode){
        x=Math.abs(x);
        if(mode==Mode.STANDARD) {
            double b=1.0/3,c=1.0/3;
            if(x<1)return (float)(((12-9*b-6*c)*x*x*x+(-18+12*b+6*c)*x*x+6-2*b)/6);
            if(x<2)return (float)(((-b-6*c)*x*x*x+(6*b+30*c)*x*x+(-12*b-48*c)*x+8*b+24*c)/6);
            return 0;
        }
        int radius=mode==Mode.NATURAL?2:3;
        return x<radius?(float)(sinc(x)*sinc(x/radius)):0;
    }
    private static final class Taps {
        int[][] index;
        float[][] weight;
        Taps(int src,int dst,Mode mode){
            index=new int[dst][]; weight=new float[dst][];
            double scale=(double)src/dst;
            double spread=Math.max(1,scale);
            int radius=mode==Mode.STANDARD||mode==Mode.NATURAL?2:3;
            for(int i=0;i<dst;i++){
                double center=(i+.5)*scale-.5;
                int start=(int)Math.ceil(center-radius*spread);
                int finish=(int)Math.floor(center+radius*spread);
                int count=finish-start+1;
                index[i]=new int[count];weight[i]=new float[count];
                double total=0;
                for(int k=0;k<count;k++){
                    int pos=start+k;
                    index[i][k]=Math.max(0,Math.min(src-1,pos));
                    weight[i][k]=kernel((pos-center)/spread,mode);
                    total+=weight[i][k];
                }
                if(Math.abs(total)<1e-10) {
                    Arrays.fill(weight[i],0);weight[i][count/2]=1;
                }else{
                    for(int k=0;k<count;k++)weight[i][k]/=(float)total;
                }
            }
        }
    }
    private static final class AutoStats {
        float gr=1,gb=1,lo=0,hi=1,strength=0;
        AutoStats(Bitmap source,Settings s){
            if(!s.normalize&&!s.wb)return;
            int w=source.getWidth(),h=source.getHeight();
            int skip=Math.max(1,(int)Math.sqrt(w*(long)h/30000.0));
            int[] row=new int[w],hist=new int[256];
            double red=0,green=0,blue=0,weight=0;
            int samples=0;
            for(int y=0;y<h;y+=skip){
                source.getPixels(row,0,w,0,y,w,1);
                for(int x=0;x<w;x+=skip){
                    int p=row[x];int r=Color.red(p),g=Color.green(p),b=Color.blue(p);
                    int l=clip(Math.round(.2126f*r+.7152f*g+.0722f*b));
                    hist[l]++;samples++;
                    int range=Math.max(r,Math.max(g,b))-Math.min(r,Math.min(g,b));
                    if(l>38&&l<230&&range<38){
                        double wt=1-range/48.0;
                        red+=r*wt;green+=g*wt;blue+=b*wt;weight+=wt;
                    }
                }
            }
            if(s.wb&&weight>150){
                gr=(float)Math.max(.92,Math.min(1.08,green/Math.max(1,red)));
                gb=(float)Math.max(.92,Math.min(1.08,green/Math.max(1,blue)));
            }
            if(s.normalize&&samples>100){
                int acc=0,low=0,high=255;
                for(int i=0;i<256;i++){acc+=hist[i];if(acc>=samples*.01){low=i;break;}}
                acc=0;
                for(int i=0;i<256;i++){acc+=hist[i];if(acc>=samples*.99){high=i;break;}}
                lo=low/255f;hi=high/255f;
                float range=hi-lo;
                strength=range<.05f?0:range<.65f?.38f:range<.84f?.30f:range<.94f?.19f:.08f;
            }
        }
    }
    static Bitmap render(Bitmap source,int dw,int dh,Settings s){
        int sw=source.getWidth(),sh=source.getHeight();
        if(dw<1||dh<1||dw>4096||dh>4096||dw*(long)dh>17500000L)
            throw new IllegalArgumentException("Image dimensions unsupported");
        AutoStats stats=new AutoStats(source,s);
        int[] dest=new int[dw*dh];
        if(dw==sw&&dh==sh) {
            source.getPixels(dest,0,dw,0,0,dw,dh);
            for(int i=0;i<dest.length;i++){
                if((i&16383)==0&&Thread.currentThread().isInterrupted())
                    throw new RuntimeException("Cancelled");
                int p=dest[i];
                dest[i]=(p&0xff000000)|adjust(Color.red(p),Color.green(p),Color.blue(p),s,stats);
            }
        } else {
            Taps xt=new Taps(sw,dw,s.mode),yt=new Taps(sh,dh,s.mode);
            final int maxRows=Math.max(32,yt.index[0].length+8);
            LinkedHashMap<Integer,float[]> cache=new LinkedHashMap<Integer,float[]>(64,.75f,true) {
                protected boolean removeEldestEntry(Map.Entry<Integer,float[]> e){return size()>maxRows;}
            };
            int[] scan=new int[sw];
            for(int y=0;y<dh;y++){
                if(Thread.currentThread().isInterrupted())throw new RuntimeException("Cancelled");
                for(int row:yt.index[y]){
                    if(cache.containsKey(row))continue;
                    source.getPixels(scan,0,sw,0,row,sw,1);
                    float[] line=new float[dw*3];
                    for(int x=0;x<dw;x++){
                        float r=0,g=0,b=0;
                        for(int k=0;k<xt.index[x].length;k++){
                            int p=scan[xt.index[x][k]];
                            float a=xt.weight[x][k];
                            r+=a*(s.linear?DECODE[Color.red(p)]:Color.red(p)/255f);
                            g+=a*(s.linear?DECODE[Color.green(p)]:Color.green(p)/255f);
                            b+=a*(s.linear?DECODE[Color.blue(p)]:Color.blue(p)/255f);
                        }
                        int o=x*3;line[o]=r;line[o+1]=g;line[o+2]=b;
                    }
                    cache.put(row,line);
                }
                for(int x=0;x<dw;x++){
                    float r=0,g=0,b=0;int idx=x*3;
                    for(int k=0;k<yt.index[y].length;k++){
                        float[] row=cache.get(yt.index[y][k]); float a=yt.weight[y][k];
                        r+=a*row[idx];g+=a*row[idx+1];b+=a*row[idx+2];
                    }
                    int ri=s.linear?encode(clip01(r)):clip(Math.round(r*255));
                    int gi=s.linear?encode(clip01(g)):clip(Math.round(g*255));
                    int bi=s.linear?encode(clip01(b)):clip(Math.round(b*255));
                    dest[y*dw+x]=0xff000000|adjust(ri,gi,bi,s,stats);
                }
            }
        }
        // These modes must refine the saved bitmap as well as the reduced
        // preview. Without this pass native-resolution JPEG/PNG exports
        // previously bypassed nearly all mode-specific reconstruction.
        if(s.mode==Mode.NATURAL)refineDetail(dest,dw,dh,.36f,11,32);
        else if(s.mode==Mode.CLEAR)refineDetail(dest,dw,dh,.90f,22,54);
        return Bitmap.createBitmap(dest,dw,dh,Bitmap.Config.ARGB_8888);
    }
    private static int adjust(int r,int g,int b,Settings s,AutoStats a) {
        float rf=r/255f*a.gr,gf=g/255f,bf=b/255f*a.gb;
        float warm=s.heat/100f*.16f;rf*=1+warm;bf*=1-warm;
        float exposure=s.brightness/100f*.24f;
        rf+=exposure;gf+=exposure;bf+=exposure;
        float contrast=1+s.contrast/100f*.5f;
        rf=(rf-.5f)*contrast+.5f;gf=(gf-.5f)*contrast+.5f;bf=(bf-.5f)*contrast+.5f;
        if(a.strength>0){
            float lum=.2126f*rf+.7152f*gf+.0722f*bf;
            float target=(lum-a.lo)*(.94f/Math.max(.05f,a.hi-a.lo))+.03f;
            float d=(target-lum)*a.strength;rf+=d;gf+=d;bf+=d;
        }
        float lum=.2126f*rf+.7152f*gf+.0722f*bf;
        float sat=1+s.saturation/100f;
        rf=lum+(rf-lum)*sat;gf=lum+(gf-lum)*sat;bf=lum+(bf-lum)*sat;
        return (clip(Math.round(rf*255))<<16)|(clip(Math.round(gf*255))<<8)|clip(Math.round(bf*255));
    }
    /**
     * Luminance-only 3x3 edge-aware reconstruction.
     * Suppresses noise amplification in nearly flat areas; clips extreme
     * changes to avoid the colour fringes and ringing of RGB unsharp masks.
     * The output is deterministic for full-resolution saves and previews.
     */
    private static void refineDetail(int[] px,int w,int h,float amount,
                                     int maximumChange,int edgeLimit){
        if(w<3||h<3)return;
        int[] luma=new int[w*h];
        for(int n=0;n<px.length;n++){
            int p=px[n];
            luma[n]=(54*Color.red(p)+183*Color.green(p)+19*Color.blue(p)+128)>>8;
        }
        for(int y=1;y<h-1;y++){
            if((y&15)==0&&Thread.currentThread().isInterrupted())
                throw new RuntimeException("Cancelled");
            int base=y*w;
            for(int x=1;x<w-1;x++){
                int i=base+x;
                int center=luma[i];
                int blurred=(luma[i-w-1]+2*luma[i-w]+luma[i-w+1]
                    +2*luma[i-1]+4*center+2*luma[i+1]
                    +luma[i+w-1]+2*luma[i+w]+luma[i+w+1]+8)>>4;
                int detail=center-blurred;
                int magnitude=Math.abs(detail);
                if(magnitude<3)continue; // Resist amplifying smooth-area noise
                float strength=amount;
                if(magnitude>edgeLimit)
                    strength*=edgeLimit/(float)magnitude;
                int delta=Math.round(detail*strength);
                delta=Math.max(-maximumChange,Math.min(maximumChange,delta));
                int p=px[i];
                int r=Color.red(p),g=Color.green(p),b=Color.blue(p);
                // Preserve highlight/shadow texture without harsh white halos.
                int brightest=Math.max(r,Math.max(g,b));
                int darkest=Math.min(r,Math.min(g,b));
                if(delta>0&&brightest>244)delta=(delta*(255-brightest))/11;
                if(delta<0&&darkest<11)delta=(delta*darkest)/11;
                if(delta==0)continue;
                px[i]=0xff000000|(clip(r+delta)<<16)|
                      (clip(g+delta)<<8)|clip(b+delta);
            }
        }
    }
}
