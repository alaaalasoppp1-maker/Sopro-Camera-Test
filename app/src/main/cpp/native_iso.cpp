#include <jni.h>
#include <android/log.h>
#include <linux/usbdevice_fs.h>
#include <sys/ioctl.h>
#include <unistd.h>
#include <errno.h>
#include <cstring>
#include <cstdlib>
#include <vector>
#include <chrono>
#include <thread>

#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, "SoproISO", __VA_ARGS__)

extern "C"
JNIEXPORT jbyteArray JNICALL
Java_com_dtdc_soprotest_MainActivity_nativeReadIso(
        JNIEnv* env, jobject,
        jint fd, jint endpoint, jint packetSize, jint packetCount, jint timeoutMs) {

    if (fd < 0 || packetSize <= 0 || packetCount <= 0) return nullptr;

    size_t urbSize = sizeof(usbdevfs_urb) +
                     sizeof(usbdevfs_iso_packet_desc) * packetCount;
    auto* urb = (usbdevfs_urb*) std::calloc(1, urbSize);
    if (!urb) return nullptr;

    size_t bufSize = (size_t)packetSize * packetCount;
    auto* buf = (unsigned char*) std::malloc(bufSize);
    if (!buf) {
        std::free(urb);
        return nullptr;
    }
    std::memset(buf, 0, bufSize);

    urb->type = USBDEVFS_URB_TYPE_ISO;
    urb->endpoint = endpoint;
    urb->flags = USBDEVFS_URB_ISO_ASAP;
    urb->buffer = buf;
    urb->buffer_length = (int)bufSize;
    urb->number_of_packets = packetCount;

    for (int i = 0; i < packetCount; i++) {
        urb->iso_frame_desc[i].length = packetSize;
        urb->iso_frame_desc[i].actual_length = 0;
        urb->iso_frame_desc[i].status = 0;
    }

    if (ioctl(fd, USBDEVFS_SUBMITURB, urb) < 0) {
        LOGE("SUBMITURB failed errno=%d", errno);
        std::free(buf);
        std::free(urb);
        return nullptr;
    }

    usbdevfs_urb* completed = nullptr;
    auto deadline = std::chrono::steady_clock::now() + std::chrono::milliseconds(timeoutMs);

    while (std::chrono::steady_clock::now() < deadline) {
        if (ioctl(fd, USBDEVFS_REAPURBNDELAY, &completed) == 0 && completed) {
            break;
        }
        if (errno != EAGAIN && errno != EINTR) {
            LOGE("REAPURBNDELAY errno=%d", errno);
            break;
        }
        std::this_thread::sleep_for(std::chrono::milliseconds(2));
    }

    if (!completed) {
        ioctl(fd, USBDEVFS_DISCARDURB, urb);
        std::free(buf);
        std::free(urb);
        return nullptr;
    }

    // Custom container: [u16 length][packet bytes] ...
    size_t total = 0;
    for (int i = 0; i < packetCount; i++) {
        int n = completed->iso_frame_desc[i].actual_length;
        if (completed->iso_frame_desc[i].status == 0 && n > 0)
            total += 2 + n;
    }

    if (total == 0) {
        std::free(buf);
        std::free(urb);
        return nullptr;
    }

    std::vector<unsigned char> out;
    out.reserve(total);

    for (int i = 0; i < packetCount; i++) {
        auto &d = completed->iso_frame_desc[i];
        int n = d.actual_length;
        if (d.status != 0 || n <= 0) continue;
        out.push_back((unsigned char)(n & 0xff));
        out.push_back((unsigned char)((n >> 8) & 0xff));
        unsigned char* p = buf + d.offset;
        out.insert(out.end(), p, p + n);
    }

    jbyteArray arr = env->NewByteArray((jsize)out.size());
    if (arr)
        env->SetByteArrayRegion(arr, 0, (jsize)out.size(),
                                reinterpret_cast<const jbyte*>(out.data()));

    std::free(buf);
    std::free(urb);
    return arr;
}

extern "C"
JNIEXPORT jint JNICALL
Java_com_dtdc_soprotest_MainActivity_nativeLastErrno(JNIEnv*, jobject) {
    return errno;
}
