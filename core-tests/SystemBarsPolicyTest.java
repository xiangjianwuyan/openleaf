import org.openleaf.reader.core.SystemBarsPolicy;

/** 状态栏图标策略 core 回归。 */
public final class SystemBarsPolicyTest {
    private static int passed;

    public static void main(String[] args) {
        lightBackgroundsUseDarkIcons();
        darkBackgroundsUseLightIcons();
        System.out.println("SystemBarsPolicyTest: " + passed + " checks passed");
    }

    private static void lightBackgroundsUseDarkIcons() {
        for (int c : new int[]{0xFFF7F5EF, 0xFFF0E7D2, 0xFFE8F0E3, 0xFFFAFAF9, 0xFFFFFFFF})
            yes(SystemBarsPolicy.darkIcons(c), "浅色底应深色图标");
        passed++;
    }

    private static void darkBackgroundsUseLightIcons() {
        for (int c : new int[]{0xFF18221F, 0xFF000000, 0xFF1F3A2A})
            yes(!SystemBarsPolicy.darkIcons(c), "深色底应浅色图标");
        passed++;
    }

    private static void yes(boolean b, String msg) { if (!b) throw new AssertionError(msg); }
}
