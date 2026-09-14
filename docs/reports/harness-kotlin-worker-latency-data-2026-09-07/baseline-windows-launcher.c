/* Trusted JNI launcher. The native broker's extended executable path is valid
 * Win32, but HotSpot 21's JIMAGE canonicalizer rejects its question mark.
 * Load the JVM explicitly from the validated, ordinary absolute runtime path.
 * No child process, shell, ACL change or script execution outside the sandbox. */
#include <windows.h>
#include <jni.h>
#include <stdio.h>
#include <stdlib.h>

static char *utf8(const wchar_t *value) {
    int size = WideCharToMultiByte(CP_UTF8, 0, value, -1, NULL, 0, NULL, NULL);
    if (!size) exit(2);
    char *result = malloc(size);
    if (!result || !WideCharToMultiByte(CP_UTF8, 0, value, -1, result, size, NULL, NULL)) exit(2);
    return result;
}

int wmain(int argc, wchar_t **argv) {
    if (argc != 4) { fputs("Expected runtime, classpath and input path\n", stderr); return 2; }
    /* Invocation paths are passed by HarnessKotlinRunner, never by the script. */
    const wchar_t *runtime = argv[1];
    if (wcslen(runtime) < 3 || wcslen(runtime) >= MAX_PATH - 32 || runtime[1] != L':' || runtime[2] != L'\\') return 2;
    wchar_t bin[MAX_PATH], jvm_path[MAX_PATH];
    swprintf_s(bin, MAX_PATH, L"%s\\bin", runtime);
    swprintf_s(jvm_path, MAX_PATH, L"%s\\bin\\server\\jvm.dll", runtime);
    if (!SetDllDirectoryW(bin)) return 2;
    HMODULE library = LoadLibraryW(jvm_path);
    if (!library) { fprintf(stderr, "JVM load failed: %lu\n", GetLastError()); return 2; }
    typedef jint (JNICALL *create_vm)(JavaVM **, void **, void *);
    create_vm create = (create_vm)GetProcAddress(library, "JNI_CreateJavaVM");
    if (!create) return 2;
    char *home = utf8(runtime), *classpath = utf8(argv[2]);
    char home_option[4096], classpath_option[32768];
    snprintf(home_option, sizeof(home_option), "-Djava.home=%s", home);
    snprintf(classpath_option, sizeof(classpath_option), "-Djava.class.path=%s", classpath);
    JavaVMOption options[] = {
        {"-Xmx384m", NULL}, {"-XX:MaxMetaspaceSize=256m", NULL}, {"-XX:-UsePerfData", NULL},
        {"-Djava.io.tmpdir=.", NULL}, {"-Duser.home=.", NULL},
        {home_option, NULL}, {classpath_option, NULL}
    };
    JavaVMInitArgs init = {JNI_VERSION_1_8, sizeof(options) / sizeof(options[0]), options, JNI_FALSE};
    JavaVM *vm = NULL;
    JNIEnv *env = NULL;
    jint status = create(&vm, (void **)&env, &init);
    free(home); free(classpath);
    if (status != JNI_OK) { fprintf(stderr, "JVM initialization failed: %d\n", status); return 2; }
    int result = 1;
    jclass main_class = (*env)->FindClass(env, "dev/promethe/harness/kotlin/MainKt");
    if (!main_class) goto done;
    jmethodID main_method = (*env)->GetStaticMethodID(env, main_class, "main", "([Ljava/lang/String;)V");
    if (!main_method) goto done;
    jclass string_class = (*env)->FindClass(env, "java/lang/String");
    if (!string_class) goto done;
    jstring input = (*env)->NewString(env, (const jchar *)argv[3], (jsize)wcslen(argv[3]));
    if (!input) goto done;
    jobjectArray arguments = (*env)->NewObjectArray(env, 1, string_class, input);
    if (!arguments) goto done;
    (*env)->CallStaticVoidMethod(env, main_class, main_method, arguments);
    if (!(*env)->ExceptionCheck(env)) result = 0;
done:
    if ((*env)->ExceptionCheck(env)) (*env)->ExceptionDescribe(env);
    (*vm)->DestroyJavaVM(vm);
    return result;
}
