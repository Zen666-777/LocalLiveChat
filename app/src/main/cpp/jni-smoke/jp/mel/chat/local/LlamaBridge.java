package jp.mel.chat.local;

/** Exercises the production JNI implementation, including UTF-8 and callbacks. */
public final class LlamaBridge {
    static { System.loadLibrary("mel_llm"); }
    interface Progress { void update(int stage, int current, int total, String text); }
    native void begin();
    native void cancel();
    native void unload();
    native void load(String path, int context, int threads, Progress progress);
    native int count(String[] roles, String[] contents);
    native String generate(String[] roles, String[] contents, float temperature, int limit, Progress progress);
    public static void main(String[] args) {
        LlamaBridge bridge = new LlamaBridge();
        bridge.begin();
        int[] loadUpdates = {0};
        bridge.load(args[0], 4096, 2, (stage, current, total, text) -> loadUpdates[0]++);
        if (loadUpdates[0] == 0) throw new AssertionError("missing load progress");
        String[] roles = {"system", "assistant", "user"};
        String[] contents = {"あなたはメル。日本語で短く自然に話してください。", "おかえり。今日はどうだった？", "やっほー。🐾"};
        if (bridge.count(roles, contents) <= 0) throw new AssertionError("JNI tokenization");
        int[] chunks = {0};
        String[] preview = {""};
        String reply = bridge.generate(roles, contents, 0, 48, (stage, current, total, text) -> {
            if (stage == 2 && !text.isEmpty()) { chunks[0]++; preview[0] = text; }
        });
        if (reply.isBlank() || chunks[0] == 0 || !reply.equals(preview[0]) || reply.contains("\ufffd"))
            throw new AssertionError("JNI streaming UTF-8");
        System.out.println("JNI Japanese streamed reply: " + reply);
        try {
            bridge.generate(roles, contents, 0, 48, (stage, current, total, text) -> {
                if (stage == 2 && current > 1) bridge.cancel();
            });
            throw new AssertionError("JNI cancellation ignored");
        } catch (IllegalStateException expected) {
            if (!expected.getMessage().contains("停止")) throw expected;
        }
        bridge.begin();
        try {
            bridge.generate(roles, contents, 0, 8, (stage, current, total, text) -> {
                if (stage == 2 && current > 0) throw new IllegalArgumentException("callback-test");
            });
            throw new AssertionError("callback exception lost");
        } catch (IllegalArgumentException expected) {
            if (!"callback-test".equals(expected.getMessage())) throw expected;
        }
        bridge.begin();
        if (bridge.generate(roles, contents, 0, 8, (s, c, t, text) -> {}).isBlank())
            throw new AssertionError("JNI retry failed");
        bridge.unload();
        System.out.println("JNI loading, progress, UTF-8, cancellation, callback failure and retry passed.");
    }
}
