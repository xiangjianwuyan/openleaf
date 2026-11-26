import org.openleaf.reader.core.AiAssist;

/** 阶段 3-A AI 旁路 core 回归；无 JUnit、断言始终启用。 */
public final class AiAssistTest {
    private static int passed;

    public static void main(String[] args) {
        privacyGate();
        inputClipping();
        sanitizeOutput();
        parseResponse();
        requestBodyEscaping();
        fallbackAndNotice();
        System.out.println("AiAssistTest: " + passed + " checks passed");
    }

    private static void privacyGate() {
        yes(!AiAssist.shouldRequest(false, "sk-valid", "有内容"), "默认关不发请求");
        yes(!AiAssist.shouldRequest(true, "", "有内容"), "无 key 不发请求");
        yes(!AiAssist.shouldRequest(true, null, "有内容"), "null key 不发请求");
        yes(!AiAssist.shouldRequest(true, " ", "有内容"), "空白 key 不发请求");
        yes(!AiAssist.shouldRequest(true, "sk-valid", ""), "空文本不发请求");
        yes(!AiAssist.shouldRequest(true, "sk-valid", null), "null 文本不发请求");
        yes(AiAssist.shouldRequest(true, "sk-valid", "第一章开头"), "显式开启+有key+有文本才发");
        passed++;
    }

    private static void inputClipping() {
        String s = new String(new char[3000]).replace('\0', 'x');
        String clipped = AiAssist.clipInput(s);
        eq(AiAssist.MAX_INPUT_CHARS, clipped.codePointCount(0, clipped.length()));
        eq("", AiAssist.clipInput(null));
        eq("", AiAssist.clipInput("   "));
        eq("短文本", AiAssist.clipInput("  短文本  "));
        yes(AiAssist.buildPrompt("") == null, "空输入不生成 prompt");
        yes(AiAssist.buildPrompt("有内容").contains("总结"), "prompt 含摘要指令");
        passed++;
    }

    private static void sanitizeOutput() {
        eq("你好", AiAssist.sanitize("你\u0000好\u0007"));
        eq("A\nB", AiAssist.sanitize("A\n\n\nB"));
        yes(AiAssist.sanitize("   ") == null, "全空白返回 null");
        yes(AiAssist.sanitize(null) == null, "null 返回 null");
        String longOut = new String(new char[3000]).replace('\0', 'a');
        eq(AiAssist.MAX_OUTPUT_CHARS, AiAssist.sanitize(longOut).length());
        passed++;
    }

    private static void parseResponse() {
        String json = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"这是摘要\\n第二行\"}}]}";
        eq("这是摘要\n第二行", AiAssist.parseContent(json));
        yes(AiAssist.parseContent("{}") == null, "无 content 返回 null");
        yes(AiAssist.parseContent(null) == null, "null JSON 返回 null");
        eq("引号\"与斜杠\\", AiAssist.parseContent("{\"content\":\"引号\\\"与斜杠\\\\\"}"));
        passed++;
    }

    private static void requestBodyEscaping() {
        String body = AiAssist.requestBody("他说\"你好\"\n下一行");
        yes(body.contains("gpt-4o-mini"), "指定小模型");
        yes(body.contains("\\\"你好\\\""), "引号已转义");
        yes(body.contains("\\n下一行"), "换行已转义");
        yes(body.contains("max_tokens"), "限制输出 token");
        passed++;
    }

    private static void fallbackAndNotice() {
        eq("规则式首句", AiAssist.fallback("规则式首句"));
        yes(AiAssist.PRIVACY_NOTICE.contains("第三方服务"), "告知含第三方");
        yes(AiAssist.PRIVACY_NOTICE.contains("API key"), "告知含 key");
        yes(AiAssist.PRIVACY_NOTICE.contains("关闭后应用完全离线"), "告知明确默认关");
        eq(15000, AiAssist.TIMEOUT_MS);
        passed++;
    }

    private static void yes(boolean b, String msg) { if (!b) throw new AssertionError(msg); }
    private static void eq(Object a, Object b) { if (!java.util.Objects.equals(a, b)) throw new AssertionError("期望 " + a + " 实际 " + b); }
}
