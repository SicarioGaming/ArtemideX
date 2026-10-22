#include "vk_api.h"

#include <dlfcn.h>
#include <android/log.h>
#include <atomic>
#include <cstdio>
#include <cstdlib>
#include <mutex>
#include <string>
#include <sys/system_properties.h>

#include <adrenotools/driver.h>

#define LOG_TAG "VulkanRenderer"
#define ALOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)
#define ALOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)
#define ALOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)

namespace {
    // A custom driver is only ever tried on arm64-v8a, where libadrenotools is packaged
    std::mutex g_driverMutex;
    std::string g_driverPath;
    std::string g_hookLibDir;
    std::string g_cacheDir;
    std::atomic<bool> g_customDriverInUse {false};
    std::atomic<bool> g_customDriverFailed {false};

    // libadrenotools only checks that the driver file exists, so catch files that can't be a
    // driver here (not a 64-bit ARM shared library) instead of failing later inside Vulkan
    bool isArm64SharedLibrary(const std::string& path) {
        unsigned char header[20] = {};
        FILE* file = fopen(path.c_str(), "rb");
        if (!file) {
            return false;
        }
        const size_t read = fread(header, 1, sizeof(header), file);
        fclose(file);
        return read == sizeof(header) &&
               header[0] == 0x7F && header[1] == 'E' && header[2] == 'L' && header[3] == 'F' &&
               header[4] == 2 &&                            // ELFCLASS64
               header[16] == 3 && header[17] == 0 &&        // ET_DYN, little-endian
               header[18] == 183 && header[19] == 0;        // EM_AARCH64
    }

#if defined(__aarch64__)
    int deviceApiLevel() {
        char value[PROP_VALUE_MAX] = {};
        if (__system_property_get("ro.build.version.sdk", value) > 0) {
            return atoi(value);
        }
        return 0;
    }
#endif

    // Opens the system libvulkan.so, once per process
    void* openSystemVulkan() {
        static void* lib = dlopen("libvulkan.so", RTLD_NOW | RTLD_LOCAL);
        return lib;
    }

    // Opens the custom driver through libadrenotools, once per process. Never throws: null means
    // the caller should use the system driver, and the codec simply stays unavailable if that
    // fails too, as it would without a driver.
    void* openCustomVulkan() {
        static void* lib = []() -> void* {
            std::lock_guard<std::mutex> lock(g_driverMutex);
            if (g_driverPath.empty()) {
                return nullptr;
            }

#if defined(__aarch64__)
            const int apiLevel = deviceApiLevel();
            if (apiLevel < 29) {
                ALOGW("Custom Vulkan driver %s configured, but this device is API %d (< 29); using the system driver",
                      g_driverPath.c_str(), apiLevel);
                return nullptr;
            }

            if (!isArm64SharedLibrary(g_driverPath)) {
                ALOGE("Custom Vulkan driver %s is not a 64-bit ARM library; using the system driver",
                      g_driverPath.c_str());
                return nullptr;
            }

            void* adrenotools = dlopen("libadrenotools.so", RTLD_NOW | RTLD_LOCAL);
            if (!adrenotools) {
                ALOGE("Custom Vulkan driver %s configured, but libadrenotools.so is missing: %s; using the system driver",
                      g_driverPath.c_str(), dlerror());
                return nullptr;
            }

            auto open = reinterpret_cast<decltype(&adrenotools_open_libvulkan)>(
                    dlsym(adrenotools, "adrenotools_open_libvulkan"));
            if (!open) {
                ALOGE("libadrenotools.so has no adrenotools_open_libvulkan; using the system driver");
                return nullptr;
            }

            // libadrenotools searches customDriverDir for customDriverName: a directory ending
            // in '/' plus the bare file name
            const size_t slash = g_driverPath.find_last_of('/');
            if (slash == std::string::npos) {
                ALOGE("Custom Vulkan driver path %s is not absolute; using the system driver",
                      g_driverPath.c_str());
                return nullptr;
            }
            const std::string driverDir = g_driverPath.substr(0, slash + 1);
            const std::string driverName = g_driverPath.substr(slash + 1);

            void* custom = open(RTLD_NOW | RTLD_LOCAL, ADRENOTOOLS_DRIVER_CUSTOM, g_cacheDir.c_str(),
                                g_hookLibDir.c_str(), driverDir.c_str(), driverName.c_str(), nullptr, nullptr);
            if (custom) {
                ALOGI("Using custom Vulkan driver %s through libadrenotools", g_driverPath.c_str());
                g_customDriverInUse = true;
                return custom;
            }

            ALOGE("libadrenotools could not load custom Vulkan driver %s: %s; using the system driver",
                  g_driverPath.c_str(), dlerror());
            return nullptr;
#else
            ALOGW("Custom Vulkan driver %s configured, but this ABI is not arm64-v8a; using the system driver",
                  g_driverPath.c_str());
            return nullptr;
#endif
        }();
        return lib;
    }
}

void VkApi::setCustomDriver(const char* driverPath, const char* hookLibDir, const char* cacheDir) {
    std::lock_guard<std::mutex> lock(g_driverMutex);
    g_driverPath = driverPath ? driverPath : "";
    g_hookLibDir = hookLibDir ? hookLibDir : "";
    g_cacheDir = cacheDir ? cacheDir : "";
}

bool VkApi::customDriverInUse() {
    return g_customDriverInUse && !g_customDriverFailed;
}

void VkApi::markCustomDriverFailed() {
    g_customDriverFailed = true;
}

bool VkApi::loadGlobal(bool customDriver) {
    // Both loaders stay loaded for the life of the process, and every VkApi for a given use
    // gets the same one, so a probe and the renderer that follows it agree on the driver
    void* lib = customDriver && !g_customDriverFailed ? openCustomVulkan() : nullptr;
    if (!lib) {
        lib = openSystemVulkan();
    }
    if (!lib) {
        ALOGE("libvulkan.so not available");
        return false;
    }

    vkGetInstanceProcAddr = reinterpret_cast<PFN_vkGetInstanceProcAddr>(dlsym(lib, "vkGetInstanceProcAddr"));
    if (!vkGetInstanceProcAddr) {
        ALOGE("vkGetInstanceProcAddr not found");
        return false;
    }

#define VK_LOAD_GLOBAL(name) \
    name = reinterpret_cast<PFN_##name>(vkGetInstanceProcAddr(VK_NULL_HANDLE, #name));
    VK_GLOBAL_FUNCTIONS(VK_LOAD_GLOBAL)
#undef VK_LOAD_GLOBAL

    // vkEnumerateInstanceVersion is missing on Vulkan 1.0 loaders, which we don't support
    if (!vkCreateInstance || !vkEnumerateInstanceExtensionProperties || !vkEnumerateInstanceVersion) {
        ALOGE("Vulkan 1.1 loader not available");
        return false;
    }
    return true;
}

bool VkApi::loadInstance(VkInstance instance) {
    bool ok = true;
#define VK_LOAD_INSTANCE(name) \
    name = reinterpret_cast<PFN_##name>(vkGetInstanceProcAddr(instance, #name)); \
    if (!name) { ALOGE("Missing Vulkan function %s", #name); ok = false; }
    VK_INSTANCE_FUNCTIONS(VK_LOAD_INSTANCE)
#undef VK_LOAD_INSTANCE
    return ok;
}

bool VkApi::loadDevice(VkDevice device) {
    bool ok = true;
#define VK_LOAD_DEVICE(name) \
    name = reinterpret_cast<PFN_##name>(vkGetDeviceProcAddr(device, #name)); \
    if (!name) { ALOGE("Missing Vulkan function %s", #name); ok = false; }
    VK_DEVICE_FUNCTIONS(VK_LOAD_DEVICE)
#undef VK_LOAD_DEVICE

#define VK_LOAD_OPTIONAL(name) \
    name = reinterpret_cast<PFN_##name>(vkGetDeviceProcAddr(device, #name));
    VK_OPTIONAL_DEVICE_FUNCTIONS(VK_LOAD_OPTIONAL)
#undef VK_LOAD_OPTIONAL
    return ok;
}
