package org.openleaf.reader;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.net.Uri;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;

/** Private, bounded JPEG copies; never modifies the image picked by the user. */
final class CoverFiles {
    private static final int MAX_BYTES=12*1024*1024;
    private CoverFiles(){}
    static File file(Context context,String name)throws IOException{
        if(name==null||!name.matches("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}\\.jpg"))
            throw new IOException("封面文件名无效");
        File directory=new File(context.getFilesDir(),"covers");
        File result=new File(directory,name);
        if(!result.getCanonicalPath().startsWith(directory.getCanonicalPath()+File.separator))throw new IOException("封面路径无效");
        return result;
    }
    static String importImage(Context context,Uri uri)throws IOException{
        if(uri==null||!"content".equals(uri.getScheme()))throw new IOException("请通过系统选择器选择图片");
        byte[] bytes;
        try(InputStream in=context.getContentResolver().openInputStream(uri);ByteArrayOutputStream out=new ByteArrayOutputStream()){
            if(in==null)throw new IOException("无法打开封面图片");
            byte[] buffer=new byte[16384];int n;
            while((n=in.read(buffer))!=-1){if(n>MAX_BYTES-out.size())throw new IOException("封面图片不能超过12MiB");out.write(buffer,0,n);}
            bytes=out.toByteArray();
        }
        BitmapFactory.Options options=new BitmapFactory.Options();options.inJustDecodeBounds=true;
        BitmapFactory.decodeByteArray(bytes,0,bytes.length,options);
        if(options.outWidth<1||options.outHeight<1||options.outWidth>16000||options.outHeight>16000
                ||(long)options.outWidth*options.outHeight>24_000_000)throw new IOException("图片损坏或尺寸过大，请选择2400万像素以内的封面");
        options.inJustDecodeBounds=false;options.inSampleSize=1;
        while(Math.max(options.outWidth,options.outHeight)/options.inSampleSize>1280)options.inSampleSize*=2;
        Bitmap bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.length,options);
        if(bitmap==null)throw new IOException("不支持该封面图片");
        String name=UUID.randomUUID()+".jpg";File target=file(context,name);boolean saved=false;
        try{
            File directory=target.getParentFile();
            if(!directory.isDirectory()&&!directory.mkdirs()&&!directory.isDirectory())throw new IOException("无法创建封面目录");
            try(FileOutputStream out=new FileOutputStream(target)){if(!bitmap.compress(Bitmap.CompressFormat.JPEG,88,out))throw new IOException("封面转换失败");}
            saved=true;return name;
        }finally{bitmap.recycle();if(!saved)target.delete();}
    }
    static String importBytes(Context context,byte[] bytes)throws IOException{
        if(bytes==null||bytes.length==0||bytes.length>MAX_BYTES)throw new IOException("封面图片为空或超过12MiB");
        BitmapFactory.Options options=new BitmapFactory.Options();options.inJustDecodeBounds=true;
        BitmapFactory.decodeByteArray(bytes,0,bytes.length,options);
        if(options.outWidth<1||options.outHeight<1||options.outWidth>16000||options.outHeight>16000
                ||(long)options.outWidth*options.outHeight>24_000_000)throw new IOException("封面图片损坏或尺寸过大");
        options.inJustDecodeBounds=false;options.inSampleSize=1;
        while(Math.max(options.outWidth,options.outHeight)/options.inSampleSize>1280)options.inSampleSize*=2;
        Bitmap bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.length,options);
        if(bitmap==null)throw new IOException("不支持该封面图片");
        String name=UUID.randomUUID()+".jpg";File target=file(context,name);boolean saved=false;
        try{
            File directory=target.getParentFile();
            if(!directory.isDirectory()&&!directory.mkdirs()&&!directory.isDirectory())throw new IOException("无法创建封面目录");
            try(FileOutputStream out=new FileOutputStream(target)){if(!bitmap.compress(Bitmap.CompressFormat.JPEG,88,out))throw new IOException("封面转换失败");}
            saved=true;return name;
        }finally{bitmap.recycle();if(!saved)target.delete();}
    }

    static Bitmap preview(Context context,String name,int maxSide)throws IOException{
        File f=file(context,name);
        BitmapFactory.Options options=new BitmapFactory.Options();options.inJustDecodeBounds=true;BitmapFactory.decodeFile(f.getPath(),options);
        if(options.outWidth<1||options.outHeight<1)return null;
        options.inJustDecodeBounds=false;options.inSampleSize=1;
        while(Math.max(options.outWidth,options.outHeight)/options.inSampleSize>maxSide)options.inSampleSize*=2;
        return BitmapFactory.decodeFile(f.getPath(),options);
    }
    static void discard(Context context,String name){if(name==null||name.isEmpty())return;try{file(context,name).delete();}catch(IOException ignored){}}
}
