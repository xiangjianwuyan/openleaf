package org.openleaf.reader;

import com.github.junrar.Archive;
import com.github.junrar.rarfile.FileHeader;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 阶段 2-B（Q3 采纳）CBR/RAR 支持（Android 层，依赖 junrar）。
 *
 * <p>只做两件事：①列出安全的图片条目名（供 ComicFlow.metaFrom 生成页数/封面）；
 * ②把单个条目解压到指定缓存文件。路径穿越防护复用 ComicFlow.safeEntry，
 * 解压字节上限复用 ComicFlow.MAX_ENTRY_BYTES，与 ZIP 口径一致。</p>
 */
final class RarArchive {
    private RarArchive() { }

    /**
     * 识别 RAR 版本并给出明确提示。junrar 7.5.3 仅支持 RAR4；
     * RAR5 魔数为 "Rar!\u001a\u0007\u0001\u0000"，必须明确拒绝而非抛 null。
     */
    private static void rejectUnsupportedVersion(File file) throws IOException {
        byte[] head = new byte[8];
        try (java.io.InputStream in = new java.io.FileInputStream(file)) {
            int n = in.read(head);
            if (n < 7 || head[0] != 'R' || head[1] != 'a' || head[2] != 'r' || head[3] != '!' || head[4] != 0x1a)
                throw new IOException("文件不是有效的 RAR 归档");
            if (head[5] == 0x07 && head[6] == 0x01)
                throw new IOException("暂不支持 RAR5 格式，请用 WinRAR 转为 RAR4 或 CBZ/ZIP");
        }
    }

    /** 列出 RAR 内所有安全图片条目名（自然序由 ComicFlow.imagePages 负责）。 */
    static List<String> imageEntries(File file) throws IOException {
        rejectUnsupportedVersion(file);
        List<String> out = new ArrayList<>();
        try (Archive archive = new Archive(file)) {
            List<FileHeader> headers = archive.getFileHeaders();
            if (headers == null) throw new IOException("RAR 条目列表为空");
            for (FileHeader h : headers) {
                if (h == null || h.isDirectory()) continue;
                String name = h.getFileNameString();
                if (name == null) name = h.getFileNameW();
                if (!org.openleaf.reader.core.ComicFlow.safeEntry(name)) continue;
                if (!org.openleaf.reader.core.ComicFlow.isImage(name)) continue;
                out.add(name.replace('\\', '/'));
                if (out.size() > org.openleaf.reader.core.ComicFlow.MAX_ENTRIES)
                    throw new IOException("RAR 图片条目数超限");
            }
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("RAR 解析失败：" + e.getMessage(), e);
        }
        if (out.isEmpty()) throw new IOException("未找到可用图片页");
        return Collections.unmodifiableList(out);
    }

    /** 把指定条目解压到目标文件，带字节上限（防 RAR bomb）。 */
    static void extract(File rar, String entryName, File target) throws IOException {
        rejectUnsupportedVersion(rar);
        try (Archive archive = new Archive(rar)) {
            List<FileHeader> headers = archive.getFileHeaders();
            if (headers == null) throw new IOException("RAR 条目列表为空");
            FileHeader match = null;
            for (FileHeader h : headers) {
                if (h == null || h.isDirectory()) continue;
                String name = h.getFileNameString();
                if (name == null) name = h.getFileNameW();
                if (name != null && name.replace('\\', '/').equals(entryName)) { match = h; break; }
            }
            if (match == null) throw new IOException("RAR 条目不存在：" + entryName);
            long declared = match.getFullUnpackSize();
            if (declared > org.openleaf.reader.core.ComicFlow.MAX_ENTRY_BYTES)
                throw new IOException("单页超过 64MB");
            try (InputStream in = archive.getInputStream(match);
                 OutputStream out = new FileOutputStream(target)) {
                if (in == null) throw new IOException("无法读取 RAR 条目");
                byte[] buf = new byte[16384];
                int n, total = 0;
                while ((n = in.read(buf)) > 0) {
                    total += n;
                    if (total > org.openleaf.reader.core.ComicFlow.MAX_ENTRY_BYTES)
                        throw new IOException("单页超过 64MB");
                    out.write(buf, 0, n);
                }
            }
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("RAR 解压失败：" + e.getMessage(), e);
        }
    }
}
