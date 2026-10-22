package com.arm.aichat.internal;

/** Host test: same class name and native signatures as the Kotlin InferenceEngineImpl. */
public class InferenceEngineImpl {
    native void init(String nativeLibDir);
    native void setSampling(float temp, float topP, float repeat);
    native int load(String path);
    native int prepare();
    native String systemInfo();
    native String benchModel(int pp, int tg, int pl, int nr);
    native int processSystemPrompt(String p);
    native int processUserPrompt(String p, int n);
    native String generateNextToken();
    native void unload();
    native void shutdown();
    native void resetContext();
    native boolean hasChatTemplate();

    String reply(String prompt) {
        resetContext();
        if (processUserPrompt(prompt, 80) != 0) throw new RuntimeException("processUserPrompt failed");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 80; i++) { String t = generateNextToken(); if (t == null) break; sb.append(t); if (sb.indexOf("\n") >= 0) break; }
        String s = sb.toString(); int k = s.indexOf('\n'); return (k >= 0 ? s.substring(0, k) : s).trim();
    }

    public static void main(String[] a) {
        System.load(a[0]);
        InferenceEngineImpl e = new InferenceEngineImpl();
        e.init("/nonexistent");
        e.setSampling(0.7f, 0.9f, 1.15f);   // the call that crashed in 1.0.0
        if (e.load(a[1]) != 0) throw new RuntimeException("load failed");
        if (e.prepare() != 0) throw new RuntimeException("prepare failed");
        System.out.println("hasChatTemplate=" + e.hasChatTemplate());
        String h = "Человек: Привет! Как тебя зовут?";
        String r1 = e.reply(h + "\nИИ:");
        System.out.println(h + "\nИИ: " + r1);
        String h2 = h + "\nИИ: " + r1 + "\nЧеловек: Что ты любишь делать?";
        String r2 = e.reply(h2 + "\nИИ:");
        System.out.println("Человек: Что ты любишь делать?\nИИ: " + r2);
        if (r1.isEmpty() && r2.isEmpty()) throw new RuntimeException("empty generation");
        e.unload(); e.shutdown();
        System.out.println("HOST_JNI_OK");
    }
}
