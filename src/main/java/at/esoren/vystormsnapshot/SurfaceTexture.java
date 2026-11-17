package at.esoren.vystormsnapshot;

import java.awt.image.BufferedImage;
import java.io.*;
import javax.imageio.ImageIO;
import javax.imageio.ImageReader;

/** Immutable nearest-neighbour texture. Image dimensions are checked before decoding. */
final class SurfaceTexture {
    final int width,height;
    private final int[] pixels;
    SurfaceTexture(BufferedImage image) {
        width=image.getWidth();height=image.getHeight();pixels=image.getRGB(0,0,width,height,null,0,width);
    }
    static SurfaceTexture read(byte[] bytes,int maxDimension) throws IOException {
        try(var input=ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers=ImageIO.getImageReaders(input);
            if(!readers.hasNext()) throw new IOException("Unsupported texture image");
            ImageReader reader=readers.next();
            try {
                reader.setInput(input);
                int w=reader.getWidth(0),h=reader.getHeight(0);
                if(w<1 || h<1 || w>maxDimension || h>maxDimension) throw new IOException("Texture dimensions exceed limit");
                return new SurfaceTexture(reader.read(0));
            } finally {reader.dispose();}
        }
    }
    int pixel(int x,int y) {return pixels[Math.clamp(y,0,height-1)*width+Math.clamp(x,0,width-1)];}
    int sample(double u,double v) {return pixel((int)(Math.clamp(u,0,0.999999)*width),(int)(Math.clamp(v,0,0.999999)*Math.min(width,height)));}
    static int composite(int argb,int background,int tint,boolean tinted) {
        int rgb=argb&0xffffff;
        if(tinted) rgb=(((rgb>>16&255)*(tint>>16&255)/255)<<16)|(((rgb>>8&255)*(tint>>8&255)/255)<<8)|((rgb&255)*(tint&255)/255);
        return Optics.blend(background,rgb,(argb>>>24)/255.0);
    }
}
