import org.openleaf.reader.core.BookParser;
import java.io.*;
import java.nio.charset.*;
import java.nio.file.*;
import java.util.*;

/** Import validation must accept exactly the TXT files accepted by the reader parser. */
public final class ImportValidationTest {
    private static int passed;
    private static Path dir;
    public static void main(String[] args) throws Exception {
        dir = Files.createTempDirectory("openleaf-import-validation-");
        try {
            if (args.length > 0 && args[0].equals("large")) {
                Path large = dir.resolve("large.txt");
                byte[] line = new byte[81];
                Arrays.fill(line, 0, 80, (byte)'a'); line[80] = '\n';
                try (OutputStream out = Files.newOutputStream(large)) {
                    // 33 MiB 超过全量校验阈值，强制走流式路径；分行避免单行缓冲放大。
                    long written = 0, target = 33L * 1024 * 1024;
                    while (written < target) { out.write(line); written += line.length; }
                }
                if (!"large".equals(BookParser.validateForImport(large.toFile(), "txt", null))) throw new AssertionError();
                System.out.println("PASS 33 MiB streaming TXT validation with -Xmx64m");
                return;
            }
            String[] bodies = {"", " \r\n\u00a0\ufeff", "第一章 开始\r\n内容\r尾声\n结束",
                    "\n````\n第一章 代码\n````\n\nChapter II: End\n正文", repeat("正文😀", 18000),
                    repeat(" ", 25000)+"第一章 标题\n正文", "正文"+repeat(" \n\n", 25000),
                    "正文\r"+repeat(" ",16380)+"\r\n第二章 结尾\n", repeat("\n第1章 标题\n正文\n",2000),
                    repeat("\n第1章 标题\n正文\n",2001)};
            for (String body : bodies) {
                parity(body.getBytes(StandardCharsets.UTF_8));
                parity(withBom(body.getBytes(StandardCharsets.UTF_16LE), 255,254));
                parity(withBom(body.getBytes(StandardCharsets.UTF_16BE), 254,255));
                parity(body.getBytes(Charset.forName("GB18030")));
            }
            parity(new byte[]{(byte)239,(byte)187,(byte)191,(byte)255});
            parity(new byte[]{(byte)255,(byte)254,65});
            parity(new byte[]{(byte)129});
            parity(new byte[]{(byte)239,(byte)187,(byte)191});
            Random random = new Random(4911);
            String[] lines = {"正文", "", "第一章 测试", "    ", "# Title", "```", "尾声", "二、标题", "```  ", repeat("a",21000)};
            for(int i=0;i<80;i++) {
                StringBuilder body = new StringBuilder();
                for(int n=0;n<40;n++) body.append(lines[random.nextInt(lines.length)]).append(n%3==0?"\r\n":"\n");
                parity(body.toString().getBytes(StandardCharsets.UTF_8));
            }
            File file = dir.resolve("cancel.txt").toFile();
            Files.write(file.toPath(), repeat("a", 200000).getBytes(StandardCharsets.UTF_8));
            final int[] calls = {0}; final RuntimeException cancelled = new RuntimeException("cancelled");
            try {
                BookParser.validateForImport(file,"txt",()->{if(++calls[0]==5)throw cancelled;});
                throw new AssertionError("callback ignored");
            } catch(RuntimeException expected) { if(expected != cancelled)throw expected; }
            passed++;
            try(RandomAccessFile out = new RandomAccessFile(file,"rw")){out.setLength(BookParser.MAX_TXT_BYTES+1);}
            try { BookParser.validateForImport(file,"txt",null);throw new AssertionError("size accepted"); }
            catch(IOException expected){if(!expected.getMessage().contains(BookParser.mib(BookParser.MAX_TXT_BYTES)))throw expected;}
            passed++;
            System.out.println("PASS: " + passed + " import validation checks");
        } finally {
            try(java.util.stream.Stream<Path> files=Files.walk(dir)) {
                for(Path path:(Iterable<Path>)files.sorted(Comparator.reverseOrder())::iterator) Files.delete(path);
            }
        }
    }
    private static void parity(byte[] data) throws Exception {
        File file=dir.resolve("fixture.txt").toFile();Files.write(file.toPath(),data);
        String parsed=null,validated=null; IOException parseError=null,validateError=null;
        try {parsed=BookParser.parse(file,"txt").title;} catch(IOException e){parseError=e;}
        try {validated=BookParser.validateForImport(file,"txt",null);} catch(IOException e){validateError=e;}
        if ((parseError==null)!=(validateError==null))throw new AssertionError("acceptance mismatch: "+parseError+" / "+validateError);
        if(parseError==null&&!Objects.equals(parsed,validated))throw new AssertionError("title mismatch");
        if(parseError!=null&&!Objects.equals(parseError.getMessage(),validateError.getMessage()))throw new AssertionError("failure mismatch: "+parseError+" / "+validateError);
        passed++;
    }
    private static String repeat(String text,int n){StringBuilder s=new StringBuilder();for(int i=0;i<n;i++)s.append(text);return s.toString();}
    private static byte[] withBom(byte[] text,int first,int second){byte[] result=new byte[text.length+2];result[0]=(byte)first;result[1]=(byte)second;System.arraycopy(text,0,result,2,text.length);return result;}
}
