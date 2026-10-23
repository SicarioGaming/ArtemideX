package com.limelight.nvstream.jni;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.limelight.RepoFiles;

import org.junit.Test;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The C side and the Java side agree: every Java method the native code looks up exists with
 * that exact signature, every call passes as many arguments as that signature takes, and every
 * native method has its C function with matching parameters. A mismatch only shows on a device,
 * as a crash when streaming starts (2fc0b6bf was one). The classes are never initialised here,
 * since that would load the native libraries.
 */
public class JniContractTest {
    private static final String CALLBACKS = "app/src/main/jni/moonlight-core/callbacks.c";

    private static Class<?> load(String name) throws Exception {
        return Class.forName(name, false, JniContractTest.class.getClassLoader());
    }

    private static Class<?> bridge() throws Exception {
        return load("com.limelight.nvstream.jni.MoonBridge");
    }

    // BridgeDrSetupMethod -> "(IIII)I", from the GetStaticMethodID lines
    private static Map<String, String> lookups(String c) {
        Matcher m = Pattern.compile("(\\w+)\\s*=\\s*\\(\\*env\\)->GetStaticMethodID\\(\\s*env,\\s*clazz,\\s*\"(\\w+)\",\\s*\"([^\"]+)\"").matcher(c);
        Map<String, String> found = new HashMap<>();
        while (m.find()) {
            found.put(m.group(1), m.group(2) + m.group(3));
        }
        return found;
    }

    @Test
    public void everyCallbackTheNativeCodeLooksUpExists() throws Exception {
        String c = RepoFiles.read(CALLBACKS);
        Matcher m = Pattern.compile("GetStaticMethodID\\(\\s*env,\\s*clazz,\\s*\"(\\w+)\",\\s*\"([^\"]+)\"").matcher(c);
        Set<String> java = new HashSet<>();
        for (Method method : bridge().getDeclaredMethods()) {
            if (Modifier.isStatic(method.getModifiers())) java.add(method.getName() + descriptor(method));
        }
        Set<String> missing = new TreeSet<>();
        int found = 0;
        while (m.find()) {
            found++;
            if (!java.contains(m.group(1) + m.group(2))) missing.add(m.group(1) + m.group(2));
        }
        assertTrue("no lookups found", found > 10);
        assertEquals("every lookup is assigned to a method ID", found, lookups(c).size());
        assertTrue("native code looks up methods MoonBridge doesn't have: " + missing, missing.isEmpty());
    }

    // The 2fc0b6bf crash: a call that passed one argument fewer than the method takes
    @Test
    public void everyCallbackCallPassesTheRightArguments() throws Exception {
        String c = RepoFiles.read(CALLBACKS);
        Map<String, String> lookups = lookups(c);
        Matcher m = Pattern.compile("->CallStatic(\\w+)Method\\(").matcher(c);
        List<String> wrong = new ArrayList<>();
        int calls = 0;
        while (m.find()) {
            List<String> args = arguments(c, m.end() - 1);
            assertTrue("call at offset " + m.start() + " has no method ID", args.size() >= 3);
            String id = args.get(2);
            String signature = lookups.get(id);
            assertTrue("call uses " + id + ", which isn't looked up", signature != null);
            String desc = signature.substring(signature.indexOf('('));
            int expected = parameterTypes(desc).size();
            int passed = args.size() - 3;
            if (passed != expected) {
                wrong.add(signature + " is called with " + passed + " arguments");
            }
            char ret = desc.charAt(desc.indexOf(')') + 1);
            String kind = m.group(1);
            if (!kind.equals(callKind(ret))) {
                wrong.add(signature + " is called with CallStatic" + kind + "Method");
            }
            calls++;
        }
        assertTrue("only " + calls + " calls found", calls > 15);
        assertTrue(wrong.toString(), wrong.isEmpty());
    }

    @Test
    public void everyMoonBridgeNativeHasItsCFunction() throws Exception {
        String c = RepoFiles.read("app/src/main/jni/moonlight-core/simplejni.c") + RepoFiles.read(CALLBACKS);
        assertNativesMatch(bridge(), c, "Java_com_limelight_nvstream_jni_MoonBridge_");
    }

    @Test
    public void everyVulkanRendererNativeHasItsCFunction() throws Exception {
        String cpp = RepoFiles.read("app/src/main/jni/vulkan/vulkan_bridge.cpp");
        assertNativesMatch(load("com.limelight.binding.video.VulkanRendererBridge"), cpp,
                "Java_com_limelight_binding_video_VulkanRendererBridge_");
    }

    // Each native method has an exported function whose parameters (after JNIEnv and jclass)
    // have the JNI types of the Java parameters, and nothing is exported for a method that's gone
    private static void assertNativesMatch(Class<?> cls, String source, String prefix) {
        Matcher m = Pattern.compile(Pattern.quote(prefix) + "(\\w+)\\s*\\(([^)]*)\\)").matcher(source);
        Map<String, List<String>> exported = new HashMap<>();
        while (m.find()) {
            List<String> types = new ArrayList<>();
            for (String param : m.group(2).split(",")) {
                // "jint cmdId", "JNIEnv* env", "jclass": the type is the first word
                types.add(param.trim().split("[\\s*]+")[0]);
            }
            exported.put(m.group(1), types);
        }

        List<String> wrong = new ArrayList<>();
        Set<String> declared = new HashSet<>();
        int natives = 0;
        for (Method method : cls.getDeclaredMethods()) {
            if (!Modifier.isNative(method.getModifiers())) continue;
            natives++;
            String name = method.getName().replace("_", "_1");
            declared.add(name);
            List<String> types = exported.get(name);
            if (types == null) {
                wrong.add(method.getName() + " has no C function");
                continue;
            }
            List<String> expected = new ArrayList<>();
            expected.add("JNIEnv");
            expected.add(Modifier.isStatic(method.getModifiers()) ? "jclass" : "jobject");
            for (Class<?> p : method.getParameterTypes()) expected.add(jniType(p));
            if (!expected.equals(types)) {
                wrong.add(method.getName() + ": C takes " + types + ", Java passes " + expected);
            }
        }
        for (String name : exported.keySet()) {
            if (!declared.contains(name)) wrong.add(prefix + name + " has no native method");
        }
        assertTrue("no native methods in " + cls.getSimpleName(), natives > 5);
        assertTrue(wrong.toString(), wrong.isEmpty());
    }

    // The comma-separated arguments of the call whose '(' is at open
    private static List<String> arguments(String c, int open) {
        List<String> args = new ArrayList<>();
        int depth = 0;
        StringBuilder arg = new StringBuilder();
        for (int i = open + 1; i < c.length(); i++) {
            char ch = c.charAt(i);
            if (ch == '(') depth++;
            if (ch == ')') {
                if (depth == 0) {
                    args.add(arg.toString().trim());
                    return args;
                }
                depth--;
            }
            if (ch == ',' && depth == 0) {
                args.add(arg.toString().trim());
                arg.setLength(0);
            } else {
                arg.append(ch);
            }
        }
        throw new AssertionError("unclosed call at offset " + open);
    }

    private static List<String> parameterTypes(String descriptor) {
        List<String> types = new ArrayList<>();
        int i = 1;
        while (descriptor.charAt(i) != ')') {
            int start = i;
            while (descriptor.charAt(i) == '[') i++;
            if (descriptor.charAt(i) == 'L') i = descriptor.indexOf(';', i);
            i++;
            types.add(descriptor.substring(start, i));
        }
        return types;
    }

    private static String callKind(char returnType) {
        switch (returnType) {
            case 'V': return "Void";
            case 'I': return "Int";
            case 'J': return "Long";
            case 'Z': return "Boolean";
            case 'B': return "Byte";
            case 'S': return "Short";
            case 'C': return "Char";
            case 'F': return "Float";
            case 'D': return "Double";
            default: return "Object";
        }
    }

    private static String jniType(Class<?> t) {
        if (t == int.class) return "jint";
        if (t == long.class) return "jlong";
        if (t == boolean.class) return "jboolean";
        if (t == byte.class) return "jbyte";
        if (t == short.class) return "jshort";
        if (t == char.class) return "jchar";
        if (t == float.class) return "jfloat";
        if (t == double.class) return "jdouble";
        if (t == String.class) return "jstring";
        if (t.isArray() && t.getComponentType().isPrimitive()) return "j" + t.getComponentType().getName() + "Array";
        if (t.isArray()) return "jobjectArray";
        return "jobject";
    }

    private static String descriptor(Method method) {
        StringBuilder d = new StringBuilder("(");
        for (Class<?> p : method.getParameterTypes()) d.append(type(p));
        return d.append(')').append(type(method.getReturnType())).toString();
    }

    private static String type(Class<?> t) {
        if (t.isArray()) return "[" + type(t.getComponentType());
        if (t == int.class) return "I";
        if (t == long.class) return "J";
        if (t == boolean.class) return "Z";
        if (t == byte.class) return "B";
        if (t == short.class) return "S";
        if (t == char.class) return "C";
        if (t == float.class) return "F";
        if (t == double.class) return "D";
        if (t == void.class) return "V";
        return "L" + t.getName().replace('.', '/') + ";";
    }
}
