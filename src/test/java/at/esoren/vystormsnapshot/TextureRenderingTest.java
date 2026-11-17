package at.esoren.vystormsnapshot;

import static org.junit.jupiter.api.Assertions.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.zip.*;
import javax.imageio.ImageIO;
import org.bukkit.block.BlockFace;
import org.bukkit.util.Vector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TextureRenderingTest {
    @TempDir Path temp;
    private static void entry(ZipOutputStream zip,String name,byte[] bytes) throws IOException {
        zip.putNextEntry(new ZipEntry("assets/minecraft/"+name));zip.write(bytes);zip.closeEntry();
    }
    private static void json(ZipOutputStream zip,String name,String value) throws IOException {entry(zip,name,value.getBytes(StandardCharsets.UTF_8));}
    private static byte[] png(BufferedImage image) throws IOException {var bytes=new ByteArrayOutputStream();ImageIO.write(image,"png",bytes);return bytes.toByteArray();}
    @Test void inheritedFacesKeepTextureDetailAndRotatedVariantsSelectTheEndTexture() throws Exception {
        Path pack=temp.resolve("pack.zip");
        BufferedImage image=new BufferedImage(2,2,BufferedImage.TYPE_INT_ARGB);
        image.setRGB(0,0,0xffff0000);image.setRGB(1,0,0xff00ff00);image.setRGB(0,1,0xff0000ff);image.setRGB(1,1,0xffffffff);
        BufferedImage end=new BufferedImage(1,1,BufferedImage.TYPE_INT_ARGB);end.setRGB(0,0,0xffffff00);
        try(var zip=new ZipOutputStream(Files.newOutputStream(pack))) {
            entry(zip,"textures/block/detail.png",png(image));entry(zip,"textures/block/end.png",png(end));
            json(zip,"models/block/base.json","""
                {"elements":[{"faces":{"north":{"texture":"#side"},"south":{"texture":"#side"},"east":{"texture":"#side"},"west":{"texture":"#side"},"up":{"texture":"#end"},"down":{"texture":"#end"}}}]}
                """);
            json(zip,"models/block/test.json","""
                {"parent":"minecraft:block/base","textures":{"side":"minecraft:block/detail","end":"minecraft:block/end"}}
                """);
            json(zip,"models/block/slab.json","""
                {"textures":{"side":"block/detail"},"elements":[{"from":[0,0,0],"to":[16,8,16],"faces":{"south":{"texture":"#side","uv":[0,0,16,16]}}}]}
                """);
            json(zip,"blockstates/test.json","""
                {"variants":{"axis=y":{"model":"block/test"},"axis=z":{"model":"block/test","x":90}}}
                """);
        }
        BlockTextures textures=BlockTextures.load(pack);
        assertEquals(2,textures.size());
        assertEquals(0xff0000,textures.sample("minecraft:test[axis=y]",BlockFace.SOUTH,.1,.9,1,0x123456));
        assertEquals(0xffffff,textures.sample("minecraft:test[axis=y]",BlockFace.SOUTH,.9,.1,1,0x123456));
        assertEquals(0xffff00,textures.sample("minecraft:test[axis=y]",BlockFace.UP,.5,1,.5,0x123456));
        assertEquals(0xffff00,textures.sample("minecraft:test[axis=z]",BlockFace.SOUTH,.5,.5,1,0x123456));
        assertEquals(0x123456,textures.sample("minecraft:unknown",BlockFace.SOUTH,.5,.5,1,0x123456));
        assertEquals(0xff0000,textures.sample("minecraft:slab",BlockFace.SOUTH,.1,.45,1,0x123456));
    }
    @Test void skinsHaveSeparateHeadBodyAndBackAndTransparentOuterLayer() {
        BufferedImage image=new BufferedImage(64,64,BufferedImage.TYPE_INT_ARGB);
        fill(image,8,8,8,8,0xffff0000);fill(image,24,8,8,8,0xff0000ff);fill(image,20,20,8,12,0xff00ff00);
        var skin=new PlayerFigure.Skin(new SurfaceTexture(image),false);
        var figure=new PlayerFigure(skin,new Vector(0,0,0),0,1.8);
        assertEquals(0xff0000,figure.hit(new Vector(0,1.6,3),new Vector(0,0,-1),5).color());
        assertEquals(0x00ff00,figure.hit(new Vector(0,.9,3),new Vector(0,0,-1),5).color());
        assertEquals(0x0000ff,figure.hit(new Vector(0,1.6,-3),new Vector(0,0,1),5).color());
        assertNull(figure.hit(new Vector(2,1.6,3),new Vector(0,0,-1),5));
        assertNull(figure.hit(new Vector(0,1.6,3),new Vector(0,0,-1),1));
        var turned=new PlayerFigure(skin,new Vector(0,0,0),180,1.8);
        assertEquals(0x0000ff,turned.hit(new Vector(0,1.6,3),new Vector(0,0,-1),5).color());
        fill(image,40,8,8,8,0xffffff00);
        var overlay=new PlayerFigure(new PlayerFigure.Skin(new SurfaceTexture(image),false),new Vector(),0,1.8);
        assertEquals(0xffff00,overlay.hit(new Vector(0,1.6,3),new Vector(0,0,-1),5).color());
    }
    @Test void textureDimensionsAndSkinUrlAreBounded() throws Exception {
        assertThrows(IOException.class,()->SurfaceTexture.read(png(new BufferedImage(128,128,BufferedImage.TYPE_INT_RGB)),64));
        String hash="a".repeat(64);
        assertEquals("https://textures.minecraft.net/texture/"+hash,SkinCache.skinUri(new URL("http://textures.minecraft.net/texture/"+hash)).toString());
        for(String value:new String[]{"https://localhost/texture/"+hash,"https://textures.minecraft.net:8080/texture/"+hash,"https://textures.minecraft.net/texture/"+hash+"?redirect=1"})
            assertThrows(IOException.class,()->SkinCache.skinUri(new URL(value)));
    }
    private static void fill(BufferedImage image,int x,int y,int w,int h,int color){for(int xx=x;xx<x+w;xx++)for(int yy=y;yy<y+h;yy++)image.setRGB(xx,yy,color);}
}
