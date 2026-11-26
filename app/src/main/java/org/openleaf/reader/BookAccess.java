package org.openleaf.reader;

import android.content.Context;
import android.net.Uri;
import android.os.CancellationSignal;
import android.os.ParcelFileDescriptor;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;

/** Lifetime-scoped source access. Only providers without seek support need an ephemeral cache. */
final class BookAccess implements AutoCloseable {
    final File file;
    final ParcelFileDescriptor descriptor;
    final boolean temporary;
    private boolean closed;

    private BookAccess(File file, ParcelFileDescriptor descriptor, boolean temporary) {
        this.file=file; this.descriptor=descriptor; this.temporary=temporary;
    }
    static BookAccess local(File file) { return new BookAccess(file,null,false); }

    static BookAccess source(Context context, Uri uri, long limit, CancellationSignal cancellation) throws IOException {
        ParcelFileDescriptor fd=null; File cache=null;
        try {
            if(cancellation!=null)cancellation.throwIfCanceled();
            fd=context.getContentResolver().openFileDescriptor(uri,"r",cancellation);
            if(fd==null)throw new IOException("无法打开原文件，请重新授权");
            if(fd.getStatSize()>limit)throw new IOException("原文件超过 "+(limit/1024/1024)+" MiB 上限");
            boolean seekable;
            try { Os.lseek(fd.getFileDescriptor(),0,OsConstants.SEEK_SET); seekable=true; }
            catch(ErrnoException nonseekable) {
                if(nonseekable.errno!=OsConstants.ESPIPE)throw new IOException("无法定位原文件",nonseekable);
                seekable=false;
            }
            if(seekable) {
                File path=new File("/proc/self/fd/"+fd.getFd());
                // A provider may grant the descriptor while denying a new inode open through proc.
                // Probe the exact File API used by ZIP/text consumers before handing it off.
                boolean reopenable=false;
                try(InputStream probe=new java.io.FileInputStream(path)) { reopenable=path.canRead(); }
                catch(IOException | SecurityException deniedReopen) { /* Read the already-authorized FD below. */ }
                if(reopenable) {
                    BookAccess access=new BookAccess(path,fd,false);
                    fd=null; return access;
                }
            }
            cache=File.createTempFile("openleaf-source-",".cache",context.getCacheDir());
            // AutoCloseInputStream owns fd even when a read or a cancellation fails.
            ParcelFileDescriptor inputFd=fd; fd=null;
            try(InputStream input=new ParcelFileDescriptor.AutoCloseInputStream(inputFd);
                    FileOutputStream output=new FileOutputStream(cache)) {
                // 128 KiB blocks: this copy loop only runs for providers without seek support, and
            // each read() is a call into that provider, so bigger blocks mean fewer round trips.
            byte[] buffer=new byte[128*1024];long count=0;
                while(true) {
                    if(cancellation!=null)cancellation.throwIfCanceled();
                    int n=input.read(buffer,0,(int)Math.min(buffer.length,limit-count+1));
                    if(n<0)break;
                    if(n==0){int next=input.read();if(next<0)break;buffer[0]=(byte)next;n=1;}
                    if(cancellation!=null)cancellation.throwIfCanceled();
                    if(n>limit-count)throw new IOException("原文件超过 "+(limit/1024/1024)+" MiB 上限");
                    output.write(buffer,0,n);count+=n;
                }
            }
            fd=ParcelFileDescriptor.open(cache,ParcelFileDescriptor.MODE_READ_ONLY);
            BookAccess access=new BookAccess(cache,fd,true);fd=null;cache=null;return access;
        } catch(SecurityException denied) {
            throw new IOException("原文件读取授权已失效，请在详情页重新选择原文件",denied);
        } catch(java.io.FileNotFoundException missing) {
            throw new IOException("原文件已移动、删除或当前不可用，请重新选择原文件",missing);
        } finally {
            if(fd!=null)fd.close();
            if(cache!=null)cache.delete();
        }
    }
    @Override public void close() throws IOException {
        if(closed)return;closed=true;
        try { if(descriptor!=null)descriptor.close(); }
        finally { if(temporary && file.exists() && !file.delete())throw new IOException("临时阅读缓存清理失败"); }
    }
}
